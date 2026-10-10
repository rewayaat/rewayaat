package com.rewayaat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rewayaat.config.ESClientProvider;
import com.rewayaat.core.HadithDisplaySegmenter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Writes out the isnād of every narration, exactly as the site splits it for display.
 *
 * <p>Stage 8 of the narrator system has to link each name in a chain to the man's page, and a
 * link can only be put where the reader actually sees the name. The split is already made by
 * {@link HadithDisplaySegmenter}, whose {@code arabicChain} / {@code englishChain} are what
 * {@code hadith-card.html} renders; resolving names against anything else would place links in
 * text nobody is shown. So this tool runs that same segmenter over the corpus and writes one
 * JSONL row per narration for the Python side to work on.
 *
 * <p>Nothing is written back to Elasticsearch. Usage:
 *
 * <pre>
 * REWAYAAT_INDEX=rewayaat_updated CHAIN_EXPORT_OUT=tmp/narrators_chains/chains.jsonl \
 *   java -cp "target/classes:$(cat /tmp/rewayaat.cp)" com.rewayaat.tools.ChainExportTool
 * </pre>
 */
public final class ChainExportTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);
    private static final int BATCH_SIZE = readInt("CHAIN_EXPORT_BATCH_SIZE", 500);
    private static final String SCROLL_KEEPALIVE = readString("CHAIN_EXPORT_SCROLL", "5m");
    private static final String OUT_PATH = readString("CHAIN_EXPORT_OUT", "tmp/narrators_chains/chains.jsonl");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String baseUrl = buildBaseUrl();
    private final String index = readString("REWAYAAT_INDEX", ESClientProvider.INDEX);

    public static void main(String[] args) throws Exception {
        new ChainExportTool().run();
    }

    private void run() throws Exception {
        Path out = Path.of(OUT_PATH);
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        long seen = 0L;
        long withArabicChain = 0L;
        long withEnglishChain = 0L;
        String scrollId = null;
        try (BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
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
                        JsonNode sourceNode = hit.path("_source");
                        if (id.isBlank() || sourceNode == null || !sourceNode.isObject()) {
                            continue;
                        }
                        @SuppressWarnings("unchecked")
                        Map<String, Object> source = MAPPER.convertValue(sourceNode, Map.class);
                        Map<String, Object> segmented = new HashMap<>(source);
                        HadithDisplaySegmenter.enrich(segmented);
                        String chainAr = str(segmented.get("arabicChain"));
                        String chainEn = str(segmented.get("englishChain"));
                        seen++;
                        if (!chainAr.isBlank()) {
                            withArabicChain++;
                        }
                        if (!chainEn.isBlank()) {
                            withEnglishChain++;
                        }
                        ObjectNode row = MAPPER.createObjectNode();
                        row.put("id", id);
                        row.put("book", str(source.get("book")));
                        row.put("volume", str(source.get("volume")));
                        row.put("number", str(source.get("number")));
                        row.put("chain_ar", chainAr);
                        row.put("chain_en", chainEn);
                        writer.write(MAPPER.writeValueAsString(row));
                        writer.write('\n');
                    }
                    System.out.printf("exported %d narrations%n", seen);
                    page = continueScroll(scrollId);
                    scrollId = page.path("_scroll_id").asText(scrollId == null ? "" : scrollId);
                }
            } finally {
                clearScroll(scrollId);
            }
        }
        System.out.printf("wrote %d rows to %s (%d with an Arabic chain, %d with an English chain)%n",
                seen, OUT_PATH, withArabicChain, withEnglishChain);
    }

    private JsonNode startScroll() throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("size", Math.max(1, BATCH_SIZE));
        body.putArray("sort").add("_doc");
        ArrayNode source = body.putArray("_source");
        source.add("arabic");
        source.add("english");
        source.add("book");
        source.add("volume");
        source.add("number");
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
            HttpRequest request = baseRequest("/_search/scroll")
                    .header("Content-Type", "application/json")
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            send(request);
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

    private HttpRequest.Builder baseRequest(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(REQUEST_TIMEOUT);
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

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String buildBaseUrl() {
        return "http://" + readString("ELASTIC_HOST", "localhost") + ":" + readString("ELASTIC_PORT", "9200");
    }

    private static int readInt(String key, int defaultValue) {
        String value = readString(key, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static String readString(String key, String defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            value = System.getenv(key);
        }
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
