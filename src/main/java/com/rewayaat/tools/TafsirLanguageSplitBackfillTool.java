package com.rewayaat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rewayaat.tafsir.TafsirSnippetSanitizer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Backfills script-separated tafsir commentary fields so Arabic and English can be ranked independently.
 */
public final class TafsirLanguageSplitBackfillTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);
    private static final int BATCH_SIZE = readInt("TAFSIR_SPLIT_BACKFILL_BATCH_SIZE", 250);
    private static final String INDEX_NAME = readString("TAFSIR_INDEX", "rewayaat_tafsir");
    private static final String SCROLL_KEEPALIVE = readString("TAFSIR_SPLIT_BACKFILL_SCROLL", "2m");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String baseUrl = buildBaseUrl();

    public static void main(String[] args) throws Exception {
        new TafsirLanguageSplitBackfillTool().run();
    }

    private void run() throws Exception {
        ensureSplitFields();
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
                updated += updateBatch(hits);
                System.out.printf("Processed %d documents, updated %d%n", seen, updated);
                page = continueScroll(scrollId);
                scrollId = page.path("_scroll_id").asText(scrollId == null ? "" : scrollId);
            }
        } finally {
            clearScroll(scrollId);
        }
        System.out.printf("Tafsir language split backfill completed. Seen=%d Updated=%d%n", seen, updated);
    }

    private void ensureSplitFields() throws Exception {
        ObjectNode mapping = MAPPER.createObjectNode();
        ObjectNode properties = mapping.putObject("properties");
        properties.putObject("commentaryTextArabic").put("type", "text");
        properties.putObject("commentaryTextEnglish").put("type", "text");
        properties.putObject("commentary_text_arabic").put("type", "text");
        properties.putObject("commentary_text_english").put("type", "text");

        HttpRequest request = baseRequest("/" + encode(INDEX_NAME) + "/_mapping")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(mapping)))
                .build();
        send(request);
    }

    private JsonNode startScroll() throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("size", Math.max(1, BATCH_SIZE));
        body.putArray("sort").add("_doc");
        ArrayNode source = body.putArray("_source");
        source.add("commentaryText");
        source.add("commentary_text");
        source.add("commentaryTextArabic");
        source.add("commentaryTextEnglish");
        source.add("commentary_text_arabic");
        source.add("commentary_text_english");
        body.set("query", MAPPER.createObjectNode().set("match_all", MAPPER.createObjectNode()));
        return postJson("/" + encode(INDEX_NAME) + "/_search?scroll=" + encode(SCROLL_KEEPALIVE), body.toString());
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
            String id = hit.path("_id").asText("");
            if (id.isBlank()) {
                continue;
            }
            JsonNode sourceNode = hit.path("_source");
            if (sourceNode == null || sourceNode.isMissingNode() || sourceNode.isNull()) {
                continue;
            }
            String commentary = valueAsString(sourceNode.get("commentaryText"));
            if (commentary.isBlank()) {
                commentary = valueAsString(sourceNode.get("commentary_text"));
            }
            if (commentary.isBlank()) {
                continue;
            }

            TafsirSnippetSanitizer.ScriptSplit split = TafsirSnippetSanitizer.splitCommentaryByScript(commentary);
            String arabic = split.arabicText();
            String english = split.englishText();
            String existingArabic = firstNonBlank(
                    valueAsString(sourceNode.get("commentaryTextArabic")),
                    valueAsString(sourceNode.get("commentary_text_arabic"))
            );
            String existingEnglish = firstNonBlank(
                    valueAsString(sourceNode.get("commentaryTextEnglish")),
                    valueAsString(sourceNode.get("commentary_text_english"))
            );
            if (arabic.equals(existingArabic) && english.equals(existingEnglish)) {
                continue;
            }

            ObjectNode action = MAPPER.createObjectNode();
            action.set("update", MAPPER.createObjectNode()
                    .put("_index", INDEX_NAME)
                    .put("_id", id));

            ObjectNode doc = MAPPER.createObjectNode();
            putNullable(doc, "commentaryTextArabic", arabic);
            putNullable(doc, "commentaryTextEnglish", english);
            putNullable(doc, "commentary_text_arabic", arabic);
            putNullable(doc, "commentary_text_english", english);

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

    private static void putNullable(ObjectNode doc, String field, String value) {
        if (value == null || value.isBlank()) {
            doc.putNull(field);
        } else {
            doc.put(field, value);
        }
    }

    private static String valueAsString(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("").trim();
    }

    private static String buildBaseUrl() {
        String host = readString("ELASTIC_HOST", "localhost");
        String port = readString("ELASTIC_PORT", "9200");
        return "http://" + host + ":" + port;
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
        String value = firstNonBlank(System.getProperty(key), System.getenv(key));
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.trim().isEmpty()) {
            return first.trim();
        }
        if (second != null && !second.trim().isEmpty()) {
            return second.trim();
        }
        return "";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
