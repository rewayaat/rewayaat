package com.rewayaat.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.config.ESClientProvider;
import com.rewayaat.core.data.HadithObject;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Represents a similarity query for narrations.
 */
public class SimilarHadithQueryResult implements RewayaatQueryResult {

    private static final Logger LOGGER = LoggerFactory.getLogger(SimilarHadithQueryResult.class);

    private static final String SEMANTIC_INFERENCE_ID = readStringSetting("SIMILAR_SEMANTIC_INFERENCE_ID", "rewayaat-multilingual-e5-large");
    private static final String SEMANTIC_VECTOR_FIELD = "semantic_vector";
    private static final String ARABIC_FIELD = "arabic";
    private static final float SEMANTIC_MIN_SIMILARITY = readFloatSetting("SIMILAR_SEMANTIC_MIN_SIMILARITY", 0.90f);
    private static final float SIMILAR_SEMANTIC_WEIGHT = readFloatSetting("SIMILAR_SEMANTIC_WEIGHT", 0.85f);
    private static final float SIMILAR_SYNTACTIC_WEIGHT = readFloatSetting("SIMILAR_SYNTACTIC_WEIGHT", 0.15f);
    private static final int SEMANTIC_POOL_SIZE = readIntSetting("SIMILAR_SEMANTIC_POOL_SIZE", 30);
    private static final int SEMANTIC_POOL_SIZE_MAX = readIntSetting("SIMILAR_SEMANTIC_POOL_SIZE_MAX", 90);
    private static final int SEMANTIC_NUM_CANDIDATES = readIntSetting("SIMILAR_SEMANTIC_NUM_CANDIDATES", 120);
    private static final int SEMANTIC_MAX_TEXT_CHARS = readIntSetting("SIMILAR_SEMANTIC_MAX_TEXT_CHARS", 4000);
    private static final double SIMILARITY_MIN_PERCENT = readFloatSetting("SIMILARITY_MIN_PERCENT", 80f);
    private static final boolean SEMANTIC_ENABLED = readBooleanSetting("SIMILAR_SEMANTIC_ENABLED", true);
    private static final Pattern ARABIC_DIACRITICS = Pattern.compile("[\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]");
    private static final Pattern NON_ARABIC_LETTERS = Pattern.compile("[^\\p{IsArabic}\\p{Digit}\\s]");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    private final String hadithId;
    private final int page;
    private final int pageSize;
    private final ObjectMapper mapper = new ObjectMapper();

    public SimilarHadithQueryResult(String hadithId, int page, int perPage) {
        this.hadithId = hadithId;
        this.page = page;
        this.pageSize = perPage;
    }

    @Override
    public HadithObjectCollection result() throws Exception {
        if (hadithId == null || hadithId.trim().isEmpty()) {
            return new HadithObjectCollection(new ArrayList<>(), 0);
        }
        if (!SEMANTIC_ENABLED) {
            LOGGER.warn("Semantic similarity is disabled. Returning no similar hadith for id {}", hadithId);
            return new HadithObjectCollection(new ArrayList<>(), 0);
        }
        try (ESClientProvider provider = new ESClientProvider()) {
            HadithObjectCollection semantic = semanticResult(provider);
            if (semantic != null) {
                return semantic;
            }
            return new HadithObjectCollection(new ArrayList<>(), 0);
        } catch (Exception ex) {
            LOGGER.warn("Unable to fetch similar hadith for id {}", hadithId, ex);
            return new HadithObjectCollection(new ArrayList<>(), 0);
        }
    }

    private HadithObjectCollection semanticResult(ESClientProvider provider) {
        try {
            GetResponse<Map> sourceResp = provider.client().get(g -> g.index(ESClientProvider.INDEX).id(hadithId), Map.class);
            if (!sourceResp.found() || sourceResp.source() == null) {
                LOGGER.info("Semantic lookup skipped for id {} because source document was not found.", hadithId);
                return null;
            }
            String semanticText = extractArabicSemanticText(sourceResp.source());
            if (semanticText.isEmpty()) {
                LOGGER.info("Arabic text missing for id {}.", hadithId);
                return null;
            }
            String sourceArabicForSyntax = normalizeArabicForSyntax(valueAsString(sourceResp.source().get(ARABIC_FIELD)));
            int poolSize = resolveSemanticPoolSize();
            int numCandidates = resolveSemanticNumCandidates(poolSize);
            List<HadithObject> hadithes = new ArrayList<>();
            SearchResponse<Map> response = provider.client().search(buildSemanticSearchRequest(semanticText, poolSize, numCandidates), Map.class);
            for (Hit<Map> hit : response.hits().hits()) {
                processHit(hadithes, hit, sourceArabicForSyntax);
            }
            List<HadithObject> unique = new ArrayList<>(new LinkedHashSet<>(hadithes));
            long totalHits = unique.size();
            LOGGER.info("Hybrid similar hadith results: {} (total={} | pool={} | minSimilarity={} | minPercent={} | semanticWeight={} | syntacticWeight={} | arabicOnly=true | inference={})",
                    unique.size(),
                    totalHits,
                    poolSize,
                    SEMANTIC_MIN_SIMILARITY,
                    SIMILARITY_MIN_PERCENT,
                    SIMILAR_SEMANTIC_WEIGHT,
                    SIMILAR_SYNTACTIC_WEIGHT,
                    SEMANTIC_INFERENCE_ID);
            return paginate(unique, totalHits);
        } catch (Exception ex) {
            LOGGER.warn("Semantic similarity failed for id {}.", hadithId, ex);
            return null;
        }
    }

