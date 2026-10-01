package com.rewayaat.controllers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The language goes onto a URL once, and only one of the two halves may add it.
 *
 * <p>A link on an Arabic page is built in two places. The controller puts a path into the
 * model and the template turns it into an href, and either one of them can prepend
 * {@code /ar}. The convention is that the model holds a bare path and the template adds
 * the language with {@code ${arPrefix}} — which is what the parts on a book page do.
 *
 * <p>The volumes beside them did it twice. {@code bookPage} built the url with
 * {@code locale.prefix()} already on it and {@code book.html} added {@code ${arPrefix}}
 * as well, so every Arabic book page carried eight links to
 * {@code /ar/ar/books/<book>/volume/<n>}. Each one 404s. Nothing failed: the English page
 * was correct, the Arabic page rendered, the hrefs looked ordinary, and the integration
 * sweep that walks Arabic pages for bad links had never been given a book page to walk —
 * its test index has no books in it, so the URL was in the list and checked nothing.
 *
 * <p>This reads the pair instead. For every model attribute the template prefixes, the
 * controller must not have prefixed it. It needs no Elasticsearch, which is the point:
 * the runtime sweep cannot see these pages and this can.
 */
class ArabicPrefixIsAddedOnceTest {

    private static final Path CONTROLLER =
            Path.of("src/main/java/com/rewayaat/controllers/BookPageController.java");
    private static final Path TEMPLATE_DIR = Path.of("src/main/resources/templates");

    /** {@code th:each="volume : ${volumes}"} — the loop variable and its model attribute. */
    private static final Pattern LOOP = Pattern.compile(
            "th:each=\"\\s*(\\w+)\\s*:\\s*\\$\\{(\\w+)\\}");

    /** {@code ${arPrefix} + ${volume.url}} — captures the loop variable being prefixed. */
    private static final Pattern PREFIXED_IN_TEMPLATE = Pattern.compile(
            "\\$\\{arPrefix\\}\\s*\\+\\s*\\$\\{(\\w+)\\.url\\}");

    @Test
    @DisplayName("a url the template prefixes is not already prefixed by the controller")
    void theLanguageIsAddedOnce() throws IOException {
        // Model attributes whose url a template prefixes: volumes, parts, chapters.
        Set<String> prefixedAttributes = new LinkedHashSet<>();
        for (Path template : templates()) {
            String html = Files.readString(template, StandardCharsets.UTF_8);
            Map<String, String> attributeOf = new LinkedHashMap<>();
            Matcher loop = LOOP.matcher(html);
            while (loop.find()) {
                attributeOf.put(loop.group(1), loop.group(2));
            }
            Matcher prefixed = PREFIXED_IN_TEMPLATE.matcher(html);
            while (prefixed.find()) {
                String attribute = attributeOf.get(prefixed.group(1));
                if (attribute != null) {
                    prefixedAttributes.add(attribute);
                }
            }
        }
        assertFalse(prefixedAttributes.isEmpty(),
                "no template adds ${arPrefix} to a looped url any more, so this checked nothing");

        String controller = Files.readString(CONTROLLER, StandardCharsets.UTF_8);
        List<String> doubled = new ArrayList<>();
        for (String attribute : prefixedAttributes) {
            for (String block : additionsOf(controller, attribute)) {
                if (block.contains("\"url\", locale.prefix()")) {
                    doubled.add(attribute + ": " + block.replaceAll("\\s+", " ").trim());
                }
            }
        }

        assertTrue(doubled.isEmpty(),
                "a template adds ${arPrefix} to these model urls and the controller has "
                        + "already added the language itself, which publishes /ar/ar/…:\n  "
                        + String.join("\n  ", doubled)
                        + "\nLeave the path bare here and let the template add the language, "
                        + "the way the parts on a book page already do.");
    }

    /**
     * Every {@code model.addAttribute("name", …)} for one attribute, as source text.
     *
     * <p>Bounded by the next addAttribute rather than by brace matching: the value is
     * often a stream with lambdas in it, and counting braces through those is more
     * machinery than this needs.
     */
    private static List<String> additionsOf(String source, String attribute) {
        List<String> blocks = new ArrayList<>();
        Matcher start = Pattern.compile(
                "model\\.addAttribute\\(\\s*\"" + Pattern.quote(attribute) + "\"").matcher(source);
        while (start.find()) {
            int next = source.indexOf("model.addAttribute(", start.end());
            blocks.add(source.substring(start.start(), next < 0 ? source.length() : next));
        }
        return blocks;
    }

    private static List<Path> templates() throws IOException {
        try (var tree = Files.walk(TEMPLATE_DIR)) {
            return tree.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".html"))
                    .toList();
        }
    }
}
