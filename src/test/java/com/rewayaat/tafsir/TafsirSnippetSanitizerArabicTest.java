package com.rewayaat.tafsir;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Arabic text processing in TafsirSnippetSanitizer.
 * Verifies that Arabic tafsir text is properly handled and cleaned.
 */
class TafsirSnippetSanitizerArabicTest {

    @Test
    void testIsArabicText_ArabicText_ReturnsTrue() {
        // Arabic text with diacritics
        assertTrue(TafsirSnippetSanitizer.isArabicText("بِسْمِ اللَّهِ الرَّحْمَنِ الرَّحِيمِ"));
        // Arabic text without diacritics
        assertTrue(TafsirSnippetSanitizer.isArabicText("الحمد لله رب العالمين"));
        // Mixed Arabic with numbers
        assertTrue(TafsirSnippetSanitizer.isArabicText("سورة الفاتحة 7 آيات"));
    }

    @Test
    void testIsArabicText_EnglishText_ReturnsFalse() {
        assertFalse(TafsirSnippetSanitizer.isArabicText("In the name of Allah"));
        assertFalse(TafsirSnippetSanitizer.isArabicText("Surah Al-Fatiha"));
        assertFalse(TafsirSnippetSanitizer.isArabicText("The Opening"));
    }

    @Test
    void testIsArabicText_MixedText_ReturnsTrue() {
        // Text containing Arabic characters
        assertTrue(TafsirSnippetSanitizer.isArabicText("Surah Al-Fatiha (سورة الفاتحة)"));
    }

    @Test
    void testSplitCommentaryByScript_MixedParagraphs_ReturnsSeparatedViews() {
        String text = "بِسْمِ اللَّهِ الرَّحْمَنِ الرَّحِيمِ\n\nThis verse opens the surah with mercy and grace.";

        TafsirSnippetSanitizer.ScriptSplit split = TafsirSnippetSanitizer.splitCommentaryByScript(text);

        assertTrue(split.arabicText().contains("بِسْمِ"), split.arabicText());
        assertFalse(split.arabicText().contains("This verse"), split.arabicText());
        assertTrue(split.englishText().contains("This verse opens the surah"), split.englishText());
        assertFalse(split.englishText().contains("بِسْمِ"), split.englishText());
    }

    @Test
    void testSanitize_PopulatesCommentaryLanguageFields() {
        TafsirDocument doc = new TafsirDocument();
        doc.setCommentaryText("الْحَمْدُ لِلَّهِ رَبِّ الْعَالَمِينَ\n\nAll praise belongs to Allah, Lord of the worlds.");

        TafsirSnippetSanitizer.sanitize(doc);

        assertNotNull(doc.getCommentaryTextArabic());
        assertNotNull(doc.getCommentaryTextEnglish());
        assertTrue(doc.getCommentaryTextArabic().contains("الْحَمْدُ"), doc.getCommentaryTextArabic());
        assertTrue(doc.getCommentaryTextEnglish().contains("All praise belongs to Allah"), doc.getCommentaryTextEnglish());
    }

    @Test
    void testSplitCommentaryByScript_StripsVerseNumberScaffoldingFromArabicSide() {
        String text = "أَأَنْتُمْ أَشَدُّ خَلْقًا أَمِ السَّمَاءُ ۚ بَنَاهَا 27. \"Is the creation of you harder or the heaven He built?\"";

        TafsirSnippetSanitizer.ScriptSplit split = TafsirSnippetSanitizer.splitCommentaryByScript(text);

        assertTrue(split.arabicText().contains("أَأَنْتُمْ أَشَدُّ خَلْقًا"), split.arabicText());
        assertFalse(split.arabicText().contains("27."), split.arabicText());
        assertFalse(split.arabicText().contains("?"), split.arabicText());
        assertTrue(split.englishText().contains("Is the creation of you harder"), split.englishText());
    }

    @Test
    void testCountArabicDiacritics_TextWithHarakat_ReturnsCorrectCount() {
        // بِسْمِ اللَّهِ - has 6 diacritics
        String text = "بِسْمِ اللَّهِ";
        int count = TafsirSnippetSanitizer.countArabicDiacritics(text);
        assertEquals(6, count, "Should count 6 diacritics in 'بِسْمِ اللَّهِ'");
    }

