package com.rewayaat.tafsir;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;

/**
 * Test cases for TafsirDocument.
 */
public class TafsirDocumentTest {

    @Test
    public void testGetId() {
        TafsirDocument doc = new TafsirDocument();
        doc.setTafsirSlug("enlightening-commentary");
        doc.setVerseKey("2:255");

        assertEquals("enlightening-commentary_2:255", doc.getId());
    }

    @Test
    public void testGetIdReturnsNullWhenMissingFields() {
        TafsirDocument doc = new TafsirDocument();
        assertNull("ID should be null when tafsirSlug is null", doc.getId());

        doc.setTafsirSlug("test");
        assertNull("ID should be null when verseKey is null", doc.getId());
    }

    @Test
    public void testComputeWordCount() {
        TafsirDocument doc = new TafsirDocument();
        doc.setCommentaryText("This is a test commentary with seven words here.");

        doc.computeWordCount();
        assertEquals(Integer.valueOf(7), doc.getCommentaryWordCount());
    }

    @Test
    public void testComputeWordCountWithEmptyText() {
        TafsirDocument doc = new TafsirDocument();
        doc.setCommentaryText("");

        doc.computeWordCount();
        assertEquals(Integer.valueOf(0), doc.getCommentaryWordCount());
    }

    @Test
    public void testComputeWordCountWithNullText() {
        TafsirDocument doc = new TafsirDocument();
        doc.setCommentaryText(null);

        doc.computeWordCount();
        assertEquals(Integer.valueOf(0), doc.getCommentaryWordCount());
    }

    @Test
    public void testComputeWordCountWithMultipleSpaces() {
        TafsirDocument doc = new TafsirDocument();
        doc.setCommentaryText("One    two     three");

        doc.computeWordCount();
        assertEquals(Integer.valueOf(3), doc.getCommentaryWordCount());
    }

    @Test
    public void testDefaultLanguage() {
        TafsirDocument doc = new TafsirDocument();
        assertEquals("en", doc.getLanguage());
    }

    @Test
    public void testFullDocument() {
        TafsirDocument doc = createTestDocument();

        assertEquals("enlightening-commentary", doc.getTafsirSlug());
        assertEquals("Test Tafsir", doc.getTafsirName());
        assertEquals(Integer.valueOf(2), doc.getSurahNumber());
        assertEquals(Integer.valueOf(255), doc.getAyahStart());
        assertEquals(Integer.valueOf(257), doc.getAyahEnd());
        assertEquals("2:255", doc.getVerseKey());
        assertEquals("en", doc.getLanguage());
        assertEquals("Test Commentary", doc.getCommentaryText());
    }

    @Test
    public void testVerseKeysArray() {
        TafsirDocument doc = new TafsirDocument();
        doc.setVerseKeys(Arrays.asList("2:255", "2:256", "2:257"));

        List<String> keys = doc.getVerseKeys();
        assertEquals(3, keys.size());
        assertTrue(keys.contains("2:255"));
        assertTrue(keys.contains("2:256"));
        assertTrue(keys.contains("2:257"));
    }

    private TafsirDocument createTestDocument() {
        TafsirDocument doc = new TafsirDocument();
        doc.setTafsirSlug("enlightening-commentary");
        doc.setTafsirName("Test Tafsir");
        doc.setSurahNumber(2);
        doc.setAyahStart(255);
        doc.setAyahEnd(257);
        doc.setVerseKey("2:255");
        doc.setCommentaryText("Test Commentary");
        doc.setSectionTitle("Test Section");
        doc.setVolume("1");
        doc.setSourceUrl("https://example.com/test");
        return doc;
    }
}
