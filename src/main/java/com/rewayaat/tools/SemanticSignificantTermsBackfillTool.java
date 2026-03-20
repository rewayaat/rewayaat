package com.rewayaat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rewayaat.config.ESClientProvider;
import com.rewayaat.core.HadithSemanticText;
import com.rewayaat.core.HadithSignificantTerms;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Backfills corpus-derived significant terms for each hadith so embeddings can include them.
 */
public final class SemanticSignificantTermsBackfillTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);
    private static final int MAX_MATN_CHARS = readInt("SIMILAR_MATN_MAX_CHARS", 4200);
    private static final int BATCH_SIZE = readInt("SEMANTIC_SIGNIFICANT_BACKFILL_BATCH_SIZE", 250);
    private static final int CANDIDATE_TERM_LIMIT = readInt("SEMANTIC_SIGNIFICANT_CANDIDATE_TERM_LIMIT", 24);
    private static final int TOP_TERM_LIMIT = readInt("SEMANTIC_SIGNIFICANT_TOP_TERM_LIMIT", 6);
    private static final String FIELD_NAME = readString("SEMANTIC_SIGNIFICANT_TERMS_FIELD", HadithSignificantTerms.FIELD_NAME);
    private static final String SCROLL_KEEPALIVE = readString("SEMANTIC_SIGNIFICANT_SCROLL", "2m");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String baseUrl = buildBaseUrl();
    private final String index = readString("REWAYAAT_INDEX", ESClientProvider.INDEX);

    public static void main(String[] args) throws Exception {
        new SemanticSignificantTermsBackfillTool().run();
    }

    private void run() throws Exception {
        List<DocTerms> docs = new ArrayList<>();
        Map<String, Integer> documentFrequency = new HashMap<>();

        String scrollId = null;
        long seen = 0L;
        try {
            JsonNode page = startScroll();
            scrollId = page.path("_scroll_id").asText("");
            while (true) {
                ArrayNode hits = arrayNode(page.path("hits").path("hits"));
                if (hits == null || hits.isEmpty()) {
                    break;
                }
                for (JsonNode hit : hits) {
                    DocTerms doc = toDocTerms(hit);
                    if (doc == null) {
                        continue;
                    }
                    docs.add(doc);
                    HadithSignificantTerms.incrementDocumentFrequency(documentFrequency, doc.candidateTerms());
                    seen++;
                }
                System.out.printf("Collected candidate terms for %d documents%n", seen);
                page = continueScroll(scrollId);
                scrollId = page.path("_scroll_id").asText(scrollId == null ? "" : scrollId);
            }
        } finally {
            clearScroll(scrollId);
        }

        int updated = updateDocuments(docs, documentFrequency);
        System.out.printf("Semantic significant terms backfill completed. Seen=%d Updated=%d Field=%s%n",
                seen, updated, FIELD_NAME);
    }

    private DocTerms toDocTerms(JsonNode hit) {
        if (hit == null || hit.isMissingNode()) {
            return null;
        }
        String id = hit.path("_id").asText("");
        if (id.isBlank()) {
            return null;
        }
        JsonNode sourceNode = hit.path("_source");
        if (sourceNode == null || sourceNode.isMissingNode() || sourceNode.isNull()) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> source = MAPPER.convertValue(sourceNode, Map.class);
        String matn = HadithSemanticText.extractMatn(source, MAX_MATN_CHARS);
        if (matn.isBlank()) {
            return new DocTerms(id, List.of(), valueAsString(source.get(FIELD_NAME)));
        }
        String normalizedMatn = HadithSemanticText.normalizeForComparison(matn);
        List<String> candidateTerms = HadithSignificantTerms.candidateTerms(normalizedMatn, CANDIDATE_TERM_LIMIT);
        String existingTerms = valueAsString(source.get(FIELD_NAME));
        return new DocTerms(id, candidateTerms, existingTerms);
    }

    private int updateDocuments(List<DocTerms> docs, Map<String, Integer> documentFrequency) throws Exception {
        if (docs == null || docs.isEmpty()) {
            return 0;
        }
        int updated = 0;
        for (int from = 0; from < docs.size(); from += Math.max(1, BATCH_SIZE)) {
            int to = Math.min(from + Math.max(1, BATCH_SIZE), docs.size());
            StringBuilder bulkPayload = new StringBuilder();
            int batchUpdates = 0;
            for (DocTerms doc : docs.subList(from, to)) {
                List<String> rankedTerms = HadithSignificantTerms.rankTerms(
                        doc.candidateTerms(),
                        documentFrequency,
                        docs.size(),
                        TOP_TERM_LIMIT);
                String joinedTerms = HadithSignificantTerms.joinTerms(rankedTerms, TOP_TERM_LIMIT);
                if (joinedTerms.equals(doc.existingTerms())) {
                    continue;
                }

                ObjectNode action = MAPPER.createObjectNode();
                action.set("update", MAPPER.createObjectNode()
                        .put("_index", index)
                        .put("_id", doc.id()));
                ObjectNode docNode = MAPPER.createObjectNode();
                if (joinedTerms.isBlank()) {
                    docNode.putNull(FIELD_NAME);
                } else {
                    docNode.put(FIELD_NAME, joinedTerms);
                }
                ObjectNode update = MAPPER.createObjectNode();
                update.set("doc", docNode);
                update.put("doc_as_upsert", false);

                bulkPayload.append(writeJson(action)).append('\n');
                bulkPayload.append(writeJson(update)).append('\n');
                batchUpdates++;
            }
            if (batchUpdates == 0) {
                continue;
            }
            JsonNode response = postNdjson("/_bulk?filter_path=errors,items.*.update.error", bulkPayload.toString());
            if (response.path("errors").asBoolean(false)) {
                throw new IllegalStateException("Bulk update failed: " + response);
            }
            updated += batchUpdates;
            System.out.printf("Updated significant terms for %d / %d documents%n", updated, docs.size());
        }
        return updated;
    }

    private JsonNode startScroll() throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("size", Math.max(1, BATCH_SIZE));
        ArrayNode sort = body.putArray("sort");
        sort.add("_doc");
        ArrayNode source = body.putArray("_source");
        source.add("arabic");
        source.add("semantic_matn_source");
        source.add(FIELD_NAME);
        body.set("query", MAPPER.createObjectNode().set("match_all", MAPPER.createObjectNode()));
        return postJson("/" + encode(index) + "/_search?scroll=" + encode(SCROLL_KEEPALIVE), body.toString());
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

    private record DocTerms(String id, List<String> candidateTerms, String existingTerms) {
    }
}
