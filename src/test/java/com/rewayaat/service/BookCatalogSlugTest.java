package com.rewayaat.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slugs are public URLs, so they are load-bearing: a change here silently 404s every
 * book and chapter page Google has indexed. These pin the shape rather than the
 * implementation.
 */
class BookCatalogSlugTest {

    /**
     * Book titles are transliterated Arabic full of combining marks. Stripping them
     * gives the plain-ASCII spelling people type and link to.
     */
    @Test
    void slugify_stripsTransliterationDiacritics() {
        assertEquals("al-kafi", BookCatalog.slugify("Al-Kāfi"));
        assertEquals("man-la-yahduruh-al-faqih", BookCatalog.slugify("Man Lā Yaḥḍuruh al-Faqīh"));
        assertEquals("nahj-al-balagha", BookCatalog.slugify("Nahj al-Balāgha"));
        assertEquals("uyun-akhbar-al-rida", BookCatalog.slugify("ʿUyūn akhbār al-Riḍā"));
        assertEquals("al-khisal", BookCatalog.slugify("Al-Khiṣāl"));
    }

    /**
     * book_blurbs.json spells its books differently from Elasticsearch; the two are
     * matched on the slug, so the two spellings have to converge on it.
     */
    @Test
    void slugify_foldsTheBlurbFileSpellingOntoTheIndexSpelling() {
        assertEquals(BookCatalog.slugify("Al-Kāfi"), BookCatalog.slugify("AL-KAFI"));
        assertEquals(BookCatalog.slugify("Nahj al-Balāgha"), BookCatalog.slugify("Nahj Al-Balagha"));
        assertEquals(BookCatalog.slugify("Kāmil al-Ziyārāt"), BookCatalog.slugify("Kamil Al-Ziyarat"));
    }

    @Test
    void slugify_producesUrlSafeOutput() {
        String slug = BookCatalog.slugify("The Imams (a.s.) are the Only True Guides — 'Chapter 3'");

        assertTrue(slug.matches("[a-z0-9-]+"), "slug had unsafe characters: " + slug);
        assertTrue(!slug.startsWith("-") && !slug.endsWith("-"), "slug had dangling separators: " + slug);
    }

    /** Chapter titles run long; an unbounded slug makes an unusable URL. */
    @Test
    void slugify_capsLengthWithoutLeavingATrailingSeparator() {
        String slug = BookCatalog.slugify("Enlightening points deduced from the Holy Quran about "
                + "leadership with divine authority and the necessity of obedience to the Imams");

        assertTrue(slug.length() <= 80, "slug was " + slug.length() + " chars: " + slug);
        assertTrue(!slug.endsWith("-"), "slug ended with a separator: " + slug);
    }

    @Test
    void slugify_handlesNullAndEmpty() {
        assertEquals("", BookCatalog.slugify(null));
        assertEquals("", BookCatalog.slugify("   "));
        assertEquals("", BookCatalog.slugify("!!!"));
    }
}
