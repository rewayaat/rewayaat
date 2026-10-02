package com.rewayaat.controllers;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Academy's name, link and copyright may only render on the Academy's own host.
 *
 * <p>rewayaat.info carries the database's own branding; hadith.academyofislam.com keeps
 * the Academy's exactly as it was. The division is a {@code th:if="${academyHost}"} on
 * each element that names them, which is easy to forget - five templates carry their own
 * copy of the footer rather than sharing the fragment, so a sixth copy, or an edit to one
 * of the five, can reintroduce the branding on both hosts without anything failing.
 *
 * <p>Read as source rather than rendered, the way {@link IndexingSignalsTest} checks
 * wiring between files. The thing worth pinning is that the guard is present on every
 * element that names the Academy, which a rendered page shows only for the hosts the test
 * happens to ask about.
 */
class AcademyBrandingIsHostScopedTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    /** Anything that puts the Academy's name, link or copyright on the page. */
    private static final List<String> ACADEMY_MARKERS = List.of(
            "academyofislam.com",
            "#{footer.projectOf}",
            "#{footer.academy}",
            "#{footer.rights}",
            "#{footer.academyFull}",
            "#{site.footer.text(",
            "#{home.presentedBy(",
            "#{home.disclaimerBody}",
            "#{privacy.footnote(");

    private static final String GUARD = "${academyHost}";

    /** An opening tag, which may run over several lines, with its attributes. */
    private static final Pattern OPENING_TAG = Pattern.compile("<[a-zA-Z][a-zA-Z0-9]*\\s[^<>]*?/?>", Pattern.DOTALL);

    private static List<Path> templates() throws IOException {
        try (Stream<Path> paths = Files.walk(TEMPLATES)) {
            return paths.filter(p -> p.toString().endsWith(".html")).sorted().toList();
        }
    }

    /**
     * The spans of the document that only render on the Academy's host: each element whose
     * opening tag carries the guard, through its matching close. The guard usually sits on
     * a parent - the footer paragraph is guarded once and the link and spans inside it
     * inherit that - so membership, not the tag itself, is what decides.
     */
    private static final String IF_GUARD = "th:if=\"" + GUARD + "\"";

    /**
     * Walks out from each guard rather than matching whole tags with a pattern. An
     * attribute value here can itself contain markup - privacy.html builds a link inside
     * a {@code th:with} - so a tag is not "angle bracket to angle bracket", and a pattern
     * that assumes it is silently skips exactly the tags most worth checking.
     */
    private static int endOfOpeningTag(String html, int from) {
        boolean quoted = false;
        char quote = 0;
        for (int i = from; i < html.length(); i++) {
            char c = html.charAt(i);
            if (quoted) {
                if (c == quote) {
                    quoted = false;
                }
            } else if (c == '"' || c == '\'') {
                quoted = true;
                quote = c;
            } else if (c == '>') {
                return i + 1;
            }
        }
        return html.length();
    }

    private static List<int[]> guardedRegions(String html) {
        List<int[]> regions = new ArrayList<>();
        int at = html.indexOf(IF_GUARD);
        while (at >= 0) {
            int start = html.lastIndexOf('<', at);
            int openEnd = endOfOpeningTag(html, start);
            String tag = html.substring(start, openEnd);
            // A void element has no close, so it guards exactly itself - the Academy's
            // logo is an <img/>, and its alt text names them.
            if (tag.endsWith("/>")) {
                regions.add(new int[]{start, openEnd});
            } else {
                String name = tag.substring(1).split("[\\s>]", 2)[0];
                Matcher boundary = Pattern.compile("<" + name + "\\b|</" + name + ">").matcher(html);
                int depth = 0;
                boundary.region(start, html.length());
                while (boundary.find()) {
                    depth += boundary.group().startsWith("</") ? -1 : 1;
                    if (depth == 0) {
                        regions.add(new int[]{start, boundary.end()});
                        break;
                    }
                }
            }
            at = html.indexOf(IF_GUARD, at + 1);
        }
        return regions;
    }

    @Test
    void everyElementNamingTheAcademyIsGuardedByItsHost() throws IOException {
        List<String> unguarded = new ArrayList<>();
        for (Path template : templates()) {
            String html = Files.readString(template, StandardCharsets.UTF_8);
            List<int[]> guarded = guardedRegions(html);
            for (String marker : ACADEMY_MARKERS) {
                int at = html.indexOf(marker);
                while (at >= 0) {
                    int here = at;
                    boolean inside = guarded.stream().anyMatch(r -> here >= r[0] && here < r[1]);
                    if (!inside) {
                        int from = Math.max(0, here - 40);
                        unguarded.add(TEMPLATES.relativize(template) + " [" + marker + "]: ..."
                                + html.substring(from, Math.min(html.length(), here + 60))
                                        .replaceAll("\\s+", " "));
                    }
                    at = html.indexOf(marker, at + 1);
                }
            }
        }
        assertTrue(unguarded.isEmpty(),
                "These render the Academy's branding on every host. Each needs an enclosing th:if=\""
                        + GUARD + "\":\n" + String.join("\n", unguarded));
    }

    /**
     * The guard decides branding, never the canonical. Both hosts serve the same pages and
     * declare the same canonical, which is what lets the index consolidate; making the
     * canonical, the hreflang alternates or a robots directive depend on the host instead
     * would be two different sites, and to a crawler, cloaking.
     */
    @Test
    void theGuardNeverReachesIndexingSignals() throws IOException {
        List<String> leaked = new ArrayList<>();
        for (Path template : templates()) {
            String html = Files.readString(template, StandardCharsets.UTF_8);
            Matcher tags = OPENING_TAG.matcher(html);
            while (tags.find()) {
                String tag = tags.group();
                if (!tag.contains(GUARD)) {
                    continue;
                }
                boolean indexingSignal = tag.contains("rel=\"canonical\"")
                        || tag.contains("hreflang")
                        || tag.contains("name=\"robots\"")
                        || tag.contains("rel=\"alternate\"");
                if (indexingSignal) {
                    leaked.add(TEMPLATES.relativize(template) + ": " + tag.replaceAll("\\s+", " "));
                }
            }
        }
        assertTrue(leaked.isEmpty(),
                "An indexing signal must not vary by host - both hosts declare the same canonical:\n"
                        + String.join("\n", leaked));
    }

    /** The emblem is the database's mark and must never appear under the Academy's name. */
    @Test
    void theAcademyHostDoesNotCarryTheDatabaseEmblem() throws IOException {
        for (Path template : templates()) {
            String html = Files.readString(template, StandardCharsets.UTF_8);
            Matcher tags = OPENING_TAG.matcher(html);
            while (tags.find()) {
                String tag = tags.group();
                if (tag.contains("hdb-emblem")) {
                    assertFalse(tag.contains("th:if=\"" + GUARD + "\""),
                            TEMPLATES.relativize(template) + " shows the database emblem on the Academy's host");
                    assertTrue(tag.contains("th:unless=\"" + GUARD + "\""),
                            TEMPLATES.relativize(template) + " shows the database emblem on every host; it needs"
                                    + " th:unless=\"" + GUARD + "\"");
                }
            }
        }
    }
}
