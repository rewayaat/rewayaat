package com.rewayaat.tafsir;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Shared post-extraction cleanup for tafsir snippets before indexing.
 * Supports both English and Arabic text processing.
 */
public final class TafsirSnippetSanitizer {

    private static final Pattern STAR_SEPARATOR_LINE = Pattern.compile("(?m)^\\*{5,}\\s*$");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("[ \\t]+");
    private static final Pattern SPACE_AROUND_NEWLINE = Pattern.compile(" *\\n *");
    private static final Pattern EXTRA_NEWLINES = Pattern.compile("\\n{3,}");
    private static final Pattern TRAILING_NOTE_DIGITS = Pattern.compile("([.!?\"\"')])\\d{1,2}$");
    private static final Pattern TRAILING_NUMERIC_NOTE = Pattern.compile("\\s+\\d+:$");
    private static final Pattern TRAILING_REFERENCE_NOISE = Pattern.compile("(?:\\s+\\d+\\s+Tafseer .*|\\s+up to the end of the Verse)$");
    private static final Pattern LEADING_GARBAGE = Pattern.compile("^(?:[\\}\\{\\[\\]]+\\s*)+");
    private static final Pattern INLINE_FOOTNOTE_DIGITS = Pattern.compile("(?<=[\\p{L}''\")])\\d{1,2}(?=[\\s,.;:!?)]|$)");
    // English-only reference pattern - should only be applied to English text
    private static final Pattern ENGLISH_REFERENCE_ONLY_TEXT = Pattern.compile(
            "(?is)^(?:[\\d\\s:()./\\-–]+|(?:tafseer|tafsir)\\s+.*|al\\s+kafi\\s*[–-]?\\s*h.*|majma\\s+ul\\s+bayaan.*|basaair\\s+al\\s+darajaat.*|extract\\s+tafsir.*|muhammad\\s+bin\\s+.*)$");

    // Arabic Unicode ranges
    private static final String ARABIC_CHAR_CLASS = "\\u0600-\\u06FF\\u0750-\\u077F\\u08A0-\\u08FF\\uFB50-\\uFDFF\\uFE70-\\uFEFF";
    private static final Pattern ARABIC_TEXT_PATTERN = Pattern.compile(".*[" + ARABIC_CHAR_CLASS + "].*", Pattern.DOTALL);
    // Arabic diacritics range (harakat)
    private static final Pattern ARABIC_DIACRITICS_PATTERN = Pattern.compile("[\\u064B-\\u0652\\u0670]");
    private static final Pattern NON_ARABIC_CHARS_PATTERN = Pattern.compile("[^\\s\\p{Punct}" + ARABIC_CHAR_CLASS + "0-9]+");
    private static final Pattern NON_LATIN_CHARS_PATTERN = Pattern.compile("[^\\s\\p{Punct}A-Za-z0-9]+");
    private static final Pattern MIXED_SEGMENT_BOUNDARY = Pattern.compile("(?=\\{\\d+\\})|(?=\\d+\\.\\s*[\"'])|(?<=[.!?؟])\\s+");
    private static final Pattern VERSE_REFERENCE_PATTERN = Pattern.compile("\\{\\d+\\}|\\(\\d+:\\d+\\)\\.?|\\b\\d+:\\d+\\b");
    private static final Pattern DANGLING_NUMERIC_PUNCTUATION = Pattern.compile("(?m)(^|\\s)\\d+(?:[.,;:!?]+)?(?=\\s|$)");
    private static final Pattern EMPTY_PUNCTUATION_RUN = Pattern.compile("(?m)(^|\\s)[\\p{Punct}]+(?=\\s|$)");

    private TafsirSnippetSanitizer() {
    }

    public static void sanitize(TafsirDocument doc) {
        if (doc == null) {
            return;
        }

        doc.setCommentaryText(sanitizeText(doc.getCommentaryText()));
        ScriptSplit split = splitCommentaryByScript(doc.getCommentaryText());
        doc.setCommentaryTextArabic(split.arabicText());
        doc.setCommentaryTextEnglish(split.englishText());
        if (doc.getVerseTextEnglish() != null) {
            doc.setVerseTextEnglish(sanitizeText(doc.getVerseTextEnglish()));
        }
        if (doc.getSectionTitle() != null) {
            doc.setSectionTitle(doc.getSectionTitle().replace('\u00A0', ' ').trim());
        }
        doc.computeWordCount();
    }

