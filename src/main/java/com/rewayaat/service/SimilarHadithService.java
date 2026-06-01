package com.rewayaat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rewayaat.config.ESClientProvider;
import com.rewayaat.core.HadithDisplaySegmenter;
import com.rewayaat.core.HadithSignificantTerms;
import com.rewayaat.core.HadithSemanticText;
import com.rewayaat.core.HadithObjectCollection;
import com.rewayaat.core.SimilarHadithRanking;
import com.rewayaat.core.data.HadithObject;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Similar hadith retrieval with hybrid candidate generation and optional LLM reranking.
 */
@Service
public class SimilarHadithService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SimilarHadithService.class);

    private static final String SEMANTIC_VECTOR_FIELD = "semantic_vector";
    private static final String ARABIC_FIELD = "arabic";
    private static final String SEMANTIC_TEXT_FIELD = "semantic_text";
    private static final String SIGNIFICANT_TERMS_FIELD = HadithSignificantTerms.FIELD_NAME;
    private static final String TOPIC_TAGS_FIELD = "topic_tags";
    private static final int SIGNIFICANT_TERMS_STORED_LIMIT = readIntSetting("SIMILAR_SIGNIFICANT_TERMS_STORED_LIMIT", 6);
    private static final int SIGNIFICANT_TERMS_QUERY_LIMIT = readIntSetting("SIMILAR_SIGNIFICANT_TERMS_QUERY_LIMIT", 10);

    private static final float SEMANTIC_MIN_SIMILARITY = readFloatSetting("SIMILAR_SEMANTIC_MIN_SIMILARITY", 0.60f);
    private static final int SEMANTIC_POOL_SIZE = readIntSetting("SIMILAR_SEMANTIC_POOL_SIZE", 300);
    private static final int SEMANTIC_NUM_CANDIDATES = readIntSetting("SIMILAR_SEMANTIC_NUM_CANDIDATES", 750);
    private static final int LEXICAL_POOL_SIZE = readIntSetting("SIMILAR_LEXICAL_POOL_SIZE", 220);
    private static final int CANDIDATE_LIMIT = readIntSetting("SIMILAR_HYBRID_CANDIDATE_LIMIT", 280);
    private static final int RERANK_INPUT_LIMIT = readIntSetting("SIMILAR_RERANK_INPUT_LIMIT", 24);
    private static final int MAX_RESULT_ITEMS = readIntSetting("SIMILAR_MAX_RESULT_ITEMS", 40);

    private static final float FINAL_MIN_PERCENT = readFloatSetting("SIMILAR_FINAL_MIN_PERCENT", 45f);
    private static final float FINAL_LLM_WEIGHT = readFloatSetting("SIMILAR_FINAL_LLM_WEIGHT", 0.88f);
    private static final int QUICK_COUNT_VERIFY_THRESHOLD = readIntSetting("SIMILAR_QUICK_COUNT_VERIFY_THRESHOLD", 3);
    private static final float RETRIEVAL_SYNTACTIC_WEIGHT = readFloatSetting("SIMILAR_RETRIEVAL_SYNTACTIC_WEIGHT", 0.10f);
    private static final float RETRIEVAL_CONTENT_WEIGHT = readFloatSetting("SIMILAR_RETRIEVAL_CONTENT_WEIGHT", 0.15f);
    private static final float RETRIEVAL_TOPIC_WEIGHT = readFloatSetting("SIMILAR_RETRIEVAL_TOPIC_WEIGHT", 0.25f);
    private static final float RRF_SEMANTIC_WEIGHT = readFloatSetting("SIMILAR_RRF_SEMANTIC_WEIGHT", 0.85f);
    private static final float RRF_LEXICAL_WEIGHT = readFloatSetting("SIMILAR_RRF_LEXICAL_WEIGHT", 0.15f);
    private static final int RRF_K = readIntSetting("SIMILAR_RRF_K", 60);
    private static final float SEMANTIC_SCORE_EXPONENT = readFloatSetting("SIMILAR_SEMANTIC_PERCENT_EXPONENT", 1.2f);

    private static final int MAX_MATN_CHARS = readIntSetting("SIMILAR_MATN_MAX_CHARS", 4200);
    private static final String LEXICAL_MIN_SHOULD_MATCH = readStringSetting("SIMILAR_LEXICAL_MIN_SHOULD_MATCH", "55%");
    private static final int LEXICAL_CONTENT_QUERY_TOKEN_LIMIT = readIntSetting("SIMILAR_LEXICAL_CONTENT_QUERY_TOKEN_LIMIT", 18);
    private static final String LEXICAL_SIGNIFICANT_MIN_SHOULD_MATCH = readStringSetting("SIMILAR_LEXICAL_SIGNIFICANT_MIN_SHOULD_MATCH", "70%");
    private static final float LEXICAL_SIGNIFICANT_TERMS_BOOST = readFloatSetting("SIMILAR_LEXICAL_SIGNIFICANT_TERMS_BOOST", 5.5f);
    private static final float LEXICAL_TOPIC_TAGS_BOOST = readFloatSetting("SIMILAR_LEXICAL_TOPIC_TAGS_BOOST", 3.0f);
    private static final float SIGNIFICANT_TERMS_SUPPORT_WEIGHT = readFloatSetting("SIMILAR_SIGNIFICANT_TERMS_SUPPORT_WEIGHT", 0.55f);
    private static final int DISTINCTIVE_TOKEN_GATE_MIN = readIntSetting("SIMILAR_DISTINCTIVE_TOKEN_GATE_MIN", 3);
    private static final int DISTINCTIVE_TOKEN_HIGH_CONTEXT_MIN = readIntSetting("SIMILAR_DISTINCTIVE_TOKEN_HIGH_CONTEXT_MIN", 12);
    private static final float DISTINCTIVE_CONTENT_MIN_PERCENT = readFloatSetting("SIMILAR_DISTINCTIVE_CONTENT_MIN_PERCENT", 12f);
    private static final float DISTINCTIVE_CONTENT_TRACE_PERCENT = readFloatSetting("SIMILAR_DISTINCTIVE_CONTENT_TRACE_PERCENT", 8f);
    private static final float DISTINCTIVE_SYNTACTIC_MIN_PERCENT = readFloatSetting("SIMILAR_DISTINCTIVE_SYNTACTIC_MIN_PERCENT", 20f);
    private static final float DISTINCTIVE_SYNTACTIC_HIGH_CONTEXT_MIN_PERCENT = readFloatSetting("SIMILAR_DISTINCTIVE_SYNTACTIC_HIGH_CONTEXT_MIN_PERCENT", 26f);
    private static final int TOPIC_TAG_RESCUE_MIN = readIntSetting("SIMILAR_TOPIC_TAG_RESCUE_MIN", 3);
    private static final float NEAR_DUPLICATE_SYNTACTIC_PERCENT = readFloatSetting("SIMILAR_NEAR_DUPLICATE_SYNTACTIC_PERCENT", 92f);
    private static final float MIN_CONTENT_OVERLAP_PERCENT = readFloatSetting("SIMILAR_MIN_CONTENT_OVERLAP_PERCENT", 12f);

    private final SimilarHadithRerankerService rerankerService;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${similar.rerank.enabled:true}")
    private boolean rerankEnabled;

    @Value("${similar.retrieval.min-percent:-1}")
    private double retrievalOnlyMinPercent;

    private final Cache<String, List<HadithObject>> rerankedCache = Caffeine.newBuilder()
            .maximumSize(5000)
            .expireAfterWrite(Duration.ofHours(6))
            .build();

    private final Cache<String, List<String>> significantTermsCache = Caffeine.newBuilder()
            .maximumSize(5000)
            .expireAfterWrite(Duration.ofHours(12))
            .build();

    private final Cache<String, Long> quickCountCache = Caffeine.newBuilder()
            .maximumSize(20000)
            .expireAfterWrite(Duration.ofHours(2))
            .build();

    @Autowired
    public SimilarHadithService(SimilarHadithRerankerService rerankerService) {
        this.rerankerService = rerankerService;
    }

    public HadithObjectCollection findSimilar(String hadithId, int page, int pageSize) {
        if (hadithId == null || hadithId.trim().isEmpty()) {
            return new HadithObjectCollection(new ArrayList<>(), 0);
        }
        String safeId = hadithId.trim();
        int safePage = Math.max(page, 0);
        int safePageSize = Math.max(pageSize, 0);

        if (safePageSize == 0) {
            long count = resolveCountOnly(safeId);
            return new HadithObjectCollection(new ArrayList<>(), count);
        }

        List<HadithObject> all = rerankedCache.getIfPresent(safeId);
        if (all == null) {
            all = computeRerankedSimilar(safeId);
            rerankedCache.put(safeId, all);
            quickCountCache.put(safeId, (long) all.size());
        }
        return paginate(all, safePage, safePageSize);
    }

    private long resolveCountOnly(String hadithId) {
        List<HadithObject> cached = rerankedCache.getIfPresent(hadithId);
        if (cached != null) {
            return cached.size();
        }
        Long cachedCount = quickCountCache.getIfPresent(hadithId);
        if (cachedCount != null && cachedCount > QUICK_COUNT_VERIFY_THRESHOLD) {
            return cachedCount;
        }
        if (cachedCount != null && cachedCount <= QUICK_COUNT_VERIFY_THRESHOLD) {
            List<HadithObject> exact = computeRerankedSimilar(hadithId);
            rerankedCache.put(hadithId, exact);
            quickCountCache.put(hadithId, (long) exact.size());
            return exact.size();
        }
        long estimated = estimateCountWithoutRerank(hadithId);
        if (estimated <= QUICK_COUNT_VERIFY_THRESHOLD) {
            List<HadithObject> exact = computeRerankedSimilar(hadithId);
            rerankedCache.put(hadithId, exact);
            quickCountCache.put(hadithId, (long) exact.size());
            return exact.size();
        }
        quickCountCache.put(hadithId, estimated);
        return estimated;
    }

    private long estimateCountWithoutRerank(String hadithId) {
        try (ESClientProvider provider = new ESClientProvider()) {
            SourceNarration source = loadSourceNarration(provider, hadithId);
            if (source == null || source.matnArabic().isBlank() || source.normalizedMatn().isBlank()) {
                return 0;
            }
            List<SimilarCandidate> candidates = retrieveHybridCandidates(provider, source, Math.max(32, Math.min(CANDIDATE_LIMIT, 140)));
            double retrievalMinPercent = effectiveRetrievalOnlyMinPercent();
            return prepareDisplayCandidates(candidates, retrievalMinPercent).size();
        } catch (Exception ex) {
            LOGGER.warn("Unable to estimate similar hadith count for id {}", hadithId, ex);
            return 0;
        }
    }

    private List<HadithObject> computeRerankedSimilar(String hadithId) {
        try (ESClientProvider provider = new ESClientProvider()) {
            SourceNarration source = loadSourceNarration(provider, hadithId);
            if (source == null || source.matnArabic().isBlank() || source.normalizedMatn().isBlank()) {
                return new ArrayList<>();
            }

            List<SimilarCandidate> candidates = retrieveHybridCandidates(provider, source, CANDIDATE_LIMIT);
            double retrievalMinPercent = effectiveRetrievalOnlyMinPercent();
            List<SimilarCandidate> displayCandidates = prepareDisplayCandidates(candidates, retrievalMinPercent);
            if (displayCandidates.isEmpty()) {
                return new ArrayList<>();
            }
            if (!rerankEnabled) {
                LOGGER.info("Similar hadith LLM reranking disabled. Returning retrieval-ranked results for id {} with minimum percent {}", hadithId, retrievalMinPercent);
                return mapRetrievalOrderedResults(displayCandidates);
            }
            int rerankLimit = Math.min(RERANK_INPUT_LIMIT, displayCandidates.size());

            List<SimilarHadithRerankerService.RerankCandidate> rerankInputs = new ArrayList<>();
            for (int i = 0; i < rerankLimit; i++) {
                SimilarCandidate candidate = displayCandidates.get(i);
                rerankInputs.add(new SimilarHadithRerankerService.RerankCandidate(
                        candidate.id(),
                        candidate.matnArabic(),
                        candidate.retrievalPercent()));
            }

            SimilarHadithRerankerService.RerankDecision reranked = rerankerService.rerank(
                    hadithId,
                    source.matnArabic(),
                    rerankInputs);
            if (!reranked.success()) {
                LOGGER.info("Reranker unavailable for id {}. Falling back to retrieval-ranked results.", hadithId);
                return mapRetrievalOrderedResults(displayCandidates);
            }
            if (reranked.rankedScores().isEmpty()) {
                LOGGER.info("Reranker returned no ranking scores for id {}. Falling back to retrieval-ranked results.", hadithId);
                return mapRetrievalOrderedResults(displayCandidates);
            }
            return buildLlmRerankedResults(displayCandidates, rerankLimit, reranked.rankedScores());
        } catch (Exception ex) {
            LOGGER.warn("Unable to compute similar hadith for id {}", hadithId, ex);
            return new ArrayList<>();
        }
    }

    private List<SimilarCandidate> retrieveHybridCandidates(ESClientProvider provider, SourceNarration source, int maxCandidates) throws Exception {
        SemanticCandidatePool semanticPool = semanticHits(provider, source);
        List<Hit<Map>> semanticHits = semanticPool.hits();
        List<Hit<Map>> lexicalHits = lexicalHits(provider, source);
        if (semanticHits.isEmpty() && lexicalHits.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, MutableCandidate> merged = new LinkedHashMap<>();
        mergeHits(merged, semanticHits, true);
        mergeHits(merged, lexicalHits, false);

        List<MutableCandidate> ranked = new ArrayList<>();
        int sourceDistinctiveTokenCount = source.distinctiveTokenCount();
        for (MutableCandidate mutable : merged.values()) {
            if (mutable.id == null || mutable.id.isBlank() || mutable.id.equals(source.id())) {
                continue;
            }
            if (mutable.source == null) {
                continue;
            }
            String matnArabic = HadithSemanticText.extractContentOnlyMatn(mutable.source, MAX_MATN_CHARS);
            if (matnArabic.isBlank()) {
                continue;
            }
            String normalizedMatn = HadithSemanticText.normalizeForComparison(matnArabic);
            if (normalizedMatn.isBlank()) {
                continue;
            }
            mutable.matnArabic = matnArabic;
            mutable.normalizedMatn = normalizedMatn;
            mutable.lexicalPercent = SimilarHadithRanking.toPercentFromRawScore(mutable.lexicalRawScore);
            mutable.semanticPercent = semanticPool.available()
                    ? semanticPercentFromRawScore(mutable.semanticRawScore)
                    : mutable.lexicalPercent;
            mutable.syntacticPercent = SimilarHadithRanking.syntacticSimilarityPercent(source.normalizedMatn(), normalizedMatn);
            mutable.sharedSyntacticTokens = SimilarHadithRanking.sharedSyntacticTokens(source.normalizedMatn(), normalizedMatn);
            mutable.contentOverlapPercent = SimilarHadithRanking.bidirectionalTokenOverlapPercent(source.normalizedMatn(), normalizedMatn);
            mutable.sharedDistinctiveTokens = SimilarHadithRanking.sharedDistinctiveTokens(source.normalizedMatn(), normalizedMatn);
            mutable.sharedDistinctiveTokenCount = SimilarHadithRanking.sharedDistinctiveTokenCount(source.normalizedMatn(), normalizedMatn);
            mutable.topicOverlapPercent = SimilarHadithRanking.topicTagJaccardPercent(source.topicTags(), readTopicTags(mutable.source));
            mutable.sharedTopicTags = SimilarHadithRanking.sharedTopicTags(source.topicTags(), readTopicTags(mutable.source));
            mutable.sharedTopicTagCount = SimilarHadithRanking.sharedTopicTagCount(source.topicTags(), readTopicTags(mutable.source));
            List<String> candidateSignificantTerms = HadithSignificantTerms.readTerms(mutable.source, SIGNIFICANT_TERMS_STORED_LIMIT);
            String candidateTermsSeed = candidateSupportText(candidateSignificantTerms, normalizedMatn);
            mutable.significantTermOverlapPercent = SimilarHadithRanking.termRecallPercent(source.significantTerms(), candidateTermsSeed);
            mutable.sharedSignificantTerms = SimilarHadithRanking.sharedTerms(source.significantTerms(), normalizedMatn);
            mutable.sharedSignificantTermCount = SimilarHadithRanking.sharedTermCount(source.significantTerms(), candidateTermsSeed);
            mutable.supportPercent = computeSupportPercent(mutable.contentOverlapPercent, mutable.significantTermOverlapPercent);
            mutable.fusedScore = buildRrfScore(mutable);
            if (!isEligibleCandidate(mutable, source, semanticPool.available())) {
                continue;
            }
            mutable.retrievalPercent = semanticPool.available()
                    ? computeRetrievalPercent(
                            mutable.semanticPercent,
                            mutable.syntacticPercent,
                            mutable.supportPercent,
                            mutable.topicOverlapPercent)
                    : computeFallbackRetrievalPercent(
                            mutable.lexicalPercent,
                            mutable.syntacticPercent,
                            mutable.supportPercent);
            ranked.add(mutable);
        }
        if (ranked.isEmpty()) {
            return new ArrayList<>();
        }

        ranked.sort(Comparator
                .comparingDouble((MutableCandidate c) -> c.retrievalPercent)
                .thenComparingDouble(c -> c.significantTermOverlapPercent)
                .thenComparingDouble(c -> c.contentOverlapPercent)
                .thenComparingDouble(c -> c.semanticPercent)
                .thenComparingDouble(c -> c.syntacticPercent)
                .thenComparingInt(c -> c.lexicalRank > 0 ? 1 : 0)
                .thenComparingDouble(c -> c.fusedScore)
                .reversed());
        if (maxCandidates > 0 && ranked.size() > maxCandidates) {
            ranked = new ArrayList<>(ranked.subList(0, maxCandidates));
        }

        List<SimilarCandidate> resolved = new ArrayList<>();
        for (MutableCandidate mutable : ranked) {
            resolved.add(new SimilarCandidate(
                    mutable.id,
                    mutable.source,
                    mutable.semanticPercent,
                    mutable.lexicalPercent,
                    mutable.syntacticPercent,
                    mutable.contentOverlapPercent,
                    mutable.significantTermOverlapPercent,
                    mutable.sharedDistinctiveTokenCount,
                    mutable.sharedSignificantTermCount,
                    mutable.sharedSyntacticTokens,
                    mutable.sharedDistinctiveTokens,
                    mutable.sharedSignificantTerms,
                    mutable.topicOverlapPercent,
                    mutable.sharedTopicTagCount,
                    mutable.sharedTopicTags,
                    mutable.retrievalPercent,
                    mutable.fusedScore,
                    mutable.semanticRank,
                    mutable.lexicalRank,
                    mutable.matnArabic,
                    mutable.normalizedMatn));
        }
        return resolved;
    }

    private void mergeHits(Map<String, MutableCandidate> merged, List<Hit<Map>> hits, boolean semantic) {
        for (int i = 0; i < hits.size(); i++) {
            Hit<Map> hit = hits.get(i);
            if (hit == null || hit.id() == null || hit.id().isBlank() || hit.source() == null) {
                continue;
            }
            MutableCandidate existing = merged.computeIfAbsent(hit.id(), id -> new MutableCandidate(id, hit.source()));
            if (existing.source == null) {
                existing.source = hit.source();
            }
            if (semantic) {
                if (existing.semanticRank < 0) {
                    existing.semanticRank = i + 1;
                }
                if (hit.score() != null && hit.score() > existing.semanticRawScore) {
                    existing.semanticRawScore = hit.score();
                }
                continue;
            }
            if (existing.lexicalRank < 0) {
                existing.lexicalRank = i + 1;
            }
            if (hit.score() != null && hit.score() > existing.lexicalRawScore) {
                existing.lexicalRawScore = hit.score();
            }
        }
    }

    private double buildRrfScore(MutableCandidate candidate) {
        double semanticComponent = candidate.semanticRank > 0
                ? (RRF_SEMANTIC_WEIGHT / (RRF_K + candidate.semanticRank))
                : 0d;
        double lexicalComponent = candidate.lexicalRank > 0
                ? (RRF_LEXICAL_WEIGHT / (RRF_K + candidate.lexicalRank))
                : 0d;
        return semanticComponent + lexicalComponent;
    }

    private SemanticCandidatePool semanticHits(ESClientProvider provider, SourceNarration source) throws Exception {
        // Use the source hadith's pre-computed embedding vector directly as the query vector.
        // This avoids the ES inference dependency and ensures query/doc vectors are in the
        // same fine-tuned embedding space.
        List<Float> queryVector = source.semanticVector();
        if (queryVector == null || queryVector.isEmpty()) {
            LOGGER.debug("No semantic_vector found for hadith {}, falling back to lexical only", source.id());
            return new SemanticCandidatePool(new ArrayList<>(), false);
        }
        int poolSize = Math.max(1, SEMANTIC_POOL_SIZE);
        int numCandidates = Math.max(Math.max(poolSize * 2, poolSize), SEMANTIC_NUM_CANDIDATES);
        SearchRequest request = new SearchRequest.Builder()
                .index(ESClientProvider.INDEX)
                .size(poolSize)
                .knn(buildSemanticKnn(queryVector, source.id(), poolSize, numCandidates))
                .build();
        try {
            SearchResponse<Map> response = provider.client().search(request, Map.class);
            return new SemanticCandidatePool(response.hits().hits(), true);
        } catch (Exception ex) {
            LOGGER.warn("Semantic similar-hadith retrieval unavailable; falling back to lexical retrieval only.", ex);
            return new SemanticCandidatePool(new ArrayList<>(), false);
        }
    }

    private KnnSearch buildSemanticKnn(List<Float> queryVector, String sourceId, int poolSize, int numCandidates) {
        KnnSearch.Builder builder = new KnnSearch.Builder()
                .field(SEMANTIC_VECTOR_FIELD)
                .queryVector(queryVector)
                .k(poolSize)
                .numCandidates(numCandidates)
                .filter(f -> f.exists(e -> e.field(SEMANTIC_VECTOR_FIELD)))
                .filter(f -> f.bool(b -> b.mustNot(mn -> mn.ids(ids -> ids.values(sourceId)))));
        if (SEMANTIC_MIN_SIMILARITY > 0f) {
            builder.similarity(SEMANTIC_MIN_SIMILARITY);
        }
        return builder.build();
    }

    private List<Hit<Map>> lexicalHits(ESClientProvider provider, SourceNarration source) throws Exception {
        if (source.matnArabic().isBlank()) {
            return new ArrayList<>();
        }
        String lexicalQuery = source.lexicalQueryText().isBlank() ? source.matnArabic() : source.lexicalQueryText();
        SearchRequest request = new SearchRequest.Builder()
                .index(ESClientProvider.INDEX)
                .size(Math.max(1, LEXICAL_POOL_SIZE))
                .query(q -> q.bool(b -> b
                        .must(m -> m.multiMatch(mm -> mm
                                .query(lexicalQuery)
                                .fields(ARABIC_FIELD + "^1.1", SEMANTIC_TEXT_FIELD + "^1.8")
                                .minimumShouldMatch(LEXICAL_MIN_SHOULD_MATCH)))
                        .should(sh -> {
                            if (source.significantTermsText() == null || source.significantTermsText().isBlank()) {
                                return sh.matchNone(mn -> mn);
                            }
                            return sh.match(m -> m
                                    .field(SIGNIFICANT_TERMS_FIELD)
                                    .query(source.significantTermsText())
                                    .minimumShouldMatch(LEXICAL_SIGNIFICANT_MIN_SHOULD_MATCH)
                                    .boost(LEXICAL_SIGNIFICANT_TERMS_BOOST));
                        })
                        .should(sh -> {
                            if (source.topicTags() == null || source.topicTags().isEmpty()) {
                                return sh.matchNone(mn -> mn);
                            }
                            return sh.terms(t -> t
                                    .field(TOPIC_TAGS_FIELD)
                                    .terms(tv -> tv.value(source.topicTags().stream().map(co.elastic.clients.elasticsearch._types.FieldValue::of).toList()))
                                    .boost(LEXICAL_TOPIC_TAGS_BOOST));
                        })
                        .mustNot(mn -> mn.ids(ids -> ids.values(source.id())))))
                .build();
        SearchResponse<Map> response = provider.client().search(request, Map.class);
        return response.hits().hits();
    }

    private SourceNarration loadSourceNarration(ESClientProvider provider, String hadithId) throws Exception {
        GetResponse<Map> sourceResp = provider.client().get(g -> g.index(ESClientProvider.INDEX).id(hadithId), Map.class);
        if (!sourceResp.found() || sourceResp.source() == null) {
            return null;
        }
        String matnArabic = HadithSemanticText.extractContentOnlyMatn(sourceResp.source(), MAX_MATN_CHARS);
        if (matnArabic.isBlank()) {
            return null;
        }
        String normalizedMatn = HadithSemanticText.normalizeForComparison(matnArabic);
        if (normalizedMatn.isBlank()) {
            return null;
        }
        int distinctiveTokenCount = SimilarHadithRanking.distinctiveTokenCount(normalizedMatn);
        List<String> significantTerms = resolveHighlySignificantTerms(sourceResp.source(), normalizedMatn);
        String significantTermsText = HadithSignificantTerms.joinTerms(significantTerms, SIGNIFICANT_TERMS_QUERY_LIMIT);
        String lexicalQueryText = buildLexicalQueryText(significantTerms, normalizedMatn, LEXICAL_CONTENT_QUERY_TOKEN_LIMIT);
        String englishHint = HadithSemanticText.extractEnglishHint(sourceResp.source());
        List<String> topicTags = readTopicTags(sourceResp.source());
        List<Float> semanticVector = readSemanticVector(sourceResp.source());
        return new SourceNarration(hadithId, matnArabic, normalizedMatn, lexicalQueryText,
                distinctiveTokenCount, significantTerms, significantTermsText, englishHint, topicTags, sourceResp.source(),
                semanticVector);
    }

    private HadithObject mapCandidate(SimilarCandidate candidate, SimilarHadithRanking.CandidateScore score) {
        return mapCandidate(candidate, score.combinedPercent(), score.llmPercent(), score.retrievalPercent());
    }

    private List<HadithObject> mapRetrievalOrderedResults(List<SimilarCandidate> candidates) {
        List<HadithObject> hadithes = new ArrayList<>();
        if (candidates == null || candidates.isEmpty()) {
            return hadithes;
        }
        for (SimilarCandidate candidate : candidates) {
            if (candidate == null || candidate.source() == null) {
                continue;
            }
            hadithes.add(mapCandidate(candidate, candidate.retrievalPercent(), null, candidate.retrievalPercent()));
        }
        return hadithes;
    }

    private List<HadithObject> buildLlmRerankedResults(List<SimilarCandidate> displayCandidates,
            int rerankLimit, Map<String, Double> rankedScores) {
        List<HadithObject> hadithes = new ArrayList<>();
        if (displayCandidates == null || displayCandidates.isEmpty()) {
            return hadithes;
        }
        List<RerankedDisplayCandidate> rerankedHead = rerankDisplayCandidates(
                displayCandidates.subList(0, Math.max(0, Math.min(rerankLimit, displayCandidates.size()))),
                rankedScores);
        for (RerankedDisplayCandidate rankedCandidate : rerankedHead) {
            hadithes.add(mapCandidate(
                    rankedCandidate.candidate(),
                    rankedCandidate.combinedPercent(),
                    rankedCandidate.llmPercent(),
                    rankedCandidate.candidate().retrievalPercent()));
        }
        for (int i = Math.max(0, rerankLimit); i < displayCandidates.size(); i++) {
            SimilarCandidate candidate = displayCandidates.get(i);
            hadithes.add(mapCandidate(candidate, candidate.retrievalPercent(), null, candidate.retrievalPercent()));
        }
        return hadithes;
    }

    private HadithObject mapCandidate(SimilarCandidate candidate, double similarityPercent, Double llmPercent, double retrievalPercent) {
        Map<String, Object> mutableSource = new HashMap<>(candidate.source());
        mutableSource.put("_id", candidate.id());
        mutableSource.put("similarityPercent", SimilarHadithRanking.round(similarityPercent, 2));
        if (llmPercent != null) {
            mutableSource.put("llmSimilarityPercent", SimilarHadithRanking.round(llmPercent, 2));
        }
        mutableSource.put("retrievalSimilarityPercent", SimilarHadithRanking.round(retrievalPercent, 2));
        mutableSource.put("semanticSimilarityPercent", SimilarHadithRanking.round(candidate.semanticPercent(), 2));
        mutableSource.put("syntacticSimilarityPercent", SimilarHadithRanking.round(candidate.syntacticPercent(), 2));
        mutableSource.put("contentOverlapPercent", SimilarHadithRanking.round(candidate.contentOverlapPercent(), 2));
        mutableSource.put("significantTermOverlapPercent", SimilarHadithRanking.round(candidate.significantTermOverlapPercent(), 2));
        mutableSource.put("sharedDistinctiveTokenCount", candidate.sharedDistinctiveTokenCount());
        mutableSource.put("sharedSignificantTermCount", candidate.sharedSignificantTermCount());
        mutableSource.put("sharedTopicTagCount", candidate.sharedTopicTagCount());
        mutableSource.put("sharedSyntacticTokens", candidate.sharedSyntacticTokens());
        mutableSource.put("sharedDistinctiveTokens", candidate.sharedDistinctiveTokens());
        mutableSource.put("sharedSignificantTerms", candidate.sharedSignificantTerms());
        mutableSource.put("topicOverlapPercent", SimilarHadithRanking.round(candidate.topicOverlapPercent(), 2));
        mutableSource.put("sharedTopicTags", candidate.sharedTopicTags());
        mutableSource.put("similarityScore", SimilarHadithRanking.round(similarityPercent / 100.0d, 6));
        HadithDisplaySegmenter.enrich(mutableSource);
        return mapper.convertValue(mutableSource, HadithObject.class);
    }

    static double semanticPercentFromRawScore(Double rawScore) {
        return SimilarHadithRanking.calibrateBoundedScore(rawScore, SEMANTIC_MIN_SIMILARITY, 1.0d, SEMANTIC_SCORE_EXPONENT);
    }

    static double computeRetrievalPercent(double semanticPercent, double syntacticPercent, double contentOverlapPercent, double topicOverlapPercent) {
        double syntacticWeight = Math.max(0d, RETRIEVAL_SYNTACTIC_WEIGHT);
        double contentWeight = Math.max(0d, RETRIEVAL_CONTENT_WEIGHT);
        // Dampen topic weight when content overlap is low — broad tags alone
        // should not carry candidates that share no actual textual content.
        // Quadratic dampening below 20%: at 10% content, topic weight is 25%;
        // at 5% content, topic weight is ~6%.
        double topicWeight = Math.max(0d, RETRIEVAL_TOPIC_WEIGHT);
        double contentClamped = SimilarHadithRanking.clampPercent(contentOverlapPercent);
        if (contentClamped < 20d) {
            double ratio = contentClamped / 20d;
            topicWeight = topicWeight * ratio * ratio;
        }
        double semanticWeight = Math.max(0d, 1.0d - syntacticWeight - contentWeight - topicWeight);
        double totalWeight = semanticWeight + syntacticWeight + contentWeight + topicWeight;
        if (totalWeight <= 0d) {
            return SimilarHadithRanking.round(SimilarHadithRanking.clampPercent(semanticPercent), 2);
        }
        double combined = ((SimilarHadithRanking.clampPercent(semanticPercent) * semanticWeight)
                + (SimilarHadithRanking.clampPercent(syntacticPercent) * syntacticWeight)
                + (SimilarHadithRanking.clampPercent(contentOverlapPercent) * contentWeight)
                + (SimilarHadithRanking.clampPercent(topicOverlapPercent) * topicWeight)) / totalWeight;
        return SimilarHadithRanking.round(SimilarHadithRanking.clampPercent(combined), 2);
    }

    static double computeSupportPercent(double contentOverlapPercent, double significantTermOverlapPercent) {
        if (significantTermOverlapPercent <= 0d) {
            return SimilarHadithRanking.round(SimilarHadithRanking.clampPercent(contentOverlapPercent), 2);
        }
        if (contentOverlapPercent <= 0d) {
            return SimilarHadithRanking.round(SimilarHadithRanking.clampPercent(significantTermOverlapPercent), 2);
        }
        return SimilarHadithRanking.combinePercent(
                significantTermOverlapPercent,
                SIGNIFICANT_TERMS_SUPPORT_WEIGHT,
                contentOverlapPercent,
                1.0d - SIGNIFICANT_TERMS_SUPPORT_WEIGHT);
    }

    static double computeFallbackRetrievalPercent(double lexicalPercent, double syntacticPercent, double contentOverlapPercent) {
        double structuralSupport = SimilarHadithRanking.combinePercent(
                SimilarHadithRanking.clampPercent(syntacticPercent),
                0.45d,
                SimilarHadithRanking.clampPercent(contentOverlapPercent),
                0.55d);
        return SimilarHadithRanking.round(Math.max(
                SimilarHadithRanking.clampPercent(lexicalPercent),
                structuralSupport), 2);
    }

    static List<SimilarHadithRanking.CandidateScore> rerankDisplayOrder(List<SimilarHadithRanking.CandidateInput> candidates,
            Map<String, Double> rankedScores, double llmWeight) {
        List<SimilarHadithRanking.CandidateScore> result = new ArrayList<>();
        if (candidates == null || candidates.isEmpty()) {
            return result;
        }
        Map<String, Double> safeScores = rankedScores == null ? Map.of() : rankedScores;
        Set<String> seen = new HashSet<>();
        for (SimilarHadithRanking.CandidateInput input : candidates) {
            if (input == null || input.id() == null || input.id().isBlank()) {
                continue;
            }
            if (!seen.add(input.id())) {
                continue;
            }
            double llmPercent = SimilarHadithRanking.round(
                    SimilarHadithRanking.clampPercent(safeScores.getOrDefault(input.id(), input.retrievalPercent())),
                    2);
            double retrievalPercent = SimilarHadithRanking.round(
                    SimilarHadithRanking.clampPercent(input.retrievalPercent()),
                    2);
            double combined = SimilarHadithRanking.combinePercent(
                    llmPercent,
                    llmWeight,
                    retrievalPercent,
                    1.0d - llmWeight);
            result.add(new SimilarHadithRanking.CandidateScore(
                    input.id(),
                    llmPercent,
                    retrievalPercent,
                    combined));
        }
        result.sort(Comparator
                .comparingDouble(SimilarHadithRanking.CandidateScore::combinedPercent)
                .thenComparingDouble(SimilarHadithRanking.CandidateScore::llmPercent)
                .thenComparingDouble(SimilarHadithRanking.CandidateScore::retrievalPercent)
                .reversed());
        return result;
    }

    static String buildLexicalQueryText(List<String> significantTerms, String normalizedMatn, int maxTokens) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (significantTerms != null) {
            for (String term : significantTerms) {
                ordered.addAll(SimilarHadithRanking.distinctiveTokens(term));
            }
        }
        ordered.addAll(SimilarHadithRanking.distinctiveTokens(normalizedMatn));
        if (ordered.isEmpty()) {
            return "";
        }
        List<String> tokens = new ArrayList<>(ordered);
        int limit = maxTokens <= 0 ? tokens.size() : Math.min(tokens.size(), maxTokens);
        return String.join(" ", tokens.subList(0, limit));
    }

    static String candidateSupportText(List<String> candidateSignificantTerms, String normalizedMatn) {
        String candidateTerms = HadithSignificantTerms.joinTerms(candidateSignificantTerms, SIGNIFICANT_TERMS_QUERY_LIMIT);
        if (candidateTerms.isBlank()) {
            return normalizedMatn == null ? "" : normalizedMatn;
        }
        if (normalizedMatn == null || normalizedMatn.isBlank()) {
            return candidateTerms;
        }
        return candidateTerms + " " + normalizedMatn;
    }

    static boolean isEligibleCandidate(boolean hasSemanticHit) {
        return hasSemanticHit;
    }

    static boolean isEligibleCandidate(boolean hasSemanticHit, boolean semanticSearchAvailable) {
        return hasSemanticHit || !semanticSearchAvailable;
    }

    static boolean isEligibleCandidate(boolean hasSemanticHit, int sourceDistinctiveTokenCount,
            int sharedDistinctiveTokenCount, double contentOverlapPercent, double syntacticPercent) {
        if (!hasSemanticHit) {
            return false;
        }
        if (sourceDistinctiveTokenCount < DISTINCTIVE_TOKEN_GATE_MIN) {
            return true;
        }
        if (sharedDistinctiveTokenCount <= 0) {
            return false;
        }
        if (sourceDistinctiveTokenCount >= DISTINCTIVE_TOKEN_HIGH_CONTEXT_MIN
                && contentOverlapPercent < DISTINCTIVE_CONTENT_TRACE_PERCENT
                && syntacticPercent < DISTINCTIVE_SYNTACTIC_HIGH_CONTEXT_MIN_PERCENT) {
            return false;
        }
        if (contentOverlapPercent < DISTINCTIVE_CONTENT_MIN_PERCENT
                && syntacticPercent < DISTINCTIVE_SYNTACTIC_MIN_PERCENT) {
            return false;
        }
        return true;
    }

    static boolean isEligibleCandidate(boolean hasSemanticHit, boolean semanticSearchAvailable,
            int sourceDistinctiveTokenCount, int sharedDistinctiveTokenCount,
            double contentOverlapPercent, double syntacticPercent) {
        if (!isEligibleCandidate(hasSemanticHit, semanticSearchAvailable)) {
            return false;
        }
        if (sourceDistinctiveTokenCount < DISTINCTIVE_TOKEN_GATE_MIN) {
            return true;
        }
        if (sharedDistinctiveTokenCount <= 0) {
            return false;
        }
        if (sourceDistinctiveTokenCount >= DISTINCTIVE_TOKEN_HIGH_CONTEXT_MIN
                && contentOverlapPercent < DISTINCTIVE_CONTENT_TRACE_PERCENT
                && syntacticPercent < DISTINCTIVE_SYNTACTIC_HIGH_CONTEXT_MIN_PERCENT) {
            return false;
        }
        if (contentOverlapPercent < DISTINCTIVE_CONTENT_MIN_PERCENT
                && syntacticPercent < DISTINCTIVE_SYNTACTIC_MIN_PERCENT) {
            return false;
        }
        return true;
    }

    private static boolean isEligibleCandidate(MutableCandidate candidate, SourceNarration source, boolean semanticSearchAvailable) {
        if (candidate == null || source == null) {
            return false;
        }
        // Content overlap floor — candidates sharing no actual text content are
        // likely noise from broad tag matching or embedding false positives.
        // Skip this check if syntactic similarity is high (near-duplicate variants
        // may have low token overlap but high character similarity).
        if (candidate.contentOverlapPercent < MIN_CONTENT_OVERLAP_PERCENT
                && candidate.syntacticPercent < DISTINCTIVE_SYNTACTIC_MIN_PERCENT
                && candidate.sharedTopicTagCount < TOPIC_TAG_RESCUE_MIN) {
            return false;
        }
        // Even semantic hits must show SOME textual evidence — the embedding can
        // produce false positives from shared broad vocabulary. Candidates with
        // near-zero content overlap and no shared distinctive tokens are noise
        // regardless of how they were retrieved.
        if (candidate.semanticRank > 0
                && candidate.contentOverlapPercent < MIN_CONTENT_OVERLAP_PERCENT
                && candidate.sharedDistinctiveTokenCount <= 1) {
            return false;
        }
        // Semantic hits that pass the content/distinctive gate are trusted
        if (candidate.semanticRank > 0) {
            return true;
        }
        // No semantic hit — require some shared signal to avoid noise
        if (candidate.sharedTopicTagCount >= TOPIC_TAG_RESCUE_MIN) {
            return true;
        }
        if (source.significantTerms() != null && !source.significantTerms().isEmpty()
                && candidate.sharedSignificantTermCount <= 0
                && candidate.sharedDistinctiveTokenCount <= 1
                && candidate.syntacticPercent < DISTINCTIVE_SYNTACTIC_MIN_PERCENT) {
            return false;
        }
        return isEligibleCandidate(
                candidate.semanticRank > 0,
                semanticSearchAvailable,
                source.distinctiveTokenCount(),
                candidate.sharedDistinctiveTokenCount,
                candidate.supportPercent,
                candidate.syntacticPercent);
    }

    private List<String> resolveHighlySignificantTerms(Map source, String normalizedMatn) {
        List<String> storedTerms = HadithSignificantTerms.readTerms(source, SIGNIFICANT_TERMS_QUERY_LIMIT);
        List<String> fallbackTerms = normalizedMatn == null || normalizedMatn.isBlank()
                ? List.of()
                : HadithSignificantTerms.fallbackTerms(normalizedMatn, SIGNIFICANT_TERMS_QUERY_LIMIT);
        if (!storedTerms.isEmpty()) {
            List<String> merged = mergeTerms(storedTerms, fallbackTerms, SIGNIFICANT_TERMS_QUERY_LIMIT);
            if (!merged.isEmpty()) {
                return merged;
            }
        }
        if (normalizedMatn == null || normalizedMatn.isBlank()) {
            return List.of();
        }
        List<String> cached = significantTermsCache.getIfPresent(normalizedMatn);
        if (cached != null) {
            return cached;
        }
        if (fallbackTerms.size() < DISTINCTIVE_TOKEN_GATE_MIN) {
            significantTermsCache.put(normalizedMatn, List.of());
            return List.of();
        }
        significantTermsCache.put(normalizedMatn, fallbackTerms);
        return fallbackTerms;
    }

    private static List<String> mergeTerms(List<String> primary, List<String> secondary, int limit) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (primary != null) {
            merged.addAll(primary);
        }
        if (secondary != null) {
            merged.addAll(secondary);
        }
        if (merged.isEmpty()) {
            return List.of();
        }
        List<String> ordered = new ArrayList<>(merged);
        if (limit > 0 && ordered.size() > limit) {
            return new ArrayList<>(ordered.subList(0, limit));
        }
        return ordered;
    }

    private static List<SimilarCandidate> prepareDisplayCandidates(List<SimilarCandidate> candidates, double minPercent) {
        List<SimilarCandidate> displayCandidates = new ArrayList<>();
        if (candidates == null || candidates.isEmpty()) {
            return displayCandidates;
        }
        List<String> seenMatns = new ArrayList<>();
        for (SimilarCandidate candidate : candidates) {
            if (candidate == null || candidate.source() == null) {
                continue;
            }
            if (!meetsDisplayThreshold(candidate.retrievalPercent(), minPercent)) {
                continue;
            }
            String dedupKey = candidate.normalizedMatn();
            if (!dedupKey.isBlank()) {
                boolean nearDuplicate = false;
                for (String seenMatn : seenMatns) {
                    if (SimilarHadithRanking.syntacticSimilarityPercent(dedupKey, seenMatn) >= NEAR_DUPLICATE_SYNTACTIC_PERCENT) {
                        nearDuplicate = true;
                        break;
                    }
                }
                if (nearDuplicate) {
                    continue;
                }
                seenMatns.add(dedupKey);
            }
            displayCandidates.add(candidate);
        }
        return displayCandidates;
    }

    private static List<RerankedDisplayCandidate> rerankDisplayCandidates(List<SimilarCandidate> candidates,
            Map<String, Double> rankedScores) {
        List<RerankedDisplayCandidate> reranked = new ArrayList<>();
        if (candidates == null || candidates.isEmpty()) {
            return reranked;
        }
        Map<String, Double> safeScores = rankedScores == null ? Map.of() : rankedScores;
        List<SimilarHadithRanking.CandidateInput> scoreInputs = new ArrayList<>();
        Map<String, SimilarCandidate> lookup = new HashMap<>();
        for (SimilarCandidate candidate : candidates) {
            if (candidate == null || candidate.id() == null || candidate.id().isBlank()) {
                continue;
            }
            lookup.put(candidate.id(), candidate);
            scoreInputs.add(new SimilarHadithRanking.CandidateInput(
                    candidate.id(),
                    candidate.retrievalPercent(),
                    candidate.retrievalPercent()));
        }
        for (SimilarHadithRanking.CandidateScore score : rerankDisplayOrder(scoreInputs, safeScores, FINAL_LLM_WEIGHT)) {
            SimilarCandidate candidate = lookup.get(score.id());
            if (candidate == null) {
                continue;
            }
            Double llmPercent = safeScores.containsKey(score.id()) ? score.llmPercent() : null;
            reranked.add(new RerankedDisplayCandidate(candidate, llmPercent, score.combinedPercent()));
        }
        return reranked;
    }

    static boolean meetsDisplayThreshold(double retrievalPercent, double minPercent) {
        if (minPercent <= 0d) {
            return true;
        }
        return SimilarHadithRanking.clampPercent(retrievalPercent) >= SimilarHadithRanking.clampPercent(minPercent);
    }

    private double effectiveRetrievalOnlyMinPercent() {
        if (retrievalOnlyMinPercent >= 0d) {
            return SimilarHadithRanking.clampPercent(retrievalOnlyMinPercent);
        }
        return FINAL_MIN_PERCENT;
    }

    private HadithObjectCollection paginate(List<HadithObject> items, int page, int pageSize) {
        List<HadithObject> safeItems = items == null ? new ArrayList<>() : items;
        long totalHits = safeItems.size();
        int fromIndex = page * pageSize;
        if (fromIndex >= safeItems.size()) {
            return new HadithObjectCollection(new ArrayList<>(), totalHits);
        }
        int toIndex = Math.min(fromIndex + pageSize, safeItems.size());
        return new HadithObjectCollection(new ArrayList<>(safeItems.subList(fromIndex, toIndex)), totalHits);
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

    private static List<String> readTopicTags(Map source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        Object raw = source.get(TOPIC_TAGS_FIELD);
        if (!(raw instanceof List<?> rawList) || rawList.isEmpty()) {
            return List.of();
        }
        List<String> tags = rawList.stream()
                .filter(item -> item != null && !item.toString().trim().isEmpty())
                .map(item -> item.toString().trim())
                .distinct()
                .collect(Collectors.toList());
        return tags.isEmpty() ? List.of() : tags;
    }

    @SuppressWarnings("unchecked")
    private static List<Float> readSemanticVector(Map source) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        Object raw = source.get(SEMANTIC_VECTOR_FIELD);
        if (raw instanceof List<?> rawList && !rawList.isEmpty()) {
            // ES stores dense_vector as List<Number>; convert to Float
            List<Float> vector = new ArrayList<>(rawList.size());
            for (Object item : rawList) {
                if (item instanceof Number num) {
                    vector.add(num.floatValue());
                }
            }
            return vector.isEmpty() ? null : vector;
        }
        return null;
    }

    private record SourceNarration(String id, String matnArabic, String normalizedMatn,
                                   String lexicalQueryText, int distinctiveTokenCount,
                                   List<String> significantTerms, String significantTermsText,
                                   String englishHint, List<String> topicTags, Map source,
                                   List<Float> semanticVector) {
    }

    private record SimilarCandidate(String id, Map source, double semanticPercent, double lexicalPercent,
                                    double syntacticPercent, double contentOverlapPercent,
                                    double significantTermOverlapPercent, int sharedDistinctiveTokenCount,
                                    int sharedSignificantTermCount, List<String> sharedSyntacticTokens,
                                    List<String> sharedDistinctiveTokens, List<String> sharedSignificantTerms,
                                    double topicOverlapPercent, int sharedTopicTagCount, List<String> sharedTopicTags,
                                    double retrievalPercent,
                                    double fusedScore, int semanticRank, int lexicalRank,
                                    String matnArabic, String normalizedMatn) {
    }

    private record SemanticCandidatePool(List<Hit<Map>> hits, boolean available) {
    }

    private record RerankedDisplayCandidate(SimilarCandidate candidate, Double llmPercent, double combinedPercent) {
    }

    private static final class MutableCandidate {
        private final String id;
        private Map source;
        private double semanticRawScore;
        private double lexicalRawScore;
        private double semanticPercent;
        private double lexicalPercent;
        private double syntacticPercent;
        private double contentOverlapPercent;
        private double significantTermOverlapPercent;
        private double topicOverlapPercent;
        private double supportPercent;
        private double retrievalPercent;
        private double fusedScore;
        private int semanticRank;
        private int lexicalRank;
        private int sharedDistinctiveTokenCount;
        private int sharedSignificantTermCount;
        private int sharedTopicTagCount;
        private List<String> sharedSyntacticTokens;
        private List<String> sharedDistinctiveTokens;
        private List<String> sharedSignificantTerms;
        private List<String> sharedTopicTags;
        private String matnArabic;
        private String normalizedMatn;

        private MutableCandidate(String id, Map source) {
            this.id = id;
            this.source = source;
            this.semanticRawScore = 0d;
            this.lexicalRawScore = 0d;
            this.semanticPercent = 0d;
            this.lexicalPercent = 0d;
            this.syntacticPercent = 0d;
            this.contentOverlapPercent = 0d;
            this.significantTermOverlapPercent = 0d;
            this.topicOverlapPercent = 0d;
            this.supportPercent = 0d;
            this.retrievalPercent = 0d;
            this.fusedScore = 0d;
            this.semanticRank = -1;
            this.lexicalRank = -1;
            this.sharedDistinctiveTokenCount = 0;
            this.sharedSignificantTermCount = 0;
            this.sharedTopicTagCount = 0;
            this.sharedSyntacticTokens = List.of();
            this.sharedDistinctiveTokens = List.of();
            this.sharedSignificantTerms = List.of();
            this.sharedTopicTags = List.of();
            this.matnArabic = "";
            this.normalizedMatn = "";
        }
    }
}
