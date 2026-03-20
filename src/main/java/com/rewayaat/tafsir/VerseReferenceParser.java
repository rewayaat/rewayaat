package com.rewayaat.tafsir;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses verse references from tafsir section headings.
 * Handles various formats from different tafsir sources.
 */
public class VerseReferenceParser {

    // Patterns for different verse reference formats
    private static final Pattern[] VERSE_PATTERNS = {
        // "Surah Al-Baqarah, Verses 21-22" or "Surah Al-Baqarah, Verse 255"
        Pattern.compile("surah\\s+([\\w\\s-]+)\\s*,\\s+verses?\\s+(\\d+)(?:\\s*-\\s*(\\d+))?", Pattern.CASE_INSENSITIVE),

        // "Al-Baqarah 21:22" or "Al-Baqarah 21:22-30"
        Pattern.compile("([\\w\\s-]+)\\s+(\\d+):(\\d+)(?:\\s*-\\s*(\\d+))?"),

        // "Exegesis of Surah Baqarah: Verses 1-62"
        Pattern.compile("exegesis\\s+of\\s+surah\\s+([\\w\\s-]+)\\s*:\\s*verses?\\s+(\\d+)(?:\\s*-\\s*(\\d+))?", Pattern.CASE_INSENSITIVE),

        // "Surah 2:255" or "2:255"
        Pattern.compile("(?:surah\\s+)?(\\d+)[:\\s]+(\\d+)(?:\\s*-\\s*(\\d+))?", Pattern.CASE_INSENSITIVE),

        // Hub-e-Ali format: "[2:255]" or "(2:255)"
        Pattern.compile("[\\[\\(](\\d+):(\\d+)[\\]\\)]"),

        // "Chapter 2, Verses 21-22"
        Pattern.compile("chapter\\s+(\\d+)\\s*,\\s+verses?\\s+(\\d+)(?:\\s*-\\s*(\\d+))?", Pattern.CASE_INSENSITIVE),

        // "Baqarah (21-22)"
        Pattern.compile("([\\w\\s-]+)\\s*\\[\\(]?(\\d+)\\s*-\\s*(\\d+)[\\)\\]]?"),

        // "Baqarah 255"
        Pattern.compile("^([\\w\\s-]+)\\s+(\\d+)$"),
    };

    /**
     * Result of parsing a verse reference.
     */
    public static class ParsedReference {
        public final Integer surahNumber;
        public final Integer ayahStart;
        public final Integer ayahEnd;

        public ParsedReference(Integer surahNumber, Integer ayahStart, Integer ayahEnd) {
            this.surahNumber = surahNumber;
            this.ayahStart = ayahStart;
            this.ayahEnd = ayahEnd != null ? ayahEnd : ayahStart;
        }

        public boolean isValid() {
            return surahNumber != null && surahNumber > 0 && surahNumber <= 114
                    && ayahStart != null && ayahStart > 0
                    && ayahEnd != null && ayahEnd >= ayahStart;
        }

        /**
         * Generates the verse_key for the primary verse.
         */
        public String getVerseKey() {
            if (!isValid()) {
                return null;
            }
            return surahNumber + ":" + ayahStart;
        }

        /**
         * Generates all verse keys covered by this reference (for multi-verse ranges).
         */
        public List<String> getVerseKeys() {
            List<String> keys = new ArrayList<>();
            if (!isValid()) {
                return keys;
            }
            // For practical purposes, limit ranges to avoid memory issues
            // Most multi-verse commentaries don't span more than a few dozen verses
            int maxVerses = Math.min(ayahEnd - ayahStart + 1, 500);
            for (int i = 0; i < maxVerses; i++) {
                keys.add(surahNumber + ":" + (ayahStart + i));
            }
            return keys;
        }

        @Override
        public String toString() {
            return "ParsedReference{" +
                    "surah=" + surahNumber +
                    ", ayahStart=" + ayahStart +
                    ", ayahEnd=" + ayahEnd +
                    '}';
        }
    }

    /**
     * Attempts to parse a verse reference from the given text.
     * Returns null if the reference cannot be parsed.
     */
    public static ParsedReference parse(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        String normalized = text.trim();

        // Try each pattern
        for (Pattern pattern : VERSE_PATTERNS) {
            ParsedReference result = tryPattern(pattern, normalized);
            if (result != null && result.isValid()) {
                return result;
            }
        }

        return null;
    }

    /**
     * Extracts all verse references found in the text.
     * Returns a list of parsed references (may be empty).
     */
    public static List<ParsedReference> parseMultiple(String text) {
        List<ParsedReference> results = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return results;
        }

        // Split by common delimiters and try to parse each part
        String[] parts = text.split("[,;\\n\\r]+");
        for (String part : parts) {
            ParsedReference ref = parse(part);
            if (ref != null && ref.isValid()) {
                results.add(ref);
            }
        }

        return results;
    }

    private static ParsedReference tryPattern(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }

        try {
            String surahStr = matcher.groupCount() >= 1 ? matcher.group(1) : null;
            String ayahStartStr = matcher.groupCount() >= 2 ? matcher.group(2) : null;
            String ayahEndStr = matcher.groupCount() >= 3 ? matcher.group(3) : null;

            // Determine surah number
            Integer surahNumber = null;
            if (surahStr != null) {
                // Try as a number first
                try {
                    surahNumber = Integer.parseInt(surahStr.trim());
                } catch (NumberFormatException e) {
                    // Try as a surah name
                    surahNumber = SurahNameResolver.resolve(surahStr);
                }
            }

            // Parse ayah numbers
            Integer ayahStart = ayahStartStr != null ? Integer.parseInt(ayahStartStr.trim()) : null;
            Integer ayahEnd = ayahEndStr != null ? Integer.parseInt(ayahEndStr.trim()) : null;

            if (surahNumber != null && ayahStart != null) {
                return new ParsedReference(surahNumber, ayahStart, ayahEnd);
            }
        } catch (NumberFormatException e) {
            // Continue to next pattern
        }

        return null;
    }

    /**
     * Checks if the given text likely contains a verse reference.
     */
    public static boolean containsVerseReference(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return parse(text) != null;
    }

    /**
     * Utility method to format a verse reference as "X:Y".
     */
    public static String formatVerseKey(int surahNumber, int ayahNumber) {
        return surahNumber + ":" + ayahNumber;
    }

    /**
     * Utility method to format a verse range as "X:Y-Z".
     */
    public static String formatVerseRange(int surahNumber, int ayahStart, int ayahEnd) {
        if (ayahStart == ayahEnd) {
            return formatVerseKey(surahNumber, ayahStart);
        }
        return surahNumber + ":" + ayahStart + "-" + ayahEnd;
    }
}
