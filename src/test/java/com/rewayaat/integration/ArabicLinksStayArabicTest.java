package com.rewayaat.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reader who enters the Arabic site stays on it until they choose not to.
 *
 * <p>Every link that forgets its prefix is a trapdoor: the reader clicks something
 * ordinary — a tag, the privacy link, the logo — and lands on the English site with no
 * indication anything happened. They were found one at a time by clicking around, which
 * is the wrong way to find nineteen of them, so this walks the pages instead.
 *
 * <p>Two things are allowed out. The language toggle points at the other site by
 * definition, and is exempted by its {@code data-set-locale} attribute rather than by
 * its href. And a handful of paths have no Arabic version at all, listed below; a link
 * to one of those is correct English rather than a leak.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ArabicLinksStayArabicTest extends ElasticsearchTestSupport {

    private static final List<String> PAGES = List.of(
            "/ar/", "/ar/books", "/ar/signin.html", "/ar/privacy", "/ar/updates.html");

    /** Paths with no Arabic twin, where an English link is the right answer. */
    private static final Pattern NO_ARABIC_VERSION = Pattern.compile(
            "^/(search_tips\\.html|swagger-ui|v1/|api/|img/|css/|js/|auth/|mcp|sitemap|robots)");

    private static final Pattern ANCHOR = Pattern.compile("<a\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HREF = Pattern.compile("href=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    @Autowired
    private TestRestTemplate restTemplate;


    @Test
    @DisplayName("no link on an Arabic page drops the reader onto the English site")
    void everyInternalLinkKeepsThePrefix() {
        Set<String> leaks = new TreeSet<>();

        for (String page : PAGES) {
            String html = restTemplate.getForObject(page, String.class);
            if (html == null) {
                continue;
            }
            for (String href : internalLinks(html)) {
                if (href.equals("/ar") || href.startsWith("/ar/")
                        || NO_ARABIC_VERSION.matcher(href).find()) {
                    continue;
                }
                leaks.add(page + "  ->  " + href);
            }
        }

        assertEquals(Set.of(), leaks,
                "these links leave the Arabic site:\n" + String.join("\n", leaks)
                        + "\nGive them the prefix — th:href=\"@{${arPrefix} ?: '' } + '/path'\" in a "
                        + "template, locale.prefix() in Java, localeHref() in the browser.");
    }

    /** Site-relative hrefs, minus the language toggle, which is meant to leave. */
    private static List<String> internalLinks(String html) {
        List<String> found = new ArrayList<>(new LinkedHashSet<>());
        Matcher anchor = ANCHOR.matcher(html);
        while (anchor.find()) {
            String attributes = anchor.group(1);
            if (attributes.contains("data-set-locale")) {
                continue;
            }
            Matcher href = HREF.matcher(attributes);
            if (!href.find()) {
                continue;
            }
            String value = href.group(1);
            if (value.startsWith("/") && !value.startsWith("//")) {
                found.add(value);
            }
        }
        return found;
    }

    @Test
    @DisplayName("the Arabic pages this walks are actually being served")
    void thePagesExist() {
        // Without this the test above passes just as well against five 404s.
        for (String page : PAGES) {
            String html = restTemplate.getForObject(page, String.class);
            assertTrue(html != null && html.contains("dir=\"rtl\""),
                    page + " did not render as an Arabic page");
        }
    }

    @Test
    @DisplayName("no link carries the language twice")
    void noLinkIsDoublePrefixed() {
        // The mirror of the sweep above. That one catches a link that forgot the prefix;
        // this catches one that got it twice, which is the same mistake made from the
        // other side and fails just as quietly — the href looks plausible, and nothing
        // reports it until a reader clicks and gets a 404.
        Set<String> doubled = new TreeSet<>();
        int seen = 0;
        for (String page : PAGES) {
            String html = restTemplate.getForObject(page, String.class);
            if (html == null) {
                continue;
            }
            for (String href : internalLinks(html)) {
                seen++;
                if (href.equals("/ar/ar") || href.startsWith("/ar/ar/")) {
                    doubled.add(page + "  ->  " + href);
                }
            }
        }
        assertTrue(seen > 0, "no links were found on any Arabic page, so this checked nothing");
        assertEquals(Set.of(), doubled,
                "these links carry the language twice and resolve to nothing:\n"
                        + String.join("\n", doubled)
                        + "\nA url in the model is bare and the template adds ${arPrefix}; "
                        + "one of the two did it and the other did it again.");
    }

    @Test
    @DisplayName("a canonical carries the language exactly once")
    void canonicalsAreNotDoublePrefixed() {
        // A page whose canonical folds elsewhere builds it from two sources — a bare
        // catalogue path, or a card's url, which carries the prefix already. Getting that
        // wrong publishes /ar/ar/..., which resolves to nothing and tells a crawler the
        // Arabic page should not be indexed at all. Neither the link sweep nor the
        // catalogue tests see it, because it lives in a <link>, not in an <a>.
        Set<String> wrong = new TreeSet<>();
        int seen = 0;
        for (String page : List.of(
                "/ar/", "/ar/books", "/ar/books/al-kafi", "/ar/books/al-khisal",
                "/ar/books/al-khisal/part/introduction",
                "/ar/books/al-kafi/part/the-book-on-virtue-of-knowledge",
                "/ar/privacy", "/ar/updates.html", "/ar/signin.html")) {
            String html = restTemplate.getForObject(page, String.class);
            if (html == null) {
                continue;
            }
            Matcher canonical = Pattern.compile(
                    "rel=\"canonical\"\\s+href=\"([^\"]*)\"").matcher(html);
            while (canonical.find()) {
                seen++;
                String href = canonical.group(1);
                if (href.contains("/ar/ar/") || href.endsWith("/ar/ar")) {
                    wrong.add(page + "  ->  " + href);
                }
            }
        }
        assertTrue(seen > 0, "no canonical was found on any Arabic page, so this checked nothing");
        assertEquals(Set.of(), wrong,
                "these canonicals carry the language twice:\n" + String.join("\n", wrong));
    }
}
