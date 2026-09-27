package com.rewayaat.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the one catalogue of strings a reader can see.
 *
 * <p>Every such string lives in {@code messages.properties} with a translation in
 * {@code messages_ar.properties}, and the site reads them through exactly three doors:
 * {@code #{key}} in a template, {@code t('key', …)} in browser code, and
 * {@code UiMessages} on the server. The catalogue only stays worth having if nothing
 * goes around it, and going around it is easy and invisible — the string renders fine
 * in English and nobody sees the Arabic page. Three sweeps of this codebase turned up
 * English hardcoded in templates, sitting inside JavaScript string literals where
 * Thymeleaf never looks, and returned from the API. These tests are what makes the next
 * one fail loudly instead.
 */
class MessageCatalogueTest {

    private static final Path ENGLISH = Path.of("src/main/resources/messages.properties");
    private static final Path ARABIC = Path.of("src/main/resources/messages_ar.properties");

    private static Properties load(Path path) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    @Test
    @DisplayName("every English string has an Arabic one, and the other way round")
    void theTwoBundlesHoldTheSameKeys() throws IOException {
        Set<String> english = new TreeSet<>(load(ENGLISH).stringPropertyNames());
        Set<String> arabic = new TreeSet<>(load(ARABIC).stringPropertyNames());

        Set<String> untranslated = new TreeSet<>(english);
        untranslated.removeAll(arabic);
        assertTrue(untranslated.isEmpty(),
                "these strings have no Arabic: " + untranslated
                        + "\nAdd them to messages_ar.properties. A key present in one bundle "
                        + "and not the other renders English on the Arabic site.");

        Set<String> orphaned = new TreeSet<>(arabic);
        orphaned.removeAll(english);
        assertTrue(orphaned.isEmpty(),
                "these Arabic strings have no English counterpart, so nothing reads them: "
                        + orphaned);
    }

    @Test
    @DisplayName("no string is left blank, and none is still the English text")
    void everyArabicStringIsActuallyArabic() throws IOException {
        Properties english = load(ENGLISH);
        Properties arabic = load(ARABIC);

        List<String> blank = new ArrayList<>();
        List<String> copied = new ArrayList<>();
        for (String key : arabic.stringPropertyNames()) {
            String value = arabic.getProperty(key).trim();
            if (value.isEmpty()) {
                blank.add(key);
                continue;
            }
            // A value identical to the English one is usually a placeholder somebody
            // meant to come back to. Two things are legitimately identical: a pattern
            // made only of placeholders and punctuation, which has no words to
            // translate, and the handful of strings listed below.
            boolean hasWords = value.codePoints().anyMatch(Character::isLetter);
            if (hasWords && value.equals(english.getProperty(key, "").trim())
                    && !DELIBERATELY_SHARED.contains(key)) {
                copied.add(key);
            }
        }
        assertTrue(blank.isEmpty(), "blank Arabic strings: " + blank);
        assertTrue(copied.isEmpty(),
                "these are still the English text: " + copied
                        + "\nTranslate them, or add the key to DELIBERATELY_SHARED with a reason.");
    }

    /** Strings that read the same in both languages on purpose. */
    private static final Set<String> DELIBERATELY_SHARED = Set.of(
            // The two toggle labels. Each names the language it switches to, written in
            // that language, so a reader who cannot read the current one can still find
            // it. That is the whole point of them, so both bundles hold the same word.
            "lang.switchToEnglish",
            "lang.switchToArabic",
            // A sample email address. Latin either way, because an address is.
            "signin.ph.email");

    @Test
    @DisplayName("browser code only asks for strings the catalogue actually has")
    void everyKeyTheBrowserAsksForExists() throws IOException {
        Set<String> english = load(ENGLISH).stringPropertyNames();
        // The closing quote has to be followed by a comma or a bracket, so that a key
        // assembled at runtime — t('match.' + kind, kind) — is not read as the literal
        // key "match.". Those cannot be checked from here; the family they draw from is
        // covered by the parity test instead.
        Pattern call = Pattern.compile("\\b(?:t|tr)\\(\\s*'([a-zA-Z0-9_.]+)'\\s*[,)]");

        Set<String> missing = new TreeSet<>();
        for (Path file : sources()) {
            Matcher matcher = call.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (matcher.find()) {
                if (!english.contains(matcher.group(1))) {
                    missing.add(matcher.group(1) + "  (" + file.getFileName() + ")");
                }
            }
        }
        // A key with no entry is not an error at runtime — t() returns the English
        // fallback written beside it — which is exactly why it goes unnoticed until
        // somebody reads the Arabic page and finds English on it.
        assertTrue(missing.isEmpty(),
                "browser code asks for keys the catalogue does not have: " + missing);
    }

    @Test
    @DisplayName("the API does not answer with English written into the code")
    void apiMessagesComeFromTheCatalogue() throws IOException {
        // The message field of an API response is shown to the reader as-is, so a literal
        // here is a string that can never be translated. Matches an assignment of a quoted
        // sentence rather than any use of the word, so passing a resolved value through is
        // unaffected.
        Pattern literal = Pattern.compile(
                "\"message\"\\s*,\\s*\"([^\"]{4,})\"|put\\(\\s*\"message\"\\s*,\\s*\"([^\"]{4,})\"");

        Set<String> found = new LinkedHashSet<>();
        try (Stream<Path> tree = Files.walk(Path.of("src/main/java"))) {
            for (Path file : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher matcher = literal.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    String text = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                    found.add(file.getFileName() + ": \"" + text + "\"");
                }
            }
        }
        assertEquals(Set.of(), found,
                "these reach a reader but are written into the code, so they cannot be "
                        + "translated.\nMove them to messages.properties and read them with "
                        + "UiMessages.say(key).");
    }

    @Test
    @DisplayName("no toast or alert is raised with English written into the script")
    void transientMessagesComeFromTheCatalogue() throws IOException {
        // Toasts, alert strips and modal dialogs are the messages a reader is most likely
        // to be reading closely — something just happened — and the least likely to be
        // caught by a sweep, because they appear only after an action and vanish. Thirty
        // of them sat in English on the Arabic site behind clicks nobody had made while
        // looking.
        //
        // swal is in this list because it was not, and fifteen modals went on being
        // raised in English long after the toasts were fixed: the no-results dialog, every
        // collection failure, the export failure, the search-modes help. They were found
        // by opening the Arabic site and typing a query that matches nothing.
        //
        // Matches a literal first argument that contains two or more English words. Icon
        // markup passed as a prefix is not prose and does not count.
        Pattern raised = Pattern.compile(
                "\\b(?:showToast|toast|setAlert|hubToast|swal)\\(\\s*'([^']{3,})'");

        Set<String> found = new TreeSet<>();
        for (Path file : sources()) {
            Matcher matcher = raised.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (matcher.find()) {
                String literal = matcher.group(1).replaceAll("<[^>]*>", "").trim();
                if (literal.matches(".*\\b[A-Za-z]{2,}\\b.*\\b[A-Za-z]{2,}\\b.*")) {
                    found.add(file.getFileName() + ": \"" + literal + "\"");
                }
            }
        }
        assertEquals(Set.of(), found,
                "these are shown to the reader but written into the script:\n" + found
                        + "\nRead them with t('key', 'English fallback') instead.");
    }

    private static List<Path> sources() throws IOException {
        List<Path> files = new ArrayList<>();
        for (String dir : new String[]{"src/main/resources/static/js", "src/main/resources/templates"}) {
            try (Stream<Path> tree = Files.walk(Path.of(dir))) {
                files.addAll(tree.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".js") || p.toString().endsWith(".html"))
                        .toList());
            }
        }
        return files;
    }
}
