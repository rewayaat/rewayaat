package com.rewayaat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an Arabic reader of a narrator page is shown.
 *
 * <p>These pin the two things that go wrong quietly: an English book title left standing in
 * the middle of an Arabic page, and a death year turned into Arabic-Indic digits that the
 * chronology band can no longer parse back into a number.
 */
class NarratorArabicTest {

    private static Map<String, Object> zurarah() {
        Map<String, Object> person = new LinkedHashMap<>();
        person.put("blurb", "Zurarah kept company with al-Sadiq.");
        person.put("blurb_ar", "زرارة من أصحاب الصادق.");
        person.put("for_him", List.of("Rijāl al-Najāshī"));
        person.put("for_him_ar", List.of("رجال النجاشي"));
        person.put("life", Map.of("city", "Kufa", "city_ar", "الكوفة",
                "died", 150, "died_ar", "١٥٠",
                "imams", List.of("al-Ṣādiq"), "imams_ar", List.of("الصادق")));
        person.put("entries", List.of(Map.of(
                "book_title", "Rijāl al-Najāshī", "book_title_ar", "رجال النجاشي",
                "book_author", "al-Najāshī (d. 450)", "book_author_ar", "النجاشي (ت ٤٥٠)",
                "grade_said", "thiqa — trustworthy", "grade_said_ar", "ثقة",
                "under_imams", List.of("al-Ṣādiq"), "under_imams_ar", List.of("الصادق"),
                "page_first", 174)));
        person.put("where_books_stand", List.of(Map.of(
                "book_title", "Rijāl al-Najāshī", "book_title_ar", "رجال النجاشي",
                "positions", List.of(Map.of("said", "thiqa — trustworthy", "said_ar", "ثقة",
                        "grade", "thiqa")))));
        person.put("death_years", List.of(Map.of("value", 150,
                "book_title", "Rijāl al-Najāshī", "book_title_ar", "رجال النجاشي")));
        person.put("narrated_from", List.of(Map.of("name", "ابن أبي عمير",
                "books", List.of("Rijāl al-Najāshī"), "books_ar", List.of("رجال النجاشي"))));
        return person;
    }

    @Test
    @DisplayName("the English page is handed back untouched, and is the same object")
    void englishIsUnchanged() {
        Map<String, Object> person = zurarah();
        assertSame(person, NarratorArabic.localise(person, PageLocale.ENGLISH));
        assertEquals("Zurarah kept company with al-Sadiq.", person.get("blurb"));
    }

    @Test
    @DisplayName("the Arabic twins are moved into the fields the template reads")
    void arabicTwinsAreSwappedIn() {
        Map<String, Object> read = NarratorArabic.localise(zurarah(), PageLocale.ARABIC);

        assertEquals("زرارة من أصحاب الصادق.", read.get("blurb"));
        assertEquals(List.of("رجال النجاشي"), read.get("for_him"));
        assertEquals("الكوفة", asMap(read.get("life")).get("city"));
        assertEquals(List.of("الصادق"), asMap(read.get("life")).get("imams"));

        Map<String, Object> entry = firstOf(read.get("entries"));
        assertEquals("رجال النجاشي", entry.get("book_title"));
        assertEquals("النجاشي (ت ٤٥٠)", entry.get("book_author"));
        assertEquals("ثقة", entry.get("grade_said"));
        assertEquals(List.of("الصادق"), entry.get("under_imams"));

        // nested two deep: the book's row, and the position it takes inside that row
        Map<String, Object> stand = firstOf(read.get("where_books_stand"));
        assertEquals("رجال النجاشي", stand.get("book_title"));
        assertEquals("ثقة", firstOf(stand.get("positions")).get("said"));

        assertEquals("رجال النجاشي", firstOf(read.get("death_years")).get("book_title"));
        assertEquals(List.of("رجال النجاشي"), firstOf(read.get("narrated_from")).get("books"));
    }

    @Test
    @DisplayName("numbers are left alone: the chronology band reads them back as numbers")
    void numbersAreNotConverted() {
        Map<String, Object> read = NarratorArabic.localise(zurarah(), PageLocale.ARABIC);

        // A death year is what the band places his rosette by. «١٥٠» would not parse, and the
        // band would silently stop rendering - LocaleDigits converts it at the point of display.
        assertEquals(150, asMap(read.get("life")).get("died"));
        assertEquals(150, firstOf(read.get("death_years")).get("value"));
        assertEquals(174, firstOf(read.get("entries")).get("page_first"));
    }

    @Test
    @DisplayName("a missing Arabic twin leaves the English standing rather than a gap")
    void missingTwinFallsBackToEnglish() {
        Map<String, Object> person = new LinkedHashMap<>();
        person.put("blurb", "Nothing is recorded of him.");
        person.put("entries", List.of(Map.of("book_title", "Kitāb al-Ḍuʿafāʾ",
                // the grade is stated in English only, and blank rather than absent
                "grade_said", "daif — weak", "grade_said_ar", "  ")));

        Map<String, Object> read = NarratorArabic.localise(person, PageLocale.ARABIC);
        assertEquals("Nothing is recorded of him.", read.get("blurb"));
        assertEquals("Kitāb al-Ḍuʿafāʾ", firstOf(read.get("entries")).get("book_title"));
        assertEquals("daif — weak", firstOf(read.get("entries")).get("grade_said"));
    }

    @Test
    @DisplayName("the document handed in is never modified: the service may be caching it")
    void theSourceDocumentIsNotTouched() {
        Map<String, Object> person = zurarah();
        NarratorArabic.localise(person, PageLocale.ARABIC);

        assertEquals("Zurarah kept company with al-Sadiq.", person.get("blurb"));
        assertEquals("Rijāl al-Najāshī", firstOf(person.get("entries")).get("book_title"));
    }

    @Test
    @DisplayName("a document with none of these fields does not throw")
    void anEmptyDocumentIsSafe() {
        Map<String, Object> read = NarratorArabic.localise(Map.of("person_id", "n000488"),
                PageLocale.ARABIC);

        assertEquals("n000488", read.get("person_id"));
        assertTrue(((List<?>) read.get("entries")).isEmpty());
        assertTrue(((List<?>) read.get("where_books_stand")).isEmpty());
        assertNull(read.get("blurb"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstOf(Object rows) {
        return (Map<String, Object>) ((List<Object>) rows).get(0);
    }
}
