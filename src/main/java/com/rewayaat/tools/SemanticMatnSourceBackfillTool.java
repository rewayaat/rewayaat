package com.rewayaat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rewayaat.config.ESClientProvider;
import com.rewayaat.core.HadithSemanticText;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Populates a source field with the same matn-only text used by similar-hadith retrieval.
 * The Elasticsearch ingest pipeline can then embed this field directly.
 */
public final class SemanticMatnSourceBackfillTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);
    private static final int MAX_MATN_CHARS = readInt("SIMILAR_MATN_MAX_CHARS", 4200);
    private static final int BATCH_SIZE = readInt("SEMANTIC_BACKFILL_BATCH_SIZE", 250);
    private static final String FIELD_NAME = readString("SEMANTIC_MATN_SOURCE_FIELD", "semantic_matn_source");
    private static final String ENGLISH_HINT_FIELD_NAME = readString("SEMANTIC_ENGLISH_HINT_SOURCE_FIELD", "semantic_english_hint_source");
    private static final String SCROLL_KEEPALIVE = readString("SEMANTIC_BACKFILL_SCROLL", "2m");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String baseUrl = buildBaseUrl();
    private final String index = readString("REWAYAAT_INDEX", ESClientProvider.INDEX);

    public static void main(String[] args) throws Exception {
        new SemanticMatnSourceBackfillTool().run();
    }

    private void run() throws Exception {
        long seen = 0L;
        long updated = 0L;
        String scrollId = null;
        try {
            JsonNode page = startScroll();
            scrollId = page.path("_scroll_id").asText("");
            while (true) {
                ArrayNode hits = arrayNode(page.path("hits").path("hits"));
                if (hits == null || hits.isEmpty()) {
                    break;
                }
                seen += hits.size();
                int batchUpdates = updateBatch(hits);
                updated += batchUpdates;
                System.out.printf("Processed %d documents, updated %d%n", seen, updated);
                page = continueScroll(scrollId);
                scrollId = page.path("_scroll_id").asText(scrollId == null ? "" : scrollId);
            }
        } finally {
            clearScroll(scrollId);
        }
        System.out.printf("Semantic matn source backfill completed. Seen=%d Updated=%d Field=%s%n",
                seen, updated, FIELD_NAME);
    }

    private JsonNode startScroll() throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("size", Math.max(1, BATCH_SIZE));
        ArrayNode sort = body.putArray("sort");
        sort.add("_doc");
        ArrayNode source = body.putArray("_source");
        source.add("arabic");
        source.add("english");
        source.add(FIELD_NAME);
        source.add(ENGLISH_HINT_FIELD_NAME);
        body.set("query", MAPPER.createObjectNode().set("match_all", MAPPER.createObjectNode()));
        return postJson("/" + encode(index) + "/_search?scroll=" + encode(SCROLL_KEEPALIVE), body.toString());
    }

    private JsonNode continueScroll(String scrollId) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("scroll", SCROLL_KEEPALIVE);
        body.put("scroll_id", scrollId == null ? "" : scrollId);
        return postJson("/_search/scroll", body.toString());
    }

    private int updateBatch(ArrayNode hits) throws Exception {
        StringBuilder bulkPayload = new StringBuilder();
        int updates = 0;
        for (JsonNode hit : hits) {
            if (hit == null || hit.isMissingNode()) {
                continue;
            }
            String id = hit.path("_id").asText("");
            if (id.isBlank()) {
                continue;
            }
            JsonNode sourceNode = hit.path("_source");
            if (sourceNode == null || sourceNode.isMissingNode() || sourceNode.isNull()) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> source = MAPPER.convertValue(sourceNode, Map.class);
            String matn = HadithSemanticText.extractMatn(source, MAX_MATN_CHARS);
            String englishHint = HadithSemanticText.extractEnglishHint(source);
            if (matn.isBlank()) {
                continue;
            }
            String existing = valueAsString(source.get(FIELD_NAME));
            String existingEnglishHint = valueAsString(source.get(ENGLISH_HINT_FIELD_NAME));
            if (matn.equals(existing) && englishHint.equals(existingEnglishHint)) {
                continue;
            }
            ObjectNode action = MAPPER.createObjectNode();
            action.set("update", MAPPER.createObjectNode()
                    .put("_index", index)
                    .put("_id", id));
            ObjectNode doc = MAPPER.createObjectNode();
            doc.put(FIELD_NAME, matn);
            if (englishHint.isBlank()) {
                doc.putNull(ENGLISH_HINT_FIELD_NAME);
            } else {
                doc.put(ENGLISH_HINT_FIELD_NAME, englishHint);
            }
            ObjectNode update = MAPPER.createObjectNode();
            update.set("doc", doc);
            update.put("doc_as_upsert", false);

            bulkPayload.append(writeJson(action)).append('\n');
            bulkPayload.append(writeJson(update)).append('\n');
            updates++;
        }
        if (updates == 0) {
            return 0;
        }

        JsonNode response = postNdjson("/_bulk?filter_path=errors,items.*.update.error", bulkPayload.toString());
        if (response.path("errors").asBoolean(false)) {
            throw new IllegalStateException("Bulk update failed: " + response);
        }
        return updates;
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

    private static String valueAsString(Object value) {
        if (!(value instanceof String)) {
            return "";
        }
        return ((String) value).trim();
    }

    private static int readInt(String key, int defaultValue) {
        String value = readString(key, null);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
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
}