    private SearchRequest buildSemanticSearchRequest(String semanticText, int poolSize, int numCandidates) {
        return new SearchRequest.Builder()
                .index(ESClientProvider.INDEX)
                .size(poolSize)
                .knn(buildSemanticKnnSearch(semanticText, poolSize, numCandidates))
                .build();
    }

    private KnnSearch buildSemanticKnnSearch(String semanticText, int poolSize, int numCandidates) {
        KnnSearch.Builder builder = new KnnSearch.Builder()
                .field(SEMANTIC_VECTOR_FIELD)
                .queryVectorBuilder(qvb -> qvb.textEmbedding(te -> te
                        .modelId(SEMANTIC_INFERENCE_ID)
                        .modelText(semanticText)))
                .k(poolSize)
                .numCandidates(numCandidates)
                .filter(f -> f.exists(e -> e.field(SEMANTIC_VECTOR_FIELD)))
                .filter(f -> f.bool(b -> b.mustNot(mn -> mn.ids(ids -> ids.values(hadithId)))));
        if (SEMANTIC_MIN_SIMILARITY > 0f) {
            builder.similarity(SEMANTIC_MIN_SIMILARITY);
        }
        return builder.build();
    }

    private String extractArabicSemanticText(Map source) {
        if (source == null || source.isEmpty()) {
            return "";
        }
        String arabic = valueAsString(source.get("arabic"));
        if (arabic.isEmpty()) {
            return "";
        }
        return SemanticTextNormalizer.normalizeForQuery(arabic, SEMANTIC_MAX_TEXT_CHARS);
    }

    private String valueAsString(Object value) {
        if (!(value instanceof String)) {
            return "";
        }
        return ((String) value).trim();
    }

    private int resolveSemanticPoolSize() {
        int requestedWindow = Math.max(pageSize, 1) * Math.max(page + 1, 1);
        int pool = Math.max(SEMANTIC_POOL_SIZE, requestedWindow);
        if (SEMANTIC_POOL_SIZE_MAX > 0) {
            pool = Math.min(pool, SEMANTIC_POOL_SIZE_MAX);
        }
        return Math.max(pool, 1);
    }

    private int resolveSemanticNumCandidates(int poolSize) {
        int minimum = Math.max(poolSize * 2, 50);
        int resolved = Math.max(SEMANTIC_NUM_CANDIDATES, minimum);
        return Math.max(resolved, poolSize);
    }

    private HadithObjectCollection paginate(List<HadithObject> items, long totalHits) {
        List<HadithObject> safeItems = items == null ? new ArrayList<>() : items;
        if (pageSize <= 0) {
            return new HadithObjectCollection(new ArrayList<>(), totalHits);
        }
        int fromIndex = page * pageSize;
        if (fromIndex >= safeItems.size()) {
            return new HadithObjectCollection(new ArrayList<>(), totalHits);
        }
        int toIndex = Math.min(fromIndex + pageSize, safeItems.size());
        return new HadithObjectCollection(new ArrayList<>(safeItems.subList(fromIndex, toIndex)), totalHits);
    }

    private static boolean readBooleanSetting(String key, boolean defaultValue) {
        String value = firstNonEmpty(System.getProperty(key), System.getenv(key));
        if (value == null) {
            return defaultValue;
        }
        String normalized = value.trim();
        return normalized.equalsIgnoreCase("true") || normalized.equals("1") || normalized.equalsIgnoreCase("yes");
    }

