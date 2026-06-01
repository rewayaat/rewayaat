package com.rewayaat.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.rewayaat.tafsir.TafsirDocument;
import com.rewayaat.tafsir.TafsirIndexManager;
import com.rewayaat.tafsir.TafsirSnippetSanitizer;
import com.rewayaat.tafsir.extractors.*;
import com.rewayaat.tafsir.extractors.HodaAlQuranExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main entry point for extracting tafsir content and indexing to Elasticsearch.
 *
 * Configuration via environment variables:
 * - TAFSIR_EXTRACTOR: Which extractor(s) to run (enlightening-commentary, pooya-yazdi, all)
 * - TAFSIR_INDEX: Elasticsearch index name (default: rewayaat_tafsir)
 * - TAFSIR_SOURCE_DIR: Local cache directory for downloaded sources (default: /tmp/tafsir-sources)
 * - TAFSIR_FETCH_DELAY: Delay between HTTP requests in ms (default: 1000)
 * - TAFSIR_DRY_RUN: If true, skip ES indexing (default: false)
 * - TAFSIR_FORCE: Re-extract even if docs exist (default: false)
 * - TAFSIR_SURAH_FILTER: Comma-separated list of surah numbers to extract (optional)
 *
 * Two-phase workflow:
 * Phase 1 (download): Run with network access to fetch sources to TAFSIR_SOURCE_DIR
 * Phase 2 (extract): Run in container with ES access to parse cached files and index
 */
public final class TafsirExtractionTool {

    private static final Logger LOGGER = LoggerFactory.getLogger(TafsirExtractionTool.class);

    private static final int BATCH_SIZE = readInt("TAFSIR_BATCH_SIZE", 100);
    private static final boolean DRY_RUN = readBool("TAFSIR_DRY_RUN", false);
    private static final String SOURCE_DIR = readString("TAFSIR_SOURCE_DIR", "/tmp/tafsir-sources");
    private static final String EXTRACTOR_NAME = readString("TAFSIR_EXTRACTOR", "enlightening-commentary");
    private static final String INDEX_NAME = readString("TAFSIR_INDEX", "rewayaat_tafsir");
    private static final boolean FORCE = readBool("TAFSIR_FORCE", false);

    // Statistics tracking
    private static final AtomicInteger TOTAL_EXTRACTED = new AtomicInteger(0);
    private static final AtomicInteger TOTAL_INDEXED = new AtomicInteger(0);
    private static final AtomicInteger TOTAL_SKIPPED = new AtomicInteger(0);
    private static final Set<String> PROCESSED_IDS = ConcurrentHashMap.newKeySet();

    private TafsirExtractionTool() {
        // Utility class
    }

    public static void main(String[] args) {
        try {
            LOGGER.info("=".repeat(80));
            LOGGER.info("Tafsir Extraction Tool");
            LOGGER.info("=".repeat(80));
            LOGGER.info("Configuration:");
            LOGGER.info("  Extractor: {}", EXTRACTOR_NAME);
            LOGGER.info("  Index: {}", INDEX_NAME);
            LOGGER.info("  Source Dir: {}", SOURCE_DIR);
            LOGGER.info("  Dry Run: {}", DRY_RUN);
            LOGGER.info("  Force: {}", FORCE);
            LOGGER.info("=".repeat(80));

            List<TafsirExtractor> extractors = createExtractors();
            if (extractors.isEmpty()) {
                LOGGER.error("No extractors configured. Check TAFSIR_EXTRACTOR value.");
                System.exit(1);
            }

            TafsirIndexManager indexManager = new TafsirIndexManager(INDEX_NAME);

            if (!DRY_RUN) {
                indexManager.createIndexIfNotExists();
            }

            for (TafsirExtractor extractor : extractors) {
                runExtractor(extractor, indexManager);
            }

            printSummary();

        } catch (Exception e) {
            LOGGER.error("Extraction failed", e);
            System.exit(1);
        }
    }

