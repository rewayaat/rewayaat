package com.rewayaat.tafsir.extractors;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for HodaAlQuranExtractor parsing logic.
 * Tests Arabic ayah title parsing and content ID extraction.
 */
class HodaAlQuranExtractorTest {

    @Test
    void testParseAyahNumber_SingleAyah_ReturnsCorrectRange() {
        HodaAlQuranExtractor extractor = HodaAlQuranExtractor.amthal();
        // Use reflection to access protected method
        try {
            java.lang.reflect.Method method = HodaAlQuranExtractor.class
                    .getDeclaredMethod("parseAyahNumber", String.class);
            method.setAccessible(true);
            Object result = method.invoke(extractor, "الآية 7");
            assertNotNull(result);
            // AyahRange is a protected inner class - use getDeclaredField to access private fields
            Field startField = result.getClass().getDeclaredField("start");
            startField.setAccessible(true);
            Field endField = result.getClass().getDeclaredField("end");
            endField.setAccessible(true);
            assertEquals(7, startField.get(result));
            assertEquals(7, endField.get(result));
        } catch (Exception e) {
            fail("Failed to test parseAyahNumber: " + e.getMessage());
        }
    }

    @Test
    void testParseAyahNumber_MultiAyah_ReturnsCorrectRange() {
        HodaAlQuranExtractor extractor = HodaAlQuranExtractor.amthal();
        try {
            java.lang.reflect.Method method = HodaAlQuranExtractor.class
                    .getDeclaredMethod("parseAyahNumber", String.class);
            method.setAccessible(true);
            Object result = method.invoke(extractor, "الآيات 1 - 5");
            assertNotNull(result);
            Field startField = result.getClass().getDeclaredField("start");
            startField.setAccessible(true);
            Field endField = result.getClass().getDeclaredField("end");
            endField.setAccessible(true);
            assertEquals(1, startField.get(result));
            assertEquals(5, endField.get(result));
        } catch (Exception e) {
            fail("Failed to test parseAyahNumber: " + e.getMessage());
        }
    }

    @Test
    void testParseAyahNumber_SurahIntro_ReturnsNull() {
        HodaAlQuranExtractor extractor = HodaAlQuranExtractor.amthal();
        try {
            java.lang.reflect.Method method = HodaAlQuranExtractor.class
                    .getDeclaredMethod("parseAyahNumber", String.class);
            method.setAccessible(true);
            Object result = method.invoke(extractor, "سورة الحمد");
            assertNull(result, "Surah intro title should return null");
        } catch (Exception e) {
            fail("Failed to test parseAyahNumber: " + e.getMessage());
        }
    }

    @Test
    void testParseAyahNumber_EmptyString_ReturnsNull() {
        HodaAlQuranExtractor extractor = HodaAlQuranExtractor.amthal();
        try {
            java.lang.reflect.Method method = HodaAlQuranExtractor.class
                    .getDeclaredMethod("parseAyahNumber", String.class);
            method.setAccessible(true);
            Object result = method.invoke(extractor, "");
            assertNull(result);
        } catch (Exception e) {
            fail("Failed to test parseAyahNumber: " + e.getMessage());
        }
    }

    @Test
    void testExtractContentId_ValidUrl_ReturnsId() {
        HodaAlQuranExtractor extractor = HodaAlQuranExtractor.amthal();
        try {
            java.lang.reflect.Method method = HodaAlQuranExtractor.class
                    .getDeclaredMethod("extractContentId", String.class);
            method.setAccessible(true);
            Object result = method.invoke(extractor, "/book/content/12345");
            assertEquals("12345", result);
        } catch (Exception e) {
            fail("Failed to test extractContentId: " + e.getMessage());
        }
    }

    @Test
    void testExtractContentId_InvalidUrl_ReturnsEmpty() {
        HodaAlQuranExtractor extractor = HodaAlQuranExtractor.amthal();
        try {
            java.lang.reflect.Method method = HodaAlQuranExtractor.class
                    .getDeclaredMethod("extractContentId", String.class);
            method.setAccessible(true);
            Object result = method.invoke(extractor, "/invalid/path");
            assertEquals("", result);
        } catch (Exception e) {
            fail("Failed to test extractContentId: " + e.getMessage());
        }
    }

