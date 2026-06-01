package com.rewayaat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rewayaat.config.ESClientProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates and backfills E5-large semantic embeddings for tafsir documents.
 * This implements Signal #7 from the Quranic Light methodology.
 *
 * Uses a two-phase approach to avoid scroll timeouts:
 * Phase 1: Collect all documents needing embeddings (fast scroll)
 * Phase 2: Generate embeddings and update (slow inference, no scroll)
 *
 * Usage: java -cp rewayaat.jar com.rewayaat.tools.TafsirEmbeddingTool
 */
public class TafsirEmbeddingTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);
    private static final String TAFSIR_INDEX = resolveTafsirIndex();
    private static final String INFERENCE_MODEL = resolveInferenceModel();
    private static final String SEMANTIC_VECTOR_FIELD = "semantic_vector";
    private static final int SCROLL_BATCH_SIZE = 1000;
    private static final int EMBEDDING_BATCH_SIZE = 20;
    private static final int MAX_TEXT_LENGTH = 2048;
    private static final String SCROLL_KEEPALIVE = "5m";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String baseUrl = buildBaseUrl();

    public static void main(String[] args) throws Exception {
        new TafsirEmbeddingTool().run();
    }

    private void run() throws Exception {
        ensureSemanticVectorField();

        // Phase 1: Collect all documents needing embeddings
        List<DocEmbedding> docs = collectDocumentsNeedingEmbeddings();
        System.out.println("Collected " + docs.size() + " documents needing embeddings");

        if (docs.isEmpty()) {
            System.out.println("All documents already have embeddings. Done!");
            return;
        }

        // Phase 2: Process embeddings in batches (no scroll held open)
        processEmbeddings(docs);
    }

    /**
     * Phase 1: Scroll through all documents and collect those needing embeddings.
     * This is fast - no inference calls, just reading.
     */
    private List<DocEmbedding> collectDocumentsNeedingEmbeddings() throws Exception {
        System.out.println("Phase 1: Collecting documents needing embeddings...");

        List<DocEmbedding> docs = new ArrayList<>();
        String scrollId = null;
        long seen = 0L;
        long needing = 0L;

        try {
            JsonNode page = startScroll();
            scrollId = page.path("_scroll_id").asText("");

            while (true) {
                ArrayNode hits = arrayNode(page.path("hits").path("hits"));
                if (hits == null || hits.isEmpty()) {
                    break;
                }

                for (JsonNode hit : hits) {
                    String id = hit.path("_id").asText("");
                    if (id.isBlank()) {
                        continue;
                    }

                    JsonNode source = hit.path("_source");
                    if (source == null || source.isMissingNode()) {
                        continue;
                    }

                    seen++;
                    String text = extractEmbeddingText(source);

                    // Check if already has embedding
                    JsonNode existingVector = source.get(SEMANTIC_VECTOR_FIELD);
                    if (existingVector != null && !existingVector.isNull() && existingVector.isArray()) {
                        continue;
                    }

                    if (text != null && !text.isBlank()) {
                        docs.add(new DocEmbedding(id, text));
                        needing++;
                    }
                }

                System.out.println("Scanned: " + seen + ", needing embeddings: " + needing);

                page = continueScroll(scrollId);
                scrollId = page.path("_scroll_id").asText(scrollId == null ? "" : scrollId);
            }

        } finally {
            clearScroll(scrollId);
        }

        System.out.println("Phase 1 complete: Scanned " + seen + " documents, " + needing + " need embeddings");
        return docs;
    }

    /**
     * Phase 2: Generate embeddings and update documents.
     * No scroll held open - each batch is independent.
     */
    private void processEmbeddings(List<DocEmbedding> docs) throws Exception {
        System.out.println("Phase 2: Processing " + docs.size() + " embeddings in batches of " + EMBEDDING_BATCH_SIZE);

        int totalProcessed = 0;
        int totalUpdated = 0;
        int totalFailed = 0;

        for (int from = 0; from < docs.size(); from += EMBEDDING_BATCH_SIZE) {
            int to = Math.min(from + EMBEDDING_BATCH_SIZE, docs.size());
            List<DocEmbedding> batch = docs.subList(from, to);

            StringBuilder bulkPayload = new StringBuilder();
            int batchUpdated = 0;
            try {
                List<List<Double>> embeddings = generateEmbeddings(batch);
                for (int i = 0; i < batch.size(); i++) {
                    List<Double> embedding = embeddings.size() > i ? embeddings.get(i) : null;
                    if (embedding == null || embedding.isEmpty()) {
                        totalFailed++;
                        System.err.println("Failed to generate embedding for " + batch.get(i).id() + ": empty embedding");
                        continue;
                    }
                    appendBulkUpdate(bulkPayload, batch.get(i).id(), embedding);
                    batchUpdated++;
                }
            } catch (Exception batchEx) {
                System.err.println("Batch inference failed for " + batch.size() + " tafsir docs: " + batchEx.getMessage());
                for (DocEmbedding doc : batch) {
                    try {
                        List<Double> embedding = generateEmbeddings(List.of(doc)).get(0);
                        if (embedding != null && !embedding.isEmpty()) {
                            appendBulkUpdate(bulkPayload, doc.id(), embedding);
                            batchUpdated++;
                        } else {
                            totalFailed++;
                        }
                    } catch (Exception docEx) {
                        System.err.println("Failed to generate embedding for " + doc.id() + ": " + docEx.getMessage());
                        totalFailed++;
                    }
                }
            }

            totalProcessed += batch.size();

            if (bulkPayload.length() > 0) {
                try {
                    JsonNode response = postNdjson("/_bulk?filter_path=errors,items.*.result", bulkPayload.toString());
                    if (response.path("errors").asBoolean(false)) {
                        System.err.println("Bulk update had errors");
                    } else {
                        totalUpdated += batchUpdated;
                    }
                } catch (Exception e) {
                    System.err.println("Bulk update failed: " + e.getMessage());
                }
            }

            System.out.println("Progress: " + totalProcessed + "/" + docs.size() +
                             " processed, " + totalUpdated + " updated, " + totalFailed + " failed");
        }

        System.out.println("Phase 2 complete: " + totalUpdated + " embeddings updated, " + totalFailed + " failed");
    }

    private void ensureSemanticVectorField() throws Exception {
        System.out.println("Checking for semantic_vector field in index: " + TAFSIR_INDEX);
        try {
            ObjectNode mapping = MAPPER.createObjectNode();
            ObjectNode properties = mapping.putObject("properties");
            ObjectNode vectorField = properties.putObject(SEMANTIC_VECTOR_FIELD);
            vectorField.put("type", "dense_vector");
            vectorField.put("dims", 1024);
            vectorField.put("index", true);
            vectorField.put("similarity", "cosine");

            HttpRequest request = baseRequest("/" + encode(TAFSIR_INDEX) + "/_mapping")
                    .PUT(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(mapping)))
                    .header("Content-Type", "application/json")
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                System.out.println("Successfully added semantic_vector field to index: " + TAFSIR_INDEX);
            }
        } catch (Exception e) {
            System.out.println("Could not update mapping: " + e.getMessage());
        }
    }

    private void appendBulkUpdate(StringBuilder bulkPayload, String docId, List<Double> embedding) {
        ObjectNode action = MAPPER.createObjectNode();
        action.set("update", MAPPER.createObjectNode()
                .put("_index", TAFSIR_INDEX)
                .put("_id", docId));

        ObjectNode docNode = MAPPER.createObjectNode();
        ArrayNode vectorArray = docNode.putArray(SEMANTIC_VECTOR_FIELD);
        for (Double value : embedding) {
            vectorArray.add(value);
        }

        ObjectNode update = MAPPER.createObjectNode();
        update.set("doc", docNode);
        update.put("doc_as_upsert", true);

        bulkPayload.append(writeJson(action)).append('\n');
        bulkPayload.append(writeJson(update)).append('\n');
    }

    private List<List<Double>> generateEmbeddings(List<DocEmbedding> docs) throws Exception {
        ArrayNode inputs = MAPPER.createArrayNode();
        for (DocEmbedding doc : docs) {
            String text = doc.text();
            if (text.length() > MAX_TEXT_LENGTH) {
                text = text.substring(0, MAX_TEXT_LENGTH);
            }
            inputs.add("passage: " + text.trim());
        }
        ObjectNode requestBody = MAPPER.createObjectNode();
        requestBody.set("input", inputs);

        try {
            JsonNode response = postJson("/_inference/text_embedding/" + encode(INFERENCE_MODEL), requestBody.toString());
            JsonNode textEmbedding = response.path("text_embedding");
            List<List<Double>> embeddings = new ArrayList<>();
            if (textEmbedding.isArray()) {
                for (JsonNode item : textEmbedding) {
                    JsonNode embeddingNode = item.path("embedding");
                    if (embeddingNode.isArray()) {
                        List<Double> embedding = new ArrayList<>();
                        for (JsonNode value : embeddingNode) {
                            embedding.add(value.asDouble());
                        }
                        embeddings.add(embedding);
                    } else {
                        embeddings.add(List.of());
                    }
                }
                return embeddings;
            }
        } catch (Exception e) {
            throw new Exception("Inference failed: " + e.getMessage(), e);
        }
        return List.of();
    }

    private JsonNode startScroll() throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("size", Math.max(1, SCROLL_BATCH_SIZE));
        ArrayNode sort = body.putArray("sort");
        sort.add("_doc");
        ArrayNode source = body.putArray("_source");
        source.add("commentaryText");
        source.add("commentary_text");
        source.add("commentaryTextEnglish");
        source.add("commentary_text_english");
        source.add("commentaryTextArabic");
        source.add("commentary_text_arabic");
        source.add("section_title");
        source.add(SEMANTIC_VECTOR_FIELD);
        body.set("query", MAPPER.createObjectNode().set("match_all", MAPPER.createObjectNode()));
        return postJson("/" + encode(TAFSIR_INDEX) + "/_search?scroll=" + encode(SCROLL_KEEPALIVE), body.toString());
    }

    private JsonNode continueScroll(String scrollId) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("scroll", SCROLL_KEEPALIVE);
        body.put("scroll_id", scrollId == null ? "" : scrollId);
        return postJson("/_search/scroll", body.toString());
    }

    private void clearScroll(String scrollId) {
        if (scrollId == null || scrollId.isBlank()) {
            return;
        }
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("scroll_id", scrollId);
            deleteJson("/_search/scroll", body.toString());
        } catch (Exception ignored) {
        }
    }

    private JsonNode postJson(String path, String body) throws Exception {
        HttpRequest request = baseRequest(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(request);
    }

    private JsonNode postNdjson(String path, String body) throws Exception {
        HttpRequest request = baseRequest(path)
                .header("Content-Type", "application/x-ndjson")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(request);
    }

    private void deleteJson(String path, String body) throws Exception {
        HttpRequest request = baseRequest(path)
                .header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(body))
                .build();
        send(request);
    }

    private HttpRequest.Builder baseRequest(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT);
    }

    private JsonNode send(HttpRequest request) throws Exception {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        String body = response.body() == null ? "" : response.body();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Elasticsearch request failed with status " + status + ": " + body);
        }
        if (body.isBlank()) {
            return MAPPER.createObjectNode();
        }
        JsonNode parsed = MAPPER.readTree(body);
        if (parsed.has("error")) {
            throw new IllegalStateException("Elasticsearch error response: " + parsed);
        }
        return parsed;
    }

    private String extractEmbeddingText(JsonNode source) {
        String merged = joinNonBlank(
                textValue(source, "section_title"),
                textValue(source, "commentary_text"),
                textValue(source, "commentaryText"),
                textValue(source, "commentary_text_english"),
                textValue(source, "commentaryTextEnglish"),
                textValue(source, "commentary_text_arabic"),
                textValue(source, "commentaryTextArabic"));
        if (!merged.isBlank()) {
            return merged;
        }
        return null;
    }

    private String textValue(JsonNode source, String field) {
        JsonNode node = source.get(field);
        return node == null ? "" : node.asText("").trim();
    }

    private String joinNonBlank(String... parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append('\n');
            }
            joined.append(part);
        }
        return joined.toString();
    }

    private static ArrayNode arrayNode(JsonNode node) {
        return node instanceof ArrayNode ? (ArrayNode) node : null;
    }

    private static String writeJson(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String buildBaseUrl() {
        String host = readString("ELASTIC_HOST", "localhost");
        String port = readString("ELASTIC_PORT", "9200");
        return "http://" + host + ":" + port;
    }

    private static String readString(String key, String defaultValue) {
        String value = firstNonEmpty(System.getProperty(key), System.getenv(key));
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static String firstNonEmpty(String first, String second) {
        if (first != null && !first.trim().isEmpty()) {
            return first.trim();
        }
        if (second != null && !second.trim().isEmpty()) {
            return second.trim();
        }
        return null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String resolveTafsirIndex() {
        String index = System.getProperty("tafsir.index");
        if (index == null || index.isEmpty()) {
            index = System.getenv().get("TAFSIR_INDEX");
        }
        return (index != null && !index.isEmpty()) ? index : "rewayaat_tafsir";
    }

    private static String resolveInferenceModel() {
        String model = System.getProperty("semantic.inference.model");
        if (model == null || model.isEmpty()) {
            model = System.getenv().get("SEMANTIC_INFERENCE_MODEL");
        }
        return (model != null && !model.isEmpty()) ? model : "rewayaat-semantic";
    }

    private record DocEmbedding(String id, String text) {
    }
}
