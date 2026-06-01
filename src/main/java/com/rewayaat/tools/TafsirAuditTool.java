package com.rewayaat.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.rewayaat.tafsir.TafsirDocument;
import com.rewayaat.tafsir.TafsirIndexManager;
import com.rewayaat.tafsir.TafsirSnippetSanitizer;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.rewayaat.config.ESClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Standalone tool that audits tafsir documents in Elasticsearch.
 *
 * Validates verse key consistency, indexability, word count accuracy,
 * and detects duplicates. Outputs a JSON report.
 *
 * Configuration via environment variables:
 * - TAFSIR_INDEX: Elasticsearch index name (default: rewayaat_tafsir)
 * - TAFSIR_AUDIT_SAMPLE_SIZE: Number of docs to sample (default: 100, 0 = all)
 * - TAFSIR_AUDIT_OUTPUT: Output file path (default: stdout)
 */
public final class TafsirAuditTool {

    private static final Logger LOGGER = LoggerFactory.getLogger(TafsirAuditTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final String INDEX_NAME = readString("TAFSIR_INDEX", "rewayaat_tafsir");
    private static final int SAMPLE_SIZE = readInt("TAFSIR_AUDIT_SAMPLE_SIZE", 100);
    private static final String OUTPUT_PATH = readString("TAFSIR_AUDIT_OUTPUT", "");

    private TafsirAuditTool() {}

    public static void main(String[] args) {
        try {
            LOGGER.info("Tafsir Audit Tool");
            LOGGER.info("  Index: {}", INDEX_NAME);
            LOGGER.info("  Sample size: {} (0 = all)", SAMPLE_SIZE);

            ElasticsearchClient client = new ESClientProvider().client();

            List<TafsirDocument> documents = sampleDocuments(client);
            LOGGER.info("Sampled {} documents", documents.size());

            AuditReport report = runAudit(documents);

            String json = MAPPER.writeValueAsString(report);
            if (OUTPUT_PATH.isBlank()) {
                System.out.println(json);
            } else {
                Files.writeString(Path.of(OUTPUT_PATH), json);
                LOGGER.info("Report written to {}", OUTPUT_PATH);
            }

            LOGGER.info("Audit complete: {} pass, {} fail, {} warning out of {}",
                    report.passCount, report.failCount, report.warningCount, report.totalSampled);

            if (report.failCount > 0) {
                System.exit(1);
            }

        } catch (Exception e) {
            LOGGER.error("Audit failed", e);
            System.exit(2);
        }
    }

    private static List<TafsirDocument> sampleDocuments(ElasticsearchClient client) throws IOException {
        List<TafsirDocument> documents = new ArrayList<>();

        int size = SAMPLE_SIZE > 0 ? Math.min(SAMPLE_SIZE, 10000) : 10000;

        SearchResponse<TafsirDocument> response = client.search(s -> s
                        .index(INDEX_NAME)
                        .size(size)
                        .query(Query.of(q -> q.matchAll(m -> m))),
                TafsirDocument.class);

        for (Hit<TafsirDocument> hit : response.hits().hits()) {
            TafsirDocument doc = hit.source();
            if (doc != null) {
                doc.setDocumentId(hit.id());
                documents.add(doc);
            }
        }

        return documents;
    }

    static AuditReport runAudit(List<TafsirDocument> documents) {
        List<AuditFinding> findings = new ArrayList<>();
        int passCount = 0;
        int failCount = 0;
        int warningCount = 0;
        Set<String> seenIds = new HashSet<>();

        for (TafsirDocument doc : documents) {
            String docId = doc.getId();
            List<AuditFinding> docFindings = new ArrayList<>();

            // Check 1: verseKey consistency
            if (doc.getSurahNumber() != null && doc.getAyahStart() != null) {
                String expectedKey = doc.getSurahNumber() + ":" + doc.getAyahStart();
                if (!expectedKey.equals(doc.getVerseKey())) {
                    docFindings.add(new AuditFinding(
                            docId, doc.getVerseKey(), CheckType.VERSE_KEY_CONSISTENCY,
                            Result.FAIL, "verseKey '" + doc.getVerseKey() + "' does not match surahNumber:ayahStart '" + expectedKey + "'"
                    ));
                }
            }

            // Check 2: verseKeys coverage
            if (doc.getAyahStart() != null && doc.getAyahEnd() != null && doc.getVerseKeys() != null) {
                int expectedCount = doc.getAyahEnd() - doc.getAyahStart() + 1;
                if (doc.getVerseKeys().size() != expectedCount) {
                    docFindings.add(new AuditFinding(
                            docId, doc.getVerseKey(), CheckType.VERSE_KEYS_COVERAGE,
                            Result.WARNING, "verseKeys has " + doc.getVerseKeys().size() + " entries but range covers " + expectedCount + " verses"
                    ));
                }
            }

            // Check 3: Indexability
            boolean indexable = TafsirSnippetSanitizer.isIndexable(doc);
            if (!indexable) {
                docFindings.add(new AuditFinding(
                        docId, doc.getVerseKey(), CheckType.INDEXABILITY,
                            Result.WARNING, "Document fails isIndexable check (wordCount=" + doc.getCommentaryWordCount() + ")"
                ));
            }

            // Check 4: Word count accuracy
            if (doc.getCommentaryText() != null) {
                int actualCount = doc.getCommentaryText().trim().split("\\s+").length;
                int storedCount = doc.getCommentaryWordCount() != null ? doc.getCommentaryWordCount() : 0;
                int diff = Math.abs(actualCount - storedCount);
                if (diff > Math.max(5, storedCount * 0.1)) {
                    docFindings.add(new AuditFinding(
                            docId, doc.getVerseKey(), CheckType.WORD_COUNT_ACCURACY,
                            Result.WARNING, "Stored wordCount=" + storedCount + " but actual count=" + actualCount + " (diff=" + diff + ")"
                    ));
                }
            }

            // Check 5: Duplicate detection
            if (docId != null) {
                if (seenIds.contains(docId)) {
                    docFindings.add(new AuditFinding(
                            docId, doc.getVerseKey(), CheckType.DUPLICATE_DETECTION,
                            Result.FAIL, "Duplicate document ID detected"
                    ));
                }
                seenIds.add(docId);
            }

            // Tally results for this doc
            if (docFindings.isEmpty()) {
                passCount++;
            } else {
                boolean hasFail = docFindings.stream().anyMatch(f -> f.result == Result.FAIL);
                if (hasFail) {
                    failCount++;
                } else {
                    warningCount++;
                }
            }
            findings.addAll(docFindings);
        }

        return new AuditReport(documents.size(), passCount, failCount, warningCount, findings);
    }

    // Data classes

    enum CheckType {
        VERSE_KEY_CONSISTENCY, VERSE_KEYS_COVERAGE, INDEXABILITY,
        WORD_COUNT_ACCURACY, DUPLICATE_DETECTION
    }

    enum Result {
        PASS, FAIL, WARNING
    }

    record AuditReport(
            int totalSampled,
            int passCount,
            int failCount,
            int warningCount,
            List<AuditFinding> findings
    ) {}

    record AuditFinding(
            String documentId,
            String verseKey,
            CheckType checkType,
            Result result,
            String message
    ) {}

    // Config helpers

    private static String readString(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isEmpty()) ? value : defaultValue;
    }

    private static int readInt(String key, int defaultValue) {
        String value = System.getenv(key);
        if (value != null && !value.isEmpty()) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}
