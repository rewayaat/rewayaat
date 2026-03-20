package com.rewayaat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.rewayaat.config.ESClientProvider;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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
 * Scans the live index after a topic-tag backfill and emits a QA report with review buckets and usage summaries.
 */
public final class TopicTagsQaTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);
    private static final int BATCH_SIZE = readInt("TOPIC_TAG_QA_BATCH_SIZE", 400);
    private static final int SAMPLE_LIMIT = readInt("TOPIC_TAG_QA_SAMPLE_LIMIT", 20);
    private static final int TOP_LIMIT = readInt("TOPIC_TAG_QA_TOP_LIMIT", 25);
    private static final int RARE_TAG_THRESHOLD = readInt("TOPIC_TAG_QA_RARE_THRESHOLD", 3);
    private static final int OVER_TAGGED_THRESHOLD = readInt("TOPIC_TAG_QA_OVER_TAGGED_THRESHOLD", 6);
    private static final int HIGH_ROOT_SPREAD_THRESHOLD = readInt("TOPIC_TAG_QA_HIGH_ROOT_SPREAD_THRESHOLD", 4);
    private static final String SCROLL_KEEPALIVE = readString("TOPIC_TAG_QA_SCROLL", "6h");
    private static final Path DEFAULT_OUTPUT = Path.of(readString("TOPIC_TAG_QA_OUTPUT", "/tmp/rewayaat-topic-tags-qa.json"));

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String baseUrl = buildBaseUrl();
    private final String index = readString("REWAYAAT_INDEX", ESClientProvider.INDEX);
    private final Path outputPath;

    public TopicTagsQaTool() {
        this(DEFAULT_OUTPUT);
    }

    TopicTagsQaTool(Path outputPath) {
        this.outputPath = outputPath;
    }

    public static void main(String[] args) throws Exception {
        new TopicTagsQaTool().run();
    }

    void run() throws Exception {
        List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy = TopicTaxonomySupport.loadBundledTaxonomy();
        QaReport report = analyze(taxonomy, loadNarrations());
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), report);
        System.out.printf(Locale.ROOT,
                "Topic tags QA written to %s. Docs=%d WithoutTags=%d UmbrellaOnly=%d OverTagged=%d HighRootSpread=%d%n",
                outputPath,
                report.corpus().documents(),
                report.buckets().withoutTags().count(),
                report.buckets().umbrellaOnly().count(),
                report.buckets().overTagged().count(),
                report.buckets().highRootSpread().count());
    }

    QaReport analyze(List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy,
                     List<NarrationSummary> narrations) {
        Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> taxonomyBySlug = TopicTaxonomySupport.indexBySlug(taxonomy);
        Map<String, List<TopicTaxonomySupport.TopicTaxonomyEntry>> childrenByParent = TopicTaxonomySupport.childrenByParent(taxonomy);
        Map<String, Set<String>> descendantsBySlug = new LinkedHashMap<>();
        for (TopicTaxonomySupport.TopicTaxonomyEntry entry : taxonomy) {
            descendantsBySlug.put(entry.slug(), Set.copyOf(TopicTaxonomySupport.descendantsOf(entry.slug(), childrenByParent)));
        }

        Map<String, Integer> tagCounts = new LinkedHashMap<>();
        Map<String, Integer> rootCounts = new LinkedHashMap<>();
        Map<String, Integer> leafPairCounts = new LinkedHashMap<>();
        Map<String, MutableBookStats> bookStats = new LinkedHashMap<>();
        LinkedHashSet<String> usedSlugs = new LinkedHashSet<>();

        SampleCollector withoutTags = new SampleCollector(SAMPLE_LIMIT);
        SampleCollector umbrellaOnly = new SampleCollector(SAMPLE_LIMIT);
        SampleCollector overTagged = new SampleCollector(SAMPLE_LIMIT);
        SampleCollector highRootSpread = new SampleCollector(SAMPLE_LIMIT);

        long documents = 0L;
        long withoutTagsCount = 0L;
        long withTagsCount = 0L;
        long totalTags = 0L;
        long totalLeafTags = 0L;

        for (NarrationSummary narration : narrations) {
            documents++;
            List<String> tags = normalizeTags(narration.topicTags());
            MutableBookStats book = bookStats.computeIfAbsent(narration.book(), ignored -> new MutableBookStats());
            book.documents++;

            if (tags.isEmpty()) {
                withoutTagsCount++;
                withoutTags.add(narration, tags, List.of(), Set.of());
                book.withoutTags++;
                continue;
            }

            withTagsCount++;
            totalTags += tags.size();
            book.totalTags += tags.size();
            usedSlugs.addAll(tags);
            for (String tag : tags) {
                tagCounts.merge(tag, 1, Integer::sum);
            }

            List<String> leafTags = leafTags(tags, descendantsBySlug);
            totalLeafTags += leafTags.size();
            Set<String> roots = new LinkedHashSet<>();
            for (String leaf : leafTags) {
                String root = TopicTaxonomySupport.rootSlugOf(leaf, taxonomyBySlug);
                if (!root.isBlank()) {
                    roots.add(root);
                }
            }
            for (String root : roots) {
                rootCounts.merge(root, 1, Integer::sum);
                book.rootCounts.merge(root, 1, Integer::sum);
            }

            if (isUmbrellaOnly(leafTags, descendantsBySlug)) {
                umbrellaOnly.add(narration, tags, leafTags, roots);
                book.umbrellaOnly++;
            }
            if (tags.size() >= OVER_TAGGED_THRESHOLD) {
                overTagged.add(narration, tags, leafTags, roots);
                book.overTagged++;
            }
            if (roots.size() >= HIGH_ROOT_SPREAD_THRESHOLD) {
                highRootSpread.add(narration, tags, leafTags, roots);
                book.highRootSpread++;
            }
            countLeafPairs(leafTags, leafPairCounts);
        }

        List<CountStat> topTags = topCounts(tagCounts, TOP_LIMIT);
        List<CountStat> topRoots = topCounts(rootCounts, TOP_LIMIT);
        List<CountStat> rareTags = rareCounts(tagCounts, RARE_TAG_THRESHOLD, TOP_LIMIT);
        List<String> unusedTaxonomySlugs = taxonomy.stream()
                .map(TopicTaxonomySupport.TopicTaxonomyEntry::slug)
                .filter(slug -> !slug.isBlank() && !usedSlugs.contains(slug))
                .sorted()
                .toList();

        List<BookQaSummary> topBooksByUmbrellaOnly = bookStats.entrySet().stream()
                .filter(entry -> entry.getValue().umbrellaOnly > 0)
                .sorted(Comparator.<Map.Entry<String, MutableBookStats>>comparingInt(entry -> entry.getValue().umbrellaOnly)
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(TOP_LIMIT)
                .map(entry -> entry.getValue().toSummary(entry.getKey(), TOP_LIMIT))
                .toList();

        List<BookQaSummary> topBooksByOverTagged = bookStats.entrySet().stream()
                .filter(entry -> entry.getValue().overTagged > 0)
                .sorted(Comparator.<Map.Entry<String, MutableBookStats>>comparingInt(entry -> entry.getValue().overTagged)
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(TOP_LIMIT)
                .map(entry -> entry.getValue().toSummary(entry.getKey(), TOP_LIMIT))
                .toList();

        List<BookQaSummary> topBooksByAverageTags = bookStats.entrySet().stream()
                .filter(entry -> entry.getValue().documents >= 10)
                .sorted(Comparator.<Map.Entry<String, MutableBookStats>>comparingDouble(entry -> entry.getValue().averageTagsPerDoc())
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(TOP_LIMIT)
                .map(entry -> entry.getValue().toSummary(entry.getKey(), TOP_LIMIT))
                .toList();

        List<PairCount> topLeafPairs = leafPairCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .limit(TOP_LIMIT)
                .map(entry -> {
                    String[] parts = entry.getKey().split("\\|", 2);
                    return new PairCount(parts[0], parts.length > 1 ? parts[1] : "", entry.getValue());
                })
                .toList();

        return new QaReport(
                Instant.now().toString(),
                index,
                new CorpusSummary(
                        documents,
                        withTagsCount,
                        withoutTagsCount,
                        round(divide(totalTags, documents)),
                        round(divide(totalLeafTags, documents))),
                new TaxonomyUsageSummary(
                        taxonomy.size(),
                        usedSlugs.size(),
                        unusedTaxonomySlugs.size(),
                        unusedTaxonomySlugs,
                        rareTags),
                topTags,
                topRoots,
                new ReviewBuckets(
                        new BucketSummary(withoutTagsCount, withoutTags.samples()),
                        new BucketSummary((long) umbrellaOnly.count(), umbrellaOnly.samples()),
                        new BucketSummary((long) overTagged.count(), overTagged.samples()),
                        new BucketSummary((long) highRootSpread.count(), highRootSpread.samples())),
                topBooksByUmbrellaOnly,
                topBooksByOverTagged,
                topBooksByAverageTags,
                topLeafPairs);
    }

    private List<NarrationSummary> loadNarrations() throws Exception {
        List<NarrationSummary> narrations = new ArrayList<>();
        String scrollId = null;
        try {
            JsonNode page = startScroll();
            scrollId = page.path("_scroll_id").asText("");
            while (true) {
                ArrayNode hits = arrayNode(page.path("hits").path("hits"));
                if (hits == null || hits.isEmpty()) {
                    break;
                }
                for (JsonNode hit : hits) {
                    String id = hit.path("_id").asText("");
                    JsonNode source = hit.path("_source");
                    narrations.add(new NarrationSummary(
                            id,
                            cleanText(source.path("book").asText("")),
                            cleanText(source.path("chapter").asText("")),
                            readStringArray(source.path("topic_tags"))));
                }
                page = continueScroll(scrollId);
                scrollId = page.path("_scroll_id").asText(scrollId == null ? "" : scrollId);
            }
        } finally {
            clearScroll(scrollId);
        }
        return List.copyOf(narrations);
    }

    private JsonNode startScroll() throws Exception {
        JsonNode body = MAPPER.createObjectNode()
                .put("size", Math.max(1, BATCH_SIZE))
                .set("sort", MAPPER.createArrayNode().add("_doc"));
        ArrayNode source = ((com.fasterxml.jackson.databind.node.ObjectNode) body).putArray("_source");
        source.add("book");
        source.add("chapter");
        source.add("topic_tags");
        ((com.fasterxml.jackson.databind.node.ObjectNode) body).set("query",
                MAPPER.createObjectNode().set("match_all", MAPPER.createObjectNode()));
        return postJson("/" + encode(index) + "/_search?scroll=" + encode(SCROLL_KEEPALIVE), MAPPER.writeValueAsString(body));
    }

    private JsonNode continueScroll(String scrollId) throws Exception {
        JsonNode body = MAPPER.createObjectNode()
                .put("scroll", SCROLL_KEEPALIVE)
                .put("scroll_id", scrollId == null ? "" : scrollId);
        return postJson("/_search/scroll", MAPPER.writeValueAsString(body));
    }

    private void clearScroll(String scrollId) {
        if (scrollId == null || scrollId.isBlank()) {
            return;
        }
        try {
            JsonNode body = MAPPER.createObjectNode().put("scroll_id", scrollId);
            deleteJson("/_search/scroll", MAPPER.writeValueAsString(body));
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
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Elasticsearch request failed with status " + response.statusCode() + ": " + response.body());
        }
        if (response.body() == null || response.body().isBlank()) {
            return MAPPER.createObjectNode();
        }
        JsonNode parsed = MAPPER.readTree(response.body());
        if (parsed.has("error")) {
            throw new IllegalStateException("Elasticsearch error response: " + parsed);
        }
        return parsed;
    }

    private static List<String> normalizeTags(List<String> rawTags) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (rawTags == null) {
            return List.of();
        }
        for (String raw : rawTags) {
            String slug = TopicTaxonomySupport.normalizeSlug(raw);
            if (!slug.isBlank()) {
                normalized.add(slug);
            }
        }
        return List.copyOf(normalized);
    }

    private static List<String> leafTags(List<String> tags,
                                         Map<String, Set<String>> descendantsBySlug) {
        List<String> leaves = new ArrayList<>();
        for (String candidate : tags) {
            boolean hasDescendantInDoc = false;
            Set<String> descendants = descendantsBySlug.getOrDefault(candidate, Set.of());
            for (String other : tags) {
                if (!candidate.equals(other) && descendants.contains(other)) {
                    hasDescendantInDoc = true;
                    break;
                }
            }
            if (!hasDescendantInDoc) {
                leaves.add(candidate);
            }
        }
        return List.copyOf(leaves);
    }

    private static boolean isUmbrellaOnly(List<String> leafTags,
                                          Map<String, Set<String>> descendantsBySlug) {
        if (leafTags.isEmpty()) {
            return false;
        }
        for (String leaf : leafTags) {
            if (descendantsBySlug.getOrDefault(leaf, Set.of()).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static void countLeafPairs(List<String> leafTags, Map<String, Integer> pairCounts) {
        if (leafTags == null || leafTags.size() < 2) {
            return;
        }
        List<String> sorted = new ArrayList<>(new LinkedHashSet<>(leafTags));
        sorted.sort(String::compareTo);
        for (int i = 0; i < sorted.size(); i++) {
            for (int j = i + 1; j < sorted.size(); j++) {
                pairCounts.merge(sorted.get(i) + "|" + sorted.get(j), 1, Integer::sum);
            }
        }
    }

    private static List<CountStat> topCounts(Map<String, Integer> counts, int limit) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .limit(limit)
                .map(entry -> new CountStat(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static List<CountStat> rareCounts(Map<String, Integer> counts, int threshold, int limit) {
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() <= threshold)
                .sorted(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry::getKey))
                .limit(limit)
                .map(entry -> new CountStat(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static ArrayNode arrayNode(JsonNode node) {
        return node instanceof ArrayNode ? (ArrayNode) node : null;
    }

    private static List<String> readStringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode child : node) {
            String value = TopicTaxonomySupport.normalizeSlug(child.asText(""));
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private static String cleanText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.replaceAll("\\s+", " ").trim();
    }

    private static double divide(long numerator, long denominator) {
        if (denominator <= 0L) {
            return 0d;
        }
        return (double) numerator / (double) denominator;
    }

    private static double round(double value) {
        return Math.round(value * 1000d) / 1000d;
    }

    private static String buildBaseUrl() {
        String host = readString("ELASTIC_HOST", "localhost");
        String port = readString("ELASTIC_PORT", "9200");
        return "http://" + host + ":" + port;
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

    record NarrationSummary(String id, String book, String chapter, List<String> topicTags) {
    }

    record QaReport(String generatedAt,
                    String index,
                    CorpusSummary corpus,
                    TaxonomyUsageSummary taxonomyUsage,
                    List<CountStat> topTags,
                    List<CountStat> topRoots,
                    ReviewBuckets buckets,
                    List<BookQaSummary> topBooksByUmbrellaOnly,
                    List<BookQaSummary> topBooksByOverTagged,
                    List<BookQaSummary> topBooksByAverageTags,
                    List<PairCount> topLeafPairs) {
    }

    record CorpusSummary(long documents,
                         long documentsWithTags,
                         long documentsWithoutTags,
                         double averageTagsPerDoc,
                         double averageLeafTagsPerDoc) {
    }

    record TaxonomyUsageSummary(int taxonomyNodes,
                                int usedTaxonomyNodes,
                                int unusedTaxonomyNodes,
                                List<String> unusedTaxonomySlugs,
                                List<CountStat> rareUsedTags) {
    }

    record CountStat(String slug, int documents) {
    }

    record PairCount(String left, String right, int documents) {
    }

    record ReviewBuckets(BucketSummary withoutTags,
                         BucketSummary umbrellaOnly,
                         BucketSummary overTagged,
                         BucketSummary highRootSpread) {
    }

    record BucketSummary(long count, List<DocumentSample> samples) {
    }

    record DocumentSample(String id,
                          String book,
                          String chapter,
                          List<String> topicTags,
                          List<String> leafTags,
                          List<String> roots) {
    }

    record BookQaSummary(String book,
                         int documents,
                         int withoutTags,
                         int umbrellaOnly,
                         int overTagged,
                         int highRootSpread,
                         double averageTagsPerDoc,
                         List<CountStat> topRoots) {
    }

    private static final class SampleCollector {
        private final int limit;
        private final List<DocumentSample> samples = new ArrayList<>();
        private int count;

        private SampleCollector(int limit) {
            this.limit = Math.max(0, limit);
        }

        private void add(NarrationSummary narration,
                         List<String> tags,
                         List<String> leafTags,
                         Set<String> roots) {
            count++;
            if (samples.size() >= limit) {
                return;
            }
            samples.add(new DocumentSample(
                    narration.id(),
                    narration.book(),
                    narration.chapter(),
                    List.copyOf(tags),
                    List.copyOf(leafTags),
                    new ArrayList<>(roots)));
        }

        private int count() {
            return count;
        }

        private List<DocumentSample> samples() {
            return List.copyOf(samples);
        }
    }

    private static final class MutableBookStats {
        private int documents;
        private int withoutTags;
        private int umbrellaOnly;
        private int overTagged;
        private int highRootSpread;
        private int totalTags;
        private final Map<String, Integer> rootCounts = new LinkedHashMap<>();

        private double averageTagsPerDoc() {
            return documents == 0 ? 0d : (double) totalTags / (double) documents;
        }

        private BookQaSummary toSummary(String book, int topLimit) {
            return new BookQaSummary(
                    book,
                    documents,
                    withoutTags,
                    umbrellaOnly,
                    overTagged,
                    highRootSpread,
                    round(averageTagsPerDoc()),
                    topCounts(rootCounts, topLimit));
        }
    }
}
