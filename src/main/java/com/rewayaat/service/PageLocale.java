package com.rewayaat.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.ui.Model;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The language a page is being served in, and every URL that follows from it.
 *
 * <p>The Arabic site is the same pages under an {@code /ar} prefix. That prefix is the
 * only thing that decides the language: not a cookie, not {@code Accept-Language}. A
 * cookie would make the language a function of who is asking rather than what was asked
 * for, which breaks two things at once - a cache in front of the application would serve
 * one visitor's language to the next, and a visitor who once opened an Arabic page would
 * see Arabic on English URLs, which is the duplicate-content signal invariant 2 exists to
 * prevent.
 *
 * <p><strong>Why this class exists at all.</strong> {@code canonicalUrl} is a model
 * attribute built by hand in each controller as {@code BASE_URL + "/books/" + slug}. The
 * Arabic request reaches that same controller, so without somewhere to put the prefix
 * every Arabic page would name the English URL as its canonical - telling Google the
 * English page is the real one and removing the entire Arabic tree from the index. Nothing
 * would fail; the pages would simply never rank. Canonicals and alternates are therefore
 * built together, here, from one locale-independent path.
 *
 * <p>The path handed in is always the English-slugged one. Slugs are public URLs
 * (invariant 4) and {@code BookCatalog.slugify} already strips transliteration diacritics,
 * so {@code /ar/books/man-la-yahduruh-al-faqih} reuses the spelling people type. The
 * Arabic title belongs in the page's text, where the ranking signal is, not in its URL.
 */
public enum PageLocale {

    ENGLISH("en", "", "ltr", Locale.ENGLISH),
    ARABIC("ar", "/ar", "rtl", Locale.forLanguageTag("ar"));

    /**
     * The one host canonicals are allowed to name (invariant 2).
     *
     * <p>rewayaat.info mirrors this application. A relative canonical made the mirror
     * declare itself canonical and split the site in two, so every canonical is absolute
     * and on this host whichever host served the request.
     */
    public static final String BASE_URL = "https://hadith.academyofislam.com";

    /** Request attribute carrying the locale across the forward that strips the prefix. */
    public static final String REQUEST_ATTRIBUTE = PageLocale.class.getName();

    /** The URL prefix that selects Arabic. */
    public static final String ARABIC_PREFIX = "/ar";

    private final String tag;
    private final String prefix;
    private final String direction;
    private final Locale locale;

    PageLocale(String tag, String prefix, String direction, Locale locale) {
        this.tag = tag;
        this.prefix = prefix;
        this.direction = direction;
        this.locale = locale;
    }

    /** The BCP 47 tag, for {@code <html lang>} and {@code hreflang}. */
    public String tag() {
        return tag;
    }

    /** {@code ""} for English, {@code "/ar"} for Arabic. */
    public String prefix() {
        return prefix;
    }

    /** For {@code <html dir>}. */
    public String direction() {
        return direction;
    }

    public Locale locale() {
        return locale;
    }

    public boolean isArabic() {
        return this == ARABIC;
    }

    /**
     * The locale this request is being served in, English unless the prefix said otherwise.
     *
     * <p>Reads the attribute rather than the URI because the prefix is stripped by a
     * forward before any controller runs, so by then the URI no longer mentions it.
     */
    public static PageLocale of(HttpServletRequest request) {
        if (request == null) {
            return ENGLISH;
        }
        Object marked = request.getAttribute(REQUEST_ATTRIBUTE);
        return marked instanceof PageLocale value ? value : ENGLISH;
    }

    /** Whether {@code uri} is under the Arabic prefix. */
    public static boolean isArabicPath(String uri) {
        return uri != null && (uri.equals(ARABIC_PREFIX) || uri.startsWith(ARABIC_PREFIX + "/"));
    }

    /**
     * Whether a page exists in Arabic at all.
     *
     * <p>The Arabic site is the home page and the book hub tree: the pages that answer
     * the phrases someone types in Arabic, and the ones whose text is drawn from the
     * translated labels. The 32,519 narration pages are deliberately not included. Their
     * body is the narration, which is already Arabic on the English page, so an Arabic
     * version would be the same text at a second URL - and it would double a URL count
     * that Search Console is already reporting as more than it will index.
     *
     * <p>Anything else under the prefix answers 404 rather than rendering half-translated:
     * a page that does not exist should say so, not enter the index and dilute the pages
     * that do.
     */
    public static boolean hasArabicVersion(String strippedPath) {
        if (strippedPath == null) {
            return false;
        }
        return strippedPath.equals("/")
                || strippedPath.equals("/books")
                || strippedPath.startsWith("/books/");
    }

    /**
     * {@code uri} with the Arabic prefix removed, for forwarding to the real handler.
     * {@code /ar} and {@code /ar/} both become {@code /}.
     */
    public static String stripArabicPrefix(String uri) {
        if (!isArabicPath(uri)) {
            return uri;
        }
        String stripped = uri.substring(ARABIC_PREFIX.length());
        return stripped.isEmpty() ? "/" : stripped;
    }

    /** The absolute URL of {@code path} in this locale. Always on the canonical host. */
    public String urlFor(String path) {
        return BASE_URL + prefix + normalise(path);
    }

    /**
     * Every language this page exists in, keyed by {@code hreflang} value.
     *
     * <p>Both entries appear on both pages, each pointing at the other and at itself:
     * Google discards a one-sided pair. {@code x-default} names English, which is what a
     * visitor with no matching language should land on.
     */
    public static Map<String, String> alternatesFor(String path) {
        Map<String, String> alternates = new LinkedHashMap<>();
        alternates.put(ENGLISH.tag, ENGLISH.urlFor(path));
        alternates.put(ARABIC.tag, ARABIC.urlFor(path));
        alternates.put("x-default", ENGLISH.urlFor(path));
        return alternates;
    }

    /**
     * Publishes this page's canonical, its alternates and its text direction.
     *
     * <p>Controllers call this instead of setting {@code canonicalUrl} by hand, so that a
     * page cannot acquire a canonical without also declaring which languages it exists in.
     *
     * @param path the locale-independent, English-slugged path, e.g. {@code /books/al-kafi}
     */
    public void applyTo(Model model, String path) {
        model.addAttribute("canonicalUrl", urlFor(path));
        model.addAttribute("hreflangAlternates", alternatesFor(path));
        model.addAttribute("htmlLang", tag);
        model.addAttribute("htmlDir", direction);
        model.addAttribute("isArabic", isArabic());
        // Every in-page link has to stay inside the language the visitor is reading.
        model.addAttribute("arPrefix", prefix);
    }

    private static String normalise(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }
}
