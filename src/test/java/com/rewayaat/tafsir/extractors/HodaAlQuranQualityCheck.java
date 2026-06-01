package com.rewayaat.tafsir.extractors;

import com.rewayaat.tafsir.TafsirDocument;
import com.rewayaat.tafsir.TafsirSnippetSanitizer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Quality checks for Arabic tafsir extraction from hodaalquran.com.
 */
class HodaAlQuranQualityCheck {

    // Test URLs - key verses from different surahs
    private static final String[] TEST_PAGES = {
        "https://www.hodaalquran.com/book/content/8730",  // 1:7 - Fatiha
        "https://www.hodaalquran.com/book/content/9235",  // 2:255 - Baqarah (Ayat al-Kursi)
        "https://www.hodaalquran.com/book/content/10046", // 5:3 - Ma'idah (Ghadir)
        "https://www.hodaalquran.com/book/content/13994", // 33:33 - Ahzab (Tathir)
        "https://www.hodaalquran.com/book/content/11911"  // 76:5-9 - Insan (Hal Ata)
    };

    @Test
    void testHtmlStructure() throws IOException {
        System.out.println("\n=== HTML Structure Check ===");

        for (int i = 0; i < TEST_PAGES.length; i++) {
            String url = TEST_PAGES[i];
            System.out.println("\nChecking: " + url);

            Document doc = Jsoup.connect(url)
                    .userAgent("Rewayaat-Tafsir-Extractor/1.0")
                    .timeout(30000)
                    .get();

            // Check for <P> tags (uppercase)
            Elements pTags = doc.select("P");
            System.out.println("  Uppercase <P> tags: " + pTags.size());

            // Check for Arabic content
            boolean hasArabic = doc.text().matches(".*[\\u0600-\\u06FF].*");
            assertTrue(hasArabic, "Page should contain Arabic text");

            // Check for tafsir heading
            boolean hasTafsir = doc.html().contains("التّفسير") || doc.html().contains("التفسير");
            assertTrue(hasTafsir, "Page should have 'التفسير' heading");

            // Check for HTML entities used in verse text
            boolean hasOrnamentalBrackets = doc.html().contains("&#64831;") || doc.html().contains("&#64830;");
            assertTrue(hasOrnamentalBrackets, "Page should use ornamental bracket entities");
        }
    }

    @Test
    void testExtractionQuality() throws IOException {
        System.out.println("\n=== Extraction Quality Check ===");

        int passed = 0;
        int totalTests = TEST_PAGES.length;

        for (int i = 0; i < totalTests; i++) {
            String url = TEST_PAGES[i];
            System.out.println("\n[" + (i + 1) + "] Checking: " + url);

            try {
                Document doc = Jsoup.connect(url)
                        .userAgent("Rewayaat-Tafsir-Extractor/1.0")
                        .timeout(30000)
                        .get();

                // Extract content using the same logic as the extractor
                Elements pTags = doc.select("p");
                StringBuilder commentary = new StringBuilder();
                boolean foundTafsir = false;
                boolean skippedFirstAfterTafsir = false;

                for (Element p : pTags) {
                    String text = p.text().trim();
                    if (text.isEmpty()) continue;

                    if (text.equals("التّفسير") || text.equals("التفسير")) {
                        foundTafsir = true;
                        continue;
                    }

                    if (foundTafsir && !skippedFirstAfterTafsir) {
                        skippedFirstAfterTafsir = true;
                        continue;
                    }

                    if (foundTafsir && skippedFirstAfterTafsir) {
                        if (text.contains("المكتبة المقروءة")) break;
                        commentary.append(text).append("\n\n");
                    }
                }

                String content = commentary.toString().trim();

                // Quality checks
                boolean allPassed = true;

                // 1. Content not empty
                if (content.isEmpty()) {
                    System.out.println("  ❌ FAIL: No content extracted");
                    allPassed = false;
                } else {
                    System.out.println("  ✓ Content length: " + content.length() + " chars");
                }

                // 2. Has Arabic text
                boolean hasArabic = content.matches(".*[\\u0600-\\u06FF].*");
                if (!hasArabic) {
                    System.out.println("  ❌ FAIL: No Arabic text found");
                    allPassed = false;
                } else {
                    System.out.println("  ✓ Has Arabic text");
                }

                // 3. Diacritics check
                int diacriticCount = countDiacritics(content);
                System.out.println("  ✓ Diacritics: " + diacriticCount);

                // 4. Latin ratio check
                double latinRatio = getLatinRatio(content);
                if (latinRatio > 0.05) {
                    System.out.println("  ⚠️  WARNING: High Latin ratio: " + String.format("%.2f%%", latinRatio * 100));
                } else {
                    System.out.println("  ✓ Latin ratio: " + String.format("%.2f%%", latinRatio * 100));
                }

                // 5. No HTML residue
                boolean hasHtmlResidue = content.contains("&") || content.contains("<") ||
                                        content.contains("&gt;") || content.contains("&lt;");
                if (hasHtmlResidue) {
                    System.out.println("  ❌ FAIL: HTML residue found");
                    allPassed = false;
                } else {
                    System.out.println("  ✓ No HTML residue");
                }

                // 6. No replacement characters
                boolean hasReplacementChars = content.contains("\uFFFD");
                if (hasReplacementChars) {
                    System.out.println("  ❌ FAIL: Replacement characters found");
                    allPassed = false;
                } else {
                    System.out.println("  ✓ No encoding corruption");
                }

                // 7. Word count
                int wordCount = content.split("\\s+").length;
                if (wordCount < 20) {
                    System.out.println("  ❌ FAIL: Too short: " + wordCount + " words");
                    allPassed = false;
                } else {
                    System.out.println("  ✓ Word count: " + wordCount);
                }

                // 8. Content preview
                String preview = content.substring(0, Math.min(100, content.length()));
                System.out.println("  Preview: \"" + preview + "...\"");

                if (allPassed) {
                    passed++;
                    System.out.println("  ✅ PASSED");
                } else {
                    System.out.println("  ❌ FAILED");
                }

            } catch (Exception e) {
                System.out.println("  ❌ ERROR: " + e.getMessage());
            }
        }

        System.out.println("\n=== Summary: " + passed + "/" + totalTests + " tests passed ===");
        assertTrue(passed >= totalTests - 1, "At least " + (totalTests - 1) + " tests should pass");
    }

