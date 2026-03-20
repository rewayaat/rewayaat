package com.rewayaat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Recomputes rule-based topic predictions for an existing reviewed gold-set file.
 */
public final class TopicTagGoldSetPredictionTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path DEFAULT_GOLD = Path.of(readString("TOPIC_TAG_PREDICT_GOLD", "/tmp/rewayaat-topic-gold-set-reviewed.jsonl"));
    private static final Path DEFAULT_OUTPUT = Path.of(readString("TOPIC_TAG_PREDICT_OUTPUT", "/tmp/rewayaat-topic-predictions.jsonl"));
    private static final int MAX_RULE_SUGGESTIONS = readInt("TOPIC_TAG_PREDICT_MAX_RULE_SUGGESTIONS", 4);

    private final Path goldPath;
    private final Path outputPath;

    public TopicTagGoldSetPredictionTool() {
        this(DEFAULT_GOLD, DEFAULT_OUTPUT);
    }

    TopicTagGoldSetPredictionTool(Path goldPath, Path outputPath) {
        this.goldPath = goldPath;
        this.outputPath = outputPath;
    }

    public static void main(String[] args) throws Exception {
        new TopicTagGoldSetPredictionTool().run();
    }

    void run() throws Exception {
        List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy = TopicTaxonomySupport.loadBundledTaxonomy();
        List<ObjectNode> predictions = predict(taxonomy);
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        List<String> lines = new ArrayList<>();
        for (ObjectNode node : predictions) {
            lines.add(MAPPER.writeValueAsString(node));
        }
        Files.write(outputPath, lines, StandardCharsets.UTF_8);
        System.out.printf(Locale.ROOT, "Gold-set predictions written to %s with %d entries%n", outputPath, predictions.size());
    }

    List<ObjectNode> predict(List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy) throws IOException {
        if (!Files.exists(goldPath)) {
            throw new IOException("Gold-set file not found: " + goldPath);
        }
        RuleClassifier classifier = RuleClassifier.from(taxonomy);
        List<ObjectNode> predictions = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(goldPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line == null ? "" : line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                JsonNode node = MAPPER.readTree(trimmed);
                String id = node.path("id").asText("");
                if (id.isBlank()) {
                    continue;
                }
                List<String> tags = classifier.classify(
                        node.path("book").asText(""),
                        node.path("chapter").asText(""),
                        node.path("english").asText(""),
                        node.path("arabic_excerpt").asText(""));
                ObjectNode prediction = MAPPER.createObjectNode();
                prediction.put("id", id);
                ArrayNode predictedTags = prediction.putArray("predicted_topic_tags");
                for (String tag : tags) {
                    predictedTags.add(tag);
                }
                predictions.add(prediction);
            }
        }
        return predictions;
    }

    private static String readString(String key, String defaultValue) {
        String system = System.getProperty(key);
        if (system != null && !system.trim().isEmpty()) {
            return system.trim();
        }
        String env = System.getenv(key);
        if (env != null && !env.trim().isEmpty()) {
            return env.trim();
        }
        return defaultValue;
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

    private static final class RuleClassifier {
        private final Map<String, SeedProfile> profiles;
        private final Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> taxonomyBySlug;

        private RuleClassifier(Map<String, SeedProfile> profiles,
                               Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> taxonomyBySlug) {
            this.profiles = profiles;
            this.taxonomyBySlug = taxonomyBySlug;
        }

        private static RuleClassifier from(List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy) {
            Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> taxonomyBySlug = TopicTaxonomySupport.indexBySlug(taxonomy);
            Map<String, SeedProfile> profiles = new LinkedHashMap<>();
            for (TopicTaxonomySupport.TopicTaxonomyEntry entry : taxonomy) {
                if (entry == null || entry.slug().isBlank()) {
                    continue;
                }
                LinkedHashSet<String> englishSeeds = new LinkedHashSet<>();
                LinkedHashSet<String> arabicSeeds = new LinkedHashSet<>();
                if (TopicTaxonomySeedSupport.useDefaultLiteralSeeds(entry.slug())) {
                    addEnglishSeed(englishSeeds, entry.slug().replace('-', ' '));
                    addEnglishSeed(englishSeeds, entry.englishLabel());
                    addArabicSeed(arabicSeeds, entry.arabicLabel());
                }
                for (String seed : TopicTaxonomySeedSupport.extraSeeds(entry.slug())) {
                    if (TopicTaxonomySeedSupport.looksArabic(seed)) {
                        addArabicSeed(arabicSeeds, seed);
                    } else {
                        addEnglishSeed(englishSeeds, seed);
                    }
                }
                profiles.put(entry.slug(), new SeedProfile(List.copyOf(englishSeeds), List.copyOf(arabicSeeds)));
            }
            return new RuleClassifier(profiles, taxonomyBySlug);
        }

        private List<String> classify(String book, String chapter, String english, String arabicExcerpt) {
            String headingEnglish = " " + TopicTaxonomySupport.normalizeEnglishForMatch(book + " " + chapter) + " ";
            String bodyEnglish = " " + TopicTaxonomySupport.normalizeEnglishForMatch(english) + " ";
            String headingArabic = " " + TopicTaxonomySupport.normalizeArabicForMatch(book + " " + chapter) + " ";
            String bodyArabic = " " + TopicTaxonomySupport.normalizeArabicForMatch(arabicExcerpt) + " ";
            List<ScoredTag> scored = new ArrayList<>();
            for (Map.Entry<String, SeedProfile> entry : profiles.entrySet()) {
                int score = entry.getValue().score(
                        headingEnglish,
                        bodyEnglish,
                        headingArabic,
                        bodyArabic,
                        !TopicTaxonomySeedSupport.headingOnlySlugs().contains(entry.getKey()));
                if (score > 0) {
                    scored.add(new ScoredTag(entry.getKey(), score));
                }
            }
            scored.sort(Comparator.comparingInt(ScoredTag::score).reversed().thenComparing(ScoredTag::slug));
            List<String> tags = scored.stream()
                    .filter(item -> item.score() >= TopicTaxonomySeedSupport.minimumSuggestionScore(item.slug()))
                    .limit(MAX_RULE_SUGGESTIONS)
                    .map(ScoredTag::slug)
                    .toList();
            tags = pruneAncestors(tags, taxonomyBySlug);
            return TopicTaxonomySeedSupport.refineSuggestedTags(book, chapter, english, arabicExcerpt, tags, taxonomyBySlug);
        }

        private static List<String> pruneAncestors(List<String> tags,
                                                   Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> taxonomyBySlug) {
            if (tags == null || tags.isEmpty()) {
                return List.of();
            }
            List<String> pruned = new ArrayList<>();
            for (String candidate : tags) {
                boolean hasMoreSpecific = false;
                for (String other : tags) {
                    if (candidate.equals(other)) {
                        continue;
                    }
                    String parent = parentOf(other, taxonomyBySlug);
                    while (!parent.isBlank()) {
                        if (candidate.equals(parent)) {
                            hasMoreSpecific = true;
                            break;
                        }
                        parent = parentOf(parent, taxonomyBySlug);
                    }
                    if (hasMoreSpecific) {
                        break;
                    }
                }
                if (!hasMoreSpecific && !pruned.contains(candidate)) {
                    pruned.add(candidate);
                }
            }
            return List.copyOf(pruned);
        }

        private static String parentOf(String slug,
                                       Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> taxonomyBySlug) {
            TopicTaxonomySupport.TopicTaxonomyEntry entry = taxonomyBySlug.get(slug);
            return entry == null ? "" : entry.parentSlug();
        }

        private static void addEnglishSeed(LinkedHashSet<String> seeds, String raw) {
            String normalized = TopicTaxonomySupport.normalizeEnglishForMatch(raw);
            if (!normalized.isBlank()) {
                seeds.add(normalized);
            }
        }

        private static void addArabicSeed(LinkedHashSet<String> seeds, String raw) {
            String normalized = TopicTaxonomySupport.normalizeArabicForMatch(raw);
            if (!normalized.isBlank()) {
                seeds.add(normalized);
            }
        }
    }

    private record SeedProfile(List<String> englishSeeds, List<String> arabicSeeds) {
        private int score(String headingEnglish,
                          String bodyEnglish,
                          String headingArabic,
                          String bodyArabic,
                          boolean allowBodyMatches) {
            int score = 0;
            for (String seed : englishSeeds) {
                if (headingEnglish.contains(" " + seed + " ")) {
                    score += seed.contains(" ") ? 4 : 2;
                }
                if (allowBodyMatches && bodyEnglish.contains(" " + seed + " ")) {
                    score += seed.contains(" ") ? 2 : 1;
                }
            }
            for (String seed : arabicSeeds) {
                if (headingArabic.contains(" " + seed + " ")) {
                    score += seed.contains(" ") ? 4 : 2;
                }
                if (allowBodyMatches && bodyArabic.contains(" " + seed + " ")) {
                    score += seed.contains(" ") ? 2 : 1;
                }
            }
            return score;
        }
    }

    private record ScoredTag(String slug, int score) {
    }
}
