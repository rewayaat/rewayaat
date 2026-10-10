package com.rewayaat.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A narrator document read in Arabic.
 *
 * <p>The indexed document carries both languages: every field whose value is words rather
 * than a number has an Arabic twin beside it, written when the index was built — {@code
 * book_title} and {@code book_title_ar}, {@code grade_said} and {@code grade_said_ar},
 * {@code blurb} and {@code blurb_ar}. This swaps the twin into the field the template
 * reads, so the template stays single-language.
 *
 * <p><strong>Why not do it in the template.</strong> The alternative is a ternary at every
 * use — {@code ${isArabic} ? ${entry.book_title_ar} : ${entry.book_title}} — about thirty
 * of them, nested three deep inside loops. Each is a place to forget the Arabic branch,
 * and a forgotten one shows an English book title in the middle of an Arabic page, which
 * is exactly the failure that is hard to see in review and obvious to a reader.
 *
 * <p>Only words are swapped here. Numbers are {@link LocaleDigits}' business, and the
 * numeric fields are left alone deliberately: {@code death_years[].value} is read back as
 * a number by the chronology band, and an Arabic-Indic string would not parse.
 *
 * <p>A missing or blank twin leaves the English in place. That is the honest fallback: a
 * reader is better served by a title he can still read than by a gap, and it is what lets
 * the Arabic page work before every field has been written in Arabic.
 */
public final class NarratorArabic {

    /** Top-level fields of the document itself. */
    private static final Map<String, String> PERSON = Map.of(
            "blurb", "blurb_ar",
            "for_him", "for_him_ar",
            "against_him", "against_him_ar");

    /** One entry as a book printed it. */
    private static final Map<String, String> ENTRY = Map.of(
            "book_title", "book_title_ar",
            "book_author", "book_author_ar",
            "grade_said", "grade_said_ar",
            "under_imams", "under_imams_ar");

    /** Anything carrying the book it was read from: verdicts, statements, positions. */
    private static final Map<String, String> SOURCED = Map.of(
            "value", "value_ar",
            "book_title", "book_title_ar",
            "said", "said_ar");

    /** A teacher or a student, with the books that record the link. */
    private static final Map<String, String> LINK = Map.of("books", "books_ar");

    /** A grade we hold no passage for: the book's name and the word, both in Arabic. */
    private static final Map<String, String> UNSOURCED = Map.of(
            "book_title", "book_title_ar",
            "grades", "grades_ar");

    /** Where and when he lived. {@code died} is a number and stays one. */
    private static final Map<String, String> LIFE = Map.of(
            "city", "city_ar",
            "imams", "imams_ar");

    private NarratorArabic() {
    }

    /**
     * The document as this locale reads it. English is returned unchanged, and the
     * argument is never modified: the document comes from a service that may cache it.
     */
    public static Map<String, Object> localise(Map<String, Object> narrator, PageLocale locale) {
        if (narrator == null || locale == null || !locale.isArabic()) {
            return narrator;
        }
        Map<String, Object> out = swap(narrator, PERSON);
        out.put("life", swap(asMap(narrator.get("life")), LIFE));
        out.put("entries", swapEach(narrator.get("entries"), ENTRY));
        out.put("verdicts", swapEach(narrator.get("verdicts"), SOURCED));
        out.put("generations", swapEach(narrator.get("generations"), SOURCED));
        out.put("cities_or_tribes", swapEach(narrator.get("cities_or_tribes"), SOURCED));
        out.put("death_years", swapEach(narrator.get("death_years"), SOURCED));
        out.put("sect_flags", swapEach(narrator.get("sect_flags"), SOURCED));
        out.put("narrated_from", swapEach(narrator.get("narrated_from"), LINK));
        out.put("narrated_to", swapEach(narrator.get("narrated_to"), LINK));
        // The chain-index links are shown beside the stated ones and were missed when they
        // were added, so an Arabic page named their books in English.
        out.put("chain_from", swapEach(narrator.get("chain_from"), LINK));
        out.put("chain_to", swapEach(narrator.get("chain_to"), LINK));
        out.put("reported_without_a_passage",
                swapEach(narrator.get("reported_without_a_passage"), UNSOURCED));

        // A book's row, and the positions it takes inside that row.
        List<Object> stands = new ArrayList<>();
        for (Object row : asList(narrator.get("where_books_stand"))) {
            Map<String, Object> stand = swap(asMap(row), SOURCED);
            stand.put("positions", swapEach(stand.get("positions"), SOURCED));
            stands.add(stand);
        }
        out.put("where_books_stand", stands);
        return out;
    }

    /** One map with the Arabic twins moved into the fields the template reads. */
    private static Map<String, Object> swap(Map<String, Object> source, Map<String, String> twins) {
        Map<String, Object> out = new LinkedHashMap<>(source);
        twins.forEach((field, arabic) -> {
            Object value = source.get(arabic);
            if (said(value)) {
                out.put(field, value);
            }
        });
        return out;
    }

    private static List<Object> swapEach(Object rows, Map<String, String> twins) {
        List<Object> out = new ArrayList<>();
        for (Object row : asList(rows)) {
            out.add(swap(asMap(row), twins));
        }
        return out;
    }

    /** Whether the Arabic twin actually holds something: a blank one is not a translation. */
    private static boolean said(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String text) {
            return !text.isBlank();
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        return true;
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map)
                : new LinkedHashMap<>();
    }
}
