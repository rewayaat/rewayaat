package com.rewayaat.tafsir;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Test cases for SurahNameResolver.
 * Tests mapping of surah names to numbers with various transliterations.
 */
public class SurahNameResolverTest {

    @Test
    public void testAlFatihaVariants() {
        assertEquals(Integer.valueOf(1), SurahNameResolver.resolve("Al-Fatiha"));
        assertEquals(Integer.valueOf(1), SurahNameResolver.resolve("Fatiha"));
        assertEquals(Integer.valueOf(1), SurahNameResolver.resolve("al-Fatihah"));
        assertEquals(Integer.valueOf(1), SurahNameResolver.resolve("Al-Hamd"));
        assertEquals(Integer.valueOf(1), SurahNameResolver.resolve("Hamd"));
        assertEquals(Integer.valueOf(1), SurahNameResolver.resolve("Surah al-Hamd"));
    }

    @Test
    public void testAlBaqarahVariants() {
        assertEquals(Integer.valueOf(2), SurahNameResolver.resolve("Al-Baqarah"));
        assertEquals(Integer.valueOf(2), SurahNameResolver.resolve("Baqarah"));
        assertEquals(Integer.valueOf(2), SurahNameResolver.resolve("Baqara"));
        assertEquals(Integer.valueOf(2), SurahNameResolver.resolve("al-Baqarah"));
        assertEquals(Integer.valueOf(2), SurahNameResolver.resolve("Surah Baqarah"));
    }

    @Test
    public void testAnNisaVariants() {
        assertEquals(Integer.valueOf(4), SurahNameResolver.resolve("An-Nisa"));
        assertEquals(Integer.valueOf(4), SurahNameResolver.resolve("Nisa"));
        assertEquals(Integer.valueOf(4), SurahNameResolver.resolve("an-Nisa"));
        assertEquals(Integer.valueOf(4), SurahNameResolver.resolve("An-Nisaa"));
        assertEquals(Integer.valueOf(4), SurahNameResolver.resolve("Surah an-Nisa"));
    }

    @Test
    public void testYusuf() {
        assertEquals(Integer.valueOf(12), SurahNameResolver.resolve("Yusuf"));
        assertEquals(Integer.valueOf(12), SurahNameResolver.resolve("Surah Yusuf"));
        assertEquals(Integer.valueOf(12), SurahNameResolver.resolve("Joseph"));
    }

    @Test
    public void testAlKahf() {
        assertEquals(Integer.valueOf(18), SurahNameResolver.resolve("Al-Kahf"));
        assertEquals(Integer.valueOf(18), SurahNameResolver.resolve("Kahf"));
        assertEquals(Integer.valueOf(18), SurahNameResolver.resolve("al-Kahf"));
        assertEquals(Integer.valueOf(18), SurahNameResolver.resolve("Surah al-Kahf"));
    }

    @Test
    public void testMaryam() {
        assertEquals(Integer.valueOf(19), SurahNameResolver.resolve("Maryam"));
        assertEquals(Integer.valueOf(19), SurahNameResolver.resolve("Surah Maryam"));
        assertEquals(Integer.valueOf(19), SurahNameResolver.resolve("Mary"));
    }

    @Test
    public void testHyphenAndSpaceNormalization() {
        assertEquals(Integer.valueOf(20), SurahNameResolver.resolve("Ta-Ha"));
        assertEquals(Integer.valueOf(20), SurahNameResolver.resolve("Ta Ha"));
        assertEquals(Integer.valueOf(3), SurahNameResolver.resolve("Ali-Imran"));
        assertEquals(Integer.valueOf(3), SurahNameResolver.resolve("Ali Imran"));
        assertEquals(Integer.valueOf(30), SurahNameResolver.resolve("Ar-Room"));
        assertEquals(Integer.valueOf(30), SurahNameResolver.resolve("Room"));
        assertEquals(Integer.valueOf(34), SurahNameResolver.resolve("As-Saba"));
        assertEquals(Integer.valueOf(35), SurahNameResolver.resolve("Al-Fatir"));
    }

    @Test
    public void testYasin() {
        assertEquals(Integer.valueOf(36), SurahNameResolver.resolve("Ya-Sin"));
        assertEquals(Integer.valueOf(36), SurahNameResolver.resolve("Yasin"));
        assertEquals(Integer.valueOf(36), SurahNameResolver.resolve("Yaseen"));
    }

    @Test
    public void testAlFajr() {
        assertEquals(Integer.valueOf(89), SurahNameResolver.resolve("Al-Fajr"));
        assertEquals(Integer.valueOf(89), SurahNameResolver.resolve("Fajr"));
        assertEquals(Integer.valueOf(89), SurahNameResolver.resolve("al-Fajr"));
    }

    @Test
    public void testAnNas() {
        assertEquals(Integer.valueOf(114), SurahNameResolver.resolve("An-Nas"));
        assertEquals(Integer.valueOf(114), SurahNameResolver.resolve("Nas"));
        assertEquals(Integer.valueOf(114), SurahNameResolver.resolve("an-Nas"));
    }

    @Test
    public void testInvalidSurahName() {
        assertNull(SurahNameResolver.resolve("Not a Surah"));
        assertNull(SurahNameResolver.resolve(null));
        assertNull(SurahNameResolver.resolve(""));
    }

    @Test
    public void testCaseInsensitivity() {
        Integer result1 = SurahNameResolver.resolve("AL-FATIHA");
        Integer result2 = SurahNameResolver.resolve("al-fatiha");
        Integer result3 = SurahNameResolver.resolve("Al-Fatiha");

        assertEquals("All should resolve to surah 1", result1, result2);
        assertEquals("All should resolve to surah 1", result2, result3);
    }

    @Test
    public void testIsSurahName() {
        assertTrue("Should recognize Al-Fatiha", SurahNameResolver.isSurahName("Al-Fatiha"));
        assertTrue("Should recognize Baqarah", SurahNameResolver.isSurahName("Baqarah"));
        assertFalse("Should reject invalid name", SurahNameResolver.isSurahName("Not a Surah"));
        assertFalse("Should reject null", SurahNameResolver.isSurahName(null));
    }
}
