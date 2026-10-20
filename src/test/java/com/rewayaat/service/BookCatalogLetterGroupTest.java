package com.rewayaat.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A long book with no volume or part is browsed by letter. These pin when that layer
 * appears and where a chapter files, because the letter URLs are public once listed.
 */
class BookCatalogLetterGroupTest {

    private static BookCatalog.Chapter chapter(String title, String volume, String part) {
        return new BookCatalog.Chapter("Ghurar al-Ḥikam", "ghurar-al-hikam",
                BookCatalog.slugify(title), title, volume, part, null, 3);
    }

    private static BookCatalog.Book flatBook(List<String> titles) {
        List<BookCatalog.Chapter> chapters = new ArrayList<>();
        for (String t : titles) {
            chapters.add(chapter(t, "1", null));
        }
        return new BookCatalog.Book("Ghurar al-Ḥikam", "ghurar-al-hikam", chapters.size() * 3L, chapters);
    }

    private static List<String> titles(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add("Topic " + i);
        }
        return out;
    }

    @Test
    void aShortFlatBookStaysOneList() {
        assertTrue(flatBook(titles(BookCatalog.LETTER_GROUP_MIN_CHAPTERS)).letterGroups().isEmpty());
    }

    @Test
    void aBookWithParts_isNeverLettered() {
        List<BookCatalog.Chapter> chapters = new ArrayList<>();
        for (int i = 0; i < BookCatalog.LETTER_GROUP_MIN_CHAPTERS + 10; i++) {
            chapters.add(chapter("Topic " + i, "1", i % 2 == 0 ? "Part One" : "Part Two"));
        }
        BookCatalog.Book book = new BookCatalog.Book("Al-Khiṣāl", "al-khisal", chapters.size(), chapters);

        assertTrue(book.letterGroups().isEmpty());
    }

    @Test
    void aLeadingArticleIsPassedOver_andAccentsFold() {
        List<String> titles = titles(BookCatalog.LETTER_GROUP_MIN_CHAPTERS);
        titles.add("The Intellect");
        titles.add("Ḥope");
        titles.add("An Oath");
        titles.add("The [Allotted] Share");
        BookCatalog.Book book = flatBook(titles);

        assertTrue(book.letterGroup("a").orElseThrow().chapters().stream()
                .anyMatch(c -> c.title().equals("The [Allotted] Share")));
        assertTrue(book.letterGroup("0-9").isEmpty());

        assertTrue(book.letterGroup("i").orElseThrow().chapters().stream()
                .anyMatch(c -> c.title().equals("The Intellect")));
        assertTrue(book.letterGroup("h").orElseThrow().chapters().stream()
                .anyMatch(c -> c.title().equals("Ḥope")));
        assertTrue(book.letterGroup("o").isPresent());
        assertTrue(book.letterGroup("t").orElseThrow().chapters().stream()
                .allMatch(c -> c.title().startsWith("Topic")));
    }

    @Test
    void groupsRunAToZ_andChaptersFileInOrderWithinALetter() {
        List<String> titles = titles(BookCatalog.LETTER_GROUP_MIN_CHAPTERS);
        titles.add("Zeal");
        titles.add("The Believer");
        titles.add("Abandoning");
        titles.add("Backbiting");
        BookCatalog.Book book = flatBook(titles);
        List<BookCatalog.LetterGroup> groups = book.letterGroups();

        assertEquals("a", groups.get(0).key());
        assertEquals("z", groups.get(groups.size() - 1).key());
        assertEquals(List.of("Backbiting", "The Believer"),
                book.letterGroup("b").orElseThrow().chapters().stream().map(BookCatalog.Chapter::title).toList());
        assertEquals("/books/ghurar-al-hikam/letter/b", book.letterGroup("b").orElseThrow().url());
        assertEquals("B", book.letterGroup("b").orElseThrow().label());
    }

    @Test
    void anUnknownLetterIsAbsent() {
        assertTrue(flatBook(titles(BookCatalog.LETTER_GROUP_MIN_CHAPTERS + 1)).letterGroup("q").isEmpty());
    }
}
