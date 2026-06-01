package com.rewayaat.tafsir;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Arabic surah name resolution in SurahNameResolver.
 * Verifies that Arabic surah names are correctly mapped to numbers.
 */
class SurahNameResolverArabicTest {

    @Test
    void testResolve_ArabicNameAlFatiha_Returns1() {
        Integer result = SurahNameResolver.resolve("الفاتحة");
        assertEquals(1, result);
    }

    @Test
    void testResolve_ArabicNameAlBaqarah_Returns2() {
        Integer result = SurahNameResolver.resolve("البقرة");
        assertEquals(2, result);
    }

    @Test
    void testResolve_ArabicNameAlImran_Returns3() {
        Integer result = SurahNameResolver.resolve("آل عمران");
        assertEquals(3, result);
    }

    @Test
    void testResolve_ArabicNameAnNisa_Returns4() {
        Integer result = SurahNameResolver.resolve("النساء");
        assertEquals(4, result);
    }

    @Test
    void testResolve_ArabicNameAlMaidah_Returns5() {
        Integer result = SurahNameResolver.resolve("المائدة");
        assertEquals(5, result);
    }

    @ParameterizedTest
    @CsvSource({
            "الفاتحة, 1",
            "البقرة, 2",
            "آل عمران, 3",
            "النساء, 4",
            "المائدة, 5",
            "الأنعام, 6",
            "الأعراف, 7",
            "الأنفال, 8",
            "التوبة, 9",
            "يونس, 10",
            "هود, 11",
            "يوسف, 12",
            "الرعد, 13",
            "إبراهيم, 14",
            "الحجر, 15",
            "النحل, 16",
            "الإسراء, 17",
            "الكهف, 18",
            "مريم, 19",
            "طه, 20",
            "الأنبياء, 21",
            "الحج, 22",
            "المؤمنون, 23",
            "النور, 24",
            "الفرقان, 25",
            "الشعراء, 26",
            "النمل, 27",
            "القصص, 28",
            "العنكبوت, 29",
            "الروم, 30",
            "لقمان, 31",
            "السجدة, 32",
            "الأحزاب, 33",
            "سبأ, 34",
            "فاطر, 35",
            "يس, 36",
            "الصافات, 37",
            "ص, 38",
            "الزمر, 39",
            "غافر, 40",
            "فصلت, 41",
            "الشورى, 42",
            "الزخرف, 43",
            "الدخان, 44",
            "الجاثية, 45",
            "الأحقاف, 46",
            "محمد, 47",
            "الفتح, 48",
            "الحجرات, 49",
            "ق, 50",
            "الذاريات, 51",
            "الطور, 52",
            "النجم, 53",
            "القمر, 54",
            "الرحمن, 55",
            "الواقعة, 56",
            "الحديد, 57",
            "المجادلة, 58",
            "الحشر, 59",
            "الممتحنة, 60",
            "الصف, 61",
            "الجمعة, 62",
            "المنافقون, 63",
            "التغابن, 64",
            "الطلاق, 65",
            "التحريم, 66",
            "الملك, 67",
            "القلم, 68",
            "الحاقة, 69",
            "المعارج, 70",
            "نوح, 71",
            "الجن, 72",
            "المزمل, 73",
            "المدثر, 74",
            "القيامة, 75",
            "الإنسان, 76",
            "المرسلات, 77",
            "النبأ, 78",
            "النازعات, 79",
            "عبس, 80",
            "التكوير, 81",
            "الانفطار, 82",
            "المطففين, 83",
            "الانشقاق, 84",
            "البروج, 85",
            "الطارق, 86",
            "الأعلى, 87",
            "الغاشية, 88",
            "الفجر, 89",
            "البلد, 90",
            "الشمس, 91",
            "الليل, 92",
            "الضحى, 93",
            "الشرح, 94",
            "التين, 95",
            "العلق, 96",
            "القدر, 97",
            "البينة, 98",
            "الزلزلة, 99",
            "العاديات, 100",
            "القارعة, 101",
            "التكاثر, 102",
            "العصر, 103",
            "الهمزة, 104",
            "الفيل, 105",
            "قريش, 106",
            "الماعون, 107",
            "الكوثر, 108",
            "الكافرون, 109",
            "النصر, 110",
            "المسد, 111",
            "الإخلاص, 112",
            "الفلق, 113",
            "الناس, 114"
    })
    void testResolve_AllArabicNames_ReturnsCorrectNumber(String arabicName, int expected) {
        Integer result = SurahNameResolver.resolve(arabicName);
        assertEquals(expected, result, "Arabic name '" + arabicName + "' should resolve to " + expected);
    }

