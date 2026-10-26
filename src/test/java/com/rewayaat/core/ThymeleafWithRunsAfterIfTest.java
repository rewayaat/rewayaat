package com.rewayaat.core;

import org.junit.jupiter.api.DisplayName;
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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Thymeleaf runs {@code th:if} before {@code th:with}, so a condition cannot read a variable
 * the same tag declares.
 *
 * <p>This is silent: the variable is simply null when the condition is evaluated, the element
 * is dropped, and the page renders without it. It cost the narrator page its «261 men stand
 * with him in the chains →» link on every narrator in the collection, and nothing failed —
 * the link was there in the template, the data was there in the document, and the page was a
 * 200. A screenshot found it.
 *
 * <p>The rule is the engine's published precedence: th:each (200), th:if / th:unless (300),
 * th:object / th:with (500), everything else after. A variable declared at 500 is readable by
 * th:attr and th:text on the same tag, and not by th:if.
 */
class ThymeleafWithRunsAfterIfTest {

    private static final Pattern DECLARED =
            Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*=");

    @Test
    @DisplayName("no th:if or th:unless reads a variable its own tag declares with th:with")
    void conditionsDoNotReadTheirOwnWith() throws IOException {
        Path templates = Path.of("src/main/resources/templates");
        List<String> found = new ArrayList<>();
        try (Stream<Path> pages = Files.walk(templates)) {
            for (Path page : pages.filter(p -> p.toString().endsWith(".html")).toList()) {
                String html = Files.readString(page, StandardCharsets.UTF_8);
                for (String tag : tags(html)) {
                    String with = attribute(tag, "th:with");
                    if (with == null) {
                        continue;
                    }
                    String condition = attribute(tag, "th:if");
                    if (condition == null) {
                        condition = attribute(tag, "th:unless");
                    }
                    if (condition == null) {
                        continue;
                    }
                    Matcher names = DECLARED.matcher(with);
                    while (names.find()) {
                        String name = names.group(1);
                        if (condition.matches(".*\\b" + Pattern.quote(name) + "\\b.*")) {
                            found.add(templates.relativize(page) + ": the condition reads «"
                                      + name + "», which th:with on the same tag declares");
                        }
                    }
                }
            }
        }
        assertTrue(found.isEmpty(),
                "A th:if cannot read a th:with of its own tag — Thymeleaf evaluates th:if "
                + "(precedence 300) first, so the variable is null and the element silently "
                + "disappears. Inline the expression in the condition instead.\n"
                + String.join("\n", found));
    }

    /** Opening tags, with quoted attribute values left alone — «chainMen &gt; 0» lives in one. */
    private static List<String> tags(String html) {
        List<String> tags = new ArrayList<>();
        int at = 0;
        while (at < html.length()) {
            int open = html.indexOf('<', at);
            if (open < 0 || open + 1 >= html.length()) {
                break;
            }
            char first = html.charAt(open + 1);
            if (!Character.isLetter(first)) {
                at = open + 1;
                continue;
            }
            int cursor = open + 1;
            char quote = 0;
            while (cursor < html.length()) {
                char here = html.charAt(cursor);
                if (quote != 0) {
                    if (here == quote) {
                        quote = 0;
                    }
                } else if (here == '"' || here == '\'') {
                    quote = here;
                } else if (here == '>') {
                    break;
                }
                cursor++;
            }
            tags.add(html.substring(open, Math.min(cursor + 1, html.length())));
            at = cursor + 1;
        }
        return tags;
    }

    private static String attribute(String tag, String name) {
        Matcher found = Pattern.compile(Pattern.quote(name) + "\\s*=\\s*\"([^\"]*)\"",
                                        Pattern.DOTALL).matcher(tag);
        return found.find() ? found.group(1) : null;
    }
}
