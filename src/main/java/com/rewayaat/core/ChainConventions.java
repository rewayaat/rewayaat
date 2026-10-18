package com.rewayaat.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The words an isnād uses in place of a man's name, found in the chain as rendered.
 *
 * <p>«عدة من أصحابنا» stands 2,731 times in these chains and «بعض أصحابنا» 459 more. They
 * are not names and must never link to a narrator — there is nobody behind them — but they
 * are not noise either: each is a convention with a literature behind it, and a reader who
 * meets one has a question the chain cannot answer. So the phrase links to the page that
 * explains it, which is the one link on a chain that does not point at a person.
 *
 * <p>Found by pattern rather than by stored offsets, unlike {@link ChainLinks}. The reason
 * is the English side: the resolver's spans are computed over Arabic, and there is no
 * English equivalent of {@code chain_people}, so a stored span could only ever light up
 * half the corpus. A fixed phrase needs no resolver.
 *
 * <p>The Arabic is matched through its diacritics. Al-Kāfī is typeset fully vowelled here —
 * «عِدَّةٌ مِنْ أَصْحَابِنَا» — and the same chain appears unvowelled in other books, so each
 * letter is allowed to carry any number of marks after it and the alif forms are folded.
 * The English is matched case-insensitively over the handful of wordings the translations
 * actually use, counted rather than guessed: "a number of our people" alone covers 2,531 of
 * them. Anything less certain is left unlinked, because a wrong link costs more than a
 * missing one.
 */
public final class ChainConventions {

    /** Where the pages live. One page per convention, with a section anchor per form. */
    private static final String PAGE = "/isnad/idda-min-ashabina";

    private static final String MARKS = "[\\u064B-\\u0655\\u0670\\u0640\\u06DF-\\u06E8\\u06EA-\\u06ED]*";

    /** One convention: the Arabic forms, the English wordings, and the anchor to land on. */
    private record Convention(Pattern arabic, Pattern english, String anchor) {
    }

    private static final List<Convention> CONVENTIONS = List.of(
            // The ʿidda itself: the page's subject, so it lands at the top.
            new Convention(
                    arabicAny("عدة من أصحابنا", "عدة من أصحابه", "عدة من أصحابي",
                            "عدتي عن أصحابنا"),
                    Pattern.compile("\\b(?:a (?:number|group) (?:of|from) (?:our|his) "
                                    + "(?:people|peole|companions|associates)"
                                    + "|certain persons of (?:our|his) people)\\b",
                            Pattern.CASE_INSENSITIVE),
                    ""),
            // The neighbours, which claim less than an ʿidda does and are set out together.
            new Convention(
                    arabicAny("بعض أصحابنا", "بعض أصحابه", "بعض أصحابي", "بعض رجاله",
                            "غير واحد من أصحابنا", "غير واحد من أصحابه", "غير واحد",
                            "رجل من أصحابنا", "رجل من أصحابه", "جماعة من أصحابنا",
                            "جماعة من مشايخي", "جماعة مشايخي"),
                    Pattern.compile("\\b(?:some of (?:our|his) (?:people|companions)"
                                    + "|a man of (?:our|his|the) (?:people|companions)"
                                    + "|a group of our scholars"
                                    + "|more than one of (?:our|his) (?:people|companions))\\b",
                            Pattern.CASE_INSENSITIVE),
                    "#neighbours"));

    private ChainConventions() {
    }

    /**
     * Builds one pattern that matches any of these phrases through its diacritics.
     *
     * <p>Alif is written أ, إ, آ and ا in the same word across the editions here, and yāʾ as
     * ي or ى, so those are matched as classes. Whitespace between words is any run of it:
     * the chains carry non-breaking spaces where a line broke in the printed page.
     */
    private static Pattern arabicAny(String... phrases) {
        StringBuilder all = new StringBuilder();
        for (String phrase : phrases) {
            if (all.length() > 0) {
                all.append('|');
            }
            all.append(arabic(phrase));
        }
        return Pattern.compile("(?:" + all + ")");
    }

    private static String arabic(String phrase) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < phrase.length(); i++) {
            char c = phrase.charAt(i);
            if (c == ' ') {
                out.append("\\s+");
                continue;
            }
            switch (c) {
                case 'ا', 'أ', 'إ', 'آ' -> out.append("[\\u0627\\u0623\\u0625\\u0622]");
                case 'ي', 'ى' -> out.append("[\\u064A\\u0649]");
                case 'ه', 'ة' -> out.append("[\\u0647\\u0629]");
                default -> out.append(Pattern.quote(String.valueOf(c)));
            }
            out.append(MARKS);
        }
        return out.toString();
    }

    /** Every convention phrase in this chain, as spans, earliest first and non-overlapping. */
    public static List<Span> spans(String chain, boolean arabicText) {
        List<Span> found = new ArrayList<>();
        if (chain == null || chain.isBlank()) {
            return found;
        }
        for (Convention convention : CONVENTIONS) {
            Pattern pattern = arabicText ? convention.arabic() : convention.english();
            Matcher matcher = pattern.matcher(chain);
            while (matcher.find()) {
                if (matcher.end() > matcher.start()) {
                    found.add(new Span(matcher.start(), matcher.end(), PAGE + convention.anchor()));
                }
            }
        }
        found.sort((left, right) -> Integer.compare(left.start(), right.start()));
        List<Span> kept = new ArrayList<>(found.size());
        int reached = 0;
        for (Span span : found) {
            if (span.start() >= reached) {
                kept.add(span);
                reached = span.end();
            }
        }
        return kept;
    }

    /** A phrase in the chain text and the page that explains it. */
    public record Span(int start, int end, String href) {
    }
}
