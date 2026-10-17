package com.rewayaat.service;

import org.springframework.stereotype.Component;

/**
 * «1 books» and «١١ كتب» — counted phrases in a language that counts.
 *
 * <p>English needs two forms and Arabic needs four: one, two, a few (three to ten) and
 * many (eleven up), each with its own ending. A single key with {0} in it is right for
 * exactly one of those and wrong for the rest, which is how a narrator with one entry came
 * to have "1 books" under his name.
 *
 * <p>The bare key carries the many form, because that is what most counts are, and the
 * other three hang off it: {@code narrators.books}, {@code narrators.booksOne},
 * {@code narrators.booksTwo}, {@code narrators.booksFew}. A base with no such variants
 * falls back to the bare key, so a key that has not been split yet still reads.
 *
 * <p>The browser has the same rule in {@code narrator-preview.js}, where the search
 * results count the same things; it runs where this cannot.
 *
 * <p>In a template: {@code th:text="${@counts.of(found.entry_count, 'narrators.entries')}"}.
 */
@Component("counts")
public class Counts {

    private final UiMessages ui;
    private final LocaleDigits digits;

    public Counts(UiMessages ui, LocaleDigits digits) {
        this.ui = ui;
        this.digits = digits;
    }

    /** The count, in the words and the digits of the page being served. */
    public String of(Object howMany, String base) {
        long number = asNumber(howMany);
        String key = base;
        if (number == 1) {
            key = base + "One";
        } else if (number == 2) {
            key = base + "Two";
        } else if (number >= 3 && number <= 10) {
            key = base + "Few";
        }
        String phrase = ui.say(key, grouped(number));
        // UiMessages hands back the key itself when it is missing, which means this base
        // has not been split into forms. The bare key always exists.
        return phrase.equals(key) ? ui.say(base, grouped(number)) : phrase;
    }

    /**
     * The number as the page's language writes it.
     *
     * <p>«4915 narrations» is a string of digits a reader has to count; «4,915» he reads at
     * a glance. Arabic groups the same way and marks it with its own separator, ٬ rather
     * than a comma, so the grouping is done first and the mark swapped after.
     */
    private String grouped(long number) {
        String western = String.format("%,d", number);
        String localised = digits.of(western);
        return localised.equals(western) ? western : localised.replace(',', '\u066c');
    }

    private static long asNumber(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0 : Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }
}
