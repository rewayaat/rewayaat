package com.rewayaat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.config.ESClientProvider;
import com.rewayaat.core.HadithSemanticText;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds exact agent payloads for sample hadith, writes them to disk, sends them to the configured
 * AI agent, and prints the raw completion for debugging.
 */
public final class TopicTagsAgentSampleTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int ENGLISH_MAX_CHARS = readInt("TOPIC_TAGS_AI_ENGLISH_MAX_CHARS", 2200);
    private static final int ARABIC_MATN_MAX_CHARS = readInt("TOPIC_TAGS_AI_ARABIC_MATN_MAX_CHARS", 2200);
    private static final double TEMPERATURE = readDouble("TOPIC_TAGS_AI_TEMPERATURE", 0.0d);
    private static final int MAX_COMPLETION_TOKENS = readInt("TOPIC_TAGS_AI_MAX_COMPLETION_TOKENS", 0);
    private static final String PROPOSAL_DEBUG_FILE = readString("TOPIC_TAGS_AI_PROPOSAL_DEBUG_FILE", "/tmp/topic-tags-ai-proposals.log");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(readInt("TOPIC_TAGS_AI_TIMEOUT_SECONDS", 90));

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String elasticBaseUrl = buildElasticBaseUrl();
    private final String index = readString("REWAYAAT_INDEX", ESClientProvider.INDEX);
    private final String agentUrl = readString("TOPIC_TAGS_AI_AGENT_URL", "");
    private final String agentKey = readString("TOPIC_TAGS_AI_AGENT_KEY", "");
    private final Path outputDir = Path.of(readString("TOPIC_TAGS_SAMPLE_OUTPUT_DIR", "tmp/topic-tags-agent-samples"));

    public static void main(String[] args) throws Exception {
        new TopicTagsAgentSampleTool().run();
    }

    private void run() throws Exception {
        if (agentUrl.isBlank() || agentKey.isBlank()) {
            throw new IllegalStateException("TOPIC_TAGS_AI_AGENT_URL and TOPIC_TAGS_AI_AGENT_KEY are required.");
        }
        String idsRaw = readString("TOPIC_TAGS_SAMPLE_IDS", "");
        List<String> ids = parseIds(idsRaw);
        if (ids.isEmpty()) {
            throw new IllegalStateException("TOPIC_TAGS_SAMPLE_IDS must contain one or more document ids.");
        }
        Files.createDirectories(outputDir);
        List<String> taxonomy = TopicTaxonomySupport.compactPromptTaxonomy(TopicTaxonomySupport.loadBundledTaxonomy());
        for (String id : ids) {
            JsonNode source = fetchSource(id);
            if (source == null || source.isMissingNode() || source.isNull()) {
                System.out.printf(Locale.ROOT, "MISSING %s%n", id);
                continue;
            }
            String userPayload = buildUserPayload(id, source, taxonomy);
            String requestBody = buildRequestBody(userPayload);
            String rawResponse = callAgent(requestBody);
            String completion = extractContent(rawResponse);
            List<TopicTaxonomySupport.TopicTaxonomyEntry> proposals = extractProposals(completion);

            String safeId = id.replaceAll("[^A-Za-z0-9._-]+", "_");
            Path payloadFile = outputDir.resolve(safeId + "_payload.json");
            Path requestFile = outputDir.resolve(safeId + "_request_body.json");
            Path responseFile = outputDir.resolve(safeId + "_response.json");
            Path completionFile = outputDir.resolve(safeId + "_completion.txt");
            Files.writeString(payloadFile, userPayload, StandardCharsets.UTF_8);
            Files.writeString(requestFile, requestBody, StandardCharsets.UTF_8);
            Files.writeString(responseFile, rawResponse, StandardCharsets.UTF_8);
            Files.writeString(completionFile, completion == null ? "" : completion, StandardCharsets.UTF_8);
            if (!proposals.isEmpty()) {
                logTaxonomyProposal(id, userPayload, completion, proposals);
            }

            System.out.printf(Locale.ROOT, "%s%n", id);
            System.out.printf(Locale.ROOT, "  payload: %s%n", payloadFile);
            System.out.printf(Locale.ROOT, "  request: %s%n", requestFile);
            System.out.printf(Locale.ROOT, "  response: %s%n", responseFile);
            System.out.printf(Locale.ROOT, "  completion: %s%n", completion == null ? "" : completion);
        }
    }

    private JsonNode fetchSource(String id) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(elasticBaseUrl + "/" + encode(index) + "/_doc/" + encode(id) + "?_source=english,semantic_matn_source"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Elasticsearch request failed with status " + response.statusCode() + ": " + response.body());
        }
        JsonNode root = MAPPER.readTree(response.body());
        return root.path("_source");
    }

    private String buildUserPayload(String id,
                                    JsonNode source,
                                    List<String> taxonomy) throws Exception {
        Map<String, Object> sourceMap = MAPPER.convertValue(source, Map.class);
        String english = cleanText(HadithSemanticText.extractEnglishMatn(sourceMap, ENGLISH_MAX_CHARS));
        String arabicMatn = cleanText(source.path("semantic_matn_source").asText(""));

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", id);
        document.put("english", cap(english, ENGLISH_MAX_CHARS));
        document.put("arabic_matn", cap(arabicMatn, ARABIC_MATN_MAX_CHARS));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task", "topic_tag_classification");
        payload.put("instructions", classificationInstructions());
        payload.put("taxonomy", taxonomy);
        payload.put("documents", List.of(document));
        return MAPPER.writeValueAsString(payload);
    }

    private String buildRequestBody(String userPayload) throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", "task=topic_tag_classification"));
        messages.add(message("user", userPayload));
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("messages", messages);
        requestBody.put("temperature", TEMPERATURE);
        if (MAX_COMPLETION_TOKENS > 0) {
            requestBody.put("max_completion_tokens", MAX_COMPLETION_TOKENS);
        }
        requestBody.put("stream", false);
        requestBody.put("retrieval_method", "none");
        return MAPPER.writeValueAsString(requestBody);
    }

    private String callAgent(String requestBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(agentUrl))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + agentKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Agent request failed with status " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    private String extractContent(String json) throws Exception {
        if (json == null || json.isBlank()) {
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
            StringBuilder builder = new StringBuilder();
            for (JsonNode item : node) {
                String text = extractTextContent(item);
                if (!text.isBlank()) {
                    if (builder.length() > 0) {
                        builder.append('\n');
                    }
                    builder.append(text);
                }
            }
            return builder.toString().trim();
        }
        return "";
    }

    private List<TopicTaxonomySupport.TopicTaxonomyEntry> extractProposals(String completion) {
        if (completion == null || completion.isBlank()) {
            return List.of();
        }
        try {
            TopicTaxonomySupport.ParsedTagAssignments parsed =
                    TopicTaxonomySupport.parseTagAssignmentsWithProposals(completion, null);
            return parsed.proposals();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void logTaxonomyProposal(String id,
                                     String userPayload,
                                     String completion,
                                     List<TopicTaxonomySupport.TopicTaxonomyEntry> proposals) {
        try {
            var node = MAPPER.createObjectNode();
            node.putArray("ids").add(id);
            var proposalNodes = node.putArray("proposed_taxonomy");
            for (TopicTaxonomySupport.TopicTaxonomyEntry proposal : proposals) {
                if (proposal == null) {
                    continue;
                }
                var proposalNode = proposalNodes.addObject();
                proposalNode.put("slug", proposal.slug());
                proposalNode.put("en", proposal.englishLabel());
                if (!proposal.arabicLabel().isBlank()) {
                    proposalNode.put("ar", proposal.arabicLabel());
                }
                proposalNode.put("category", proposal.category());
                if (!proposal.parentSlug().isBlank()) {
                    proposalNode.put("parent", proposal.parentSlug());
                }
            }
            node.put("user_payload", userPayload == null ? "" : userPayload);
            node.put("raw_completion", completion == null ? "" : completion);
            Files.writeString(Paths.get(PROPOSAL_DEBUG_FILE),
                    MAPPER.writeValueAsString(node) + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", role);
        map.put("content", content);
        return map;
    }

    private String cleanText(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("\\s+", " ").trim();
    }

    private String cap(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String clean = cleanText(text);
        if (maxChars <= 0 || clean.length() <= maxChars) {
            return clean;
        }
        return clean.substring(0, maxChars).trim();
    }

    private String classificationInstructions() {
        return "You classify Shia hadith into controlled taxonomy slugs. " +
                "You will receive only id, english, arabic_matn, and taxonomy. " +
                "Tag only themes the hadith substantively addresses. " +
                "Do not tag based on passing mentions, chains, incidental names, or weak associations. " +
                "Prefer fewer correct tags over many weak tags. " +
                "Most hadith should receive 1-5 direct tags; use more only when the hadith clearly spans multiple major themes. " +
                "The taxonomy contains only directly taggable slugs. Parent and ancestor tags are added by the system later. " +
                "If no existing tag is a reasonable fit, you may propose and use a new tag only when you strongly believe the taxonomy is missing a necessary concept. " +
                "When proposing a new tag, include it in the document tags and also add an entry to proposed_taxonomy with slug, en, category, and parent. " +
                "You may propose a new parent tag or even a new category if truly necessary, but do this rarely and only when the existing taxonomy is clearly insufficient. " +
                "If no tag is a reasonable fit and no new tag is clearly justified, return an empty tags array for that document; quality matters more than coverage. " +
                "Prefer the most specific supported direct tag. " +
                "Do not output parent or ancestor rollups. " +
                "Do not use quran just because a verse is quoted or referenced. " +
                "Do not use knowledge just because the hadith teaches something or includes a chain. " +
                "Do not use good-character if a more specific ethical tag fits. " +
                "Avoid broad umbrella tags such as knowledge, faith, good-character, family, leadership, livelihood, and halal unless the hadith is truly about that umbrella topic. " +
                "Do not infer a specific Imam from kunyah, title, or weak contextual clues alone. " +
                "Do not assign a person tag just because a figure is mentioned in passing. " +
                "Use person tags only when the hadith is materially about that figure, their words, their role, their example, or an event centered on them. " +
                "Do not assign ahl-al-bayt by default to every Imam narration. " +
                "Do not assign leadership unless governance, authority, rule, rights, or public authority are actually central. " +
                "Do not assign legal or ritual tags unless the hadith is actually discussing that legal or ritual matter. " +
                "When clearly supported, prefer specific Shia tags such as imamate, wilayah, ghadir, imam-ali, imam-husayn, karbala, ziyarat, occultation, imam-mahdi, and reappearance-signs over generic doctrinal tags. " +
                "Use evidence in this order: arabic_matn, then english. " +
                "Return only valid JSON. " +
                "Use this form when no new tags are needed: {\"documents\":[{\"id\":\"doc-id\",\"tags\":[\"slug-1\",\"slug-2\"]}]}. " +
                "Use this form when proposing new tags: {\"documents\":[{\"id\":\"doc-id\",\"tags\":[\"slug-1\",\"new-slug\"]}],\"proposed_taxonomy\":[{\"slug\":\"new-slug\",\"en\":\"New Label\",\"category\":\"existing-or-new-category\",\"parent\":\"existing-or-new-parent-slug\"}]}. " +
                "Do not invent weak or redundant slugs, and do not output prose, markdown, explanations, or code fences.";
    }

    private static List<String> parseIds(String idsRaw) {
        if (idsRaw == null || idsRaw.isBlank()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (String part : idsRaw.split(",")) {
            String clean = part == null ? "" : part.trim();
            if (!clean.isBlank()) {
                ids.add(clean);
            }
        }
        return List.copyOf(ids);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String buildElasticBaseUrl() {
        String host = readString("ELASTIC_HOST", "127.0.0.1");
        int port = readInt("ELASTIC_PORT", 9200);
        String scheme = readString("ELASTIC_SCHEME", "http");
        return scheme + "://" + host + ":" + port;
    }

    private static String readString(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int readInt(String key, int fallback) {
        String raw = System.getenv(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double readDouble(String key, double fallback) {
        String raw = System.getenv(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
