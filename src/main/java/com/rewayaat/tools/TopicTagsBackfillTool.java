package com.rewayaat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.StringJoiner;

/**
 * Backfills the `topic_tags` field using a frozen taxonomy and an LLM-only classifier.
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
    private static final String CLASSIFIER_MODE = readString("TOPIC_TAGS_CLASSIFIER_MODE", "");
    private static final int AI_BATCH_SIZE = readInt("TOPIC_TAGS_AI_BATCH_SIZE", 4);
    private static final Duration AI_REQUEST_TIMEOUT = Duration.ofSeconds(readInt("TOPIC_TAGS_AI_TIMEOUT_SECONDS", 90));
    private static final long AI_RETRY_DELAY_MS = readInt("TOPIC_TAGS_AI_RETRY_DELAY_MS", 1500);
    private static final boolean AI_SEND_REASONING_EFFORT = readBoolean("TOPIC_TAGS_AI_SEND_REASONING_EFFORT", true);
    private static final double AI_TEMPERATURE = readDouble("TOPIC_TAGS_AI_TEMPERATURE", 0.0d);
    private static final int AI_MAX_COMPLETION_TOKENS_OVERRIDE = readInt("TOPIC_TAGS_AI_MAX_COMPLETION_TOKENS", 0);
    private static final int AI_ENGLISH_MAX_CHARS = readInt("TOPIC_TAGS_AI_ENGLISH_MAX_CHARS", 2200);
    private static final int AI_ARABIC_MATN_MAX_CHARS = readInt("TOPIC_TAGS_AI_ARABIC_MATN_MAX_CHARS", 2200);
    private static final int AI_MAX_PROMPT_TOKENS = readInt("TOPIC_TAGS_AI_MAX_PROMPT_TOKENS", 16000);
    private static final String AI_PARSE_DEBUG_FILE = readString("TOPIC_TAGS_AI_PARSE_DEBUG_FILE", "/tmp/topic-tags-ai-parse-failures.log");
    private static final String AI_PROPOSAL_DEBUG_FILE = readString("TOPIC_TAGS_AI_PROPOSAL_DEBUG_FILE", "/tmp/topic-tags-ai-proposals.log");
    private static final int PROGRESS_EVERY = readInt("TOPIC_TAGS_PROGRESS_EVERY", 10);
    private static final String SCROLL_KEEPALIVE = readString("TOPIC_TAGS_SCROLL", "6h");
    private static final int SLICE_ID = readInt("TOPIC_TAGS_SLICE_ID", -1);
    private static final int SLICE_MAX = readInt("TOPIC_TAGS_SLICE_MAX", 0);
    private static final String CHECKPOINT_FILE = readString("TOPIC_TAGS_CHECKPOINT_FILE", "/tmp/topic-tags-backfill-checkpoint.json");
    private static final int CHECKPOINT_INTERVAL = readInt("TOPIC_TAGS_CHECKPOINT_INTERVAL", 10);
    private static final boolean USE_OLLAMA = readBoolean("TOPIC_TAGS_USE_OLLAMA", false);
    private static final boolean ALLOW_PROPOSALS = readBoolean("TOPIC_TAGS_ALLOW_PROPOSALS", false);
    private static final String OLLAMA_URL = readString("TOPIC_TAGS_OLLAMA_URL", "http://localhost:11434/api/chat");
    private static final String OLLAMA_MODEL = readString("TOPIC_TAGS_OLLAMA_MODEL", "qwen2.5:14b");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String baseUrl = buildBaseUrl();
    private final String index = readString("REWAYAAT_INDEX", ESClientProvider.INDEX);
    private final String agentUrl = USE_OLLAMA ? OLLAMA_URL : readString("TOPIC_TAGS_AI_AGENT_URL",
            readString("SUMMARY_AI_AGENT_URL",
                    "https://kbm2sc4qjqcubxjmkawniaei.agents.do-ai.run/api/v1/chat/completions"));
    private final String agentKey = USE_OLLAMA ? "" : readString("TOPIC_TAGS_AI_AGENT_KEY", readString("SUMMARY_AI_AGENT_KEY", ""));
    private final String ollamaModel = USE_OLLAMA ? OLLAMA_MODEL : "";

    /**
     * Checkpoint state for resumable progress tracking.
     */
    private static class CheckpointState {
        private long seen = 0;
        private long changed = 0;
        private long aiClassified = 0;
        private long untagged = 0;
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

        public synchronized void incrementUntagged() {
            untagged++;
        }

        public synchronized long getUntagged() {
            return untagged;
        }

        public synchronized String toJson() throws Exception {
            return MAPPER.writeValueAsString(Map.of(
                "seen", seen,
                "changed", changed,
                "aiClassified", aiClassified,
                "untagged", untagged,
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
        List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy = new ArrayList<>(TopicTaxonomySupport.loadBundledTaxonomy());
        if (taxonomy.isEmpty()) {
            throw new IllegalStateException("Bundled taxonomy.json is empty.");
        }
        TopicTaggingMode mode = classifierMode();
        if (mode.requiresAi() && !USE_OLLAMA && agentKey.isBlank()) {
            throw new IllegalStateException("TOPIC_TAGS_AI_AGENT_KEY is required when AI topic tagging is enabled (unless using Ollama).");
        }
        validateSliceConfig();
        ensureTopicTagsMapping();
        Set<String> allowedSlugs = new LinkedHashSet<>(TopicTaxonomySupport.taggableSlugSet(taxonomy));
        Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> taxonomyBySlug = new LinkedHashMap<>(TopicTaxonomySupport.indexBySlug(taxonomy));

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

                    PreparedNarration prepared = classify(hit, mode);
                    if (prepared == null) {
                        continue;
                    }
                    checkpoint.incrementSeen();
                    checkpoint.addProcessed(docId);

                    if (prepared.requiresAi()) {
                        aiPending.add(prepared);
                        List<PreparedNarration> flushableBatch = flushableAiBatch(aiPending, taxonomy, mode);
                        if (!flushableBatch.isEmpty()) {
                            AiBatchResult result = classifyWithAiBatch(
                                    flushableBatch, allowedSlugs, taxonomy, mode);
                            mergeAcceptedProposals(result.proposals(), taxonomy, allowedSlugs, taxonomyBySlug);
                            for (PreparedNarration narration : flushableBatch) {
                                List<String> assignedTags = result.assignments().getOrDefault(narration.id(), List.of());
                                PendingUpdate update = narration.resolve(assignedTags, result.taxonomyBySlug(), mode);
                                if (update.changed()) {
                                    pending.add(update);
                                }
                                checkpoint.incrementAiClassified(1);
                            }
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
                AiBatchResult result = classifyWithAiBatch(
                        aiPending, allowedSlugs, taxonomy, mode);
                mergeAcceptedProposals(result.proposals(), taxonomy, allowedSlugs, taxonomyBySlug);
                for (PreparedNarration narration : aiPending) {
                    List<String> assignedTags = result.assignments().getOrDefault(narration.id(), List.of());
                    PendingUpdate update = narration.resolve(assignedTags, result.taxonomyBySlug(), mode);
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

    private PreparedNarration classify(JsonNode hit, TopicTaggingMode mode) {
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
        return PreparedNarration.pending(id, existing, buildAiNarration(id, source));
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
        AiBatchResult result = classifyWithAiBatch(aiCandidates, allowedSlugs, taxonomy, mode);
        for (PreparedNarration candidate : aiCandidates) {
            PendingUpdate update = candidate.resolve(
                    result.assignments().getOrDefault(candidate.id(), List.of()),
                    result.taxonomyBySlug().isEmpty() ? taxonomyBySlug : result.taxonomyBySlug(),
                    mode);
            if (update.changed()) {
                pending.add(update);
            }
        }
        return aiCandidates.size();
    }

    private AiBatchResult classifyWithAiBatch(List<PreparedNarration> aiCandidates,
                                              Set<String> allowedSlugs,
                                              List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy,
                                              TopicTaggingMode mode) throws Exception {
        return classifyWithAiBatch(aiCandidates, allowedSlugs, taxonomy, mode, 0);
    }

    private AiBatchResult classifyWithAiBatch(List<PreparedNarration> aiCandidates,
                                              Set<String> allowedSlugs,
                                              List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy,
                                              TopicTaggingMode mode,
                                              int depth) throws Exception {
        if (aiCandidates.isEmpty()) {
            return new AiBatchResult(Map.of(), taxonomyBySlug(taxonomy), List.of());
        }
        String text = buildAiBatchPromptPayload(aiCandidates, taxonomy, mode);
        if (text.isBlank()) {
            return new AiBatchResult(Map.of(), taxonomyBySlug(taxonomy), List.of());
        }
        Integer maxCompletionTokens = AI_MAX_COMPLETION_TOKENS_OVERRIDE > 0
                ? AI_MAX_COMPLETION_TOKENS_OVERRIDE
                : null;
        try {
            String completion = callAgent("topic_tag_classification", text, maxCompletionTokens,
                    mode == TopicTaggingMode.AI_REFINE_ALL ? "high" : "medium");
            Map<String, List<String>> assignments;
            Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> effectiveTaxonomyBySlug = taxonomyBySlug(taxonomy);
            List<TopicTaxonomySupport.TopicTaxonomyEntry> acceptedProposals = List.of();
            try {
                TopicTaxonomySupport.ParsedTagAssignments parsed =
                        TopicTaxonomySupport.parseTagAssignmentsWithProposals(completion, allowedSlugs);
                assignments = parsed.assignments();
                if (!parsed.proposals().isEmpty()) {
                    acceptedProposals = filterNovelProposals(parsed.proposals(), effectiveTaxonomyBySlug);
                    if (!acceptedProposals.isEmpty()) {
                        logTaxonomyProposalResponse(aiCandidates, text, completion, acceptedProposals);
                    }
                    for (TopicTaxonomySupport.TopicTaxonomyEntry proposal : acceptedProposals) {
                        effectiveTaxonomyBySlug.put(proposal.slug(), proposal);
                    }
                }
            } catch (Exception parseEx) {
                logMalformedAiResponse(aiCandidates, text, completion, parseEx);
                throw parseEx;
            }
            if (assignments.isEmpty() && aiCandidates.size() > 1) {
                return splitAiBatch(aiCandidates, allowedSlugs, taxonomy, mode, depth,
                        new IllegalStateException("AI classifier returned no usable document assignments."));
            }
            return new AiBatchResult(assignments, effectiveTaxonomyBySlug, acceptedProposals);
        } catch (Exception ex) {
            if (AI_RETRY_DELAY_MS > 0) {
                Thread.sleep(AI_RETRY_DELAY_MS);
            }
            if (aiCandidates.size() <= 1) {
                System.err.printf(Locale.ROOT,
                        "AI classification failed for %s; leaving tags empty for this pass. Reason=%s%n",
                        aiCandidates.get(0).id(), rootMessage(ex));
                return new AiBatchResult(Map.of(aiCandidates.get(0).id(), List.of()), taxonomyBySlug(taxonomy), List.of());
            }
            return splitAiBatch(aiCandidates, allowedSlugs, taxonomy, mode, depth, ex);
        }
    }

    private AiBatchResult splitAiBatch(List<PreparedNarration> aiCandidates,
                                       Set<String> allowedSlugs,
                                       List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy,
                                       TopicTaggingMode mode,
                                       int depth,
                                       Exception ex) throws Exception {
        int midpoint = Math.max(1, aiCandidates.size() / 2);
        System.err.printf(Locale.ROOT,
                "AI batch classification failed at size=%d depth=%d; splitting batch. Reason=%s%n",
                aiCandidates.size(), depth, rootMessage(ex));
        AiBatchResult left = classifyWithAiBatch(new ArrayList<>(aiCandidates.subList(0, midpoint)), allowedSlugs, taxonomy, mode, depth + 1);
        AiBatchResult right = classifyWithAiBatch(new ArrayList<>(aiCandidates.subList(midpoint, aiCandidates.size())), allowedSlugs, taxonomy, mode, depth + 1);
        Map<String, List<String>> assignments = new LinkedHashMap<>();
        assignments.putAll(left.assignments());
        assignments.putAll(right.assignments());
        Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> mergedTaxonomy = new LinkedHashMap<>();
        mergedTaxonomy.putAll(left.taxonomyBySlug());
        mergedTaxonomy.putAll(right.taxonomyBySlug());
        List<TopicTaxonomySupport.TopicTaxonomyEntry> mergedProposals = new ArrayList<>();
        mergedProposals.addAll(left.proposals());
        mergedProposals.addAll(right.proposals());
        return new AiBatchResult(assignments, mergedTaxonomy, List.copyOf(mergedProposals));
    }

    private List<PreparedNarration> flushableAiBatch(List<PreparedNarration> aiPending,
                                                     List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy,
                                                     TopicTaggingMode mode) throws IOException {
        if (aiPending.isEmpty()) {
            return List.of();
        }
        int maxDocs = Math.max(1, AI_BATCH_SIZE);
        if (aiPending.size() >= maxDocs) {
            List<PreparedNarration> batch = new ArrayList<>(aiPending.subList(0, maxDocs));
            aiPending.subList(0, maxDocs).clear();
            return batch;
        }
        if (aiPending.size() == 1) {
            return List.of();
        }
        String rawPayload = buildAiBatchPromptPayload(aiPending, taxonomy, mode, false);
        if (estimatePromptTokens(rawPayload) <= AI_MAX_PROMPT_TOKENS) {
            return List.of();
        }
        PreparedNarration overflow = aiPending.remove(aiPending.size() - 1);
        List<PreparedNarration> batch = new ArrayList<>(aiPending);
        aiPending.clear();
        aiPending.add(overflow);
        return batch;
    }

    private String buildAiBatchPromptPayload(List<PreparedNarration> aiCandidates,
                                             List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy,
                                             TopicTaggingMode mode) throws IOException {
        return buildAiBatchPromptPayload(aiCandidates, taxonomy, mode, true);
    }

    private String buildAiBatchPromptPayload(List<PreparedNarration> aiCandidates,
                                             List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy,
                                             TopicTaggingMode mode,
                                             boolean enforceMaxTokens) throws IOException {
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
        String serialized = MAPPER.writeValueAsString(payload);
        if (!enforceMaxTokens || estimatePromptTokens(serialized) <= AI_MAX_PROMPT_TOKENS) {
            return serialized;
        }
        trimDocumentsToFit(documents, payload, AI_MAX_PROMPT_TOKENS);
        return MAPPER.writeValueAsString(payload);
    }

    private void logMalformedAiResponse(List<PreparedNarration> aiCandidates,
                                        String userPayload,
                                        String completion,
                                        Exception parseEx) {
        try {
            ObjectNode node = MAPPER.createObjectNode();
            ArrayNode ids = node.putArray("ids");
            for (PreparedNarration candidate : aiCandidates) {
                ids.add(candidate.id());
            }
            node.put("error", rootMessage(parseEx));
            node.put("user_payload", userPayload == null ? "" : userPayload);
            node.put("raw_completion", completion == null ? "" : completion);
            String line = MAPPER.writeValueAsString(node) + System.lineSeparator();
            Files.writeString(Paths.get(AI_PARSE_DEBUG_FILE), line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception logEx) {
            System.err.printf(Locale.ROOT,
                    "Failed to write AI parse debug log: %s%n", rootMessage(logEx));
        }
    }

    private void logTaxonomyProposalResponse(List<PreparedNarration> aiCandidates,
                                             String userPayload,
                                             String completion,
                                             List<TopicTaxonomySupport.TopicTaxonomyEntry> proposals) {
        try {
            ObjectNode node = MAPPER.createObjectNode();
            ArrayNode ids = node.putArray("ids");
            for (PreparedNarration candidate : aiCandidates) {
                ids.add(candidate.id());
            }
            ArrayNode proposalNodes = node.putArray("proposed_taxonomy");
            for (TopicTaxonomySupport.TopicTaxonomyEntry proposal : proposals) {
                if (proposal == null) {
                    continue;
                }
                ObjectNode proposalNode = proposalNodes.addObject();
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
            String line = MAPPER.writeValueAsString(node) + System.lineSeparator();
            Files.writeString(Paths.get(AI_PROPOSAL_DEBUG_FILE), line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception logEx) {
            System.err.printf(Locale.ROOT,
                    "Failed to write AI taxonomy proposal log: %s%n", rootMessage(logEx));
        }
    }

    private ObjectNode buildAiNarration(String id, JsonNode source) {
        Map<String, Object> sourceMap = MAPPER.convertValue(source, Map.class);
        String english = cleanText(source.path("semantic_english_hint_source").asText(""));
        if (english.isBlank()) {
            english = cleanText(HadithSemanticText.extractEnglishHint(sourceMap, AI_ENGLISH_MAX_CHARS));
        }
        String semanticMatn = cleanText(source.path("semantic_matn_source").asText(""));
        if (english.isBlank() && semanticMatn.isBlank()) {
            return null;
        }
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", id);
        node.put("book", cleanText(source.path("book").asText("")));
        node.put("chapter", cleanText(source.path("chapter").asText("")));
        node.put("section", cleanText(source.path("section").asText("")));
        node.put("english", cap(english, AI_ENGLISH_MAX_CHARS));
        node.put("arabic_matn", cap(semanticMatn, AI_ARABIC_MATN_MAX_CHARS));
        return node;
    }

    private List<TopicTaxonomySupport.TopicTaxonomyEntry> filterNovelProposals(
            List<TopicTaxonomySupport.TopicTaxonomyEntry> proposals,
            Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> taxonomyBySlug) {
        if (proposals == null || proposals.isEmpty()) {
            return List.of();
        }
        List<TopicTaxonomySupport.TopicTaxonomyEntry> filtered = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (TopicTaxonomySupport.TopicTaxonomyEntry proposal : proposals) {
            if (proposal == null) {
                continue;
            }
            String slug = TopicTaxonomySupport.normalizeSlug(proposal.slug());
            if (slug.isBlank() || !seen.add(slug) || taxonomyBySlug.containsKey(slug)) {
                continue;
            }
            filtered.add(new TopicTaxonomySupport.TopicTaxonomyEntry(
                    slug,
                    proposal.englishLabel(),
                    proposal.arabicLabel(),
                    proposal.category(),
                    proposal.description(),
                    TopicTaxonomySupport.normalizeSlug(proposal.parentSlug()),
                    proposal.tagType(),
                    proposal.isTaggable()));
        }
        return List.copyOf(filtered);
    }

    private void trimDocumentsToFit(ArrayNode documents,
                                    Map<String, Object> payload,
                                    int maxTokens) throws IOException {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        while (documents.size() > 1 && estimatePromptTokens(MAPPER.writeValueAsString(payload)) > maxTokens) {
            documents.remove(documents.size() - 1);
        }
    }

    private int estimatePromptTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int asciiChars = 0;
        int nonAsciiChars = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch <= 0x7F) {
                asciiChars += 1;
            } else {
                nonAsciiChars += 1;
            }
        }
        int asciiTokens = (int) Math.ceil(asciiChars / 4.0d);
        int nonAsciiTokens = (int) Math.ceil(nonAsciiChars / 1.5d);
        return asciiTokens + nonAsciiTokens;
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

    private String callAgent(String task, String userPrompt, Integer maxCompletionTokens, String reasoningEffort) throws Exception {
        if (USE_OLLAMA) {
            return callOllama(task, userPrompt);
        }
        return callOpenAiCompatible(task, userPrompt, maxCompletionTokens, reasoningEffort);
    }

    private String callOpenAiCompatible(String task, String userPrompt, Integer maxCompletionTokens, String reasoningEffort) throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("user", userPrompt));

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("messages", messages);
        requestBody.put("temperature", AI_TEMPERATURE);
        if (maxCompletionTokens != null && maxCompletionTokens > 0) {
            requestBody.put("max_tokens", maxCompletionTokens);
        }
        requestBody.put("stream", false);
        requestBody.put("retrieval_method", "none");
        if (AI_SEND_REASONING_EFFORT && reasoningEffort != null && !reasoningEffort.isBlank()) {
            requestBody.put("reasoning_effort", reasoningEffort);
        }

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
        return extractContentOpenAi(response.body());
    }

    private String callOllama(String task, String userPrompt) throws Exception {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", ollamaModel);
        requestBody.put("stream", false);

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("user", userPrompt));
        requestBody.put("messages", messages);

        // Ollama-specific options
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", AI_TEMPERATURE);
        if (AI_MAX_COMPLETION_TOKENS_OVERRIDE > 0) {
            options.put("num_predict", AI_MAX_COMPLETION_TOKENS_OVERRIDE);
        }
        requestBody.put("options", options);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(agentUrl))
                .timeout(AI_REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(requestBody), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Ollama returned status " + response.statusCode() + ": " + response.body());
        }
        return extractContentOllama(response.body());
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

    private TopicTaggingMode classifierMode() {
        if (!CLASSIFIER_MODE.isBlank()) {
            return TopicTaggingMode.fromExternal(CLASSIFIER_MODE);
        }
        return TopicTaggingMode.AI_ONLY;
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
        source.add("semantic_english_hint_source");
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

    private String extractContentOpenAi(String json) throws Exception {
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

    private String extractContentOllama(String json) throws Exception {
        if (json == null || json.trim().isEmpty()) {
            return "";
        }
        JsonNode root = MAPPER.readTree(json);
        return extractTextContent(root.path("message").path("content"));
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

    private void mergeAcceptedProposals(List<TopicTaxonomySupport.TopicTaxonomyEntry> proposals,
                                        List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy,
                                        Set<String> allowedSlugs,
                                        Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> taxonomyBySlug) throws IOException {
        if (proposals == null || proposals.isEmpty()) {
            return;
        }
        List<TopicTaxonomySupport.TopicTaxonomyEntry> newEntries = new ArrayList<>();
        for (TopicTaxonomySupport.TopicTaxonomyEntry proposal : proposals) {
            if (proposal == null || proposal.slug().isBlank() || taxonomyBySlug.containsKey(proposal.slug())) {
                continue;
            }
            taxonomy.add(proposal);
            taxonomyBySlug.put(proposal.slug(), proposal);
            if (proposal.isTaggable()) {
                allowedSlugs.add(proposal.slug());
            }
            newEntries.add(proposal);
        }
        if (!newEntries.isEmpty()) {
            TopicTaxonomySupport.persistSupplementalProposals(newEntries);
            for (TopicTaxonomySupport.TopicTaxonomyEntry entry : newEntries) {
                System.out.printf(Locale.ROOT,
                        "Topic tag backfill new_slug: slug=%s en=%s category=%s parent=%s%n",
                        entry.slug(),
                        entry.englishLabel(),
                        entry.category(),
                        entry.parentSlug());
            }
        }
    }

    private Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> taxonomyBySlug(
            List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy) {
        return new LinkedHashMap<>(TopicTaxonomySupport.indexBySlug(taxonomy));
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

    private static double readDouble(String key, double defaultValue) {
        String value = readString(key, null);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
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

    private record AiBatchResult(Map<String, List<String>> assignments,
                                 Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> taxonomyBySlug,
                                 List<TopicTaxonomySupport.TopicTaxonomyEntry> proposals) {
    }

    private record PreparedNarration(String id,
                                     List<String> existingTags,
                                     ObjectNode aiPayload) {
        private static PreparedNarration resolved(String id, List<String> existingTags, List<String> resolvedTags) {
            return new PreparedNarration(id, List.copyOf(resolvedTags), null);
        }

        private static PreparedNarration pending(String id,
                                                 List<String> existingTags,
                                                 ObjectNode aiPayload) {
            return new PreparedNarration(id, List.copyOf(existingTags), aiPayload);
        }

        private boolean requiresAi() {
            return aiPayload != null;
        }

        private PendingUpdate resolve(List<String> aiTags,
                                      Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> taxonomyBySlug,
                                      TopicTaggingMode mode) {
            List<String> chosen = mode.chooseTags(existingTags, aiTags);
            List<String> expanded = TopicTaxonomySupport.expandWithAncestors(chosen, taxonomyBySlug);
            return new PendingUpdate(id, existingTags, expanded);
        }
    }

    private enum TopicTaggingMode {
        AI_ONLY("ai_only"),
        AI_REFINE_ALL("ai_refine_all");

        private final String externalValue;

        TopicTaggingMode(String externalValue) {
            this.externalValue = externalValue;
        }

        private static TopicTaggingMode fromExternal(String raw) {
            String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            return switch (normalized) {
                case "ai", "ai_only" -> AI_ONLY;
                case "ai_refine_all", "refine_all", "high_precision_ai", "precision_ai" -> AI_REFINE_ALL;
                default -> throw new IllegalArgumentException("Unsupported TOPIC_TAGS_CLASSIFIER_MODE: " + raw);
            };
        }

        private String externalValue() {
            return externalValue;
        }

        private boolean requiresAi() {
            return true;
        }

        private boolean reclassifiesTaggedDocs() {
            return this == AI_REFINE_ALL;
        }

        private List<String> chooseTags(List<String> existingTags, List<String> aiTags) {
            List<String> normalizedExisting = existingTags == null ? List.of() : existingTags;
            List<String> normalizedAi = aiTags == null ? List.of() : aiTags;
            return switch (this) {
                case AI_ONLY -> normalizedAi;
                case AI_REFINE_ALL -> normalizedAi.isEmpty() ? normalizedExisting : normalizedAi;
            };
        }

        private String classificationInstructions() {
            // Base instructions common to all modes
            String baseInstructions =
                "You classify Shia hadith into controlled taxonomy slugs. " +
                "You will receive id, book, chapter, section, english, arabic_matn, and taxonomy. " +
                "The english field is only a short semantic hint, not the full narration, so rely on arabic_matn first and use english only as support. " +
                "Tag only themes the hadith substantively addresses. " +
                "Do not tag based on passing mentions, chains, incidental names, weak associations, or taxonomy-adjacent guesses. " +
                "Prefer fewer correct tags over many weak tags. " +
                "Most hadith should receive 1-5 direct tags; use more only when the hadith clearly spans multiple major themes. " +
                "The taxonomy contains only directly taggable slugs. Parent and ancestor tags are added by the system later. " +
                "Prefer the most specific supported direct tag. " +
                "Do not output parent or ancestor rollups. " +
                "Use chapter and section headings as supporting context, especially for terse legal narrations, but do not tag from heading alone when the body clearly points elsewhere. " +
                "If the entry is only transmission metadata, rijal evaluation, bibliographic boilerplate, or chain material without substantive hadith content, return an empty tags array. " +
                "Do not use quran just because a verse is quoted or referenced. " +
                "Do not use knowledge just because the hadith teaches something, includes a chain, or is a transmission-chain notice. " +
                "Do not use good-character if a more specific ethical tag fits. " +
                "Do not use faith, halal, or similar umbrella tags unless they are the explicit central subject of the hadith. " +
                "Avoid broad umbrella tags such as knowledge, faith, good-character, family, leadership, livelihood, and halal unless the hadith is truly about that umbrella topic. " +
                "Do not infer a specific Imam from kunyah, title, or weak contextual clues alone. " +
                "Use person tags only when the hadith is materially about that figure, their words, their role, their example, or an event centered on them. " +
                "Do not assign ahl-al-bayt by default to every Imam narration. " +
                "Do not assign leadership unless governance, authority, rule, rights, or public authority are actually central. " +
                "Do not assign legal or ritual tags unless the hadith is actually discussing that legal or ritual matter. " +
                "Do not choose the nearest available devotional or legal tag when the exact fit is missing; prefer an empty tags array over a near miss. " +
                "When a secondary theme is explicit in the body, include it along with the primary legal or ritual tag, for example taqiyyah in an oath narration or wilayah in an authority narration. " +
                "When clearly supported, prefer specific Shia tags such as imamate, wilayah, ghadir, imam-ali, imam-husayn, karbala, ziyarat, occultation, imam-mahdi, and reappearance-signs over generic doctrinal tags. " +
                "Use evidence in this order: arabic_matn, then english. " +
                "Return only valid JSON with this schema: {\"documents\":[{\"id\":\"doc-id\",\"tags\":[\"slug-1\",\"slug-2\"]}]}. " +
                "Do not output prose, markdown, explanations, or code fences.";

            if (ALLOW_PROPOSALS) {
                return baseInstructions +
                        "If no existing tag is a reasonable fit for the hadith, you MAY propose a new tag. " +
                        "Only propose new tags when you are confident the taxonomy is genuinely missing a necessary concept. " +
                        "When proposing a new tag, include it in the document tags AND add it to the proposed_taxonomy array. " +
                        "Each proposed tag must have: slug (lowercase-with-hyphens), en (English label), category (one of: beliefs, practices, persons, ethics, history, koran, family, acts-of-worship, jurisprudence, other), and optionally parent (if it belongs under another tag). " +
                        "Be conservative with proposals - quality matters more than coverage. " +
                        "Example: {\"slug\":\"new-concept\",\"en\":\"New Concept\",\"category\":\"ethics\",\"parent\":\"ethics\"} " +
                        "If nothing fits well and no new tag is clearly justified, return an empty tags array.";
            } else {
                return baseInstructions +
                        "IMPORTANT: You MUST ONLY USE TAGS FROM THE PROVIDED TAXONOMY. Do NOT suggest, propose, or invent new tags. " +
                        "If no existing tag is a reasonable fit, return an empty tags array for that document; quality matters more than coverage.";
            }
        }
    }
}