    @Test
    void testResolve_ArabicNameWithSurahPrefix_ReturnsCorrect() {
        Integer result = SurahNameResolver.resolve("سورة الفاتحة");
        assertEquals(1, result);
    }

    @Test
    void testResolve_ArabicNameWithoutAl_ReturnsCorrect() {
        Integer result = SurahNameResolver.resolve("حمد");
        assertEquals(1, result);
    }

    @Test
    void testResolve_ArabicNameWithDiacritics_ReturnsCorrect() {
        // With tanween and other diacritics
        Integer result = SurahNameResolver.resolve("سُورَةُ الـحَمْـد");
        assertEquals(1, result, "Should handle Arabic with diacritics");
    }

    @Test
    void testResolve_ArabicNameWithTatweel_ReturnsCorrect() {
        // With tatweel (stretching character)
        Integer result = SurahNameResolver.resolve("الــــفــاتــحــة");
        assertEquals(1, result, "Should handle Arabic with tatweel");
    }

    @Test
    void testResolve_EnglishTransliteration_StillWorks() {
        // Ensure English names still work after adding Arabic support
        assertEquals(1, SurahNameResolver.resolve("Al-Fatiha"));
        assertEquals(2, SurahNameResolver.resolve("Al-Baqarah"));
        assertEquals(3, SurahNameResolver.resolve("Ali Imran"));
        assertEquals(36, SurahNameResolver.resolve("Ya-Sin"));
        assertEquals(112, SurahNameResolver.resolve("Al-Ikhlas"));
    }

    @Test
    void testResolve_InvalidArabicName_ReturnsNull() {
        Integer result = SurahNameResolver.resolve("سورة غير موجودة");
        assertNull(result, "Invalid Arabic name should return null");
    }

    @Test
    void testResolve_EmptyString_ReturnsNull() {
        Integer result = SurahNameResolver.resolve("");
        assertNull(result);
    }

    @Test
    void testResolve_Null_ReturnsNull() {
        Integer result = SurahNameResolver.resolve(null);
        assertNull(result);
    }

    @Test
    void testIsSurahName_ArabicNames_ReturnsTrue() {
        assertTrue(SurahNameResolver.isSurahName("الفاتحة"));
        assertTrue(SurahNameResolver.isSurahName("البقرة"));
        assertTrue(SurahNameResolver.isSurahName("الناس"));
    }

    @Test
    void testIsSurahName_InvalidArabicNames_ReturnsFalse() {
        assertFalse(SurahNameResolver.isSurahName("غير موجودة"));
        assertFalse(SurahNameResolver.isSurahName("سورة"));
        assertFalse(SurahNameResolver.isSurahName(""));
    }

    // Test some alternative Arabic names
    @Test
    void testResolve_AlternativeArabicNames() {
        // Al-Hamd (alternative for Al-Fatiha)
        assertEquals(1, SurahNameResolver.resolve("الحمد"));
        assertEquals(1, SurahNameResolver.resolve("سورة الحمد"));

        // Yasin (sometimes written with different spellings)
        assertEquals(36, SurahNameResolver.resolve("يس"));

        // Al-Mu'min (alternative for Ghafir)
        assertEquals(40, SurahNameResolver.resolve("المؤمن"));

        // Ad-Dahr (alternative for Al-Insan)
        assertEquals(76, SurahNameResolver.resolve("الدهر"));

        // Al-Lahab (alternative for Al-Masad)
        assertEquals(111, SurahNameResolver.resolve("اللهب"));

        // Tabbat (alternative for Al-Masad)
        assertEquals(111, SurahNameResolver.resolve("تبت"));
    }
}