    private static List<TafsirExtractor> createExtractors() {
        List<TafsirExtractor> extractors = new ArrayList<>();

        if ("all".equalsIgnoreCase(EXTRACTOR_NAME)) {
            // Add all HTML extractors from al-islam.org
            extractors.add(new EnlighteningCommentaryExtractor());
            extractors.add(new AlMizanExtractor());
            extractors.add(new PooyaYazdiExtractor());
            extractors.add(new DivineLightsExtractor());
            extractors.add(new ImamAskariExtractor());
            extractors.add(new KhomeiniHamdExtractor());
            extractors.add(SingleSurahExtractor.forJinn());
            extractors.add(SingleSurahExtractor.forYusuf());
            extractors.add(SingleSurahExtractor.forKahf());
            extractors.add(SingleSurahExtractor.forMaryam());
            extractors.add(new FatimaZahraExtractor());
            extractors.add(new AlBayanExtractor());
            extractors.add(new QuranicReflectionsExtractor());
            // PDF extractor
            extractors.add(new HubEAliExtractor());

            // Arabic tafsirs from hodaalquran.com
            extractors.add(HodaAlQuranExtractor.amthal());
            extractors.add(HodaAlQuranExtractor.majmaBayan());
            extractors.add(HodaAlQuranExtractor.qummi());
            extractors.add(HodaAlQuranExtractor.khomeini());
            extractors.add(HodaAlQuranExtractor.jawami());
            extractors.add(HodaAlQuranExtractor.tibyan());
            extractors.add(HodaAlQuranExtractor.safi());
            extractors.add(HodaAlQuranExtractor.kanz());
            extractors.add(HodaAlQuranExtractor.noor());
            extractors.add(HodaAlQuranExtractor.ghareeb());
        } else if ("arabic-all".equalsIgnoreCase(EXTRACTOR_NAME) || "arabic".equalsIgnoreCase(EXTRACTOR_NAME)) {
            // Only Arabic tafsirs
            extractors.add(HodaAlQuranExtractor.amthal());
            extractors.add(HodaAlQuranExtractor.majmaBayan());
            extractors.add(HodaAlQuranExtractor.qummi());
            extractors.add(HodaAlQuranExtractor.khomeini());
            extractors.add(HodaAlQuranExtractor.jawami());
            extractors.add(HodaAlQuranExtractor.tibyan());
            extractors.add(HodaAlQuranExtractor.safi());
            extractors.add(HodaAlQuranExtractor.kanz());
            extractors.add(HodaAlQuranExtractor.noor());
            extractors.add(HodaAlQuranExtractor.ghareeb());
        } else {
            String[] names = EXTRACTOR_NAME.split(",");
            for (String name : names) {
                TafsirExtractor extractor = createExtractor(name.trim());
                if (extractor != null) {
                    extractors.add(extractor);
                }
            }
        }

        return extractors;
    }

    private static TafsirExtractor createExtractor(String name) {
        return switch (name.toLowerCase()) {
            // Full Quran tafsirs
            case "enlightening-commentary", "enlightening" -> new EnlighteningCommentaryExtractor();
            case "al-mizan", "mizan" -> new AlMizanExtractor();
            case "pooya-yazdi", "pooya", "mir-ahmad-ali" -> new PooyaYazdiExtractor();
            case "divine-lights", "chinoy" -> new DivineLightsExtractor();
            case "hubeali", "hub-e-ali" -> new HubEAliExtractor();

            // Partial/specific tafsirs
            case "imam-askari", "askari" -> new ImamAskariExtractor();
            case "khomeini-hamd", "khomeini" -> new KhomeiniHamdExtractor();
            case "jinn", "surah-jinn" -> SingleSurahExtractor.forJinn();
            case "yusuf", "surah-yusuf" -> SingleSurahExtractor.forYusuf();
            case "kahf", "surah-kahf" -> SingleSurahExtractor.forKahf();
            case "maryam", "surah-maryam" -> SingleSurahExtractor.forMaryam();
            case "fatima-zahra", "fatima" -> new FatimaZahraExtractor();
            case "al-bayan", "bayan" -> new AlBayanExtractor();
            case "quranic-reflections", "reflections" -> new QuranicReflectionsExtractor();

            // Arabic tafsirs from hodaalquran.com
            case "ar-amthal", "amthal" -> HodaAlQuranExtractor.amthal();
            case "ar-majma-al-bayan", "majma-bayan" -> HodaAlQuranExtractor.majmaBayan();
            case "ar-tafsir-al-qummi", "qummi" -> HodaAlQuranExtractor.qummi();
            case "ar-khomeini-tafsir", "khomeini-arabic" -> HodaAlQuranExtractor.khomeini();
            case "ar-jawami-al-jami", "jawami" -> HodaAlQuranExtractor.jawami();
            case "ar-al-tibyan", "tibyan" -> HodaAlQuranExtractor.tibyan();
            case "ar-tafsir-al-safi", "safi" -> HodaAlQuranExtractor.safi();
            case "ar-kanz-al-daqaiq", "kanz" -> HodaAlQuranExtractor.kanz();
            case "ar-noor-al-thaqalayn", "noor" -> HodaAlQuranExtractor.noor();
            case "ar-ghareeb-al-quran", "ghareeb" -> HodaAlQuranExtractor.ghareeb();
            case "arabic-all", "arabic" -> {
                // Handled in createExtractors() method
                LOGGER.warn("Use 'arabic-all' as TAFSIR_EXTRACTOR value directly, not in comma-separated list");
                yield null;
            }

            default -> {
                LOGGER.warn("Unknown extractor: {}", name);
                yield null;
            }
        };
    }