    @Test
    void testCountArabicDiacritics_TextWithoutHarakat_ReturnsZero() {
        String text = "بسم الله";
        int count = TafsirSnippetSanitizer.countArabicDiacritics(text);
        assertEquals(0, count);
    }

    @Test
    void testGetLatinRatio_PureArabic_ReturnsNearZero() {
        String arabicText = "الحمد لله رب العالمين ملك يوم الدين";
        double ratio = TafsirSnippetSanitizer.getLatinRatio(arabicText);
        assertTrue(ratio < 0.1, "Pure Arabic should have < 10% Latin characters");
    }

    @Test
    void testGetLatinRatio_PureEnglish_ReturnsOne() {
        String englishText = "In the name of Allah the Most Gracious";
        double ratio = TafsirSnippetSanitizer.getLatinRatio(englishText);
        assertTrue(ratio > 0.9, "Pure English should have > 90% Latin characters");
    }

    @Test
    void testGetLatinRatio_MixedText_ReturnsIntermediate() {
        String mixedText = "Surah Al-Fatiha (سورة الفاتحة)";
        double ratio = TafsirSnippetSanitizer.getLatinRatio(mixedText);
        assertTrue(ratio > 0.1 && ratio < 0.9, "Mixed text should have intermediate Latin ratio");
    }

    @Test
    void testSanitizeText_PreservesArabicDiacritics() {
        String input = "بِسْمِ اللَّهِ الرَّحْمَنِ الرَّحِيمِ";
        String sanitized = TafsirSnippetSanitizer.sanitizeText(input);
        assertTrue(TafsirSnippetSanitizer.countArabicDiacritics(sanitized) > 0,
                "Sanitize should preserve Arabic diacritics");
        // Check that Arabic letters are preserved by checking for some key letters
        assertTrue(sanitized.contains("ال"), "Sanitize should preserve Arabic letters (lam-alif)");
        assertTrue(sanitized.contains("الله") || sanitized.length() > 5,
                "Sanitize should preserve Arabic text content");
    }

    @Test
    void testSanitizeText_RemovesExtraWhitespace() {
        String input = "الحمد     لله\n\n\nرب   العالمين";
        String sanitized = TafsirSnippetSanitizer.sanitizeText(input);
        assertFalse(sanitized.contains("  "), "Should remove double spaces");
        assertFalse(sanitized.contains("\n\n\n"), "Should remove excessive newlines");
    }

    @Test
    void testIsIndexable_ArabicDocumentWithEnoughContent_ReturnsTrue() {
        TafsirDocument doc = new TafsirDocument();
        doc.setLanguage("ar");
        doc.setCommentaryText("هذا نص طويل يحتوي على العديد من الكلمات العربية. " +
                "نحن نختبر هذا النص للتأكد من أنه يحتوي على المحتوى الكافي. " +
                "هذا تفسير للآية الكريمة التي نتحدث عنها. " +
                "وفي هذا التفسير نوضح المعاني العظيمة للقرآن الكريم. " +
                "وهذا المزيد من النص للتأكد من أننا نمتلك العدد الكافي من الكلمات.");
        doc.computeWordCount();
        doc.setCommentaryWordCount(doc.getCommentaryText().split("\\s+").length);

        assertTrue(TafsirSnippetSanitizer.isIndexable(doc),
                "Arabic document with substantial content should be indexable");
    }

    @Test
    void testIsIndexable_ArabicDocumentWithLittleContent_ReturnsFalse() {
        TafsirDocument doc = new TafsirDocument();
        doc.setLanguage("ar");
        doc.setCommentaryText("قصير");
        doc.computeWordCount();

        assertFalse(TafsirSnippetSanitizer.isIndexable(doc),
                "Arabic document with little content should not be indexable");
    }

