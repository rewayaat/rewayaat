package com.rewayaat.tafsir;

import org.junit.Test;
import static org.junit.Assert.*;

import com.rewayaat.tafsir.VerseReferenceParser.ParsedReference;

import java.util.List;

/**
 * Test cases for verse reference parsing.
 * Covers TC-VRP-01 through TC-VRP-06 from the extraction plan.
 */
public class VerseReferenceParserTest {

    // TC-VRP-01: Standard verse range
    @Test
    public void testStandardVerseRange() {
        String input = "Surah Al-Baqarah, Verses 21-22";
        ParsedReference result = VerseReferenceParser.parse(input);

        assertNotNull("Result should not be null", result);
        assertTrue("Result should be valid", result.isValid());
        assertEquals("Surah number should be 2", Integer.valueOf(2), result.surahNumber);
        assertEquals("Ayah start should be 21", Integer.valueOf(21), result.ayahStart);
        assertEquals("Ayah end should be 22", Integer.valueOf(22), result.ayahEnd);
        assertEquals("Verse key should be 2:21", "2:21", result.getVerseKey());
    }

    // TC-VRP-02: Single verse
    @Test
    public void testSingleVerse() {
        String input = "Surah Al-Baqarah, Verse 255";
        ParsedReference result = VerseReferenceParser.parse(input);

        assertNotNull("Result should not be null", result);
        assertTrue("Result should be valid", result.isValid());
        assertEquals("Surah number should be 2", Integer.valueOf(2), result.surahNumber);
        assertEquals("Ayah start should be 255", Integer.valueOf(255), result.ayahStart);
        assertEquals("Ayah end should equal ayah start", result.ayahStart, result.ayahEnd);
        assertEquals("Verse key should be 2:255", "2:255", result.getVerseKey());
    }

    // TC-VRP-03: Hub-e-Ali bracket format
    @Test
    public void testHubeAliBracketFormat() {
        String input1 = "[2:255]";
        ParsedReference result1 = VerseReferenceParser.parse(input1);

        assertNotNull("Bracket format should parse", result1);
        assertTrue("Bracket format should be valid", result1.isValid());
        assertEquals("Surah should be 2", Integer.valueOf(2), result1.surahNumber);
        assertEquals("Ayah should be 255", Integer.valueOf(255), result1.ayahStart);

        String input2 = "(2:255)";
        ParsedReference result2 = VerseReferenceParser.parse(input2);

        assertNotNull("Parenthesis format should parse", result2);
        assertTrue("Parenthesis format should be valid", result2.isValid());
    }

    // TC-VRP-04: Imam Askari large range format
    @Test
    public void testImamAskariLargeRangeFormat() {
        String input = "Exegesis of Surah Baqarah: Verses 1-62";
        ParsedReference result = VerseReferenceParser.parse(input);

        assertNotNull("Result should not be null", result);
        assertTrue("Result should be valid", result.isValid());
        assertEquals("Surah number should be 2", Integer.valueOf(2), result.surahNumber);
        assertEquals("Ayah start should be 1", Integer.valueOf(1), result.ayahStart);
        assertEquals("Ayah end should be 62", Integer.valueOf(62), result.ayahEnd);
    }

    // TC-VRP-05: Surah name variants
    @Test
    public void testSurahNameVariants() {
        String[] variants = {
            "Surah an-Nisa'",
            "Surah An-Nisaa",
            "Surah Nisa",
            "Surah An-Nisa"
        };

        for (String variant : variants) {
            ParsedReference result = VerseReferenceParser.parse(variant + ", Verse 1");
            assertNotNull("Variant should parse: " + variant, result);
            assertEquals("Variant should resolve to surah 4: " + variant,
                    Integer.valueOf(4), result.surahNumber);
        }
    }

    // TC-VRP-06: Surah al-Fatiha edge case
    @Test
    public void testSurahAlFatihaVariants() {
        String[] variants = {
            "Surah Al-Fatiha",
            "Surah al-Hamd",
            "Surah Fatiha",
            "Surah al-Fatihah"
        };

        for (String variant : variants) {
            // Test with SurahNameResolver directly
            Integer surahNumber = SurahNameResolver.resolve(variant);
            assertEquals("Variant should resolve to surah 1: " + variant,
                    Integer.valueOf(1), surahNumber);
        }
    }

    @Test
    public void testMultipleVerseReferences() {
        String input = "Surah Al-Baqarah, Verses 21-22; Surah Al-Baqarah, Verse 255";
        List<ParsedReference> results = VerseReferenceParser.parseMultiple(input);

        assertEquals("Should find 2 references", 2, results.size());
        assertEquals("First reference surah", Integer.valueOf(2), results.get(0).surahNumber);
        assertEquals("Second reference ayah", Integer.valueOf(255), results.get(1).ayahStart);
    }

    @Test
    public void testVerseKeyGeneration() {
        ParsedReference ref = new ParsedReference(2, 255, 255);
        assertEquals("2:255", ref.getVerseKey());
    }

    @Test
    public void testVerseKeysGenerationForRange() {
        ParsedReference ref = new ParsedReference(2, 21, 23);
        List<String> keys = ref.getVerseKeys();

        assertEquals("Should have 3 verse keys", 3, keys.size());
        assertTrue("Should contain 2:21", keys.contains("2:21"));
        assertTrue("Should contain 2:22", keys.contains("2:22"));
        assertTrue("Should contain 2:23", keys.contains("2:23"));
    }

    @Test
    public void testInvalidReferenceReturnsNull() {
        ParsedReference result = VerseReferenceParser.parse("This is not a verse reference");
        assertNull("Invalid reference should return null", result);
    }

    @Test
    public void testFormatVerseKey() {
        assertEquals("2:255", VerseReferenceParser.formatVerseKey(2, 255));
    }

    @Test
    public void testFormatVerseRange() {
        assertEquals("2:21-23", VerseReferenceParser.formatVerseRange(2, 21, 23));
        assertEquals("2:255", VerseReferenceParser.formatVerseRange(2, 255, 255));
    }
}
