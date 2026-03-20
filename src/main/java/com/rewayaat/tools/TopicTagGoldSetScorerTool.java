package com.rewayaat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.util.Map;
import java.util.Set;

/**
 * Scores predicted topic tags against a manually reviewed gold set.
 */
public final class TopicTagGoldSetScorerTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path DEFAULT_GOLD = Path.of(readString("TOPIC_TAG_SCORE_GOLD", "/tmp/rewayaat-topic-gold-set.jsonl"));
    private static final Path DEFAULT_PREDICTIONS = Path.of(readString("TOPIC_TAG_SCORE_PREDICTIONS", "/tmp/rewayaat-topic-predictions.jsonl"));
    private static final Path DEFAULT_OUTPUT = Path.of(readString("TOPIC_TAG_SCORE_OUTPUT", "/tmp/rewayaat-topic-gold-score.json"));

    private final Path goldPath;
    private final Path predictionsPath;
    private final Path outputPath;

    public TopicTagGoldSetScorerTool() {
        this(DEFAULT_GOLD, DEFAULT_PREDICTIONS, DEFAULT_OUTPUT);
    }

    TopicTagGoldSetScorerTool(Path goldPath, Path predictionsPath, Path outputPath) {
        this.goldPath = goldPath;
        this.predictionsPath = predictionsPath;
        this.outputPath = outputPath;
    }

    public static void main(String[] args) throws Exception {
        new TopicTagGoldSetScorerTool().run();
    }

    void run() throws Exception {
        ScoreReport report = score(TopicTaxonomySupport.loadBundledTaxonomy());
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        Files.writeString(outputPath, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8);
        System.out.printf("Gold-set score written to %s. Reviewed=%d ExactMatch=%.4f HierF1=%.4f%n",
                outputPath,
                report.corpus().reviewedDocuments(),
                report.raw().exactMatchRate(),
                report.hierarchyAware().microF1());
    }

    ScoreReport score(List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy) throws IOException {
        Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> taxonomyBySlug = TopicTaxonomySupport.indexBySlug(taxonomy);
        Map<String, ReviewedDoc> goldDocs = readReviewedDocs(goldPath, true);
        Map<String, ReviewedDoc> predictedDocs = readReviewedDocs(predictionsPath, false);
        MetricsAccumulator raw = new MetricsAccumulator();
        MetricsAccumulator hierarchy = new MetricsAccumulator();
        Map<String, Integer> falseNegatives = new LinkedHashMap<>();
        Map<String, Integer> falsePositives = new LinkedHashMap<>();
        List<MismatchDoc> mismatches = new ArrayList<>();
        int pending = 0;

        for (ReviewedDoc gold : goldDocs.values()) {
            if (!gold.reviewed()) {
                pending++;
                continue;
            }
            List<String> predicted = predictedDocs.getOrDefault(gold.id(), ReviewedDoc.empty(gold.id())).tags();
            raw.accept(gold.tags(), predicted);
            List<String> expandedGold = TopicTaxonomySupport.expandWithAncestors(gold.tags(), taxonomyBySlug);
            List<String> expandedPredicted = TopicTaxonomySupport.expandWithAncestors(predicted, taxonomyBySlug);
            hierarchy.accept(expandedGold, expandedPredicted);

            Set<String> rawGold = new LinkedHashSet<>(gold.tags());
            Set<String> rawPredicted = new LinkedHashSet<>(predicted);
            Set<String> missing = new LinkedHashSet<>(rawGold);
            missing.removeAll(rawPredicted);
            Set<String> unexpected = new LinkedHashSet<>(rawPredicted);
            unexpected.removeAll(rawGold);
            for (String slug : missing) {
                falseNegatives.put(slug, falseNegatives.getOrDefault(slug, 0) + 1);
            }
            for (String slug : unexpected) {
                falsePositives.put(slug, falsePositives.getOrDefault(slug, 0) + 1);
            }
            if (!missing.isEmpty() || !unexpected.isEmpty()) {
                mismatches.add(new MismatchDoc(gold.id(), gold.tags(), predicted, List.copyOf(missing), List.copyOf(unexpected)));
            }
        }

        mismatches.sort(Comparator.comparingInt((MismatchDoc doc) -> doc.missing().size() + doc.unexpected().size()).reversed()
                .thenComparing(MismatchDoc::id));

        return new ScoreReport(
                new CorpusScoreSummary(goldDocs.size() - pending, pending, predictedDocs.size()),
                raw.toSummary(),
                hierarchy.toSummary(),
                topCounts(falseNegatives),
                topCounts(falsePositives),
                mismatches.stream().limit(25).toList());
    }

    private Map<String, ReviewedDoc> readReviewedDocs(Path path, boolean gold) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("File not found: " + path);
        }
        Map<String, ReviewedDoc> docs = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
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
                boolean reviewed = !gold || "reviewed".equalsIgnoreCase(node.path("review_status").asText(""));
                docs.put(id, new ReviewedDoc(id, extractTags(node, gold), reviewed));
            }
        }
        return docs;
    }

    private List<String> extractTags(JsonNode node, boolean gold) {
        List<String> keys = gold
                ? List.of("gold_topic_tags", "reviewed_topic_tags", "topic_tags")
                : List.of("predicted_topic_tags", "topic_tags", "gold_topic_tags");
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (String key : keys) {
            JsonNode array = node.path(key);
            if (!array.isArray()) {
                continue;
            }
            for (JsonNode item : array) {
                String slug = TopicTaxonomySupport.normalizeSlug(item.asText(""));
                if (!slug.isBlank()) {
                    tags.add(slug);
                }
            }
            if (!tags.isEmpty()) {
                break;
            }
        }
        return List.copyOf(tags);
    }

    private static List<SlugCount> topCounts(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .map(entry -> new SlugCount(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(SlugCount::count).reversed().thenComparing(SlugCount::slug))
                .limit(25)
                .toList();
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

    record ScoreReport(CorpusScoreSummary corpus,
                       MetricSummary raw,
                       MetricSummary hierarchyAware,
                       List<SlugCount> falseNegatives,
                       List<SlugCount> falsePositives,
                       List<MismatchDoc> mismatches) {
    }

    record CorpusScoreSummary(int reviewedDocuments, int pendingDocuments, int predictionDocuments) {
    }

    record MetricSummary(double exactMatchRate,
                         double microPrecision,
                         double microRecall,
                         double microF1,
                         double averageGoldTags,
                         double averagePredictedTags) {
    }

    record SlugCount(String slug, int count) {
    }

    record MismatchDoc(String id, List<String> gold, List<String> predicted, List<String> missing, List<String> unexpected) {
    }

    private record ReviewedDoc(String id, List<String> tags, boolean reviewed) {
        private static ReviewedDoc empty(String id) {
            return new ReviewedDoc(id, List.of(), true);
        }
    }

    private static final class MetricsAccumulator {
        private int documents;
        private int exactMatches;
        private int truePositives;
        private int falsePositives;
        private int falseNegatives;
        private int goldTagTotal;
        private int predictedTagTotal;

        private void accept(List<String> gold, List<String> predicted) {
            documents++;
            Set<String> goldSet = new LinkedHashSet<>(gold == null ? List.of() : gold);
            Set<String> predictedSet = new LinkedHashSet<>(predicted == null ? List.of() : predicted);
            goldTagTotal += goldSet.size();
            predictedTagTotal += predictedSet.size();
            if (goldSet.equals(predictedSet)) {
                exactMatches++;
            }
            for (String slug : predictedSet) {
                if (goldSet.contains(slug)) {
                    truePositives++;
                } else {
                    falsePositives++;
                }
            }
            for (String slug : goldSet) {
                if (!predictedSet.contains(slug)) {
                    falseNegatives++;
                }
            }
        }

        private MetricSummary toSummary() {
            double precision = truePositives + falsePositives == 0 ? 0d
                    : ((double) truePositives) / ((double) (truePositives + falsePositives));
            double recall = truePositives + falseNegatives == 0 ? 0d
                    : ((double) truePositives) / ((double) (truePositives + falseNegatives));
            double f1 = precision + recall == 0d ? 0d : (2d * precision * recall) / (precision + recall);
            double exact = documents == 0 ? 0d : ((double) exactMatches) / ((double) documents);
            double avgGold = documents == 0 ? 0d : ((double) goldTagTotal) / ((double) documents);
            double avgPred = documents == 0 ? 0d : ((double) predictedTagTotal) / ((double) documents);
            return new MetricSummary(round(exact), round(precision), round(recall), round(f1), round(avgGold), round(avgPred));
        }

        private double round(double value) {
            return Math.round(value * 10000d) / 10000d;
        }
    }
}
