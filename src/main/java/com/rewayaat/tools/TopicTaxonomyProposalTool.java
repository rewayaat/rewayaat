package com.rewayaat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rewayaat.config.ESClientProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Proposes a corpus-derived topic taxonomy using representative indexed narrations.
 * The generated JSON is intentionally review-first: it writes to a proposal file unless
 * the caller explicitly points it at the frozen taxonomy path.
 */
public final class TopicTaxonomyProposalTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);
    private static final int SAMPLE_DOC_LIMIT = readInt("TOPIC_TAXONOMY_SAMPLE_DOC_LIMIT", 60);
    private static final int SAMPLE_BOOK_LIMIT = readInt("TOPIC_TAXONOMY_SAMPLE_BOOK_LIMIT", 18);
    private static final int SAMPLE_PER_BOOK_LIMIT = readInt("TOPIC_TAXONOMY_SAMPLE_PER_BOOK_LIMIT", 4);
    private static final int TOP_BOOK_LIMIT = readInt("TOPIC_TAXONOMY_TOP_BOOK_LIMIT", 24);
    private static final int TOP_CHAPTER_LIMIT = readInt("TOPIC_TAXONOMY_TOP_CHAPTER_LIMIT", 120);
    private static final int MAX_PROMPT_CHARS = readInt("TOPIC_TAXONOMY_MAX_PROMPT_CHARS", 18000);
    private static final Duration AI_REQUEST_TIMEOUT = Duration.ofSeconds(readInt("TOPIC_TAXONOMY_AI_TIMEOUT_SECONDS", 90));
    private static final String SCROLL_KEEPALIVE = readString("TOPIC_TAXONOMY_SCROLL", "2m");
    private static final String DEFAULT_OUTPUT = readString("TOPIC_TAXONOMY_OUTPUT", "/tmp/rewayaat-taxonomy-proposed.json");
    private static final String RAW_OUTPUT = readString("TOPIC_TAXONOMY_RAW_OUTPUT", "/tmp/rewayaat-taxonomy-proposed.raw.txt");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String baseUrl = buildBaseUrl();
    private final String index = readString("REWAYAAT_INDEX", ESClientProvider.INDEX);
    private final String agentUrl = readString("SUMMARY_AI_AGENT_URL",
            "https://kbm2sc4qjqcubxjmkawniaei.agents.do-ai.run/api/v1/chat/completions");
    private final String agentKey = readString("SUMMARY_AI_AGENT_KEY", "");

    public static void main(String[] args) throws Exception {
        new TopicTaxonomyProposalTool().run();
    }

    private void run() throws Exception {
        if (agentKey.isBlank()) {
            throw new IllegalStateException("SUMMARY_AI_AGENT_KEY is required to propose a taxonomy.");
        }
        CorpusSignals corpusSignals = collectCorpusSignals();
        List<SampleNarration> samples = collectSamples();
        if (samples.isEmpty()) {
            throw new IllegalStateException("No indexed narrations were available for taxonomy proposal.");
        }
        String prompt = buildPrompt(samples, corpusSignals);
        String rawProposal = callAgent(prompt);
        Files.writeString(Path.of(RAW_OUTPUT), rawProposal + System.lineSeparator(), StandardCharsets.UTF_8);
        List<TopicTaxonomySupport.TopicTaxonomyEntry> proposed = TopicTaxonomySupport.parseTaxonomyProposal(rawProposal);
        if (proposed.isEmpty()) {
            throw new IllegalStateException("AI taxonomy proposal returned no valid entries. Raw response saved to " + RAW_OUTPUT);
        }
        writeProposal(proposed);
        System.out.printf("Proposed taxonomy written to %s with %d entries%n", DEFAULT_OUTPUT, proposed.size());
    }

    private List<SampleNarration> collectSamples() throws Exception {
        List<SampleNarration> samples = new ArrayList<>();
        Map<String, Integer> bookCounts = new LinkedHashMap<>();
        String scrollId = null;
        try {
            JsonNode page = startScroll();
            scrollId = page.path("_scroll_id").asText("");
            while (samples.size() < SAMPLE_DOC_LIMIT) {
                ArrayNode hits = arrayNode(page.path("hits").path("hits"));
                if (hits == null || hits.isEmpty()) {
                    break;
                }
                for (JsonNode hit : hits) {
                    SampleNarration sample = toSample(hit);
                    if (sample == null) {
                        continue;
                    }
                    int seenForBook = bookCounts.getOrDefault(sample.book(), 0);
                    if (bookCounts.size() >= SAMPLE_BOOK_LIMIT && !bookCounts.containsKey(sample.book())) {
                        continue;
                    }
                    if (seenForBook >= SAMPLE_PER_BOOK_LIMIT) {
                        continue;
                    }
                    bookCounts.put(sample.book(), seenForBook + 1);
                    samples.add(sample);
                    if (samples.size() >= SAMPLE_DOC_LIMIT) {
                        break;
                    }
                }
                page = continueScroll(scrollId);
                scrollId = page.path("_scroll_id").asText(scrollId == null ? "" : scrollId);
            }
        } finally {
            clearScroll(scrollId);
        }
        return samples;
    }

    private SampleNarration toSample(JsonNode hit) {
        if (hit == null || hit.isMissingNode()) {
            return null;
        }
        JsonNode source = hit.path("_source");
        if (source == null || source.isMissingNode() || source.isNull()) {
            return null;
        }
        String book = cleanText(source.path("book").asText(""));
        String chapter = cleanText(source.path("chapter").asText(""));
        String english = cap(cleanText(source.path("english").asText("")), 420);
        String arabic = cap(cleanText(source.path("arabic").asText("")), 280);
        String keyTerms = cleanText(source.path("semantic_significant_terms_source").asText(""));
        if (english.isBlank() && arabic.isBlank()) {
            return null;
        }
        return new SampleNarration(book.isBlank() ? "Unknown Book" : book, chapter, english, arabic, keyTerms);
    }

    private String buildPrompt(List<SampleNarration> samples, CorpusSignals corpusSignals) throws IOException {
        List<SampleNarration> selectedSamples = new ArrayList<>(samples);
        while (!selectedSamples.isEmpty()) {
            String json = buildPromptJson(selectedSamples, corpusSignals);
            if (json.length() <= MAX_PROMPT_CHARS || selectedSamples.size() <= 12) {
                return json;
            }
            selectedSamples.remove(selectedSamples.size() - 1);
        }
        return buildPromptJson(List.of(), corpusSignals);
    }

    private String buildPromptJson(List<SampleNarration> samples, CorpusSignals corpusSignals) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task", "topic_taxonomy_proposal");
        payload.put("instructions",
                "Revise the existing hadith topic taxonomy using the corpus signals and sampled narrations. " +
                        "Prefer broad reusable themes over narrow one-off events, and preserve existing slugs unless the corpus strongly suggests a change. " +
                        "Return only JSON. " +
                        "Schema: {\"taxonomy\":[{\"slug\":string,\"en\":string,\"ar\":string?,\"category\":string,\"parent\":string?,\"description\":string?}]}. " +
                        "Use lowercase kebab-case slugs. Use 55 to 85 entries. " +
                        "Prefer a two-level hierarchy with broad parent tags and specific child tags. " +
                        "Categories should be reusable buckets like worship, belief, ethics, social-relations, law, transactions, biography, eschatology, knowledge. " +
                        "Keep output concise: omit description unless needed, and omit Arabic labels if you are not confident.");
        payload.put("existing_taxonomy", existingTaxonomySummary());
        payload.put("corpus_signals", corpusSignals);
        payload.put("sampled_narrations", samples);
        return MAPPER.writeValueAsString(payload);
    }

    private String existingTaxonomySummary() throws IOException {
        List<TopicTaxonomySupport.TopicTaxonomyEntry> existing = TopicTaxonomySupport.loadBundledTaxonomy();
        StringJoiner joiner = new StringJoiner("\n");
        for (String line : TopicTaxonomySupport.compactPromptTaxonomy(existing)) {
            joiner.add(line);
        }
        return joiner.toString();
    }

    private CorpusSignals collectCorpusSignals() throws Exception {
        return new CorpusSignals(fetchTotalDocs(), fetchTopTerms("book", TOP_BOOK_LIMIT), fetchTopTerms("chapter", TOP_CHAPTER_LIMIT));
    }

    private long fetchTotalDocs() throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.set("query", MAPPER.createObjectNode().set("match_all", MAPPER.createObjectNode()));
        JsonNode response = postJson("/" + encode(index) + "/_count", body.toString());
        return response.path("count").asLong(0L);
    }

    private List<FacetCount> fetchTopTerms(String field, int size) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("size", 0);
        ObjectNode terms = body.putObject("aggs").putObject("top_terms").putObject("terms");
        terms.put("field", field + ".keyword");
        terms.put("size", Math.max(1, size));
        JsonNode response = postJson("/" + encode(index) + "/_search", body.toString());
        ArrayNode buckets = arrayNode(response.path("aggregations").path("top_terms").path("buckets"));
        List<FacetCount> counts = new ArrayList<>();
        if (buckets == null) {
            return counts;
        }
        for (JsonNode bucket : buckets) {
            String key = cleanText(bucket.path("key").asText(""));
            if (!key.isBlank()) {
                counts.add(new FacetCount(key, bucket.path("doc_count").asLong(0L)));
            }
        }
        return counts;
    }

    private String callAgent(String userPrompt) throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", "task=topic_taxonomy_proposal"));
        messages.add(message("user", userPrompt));

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.2);
        requestBody.put("max_completion_tokens", 2200);
        requestBody.put("stream", false);
        requestBody.put("retrieval_method", "none");
        requestBody.put("reasoning_effort", "medium");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(agentUrl))
                .timeout(AI_REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + agentKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(requestBody), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Taxonomy proposal upstream returned status " + response.statusCode());
        }
        return extractContent(response.body());
    }

    private void writeProposal(List<TopicTaxonomySupport.TopicTaxonomyEntry> proposed) throws IOException {
        ArrayNode root = MAPPER.createArrayNode();
        for (TopicTaxonomySupport.TopicTaxonomyEntry entry : proposed) {
            ObjectNode node = root.addObject();
            node.put("slug", entry.slug());
            node.put("en", entry.englishLabel());
            if (!entry.arabicLabel().isBlank()) {
                node.put("ar", entry.arabicLabel());
            }
            node.put("category", entry.category());
            if (!entry.parentSlug().isBlank()) {
                node.put("parent", entry.parentSlug());
            }
            if (!entry.description().isBlank()) {
                node.put("description", entry.description());
            }
        }
        Files.writeString(Path.of(DEFAULT_OUTPUT),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    private JsonNode startScroll() throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("size", Math.max(50, SAMPLE_DOC_LIMIT));
        body.putArray("sort").add("_doc");
        ArrayNode source = body.putArray("_source");
        source.add("book");
        source.add("chapter");
        source.add("english");
        source.add("arabic");
        source.add("semantic_significant_terms_source");
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

    private String extractContent(String json) throws Exception {
        if (json == null || json.trim().isEmpty()) {
            return "";
        }
        JsonNode root = MAPPER.readTree(json);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return "";
        }
        JsonNode first = choices.get(0);
        String fromMessage = extractTextContent(first.path("message").path("content"));
        if (!fromMessage.isBlank()) {
            return fromMessage;
        }
        return extractTextContent(first.path("delta").path("content"));
    }

    private String extractTextContent(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText("").trim();
        }
        if (node.isObject()) {
            return node.path("text").asText("").trim();
        }
        if (node.isArray()) {
            StringJoiner joiner = new StringJoiner("\n");
            for (JsonNode item : node) {
                String text = extractTextContent(item);
                if (!text.isBlank()) {
                    joiner.add(text);
                }
            }
            return joiner.toString().trim();
        }
        return "";
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", role);
        map.put("content", content);
        return map;
    }

    private static ArrayNode arrayNode(JsonNode node) {
        return node instanceof ArrayNode ? (ArrayNode) node : null;
    }

    private static String cleanText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.replaceAll("\\s+", " ").trim();
    }

    private static String cap(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxChars).trim() + "...";
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

    private record SampleNarration(String book, String chapter, String english, String arabic, String key_terms) {
    }

    private record FacetCount(String key, long count) {
    }

    private record CorpusSignals(long totalDocs, List<FacetCount> topBooks, List<FacetCount> topChapters) {
    }
}
