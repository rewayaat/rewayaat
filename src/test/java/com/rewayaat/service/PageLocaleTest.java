package com.rewayaat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Arabic site's URL rules.
 *
 * <p>The first test is the one that matters. Every controller builds its canonical by
 * hand, and the Arabic request reaches the same controller, so the failure being guarded
 * against is an Arabic page quietly naming the English URL as canonical - which asks
 * Google to drop the Arabic page. Nothing about that fails loudly at runtime.
 */
class PageLocaleTest {

    @Test
    @DisplayName("an Arabic page is canonical to itself, never to the English page")
    void arabicPagesAreCanonicalToTheArabicUrl() {
        String canonical = PageLocale.ARABIC.urlFor("/books/al-kafi");

        assertEquals("https://hadith.academyofislam.com/ar/books/al-kafi", canonical);
        assertNotEquals(PageLocale.ENGLISH.urlFor("/books/al-kafi"), canonical,
                "an Arabic page canonical to the English URL is asking to be deindexed");
    }

    @Test
    @DisplayName("both languages name each other, and x-default is English")
    void alternatesArePairedAndAbsolute() {
        Map<String, String> alternates = PageLocale.alternatesFor("/books/al-kafi");

        assertEquals("https://hadith.academyofislam.com/books/al-kafi", alternates.get("en"));
        assertEquals("https://hadith.academyofislam.com/ar/books/al-kafi", alternates.get("ar"));
        assertEquals(alternates.get("en"), alternates.get("x-default"));

        // The same map is served on both pages: a one-sided hreflang pair is discarded.
        assertEquals(alternates, PageLocale.alternatesFor("/books/al-kafi"));
        alternates.values().forEach(url ->
                assertTrue(url.startsWith(PageLocale.BASE_URL + "/"),
                        "alternates are absolute and on the canonical host: " + url));
    }

    @Test
    @DisplayName("the prefix decides the language; nothing else does")
    void localeComesFromTheRequestAttributeOnly() {
        MockHttpServletRequest plain = new MockHttpServletRequest();
        plain.addHeader("Accept-Language", "ar");
        assertSame(PageLocale.ENGLISH, PageLocale.of(plain),
                "Accept-Language must not change which page is served");

        MockHttpServletRequest arabic = new MockHttpServletRequest();
        arabic.setAttribute(PageLocale.REQUEST_ATTRIBUTE, PageLocale.ARABIC);
        assertSame(PageLocale.ARABIC, PageLocale.of(arabic));

        assertSame(PageLocale.ENGLISH, PageLocale.of(null));
    }

    @Test
    @DisplayName("the prefix is recognised and stripped, including at the root")
    void prefixHandling() {
        assertTrue(PageLocale.isArabicPath("/ar"));
        assertTrue(PageLocale.isArabicPath("/ar/"));
        assertTrue(PageLocale.isArabicPath("/ar/books/al-kafi"));

        // A path that merely starts with the same letters is not the Arabic site.
        assertFalse(PageLocale.isArabicPath("/archive"));
        assertFalse(PageLocale.isArabicPath("/books/al-kafi"));

        assertEquals("/", PageLocale.stripArabicPrefix("/ar"));
        assertEquals("/", PageLocale.stripArabicPrefix("/ar/"));
        assertEquals("/books/al-kafi", PageLocale.stripArabicPrefix("/ar/books/al-kafi"));
        assertEquals("/archive", PageLocale.stripArabicPrefix("/archive"));
    }

    @Test
    @DisplayName("applyTo publishes the direction and prefix the templates need")
    void applyToPublishesEverythingATemplateNeeds() {
        Model model = new ExtendedModelMap();
        PageLocale.ARABIC.applyTo(model, "/books/al-kafi");

        assertEquals("https://hadith.academyofislam.com/ar/books/al-kafi",
                model.getAttribute("canonicalUrl"));
        assertEquals("ar", model.getAttribute("htmlLang"));
        assertEquals("rtl", model.getAttribute("htmlDir"));
        assertEquals(Boolean.TRUE, model.getAttribute("isArabic"));
        assertEquals("/ar", model.getAttribute("arPrefix"));

        Model english = new ExtendedModelMap();
        PageLocale.ENGLISH.applyTo(english, "/books/al-kafi");
        assertEquals("ltr", english.getAttribute("htmlDir"));
        assertEquals("", english.getAttribute("arPrefix"),
                "an empty prefix keeps English links on English URLs");

        // The toggle is relative; only the hreflang tags are absolute.
        assertEquals("/books/al-kafi", model.getAttribute("switchLocalePath"));
        assertEquals("en", model.getAttribute("switchLocaleTag"));
        assertEquals("/ar/books/al-kafi", english.getAttribute("switchLocalePath"));
        assertEquals("ar", english.getAttribute("switchLocaleTag"));
    }

    @Test
    @DisplayName("a stored language tag resolves, and anything unrecognised reads as English")
    void ofTagResolvesStoredPreferences() {
        assertEquals(PageLocale.ARABIC, PageLocale.ofTag("ar"));
        assertEquals(PageLocale.ARABIC, PageLocale.ofTag(" AR "));
        assertEquals(PageLocale.ENGLISH, PageLocale.ofTag("en"));

        // Accounts predate the preference, so absent has to mean English rather than
        // throwing or returning null into a mail-sending path.
        assertEquals(PageLocale.ENGLISH, PageLocale.ofTag(null));
        assertEquals(PageLocale.ENGLISH, PageLocale.ofTag(""));

        // A caller that needs to reject an unknown tag compares the round trip, which is
        // what stops "fr" being silently stored as English.
        assertNotEquals("fr", PageLocale.ofTag("fr").tag());
    }
}
