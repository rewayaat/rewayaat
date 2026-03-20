package com.rewayaat.tools;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rewayaat.config.ESClientProvider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tags Quranic verses in the rewayaat_quran index using the 171-tag taxonomy.
 *
 * Processes verses surah-by-surah using DigitalOcean Gradient AI for LLM-based tagging.
 * Only primary tags are assigned to Quran verses (secondary tags are hadith-only).
 *
 * Environment variables:
 * - QURAN_VERSES_INDEX: Index name (default: rewayaat_quran)
 * - QURAN_TAGGING_AI_AGENT_URL: AI agent endpoint
 * - QURAN_TAGGING_AI_AGENT_KEY: API key for the AI agent
 * - QURAN_TAGGING_BATCH_SIZE: Number of surahs to process per batch (default: 1)
 * - QURAN_TAGGING_DRY_RUN: Preview changes without applying (default: false)
 * - QURAN_TAGGING_START_SURAH: First surah to process (default: 1)
 * - QURAN_TAGGING_END_SURAH: Last surah to process (default: 114)
 * - QURAN_TAGGING_CHECKPOINT_FILE: Checkpoint file path (default: /tmp/quran-tagging-checkpoint.json)
 */
public final class QuranVerseTaggingTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(3);
    private static final boolean DRY_RUN = readBoolean("QURAN_TAGGING_DRY_RUN", false);
    private static final int START_SURAH = readInt("QURAN_TAGGING_START_SURAH", 1);
    private static final int END_SURAH = readInt("QURAN_TAGGING_END_SURAH", 114);
    private static final String CHECKPOINT_FILE = readString("QURAN_TAGGING_CHECKPOINT_FILE",
            "/tmp/quran-tagging-checkpoint.json");
    private static final int RETRY_DELAY_MS = readInt("QURAN_TAGGING_RETRY_DELAY_MS", 2000);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String baseUrl = buildBaseUrl();
    private final String index = readString("QURAN_VERSES_INDEX", "rewayaat_quran");
    private final String agentUrl = readString("QURAN_TAGGING_AI_AGENT_URL",
            "https://rercls6rqu77j57ntpfvsicy.agents.do-ai.run/api/v1/chat/completions");
    private final String agentKey = readString("QURAN_TAGGING_AI_AGENT_KEY",
            readString("SUMMARY_AI_AGENT_KEY", ""));

    /**
     * Checkpoint state for resumable progress tracking.
     */
    private static class CheckpointState {
        private final Set<String> processedSurahs = new LinkedHashSet<>();
        private long totalVersesProcessed = 0;
        private long totalVersesChanged = 0;
        private final long startTime = System.currentTimeMillis();

        public synchronized void addProcessedSurah(String surahId) {
            processedSurahs.add(surahId);
        }

        public synchronized boolean isSurahProcessed(String surahId) {
            return processedSurahs.contains(surahId);
        }

        public synchronized void addVersesProcessed(long count) {
            totalVersesProcessed += count;
        }

        public synchronized void addVersesChanged(long count) {
            totalVersesChanged += count;
        }

        public synchronized String toJson() throws Exception {
            return MAPPER.writeValueAsString(Map.of(
                "processedSurahs", new ArrayList<>(processedSurahs),
                "totalVersesProcessed", totalVersesProcessed,
                "totalVersesChanged", totalVersesChanged,
                "startTime", startTime
            ));
        }

        public static CheckpointState fromJson(String json) throws Exception {
            JsonNode node = MAPPER.readTree(json);
            CheckpointState state = new CheckpointState();
            if (node.has("processedSurahs")) {
                for (JsonNode surah : node.path("processedSurahs")) {
                    state.processedSurahs.add(surah.asText());
                }
            }
            if (node.has("totalVersesProcessed")) {
                state.totalVersesProcessed = node.path("totalVersesProcessed").asLong();
            }
            if (node.has("totalVersesChanged")) {
                state.totalVersesChanged = node.path("totalVersesChanged").asLong();
            }
            return state;
        }

        public synchronized long getElapsedMinutes() {
            return (System.currentTimeMillis() - startTime) / 60000;
        }
    }

    public static void main(String[] args) throws Exception {
        new QuranVerseTaggingTool().run();
        System.exit(0);
    }

    private void run() throws Exception {
        List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy = TopicTaxonomySupport.loadBundledTaxonomy();
        if (taxonomy.isEmpty()) {
            throw new IllegalStateException("Bundled taxonomy.json is empty.");
        }

        // Filter for primary tags only (secondary tags are hadith-only)
        List<TopicTaxonomySupport.TopicTaxonomyEntry> primaryTaxonomy = taxonomy.stream()
                .filter(e -> "primary".equals(e.tagType()))
                .toList();

        if (primaryTaxonomy.isEmpty()) {
            throw new IllegalStateException("No primary tags found in taxonomy.");
        }

        if (agentKey.isBlank()) {
            throw new IllegalStateException("QURAN_TAGGING_AI_AGENT_KEY or SUMMARY_AI_AGENT_KEY is required.");
        }

        Set<String> allowedSlugs = TopicTaxonomySupport.slugSet(primaryTaxonomy);
        Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> taxonomyBySlug = TopicTaxonomySupport.indexBySlug(primaryTaxonomy);

        // Load or initialize checkpoint
        CheckpointState checkpoint = loadCheckpoint();
        System.out.printf("Starting from checkpoint: processedSurahs=%d versesProcessed=%d versesChanged=%d%n",
                checkpoint.processedSurahs.size(), checkpoint.totalVersesProcessed, checkpoint.totalVersesChanged);

        try {
            for (int surahNumber = START_SURAH; surahNumber <= END_SURAH; surahNumber++) {
                String surahId = String.valueOf(surahNumber);

                if (checkpoint.isSurahProcessed(surahId)) {
                    System.out.println("Skipping already processed Surah " + surahNumber);
                    continue;
                }

                System.out.println("Processing Surah " + surahNumber + "...");

                // Fetch all verses for this surah
                List<JsonNode> verses = fetchVersesForSurah(surahNumber);
                if (verses.isEmpty()) {
                    System.out.println("  No verses found for Surah " + surahNumber);
                    checkpoint.addProcessedSurah(surahId);
                    saveCheckpoint(checkpoint);
                    continue;
                }

                // Tag verses using AI
                long versesChanged = tagSurahVerses(verses, primaryTaxonomy, allowedSlugs, taxonomyBySlug);

                checkpoint.addProcessedSurah(surahId);
                checkpoint.addVersesProcessed(verses.size());
                checkpoint.addVersesChanged(versesChanged);

                System.out.printf("  Completed Surah %d: %d verses, %d changed%n",
                        surahNumber, verses.size(), versesChanged);

                // Save checkpoint after each surah
                saveCheckpoint(checkpoint);
            }

            // Final checkpoint save and delete
            saveCheckpoint(checkpoint);
            System.out.println("Final checkpoint saved, deleting checkpoint file...");
            try {
                Files.deleteIfExists(Paths.get(CHECKPOINT_FILE));
            } catch (Exception e) {
                System.err.printf("Failed to delete checkpoint file: %s%n", e.getMessage());
            }

        } catch (Exception e) {
            System.err.println("Error during processing: " + e.getMessage());
            e.printStackTrace();
            saveCheckpoint(checkpoint);
            throw e;
        }

        long elapsed = checkpoint.getElapsedMinutes();
        System.out.printf("Quran verse tagging complete. SurahsProcessed=%d VersesProcessed=%d VersesChanged=%d DryRun=%s Elapsed=%dmin%n",
                checkpoint.processedSurahs.size(), checkpoint.totalVersesProcessed,
                checkpoint.totalVersesChanged, DRY_RUN, elapsed);
    }

    /**
     * Fetches all verses for a given surah number from Elasticsearch.
     */
    private List<JsonNode> fetchVersesForSurah(int surahNumber) throws Exception {
        String endpoint = baseUrl + "/" + index + "/_search";
        String query = String.format(Locale.ROOT,
                "{\"size\":1000,\"query\":{\"term\":{\"surah_number\":%d}},\"sort\":[\"ayah_number\"]}",
                surahNumber);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(query))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Elasticsearch query failed: " + response.statusCode() + " " + response.body());
        }

        JsonNode root = MAPPER.readTree(response.body());
        JsonNode hits = root.path("hits").path("hits");

        List<JsonNode> verses = new ArrayList<>();
        for (JsonNode hit : hits) {
            verses.add(hit);
        }
        return verses;
    }

    /**
     * Tags all verses in a surah using AI classification.
     * Splits large surahs into chunks to avoid token limits.
     */
    private long tagSurahVerses(List<JsonNode> verses,
                                List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy,
                                Set<String> allowedSlugs,
                                Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> taxonomyBySlug) throws Exception {

        long changedCount = 0;
        int batchSize = 50; // Process surahs in chunks of 50 verses

        for (int i = 0; i < verses.size(); i += batchSize) {
            int endIndex = Math.min(i + batchSize, verses.size());
            List<JsonNode> batch = verses.subList(i, endIndex);

            // Build AI payload for this batch
            String aiPrompt = buildSurahAiPrompt(batch, taxonomy);

            // Call AI agent
            String completion = callAgent("quran_verse_tagging", aiPrompt, estimateMaxTokens(batch));
            Map<String, List<String>> assignments = TopicTaxonomySupport.parseTagAssignments(completion, allowedSlugs);

            // Apply tags to verses in this batch
            for (JsonNode hit : batch) {
                String verseId = hit.path("_id").asText();
                JsonNode source = hit.path("_source");
                List<String> existingTags = parseExistingTags(source.path("topic_tags"));
                List<String> assignedTags = assignments.getOrDefault(verseId, List.of());

                if (!assignedTags.equals(existingTags)) {
                    if (!DRY_RUN) {
                        updateVerseTags(verseId, assignedTags);
                    }
                    changedCount++;
                }
            }
        }

        return changedCount;
    }

    /**
     * Builds the AI prompt for tagging all verses in a surah.
     */
    private String buildSurahAiPrompt(List<JsonNode> verses,
                                     List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy) throws IOException {
        ArrayNode documents = MAPPER.createArrayNode();

        for (JsonNode hit : verses) {
            String verseId = hit.path("_id").asText();
            JsonNode source = hit.path("_source");

            String surahName = source.path("surah_name_english").asText("");
            String verseRef = surahName + " " + source.path("ayah_number").asText();
            String english = source.path("text_english").asText("");
            String arabic = source.path("text_arabic").asText("");

            ObjectNode doc = MAPPER.createObjectNode();
            doc.put("id", verseId);
            doc.put("reference", verseRef);
            doc.put("english", cap(english, 500));
            doc.put("arabic", cap(arabic, 300));

            documents.add(doc);
        }

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("task", "quran_verse_tagging");
        payload.put("instructions", """
                You are an expert Quranic verse classification system specializing in Islamic theological taxonomy.

                Your task is to classify Quranic verses (ayat) into controlled taxonomy slugs.

                CLASSIFICATION RULES:
                1. Assign ONLY PRIMARY tags (conceptual/theological themes). NEVER assign secondary tags (biographical/historical).
                2. Assign all tags that genuinely apply where the verse substantively addresses that theme.
                3. Most verses will have 2-4 primary tags; complex narrative verses may have 5-7 tags.
                4. Every verse must receive at least one primary tag.
                5. Choose the most specific child tag when the verse clearly supports it; otherwise choose the narrowest defensible parent.
                6. Do not add both a parent and its child—the system adds ancestors automatically.
                7. Do not invent slugs—only use tags from the provided taxonomy.

                QURAN-SPECIFIC GUIDELINES:
                - Prophet tags (musa, ibrahim, isa, nuh, etc.): Use when the verse narrates stories or lessons about specific prophets
                - Theological tags (tawhid, signs-of-god, creation, unseen): Use for verses about God's attributes, creation, or metaphysical concepts
                - Ethical tags (taqwa, justice, charity, patience): Use for verses prescribing moral behavior or character traits
                - Social/legal tags (family, warfare-jihad, halal, equality): Use for verses with social rulings or guidance
                - Worship tags (prayer, fasting, hajj, zakat): Use for verses about ritual worship
                - Narrative tags (previous-nations, pharaoh): Use for verses containing historical stories

                AVOID generic umbrella tags unless the verse is explicitly about that umbrella topic:
                - Do NOT use faith merely because the verse mentions belief
                - Do NOT use knowledge merely because the verse mentions knowing or learning
                - Do NOT use quran for verses that are self-referential (about the Quran itself)

                For well-known verses with established Shia tafsir:
                - 2:255 (Ayat al-Kursi): tawhid, signs-of-god, knowledge, throne-of-god
                - 5:55 (Wilayah verse): wilayah, imamate, leadership
                - 33:33 (Tathir verse): ahl-al-bayt, purity, imamate (as contextual tafsir, not direct content)
                - 112 (Surah Al-Ikhlas): tawhid, oneness-of-god

                RESPONSE FORMAT:
                Return ONLY valid JSON in this exact format:
                {"documents":[{"id":"verse-id","tags":["slug-1","slug-2"]}]}

                Example:
                Input: {"id":"2:255","reference":"Al-Baqarah 255","english":"Allah – there is no deity except Him..."}
                Output: {"id":"2:255","tags":["tawhid","signs-of-god","knowledge","throne-of-god"]}
                """);
        payload.set("taxonomy", MAPPER.valueToTree(TopicTaxonomySupport.compactPromptTaxonomy(taxonomy)));
        payload.set("documents", documents);

        return MAPPER.writeValueAsString(payload);
    }

    /**
     * Updates the topic_tags field for a single verse using the Elasticsearch client.
     */
    private void updateVerseTags(String verseId, List<String> tags) throws Exception {
        ElasticsearchClient client = new ESClientProvider().client();

        // Build the update document
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("topic_tags", tags);

        // Use the ES client's update method which handles ID encoding properly
        client.update(u -> u
                        .index(index)
                        .id(verseId)
                        .doc(doc),
                Map.class
        );
    }

    private String callAgent(String task, String userPrompt, int maxCompletionTokens) throws Exception {
        return callAgentWithRetry(task, userPrompt, maxCompletionTokens, 0);
    }

    private String callAgentWithRetry(String task, String userPrompt, int maxCompletionTokens, int attempt) throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", "task=" + task));
        messages.add(message("user", userPrompt));

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.1);
        requestBody.put("max_completion_tokens", maxCompletionTokens);
        requestBody.put("stream", false);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(agentUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + agentKey)
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(requestBody)))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            if (attempt < 3) {
                Thread.sleep(RETRY_DELAY_MS * (attempt + 1));
                return callAgentWithRetry(task, userPrompt, maxCompletionTokens, attempt + 1);
            }
            throw new IOException("AI agent request failed: " + response.statusCode() + " " + response.body());
        }

        JsonNode root = MAPPER.readTree(response.body());
        JsonNode choices = root.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            return choices.get(0).path("message").path("content").asText("");
        }

        throw new IOException("Invalid AI agent response: " + response.body());
    }

    private int estimateMaxTokens(List<JsonNode> verses) {
        int baseTokens = 800;
        int perVerseTokens = 180;
        int estimated = baseTokens + (verses.size() * perVerseTokens);
        // Cap at model's maximum completion tokens (16000 for safety)
        return Math.min(estimated, 16000);
    }

    private List<String> parseExistingTags(JsonNode tagsNode) {
        if (tagsNode == null || !tagsNode.isArray()) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (JsonNode tag : tagsNode) {
            String slug = tag.asText("");
            if (!slug.isBlank()) {
                tags.add(slug);
            }
        }
        return tags;
    }

    private String cap(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", role);
        msg.put("content", content);
        return msg;
    }

    private String buildBaseUrl() {
        String host = System.getenv().getOrDefault("ELASTIC_HOST", "localhost");
        String port = System.getenv().getOrDefault("ELASTIC_PORT", "9200");
        return "http://" + host + ":" + port;
    }

    private CheckpointState loadCheckpoint() {
        try {
            if (Files.exists(Paths.get(CHECKPOINT_FILE))) {
                String content = Files.readString(Paths.get(CHECKPOINT_FILE));
                return CheckpointState.fromJson(content);
            }
        } catch (Exception e) {
            System.err.println("Failed to load checkpoint: " + e.getMessage());
        }
        return new CheckpointState();
    }

    private void saveCheckpoint(CheckpointState checkpoint) {
        try {
            Files.writeString(Paths.get(CHECKPOINT_FILE), checkpoint.toJson(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            System.err.println("Failed to save checkpoint: " + e.getMessage());
        }
    }

    private static String readString(String key, String defaultValue) {
        String value = System.getProperty(key);
        if (value != null && !value.trim().isEmpty()) {
            return value.trim();
        }
        value = System.getenv(key);
        if (value != null && !value.trim().isEmpty()) {
            return value.trim();
        }
        return defaultValue;
    }

    private static int readInt(String key, int defaultValue) {
        String value = readString(key, "");
        if (value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean readBoolean(String key, boolean defaultValue) {
        String value = readString(key, "");
        if (value.isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }
}
