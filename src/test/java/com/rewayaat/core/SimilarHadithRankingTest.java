package com.rewayaat.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimilarHadithRankingTest {

    @Test
    void filterAndSortUsesCombinedScoreAndDeduplicates() {
        List<SimilarHadithRanking.CandidateInput> inputs = List.of(
                new SimilarHadithRanking.CandidateInput("A", 92d, 61d),
                new SimilarHadithRanking.CandidateInput("B", 88d, 90d),
                new SimilarHadithRanking.CandidateInput("A", 97d, 10d),
                new SimilarHadithRanking.CandidateInput("C", 55d, 40d));

        List<SimilarHadithRanking.CandidateScore> ranked = SimilarHadithRanking.filterAndSort(inputs, 80d, 0.88d);

        assertEquals(2, ranked.size());
        assertEquals("A", ranked.get(0).id());
        assertEquals("B", ranked.get(1).id());
        assertTrue(ranked.get(0).combinedPercent() >= ranked.get(1).combinedPercent());
    }

    @Test
    void syntacticSimilarityDistinguishesCloseAndFarArabicText() {
        String source = "ما من أحد يموت من المؤمنين أحب إلى إبليس من موت فقيه";
        String close = "ما من مؤمن يموت أحب إلى إبليس من موت فقيه";
        String far = "نعم البقلة السلق";

        double closeScore = SimilarHadithRanking.syntacticSimilarityPercent(source, close);
        double farScore = SimilarHadithRanking.syntacticSimilarityPercent(source, far);

        assertTrue(closeScore > 45d);
        assertTrue(farScore < 35d);
    }

    @Test
    void calibrateBoundedScoreCompressesWeakMatchesAndRewardsHighOnes() {
        double weak = SimilarHadithRanking.calibrateBoundedScore(0.95d, 0.78d, 1.0d, 1.5d);
        double strong = SimilarHadithRanking.calibrateBoundedScore(0.985d, 0.78d, 1.0d, 1.5d);

        assertTrue(weak < 70d);
        assertTrue(strong > 85d);
    }

    @Test
    void distinctiveTokenOverlapIgnoresGenericPropheticScaffolding() {
        String source = "قال رسول الله الرؤيا لا تقص إلا على مؤمن خلا من الحسد والبغي";
        String generic = "قال رسول الله خير العبادة قول لا إله إلا الله";
        String close = "لا تقص الرؤيا إلا على مؤمن لا حسد فيه ولا بغي";

        assertEquals(0, SimilarHadithRanking.sharedDistinctiveTokenCount(source, generic));
        assertTrue(SimilarHadithRanking.distinctiveTokenRecallPercent(source, generic) < 5d);
        assertTrue(SimilarHadithRanking.sharedDistinctiveTokenCount(source, close) >= 4);
        assertTrue(SimilarHadithRanking.distinctiveTokenRecallPercent(source, close) >= 75d);
    }

    @Test
    void distinctiveTokenOverlapStaysLowForNarrativeOnlyMatches() {
        String source = "كان على عهد رسول الله رجل يقال له ذو النمرة وكان من أقبح الناس "
                + "فأتى النبي فقال يا رسول الله أخبرني ما فرض الله علي";
        String genericNarrative = "كان رجل بالمدينة يدخل مسجد الرسول فقال اللهم آنس وحشتي "
                + "وارزقني جليسا صالحا فقال له من أنت";

        assertTrue(SimilarHadithRanking.distinctiveTokenCount(source) >= 6);
        assertTrue(SimilarHadithRanking.sharedDistinctiveTokenCount(source, genericNarrative) <= 1);
        assertTrue(SimilarHadithRanking.distinctiveTokenRecallPercent(source, genericNarrative) < 10d);
    }

    @Test
    void significantTermRecallRewardsCandidatesThatShareSalientTerms() {
        List<String> significantTerms = List.of("الرؤيا", "الحسد", "البغي");
        String close = "لا تقص الرؤيا إلا على مؤمن خلا من الحسد والبغي";
        String generic = "قال رسول الله خير العبادة قول لا إله إلا الله";

        assertEquals(3, SimilarHadithRanking.sharedTermCount(significantTerms, close));
        assertTrue(SimilarHadithRanking.termRecallPercent(significantTerms, close) >= 95d);
        assertEquals(0, SimilarHadithRanking.sharedTermCount(significantTerms, generic));
        assertTrue(SimilarHadithRanking.termRecallPercent(significantTerms, generic) == 0d);
    }

    @Test
    void bidirectionalTokenOverlapPenalizesTangentialLongMatches() {
        String source = "الصلاه سبع عشر ركعه في اليوم والليله";
        String target = "الصلاه سبع عشر ركعه في اليوم والليله والزكاه والصوم والحج والطلاق والبيع";

        double forwardOnly = SimilarHadithRanking.distinctiveTokenRecallPercent(source, target);
        double bidirectional = SimilarHadithRanking.bidirectionalTokenOverlapPercent(source, target);

        assertTrue(forwardOnly > bidirectional);
        assertTrue(bidirectional < 80d);
    }

    @Test
    void topicTagOverlapUsesJaccardAndPreservesSharedOrder() {
        List<String> sourceTags = List.of("prayer", "obligation", "purity");
        List<String> targetTags = List.of("prayer", "charity", "purity");

        assertEquals(2, SimilarHadithRanking.sharedTopicTagCount(sourceTags, targetTags));
        assertEquals(List.of("prayer", "purity"), SimilarHadithRanking.sharedTopicTags(sourceTags, targetTags));
        assertEquals(50d, SimilarHadithRanking.topicTagJaccardPercent(sourceTags, targetTags));
    }

    @Test
    void sharedTokenListsExposeSurfaceOverlapForUiHighlighting() {
        String source = "قال ابو عبد الله في الماء الطاهر لا ينجسه شيء";
        String target = "الماء الطاهر لا يفسده شيء اذا لم يتغير";
        List<String> significantTerms = List.of("الماء", "الطاهر", "النجاسه");

        assertEquals(List.of("الماء", "الطاهر", "لا", "شيء"),
                SimilarHadithRanking.sharedSyntacticTokens(source, target));
        assertEquals(List.of("ماء", "طاهر"),
                SimilarHadithRanking.sharedDistinctiveTokens(source, target));
        assertEquals(List.of("ماء", "طاهر"),
                SimilarHadithRanking.sharedTerms(significantTerms, target));
    }
}