    @Test
    void testDocumentStructure() {
        System.out.println("\n=== Document Structure Check ===");

        HodaAlQuranExtractor extractor = HodaAlQuranExtractor.amthal();

        assertEquals("ar-amthal", extractor.getTafsirSlug());
        assertEquals("تفسير الأمثل", extractor.getTafsirName());

        // Create a sample document
        TafsirDocument doc = new TafsirDocument();
        doc.setTafsirSlug("ar-amthal");
        doc.setTafsirName("تفسير الأمثل");
        doc.setSurahNumber(1);
        doc.setAyahStart(7);
        doc.setAyahEnd(7);
        doc.setVerseKey("1:7");
        doc.setLanguage("ar");
        doc.setCommentaryText("هذا نص تفسيري عربي يحتوي على كلمات كافية للاختبار.");
        doc.setSourceUrl("https://www.hodaalquran.com/book/content/8730");
        doc.computeWordCount();

        // Verify document structure
        assertEquals("ar-amthal", doc.getTafsirSlug());
        assertEquals("ar", doc.getLanguage());
        assertEquals(Integer.valueOf(1), doc.getSurahNumber());
        assertEquals(Integer.valueOf(7), doc.getAyahStart());
        assertEquals(Integer.valueOf(7), doc.getAyahEnd());
        assertEquals("1:7", doc.getVerseKey());
        assertTrue(doc.getCommentaryWordCount() > 0);
        assertTrue(TafsirSnippetSanitizer.isIndexable(doc));

        System.out.println("  ✓ Document structure is correct");
    }

    @Test
    void testDiacriticPreservation() throws IOException {
        System.out.println("\n=== Diacritic Preservation Check ===");

        // Fetch a page and check if diacritics are preserved
        String url = "https://www.hodaalquran.com/book/content/8730";
        Document doc = Jsoup.connect(url)
                .userAgent("Rewayaat-Tafsir-Extractor/1.0")
                .timeout(30000)
                .get();

        String html = doc.html();

        // Check for diacritics in the HTML
        int diacriticCount = countDiacritics(html);
        System.out.println("  Diacritics in HTML: " + diacriticCount);
        assertTrue(diacriticCount > 0, "HTML should contain Arabic diacritics");

        // Check for shadda specifically (very common in Quranic Arabic)
        assertTrue(html.contains("ّ") || html.contains("\\u0651"),
                "Should contain shadda (تشديد)");
        System.out.println("  ✓ Diacritics are preserved in HTML");
    }

    @Test
    void testVerseReferenceParsing() {
        System.out.println("\n=== Verse Reference Parsing Check ===");

        HodaAlQuranExtractor extractor = HodaAlQuranExtractor.amthal();

        // Test the pattern matching - using reflection to access protected method
        try {
            java.lang.reflect.Method method = HodaAlQuranExtractor.class
                    .getDeclaredMethod("parseAyahNumber", String.class);
            method.setAccessible(true);

            assertNotNull(method.invoke(extractor, "الآية 7"), "Should parse single ayah");
            assertNotNull(method.invoke(extractor, "الآية 255"), "Should parse ayah 255");
            assertNotNull(method.invoke(extractor, "الآيات 1 - 5"), "Should parse ayah range");
            assertNull(method.invoke(extractor, "سورة الفاتحة"), "Should skip surah intro");
            assertNull(method.invoke(extractor, ""), "Should handle empty string");

            System.out.println("  ✓ Verse reference parsing works correctly");
        } catch (Exception e) {
            fail("Failed to test parseAyahNumber: " + e.getMessage());
        }
    }

    @Test
    void testNoorAlThaqalaynSpecialCase() {
        System.out.println("\n=== نور الثقلين Special Case Check ===");

        HodaAlQuranExtractor extractor = HodaAlQuranExtractor.noor();
        assertEquals("ar-noor-al-thaqalayn", extractor.getTafsirSlug());
        assertEquals("نور الثقلين", extractor.getTafsirName());

        System.out.println("  ✓ نور الثقلين extractor configured");
        System.out.println("  ℹ️  Note: Some verses may be empty (narration-dependent)");
        System.out.println("  ℹ️  Expected: ~4,000-5,000 documents (not all 6,236 verses)");
    }

    // Helper methods

    private static int countDiacritics(String text) {
        int count = 0;
        for (int i = 0x064B; i <= 0x065F; i++) {
            char c = (char) i;
            String diacritic = String.valueOf(c);
            count += text.split(Pattern.quote(diacritic), -1).length - 1;
        }
        // Also count shadda
        count += text.split("ّ", -1).length - 1;
        return count;
    }

    private static double getLatinRatio(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int totalChars = 0;
        int latinChars = 0;
        for (char c : text.toCharArray()) {
            if (Character.isLetter(c)) {
                totalChars++;
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                    latinChars++;
                }
            }
        }
        return totalChars > 0 ? (double) latinChars / totalChars : 0;
    }
}
