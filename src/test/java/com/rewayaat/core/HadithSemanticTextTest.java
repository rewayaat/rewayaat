package com.rewayaat.core;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HadithSemanticTextTest {

    @Test
    void extractMatnPrefersChainlessContentAndAlignsQueryAndPassageBodies() {
        Map<String, Object> source = new HashMap<>();
        source.put("arabic",
                "حُمَيْدُ بْنُ زِيَادٍ عَنِ الْحَسَنِ بْنِ مُحَمَّدٍ الْكِنْدِيِّ عَنْ أَبِي عَبْدِ اللَّهِ عليه السلام قَالَ "
                        + "كَانَ عَلَى عَهْدِ رَسُولِ اللَّهِ رَجُلٌ يُقَالُ لَهُ ذُو النَّمِرَةِ");
        source.put("english",
                "Humayd ibn Ziyad narrated from al-Hasan ibn Muhammad that Abu Abdillah said there was a man called Dhu al-Namirah.");

        String matn = HadithSemanticText.extractMatn(source, 1200);
        String englishHint = HadithSemanticText.extractEnglishHint(source);
        String query = HadithSemanticText.toQueryText(matn, englishHint, "ذو النمرة", 1200);
        String passage = HadithSemanticText.toPassageText(matn, englishHint, "ذو النمرة", 1200);

        assertTrue(matn.startsWith("كَانَ عَلَى عَهْدِ رَسُولِ اللَّهِ"));
        assertFalse(matn.contains("حُمَيْدُ بْنُ زِيَاد"));
        assertEquals(query.substring("query: ".length()), passage.substring("passage: ".length()));
        assertTrue(query.contains("en_hint:"));
        assertTrue(query.contains("key_terms: ذو النمرة"));
    }

    @Test
    void extractMatnKeepsEarlyNarrativeContextForLongArabicHadith() {
        Map<String, Object> source = new HashMap<>();
        source.put("arabic",
                "حُمَيْدُ بْنُ زِيَادٍ عَنِ الْحَسَنِ بْنِ مُحَمَّدٍ الْكِنْدِيِّ عَنْ أَحْمَدَ بْنِ الْحَسَنِ الْمِيثَمِيِّ "
                        + "عَنْ أَبَانِ بْنِ عُثْمَانَ عَنْ رَجُلٍ عَنْ أَبِي عَبْدِ اللَّهِ (عليه السلام) قَالَ "
                        + "كَانَ عَلَى عَهْدِ رَسُولِ اللَّهِ (صلى الله عليه وآله) رَجُلٌ يُقَالُ لَهُ ذُو النَّمِرَةِ "
                        + "وَ كَانَ مِنْ أَقْبَحِ النَّاسِ وَ إِنَّمَا سُمِّيَ ذُو النَّمِرَةِ مِنْ قُبْحِهِ فَأَتَى النَّبِيَّ "
                        + "(صلى الله عليه وآله) فَقَالَ يَا رَسُولَ اللَّهِ أَخْبِرْنِي مَا فَرَضَ اللَّهُ عَزَّ وَ جَلَّ عَلَيَّ "
                        + "فَقَالَ لَهُ رَسُولُ اللَّهِ (صلى الله عليه وآله) فَرَضَ اللَّهُ عَلَيْكَ سَبْعَةَ عَشَرَ رَكْعَةً");

        String matn = HadithSemanticText.extractMatn(source, 1200);

        assertTrue(matn.startsWith("كَانَ عَلَى عَهْدِ رَسُولِ اللَّهِ"), matn);
        assertTrue(matn.contains("ذُو النَّمِرَةِ"), matn);
        assertFalse(matn.startsWith("لَهُ رَسُولُ اللَّهِ"), matn);
    }

    @Test
    void extractContentOnlyMatnDoesNotFallBackToRawChainText() {
        Map<String, Object> source = new HashMap<>();
        source.put("arabic",
                "محمد بن يحيى عن احمد بن محمد عن الحسن بن محبوب عن علي بن رئاب عن ابي عبيدة");

        String matn = HadithSemanticText.extractContentOnlyMatn(source, 1200);

        assertEquals("", matn);
    }

    @Test
    void extractContentOnlyMatnStillKeepsChainlessBodyWhenPresent() {
        Map<String, Object> source = new HashMap<>();
        source.put("arabic",
                "محمد بن يحيى عن احمد بن محمد عن الحسن بن محبوب قال قال ابو عبد الله عليه السلام الماء طاهر لا ينجسه شيء");

        String matn = HadithSemanticText.extractContentOnlyMatn(source, 1200);

        assertTrue(matn.startsWith("قال ابو عبد الله"), matn);
        assertFalse(matn.contains("محمد بن يحيى"), matn);
    }

    @Test
    void comparisonNormalizationCanonicalizesMatnForDedupAndSyntacticChecks() {
        String comparison = HadithSemanticText.normalizeForComparison("الإيمانُ معرفةٌ بالقلبِ");

        assertEquals("الايمان معرفه بالقلب", comparison);
    }

    @Test
    void extractEnglishHintTrimsNarrationScaffoldingAndCapsLength() {
        Map<String, Object> source = new HashMap<>();
        source.put("english",
                "A number of our people narrated from Ahmad ibn Muhammad who has said the following: The obligatory prayers are seventeen.");

        String hint = HadithSemanticText.extractEnglishHint(source, 40);

        assertEquals("The obligatory prayers are seventeen.", hint);
    }
}
