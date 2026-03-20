package com.rewayaat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rewayaat.config.ESClientProvider;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Backfills the `topic_tags` field using a frozen taxonomy. The default path is conservative:
 * deterministic rule matches first, with optional AI refinement if TOPIC_TAGS_AI_AGENT_KEY is set.
 *
 * Checkpointing for resumable progress:
 * - TOPIC_TAGS_CHECKPOINT_FILE: Path to checkpoint file (default: /tmp/topic-tags-backfill-checkpoint.json)
 * - TOPIC_TAGS_CHECKPOINT_INTERVAL: Save checkpoint every N documents (default: 100)
 */
public final class TopicTagsBackfillTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);
    private static final int BATCH_SIZE = readInt("TOPIC_TAGS_BACKFILL_BATCH_SIZE", 200);
    private static final int LIMIT = readInt("TOPIC_TAGS_LIMIT", 0);
    private static final boolean FORCE = readBoolean("TOPIC_TAGS_FORCE", false);
    private static final boolean DRY_RUN = readBoolean("TOPIC_TAGS_DRY_RUN", false);
    private static final boolean USE_AI = readBoolean("TOPIC_TAGS_USE_AI", false);
    private static final boolean AI_ONLY_WHEN_EMPTY = readBoolean("TOPIC_TAGS_AI_ONLY_WHEN_EMPTY", true);
    private static final String CLASSIFIER_MODE = readString("TOPIC_TAGS_CLASSIFIER_MODE", "");
    private static final int AI_BATCH_SIZE = readInt("TOPIC_TAGS_AI_BATCH_SIZE", 4);
    private static final Duration AI_REQUEST_TIMEOUT = Duration.ofSeconds(readInt("TOPIC_TAGS_AI_TIMEOUT_SECONDS", 90));
    private static final long AI_RETRY_DELAY_MS = readInt("TOPIC_TAGS_AI_RETRY_DELAY_MS", 1500);
    private static final int PROGRESS_EVERY = readInt("TOPIC_TAGS_PROGRESS_EVERY", 500);
    private static final String SCROLL_KEEPALIVE = readString("TOPIC_TAGS_SCROLL", "6h");
    private static final String DEFAULT_FALLBACK_TAG = readString("TOPIC_TAGS_DEFAULT_FALLBACK_TAG", "knowledge");
    private static final int SLICE_ID = readInt("TOPIC_TAGS_SLICE_ID", -1);
    private static final int SLICE_MAX = readInt("TOPIC_TAGS_SLICE_MAX", 0);
    private static final String CHECKPOINT_FILE = readString("TOPIC_TAGS_CHECKPOINT_FILE", "/tmp/topic-tags-backfill-checkpoint.json");
    private static final int CHECKPOINT_INTERVAL = readInt("TOPIC_TAGS_CHECKPOINT_INTERVAL", 100);

    private static final Map<String, List<String>> EXTRA_SEEDS = TopicTaxonomySeedSupport.extraSeedsBySlug();
    private static final Set<String> HEADING_ONLY_SLUGS = TopicTaxonomySeedSupport.headingOnlySlugs();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String baseUrl = buildBaseUrl();
    private final String index = readString("REWAYAAT_INDEX", ESClientProvider.INDEX);
    private final String agentUrl = readString("TOPIC_TAGS_AI_AGENT_URL",
            readString("SUMMARY_AI_AGENT_URL",
                    "https://kbm2sc4qjqcubxjmkawniaei.agents.do-ai.run/api/v1/chat/completions"));
    private final String agentKey = readString("TOPIC_TAGS_AI_AGENT_KEY", readString("SUMMARY_AI_AGENT_KEY", ""));

    /**
     * Checkpoint state for resumable progress tracking.
     */
    private static class CheckpointState {
        private long seen = 0;
        private long changed = 0;
        private long aiClassified = 0;
        private final Set<String> processedIds = new LinkedHashSet<>();
        private final long startTime = System.currentTimeMillis();

        public synchronized void addProcessed(String id) {
            processedIds.add(id);
        }

        public synchronized boolean isProcessed(String id) {
            return processedIds.contains(id);
        }

        public synchronized void incrementSeen() {
            seen++;
        }

        public synchronized void incrementChanged(long count) {
            changed += count;
        }

        public synchronized void incrementAiClassified(long count) {
            aiClassified += count;
        }

        public synchronized long getSeen() {
            return seen;
        }

        public synchronized long getChanged() {
            return changed;
        }

        public synchronized long getAiClassified() {
            return aiClassified;
        }

        public synchronized String toJson() throws Exception {
            return MAPPER.writeValueAsString(Map.of(
                "seen", seen,
                "changed", changed,
                "aiClassified", aiClassified,
                "processedIds", new ArrayList<>(processedIds),
                "startTime", startTime
            ));
        }

        public static CheckpointState fromJson(String json) throws Exception {
            JsonNode node = MAPPER.readTree(json);
            CheckpointState state = new CheckpointState();
            if (node.has("seen")) state.seen = node.path("seen").asLong();
            if (node.has("changed")) state.changed = node.path("changed").asLong();
            if (node.has("aiClassified")) state.aiClassified = node.path("aiClassified").asLong();
            if (node.has("processedIds")) {
                JsonNode ids = node.path("processedIds");
                if (ids.isArray()) {
                    for (JsonNode id : ids) {
                        state.processedIds.add(id.asText());
                    }
                }
            }
            return state;
        }

        public synchronized long getElapsedMinutes() {
            return (System.currentTimeMillis() - startTime) / 60000;
        }
    }

    public static void main(String[] args) throws Exception {
        new TopicTagsBackfillTool().run();
        System.exit(0);
    }

    private void run() throws Exception {
        List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy = TopicTaxonomySupport.loadBundledTaxonomy();
        if (taxonomy.isEmpty()) {
            throw new IllegalStateException("Bundled taxonomy.json is empty.");
        }
        TopicTaggingMode mode = classifierMode();
        if (mode.requiresAi() && agentKey.isBlank()) {
            throw new IllegalStateException("TOPIC_TAGS_AI_AGENT_KEY is required when AI topic tagging is enabled.");
        }
        validateSliceConfig();
        ensureTopicTagsMapping();
        Set<String> allowedSlugs = TopicTaxonomySupport.slugSet(taxonomy);
        Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> taxonomyBySlug = TopicTaxonomySupport.indexBySlug(taxonomy);
        Map<String, SeedProfile> seedProfiles = buildSeedProfiles(taxonomy);

        // Load or initialize checkpoint
        CheckpointState checkpoint = loadCheckpoint();
        long startSeen = checkpoint.getSeen();
        System.out.printf("Starting from checkpoint: seen=%d changed=%d aiClassified=%d%n",
                startSeen, checkpoint.getChanged(), checkpoint.getAiClassified());

        String scrollId = null;
        List<PendingUpdate> pending = new ArrayList<>();
        List<PreparedNarration> aiPending = new ArrayList<>();
        int lastCheckpointSave = (int) startSeen;

        try {
            JsonNode page = startScroll();
            scrollId = page.path("_scroll_id").asText("");

            while (true) {
                ArrayNode hits = arrayNode(page.path("hits").path("hits"));
                if (hits == null || hits.isEmpty()) {
                    break;
                }

                for (JsonNode hit : hits) {
                    String docId = hit.path("_id").asText();

                    // Skip already processed documents
                    if (checkpoint.isProcessed(docId)) {
                        continue;
                    }

                    PreparedNarration prepared = classify(hit, seedProfiles, taxonomyBySlug, mode);
                    if (prepared == null) {
                        continue;
                    }
                    checkpoint.incrementSeen();
                    checkpoint.addProcessed(docId);

                    if (prepared.requiresAi(mode)) {
                        aiPending.add(prepared);
                        if (aiPending.size() >= Math.max(1, AI_BATCH_SIZE)) {
                            // Process AI batch sequentially
                            Map<String, List<String>> assignments = classifyWithAiBatch(
                                    aiPending, allowedSlugs, taxonomy, mode);
                            for (PreparedNarration narration : aiPending) {
                                List<String> assignedTags = assignments.getOrDefault(narration.id(), List.of());
                                PendingUpdate update = narration.resolve(assignedTags, taxonomyBySlug, mode);
                                if (update.changed()) {
                                    pending.add(update);
                                }
                                checkpoint.incrementAiClassified(1);
                            }
                            aiPending.clear();
                        }
                    } else {
                        PendingUpdate update = prepared.resolve(List.of(), taxonomyBySlug, mode);
                        if (update.changed()) {
                            pending.add(update);
                        }
                    }

                    // Flush pending updates
                    if (pending.size() >= BATCH_SIZE) {
                        long flushed = flushUpdates(pending);
                        checkpoint.incrementChanged(flushed);
                        pending.clear();
                    }

                    // Save checkpoint periodically
                    if (checkpoint.getSeen() - lastCheckpointSave >= CHECKPOINT_INTERVAL) {
                        saveCheckpoint(checkpoint);
                        lastCheckpointSave = (int) checkpoint.getSeen();
                        System.out.printf("Checkpoint saved: seen=%d changed=%d%n",
                                checkpoint.getSeen(), checkpoint.getChanged());
                    }

                    logProgress(checkpoint.getSeen(), checkpoint.getChanged(),
                            pending.size(), aiPending.size(), mode);

                    if (LIMIT > 0 && checkpoint.getSeen() - startSeen >= LIMIT) {
                        break;
                    }
                }

                if (LIMIT > 0 && checkpoint.getSeen() - startSeen >= LIMIT) {
                    break;
                }

                page = continueScroll(scrollId);
                scrollId = page.path("_scroll_id").asText(scrollId == null ? "" : scrollId);
            }

            // Process remaining AI batch
            if (!aiPending.isEmpty()) {
                Map<String, List<String>> assignments = classifyWithAiBatch(
                        aiPending, allowedSlugs, taxonomy, mode);
                for (PreparedNarration narration : aiPending) {
                    List<String> assignedTags = assignments.getOrDefault(narration.id(), List.of());
                    PendingUpdate update = narration.resolve(assignedTags, taxonomyBySlug, mode);
                    if (update.changed()) {
                        pending.add(update);
                    }
                    checkpoint.incrementAiClassified(1);
                }
                aiPending.clear();
            }

            // Flush remaining updates
            if (!pending.isEmpty()) {
                long flushed = flushUpdates(pending);
                checkpoint.incrementChanged(flushed);
                pending.clear();
            }

            // Final checkpoint save and delete
            saveCheckpoint(checkpoint);
            System.out.println("Final checkpoint saved, deleting checkpoint file...");
            try {
                Files.deleteIfExists(Paths.get(CHECKPOINT_FILE));
            } catch (Exception e) {
                System.err.printf("Failed to delete checkpoint file: %s%n", e.getMessage());
            }

        } finally {
            clearScroll(scrollId);
        }

        long elapsed = checkpoint.getElapsedMinutes();
        System.out.printf("Topic tag backfill complete. Seen=%d Changed=%d AiClassified=%d DryRun=%s Force=%s Mode=%s Slice=%s Elapsed=%dmin%n",
                checkpoint.getSeen(), checkpoint.getChanged(), checkpoint.getAiClassified(),
                DRY_RUN, FORCE, mode.externalValue(), sliceLabel(), elapsed);
    }

    private CheckpointState loadCheckpoint() {
        try {
            if (Files.exists(Paths.get(CHECKPOINT_FILE))) {
                String content = Files.readString(Paths.get(CHECKPOINT_FILE));
                System.out.println("Loaded checkpoint from " + CHECKPOINT_FILE);
                return CheckpointState.fromJson(content);
            }
        } catch (Exception e) {
            System.err.printf("Failed to load checkpoint: %s. Starting fresh.%n", e.getMessage());
        }
        return new CheckpointState();
    }

    private void saveCheckpoint(CheckpointState checkpoint) {
        try {
            String json = checkpoint.toJson();
            Files.writeString(Paths.get(CHECKPOINT_FILE), json,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            System.err.printf("Failed to save checkpoint: %s%n", e.getMessage());
        }
    }

    private PreparedNarration classify(JsonNode hit,
                                       Map<String, SeedProfile> seedProfiles,
                                       Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> taxonomyBySlug,
                                       TopicTaggingMode mode) {
        if (hit == null || hit.isMissingNode()) {
            return null;
        }
        String id = hit.path("_id").asText("");
        if (id.isBlank()) {
            return null;
        }
        JsonNode source = hit.path("_source");
        if (source == null || source.isMissingNode() || source.isNull()) {
            return null;
        }
        List<String> existing = readStringArray(source.path("topic_tags"));
        if (!FORCE && !existing.isEmpty() && !mode.reclassifiesTaggedDocs()) {
            return PreparedNarration.resolved(id, existing, existing);
        }

        String headingEnglish = TopicTaxonomySupport.normalizeEnglishForMatch(joinFields(
                source.path("book").asText(""),
                source.path("chapter").asText(""),
                source.path("section").asText("")));
        String bodyEnglish = TopicTaxonomySupport.normalizeEnglishForMatch(joinFields(
                source.path("semantic_significant_terms_source").asText("")));
        String headingArabic = TopicTaxonomySupport.normalizeArabicForMatch(joinFields(
                source.path("book").asText(""),
                source.path("chapter").asText(""),
                source.path("section").asText("")));
        String bodyArabic = TopicTaxonomySupport.normalizeArabicForMatch(joinFields(
                source.path("semantic_matn_source").asText(""),
                source.path("semantic_significant_terms_source").asText("")));

        List<ScoredTag> scoredTags = scoreTags(seedProfiles, headingEnglish, bodyEnglish, headingArabic, bodyArabic);
        List<String> ruleTags = chooseStrongRuleTags(scoredTags);
        if (ruleTags.isEmpty()) {
            ruleTags = chooseFallbackTags(scoredTags, source.path("book").asText(""), headingEnglish, headingArabic);
        }
        ruleTags = TopicTaxonomySeedSupport.refineSuggestedTags(
                source.path("book").asText(""),
                source.path("chapter").asText(""),
                joinFields(source.path("english").asText(""), source.path("semantic_significant_terms_source").asText("")),
                joinFields(source.path("arabic").asText(""), source.path("semantic_matn_source").asText("")),
                ruleTags,
                taxonomyBySlug);
        if (ruleTags.isEmpty()) {
            ruleTags = chooseFallbackTags(scoredTags, source.path("book").asText(""), headingEnglish, headingArabic);
        }
        return PreparedNarration.pending(id, existing, ruleTags, buildAiNarration(id, source, ruleTags, existing));
    }

    private List<ScoredTag> scoreTags(Map<String, SeedProfile> seedProfiles,
                                      String headingEnglish,
                                      String bodyEnglish,
                                      String headingArabic,
                                      String bodyArabic) {
        List<ScoredTag> scored = new ArrayList<>();
        for (Map.Entry<String, SeedProfile> entry : seedProfiles.entrySet()) {
            int score = entry.getValue().score(
                    headingEnglish,
                    bodyEnglish,
                    headingArabic,
                    bodyArabic,
                    !HEADING_ONLY_SLUGS.contains(entry.getKey()));
            if (score > 0) {
                scored.add(new ScoredTag(entry.getKey(), score));
            }
        }
        scored.sort(Comparator.comparingInt(ScoredTag::score).reversed().thenComparing(ScoredTag::slug));
        return scored;
    }

    private List<String> chooseStrongRuleTags(List<ScoredTag> scored) {
        List<String> tags = new ArrayList<>();
        for (ScoredTag item : scored) {
            if (item.score() < TopicTaxonomySeedSupport.minimumSuggestionScore(item.slug())) {
                continue;
            }
            tags.add(item.slug());
        }
        return tags;
    }

    private List<String> chooseFallbackTags(List<ScoredTag> scored,
                                            String book,
                                            String headingEnglish,
                                            String headingArabic) {
        if (scored != null && !scored.isEmpty()) {
            return List.of(scored.get(0).slug());
        }
        String bookEnglish = TopicTaxonomySupport.normalizeEnglishForMatch(book);
        String inferred = inferFallbackTag(bookEnglish, headingEnglish, headingArabic);
        if (!inferred.isBlank()) {
            return List.of(inferred);
        }
        return List.of(DEFAULT_FALLBACK_TAG);
    }

    private String inferFallbackTag(String bookEnglish,
                                    String headingEnglish,
                                    String headingArabic) {
        String combinedEnglish = (bookEnglish + " " + (headingEnglish == null ? "" : headingEnglish)).trim();
        String combinedArabic = headingArabic == null ? "" : headingArabic.trim();
        if (combinedEnglish.contains("ghayba") || combinedEnglish.contains("mahdi") || combinedArabic.contains("غيبه") || combinedArabic.contains("مهدي")) {
            return "imamate";
        }
        if (combinedEnglish.contains("tawhid") || combinedEnglish.contains("unity") || combinedArabic.contains("توحيد")) {
            return "faith";
        }
        if (combinedEnglish.contains("ziyarat") || combinedArabic.contains("زياره")) {
            return "ziyarat";
        }
        if (combinedEnglish.contains("zuhd") || combinedEnglish.contains("ascetic")) {
            return "asceticism";
        }
        if (combinedEnglish.contains("nahj") || combinedEnglish.contains("malik al ashtar") || combinedEnglish.contains("governor")) {
            return "leadership";
        }
        if (combinedEnglish.contains("faqih") || combinedEnglish.contains("permissible") || combinedEnglish.contains("impermissible")) {
            return "halal";
        }
        if (combinedEnglish.contains("ridha")) {
            return "imam-ridha";
        }
        if (combinedEnglish.contains("mumin")) {
            return "good-character";
        }
        if (combinedEnglish.contains("khisal") || combinedEnglish.contains("maani") || combinedEnglish.contains("amali") || combinedEnglish.contains("rare ahadith")) {
            return "knowledge";
        }
        return "";
    }

    private long appendResolvedAiUpdates(List<PreparedNarration> aiCandidates,
                                         List<PendingUpdate> pending,
                                         Set<String> allowedSlugs,
                                         List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy,
                                         Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> taxonomyBySlug,
                                         TopicTaggingMode mode) throws Exception {
        if (aiCandidates.isEmpty()) {
            return 0L;
        }
        Map<String, List<String>> assignments = classifyWithAiBatch(aiCandidates, allowedSlugs, taxonomy, mode);
        for (PreparedNarration candidate : aiCandidates) {
            PendingUpdate update = candidate.resolve(assignments.getOrDefault(candidate.id(), List.of()), taxonomyBySlug, mode);
            if (update.changed()) {
                pending.add(update);
            }
        }
        return aiCandidates.size();
    }

    private Map<String, List<String>> classifyWithAiBatch(List<PreparedNarration> aiCandidates,
                                                          Set<String> allowedSlugs,
                                                          List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy,
                                                          TopicTaggingMode mode) throws Exception {
        return classifyWithAiBatch(aiCandidates, allowedSlugs, taxonomy, mode, 0);
    }

    private Map<String, List<String>> classifyWithAiBatch(List<PreparedNarration> aiCandidates,
                                                          Set<String> allowedSlugs,
                                                          List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy,
                                                          TopicTaggingMode mode,
                                                          int depth) throws Exception {
        if (aiCandidates.isEmpty()) {
            return Map.of();
        }
        String text = buildAiBatchPromptPayload(aiCandidates, taxonomy, mode);
        if (text.isBlank()) {
            return Map.of();
        }
        int maxCompletionTokens = mode == TopicTaggingMode.AI_REFINE_ALL
                ? Math.max(900, aiCandidates.size() * 220)
                : Math.max(600, aiCandidates.size() * 140);
        try {
            String completion = callAgent("topic_tag_classification", text, maxCompletionTokens,
                    mode == TopicTaggingMode.AI_REFINE_ALL ? "high" : "medium");
            Map<String, List<String>> assignments = TopicTaxonomySupport.parseTagAssignments(completion, allowedSlugs);
            if (assignments.isEmpty() && aiCandidates.size() > 1) {
                return splitAiBatch(aiCandidates, allowedSlugs, taxonomy, mode, depth,
                        new IllegalStateException("AI classifier returned no usable document assignments."));
            }
            return assignments;
        } catch (Exception ex) {
            if (AI_RETRY_DELAY_MS > 0) {
                Thread.sleep(AI_RETRY_DELAY_MS);
            }
            if (aiCandidates.size() <= 1) {
                System.err.printf(Locale.ROOT,
                        "AI classification failed for %s; falling back to rule tags. Reason=%s%n",
                        aiCandidates.get(0).id(), rootMessage(ex));
                return Map.of();
            }
            return splitAiBatch(aiCandidates, allowedSlugs, taxonomy, mode, depth, ex);
        }
    }

    private Map<String, List<String>> splitAiBatch(List<PreparedNarration> aiCandidates,
                                                   Set<String> allowedSlugs,
                                                   List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy,
                                                   TopicTaggingMode mode,
                                                   int depth,
                                                   Exception ex) throws Exception {
        int midpoint = Math.max(1, aiCandidates.size() / 2);
        System.err.printf(Locale.ROOT,
                "AI batch classification failed at size=%d depth=%d; splitting batch. Reason=%s%n",
                aiCandidates.size(), depth, rootMessage(ex));
        Map<String, List<String>> assignments = new LinkedHashMap<>();
        assignments.putAll(classifyWithAiBatch(new ArrayList<>(aiCandidates.subList(0, midpoint)), allowedSlugs, taxonomy, mode, depth + 1));
        assignments.putAll(classifyWithAiBatch(new ArrayList<>(aiCandidates.subList(midpoint, aiCandidates.size())), allowedSlugs, taxonomy, mode, depth + 1));
        return assignments;
    }

    private String buildAiBatchPromptPayload(List<PreparedNarration> aiCandidates,
                                             List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy,
                                             TopicTaggingMode mode) throws IOException {
        ArrayNode documents = MAPPER.createArrayNode();
        for (PreparedNarration candidate : aiCandidates) {
            if (candidate.aiPayload() == null) {
                continue;
            }
            documents.add(candidate.aiPayload().deepCopy());
        }
        if (documents.isEmpty()) {
            return "";
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task", "topic_tag_classification");
        payload.put("instructions", mode.classificationInstructions());
        payload.put("taxonomy", TopicTaxonomySupport.compactPromptTaxonomy(taxonomy));
        payload.put("documents", documents);
        return MAPPER.writeValueAsString(payload);
    }

    private ObjectNode buildAiNarration(String id, JsonNode source, List<String> suggestedTags, List<String> existingTags) {
        String english = cleanText(source.path("english").asText(""));
        String arabic = cleanText(source.path("arabic").asText(""));
        String semanticTerms = cleanText(source.path("semantic_significant_terms_source").asText(""));
        String semanticMatn = cleanText(source.path("semantic_matn_source").asText(""));
        if (english.isBlank() && arabic.isBlank() && semanticTerms.isBlank() && semanticMatn.isBlank()) {
            return null;
        }
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", id);
        node.put("book", cleanText(source.path("book").asText("")));
        node.put("chapter", cleanText(source.path("chapter").asText("")));
        node.put("section", cleanText(source.path("section").asText("")));
        addArray(node.putArray("existing_tags"), existingTags);
        addArray(node.putArray("rule_suggestions"), suggestedTags);
        node.put("english", cap(english, 700));
        node.put("arabic", cap(arabic, 520));
        node.put("semantic_matn", cap(semanticMatn, 520));
        node.put("semantic_terms", cap(semanticTerms, 280));
        return node;
    }

    private void addArray(ArrayNode array, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                array.add(value);
            }
        }
    }

    private String callAgent(String task, String userPrompt, int maxCompletionTokens, String reasoningEffort) throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", "task=" + task));
        messages.add(message("user", userPrompt));

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.1);
        requestBody.put("max_completion_tokens", maxCompletionTokens);
        requestBody.put("stream", false);
        requestBody.put("retrieval_method", "none");
        requestBody.put("reasoning_effort", reasoningEffort);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(agentUrl))
                .timeout(AI_REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + agentKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(requestBody), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Topic tag classifier upstream returned status " + response.statusCode());
        }
        return extractContent(response.body());
    }

    private long flushUpdates(List<PendingUpdate> updates) throws Exception {
        List<PendingUpdate> changed = updates.stream().filter(PendingUpdate::changed).toList();
        if (changed.isEmpty()) {
            return 0L;
        }
        if (DRY_RUN) {
            for (PendingUpdate update : changed) {
                System.out.printf("DRY RUN %s -> %s%n", update.id(), update.nextTags());
            }
            return changed.size();
        }
        StringBuilder bulkPayload = new StringBuilder();
        for (PendingUpdate update : changed) {
            ObjectNode action = MAPPER.createObjectNode();
            action.set("update", MAPPER.createObjectNode()
                    .put("_index", index)
                    .put("_id", update.id()));
            ObjectNode doc = MAPPER.createObjectNode();
            ArrayNode tags = doc.putArray("topic_tags");
            for (String tag : update.nextTags()) {
                tags.add(tag);
            }
            ObjectNode updateNode = MAPPER.createObjectNode();
            updateNode.set("doc", doc);
            updateNode.put("doc_as_upsert", false);
            bulkPayload.append(MAPPER.writeValueAsString(action)).append('\n');
            bulkPayload.append(MAPPER.writeValueAsString(updateNode)).append('\n');
        }
        JsonNode response = postNdjson("/_bulk?filter_path=errors,items.*.update.error", bulkPayload.toString());
        if (response.path("errors").asBoolean(false)) {
            throw new IllegalStateException("Bulk update failed: " + response);
        }
        return changed.size();
    }

    private List<String> expandTagsWithAncestors(List<String> slugs,
                                                 Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> taxonomyBySlug) {
        return TopicTaxonomySupport.expandWithAncestors(slugs, taxonomyBySlug);
    }

    private void ensureTopicTagsMapping() throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        ObjectNode properties = body.putObject("properties");
        ObjectNode topicTags = properties.putObject("topic_tags");
        topicTags.put("type", "keyword");
        topicTags.put("ignore_above", 256);
        putJson("/" + encode(index) + "/_mapping", body.toString());
    }

    private Map<String, SeedProfile> buildSeedProfiles(List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy) {
        Map<String, SeedProfile> profiles = new LinkedHashMap<>();
        for (TopicTaxonomySupport.TopicTaxonomyEntry entry : taxonomy) {
            LinkedHashSet<String> englishSeeds = new LinkedHashSet<>();
            LinkedHashSet<String> arabicSeeds = new LinkedHashSet<>();

            if (TopicTaxonomySeedSupport.useDefaultLiteralSeeds(entry.slug())) {
                addEnglishSeed(englishSeeds, entry.slug().replace('-', ' '));
                addEnglishSeed(englishSeeds, entry.englishLabel());
                addArabicSeed(arabicSeeds, entry.arabicLabel());
            }

            List<String> extra = EXTRA_SEEDS.getOrDefault(entry.slug(), List.of());
            for (String seed : extra) {
                if (TopicTaxonomySeedSupport.looksArabic(seed)) {
                    addArabicSeed(arabicSeeds, seed);
                } else {
                    addEnglishSeed(englishSeeds, seed);
                }
            }
            profiles.put(entry.slug(), new SeedProfile(List.copyOf(englishSeeds), List.copyOf(arabicSeeds)));
        }
        return profiles;
    }

    private void validateSliceConfig() {
        boolean hasSliceId = SLICE_ID >= 0;
        boolean hasSliceMax = SLICE_MAX > 0;
        if (hasSliceId != hasSliceMax) {
            throw new IllegalArgumentException("TOPIC_TAGS_SLICE_ID and TOPIC_TAGS_SLICE_MAX must be set together.");
        }
        if (hasSliceMax && (SLICE_ID < 0 || SLICE_ID >= SLICE_MAX)) {
            throw new IllegalArgumentException("TOPIC_TAGS_SLICE_ID must be between 0 and TOPIC_TAGS_SLICE_MAX - 1.");
        }
    }

    private String sliceLabel() {
        return SLICE_MAX > 0 ? SLICE_ID + "/" + SLICE_MAX : "all";
    }

    private boolean shouldUseAi() {
        return classifierMode().requiresAi();
    }

    private TopicTaggingMode classifierMode() {
        if (!CLASSIFIER_MODE.isBlank()) {
            return TopicTaggingMode.fromExternal(CLASSIFIER_MODE);
        }
        if (!USE_AI) {
            return TopicTaggingMode.RULES_ONLY;
        }
        return AI_ONLY_WHEN_EMPTY ? TopicTaggingMode.HYBRID_WHEN_EMPTY : TopicTaggingMode.HYBRID_ALL;
    }

    private void logProgress(long seen, long changed, int pendingUpdates, int pendingAi, TopicTaggingMode mode) {
        if (PROGRESS_EVERY <= 0 || seen == 0 || (seen % PROGRESS_EVERY) != 0) {
            return;
        }
        System.out.printf(Locale.ROOT,
                "Topic tag backfill progress: seen=%d changed=%d pending_updates=%d pending_ai=%d mode=%s%n",
                seen, changed, pendingUpdates, pendingAi, mode.externalValue());
    }

    private JsonNode startScroll() throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("size", Math.max(1, BATCH_SIZE));
        body.putArray("sort").add("_doc");
        if (SLICE_MAX > 0) {
            body.putObject("slice")
                    .put("id", SLICE_ID)
                    .put("max", SLICE_MAX);
        }
        ArrayNode source = body.putArray("_source");
        source.add("book");
        source.add("chapter");
        source.add("section");
        source.add("english");
        source.add("arabic");
        source.add("semantic_matn_source");
        source.add("semantic_significant_terms_source");
        source.add("topic_tags");
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

    private JsonNode putJson(String path, String body) throws Exception {
        HttpRequest request = baseRequest(path)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
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

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", role);
        map.put("content", content);
        return map;
    }

    private static List<String> readStringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode child : node) {
            String value = TopicTaxonomySupport.normalizeSlug(child.asText(""));
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private static String joinFields(String... values) {
        StringJoiner joiner = new StringJoiner(" ");
        if (values != null) {
            for (String value : values) {
                String cleaned = cleanText(value);
                if (!cleaned.isBlank()) {
                    joiner.add(cleaned);
                }
            }
        }
        return joiner.toString();
    }

    private static void addEnglishSeed(Set<String> seeds, String raw) {
        String normalized = TopicTaxonomySupport.normalizeEnglishForMatch(raw);
        if (!normalized.isBlank() && normalized.length() > 2) {
            seeds.add(normalized);
        }
    }

    private static void addArabicSeed(Set<String> seeds, String raw) {
        String normalized = TopicTaxonomySupport.normalizeArabicForMatch(raw);
        if (!normalized.isBlank()) {
            seeds.add(normalized);
        }
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

    private static ArrayNode arrayNode(JsonNode node) {
        return node instanceof ArrayNode ? (ArrayNode) node : null;
    }

    private static String buildBaseUrl() {
        String host = readString("ELASTIC_HOST", "localhost");
        String port = readString("ELASTIC_PORT", "9200");
        return "http://" + host + ":" + port;
    }

    private static boolean readBoolean(String key, boolean defaultValue) {
        String value = readString(key, null);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
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

    private record PendingUpdate(String id, List<String> existingTags, List<String> nextTags) {
        boolean changed() {
            return !List.copyOf(existingTags).equals(List.copyOf(nextTags));
        }
    }

    private record ScoredTag(String slug, int score) {
    }

    private record PreparedNarration(String id,
                                     List<String> existingTags,
                                     List<String> fallbackTags,
                                     ObjectNode aiPayload) {
        private static PreparedNarration resolved(String id, List<String> existingTags, List<String> resolvedTags) {
            return new PreparedNarration(id, List.copyOf(existingTags), List.copyOf(resolvedTags), null);
        }

        private static PreparedNarration pending(String id,
                                                 List<String> existingTags,
                                                 List<String> fallbackTags,
                                                 ObjectNode aiPayload) {
            return new PreparedNarration(id, List.copyOf(existingTags), List.copyOf(fallbackTags), aiPayload);
        }

        private boolean requiresAi(TopicTaggingMode mode) {
            return aiPayload != null && mode.shouldUseAi(fallbackTags);
        }

        private PendingUpdate resolve(List<String> aiTags,
                                      Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> taxonomyBySlug,
                                      TopicTaggingMode mode) {
            List<String> chosen = mode.chooseTags(existingTags, fallbackTags, aiTags);
            List<String> expanded = TopicTaxonomySupport.expandWithAncestors(chosen, taxonomyBySlug);
            return new PendingUpdate(id, existingTags, expanded);
        }
    }

    private enum TopicTaggingMode {
        RULES_ONLY("rules"),
        HYBRID_WHEN_EMPTY("hybrid_when_empty"),
        HYBRID_ALL("hybrid_all"),
        AI_ONLY("ai_only"),
        AI_REFINE_ALL("ai_refine_all");

        private final String externalValue;

        TopicTaggingMode(String externalValue) {
            this.externalValue = externalValue;
        }

        private static TopicTaggingMode fromExternal(String raw) {
            String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            return switch (normalized) {
                case "rules", "rules_only" -> RULES_ONLY;
                case "hybrid", "hybrid_when_empty" -> HYBRID_WHEN_EMPTY;
                case "hybrid_all", "ai_review" -> HYBRID_ALL;
                case "ai", "ai_only" -> AI_ONLY;
                case "ai_refine_all", "refine_all", "high_precision_ai", "precision_ai" -> AI_REFINE_ALL;
                default -> throw new IllegalArgumentException("Unsupported TOPIC_TAGS_CLASSIFIER_MODE: " + raw);
            };
        }

        private String externalValue() {
            return externalValue;
        }

        private boolean requiresAi() {
            return this != RULES_ONLY;
        }

        private boolean reclassifiesTaggedDocs() {
            return this == AI_REFINE_ALL;
        }

        private boolean shouldUseAi(List<String> fallbackTags) {
            return switch (this) {
                case RULES_ONLY -> false;
                case HYBRID_WHEN_EMPTY -> fallbackTags == null || fallbackTags.isEmpty();
                case HYBRID_ALL, AI_ONLY, AI_REFINE_ALL -> true;
            };
        }

        private List<String> chooseTags(List<String> existingTags, List<String> fallbackTags, List<String> aiTags) {
            List<String> normalizedExisting = existingTags == null ? List.of() : existingTags;
            List<String> normalizedFallback = fallbackTags == null ? List.of() : fallbackTags;
            List<String> normalizedAi = aiTags == null ? List.of() : aiTags;
            return switch (this) {
                case RULES_ONLY -> normalizedFallback;
                case HYBRID_WHEN_EMPTY, HYBRID_ALL -> normalizedAi.isEmpty() ? normalizedFallback : normalizedAi;
                case AI_ONLY -> normalizedAi;
                case AI_REFINE_ALL -> normalizedAi.isEmpty()
                        ? (!normalizedExisting.isEmpty() ? normalizedExisting : normalizedFallback)
                        : normalizedAi;
            };
        }

        private String classificationInstructions() {
            return switch (this) {
                case AI_REFINE_ALL ->
                        "Reclassify every narration into taxonomy slugs with high precision. " +
                                "Return only JSON with this schema: {\"documents\":[{\"id\":\"...\",\"tags\":[\"slug-1\",\"slug-2\"]}]}. " +
                                "Use the narration text and heading context as the primary evidence. " +
                                "Treat existing_tags and rule_suggestions as weak hints that may be wrong. " +
                                "Assign all tags that genuinely apply where the hadith substantively addresses that theme—not just because a word is mentioned in passing. " +
                                "Most hadith will have 2-5 primary tags; rich narrative hadith may legitimately have 8-12 tags when they span multiple themes (e.g., a hadith about Prophet Ibrahim discussing idols, with lessons about patience and trust in God, maps to: ibrahim, shirk, tawhid, patience, trust-in-god, creation, afterlife, prophethood, previous-nations). " +
                                "Every hadith must receive at least one primary tag for Quran matching purposes. " +
                                "Choose the most specific child tag when the narration clearly supports it; otherwise choose the narrowest defensible parent. " +
                                "Avoid generic umbrella tags such as knowledge, faith, good-character, family, leadership, livelihood, and halal unless the narration is explicitly about that umbrella topic itself and no more specific child tag fits. " +
                                "Do not use quran merely because the narration quotes or references a verse. " +
                                "Do not use knowledge merely because the narration teaches something or contains a chain of transmission. " +
                                "Do not use good-character merely because the narration has a moral lesson. " +
                                "When a narration is explicitly about Ahl al-Bayt, Imam Husayn, ziyarat, wilayah, imamate, or related martyrdom/reappearance themes, prefer those tags over generic doctrinal or knowledge labels. " +
                                "Do not add both a parent and its child because the system adds ancestors automatically. " +
                                "Do not add parent rollups because the system adds ancestors automatically. " +
                                "Do not invent slugs. " +
                                "Each document must receive at least 1 slug unless the text is truly unusable.";
                default ->
                        "Classify each narration into 0 to 4 taxonomy slugs. Return only JSON with this schema: " +
                                "{\"documents\":[{\"id\":\"...\",\"tags\":[\"slug-1\",\"slug-2\"]}]}. " +
                                "Choose the most specific child tag when a child clearly applies. " +
                                "Do not add parent rollups because the system adds ancestors automatically. " +
                                "Do not invent slugs. If nothing fits, return an empty tags array for that document.";
            };
        }
    }

    private static final class SeedProfile {
        private final List<String> englishSeeds;
        private final List<String> arabicSeeds;

        private SeedProfile(List<String> englishSeeds, List<String> arabicSeeds) {
            this.englishSeeds = englishSeeds;
            this.arabicSeeds = arabicSeeds;
        }

        private int score(String headingEnglish,
                          String bodyEnglish,
                          String headingArabic,
                          String bodyArabic,
                          boolean allowBodyMatches) {
            int score = 0;
            String paddedHeadingEnglish = " " + (headingEnglish == null ? "" : headingEnglish) + " ";
            String paddedBodyEnglish = " " + (bodyEnglish == null ? "" : bodyEnglish) + " ";
            String paddedHeadingArabic = " " + (headingArabic == null ? "" : headingArabic) + " ";
            String paddedBodyArabic = " " + (bodyArabic == null ? "" : bodyArabic) + " ";
            for (String seed : englishSeeds) {
                if (seed.isBlank()) {
                    continue;
                }
                if (paddedHeadingEnglish.contains(" " + seed + " ")) {
                    score += seed.contains(" ") ? 4 : 2;
                }
                if (allowBodyMatches && paddedBodyEnglish.contains(" " + seed + " ")) {
                    score += seed.contains(" ") ? 2 : 0;
                }
            }
            for (String seed : arabicSeeds) {
                if (seed.isBlank()) {
                    continue;
                }
                if (paddedHeadingArabic.contains(" " + seed + " ")) {
                    score += seed.contains(" ") ? 4 : 3;
                }
                if (allowBodyMatches && paddedBodyArabic.contains(" " + seed + " ")) {
                    score += seed.contains(" ") ? 2 : 1;
                }
            }
            return score;
        }
    }
}
