package com.rewayaat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.core.SemanticTextNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * LLM reranker for Arabic hadith similarity.
 */
@Service
public class SimilarHadithRerankerService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SimilarHadithRerankerService.class);

    private static final int MAX_QUERY_CHARS = readIntSetting("SIMILAR_RERANK_QUERY_CHARS", 560);
    private static final int MAX_CANDIDATE_CHARS = readIntSetting("SIMILAR_RERANK_CANDIDATE_CHARS", 180);
    private static final int MAX_CANDIDATES = readIntSetting("SIMILAR_RERANK_MAX_CANDIDATES", 24);
    private static final int MAX_COMPLETION_TOKENS = readIntSetting("SIMILAR_RERANK_MAX_COMPLETION_TOKENS", 900);

    private static final String SYSTEM_PROMPT = "You rerank Arabic hadith by semantic similarity using matn only. "
            + "Ignore chains of narrators and generic formulae. "
            + "Score every candidate and return order only. "
            + "Do not omit, remove, or drop candidates. "
            + "Return strict JSON only.";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Value("${summary.ai.enabled:true}")
    private boolean enabled;

    @Value("${summary.ai.agent.url:https://kbm2sc4qjqcubxjmkawniaei.agents.do-ai.run/api/v1/chat/completions}")
    private String agentUrl;

    @Value("${summary.ai.agent.key:}")
    private String agentKey;

    public RerankDecision rerank(String queryId, String queryMatnArabic, List<RerankCandidate> candidates) {
        if (!enabled || candidates == null || candidates.isEmpty()) {
            return RerankDecision.failure();
        }
        if (agentUrl == null || agentUrl.trim().isEmpty() || agentKey == null || agentKey.trim().isEmpty()) {
            LOGGER.warn("Reranker AI config is missing (url/key).");
            return RerankDecision.failure();
        }

        String safeQuery = normalizeForPrompt(queryMatnArabic, MAX_QUERY_CHARS);
        if (safeQuery.isBlank()) {
            return RerankDecision.failure();
        }
        List<Map<String, String>> compactCandidates = compactCandidates(candidates);
        if (compactCandidates.isEmpty()) {
            return RerankDecision.failure();
        }

        try {
            String prompt = buildUserPrompt(queryId, safeQuery, compactCandidates);
            String raw = callAgent(prompt);
            RerankDecision parsed = parseRerankResponse(raw, objectMapper);
            if (!parsed.success()) {
                LOGGER.warn("Reranker returned malformed output for query id {}", queryId);
            }
            return parsed;
        } catch (Exception ex) {
            LOGGER.warn("Reranker request failed for query id {}", queryId, ex);
            return RerankDecision.failure();
        }
    }

    static RerankDecision parseRerankResponse(String rawResponse, ObjectMapper mapper) {
        if (rawResponse == null || rawResponse.trim().isEmpty()) {
            return RerankDecision.failure();
        }
        String json = extractJsonObject(rawResponse);
        if (json.isBlank()) {
            return RerankDecision.failure();
        }
        try {
            JsonNode root = mapper.readTree(json);
            LinkedHashMap<String, Double> ranked = new LinkedHashMap<>();
            JsonNode rankedNode = root.path("ranked");
            if (!rankedNode.isArray()) {
                rankedNode = root.path("kept");
            }
            if (rankedNode.isArray()) {
                for (JsonNode item : rankedNode) {
                    if (item == null || item.isNull()) {
                        continue;
                    }
                    String id = item.path("id").asText("").trim();
                    if (id.isBlank()) {
                        continue;
                    }
                    double score = clampPercent(item.path("score").asDouble(0d));
                    ranked.putIfAbsent(id, round(score, 2));
                }
            }
            return new RerankDecision(ranked, true);
        } catch (Exception ignored) {
            return RerankDecision.failure();
        }
    }

    private static String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return "";
        }
        return raw.substring(start, end + 1).trim();
    }

    private String callAgent(String userPrompt) throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", SYSTEM_PROMPT));
        messages.add(message("user", userPrompt));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", messages);
        body.put("temperature", 0.0);
        body.put("max_completion_tokens", MAX_COMPLETION_TOKENS);
        body.put("stream", false);
        body.put("retrieval_method", "none");
        body.put("reasoning_effort", "low");

        String requestJson = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(agentUrl.trim()))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + agentKey.trim())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Reranker upstream returned status " + response.statusCode());
        }
        return extractContent(response.body());
    }

    private String extractContent(String json) throws Exception {
        if (json == null || json.trim().isEmpty()) {
            return "";
        }
        JsonNode root = objectMapper.readTree(json);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return "";
        }
        JsonNode firstChoice = choices.get(0);
        JsonNode message = firstChoice.path("message");
        String fromMessage = extractTextContent(message.path("content"));
        if (!fromMessage.isBlank()) {
            return fromMessage;
        }
        return extractTextContent(firstChoice.path("delta").path("content"));
    }

    private String extractTextContent(JsonNode contentNode) {
        if (contentNode == null || contentNode.isNull()) {
            return "";
        }
        if (contentNode.isTextual()) {
            return contentNode.asText("").trim();
        }
        if (contentNode.isObject()) {
            return contentNode.path("text").asText("").trim();
        }
        if (contentNode.isArray()) {
            StringJoiner joiner = new StringJoiner("\n");
            for (JsonNode item : contentNode) {
                if (item.isTextual()) {
                    String value = item.asText("").trim();
                    if (!value.isBlank()) {
                        joiner.add(value);
                    }
                } else if (item.isObject()) {
                    String value = item.path("text").asText("").trim();
                    if (!value.isBlank()) {
                        joiner.add(value);
                    }
                }
            }
            return joiner.toString().trim();
        }
        return contentNode.asText("").trim();
    }

    private String buildUserPrompt(String queryId, String queryMatnArabic, List<Map<String, String>> candidates) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query_id", queryId == null ? "" : queryId.trim());
        payload.put("query_matn_ar", queryMatnArabic);
        payload.put("candidates", candidates);
        String inputJson = objectMapper.writeValueAsString(payload);

        return "Input:\n" + inputJson + "\n\n"
                + "Task:\n"
                + "- Score every candidate by semantic meaning only.\n"
                + "- Ignore narrator-chain overlap and generic words.\n"
                + "- Do not omit, remove, or drop any candidate.\n"
                + "- Score every item from 0 to 100.\n"
                + "- Return all candidates exactly once in best-to-worst order.\n\n"
                + "Return STRICT JSON ONLY with this schema:\n"
                + "{\"ranked\":[{\"id\":\"...\",\"score\":0}]}";
    }

    private List<Map<String, String>> compactCandidates(List<RerankCandidate> candidates) {
        List<Map<String, String>> compact = new ArrayList<>();
        int max = Math.min(MAX_CANDIDATES, candidates.size());
        for (int i = 0; i < max; i++) {
            RerankCandidate candidate = candidates.get(i);
            if (candidate == null || candidate.id() == null || candidate.id().isBlank()) {
                continue;
            }
            String matn = normalizeForPrompt(candidate.matnArabic(), MAX_CANDIDATE_CHARS);
            if (matn.isBlank()) {
                continue;
            }
            Map<String, String> item = new LinkedHashMap<>();
            item.put("id", candidate.id().trim());
            item.put("matn_ar", matn);
            compact.add(item);
        }
        return compact;
    }

    private String normalizeForPrompt(String rawText, int maxChars) {
        String normalized = SemanticTextNormalizer.normalizeMatn(rawText, maxChars * 2);
        if (normalized.isBlank()) {
            return "";
        }
        String compact = normalized.replaceAll("\\s+", " ").trim();
        if (compact.length() <= maxChars) {
            return compact;
        }
        return compact.substring(0, maxChars).trim();
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", role);
        map.put("content", content);
        return map;
    }

    private static int readIntSetting(String key, int defaultValue) {
        String value = firstNonEmpty(System.getProperty(key), System.getenv(key));
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
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

    private static double clampPercent(double value) {
        if (value < 0d) {
            return 0d;
        }
        if (value > 100d) {
            return 100d;
        }
        return value;
    }

    private static double round(double value, int precision) {
        double scale = Math.pow(10, precision);
        return Math.round(value * scale) / scale;
    }

    public record RerankCandidate(String id, String matnArabic, double retrievalPercent) {
    }

    public record RerankDecision(Map<String, Double> rankedScores, boolean success) {

        public static RerankDecision failure() {
            return new RerankDecision(new LinkedHashMap<>(), false);
        }
    }
}
