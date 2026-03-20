package com.rewayaat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Quick test of AI tagging for Ayat al-Kursi (2:255)
 * to verify the AI agent assigns appropriate tags.
 */
public class TestAyatKursiTagging {

    public static void main(String[] args) throws Exception {
        String agentKey = System.getenv().getOrDefault("QURAN_TAGGING_AI_AGENT_KEY",
                System.getenv().getOrDefault("SUMMARY_AI_AGENT_KEY", ""));
        String agentUrl = "https://rercls6rqu77j57ntpfvsicy.agents.do-ai.run/api/v1/chat/completions";

        if (agentKey.isEmpty()) {
            System.err.println("Set QURAN_TAGGING_AI_AGENT_KEY");
            System.exit(1);
        }

        ObjectMapper mapper = new ObjectMapper();

        // Build test payload with Ayat al-Kursi
        ObjectNode payload = mapper.createObjectNode();
        payload.put("task", "quran_verse_tagging");
        payload.put("instructions", """
                You are an expert Quranic verse classification system.
                Classify the verse into taxonomy slugs. Return JSON:
                {"documents":[{"id":"verse-id","tags":["slug-1","slug-2"]}]}
                """);

        // Sample taxonomy
        ArrayNode taxonomy = mapper.createArrayNode();
        taxonomy.add("tawhid | Divine Oneness | category=belief");
        taxonomy.add("signs-of-god | Signs of God (Ayat) | category=belief | parent=tawhid");
        taxonomy.add("knowledge | Knowledge | category=belief");
        taxonomy.add("throne-of-god | Throne of God (Arsh/Kursi) | category=belief | parent=tawhid");
        taxonomy.add("faith | Faith and Belief | category=belief");
        taxonomy.add("disbelief | Disbelief/Kufr | category=belief | parent=faith");
        taxonomy.add("creation | Creation/Signs of God | category=belief | parent=tawhid");
        taxonomy.add("oneness-of-god | Oneness of God | category=belief | parent=tawhid");
        payload.set("taxonomy", taxonomy);

        // Test verse: Ayat al-Kursi
        ArrayNode documents = mapper.createArrayNode();
        ObjectNode doc = mapper.createObjectNode();
        doc.put("id", "2:255");
        doc.put("reference", "Al-Baqarah 255 (Ayat al-Kursi)");
        doc.put("english", "Allah - there is no deity except Him, the Ever-Living, the Sustainer of existence. Neither drowsiness overtakes Him nor sleep. To Him belongs whatever is in the heavens and whatever is on the earth. Who is it that can intercede with Him except by His permission? He knows what is before them and what is behind them, and they encompass not a thing of His knowledge except for what He wills. His Kursi extends over the heavens and the earth, and their preservation tires Him not. And He is the Most High, the Most Great.");
        doc.put("arabic", "ٱللَّهُ لَآ إِلَـٰهَ إِلَّا هُوَ ٱلْحَىُّ ٱلْقَيُّومُ ۚ لَا تَأْخُذُهُۥ سِنَةٌۭ وَلَا نَوْمٌ ۚ لَّهُۥ مَا فِى ٱلسَّمَـٰوَٰتِ وَمَا فِى ٱلْأَرْضِ");
        documents.add(doc);
        payload.set("documents", documents);

        // Make request
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", "task=quran_verse_tagging"));
        messages.add(Map.of("role", "user", "content", mapper.writeValueAsString(payload)));

        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.set("messages", mapper.valueToTree(messages));
        requestBody.put("temperature", 0.1);
        requestBody.put("max_completion_tokens", 500);
        requestBody.put("stream", false);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(agentUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + agentKey)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestBody)))
                .build();

        System.out.println("Testing AI tagging with Ayat al-Kursi (2:255)...\n");

        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            System.err.println("FAILED: Status " + response.statusCode());
            System.err.println(response.body());
            System.exit(1);
        }

        JsonNode root = mapper.readTree(response.body());
        String content = root.path("choices").get(0)
                .path("message").path("content").asText();

        System.out.println("AI Response:");
        System.out.println(content);

        JsonNode result = mapper.readTree(content);
        JsonNode tags = result.path("documents").get(0).path("tags");

        System.out.println("\nAssigned tags:");
        List<String> tagList = new ArrayList<>();
        if (tags.isArray()) {
            for (JsonNode tag : tags) {
                String tagText = tag.asText();
                tagList.add(tagText);
                System.out.println("  ✓ " + tagText);
            }
        }

        // Verify expected tags
        System.out.println("\nVerifying expected tags:");
        String[] expected = {"tawhid", "signs-of-god", "knowledge", "throne-of-god"};
        for (String exp : expected) {
            boolean found = tagList.stream().anyMatch(t -> t.equals(exp));
            System.out.println("  " + (found ? "✓" : "✗") + " " + exp);
        }

        if (tagList.isEmpty()) {
            System.out.println("\nWARNING: No tags assigned. AI may need better instructions.");
        } else {
            System.out.println("\nSUCCESS: AI assigned " + tagList.size() + " tags");
        }
    }
}