    private static void runExtractor(TafsirExtractor extractor, TafsirIndexManager indexManager) {
        LOGGER.info("-".repeat(80));
        LOGGER.info("Running extractor: {}", extractor.getTafsirName());
        LOGGER.info("-".repeat(80));

        try {
            List<TafsirDocument> documents = extractor.extract();
            documents.forEach(TafsirSnippetSanitizer::sanitize);
            LOGGER.info("Extracted {} documents from {}", documents.size(), extractor.getTafsirName());
            TOTAL_EXTRACTED.addAndGet(documents.size());

            if (DRY_RUN) {
                LOGGER.info("DRY RUN: Skipping indexing of {} documents", documents.size());
                logSampleDocuments(documents, 3);
                dumpDocumentsToJson(documents, extractor.getTafsirSlug());
                return;
            }

            // Index in batches
            List<TafsirDocument> batch = new ArrayList<>(BATCH_SIZE);
            for (TafsirDocument doc : documents) {
                boolean indexable = TafsirSnippetSanitizer.isIndexable(doc);
                if (!indexable) {
                    LOGGER.info("NOT INDEXABLE: id={}, wordCount={}, isSubstantive={}, textPreview={}",
                            doc.getId(), doc.getCommentaryWordCount(),
                            TafsirSnippetSanitizer.isArabicText(doc.getCommentaryText()),
                            doc.getCommentaryText() != null ? doc.getCommentaryText().substring(0, Math.min(30, doc.getCommentaryText().length())) : "null");
                }
                if (indexable && shouldProcess(doc)) {
                    batch.add(doc);
                    if (batch.size() >= BATCH_SIZE) {
                        indexBatch(batch, indexManager);
                        batch.clear();
                    }
                } else if (!TafsirSnippetSanitizer.isIndexable(doc)) {
                    TOTAL_SKIPPED.incrementAndGet();
                }
            }

            // Index remaining documents
            if (!batch.isEmpty()) {
                indexBatch(batch, indexManager);
            }

        } catch (TafsirExtractor.ExtractionException e) {
            LOGGER.error("Extraction failed for {}", extractor.getTafsirName(), e);
        }
    }

    private static boolean shouldProcess(TafsirDocument doc) {
        if (FORCE) {
            return true;
        }
        // Skip if already processed
        return PROCESSED_IDS.add(doc.getId());
    }

    private static void indexBatch(List<TafsirDocument> batch, TafsirIndexManager indexManager) {
        try {
            TafsirIndexManager.BulkResult result = indexManager.indexDocuments(batch);

            TOTAL_INDEXED.addAndGet(result.created());
            TOTAL_SKIPPED.addAndGet(batch.size() - result.created());

            if (!result.isSuccess()) {
                LOGGER.warn("Batch had {} errors out of {}", result.errors(), result.total());
                for (String error : result.errorMessages()) {
                    LOGGER.debug("  {}", error);
                }
            }

            LOGGER.info("Indexed {} documents (total: {})", result.created(), TOTAL_INDEXED.get());

        } catch (Exception e) {
            LOGGER.error("Failed to index batch of {} documents", batch.size(), e);
        }
    }

