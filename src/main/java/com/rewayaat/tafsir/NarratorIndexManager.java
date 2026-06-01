package com.rewayaat.tafsir;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.OpType;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import com.rewayaat.config.ESClientProvider;
import com.rewayaat.core.data.NarratorDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Manages the Elasticsearch index for narrator documents.
 * Handles index creation, bulk insertion, and idempotent updates.
 */
public class NarratorIndexManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(NarratorIndexManager.class);

    private final ElasticsearchClient client;
    private final String indexName;

    public NarratorIndexManager(String indexName) {
        ESClientProvider provider = new ESClientProvider();
        this.client = provider.client();
        this.indexName = indexName;
    }

    public NarratorIndexManager() {
        this(resolveIndexName());
    }

    private static String resolveIndexName() {
        String index = System.getProperty("narrator.index");
        if (index == null || index.isEmpty()) {
            index = System.getenv().get("NARRATOR_INDEX");
        }
        return (index != null && !index.isEmpty()) ? index : "rewayaat_narrators";
    }

    public void createIndexIfNotExists() throws IOException {
        boolean exists;
        try {
            ExistsRequest existsRequest = ExistsRequest.of(e -> e.index(indexName));
            exists = client.indices().exists(existsRequest).value();
        } catch (Exception e) {
            LOGGER.warn("Could not check index existence via HEAD: {}", e.getMessage());
            try {
                client.search(s -> s.index(indexName).size(0), Map.class);
                exists = true;
            } catch (Exception e2) {
                exists = false;
            }
        }

        if (!exists) {
            LOGGER.info("Creating narrator index: {}", indexName);

            String mapping = "{"
                    + "\"mappings\": {"
                    + "\"properties\": {"
                    + "\"primary_arabic_name\": {\"type\": \"text\", \"analyzer\": \"arabic\", \"fields\": {\"keyword\": {\"type\": \"keyword\", \"ignore_above\": 512}}},"
                    + "\"primary_english_name\": {\"type\": \"text\", \"fields\": {\"keyword\": {\"type\": \"keyword\", \"ignore_above\": 512}}},"
                    + "\"arabic_aliases\": {\"type\": \"text\", \"analyzer\": \"arabic\", \"fields\": {\"keyword\": {\"type\": \"keyword\", \"ignore_above\": 512}}},"
                    + "\"english_aliases\": {\"type\": \"text\", \"fields\": {\"keyword\": {\"type\": \"keyword\", \"ignore_above\": 512}}},"
                    + "\"kunyah_arabic\": {\"type\": \"keyword\", \"ignore_above\": 256},"
                    + "\"kunyah_english\": {\"type\": \"keyword\", \"ignore_above\": 256},"
                    + "\"titles\": {\"type\": \"keyword\", \"ignore_above\": 256},"
                    + "\"scholarly_names\": {\"type\": \"keyword\", \"ignore_above\": 256},"
                    + "\"is_imam_or_prophet\": {\"type\": \"boolean\"},"
                    + "\"is_doubtful\": {\"type\": \"boolean\"},"
                    + "\"doubtful_reason\": {\"type\": \"text\"},"
                    + "\"reliability_grade\": {\"type\": \"keyword\", \"ignore_above\": 64},"
                    + "\"reliability_sources\": {\"type\": \"keyword\", \"ignore_above\": 256},"
                    + "\"source_assessments\": {\"type\": \"nested\", \"properties\": {"
                    + "\"source_name\": {\"type\": \"keyword\", \"ignore_above\": 256},"
                    + "\"author\": {\"type\": \"keyword\", \"ignore_above\": 128},"
                    + "\"assessment_en\": {\"type\": \"text\"},"
                    + "\"assessment_ar\": {\"type\": \"text\", \"analyzer\": \"arabic\"}"
                    + "}},"
                    + "\"biography_summary_en\": {\"type\": \"text\"},"
                    + "\"biography_summary_ar\": {\"type\": \"text\", \"analyzer\": \"arabic\"},"
                    + "\"rijal_sources\": {\"type\": \"keyword\", \"ignore_above\": 256},"
                    + "\"death_year_hijri\": {\"type\": \"keyword\", \"ignore_above\": 32},"
                    + "\"birth_year_hijri\": {\"type\": \"keyword\", \"ignore_above\": 32},"
                    + "\"tribe_or_city\": {\"type\": \"keyword\", \"ignore_above\": 128},"
                    + "\"generation\": {\"type\": \"keyword\", \"ignore_above\": 64},"
                    + "\"gender\": {\"type\": \"keyword\", \"ignore_above\": 16},"
                    + "\"hadith_count\": {\"type\": \"integer\"},"
                    + "\"normalized_arabic\": {\"type\": \"keyword\", \"ignore_above\": 512},"
                    + "\"normalized_english\": {\"type\": \"keyword\", \"ignore_above\": 512},"
                    + "\"created_at\": {\"type\": \"date\"},"
                    + "\"updated_at\": {\"type\": \"date\"}"
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
                LOGGER.warn("Could not create index via client (may already exist): {}", e.getMessage());
            }
        } else {
            LOGGER.info("Index {} already exists", indexName);
        }
    }

    public boolean indexDocument(NarratorDocument doc) throws IOException {
        if (doc == null || doc.getId() == null) {
            LOGGER.warn("Cannot index narrator document: null or missing ID");
            return false;
        }
        IndexRequest<NarratorDocument> request = IndexRequest.of(i -> i
                .index(indexName)
                .id(doc.getId())
                .document(doc)
                .opType(OpType.Create));
        var response = client.index(request);
        return response.result().name().equals("Created");
    }

    public BulkResult indexDocuments(List<NarratorDocument> documents) throws IOException {
        if (documents == null || documents.isEmpty()) {
            return new BulkResult(0, 0, new ArrayList<>());
        }
        List<BulkOperation> operations = new ArrayList<>();
        for (NarratorDocument doc : documents) {
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
                } else {
                    created++;
                }
            }
        } else {
            created = operations.size();
        }
        return new BulkResult(created, errors, errorMessages);
    }

    public BulkResult indexDocumentsWithOverwrite(List<NarratorDocument> documents) throws IOException {
        if (documents == null || documents.isEmpty()) {
            return new BulkResult(0, 0, new ArrayList<>());
        }
        List<BulkOperation> operations = new ArrayList<>();
        for (NarratorDocument doc : documents) {
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

    public String getIndexName() {
        return indexName;
    }

    public record BulkResult(int created, int errors, List<String> errorMessages) {
        public boolean isSuccess() {
            return errors == 0;
        }

        public int total() {
            return created + errors;
        }
    }
}
