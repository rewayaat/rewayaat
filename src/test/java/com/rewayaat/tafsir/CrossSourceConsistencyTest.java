package com.rewayaat.tafsir;

import com.rewayaat.tafsir.extractors.EnlighteningCommentaryExtractor;
import com.rewayaat.tafsir.extractors.PooyaYazdiExtractor;
import org.junit.Test;
import static org.junit.Assert.*;

import java.util.List;

/**
 * Cross-source consistency tests.
 * Covers TC-CS-01 through TC-CS-03 from the extraction plan.
 */
public class CrossSourceConsistencyTest {

    // TC-CS-01: Same verse, multiple tafsirs (structural test)
    @Test
    public void testMultipleExtractorsHaveDifferentSlugs() {
        EnlighteningCommentaryExtractor ec = new EnlighteningCommentaryExtractor();
        PooyaYazdiExtractor py = new PooyaYazdiExtractor();

        assertNotEquals("Extractors should have different slugs",
                ec.getTafsirSlug(), py.getTafsirSlug());

        assertNotEquals("Extractors should have different names",
                ec.getTafsirName(), py.getTafsirName());
    }

    // TC-CS-02: Idempotency - same input produces same output
    @Test
    public void testDocumentIdIsDeterministic() {
        TafsirDocument doc1 = new TafsirDocument();
        doc1.setTafsirSlug("enlightening-commentary");
        doc1.setVerseKey("2:255");

        TafsirDocument doc2 = new TafsirDocument();
        doc2.setTafsirSlug("enlightening-commentary");
        doc2.setVerseKey("2:255");

        assertEquals("Same documents should have same ID",
                doc1.getId(), doc2.getId());
    }

    @Test
    public void testDocumentIdChangesWithDifferentInput() {
        TafsirDocument doc1 = new TafsirDocument();
        doc1.setTafsirSlug("enlightening-commentary");
        doc1.setVerseKey("2:255");

        TafsirDocument doc2 = new TafsirDocument();
        doc2.setTafsirSlug("pooya-mir-ahmad-ali");
        doc2.setVerseKey("2:255");

        assertNotEquals("Different tafsir should have different ID",
                doc1.getId(), doc2.getId());
    }

    // TC-CS-03: No empty documents validation
    @Test
    public void testDocumentValidationRequiresEssentialFields() {
        TafsirDocument doc = new TafsirDocument();

        // Document without verse key should not have valid ID
        doc.setTafsirSlug("test");
        assertNull("Document without verseKey should have null ID", doc.getId());

        // Adding verse key should produce valid ID
        doc.setVerseKey("1:1");
        assertNotNull("Document with verseKey should have ID", doc.getId());
    }

    @Test
    public void testVerseKeyFormat() {
        TafsirDocument doc = new TafsirDocument();
        doc.setTafsirSlug("test");
        doc.setSurahNumber(1);
        doc.setAyahStart(1);
        doc.setAyahEnd(1);
        doc.setVerseKey("1:1");

        assertTrue("Verse key should match surah:ayah format",
                doc.getVerseKey().matches("\\d+:\\d+"));
    }

    @Test
    public void testVerseKeysArrayForMultiVerseRange() {
        TafsirDocument doc = new TafsirDocument();
        doc.setSurahNumber(2);
        doc.setAyahStart(255);
        doc.setAyahEnd(257);
        doc.setVerseKey("2:255");

        // For multi-verse ranges, verseKeys should include all verses
        // This test verifies the structure, not automatic population
        assertNotNull("verseKeys array should not be null", doc.getVerseKeys());
    }

    @Test
    public void testWordCountComputation() {
        TafsirDocument doc = new TafsirDocument();
        doc.setCommentaryText("Short commentary");
        doc.computeWordCount();

        assertTrue("Word count should be positive",
                doc.getCommentaryWordCount() > 0);
    }

    @Test
    public void testLanguageDefaultsToEnglish() {
        TafsirDocument doc = new TafsirDocument();
        assertEquals("en", doc.getLanguage());
    }

    @Test
    public void testSurahNumberWithinValidRange() {
        TafsirDocument doc = new TafsirDocument();
        doc.setSurahNumber(1);

        assertTrue("Surah 1 should be valid", doc.getSurahNumber() >= 1 && doc.getSurahNumber() <= 114);

        doc.setSurahNumber(114);
        assertTrue("Surah 114 should be valid", doc.getSurahNumber() >= 1 && doc.getSurahNumber() <= 114);
    }
}