    /**
     * Sanitizes text by normalizing whitespace, removing artifacts, and cleaning references.
     * Preserves Arabic diacritics (harakat) for proper text representation.
     */
    public static String sanitizeText(String text) {
        if (text == null || text.isBlank()) {
            return text == null ? null : "";
        }

        String normalized = text
                .replace('\u00A0', ' ')  // Non-breaking space to regular space
                .replace("\r\n", "\n")
                .replace('\r', '\n');

        normalized = STAR_SEPARATOR_LINE.matcher(normalized).replaceAll("");
        normalized = LEADING_GARBAGE.matcher(normalized).replaceFirst("");
        normalized = WHITESPACE_RUN.matcher(normalized).replaceAll(" ");
        normalized = SPACE_AROUND_NEWLINE.matcher(normalized).replaceAll("\n");
        normalized = EXTRA_NEWLINES.matcher(normalized).replaceAll("\n\n");
        normalized = INLINE_FOOTNOTE_DIGITS.matcher(normalized).replaceAll("");
        normalized = TRAILING_REFERENCE_NOISE.matcher(normalized).replaceFirst("");
        normalized = TRAILING_NUMERIC_NOTE.matcher(normalized).replaceFirst("");
        normalized = TRAILING_NOTE_DIGITS.matcher(normalized).replaceFirst("$1");
        normalized = stripTrailingReferenceParagraphs(normalized.trim());

        return normalized.trim();
    }

    public static boolean isIndexable(TafsirDocument doc) {
        if (doc == null) {
            return false;
        }
        return doc.getCommentaryWordCount() != null
                && doc.getCommentaryWordCount() >= 10
                && isSubstantiveText(doc.getCommentaryText());
    }

    /**
     * Checks if text is substantive (contains meaningful content).
     * Supports both English and Arabic text detection.
     */
    private static boolean isSubstantiveText(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return false;
        }

        // Check if text is Arabic
        boolean isArabic = isArabicText(trimmed);

        // English-only reference check - skip for Arabic
        if (!isArabic && ENGLISH_REFERENCE_ONLY_TEXT.matcher(trimmed).matches()) {
            return false;
        }

