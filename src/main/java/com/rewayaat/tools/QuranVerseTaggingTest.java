package com.rewayaat.tools;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.config.ESClientProvider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Test utility to verify QuranVerseTaggingTool setup before running full tagging.
 *
 * Usage:
 * export QURAN_TAGGING_AI_AGENT_KEY=iB1SdywA9COpiuS4o985aHxzI4m5f52Z
 * java com.rewayaat.tools.QuranVerseTaggingTest
 */
public class QuranVerseTaggingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INDEX = "rewayaat_quran";
    private static final String AGENT_URL = "https://rercls6rqu77j57ntpfvsicy.agents.do-ai.run/api/v1/chat/completions";

    public static void main(String[] args) throws Exception {
        String agentKey = System.getenv().getOrDefault("QURAN_TAGGING_AI_AGENT_KEY",
                System.getenv().getOrDefault("SUMMARY_AI_AGENT_KEY", ""));

        if (agentKey.isEmpty()) {
            System.err.println("ERROR: QURAN_TAGGING_AI_AGENT_KEY or SUMMARY_AI_AGENT_KEY must be set");
            System.err.println("Example: export QURAN_TAGGING_AI_AGENT_KEY=your_key_here");
            System.exit(1);
        }

        System.out.println("=== Quran Verse Tagging Test ===\n");

        // Test 1: Check Elasticsearch connection and index
        System.out.println("Test 1: Checking Elasticsearch...");
        if (!checkElasticsearch()) {
            System.err.println("FAILED: Cannot connect to Elasticsearch or index doesn't exist");
            System.err.println("Make sure Elasticsearch is running and rewayaat_quran index exists");
            System.exit(1);
        }
        System.out.println("✓ Elasticsearch connection OK\n");

        // Test 2: Check if Quran data exists
        System.out.println("Test 2: Checking for Quran data...");
        long verseCount = countVerses();
        if (verseCount == 0) {
            System.err.println("FAILED: No Quran verses found in index");
            System.err.println("Run: java com.rewayaat.loader.quran.QuranVerseLoader");
            System.exit(1);
        }
        System.out.println("✓ Found " + verseCount + " verses in index\n");

        // Test 3: Load taxonomy
        System.out.println("Test 3: Loading taxonomy...");
        List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy = TopicTaxonomySupport.loadBundledTaxonomy();
        List<TopicTaxonomySupport.TopicTaxonomyEntry> primaryTaxonomy = taxonomy.stream()
                .filter(e -> "primary".equals(e.tagType()))
                .toList();
        System.out.println("✓ Loaded " + taxonomy.size() + " total tags (" + primaryTaxonomy.size() + " primary)\n");

        // Test 4: Test AI agent connection with a small sample
        System.out.println("Test 4: Testing AI agent connection...");
        testAiAgent(agentKey, primaryTaxonomy);

        System.out.println("\n=== All Tests Passed! ===");
        System.out.println("\nTo run the full tagging:");
        System.out.println("  export QURAN_TAGGING_AI_AGENT_KEY=" + agentKey);
        System.out.println("  java com.rewayaat.tools.QuranVerseTaggingTool");
    }

    private static boolean checkElasticsearch() {
        try {
            ElasticsearchClient client = new ESClientProvider().client();
            return client.indices().exists(e -> e.index(INDEX)).value();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return false;
        }
    }

    private static long countVerses() {
        try {
            ElasticsearchClient client = new ESClientProvider().client();
            return client.count(c -> c.index(INDEX)).count();
        } catch (Exception e) {
            System.err.println("Error counting verses: " + e.getMessage());
            return 0;
        }
    }

    private static void testAiAgent(String agentKey, List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy) throws Exception {
        // Build a minimal test payload
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task", "quran_verse_tagging");
        payload.put("instructions", "You are an expert Quranic verse classification system. Classify the verse into taxonomy slugs. Return JSON: {\"documents\":[{\"id\":\"verse-id\",\"tags\":[\"slug-1\",\"slug-2\"]}]}");

        // Add compact taxonomy
        List<String> compactTaxonomy = TopicTaxonomySupport.compactPromptTaxonomy(taxonomy);
        payload.put("taxonomy", compactTaxonomy.subList(0, Math.min(20, compactTaxonomy.size())));

        // Add a test verse (Al-Fatihah 1)
        List<Map<String, Object>> documents = new ArrayList<>();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", "1:1");
        doc.put("reference", "Al-Fatihah 1");
        doc.put("english", "In the name of Allah, the Entirely Merciful, the Especially Merciful.");
        doc.put("arabic", "بِسْمِ ٱللَّهِ ٱلرَّحْمَـٰنِ ٱلرَّحِيمِ");
        documents.add(doc);
        payload.put("documents", documents);

        // Make request
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", "task=quran_verse_tagging"));
        messages.add(Map.of("role", "user", "content", MAPPER.writeValueAsString(payload)));

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.1);
        requestBody.put("max_completion_tokens", 500);
        requestBody.put("stream", false);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(AGENT_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + agentKey)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(requestBody)))
                .build();

        System.out.println("Sending test request to AI agent...");
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            System.err.println("FAILED: AI agent returned status " + response.statusCode());
            System.err.println("Response: " + response.body());
            System.exit(1);
        }

        JsonNode root = MAPPER.readTree(response.body());
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.size() == 0) {
            System.err.println("FAILED: Invalid AI response format");
            System.err.println("Response: " + response.body());
            System.exit(1);
        }

        String content = choices.get(0).path("message").path("content").asText("");
        System.out.println("AI Response: " + content.substring(0, Math.min(200, content.length())) + "...");

        // Parse and validate response
        JsonNode result = MAPPER.readTree(content);
        JsonNode docs = result.path("documents");
        if (!docs.isArray() || docs.size() == 0) {
            System.err.println("FAILED: AI didn't return documents array");
            System.exit(1);
        }

        JsonNode firstDoc = docs.get(0);
        String id = firstDoc.path("id").asText("");
        JsonNode tags = firstDoc.path("tags");

        if (!"1:1".equals(id)) {
            System.err.println("FAILED: AI returned wrong id: " + id);
            System.exit(1);
        }

        if (!tags.isArray()) {
            System.err.println("FAILED: AI didn't return tags array");
            System.exit(1);
        }

        List<String> tagList = new ArrayList<>();
        for (JsonNode tag : tags) {
            tagList.add(tag.asText());
        }

        System.out.println("✓ AI agent connection OK");
        System.out.println("✓ Verse 1:1 tagged with: " + tagList);

        if (tagList.isEmpty()) {
            System.err.println("WARNING: AI returned no tags for verse 1:1");
            System.out.println("This might be expected for Bismillah, but verify with a more substantive verse");
        }
    }
}
