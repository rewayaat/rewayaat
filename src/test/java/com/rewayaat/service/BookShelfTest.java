package com.rewayaat.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookShelfTest {

    private static BookCatalog.Book book(String slug, long count) {
        return new BookCatalog.Book(slug, slug, count, List.of());
    }

    @Test
    void largestCollectionStandsFirst() {
        List<BookShelf.Shelved> shelf = BookShelf.arrange(List.of(
                book("al-amali", 1469), book("al-kafi", 14242), book("fada-il-al-shi-a", 45)));

        assertEquals(List.of("al-kafi", "al-amali", "fada-il-al-shi-a"),
                shelf.stream().map(s -> s.book().slug()).toList());
    }

    @Test
    void aSmallerCollectionIsAShorterThinnerBook() {
        List<BookShelf.Shelved> shelf = BookShelf.arrange(List.of(book("al-kafi", 14242), book("fada-il-al-shi-a", 45)));

        assertTrue(scale(shelf.get(0)) > 0.97, shelf.get(0).style());
        assertTrue(scale(shelf.get(1)) < 0.9, shelf.get(1).style());
        assertTrue(edges(shelf.get(0)) > edges(shelf.get(1)));
        assertTrue(shelf.get(0).style().endsWith("--gilt:url(/img/books/gilt/al-kafi.webp)"));
    }

    /**
     * Every book the site serves today has a drawn cover. A book added later still
     * stands on the shelf, in plain leather, until scripts/book-covers draws one.
     */
    @Test
    void theCoversAreOnTheClasspath() {
        for (String slug : List.of("al-kafi", "man-la-yahduruh-al-faqih", "nahj-al-balagha", "al-amali",
                "al-khisal", "kitab-al-ghayba", "thawab-al-a-mal-wa-iqab-al-a-mal", "uyun-akhbar-al-rida",
                "ma-ani-al-akhbar", "kamil-al-ziyarat", "al-tawhid", "mu-jam-al-ahadith-al-mu-tabara",
                "kitab-al-zuhd", "kitab-al-du-afa", "kitab-al-mu-min", "sifat-al-shi-a", "risalat-al-huquq",
                "fada-il-al-shi-a")) {
            assertTrue(BookShelf.hasCover(slug), slug);
        }
        assertFalse(BookShelf.hasCover("a-book-not-yet-drawn"));
    }

    private static double scale(BookShelf.Shelved s) {
        return Double.parseDouble(s.style().substring("--scale:".length(), s.style().indexOf(';')));
    }

    private static long edges(BookShelf.Shelved s) {
        return s.style().chars().filter(c -> c == '#').count();
    }
}
