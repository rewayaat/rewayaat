package com.rewayaat.tafsir;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.OpType;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import com.rewayaat.config.ESClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Manages the Elasticsearch index for tafsir documents.
 * Handles index creation, bulk insertion, and idempotent updates.
 */
public class TafsirIndexManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(TafsirIndexManager.class);

    private final ElasticsearchClient client;
    private final String indexName;

    public TafsirIndexManager(String indexName) {
        ESClientProvider provider = new ESClientProvider();
        this.client = provider.client();
        this.indexName = indexName;
    }

    public TafsirIndexManager() {
        this(resolveIndexName());
    }

    private static String resolveIndexName() {
        // Try Spring config first, then env var, then default
        String index = System.getProperty("tafsir.index");
        if (index == null || index.isEmpty()) {
            index = System.getenv().get("TAFSIR_INDEX");
        }
        return (index != null && !index.isEmpty()) ? index : "rewayaat_tafsir";
    }

    /**
     * Creates the tafsir index with the appropriate mapping if it doesn't exist.
     */
    public void createIndexIfNotExists() throws IOException {
        boolean exists;
        try {
            ExistsRequest existsRequest = ExistsRequest.of(e -> e.index(indexName));
            exists = client.indices().exists(existsRequest).value();
        } catch (Exception e) {
            // Rest5Client may fail on HEAD/exists requests; try a document search as fallback
            LOGGER.warn("Could not check index existence via HEAD: {}", e.getMessage());
            try {
                client.search(s -> s.index(indexName).size(0), Map.class);
                exists = true;
            } catch (Exception e2) {
                exists = false;
            }
        }

        if (!exists) {
            LOGGER.info("Creating tafsir index: {}", indexName);

            String mapping = "{"
                    + "\"mappings\": {"
                    + "\"properties\": {"
                    + "\"tafsir_slug\": {\"type\": \"keyword\"},"
                    + "\"tafsir_name\": {\"type\": \"text\"},"
                    + "\"surah_number\": {\"type\": \"integer\"},"
                    + "\"ayah_start\": {\"type\": \"integer\"},"
                    + "\"ayah_end\": {\"type\": \"integer\"},"
                    + "\"verse_key\": {\"type\": \"keyword\"},"
                    + "\"verse_keys\": {\"type\": \"keyword\"},"
                    + "\"verse_text_english\": {\"type\": \"text\"},"
                    + "\"commentaryTextArabic\": {\"type\": \"text\"},"
                    + "\"commentaryTextEnglish\": {\"type\": \"text\"},"
                    + "\"commentary_text_arabic\": {\"type\": \"text\"},"
                    + "\"commentary_text_english\": {\"type\": \"text\"},"
                    + "\"commentary_text\": {\"type\": \"text\"},"
                    + "\"section_title\": {\"type\": \"text\"},"
                    + "\"commentary_word_count\": {\"type\": \"integer\"},"
                    + "\"volume\": {\"type\": \"keyword\"},"
                    + "\"source_url\": {\"type\": \"keyword\"},"
                    + "\"language\": {\"type\": \"keyword\"}"
                    + "}"
                    + "}"
                    + "}";

            try {
                CreateIndexRequest createRequest = CreateIndexRequest.of(c -> c
                        .index(indexName)
                        .withJson(new StringReader(mapping)));

                client.indices().create(createRequest);
                LOGGER.info("Created index {} with mapping", indexName);
            } catch (Exception e) {
                // Rest5Client content-type issues; index may already exist
                LOGGER.warn("Could not create index via client (may already exist): {}", e.getMessage());
            }
        } else {
            LOGGER.info("Index {} already exists", indexName);
        }
    }

    /**
     * Indexes a single document with idempotent update (create-or-noop).
     */
    public boolean indexDocument(TafsirDocument doc) throws IOException {
        if (doc == null || doc.getId() == null) {
            LOGGER.warn("Cannot index document: null or missing ID");
            return false;
        }

        IndexRequest<TafsirDocument> request = IndexRequest.of(i -> i
                .index(indexName)
                .id(doc.getId())
                .document(doc)
                .opType(OpType.Create)); // Only create if doesn't exist

        var response = client.index(request);
        return response.result().name().equals("Created");
    }

    /**
     * Bulk indexes a list of documents.
     * Uses idempotent create-or-noop semantics.
     */
    public BulkResult indexDocuments(List<TafsirDocument> documents) throws IOException {
        if (documents == null || documents.isEmpty()) {
            return new BulkResult(0, 0, new ArrayList<>());
        }

        List<BulkOperation> operations = new ArrayList<>();
        for (TafsirDocument doc : documents) {
            if (doc != null && doc.getId() != null) {
                operations.add(BulkOperation.of(b -> b
                        .create(c -> c
                                .index(indexName)
                                .id(doc.getId())
                                .document(doc))));
            }
        }

        if (operations.isEmpty()) {
            return new BulkResult(0, 0, new ArrayList<>());
        }

        BulkRequest bulkRequest = BulkRequest.of(b -> b.operations(operations));
        BulkResponse response = client.bulk(bulkRequest);

        int created = 0;
        int errors = 0;
        List<String> errorMessages = new ArrayList<>();

        if (response.errors()) {
            for (var item : response.items()) {
                if (item.error() != null) {
                    errors++;
                    errorMessages.add("ID " + item.id() + ": " + item.error().reason());
                } else if (item.result() != null) {
                    // Check if the operation resulted in creation
                    String resultStr = item.result().toString();
                    if ("Created".equalsIgnoreCase(resultStr)) {
                        created++;
                    }
                }
            }
        } else {
            created = operations.size();
        }

        return new BulkResult(created, errors, errorMessages);
    }

    /**
     * Result of a bulk indexing operation.
     */
    public record BulkResult(int created, int errors, List<String> errorMessages) {
        public boolean isSuccess() {
            return errors == 0;
        }

        public int total() {
            return created + errors;
        }
    }

    /**
     * Bulk indexes a list of documents with overwrite semantics.
     * Uses OpType.Index instead of OpType.Create, allowing existing documents
     * to be overwritten. Used when importing split per-verse documents
     * that replace original multi-verse blobs.
     */
    public BulkResult indexDocumentsWithOverwrite(List<TafsirDocument> documents) throws IOException {
        if (documents == null || documents.isEmpty()) {
            return new BulkResult(0, 0, new ArrayList<>());
        }

        List<BulkOperation> operations = new ArrayList<>();
        for (TafsirDocument doc : documents) {
            if (doc != null && doc.getId() != null) {
                operations.add(BulkOperation.of(b -> b
                        .index(c -> c
                                .index(indexName)
                                .id(doc.getId())
                                .document(doc))));
            }
        }

        if (operations.isEmpty()) {
            return new BulkResult(0, 0, new ArrayList<>());
        }

        BulkRequest bulkRequest = BulkRequest.of(b -> b.operations(operations));
        BulkResponse response = client.bulk(bulkRequest);

        int created = 0;
        int errors = 0;
        List<String> errorMessages = new ArrayList<>();

        for (var item : response.items()) {
            if (item.error() != null) {
                errors++;
                errorMessages.add("ID " + item.id() + ": " + item.error().reason());
            } else {
                created++;
            }
        }

        return new BulkResult(created, errors, errorMessages);
    }

    /**
     * Deletes a single document by ID.
     */
    public boolean deleteDocument(String docId) throws IOException {
        if (docId == null || docId.isBlank()) {
            return false;
        }
        var response = client.delete(d -> d.index(indexName).id(docId));
        return response.result().name().equals("Deleted");
    }

    /**
     * Deletes the entire index. Use with caution!
     */
    public void deleteIndex() throws IOException {
        LOGGER.warn("Deleting index: {}", indexName);
        client.indices().delete(d -> d.index(indexName));
        LOGGER.info("Deleted index: {}", indexName);
    }

    /**
     * Returns the index name.
     */
    public String getIndexName() {
        return indexName;
    }
}