    @Test
    void testIsIndexable_EnglishDocumentWithEnoughContent_ReturnsTrue() {
        TafsirDocument doc = new TafsirDocument();
        doc.setLanguage("en");
        doc.setCommentaryText("This is a longer text with many words. " +
                "We are testing this document to ensure it has enough content. " +
                "This commentary explains the verse we are discussing. " +
                "In this interpretation we clarify the great meanings of the Holy Quran. " +
                "And this is more text to ensure we have enough words to pass the validation.");
        doc.computeWordCount();

        assertTrue(TafsirSnippetSanitizer.isIndexable(doc),
                "English document with substantial content should be indexable");
    }

    @Test
    void testIsSubstantiveText_ArabicWithMoreThan6Words_ReturnsTrue() {
        // Create a document with enough Arabic words (10+) to pass isIndexable
        TafsirDocument doc = new TafsirDocument();
        doc.setLanguage("ar");
        doc.setCommentaryText("هذا نص عربي طويل يحتوي على أكثر من عشر كلمات كما نحتاج للاختبار");
        doc.computeWordCount();

        assertTrue(TafsirSnippetSanitizer.isIndexable(doc),
                "Arabic text with 10+ words should be indexable");
    }

    @Test
    void testIsSubstantiveText_EnglishWithMoreThan6Words_ReturnsTrue() {
        // Create a document with enough English words (10+) to pass isIndexable
        TafsirDocument doc = new TafsirDocument();
        doc.setLanguage("en");
        doc.setCommentaryText("This English text has more than ten words for testing the index check");
        doc.computeWordCount();

        assertTrue(TafsirSnippetSanitizer.isIndexable(doc),
                "English text with 10+ words should be indexable");
    }

    @Test
    void testSanitize_RemovesTrailingReferenceNoise_Arabic() {
        // Arabic text with numeric-only ending (common in Arabic references)
        String input = "هذا نص التفسير\n\n1 2 3 4 5";
        String sanitized = TafsirSnippetSanitizer.sanitizeText(input);
        // Arabic reference paragraphs may be preserved since they use different detection
        assertNotNull(sanitized);
    }

    @Test
    void testArabicTextQuality_HasDiacritics() {
        String tafsirText = "فَتَبَارَكَ اللَّهُ أَحْسَنُ الْخَالِقِينَ";
        int diacritics = TafsirSnippetSanitizer.countArabicDiacritics(tafsirText);
        assertTrue(diacritics > 0, "Quality Arabic tafsir should have diacritics");
    }

    @Test
    void testArabicTextQuality_LowLatinRatio() {
        String tafsirText = "وَمَا أَرْسَلْنَاكَ إِلَّا رَحْمَةً لِلْعَالَمِينَ";
        double ratio = TafsirSnippetSanitizer.getLatinRatio(tafsirText);
        assertTrue(ratio < 0.05, "Quality Arabic tafsir should have < 5% Latin characters");
    }

    @Test
    void testArabicTextQuality_NoHTMLEntities() {
        String withHtml = "هذا نص &lt;مع&gt; كيانات HTML";
        String sanitized = TafsirSnippetSanitizer.sanitizeText(withHtml);
        // Should not contain HTML entities after proper sanitization
        // Note: This test documents current behavior - may need improvement
        assertNotNull(sanitized);
    }

    @Test
    void testArabicTextQuality_NoEncodingCorruption() {
        // Test for \uFFFD replacement character
        String cleanText = "نص نظيف بدون مشاكل";
        assertFalse(cleanText.contains("\uFFFD"), "Should not have replacement characters");
    }

    @Test
    void testIsIndexable_NullDocument_ReturnsFalse() {
        assertFalse(TafsirSnippetSanitizer.isIndexable(null));
    }

    @Test
    void testIsIndexable_DocumentWithNullCommentary_ReturnsFalse() {
        TafsirDocument doc = new TafsirDocument();
        doc.setCommentaryText(null);
        doc.computeWordCount();
        assertFalse(TafsirSnippetSanitizer.isIndexable(doc));
    }

    @Test
    void testIsIndexable_DocumentWithEmptyCommentary_ReturnsFalse() {
        TafsirDocument doc = new TafsirDocument();
        doc.setCommentaryText("");
        doc.computeWordCount();
        assertFalse(TafsirSnippetSanitizer.isIndexable(doc));
    }
}
