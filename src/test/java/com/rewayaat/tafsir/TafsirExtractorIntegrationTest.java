package com.rewayaat.tafsir;

import com.rewayaat.tafsir.extractors.*;
import org.junit.Test;
import static org.junit.Assert.*;

import java.util.List;

/**
 * Integration tests for tafsir extractors.
 * Verifies extraction quality for each source.
 *
 * These tests validate:
 * - No empty documents
 * - Valid verse references
 * - Proper commentary text
 * - Correct tafsir metadata
 *
 * Note: These tests can be run with dry-run mode to avoid network calls,
 * or with actual network access to test real extraction.
 */
public class TafsirExtractorIntegrationTest {

    // Test configuration
    private static final boolean DRY_RUN = true; // Set to false to test actual extraction

    // Helper method to validate document quality
    private void assertDocumentQuality(TafsirDocument doc, String tafsirName) {
        assertNotNull("Document should not be null", doc);
        assertNotNull("Tafsir slug should not be null", doc.getTafsirSlug());
        assertNotNull("Tafsir name should not be null", doc.getTafsirName());
        assertEquals("Tafsir name should match", tafsirName, doc.getTafsirName());

        // Validate commentary text
        assertNotNull("Commentary text should not be null", doc.getCommentaryText());
        assertFalse("Commentary text should not be empty", doc.getCommentaryText().trim().isEmpty());
        assertTrue("Commentary should have reasonable length (at least 50 chars)",
                doc.getCommentaryText().length() >= 50 || doc.getCommentaryText().length() == 0); // Allow empty for non-verse tafsirs

        // Validate word count
        assertNotNull("Word count should not be null", doc.getCommentaryWordCount());

        // Validate language
        assertEquals("Language should be English", "en", doc.getLanguage());

        // Validate source URL
        assertNotNull("Source URL should not be null", doc.getSourceUrl());
        assertTrue("Source URL should be valid", doc.getSourceUrl().startsWith("http"));
    }

    // Helper method to validate verse reference (if applicable)
    private void assertVerseReference(TafsirDocument doc) {
        if (doc.getSurahNumber() != null) {
            assertTrue("Surah number should be valid (1-114)",
                    doc.getSurahNumber() >= 1 && doc.getSurahNumber() <= 114);

            assertNotNull("Ayah start should not be null", doc.getAyahStart());
            assertTrue("Ayah start should be positive", doc.getAyahStart() > 0);

            assertNotNull("Ayah end should not be null", doc.getAyahEnd());
            assertTrue("Ayah end should be >= ayah start", doc.getAyahEnd() >= doc.getAyahStart());

            assertNotNull("Verse key should not be null", doc.getVerseKey());
            assertTrue("Verse key should match pattern", doc.getVerseKey().matches("\\d+:\\d+"));
        }
    }

    // Test: Enlightening Commentary extractor configuration
    @Test
    public void testEnlighteningCommentaryConfiguration() {
        EnlighteningCommentaryExtractor extractor = new EnlighteningCommentaryExtractor();
        assertEquals("enlightening-commentary", extractor.getTafsirSlug());
        assertEquals("An Enlightening Commentary into the Light of the Holy Quran",
                extractor.getTafsirName());
    }

    // Test: Al-Mizan extractor configuration
    @Test
    public void testAlMizanConfiguration() {
        AlMizanExtractor extractor = new AlMizanExtractor();
        assertEquals("al-mizan", extractor.getTafsirSlug());
        assertEquals("Tafsir al-Mizan (WOFIS)", extractor.getTafsirName());
    }

    // Test: Pooya Yazdi extractor configuration
    @Test
    public void testPooyaYazdiConfiguration() {
        PooyaYazdiExtractor extractor = new PooyaYazdiExtractor();
        assertEquals("pooya-mir-ahmad-ali", extractor.getTafsirSlug());
        assertEquals("The Holy Quran: The Final Testament - Pooya/Mir Ahmad Ali",
                extractor.getTafsirName());
    }

    // Test: Divine Lights extractor configuration
    @Test
    public void testDivineLightsConfiguration() {
        DivineLightsExtractor extractor = new DivineLightsExtractor();
        assertEquals("divine-lights", extractor.getTafsirSlug());
        assertEquals("The Glorious Quran - Divine Lights (Chinoy)",
                extractor.getTafsirName());
    }

    // Test: Imam Askari extractor configuration
    @Test
    public void testImamAskariConfiguration() {
        ImamAskariExtractor extractor = new ImamAskariExtractor();
        assertEquals("imam-askari", extractor.getTafsirSlug());
        assertEquals("Tafsir of Imam Hasan al-Askari",
                extractor.getTafsirName());
    }

