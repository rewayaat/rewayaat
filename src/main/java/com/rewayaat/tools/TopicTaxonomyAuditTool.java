package com.rewayaat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.core.SimilarHadithRanking;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Audits taxonomy quality against the full exported corpus, highlighting broad coverage and likely gap clusters.
 * This is an offline-first precursor to revising taxonomy.json and then retagging the index.
 */
public final class TopicTaxonomyAuditTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path DEFAULT_MANIFEST = Path.of(readString("TOPIC_TAXONOMY_AUDIT_MANIFEST", "batches/manifest.json"));
    private static final Path DEFAULT_BATCH_DIR = Path.of(readString("TOPIC_TAXONOMY_AUDIT_BATCH_DIR", "batches"));
    private static final Path DEFAULT_OUTPUT = Path.of(readString("TOPIC_TAXONOMY_AUDIT_OUTPUT", "/tmp/rewayaat-taxonomy-audit.json"));
    private static final int TOP_LIST_LIMIT = readInt("TOPIC_TAXONOMY_AUDIT_TOP_LIMIT", 20);
    private static final int SAMPLE_PER_GAP = readInt("TOPIC_TAXONOMY_AUDIT_SAMPLE_PER_GAP", 3);
    private static final int MIN_GAP_DOCS = readInt("TOPIC_TAXONOMY_AUDIT_MIN_GAP_DOCS", 5);
    private static final Set<String> HEADING_STOPWORDS = Set.of(
            "the", "a", "an", "of", "for", "to", "in", "on", "with", "and", "or", "by", "from", "at", "into",
            "chapter", "book", "books", "section", "part", "volume", "assembly", "session", "lesson", "tradition",
            "traditions", "report", "reports", "narration", "narrations", "regarding", "concerning", "about",
            "related", "mentioning", "virtue", "virtues", "merit", "merits", "issue", "issues", "matter",
            "matters", "miscellaneous", "among", "what", "whose", "use", "types", "rare", "various", "concise",
            "word", "words", "meaning", "meanings", "aspect", "aspects", "deed", "deeds", "remaining", "days",
            "day", "night", "monday", "tuesday", "wednesday", "thursday", "friday",
            "saturday", "sunday", "muharram", "safar", "rabi", "rabiul", "rabiulawwal", "rabiulakhir", "jumada",
            "rajab", "shaban", "ramadan", "shawwal", "dhul", "dhu", "qadah", "hijjah", "ah", "first", "second",
            "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth", "eleventh", "twelfth",
            "thirteenth", "fourteenth", "fifteenth", "sixteenth", "seventeenth", "eighteenth", "nineteenth",
            "twentieth", "thirtieth", "thirty", "twenty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
            "peace", "upon", "be", "him", "her", "them", "allah", "ahadith"
    );

    private final Path manifestPath;
    private final Path batchDir;
    private final Path outputPath;
    private final int topListLimit;
    private final int samplePerGap;
    private final int minGapDocs;

    public TopicTaxonomyAuditTool() {
        this(DEFAULT_MANIFEST, DEFAULT_BATCH_DIR, DEFAULT_OUTPUT, TOP_LIST_LIMIT, SAMPLE_PER_GAP, MIN_GAP_DOCS);
    }

    TopicTaxonomyAuditTool(Path manifestPath,
                           Path batchDir,
                           Path outputPath,
                           int topListLimit,
                           int samplePerGap,
                           int minGapDocs) {
        this.manifestPath = manifestPath;
        this.batchDir = batchDir;
        this.outputPath = outputPath;
        this.topListLimit = Math.max(1, topListLimit);
        this.samplePerGap = Math.max(1, samplePerGap);
        this.minGapDocs = Math.max(1, minGapDocs);
    }

    public static void main(String[] args) throws Exception {
        new TopicTaxonomyAuditTool().run();
    }

    void run() throws Exception {
        List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy = TopicTaxonomySupport.loadBundledTaxonomy();
        AuditReport report = buildReport(taxonomy);
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        Files.writeString(outputPath, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8);
        System.out.printf(Locale.ROOT,
                "Topic taxonomy audit written to %s. Docs=%d Covered=%d Uncovered=%d CandidateGaps=%d%n",
                outputPath,
                report.corpus().documents(),
                report.headingCoverage().coveredDocuments(),
                report.headingCoverage().uncoveredDocuments(),
                report.candidateGaps().size());
    }

    AuditReport buildReport(List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy) throws IOException {
        if (taxonomy == null || taxonomy.isEmpty()) {
            throw new IOException("Bundled taxonomy is empty.");
        }
        if (!Files.exists(batchDir) || !Files.isDirectory(batchDir)) {
            throw new IOException("Batch directory does not exist: " + batchDir);
        }
        SeedIndex seedIndex = SeedIndex.from(taxonomy);
        long manifestDocuments = readManifestCount(manifestPath, "documents");
        long manifestBatches = readManifestCount(manifestPath, "batches");
        Accumulator accumulator = new Accumulator(seedIndex, samplePerGap);

        try (Stream<Path> batchPaths = Files.list(batchDir)) {
            List<Path> files = batchPaths
                    .filter(path -> path.getFileName().toString().startsWith("batch_"))
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .sorted()
                    .toList();
            for (Path file : files) {
                accumulator.incrementBatchFiles();
                readBatch(file, accumulator);
            }
        }

        return new AuditReport(
                Instant.now().toString(),
                new CorpusStats(
                        manifestDocuments,
                        manifestBatches,
                        accumulator.documents,
                        accumulator.batchFiles,
                        accumulator.books.size(),
                        accumulator.uniqueChapters.size()),
                summarizeTaxonomy(taxonomy),
                accumulator.toHeadingCoverage(topListLimit),
                accumulator.toCandidateGaps(topListLimit, minGapDocs));
    }

    private void readBatch(Path file, Accumulator accumulator) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line == null ? "" : line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                JsonNode node = MAPPER.readTree(trimmed);
                CorpusNarration narration = toNarration(node);
                if (narration != null) {
                    accumulator.accept(narration);
                }
            }
        }
    }

    private static CorpusNarration toNarration(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode source = node.path("_source");
        if (source == null || source.isMissingNode() || source.isNull()) {
            return null;
        }
        String id = clean(node.path("_id").asText(""));
        if (id.isBlank()) {
            return null;
        }
        return new CorpusNarration(
                id,
                clean(source.path("book").asText("")),
                clean(source.path("chapter").asText("")),
                clean(source.path("english").asText("")),
                clean(source.path("arabic").asText("")));
    }

    private TaxonomyStats summarizeTaxonomy(List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy) {
        Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> bySlug = TopicTaxonomySupport.indexBySlug(taxonomy);
        Map<String, Integer> childCounts = new TreeMap<>();
        Map<String, Integer> categoryCounts = new TreeMap<>();
        List<String> orphanParents = new ArrayList<>();
        int roots = 0;
        int leaves = 0;
        int maxDepth = 0;

        for (TopicTaxonomySupport.TopicTaxonomyEntry entry : taxonomy) {
            if (entry == null || entry.slug().isBlank()) {
                continue;
            }
            categoryCounts.merge(entry.category(), 1, Integer::sum);
            if (entry.parentSlug().isBlank()) {
                roots++;
            } else {
                childCounts.merge(entry.parentSlug(), 1, Integer::sum);
                if (!bySlug.containsKey(entry.parentSlug())) {
                    orphanParents.add(entry.slug() + "->" + entry.parentSlug());
                }
            }
            maxDepth = Math.max(maxDepth, depthOf(entry.slug(), bySlug));
        }

        for (TopicTaxonomySupport.TopicTaxonomyEntry entry : taxonomy) {
            if (entry != null && childCounts.getOrDefault(entry.slug(), 0) == 0) {
                leaves++;
            }
        }

        List<CategoryCount> categories = categoryCounts.entrySet().stream()
                .map(entry -> new CategoryCount(entry.getKey(), entry.getValue()))
                .toList();
        List<ParentChildCount> parents = childCounts.entrySet().stream()
                .map(entry -> new ParentChildCount(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(ParentChildCount::children).reversed()
                        .thenComparing(ParentChildCount::slug))
                .limit(topListLimit)
                .toList();
        orphanParents.sort(String::compareTo);
        return new TaxonomyStats(taxonomy.size(), roots, leaves, maxDepth, categories, parents, orphanParents);
    }

    private static int depthOf(String slug, Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> bySlug) {
        int depth = 0;
        String current = slug;
        Set<String> seen = new LinkedHashSet<>();
        while (current != null && !current.isBlank()) {
            TopicTaxonomySupport.TopicTaxonomyEntry entry = bySlug.get(current);
            if (entry == null || entry.parentSlug().isBlank() || !seen.add(current)) {
                break;
            }
            current = entry.parentSlug();
            depth++;
        }
        return depth;
    }

    static String normalizeHeadingKey(String raw) {
        String normalized = normalizeLatin(raw);
        if (normalized.isBlank()) {
            return "";
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split(" ")) {
            if (token.isBlank() || token.length() < 3 || token.chars().allMatch(Character::isDigit)
                    || HEADING_STOPWORDS.contains(token)) {
                continue;
            }
            tokens.add(token);
        }
        if (tokens.size() < 2) {
            return "";
        }
        return String.join(" ", tokens.stream().limit(6).toList());
    }

    private static long readManifestCount(Path manifestPath, String field) {
        if (manifestPath == null || !Files.exists(manifestPath)) {
            return 0L;
        }
        try {
            JsonNode manifest = MAPPER.readTree(Files.readString(manifestPath, StandardCharsets.UTF_8));
            return manifest.path(field).asLong(0L);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String englishPreview(String english) {
        String compact = cleanWhitespace(english);
        if (compact.length() <= 220) {
            return compact;
        }
        return compact.substring(0, 220).trim() + "...";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String cleanWhitespace(String value) {
        return clean(value).replaceAll("\\s+", " ");
    }

    private static String normalizeLatin(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return normalized;
    }

    private static String pad(String value) {
        String clean = value == null ? "" : value.trim();
        return clean.isBlank() ? "" : " " + clean + " ";
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

    record AuditReport(String generatedAt,
                       CorpusStats corpus,
                       TaxonomyStats taxonomy,
                       HeadingCoverage headingCoverage,
                       List<GapCandidate> candidateGaps) {
    }

    record CorpusStats(long manifestDocuments,
                       long manifestBatches,
                       long documents,
                       int batchFiles,
                       int distinctBooks,
                       int distinctChapters) {
    }

    record TaxonomyStats(int entries,
                         int roots,
                         int leaves,
                         int maxDepth,
                         List<CategoryCount> categories,
                         List<ParentChildCount> parentsByChildren,
                         List<String> orphanParents) {
    }

    record CategoryCount(String category, int entries) {
    }

    record ParentChildCount(String slug, int children) {
    }

    record HeadingCoverage(long coveredDocuments,
                           long uncoveredDocuments,
                           long genericUncoveredDocuments,
                           double coveredRatio,
                           List<SlugCount> matchedTags,
                           List<BookCount> uncoveredBooks) {
    }

    record SlugCount(String slug, long documents) {
    }

    record BookCount(String book, long documents) {
    }

    record GapCandidate(String headingKey,
                        String representativeHeading,
                        long documents,
                        List<String> books,
                        List<SampleNarration> samples) {
    }

    record SampleNarration(String id, String book, String chapter, String englishPreview) {
    }

    private record CorpusNarration(String id, String book, String chapter, String english, String arabic) {
    }

    private record SeedProfile(String slug, List<String> englishSeeds, List<String> arabicSeeds) {
        private boolean matches(String englishSignal, String arabicSignal) {
            for (String seed : englishSeeds) {
                if (!seed.isBlank() && englishSignal.contains(" " + seed + " ")) {
                    return true;
                }
            }
            for (String seed : arabicSeeds) {
                if (!seed.isBlank() && arabicSignal.contains(" " + seed + " ")) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class SeedIndex {
        private final List<SeedProfile> profiles;

        private SeedIndex(List<SeedProfile> profiles) {
            this.profiles = profiles;
        }

        private static SeedIndex from(List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy) {
            List<SeedProfile> profiles = new ArrayList<>();
            for (TopicTaxonomySupport.TopicTaxonomyEntry entry : taxonomy) {
                if (entry == null || entry.slug().isBlank()) {
                    continue;
                }
                LinkedHashSet<String> englishSeeds = new LinkedHashSet<>();
                LinkedHashSet<String> arabicSeeds = new LinkedHashSet<>();
                if (TopicTaxonomySeedSupport.useDefaultLiteralSeeds(entry.slug())) {
                    addEnglishSeed(englishSeeds, entry.slug().replace('-', ' '));
                    addEnglishSeed(englishSeeds, entry.englishLabel());
                    addEnglishParentheticalSeeds(englishSeeds, entry.englishLabel());
                    addArabicSeed(arabicSeeds, entry.arabicLabel());
                }
                for (String seed : TopicTaxonomySeedSupport.extraSeeds(entry.slug())) {
                    if (TopicTaxonomySeedSupport.looksArabic(seed)) {
                        addArabicSeed(arabicSeeds, seed);
                    } else {
                        addEnglishSeed(englishSeeds, seed);
                    }
                }
                profiles.add(new SeedProfile(entry.slug(), List.copyOf(englishSeeds), List.copyOf(arabicSeeds)));
            }
            return new SeedIndex(List.copyOf(profiles));
        }

        private List<String> match(CorpusNarration narration) {
            String englishSignal = pad(normalizeLatin(narration.book() + " " + narration.chapter()));
            String arabicSignal = pad(SimilarHadithRanking.normalizeArabic(narration.book() + " " + narration.chapter()
                    + " " + narration.arabic()));
            List<String> matches = new ArrayList<>();
            for (SeedProfile profile : profiles) {
                if (profile.matches(englishSignal, arabicSignal)) {
                    matches.add(profile.slug());
                }
            }
            return matches;
        }

        private static void addEnglishSeed(Set<String> seeds, String raw) {
            String normalized = normalizeLatin(raw);
            if (!normalized.isBlank() && normalized.length() >= 3) {
                seeds.add(normalized);
            }
        }

        private static void addEnglishParentheticalSeeds(Set<String> seeds, String raw) {
            if (raw == null || raw.isBlank()) {
                return;
            }
            String withoutParenthetical = raw.replaceAll("\\([^)]*\\)", " ").trim();
            addEnglishSeed(seeds, withoutParenthetical);
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\(([^)]+)\\)").matcher(raw);
            while (matcher.find()) {
                addEnglishSeed(seeds, matcher.group(1));
            }
        }

        private static void addArabicSeed(Set<String> seeds, String raw) {
            String normalized = SimilarHadithRanking.normalizeArabic(raw);
            if (!normalized.isBlank()) {
                seeds.add(normalized);
            }
        }
    }

    private static final class Accumulator {
        private final SeedIndex seedIndex;
        private final int samplePerGap;
        private final Set<String> books = new LinkedHashSet<>();
        private final Set<String> uniqueChapters = new LinkedHashSet<>();
        private final Map<String, Long> matchedTags = new LinkedHashMap<>();
        private final Map<String, Long> uncoveredBooks = new LinkedHashMap<>();
        private final Map<String, GapAccumulator> gapAccumulators = new LinkedHashMap<>();
        private long documents;
        private int batchFiles;
        private long coveredDocuments;
        private long uncoveredDocuments;
        private long genericUncoveredDocuments;

        private Accumulator(SeedIndex seedIndex, int samplePerGap) {
            this.seedIndex = seedIndex;
            this.samplePerGap = samplePerGap;
        }

        private void incrementBatchFiles() {
            batchFiles++;
        }

        private void accept(CorpusNarration narration) {
            documents++;
            if (!narration.book().isBlank()) {
                books.add(narration.book());
            }
            if (!narration.chapter().isBlank()) {
                uniqueChapters.add(narration.chapter());
            }

            List<String> matches = seedIndex.match(narration);
            if (!matches.isEmpty()) {
                coveredDocuments++;
                for (String slug : new LinkedHashSet<>(matches)) {
                    matchedTags.merge(slug, 1L, Long::sum);
                }
                return;
            }

            uncoveredDocuments++;
            uncoveredBooks.merge(narration.book().isBlank() ? "Unknown Book" : narration.book(), 1L, Long::sum);
            String headingKey = normalizeHeadingKey(narration.chapter());
            if (headingKey.isBlank()) {
                genericUncoveredDocuments++;
                return;
            }
            gapAccumulators.computeIfAbsent(headingKey, key -> new GapAccumulator(samplePerGap))
                    .accept(narration);
        }

        private HeadingCoverage toHeadingCoverage(int topListLimit) {
            double coveredRatio = documents == 0 ? 0d : ((double) coveredDocuments / (double) documents);
            List<SlugCount> topMatchedTags = matchedTags.entrySet().stream()
                    .map(entry -> new SlugCount(entry.getKey(), entry.getValue()))
                    .sorted(Comparator.comparingLong(SlugCount::documents).reversed()
                            .thenComparing(SlugCount::slug))
                    .limit(topListLimit)
                    .toList();
            List<BookCount> topUncoveredBooks = uncoveredBooks.entrySet().stream()
                    .map(entry -> new BookCount(entry.getKey(), entry.getValue()))
                    .sorted(Comparator.comparingLong(BookCount::documents).reversed()
                            .thenComparing(BookCount::book))
                    .limit(topListLimit)
                    .toList();
            return new HeadingCoverage(
                    coveredDocuments,
                    uncoveredDocuments,
                    genericUncoveredDocuments,
                    Math.round(coveredRatio * 10000d) / 10000d,
                    topMatchedTags,
                    topUncoveredBooks);
        }

        private List<GapCandidate> toCandidateGaps(int topListLimit, int minGapDocs) {
            return gapAccumulators.entrySet().stream()
                    .map(entry -> entry.getValue().toCandidate(entry.getKey()))
                    .filter(candidate -> candidate.documents() >= minGapDocs)
                    .sorted(Comparator.comparingLong(GapCandidate::documents).reversed()
                            .thenComparing(GapCandidate::headingKey))
                    .limit(topListLimit)
                    .toList();
        }
    }

    private static final class GapAccumulator {
        private final int sampleLimit;
        private final Map<String, Integer> headings = new LinkedHashMap<>();
        private final Map<String, Integer> books = new LinkedHashMap<>();
        private final List<SampleNarration> samples = new ArrayList<>();
        private long documents;

        private GapAccumulator(int sampleLimit) {
            this.sampleLimit = sampleLimit;
        }

        private void accept(CorpusNarration narration) {
            documents++;
            headings.merge(narration.chapter(), 1, Integer::sum);
            books.merge(narration.book().isBlank() ? "Unknown Book" : narration.book(), 1, Integer::sum);
            if (samples.size() < sampleLimit) {
                samples.add(new SampleNarration(
                        narration.id(),
                        narration.book(),
                        narration.chapter(),
                        englishPreview(narration.english())));
            }
        }

        private GapCandidate toCandidate(String headingKey) {
            String representativeHeading = headings.entrySet().stream()
                    .max(Comparator.comparingInt(Map.Entry<String, Integer>::getValue)
                            .thenComparing(Map.Entry::getKey))
                    .map(Map.Entry::getKey)
                    .orElse(headingKey);
            List<String> topBooks = books.entrySet().stream()
                    .sorted(Comparator.comparingInt(Map.Entry<String, Integer>::getValue).reversed()
                            .thenComparing(Map.Entry::getKey))
                    .map(Map.Entry::getKey)
                    .limit(5)
                    .toList();
            return new GapCandidate(headingKey, representativeHeading, documents, topBooks, List.copyOf(samples));
        }
    }
}