    @Test
    void testFactoryMethods_AllBooks_ReturnCorrectSlugs() {
        assertEquals("ar-amthal", HodaAlQuranExtractor.amthal().getTafsirSlug());
        assertEquals("ar-majma-al-bayan", HodaAlQuranExtractor.majmaBayan().getTafsirSlug());
        assertEquals("ar-tafsir-al-qummi", HodaAlQuranExtractor.qummi().getTafsirSlug());
        assertEquals("ar-khomeini-tafsir", HodaAlQuranExtractor.khomeini().getTafsirSlug());
        assertEquals("ar-jawami-al-jami", HodaAlQuranExtractor.jawami().getTafsirSlug());
        assertEquals("ar-al-tibyan", HodaAlQuranExtractor.tibyan().getTafsirSlug());
        assertEquals("ar-tafsir-al-safi", HodaAlQuranExtractor.safi().getTafsirSlug());
        assertEquals("ar-kanz-al-daqaiq", HodaAlQuranExtractor.kanz().getTafsirSlug());
        assertEquals("ar-noor-al-thaqalayn", HodaAlQuranExtractor.noor().getTafsirSlug());
        assertEquals("ar-ghareeb-al-quran", HodaAlQuranExtractor.ghareeb().getTafsirSlug());
    }

    @Test
    void testFactoryMethods_AllBooks_ReturnCorrectNames() {
        assertEquals("تفسير الأمثل", HodaAlQuranExtractor.amthal().getTafsirName());
        assertEquals("مجمع البيان", HodaAlQuranExtractor.majmaBayan().getTafsirName());
        assertEquals("تفسير القمي", HodaAlQuranExtractor.qummi().getTafsirName());
        assertEquals("تفسير القرآن الكريم", HodaAlQuranExtractor.khomeini().getTafsirName());
        assertEquals("جوامع الجامع", HodaAlQuranExtractor.jawami().getTafsirName());
        assertEquals("التبيان", HodaAlQuranExtractor.tibyan().getTafsirName());
        assertEquals("تفسير الصافي", HodaAlQuranExtractor.safi().getTafsirName());
        assertEquals("كنز الدقائق", HodaAlQuranExtractor.kanz().getTafsirName());
        assertEquals("نور الثقلين", HodaAlQuranExtractor.noor().getTafsirName());
        assertEquals("غريب القرآن", HodaAlQuranExtractor.ghareeb().getTafsirName());
    }

    @Test
    void testForBook_CustomBook_ReturnsCorrectValues() {
        HodaAlQuranExtractor extractor = HodaAlQuranExtractor.forBook(299, "test-slug", "Test Name");
        assertEquals("test-slug", extractor.getTafsirSlug());
        assertEquals("Test Name", extractor.getTafsirName());
    }

    @Test
    void testParseAyahNumber_VariousFormats() {
        HodaAlQuranExtractor extractor = HodaAlQuranExtractor.amthal();
        try {
            java.lang.reflect.Method method = HodaAlQuranExtractor.class
                    .getDeclaredMethod("parseAyahNumber", String.class);
            method.setAccessible(true);
            Field startField = null;
            Field endField = null;

            // Test various single ayah formats
            Object r1 = method.invoke(extractor, "الآية 1");
            assertNotNull(r1);
            if (startField == null) {
                startField = r1.getClass().getDeclaredField("start");
                startField.setAccessible(true);
            }
            assertEquals(1, startField.get(r1));

            Object r255 = method.invoke(extractor, "الآية 255");
            assertNotNull(r255);
            assertEquals(255, startField.get(r255));

            // Test multi-ayah with different dash styles
            Object rMulti1 = method.invoke(extractor, "الآيات 1-5");
            assertNotNull(rMulti1);
            if (endField == null) {
                endField = rMulti1.getClass().getDeclaredField("end");
                endField.setAccessible(true);
            }
            assertEquals(1, startField.get(rMulti1));
            assertEquals(5, endField.get(rMulti1));

            Object rMulti2 = method.invoke(extractor, "الآيات 10 – 15");
            assertNotNull(rMulti2);
            assertEquals(10, startField.get(rMulti2));
            assertEquals(15, endField.get(rMulti2));

        } catch (Exception e) {
            fail("Failed to test parseAyahNumber: " + e.getMessage());
        }
    }

    @Test
    void testArabicPatterns_SingleAyahPattern() {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\\u0627\\u0644\\u0622\\u064A\\u0629\\s+(\\d+)");
        java.util.regex.Matcher matcher = pattern.matcher("الآية 7");
        assertTrue(matcher.find());
        assertEquals("7", matcher.group(1));
    }

    @Test
    void testArabicPatterns_MultiAyahPattern() {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\\u0627\\u0644\\u0622\\u064A\\u0627\\u062A\\s+(\\d+)\\s*[\\-–—]\\s*(\\d+)");
        java.util.regex.Matcher matcher = pattern.matcher("الآيات 1 - 5");
        assertTrue(matcher.find());
        assertEquals("1", matcher.group(1));
        assertEquals("5", matcher.group(2));
    }
}
