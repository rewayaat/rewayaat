package com.rewayaat.service;

import org.springframework.stereotype.Component;

/**
 * Numbers written in the digits the page is set in.
 *
 * <p>Arabic pages count in ٠١٢٣٤٥٦٧٨٩. Thymeleaf's number formatting already follows the
 * locale, so anything passed through {@code #numbers} is right; what is not right is
 * everything that reaches a template or a card as text — a volume named "1", a section
 * named "al-qism 10", a hadith number. Those arrive already stringified and no formatter
 * touches them, which is how a single page came to show ١٨٩ in its heading and 189 three
 * lines below it.
 *
 * <p>One copy, because there were three: the same loop had been written into two
 * controllers and the card factory, and the browser had a fourth in JavaScript. The
 * JavaScript one has to stay — it runs where this cannot — but it is the only twin left.
 *
 * <p>In a template: {@code th:text="${@localeDigits.of(chapter.volume)}"}.
 */
@Component("localeDigits")
public class LocaleDigits {

    private static final char ARABIC_ZERO = '٠';

    private final UiMessages ui;

    public LocaleDigits(UiMessages ui) {
        this.ui = ui;
    }

    /** Converted for the request being served. */
    public String of(Object value) {
        return in(ui.current(), value);
    }

    /**
     * Converted for a given language.
     *
     * <p>Only the digits change. Words around them are left alone, so "al-qism 10" keeps
     * its word and loses only its numeral.
     */
    public static String in(PageLocale locale, Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        if (locale == null || !locale.isArabic()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (char character : text.toCharArray()) {
            out.append(character >= '0' && character <= '9'
                    ? (char) (ARABIC_ZERO + (character - '0'))
                    : character);
        }
        return out.toString();
    }

    /**
     * A whole number grouped in thousands, in the page's own digits and separator.
     *
     * <p>«4915» is a string of digits a reader has to count; «4,915» he reads at a glance.
     * Arabic groups the same way but marks it with ٬ (U+066C), not an ASCII comma, which in
     * Arabic script reads as a stray Latin mark between two numerals. The grouping is done
     * first on the western digits and the mark swapped after, so English keeps its comma.
     */
    public String grouped(Object value) {
        if (value == null) {
            return null;
        }
        String western = value instanceof Number number
                ? String.format("%,d", number.longValue())
                : String.valueOf(value);
        String localised = of(western);
        return localised.equals(western) ? western : localised.replace(',', '\u066c');
    }
}
