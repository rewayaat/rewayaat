package com.rewayaat.core.data;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HadithObjectTest {

    @Test
    void insertEnglishText_appendsWhenNull() {
        HadithObject hadith = new HadithObject();
        hadith.insertEnglishText("hello");
        assertEquals("hello", hadith.getEnglish());
    }

    @Test
    void insertArabicText_appendsWithSpaceWhenNull() {
        HadithObject hadith = new HadithObject();
        hadith.insertArabicText("\u0633\u0644\u0627\u0645");
        assertEquals(" \u0633\u0644\u0627\u0645", hadith.getArabic());
    }

    @Test
    void insertHistoryNote_addsEntries() {
        HadithObject hadith = new HadithObject();
        hadith.insertHistoryNote("note");
        assertEquals(Collections.singletonList("note"), hadith.getHistory());
    }

    @Test
    void equals_comparesKeyFields() {
        HadithObject first = new HadithObject();
        HadithObject second = new HadithObject();

        first.setBook("book");
        second.setBook("book");
        first.setNumber("1");
        second.setNumber("1");
        first.setChapter("chapter");
        second.setChapter("chapter");
        first.setSection("section");
        second.setSection("section");
        first.setSource("source");
        second.setSource("source");
        first.setEnglish("english");
        second.setEnglish("english");
        first.setVolume("volume");
        second.setVolume("volume");
        first.setPart("part");
        second.setPart("part");
        first.setArabic("\u0633\u0644\u0627\u0645");
        second.setArabic("\u0633\u0644\u0627\u0645");

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }
}