    private static float readFloatSetting(String key, float defaultValue) {
        String value = firstNonEmpty(System.getProperty(key), System.getenv(key));
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static int readIntSetting(String key, int defaultValue) {
        String value = firstNonEmpty(System.getProperty(key), System.getenv(key));
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static String readStringSetting(String key, String defaultValue) {
        String value = firstNonEmpty(System.getProperty(key), System.getenv(key));
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim();
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

    private void processHit(List<HadithObject> hadithes, Hit<Map> hit, String sourceArabicForSyntax) {
        Map<String, Object> source = hit.source();
        if (source == null) {
            return;
        }
        Map<String, Object> mutableSource = new HashMap<>(source);
        mutableSource.put("_id", hit.id());
        double semanticPercent = 0d;
        if (hit.score() != null) {
            mutableSource.put("similarityScore", round(hit.score(), 6));
            semanticPercent = toSemanticPercent(hit.score());
        }
        String targetArabicForSyntax = normalizeArabicForSyntax(valueAsString(source.get(ARABIC_FIELD)));
        double syntacticPercent = toSyntacticPercent(sourceArabicForSyntax, targetArabicForSyntax);
        double similarityPercent = combineSimilarityPercent(semanticPercent, syntacticPercent);
        mutableSource.put("semanticSimilarityPercent", semanticPercent);
        mutableSource.put("syntacticSimilarityPercent", syntacticPercent);
        mutableSource.put("similarityPercent", similarityPercent);
        HadithDisplaySegmenter.enrich(mutableSource);
        if (SIMILARITY_MIN_PERCENT > 0d && similarityPercent < SIMILARITY_MIN_PERCENT) {
            return;
        }
        hadithes.add(mapper.convertValue(mutableSource, HadithObject.class));
    }

    private double toSemanticPercent(double score) {
        if (score <= 0d) {
            return 0d;
        }
        double percent = score <= 1.0d ? (score * 100.0d) : score;
        return round(clampPercent(percent), 2);
    }

    private double toSyntacticPercent(String sourceArabic, String targetArabic) {
        if (sourceArabic == null || sourceArabic.isEmpty() || targetArabic == null || targetArabic.isEmpty()) {
            return 0d;
        }
        double tokenScore = tokenJaccard(sourceArabic, targetArabic);
        double charScore = charNgramJaccard(sourceArabic, targetArabic, 3);
        double score = (tokenScore * 0.65d) + (charScore * 0.35d);
        return round(clampPercent(score * 100.0d), 2);
    }

    private double combineSimilarityPercent(double semanticPercent, double syntacticPercent) {
        double semanticWeight = Math.max(0d, SIMILAR_SEMANTIC_WEIGHT);
        double syntacticWeight = Math.max(0d, SIMILAR_SYNTACTIC_WEIGHT);
        double totalWeight = semanticWeight + syntacticWeight;
        if (totalWeight <= 0d) {
            return round(clampPercent(semanticPercent), 2);
        }
        double combined = ((semanticPercent * semanticWeight) + (syntacticPercent * syntacticWeight)) / totalWeight;
        return round(clampPercent(combined), 2);
    }

    private double tokenJaccard(String source, String target) {
        Set<String> sourceTokens = tokenizeArabic(source);
        Set<String> targetTokens = tokenizeArabic(target);
        if (sourceTokens.isEmpty() || targetTokens.isEmpty()) {
            return 0d;
        }
        Set<String> intersection = new HashSet<>(sourceTokens);
        intersection.retainAll(targetTokens);
        Set<String> union = new HashSet<>(sourceTokens);
        union.addAll(targetTokens);
        if (union.isEmpty()) {
            return 0d;
        }
        return ((double) intersection.size()) / ((double) union.size());
    }

    private double charNgramJaccard(String source, String target, int ngramSize) {
        Set<String> sourceNgrams = toCharacterNgrams(source, ngramSize);
        Set<String> targetNgrams = toCharacterNgrams(target, ngramSize);
        if (sourceNgrams.isEmpty() || targetNgrams.isEmpty()) {
            return 0d;
        }
        Set<String> intersection = new HashSet<>(sourceNgrams);
        intersection.retainAll(targetNgrams);
        Set<String> union = new HashSet<>(sourceNgrams);
        union.addAll(targetNgrams);
        if (union.isEmpty()) {
            return 0d;
        }
        return ((double) intersection.size()) / ((double) union.size());
    }

    private Set<String> tokenizeArabic(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        for (String token : text.split(" ")) {
            String clean = token == null ? "" : token.trim();
            if (clean.length() >= 2) {
                tokens.add(clean);
            }
        }
        return tokens;
    }

    private Set<String> toCharacterNgrams(String text, int ngramSize) {
        Set<String> ngrams = new HashSet<>();
        if (text == null || text.isEmpty() || ngramSize <= 0) {
            return ngrams;
        }
        String compact = text.replace(" ", "");
        if (compact.length() < ngramSize) {
            return ngrams;
        }
        for (int i = 0; i <= compact.length() - ngramSize; i++) {
            ngrams.add(compact.substring(i, i + ngramSize));
        }
        return ngrams;
    }

    private String normalizeArabicForSyntax(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) {
            return "";
        }
        String normalized = SemanticTextNormalizer.normalizeBody(rawText, SEMANTIC_MAX_TEXT_CHARS);
        if (normalized.isEmpty()) {
            return "";
        }
        normalized = ARABIC_DIACRITICS.matcher(normalized).replaceAll("");
        normalized = normalized.replace("أ", "ا")
                .replace("إ", "ا")
                .replace("آ", "ا")
                .replace("ٱ", "ا")
                .replace("ى", "ي")
                .replace("ؤ", "و")
                .replace("ئ", "ي")
                .replace("ة", "ه")
                .replace("ـ", "");
        normalized = NON_ARABIC_LETTERS.matcher(normalized).replaceAll(" ");
        normalized = MULTI_SPACE.matcher(normalized).replaceAll(" ").trim();
        return normalized;
    }

    private double clampPercent(double value) {
        if (value < 0d) {
            return 0d;
        }
        if (value > 100d) {
            return 100d;
        }
        return value;
    }

    private double round(double value, int precision) {
        double scale = Math.pow(10, precision);
        return Math.round(value * scale) / scale;
    }
}