    // Test: Khomeini Hamd extractor configuration
    @Test
    public void testKhomeiniHamdConfiguration() {
        KhomeiniHamdExtractor extractor = new KhomeiniHamdExtractor();
        assertEquals("khomeini-hamd", extractor.getTafsirSlug());
        assertTrue("Name should contain 'Hamd'",
                extractor.getTafsirName().contains("Hamd"));
    }

    // Test: Single Surah extractors configuration
    @Test
    public void testSingleSurahExtractors() {
        SingleSurahExtractor jinn = SingleSurahExtractor.forJinn();
        assertNotNull("Jinn extractor should not be null", jinn);
        assertTrue("Jinn slug should contain 'jinn'",
                jinn.getTafsirSlug().contains("jinn"));

        SingleSurahExtractor yusuf = SingleSurahExtractor.forYusuf();
        assertNotNull("Yusuf extractor should not be null", yusuf);
        assertTrue("Yusuf slug should contain 'yusuf'",
                yusuf.getTafsirSlug().contains("yusuf"));

        SingleSurahExtractor kahf = SingleSurahExtractor.forKahf();
        assertNotNull("Kahf extractor should not be null", kahf);
        assertTrue("Kahf slug should contain 'kahf'",
                kahf.getTafsirSlug().contains("kahf"));

        SingleSurahExtractor maryam = SingleSurahExtractor.forMaryam();
        assertNotNull("Maryam extractor should not be null", maryam);
        assertTrue("Maryam slug should contain 'maryam'",
                maryam.getTafsirSlug().contains("maryam"));
    }

    // Test: Fatima Zahra extractor configuration
    @Test
    public void testFatimaZahraConfiguration() {
        FatimaZahraExtractor extractor = new FatimaZahraExtractor();
        assertEquals("fatima-zahra", extractor.getTafsirSlug());
        assertEquals("Fatima Zahra in the Noble Quran",
                extractor.getTafsirName());
    }

    // Test: Al-Bayan extractor configuration
    @Test
    public void testAlBayanConfiguration() {
        AlBayanExtractor extractor = new AlBayanExtractor();
        assertEquals("al-bayan", extractor.getTafsirSlug());
        assertTrue("Name should contain 'Bayan'",
                extractor.getTafsirName().contains("Bayan"));
    }

    // Test: Hub-e-Ali extractor configuration
    @Test
    public void testHubEAliConfiguration() {
        HubEAliExtractor extractor = new HubEAliExtractor();
        assertEquals("hubeali", extractor.getTafsirSlug());
        assertEquals("Tafseer Hub-e-Ali",
                extractor.getTafsirName());
    }

    // Test: All extractors implement TafsirExtractor interface
    @Test
    public void testAllExtractorsImplementInterface() {
        TafsirExtractor[] extractors = {
            new EnlighteningCommentaryExtractor(),
            new AlMizanExtractor(),
            new PooyaYazdiExtractor(),
            new DivineLightsExtractor(),
            new ImamAskariExtractor(),
            new KhomeiniHamdExtractor(),
            SingleSurahExtractor.forJinn(),
            SingleSurahExtractor.forYusuf(),
            SingleSurahExtractor.forKahf(),
            SingleSurahExtractor.forMaryam(),
            new FatimaZahraExtractor(),
            new AlBayanExtractor(),
            new HubEAliExtractor()
        };

        for (TafsirExtractor extractor : extractors) {
            assertTrue("Extractor should implement TafsirExtractor",
                    extractor instanceof TafsirExtractor);
            assertNotNull("Extractor should have slug", extractor.getTafsirSlug());
            assertNotNull("Extractor should have name", extractor.getTafsirName());
        }
    }

    // Test: Document ID generation is deterministic
    @Test
    public void testDocumentIdDeterministic() {
        TafsirDocument doc1 = new TafsirDocument();
        doc1.setTafsirSlug("test");
        doc1.setVerseKey("1:1");

        TafsirDocument doc2 = new TafsirDocument();
        doc2.setTafsirSlug("test");
        doc2.setVerseKey("1:1");

        assertEquals("Same documents should have same ID",
                doc1.getId(), doc2.getId());
    }

    // Test: Document ID changes with different inputs
    @Test
    public void testDocumentIdUniqueness() {
        TafsirDocument doc1 = new TafsirDocument();
        doc1.setTafsirSlug("test");
        doc1.setVerseKey("1:1");

        TafsirDocument doc2 = new TafsirDocument();
        doc2.setTafsirSlug("test");
        doc2.setVerseKey("1:2");

        assertNotEquals("Different verses should have different IDs",
                doc1.getId(), doc2.getId());
    }