        // Count meaningful tokens based on language
        int substantiveTokens = 0;
        for (String token : trimmed.split("\\s+")) {
            if (isArabic) {
                // For Arabic, count tokens with Arabic characters
                if (token.matches(".*[" + ARABIC_CHAR_CLASS + "].*")) {
                    substantiveTokens++;
                }
            } else {
                // For English, count tokens with Latin letters
                if (token.matches(".*[A-Za-z].*")) {
                    substantiveTokens++;
                }
            }
        }
        return substantiveTokens >= 6;
    }

    /**
     * Checks if text contains Arabic characters.
     */
    public static boolean isArabicText(String text) {
        return text != null && ARABIC_TEXT_PATTERN.matcher(text).matches();
    }

    public static ScriptSplit splitCommentaryByScript(String text) {
        String sanitized = sanitizeText(text);
        if (sanitized == null || sanitized.isBlank()) {
            return new ScriptSplit("", "");
        }
        if (isArabicText(sanitized) && getLatinRatio(sanitized) < 0.10) {
            return new ScriptSplit(sanitized, "");
        }
        if (!isArabicText(sanitized)) {
            return new ScriptSplit("", sanitized);
        }

        List<String> arabicParts = new ArrayList<>();
        List<String> englishParts = new ArrayList<>();
        for (String paragraph : sanitized.split("\\n\\n+")) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (countArabicLetters(trimmed) == 0 || countLatinLetters(trimmed) == 0) {
                String arabicView = cleanArabicSplitText(NON_ARABIC_CHARS_PATTERN.matcher(trimmed).replaceAll(" "));
                String englishView = sanitizeSplitView(NON_LATIN_CHARS_PATTERN.matcher(trimmed).replaceAll(" "));
                if (!arabicView.isBlank()) {
                    arabicParts.add(arabicView);
                }
                if (!englishView.isBlank()) {
                    englishParts.add(englishView);
                }
                continue;
            }

            List<String> arabicSegments = new ArrayList<>();
            List<String> englishSegments = new ArrayList<>();
            for (String segment : MIXED_SEGMENT_BOUNDARY.split(trimmed)) {
                String cleanSegment = segment == null ? "" : segment.trim();
                if (cleanSegment.isEmpty()) {
                    continue;
                }
                int arabicLetters = countArabicLetters(cleanSegment);
                int latinLetters = countLatinLetters(cleanSegment);
                if (arabicLetters == 0 && latinLetters == 0) {
                    continue;
                }
                if (arabicLetters >= latinLetters) {
                    arabicSegments.add(cleanSegment);
                }
                if (latinLetters > arabicLetters) {
                    englishSegments.add(cleanSegment);
                }
            }

            String arabicView = cleanArabicSplitText(String.join(" ", arabicSegments));
            String englishView = sanitizeSplitView(String.join(" ", englishSegments));
            if (!arabicView.isBlank()) {
                arabicParts.add(arabicView);
            }
            if (!englishView.isBlank()) {
                englishParts.add(englishView);
            }
        }
        return new ScriptSplit(String.join("\n\n", arabicParts), String.join("\n\n", englishParts));
    }

    /**
     * Counts Arabic diacritics (harakat) in text.
     */
    public static int countArabicDiacritics(String text) {
        if (text == null) {
            return 0;
        }
        java.util.regex.Matcher matcher = ARABIC_DIACRITICS_PATTERN.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * Counts the ratio of Latin characters in text.
     * Useful for validating Arabic documents should have minimal Latin content.
     */
    public static double getLatinRatio(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int totalChars = 0;
        int latinChars = 0;
        for (char c : text.toCharArray()) {
            if (Character.isLetter(c)) {
                totalChars++;
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                    latinChars++;
                }
            }
        }
        return totalChars > 0 ? (double) latinChars / totalChars : 0;
    }

    private static String stripTrailingReferenceParagraphs(String text) {
        List<String> paragraphs = new ArrayList<>(List.of(text.split("\\n\\n+")));
        while (!paragraphs.isEmpty() && isReferenceParagraph(paragraphs.get(paragraphs.size() - 1))) {
            paragraphs.remove(paragraphs.size() - 1);
        }
        return String.join("\n\n", paragraphs);
    }

    /**
     * Checks if a paragraph is a reference paragraph (footnotes, citations, etc.).
     * Language-aware - English keywords only apply to English text.
     */
    private static boolean isReferenceParagraph(String paragraph) {
        String trimmed = paragraph.trim();
        if (trimmed.isEmpty()) {
            return false;
        }

        // For Arabic text, use different heuristics
        if (isArabicText(trimmed)) {
            return isArabicReferenceParagraph(trimmed);
        }

        String lower = trimmed.toLowerCase(Locale.ROOT);
        int noteMarkers = countMatches(trimmed, "\\b\\d+\\.");
        int verseRefs = countMatches(trimmed, "\\b\\d+:\\d+\\b");
        boolean hasReferenceKeywords = lower.contains("surah ")
                || lower.contains("verse ")
                || lower.contains("refer to ")
                || lower.contains("see ")
                || lower.contains("tafsir")
                || lower.contains("vol.")
                || lower.contains("p.")
                || lower.contains("nahj")
                || lower.contains("majma")
                || lower.contains("kafi");

        return trimmed.matches("^\\d+\\..*") && (noteMarkers >= 3 || verseRefs >= 2 || hasReferenceKeywords);
    }

    /**
     * Arabic-specific reference paragraph detection.
     * Arabic references often contain specific patterns like:
     * - numeros digits followed by dots
     * - verse references in format 1:1
     * - Source book names
     */
    private static boolean isArabicReferenceParagraph(String paragraph) {
        // Check if paragraph is mostly numeric references or citations
        String[] tokens = paragraph.split("\\s+");
        int numericTokens = 0;
        int verseRefs = 0;

        for (String token : tokens) {
            // Count tokens that are purely numbers or have verse reference patterns
            if (token.matches("^\\d+[.:.]?$") || token.matches("\\d+:\\d+")) {
                numericTokens++;
            }
            if (token.matches("\\d+:\\d+")) {
                verseRefs++;
            }
        }

        // If most tokens are numeric, likely a reference paragraph
        return tokens.length > 3 && (numericTokens > tokens.length / 2 || verseRefs >= 2);
    }

    private static int countMatches(String input, String regex) {
        Pattern pattern = Pattern.compile(regex);
        var matcher = pattern.matcher(input);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String sanitizeSplitView(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String cleaned = WHITESPACE_RUN.matcher(text).replaceAll(" ").trim();
        cleaned = SPACE_AROUND_NEWLINE.matcher(cleaned).replaceAll("\n");
        return cleaned.trim();
    }

    private static String cleanArabicSplitText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String cleaned = VERSE_REFERENCE_PATTERN.matcher(text).replaceAll(" ");
        cleaned = DANGLING_NUMERIC_PUNCTUATION.matcher(cleaned).replaceAll(" ");
        cleaned = EMPTY_PUNCTUATION_RUN.matcher(cleaned).replaceAll(" ");
        cleaned = sanitizeSplitView(cleaned);
        if (countArabicLetters(cleaned) == 0) {
            return "";
        }
        return cleaned;
    }

    private static int countArabicLetters(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (char c : text.toCharArray()) {
            if ((c >= '\u0600' && c <= '\u06FF')
                    || (c >= '\u0750' && c <= '\u077F')
                    || (c >= '\u08A0' && c <= '\u08FF')
                    || (c >= '\uFB50' && c <= '\uFDFF')
                    || (c >= '\uFE70' && c <= '\uFEFF')) {
                count++;
            }
        }
        return count;
    }

    private static int countLatinLetters(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (char c : text.toCharArray()) {
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                count++;
            }
        }
        return count;
    }

    public record ScriptSplit(String arabicText, String englishText) {
    }

}
