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
import java.util.Set;
import java.util.stream.Stream;

/**
 * Produces a review file for manual topic-tag adjudication, stratified by audit gaps, books, and inferred topic roots.
 */
public final class TopicTagGoldSetSamplerTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path DEFAULT_BATCH_DIR = Path.of(readString("TOPIC_TAG_GOLD_BATCH_DIR", "batches"));
    private static final Path DEFAULT_AUDIT = Path.of(readString("TOPIC_TAG_GOLD_AUDIT", "/tmp/rewayaat-taxonomy-audit.json"));
    private static final Path DEFAULT_OUTPUT = Path.of(readString("TOPIC_TAG_GOLD_OUTPUT", "/tmp/rewayaat-topic-gold-set.jsonl"));
    private static final int GAP_LIMIT = readInt("TOPIC_TAG_GOLD_GAP_LIMIT", 12);
    private static final int GAP_SAMPLES_PER_CLUSTER = readInt("TOPIC_TAG_GOLD_GAP_SAMPLES", 2);
    private static final int BOOK_LIMIT = readInt("TOPIC_TAG_GOLD_BOOK_LIMIT", 6);
    private static final int BOOK_SAMPLES_PER_BOOK = readInt("TOPIC_TAG_GOLD_BOOK_SAMPLES", 3);
    private static final int ROOT_SAMPLES = readInt("TOPIC_TAG_GOLD_ROOT_SAMPLES", 2);
    private static final int MAX_RULE_SUGGESTIONS = readInt("TOPIC_TAG_GOLD_MAX_RULE_SUGGESTIONS", 4);

    private final Path batchDir;
    private final Path auditPath;
    private final Path outputPath;

    public TopicTagGoldSetSamplerTool() {
        this(DEFAULT_BATCH_DIR, DEFAULT_AUDIT, DEFAULT_OUTPUT);
    }

    TopicTagGoldSetSamplerTool(Path batchDir, Path auditPath, Path outputPath) {
        this.batchDir = batchDir;
        this.auditPath = auditPath;
        this.outputPath = outputPath;
    }

    public static void main(String[] args) throws Exception {
        new TopicTagGoldSetSamplerTool().run();
    }

    void run() throws Exception {
        List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy = TopicTaxonomySupport.loadBundledTaxonomy();
        SamplerReport report = sample(taxonomy);
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        List<String> lines = new ArrayList<>();
        for (ObjectNode node : report.entries()) {
            lines.add(MAPPER.writeValueAsString(node));
        }
        Files.write(outputPath, lines, StandardCharsets.UTF_8);
        System.out.printf(Locale.ROOT,
                "Gold-set review file written to %s with %d entries (gaps=%d books=%d roots=%d)%n",
                outputPath,
                report.entries().size(),
                report.gapSelections(),
                report.bookSelections(),
                report.rootSelections());
    }

    SamplerReport sample(List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy) throws IOException {
        Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> taxonomyBySlug = TopicTaxonomySupport.indexBySlug(taxonomy);
        GapTargets gapTargets = loadGapTargets();
        Map<String, Integer> bookQuotaRemaining = loadBookTargets();
        Map<String, Integer> rootQuotaRemaining = new LinkedHashMap<>();
        for (TopicTaxonomySupport.TopicTaxonomyEntry entry : taxonomy) {
            if (entry != null && entry.parentSlug().isBlank()) {
                rootQuotaRemaining.put(entry.slug(), ROOT_SAMPLES);
            }
        }
        SeedClassifier classifier = SeedClassifier.from(taxonomy, taxonomyBySlug);
        Map<String, ObjectNode> selected = new LinkedHashMap<>();
        Map<String, Integer> counters = new LinkedHashMap<>();

        try (Stream<Path> batchPaths = Files.list(batchDir)) {
            List<Path> files = batchPaths
                    .filter(path -> path.getFileName().toString().startsWith("batch_"))
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .sorted()
                    .toList();
            for (Path file : files) {
                readBatch(file, selected, counters, gapTargets, bookQuotaRemaining, rootQuotaRemaining, classifier);
            }
        }

        List<ObjectNode> entries = new ArrayList<>(selected.values());
        entries.sort(Comparator.comparing((ObjectNode node) -> node.path("book").asText(""))
                .thenComparing(node -> node.path("chapter").asText(""))
                .thenComparing(node -> node.path("id").asText("")));
        return new SamplerReport(
                entries,
                counters.getOrDefault("gap", 0),
                counters.getOrDefault("book", 0),
                counters.getOrDefault("root", 0));
    }

    private void readBatch(Path file,
                           Map<String, ObjectNode> selected,
                           Map<String, Integer> counters,
                           GapTargets gapTargets,
                           Map<String, Integer> bookQuotaRemaining,
                           Map<String, Integer> rootQuotaRemaining,
                           SeedClassifier classifier) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line == null ? "" : line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                JsonNode root = MAPPER.readTree(trimmed);
                JsonNode source = root.path("_source");
                String id = root.path("_id").asText("");
                if (id.isBlank() || source.isMissingNode() || source.isNull()) {
                    continue;
                }
                String book = source.path("book").asText("");
                String chapter = source.path("chapter").asText("");
                String english = source.path("english").asText("");
                String arabic = source.path("arabic").asText("");
                TagSuggestion suggestion = classifier.classify(book, chapter, english, arabic);
                ObjectNode existing = selected.get(id);

                String gapKey = gapTargets.byId().get(id);
                if (gapKey != null) {
                    ObjectNode node = existing == null ? createEntry(id, book, chapter, english, arabic, suggestion) : existing;
                    addStratum(node, "gap:" + gapKey);
                    selected.put(id, node);
                    if (existing == null) {
                        increment(counters, "gap");
                    }
                }

                if (bookQuotaRemaining.getOrDefault(book, 0) > 0) {
                    ObjectNode node = selected.get(id);
                    if (node == null) {
                        node = createEntry(id, book, chapter, english, arabic, suggestion);
                        selected.put(id, node);
                        bookQuotaRemaining.put(book, bookQuotaRemaining.get(book) - 1);
                        addStratum(node, "book:" + book);
                        increment(counters, "book");
                    } else if (!hasStratum(node, "book:" + book)) {
                        addStratum(node, "book:" + book);
                    }
                }

                String rootSlug = suggestion.rootSlug();
                if (!rootSlug.isBlank() && rootQuotaRemaining.getOrDefault(rootSlug, 0) > 0) {
                    ObjectNode node = selected.get(id);
                    if (node == null) {
                        node = createEntry(id, book, chapter, english, arabic, suggestion);
                        selected.put(id, node);
                        rootQuotaRemaining.put(rootSlug, rootQuotaRemaining.get(rootSlug) - 1);
                        addStratum(node, "root:" + rootSlug);
                        increment(counters, "root");
                    } else if (!hasStratum(node, "root:" + rootSlug)) {
                        addStratum(node, "root:" + rootSlug);
                    }
                }

                if (gapTargets.isSatisfied() && quotasExhausted(bookQuotaRemaining) && quotasExhausted(rootQuotaRemaining)) {
                    return;
                }
            }
        }
    }

    private GapTargets loadGapTargets() throws IOException {
        if (!Files.exists(auditPath)) {
            throw new IOException("Audit report not found: " + auditPath);
        }
        JsonNode audit = MAPPER.readTree(Files.readString(auditPath, StandardCharsets.UTF_8));
        Map<String, String> byId = new LinkedHashMap<>();
        int clusters = 0;
        for (JsonNode candidate : audit.path("candidateGaps")) {
            if (clusters >= GAP_LIMIT) {
                break;
            }
            String key = candidate.path("headingKey").asText("");
            int count = 0;
            for (JsonNode sample : candidate.path("samples")) {
                if (count >= GAP_SAMPLES_PER_CLUSTER) {
                    break;
                }
                String id = sample.path("id").asText("");
                if (!id.isBlank()) {
                    byId.put(id, key);
                    count++;
                }
            }
            if (!key.isBlank()) {
                clusters++;
            }
        }
        return new GapTargets(byId, Math.min(clusters, GAP_LIMIT));
    }

    private Map<String, Integer> loadBookTargets() throws IOException {
        if (!Files.exists(auditPath)) {
            throw new IOException("Audit report not found: " + auditPath);
        }
        JsonNode audit = MAPPER.readTree(Files.readString(auditPath, StandardCharsets.UTF_8));
        Map<String, Integer> quotas = new LinkedHashMap<>();
        int count = 0;
        for (JsonNode book : audit.path("headingCoverage").path("uncoveredBooks")) {
            if (count >= BOOK_LIMIT) {
                break;
            }
            String label = book.path("book").asText("");
            if (!label.isBlank()) {
                quotas.put(label, BOOK_SAMPLES_PER_BOOK);
                count++;
            }
        }
        return quotas;
    }

    private static boolean quotasExhausted(Map<String, Integer> quotas) {
        return quotas.values().stream().noneMatch(value -> value != null && value > 0);
    }

    private ObjectNode createEntry(String id,
                                   String book,
                                   String chapter,
                                   String english,
                                   String arabic,
                                   TagSuggestion suggestion) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", id);
        node.put("book", book == null ? "" : book);
        node.put("chapter", chapter == null ? "" : chapter);
        node.put("english", english == null ? "" : english);
        node.put("arabic_excerpt", abbreviate(arabic, 320));
        ArrayNode suggestions = node.putArray("rule_suggestions");
        for (String tag : suggestion.tags()) {
            suggestions.add(tag);
        }
        node.putArray("strata");
        node.put("review_status", "pending");
        node.putArray("gold_topic_tags");
        node.put("review_notes", "");
        return node;
    }

    private static void addStratum(ObjectNode node, String stratum) {
        if (node == null || stratum == null || stratum.isBlank() || hasStratum(node, stratum)) {
            return;
        }
        node.withArray("strata").add(stratum);
    }

    private static boolean hasStratum(ObjectNode node, String stratum) {
        for (JsonNode item : node.withArray("strata")) {
            if (stratum.equals(item.asText(""))) {
                return true;
            }
        }
        return false;
    }

    private static void increment(Map<String, Integer> counters, String key) {
        counters.put(key, counters.getOrDefault(key, 0) + 1);
    }

    private static String abbreviate(String value, int maxChars) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (maxChars <= 0 || clean.length() <= maxChars) {
            return clean;
        }
        return clean.substring(0, maxChars).trim() + "...";
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

    record SamplerReport(List<ObjectNode> entries, int gapSelections, int bookSelections, int rootSelections) {
    }

    private record GapTargets(Map<String, String> byId, int clusters) {
        private boolean isSatisfied() {
            return byId.isEmpty() || byId.size() >= clusters;
        }
    }

    private record TagSuggestion(List<String> tags, String rootSlug) {
    }

    private static final class SeedClassifier {
        private final Map<String, SeedProfile> profiles;
        private final Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> taxonomyBySlug;

        private SeedClassifier(Map<String, SeedProfile> profiles,
                               Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> taxonomyBySlug) {
            this.profiles = profiles;
            this.taxonomyBySlug = taxonomyBySlug;
        }

        private static SeedClassifier from(List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy,
                                           Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> taxonomyBySlug) {
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
            return new SeedClassifier(profiles, taxonomyBySlug);
        }

        private TagSuggestion classify(String book, String chapter, String english, String arabic) {
            String headingEnglish = " " + TopicTaxonomySupport.normalizeEnglishForMatch(book + " " + chapter) + " ";
            String bodyEnglish = " " + TopicTaxonomySupport.normalizeEnglishForMatch(abbreviate(english, 700)) + " ";
            String headingArabic = " " + TopicTaxonomySupport.normalizeArabicForMatch(book + " " + chapter) + " ";
            String bodyArabic = " " + TopicTaxonomySupport.normalizeArabicForMatch(abbreviate(arabic, 700)) + " ";
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
            tags = TopicTaxonomySeedSupport.refineSuggestedTags(book, chapter, english, arabic, tags, taxonomyBySlug);
            String rootSlug = tags.isEmpty() ? "" : TopicTaxonomySupport.rootSlugOf(tags.get(0), taxonomyBySlug);
            return new TagSuggestion(tags, rootSlug);
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

        private static void addEnglishSeed(Set<String> seeds, String raw) {
            String normalized = TopicTaxonomySupport.normalizeEnglishForMatch(raw);
            if (!normalized.isBlank()) {
                seeds.add(normalized);
            }
        }

        private static void addArabicSeed(Set<String> seeds, String raw) {
            String normalized = TopicTaxonomySupport.normalizeArabicForMatch(raw);
            if (!normalized.isBlank()) {
                seeds.add(normalized);
            }
        }
    }

    private record ScoredTag(String slug, int score) {
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
}