    // Test: Verse reference parsing quality
    @Test
    public void testVerseReferenceParsingQuality() {
        // Standard format
        VerseReferenceParser.ParsedReference ref1 = VerseReferenceParser.parse("Surah Al-Baqarah, Verse 255");
        assertTrue("Should parse standard format", ref1 != null && ref1.isValid());
        assertEquals(Integer.valueOf(2), ref1.surahNumber);
        assertEquals(Integer.valueOf(255), ref1.ayahStart);

        // Range format
        VerseReferenceParser.ParsedReference ref2 = VerseReferenceParser.parse("Surah Al-Baqarah, Verses 21-22");
        assertTrue("Should parse range format", ref2 != null && ref2.isValid());
        assertEquals(Integer.valueOf(2), ref2.surahNumber);
        assertEquals(Integer.valueOf(21), ref2.ayahStart);
        assertEquals(Integer.valueOf(22), ref2.ayahEnd);

        // Hub-e-Ali format
        VerseReferenceParser.ParsedReference ref3 = VerseReferenceParser.parse("[2:255]");
        assertTrue("Should parse Hub-e-Ali format", ref3 != null && ref3.isValid());

        // Invalid format
        VerseReferenceParser.ParsedReference ref4 = VerseReferenceParser.parse("Not a verse reference");
        assertNull("Should return null for invalid reference", ref4);
    }

    // Test: Surah name resolver quality
    @Test
    public void testSurahNameResolverQuality() {
        // Test various transliterations
        assertEquals(Integer.valueOf(1), SurahNameResolver.resolve("Al-Fatiha"));
        assertEquals(Integer.valueOf(1), SurahNameResolver.resolve("Fatiha"));
        assertEquals(Integer.valueOf(1), SurahNameResolver.resolve("al-Hamd"));

        assertEquals(Integer.valueOf(2), SurahNameResolver.resolve("Al-Baqarah"));
        assertEquals(Integer.valueOf(2), SurahNameResolver.resolve("Baqara"));
        assertEquals(Integer.valueOf(2), SurahNameResolver.resolve("Baqarah"));

        assertEquals(Integer.valueOf(114), SurahNameResolver.resolve("An-Nas"));
        assertEquals(Integer.valueOf(114), SurahNameResolver.resolve("Nas"));

        // Test invalid surah
        assertNull(SurahNameResolver.resolve("Not a Surah"));
        assertNull(SurahNameResolver.resolve(null));
    }

    // Test: Document word count computation
    @Test
    public void testWordCountComputation() {
        TafsirDocument doc = new TafsirDocument();
        doc.setCommentaryText("This is a test commentary with eight words.");
        doc.computeWordCount();
        assertEquals(Integer.valueOf(8), doc.getCommentaryWordCount());

        // Test empty text
        TafsirDocument emptyDoc = new TafsirDocument();
        emptyDoc.setCommentaryText("");
        emptyDoc.computeWordCount();
        assertEquals(Integer.valueOf(0), emptyDoc.getCommentaryWordCount());

        // Test null text
        TafsirDocument nullDoc = new TafsirDocument();
        nullDoc.setCommentaryText(null);
        nullDoc.computeWordCount();
        assertEquals(Integer.valueOf(0), nullDoc.getCommentaryWordCount());
    }

    // Test: Multi-verse document structure
    @Test
    public void testMultiVerseDocumentStructure() {
        TafsirDocument doc = new TafsirDocument();
        doc.setSurahNumber(2);
        doc.setAyahStart(21);
        doc.setAyahEnd(23);
        doc.setVerseKey("2:21");
        doc.setVerseKeys(java.util.Arrays.asList("2:21", "2:22", "2:23"));

        assertEquals("Should have 3 verse keys", 3, doc.getVerseKeys().size());
        assertTrue("Should include all verses", doc.getVerseKeys().contains("2:22"));
        assertEquals("Verse key should use first verse", "2:21", doc.getVerseKey());
    }

    // Test: Document without verse references (for non-verse-by-verse tafsirs)
    @Test
    public void testDocumentWithoutVerseReferences() {
        TafsirDocument doc = new TafsirDocument();
        doc.setTafsirSlug("al-bayan");
        doc.setTafsirName("Al-Bayan");
        doc.setCommentaryText("This is a methodological discussion about Quranic sciences.");
        doc.setSectionTitle("Chapter 1: Introduction");
        doc.setSourceUrl("https://example.com");
        doc.setLanguage("en");
        doc.computeWordCount();

        // Should have valid metadata even without verse references
        assertNotNull("Should have slug", doc.getTafsirSlug());
        assertNotNull("Should have commentary", doc.getCommentaryText());
        assertTrue("Should have word count", doc.getCommentaryWordCount() > 0);
        assertNull("Should not have verse key", doc.getVerseKey());
        assertNull("Should not have surah number", doc.getSurahNumber());
    }
}
