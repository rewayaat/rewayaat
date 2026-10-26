package com.rewayaat.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The years behind the thirteen marks: what the preview on a narrator's page draws its axis
 * from, and the things a reader would see go wrong if the chronology were misread.
 */
class ErasTest {

    private final Eras eras = new Eras();

    @Test
    void oneSpanForEachOfTheThirteenMarksInOrder() {
        List<Map<String, Object>> rows = eras.forPage(false);

        assertEquals(13, rows.size(), "the Prophet and the twelve Imams, as the strip has them");
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(i, rows.get(i).get("order"), "the preview finds a mark's era by its ordinal");
        }
    }

    @Test
    void eachImamateBeginsWhereTheOneBeforeItEnded() {
        // A gap or an overlap would draw as a seam on the upper lane, and would make the card
        // say a man's years «meet» an Imam's when the record has them a year apart.
        List<Map<String, Object>> rows = eras.forPage(false);
        for (int i = 1; i < rows.size(); i++) {
            assertEquals(rows.get(i - 1).get("to"), rows.get(i).get("from"));
        }
        assertEquals(114, rows.get(6).get("from"));
        assertEquals(148, rows.get(6).get("to"));
    }

    @Test
    void anArabicPageNamesTheEraInArabicAndAnEnglishOneKeepsTheArabicBeside() {
        Map<String, Object> sadiqAr = eras.forPage(true).get(6);
        Map<String, Object> sadiqEn = eras.forPage(false).get(6);

        assertEquals("الإمام الصادق (ع)", sadiqAr.get("name"));
        assertEquals("الصادق", sadiqAr.get("short"));
        assertTrue(String.valueOf(sadiqEn.get("name")).contains("al-Ṣādiq"));
        assertEquals("الإمام الصادق (ع)", sadiqEn.get("nameAr"), "the card's ornament on an English page");
    }
}
