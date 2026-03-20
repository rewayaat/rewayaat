package com.rewayaat.service;

import com.rewayaat.core.SimilarHadithRanking;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimilarHadithServiceScoringTest {

    @Test
    void calibratedSemanticScoreSeparatesWeakAndStrongMatches() {
        double weak = SimilarHadithService.semanticPercentFromRawScore(0.95d);
        double strong = SimilarHadithService.semanticPercentFromRawScore(0.985d);

        assertTrue(weak < 80d);
        assertTrue(strong > 85d);
        assertTrue(strong > weak + 8d);
    }

    @Test
    void retrievalPercentNoLongerLooksHighForWeakSemanticAndLowSyntacticSignals() {
        double weakSemantic = SimilarHadithService.semanticPercentFromRawScore(0.95d);
        double weakRetrieval = SimilarHadithService.computeRetrievalPercent(weakSemantic, 8d, 0d, 0d);
        double strongRetrieval = SimilarHadithService.computeRetrievalPercent(
                SimilarHadithService.semanticPercentFromRawScore(0.985d),
                52d,
                70d,
                100d);

        assertTrue(weakRetrieval < 70d);
        assertTrue(strongRetrieval >= 80d);
    }

    @Test
    void topicOverlapProvidesModestRetrievalLiftInsteadOfDominating() {
        double withoutTopic = SimilarHadithService.computeRetrievalPercent(72d, 30d, 25d, 0d);
        double withTopic = SimilarHadithService.computeRetrievalPercent(72d, 30d, 25d, 100d);

        assertTrue(withTopic > withoutTopic);
        assertTrue(withTopic < withoutTopic + 12d);
    }

    @Test
    void fallbackRetrievalPercentKeepsStrongLexicalStructuralMatchesVisible() {
        double fallback = SimilarHadithService.computeFallbackRetrievalPercent(42d, 100d, 100d);
        assertTrue(fallback >= 90d);
    }

    @Test
    void lexicalOnlyCandidatesAreNotEligibleForFinalRanking() {
        assertFalse(SimilarHadithService.isEligibleCandidate(false));
        assertTrue(SimilarHadithService.isEligibleCandidate(true));
        assertTrue(SimilarHadithService.isEligibleCandidate(false, false));
        assertFalse(SimilarHadithService.isEligibleCandidate(false, true));
    }

    @Test
    void candidatesNeedDistinctiveSupportWhenSourceHasMeaningfulContent() {
        assertFalse(SimilarHadithService.isEligibleCandidate(true, 5, 0, 0d, 22d));
        assertFalse(SimilarHadithService.isEligibleCandidate(true, 10, 1, 5d, 15d));
        assertTrue(SimilarHadithService.isEligibleCandidate(true, 5, 1, 20d, 10d));
        assertTrue(SimilarHadithService.isEligibleCandidate(true, 10, 3, 18d, 18d));
    }

    @Test
    void significantTermSupportBoostsSupportedMatches() {
        double unsupported = SimilarHadithService.computeSupportPercent(12d, 0d);
        double supported = SimilarHadithService.computeSupportPercent(12d, 100d);

        assertTrue(unsupported == 12d);
        assertTrue(supported > 60d);
    }

    @Test
    void lexicalQueryStartsWithSignificantTermsBeforeFillingFromMatn() {
        String query = SimilarHadithService.buildLexicalQueryText(
                List.of("نمره", "جبرييل", "رمضان"),
                "فرض الله عليك سبعه عشر ركعه في اليوم والليله",
                6);

        assertEquals("نمره جبرييل رمضان فرض سبعه عشر", query);
    }

    @Test
    void candidateSupportTextKeepsStoredTermsAndFullMatnContext() {
        String supportText = SimilarHadithService.candidateSupportText(
                List.of("نمره", "جبرييل"),
                "فرض الله عليك سبعه عشر ركعه");

        assertEquals("نمره جبرييل فرض الله عليك سبعه عشر ركعه", supportText);
    }

    @Test
    void rerankDisplayOrderKeepsCandidatesWithoutLlmScores() {
        List<SimilarHadithRanking.CandidateScore> reranked = SimilarHadithService.rerankDisplayOrder(
                List.of(
                        new SimilarHadithRanking.CandidateInput("h1", 0d, 72d),
                        new SimilarHadithRanking.CandidateInput("h2", 0d, 68d),
                        new SimilarHadithRanking.CandidateInput("h3", 0d, 61d)),
                java.util.Map.of("h2", 95d, "h1", 40d),
                0.88d);

        assertEquals(3, reranked.size());
        assertEquals(List.of("h2", "h3", "h1"), reranked.stream().map(SimilarHadithRanking.CandidateScore::id).toList());
        assertEquals(61d, reranked.get(1).llmPercent());
        assertEquals(40d, reranked.get(2).llmPercent());
    }

    @Test
    void displayThresholdRequiresSeventyPercentOrHigher() {
        assertFalse(SimilarHadithService.meetsDisplayThreshold(69.99d, 70d));
        assertTrue(SimilarHadithService.meetsDisplayThreshold(70d, 70d));
        assertTrue(SimilarHadithService.meetsDisplayThreshold(88d, 70d));
    }
}