    private static void logSampleDocuments(List<TafsirDocument> documents, int count) {
        int samples = Math.min(count, documents.size());
        LOGGER.info("Sample documents (first {} of {}):", samples, documents.size());

        for (int i = 0; i < samples; i++) {
            TafsirDocument doc = documents.get(i);
            LOGGER.info("  [{}] {}: {} - {} words",
                    doc.getId(),
                    doc.getVerseKey(),
                    doc.getSectionTitle() != null ? doc.getSectionTitle() : "No title",
                    doc.getCommentaryWordCount());
        }
    }

    private static final String JSON_DUMP_DIR = readString("TAFSIR_JSON_DUMP_DIR", "tmp/tafsir-json");

    private static void dumpDocumentsToJson(List<TafsirDocument> documents, String slug) {
        try {
            java.nio.file.Path dumpDir = java.nio.file.Path.of(JSON_DUMP_DIR);
            java.nio.file.Files.createDirectories(dumpDir);
            java.nio.file.Path dumpFile = dumpDir.resolve(slug + ".json");

            ObjectMapper mapper = new ObjectMapper()
                    .enable(SerializationFeature.INDENT_OUTPUT);

            // Filter to indexable docs and include their computed ID
            List<java.util.Map<String, Object>> exportDocs = new ArrayList<>();
            for (TafsirDocument doc : documents) {
                if (!TafsirSnippetSanitizer.isIndexable(doc)) {
                    continue;
                }
                java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
                map.put("_id", doc.getId());
                map.put("tafsir_slug", doc.getTafsirSlug());
                map.put("tafsir_name", doc.getTafsirName());
                map.put("surah_number", doc.getSurahNumber());
                map.put("ayah_start", doc.getAyahStart());
                map.put("ayah_end", doc.getAyahEnd());
                map.put("verse_key", doc.getVerseKey());
                map.put("verse_keys", doc.getVerseKeys());
                map.put("commentary_text", doc.getCommentaryText());
                map.put("commentary_text_arabic", doc.getCommentaryTextArabic());
                map.put("commentary_text_english", doc.getCommentaryTextEnglish());
                map.put("section_title", doc.getSectionTitle());
                map.put("commentary_word_count", doc.getCommentaryWordCount());
                map.put("volume", doc.getVolume());
                map.put("source_url", doc.getSourceUrl());
                map.put("language", doc.getLanguage());
                exportDocs.add(map);
            }

            mapper.writeValue(dumpFile.toFile(), exportDocs);
            LOGGER.info("Dumped {} indexable documents to {}", exportDocs.size(), dumpFile);
        } catch (Exception e) {
            LOGGER.error("Failed to dump documents to JSON", e);
        }
    }

    private static void printSummary() {
        LOGGER.info("=".repeat(80));
        LOGGER.info("Extraction Summary");
        LOGGER.info("=".repeat(80));
        LOGGER.info("Total Extracted: {}", TOTAL_EXTRACTED.get());
        LOGGER.info("Total Indexed:    {}", TOTAL_INDEXED.get());
        LOGGER.info("Total Skipped:    {}", TOTAL_SKIPPED.get());
        LOGGER.info("Dry Run:          {}", DRY_RUN);
        LOGGER.info("=".repeat(80));

        if (TOTAL_INDEXED.get() == 0 && !DRY_RUN) {
            LOGGER.warn("No documents were indexed. Check logs for errors.");
        }
    }

    // Configuration helpers

    private static String readString(String key, String defaultValue) {
        String value = System.getProperty(key.toLowerCase().replace('_', '.'));
        if (value == null || value.isEmpty()) {
            value = System.getenv(key);
        }
        return (value != null && !value.isEmpty()) ? value : defaultValue;
    }

    private static int readInt(String key, int defaultValue) {
        String value = readString(key, null);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                LOGGER.warn("Invalid integer value for {}: {}", key, value);
            }
        }
        return defaultValue;
    }

    private static boolean readBool(String key, boolean defaultValue) {
        String value = readString(key, null);
        if (value != null) {
            return Boolean.parseBoolean(value);
        }
        return defaultValue;
    }
}
