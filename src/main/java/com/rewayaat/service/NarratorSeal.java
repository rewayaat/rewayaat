package com.rewayaat.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The mark shown beside a narrator: their name, set in a roundel.
 *
 * <p>There are more than sixteen thousand people in the index and no likeness of any of
 * them, so the mark is derived rather than curated, and it is calligraphic rather than
 * figurative. Writing a name inside an ornamental frame is ordinary in the manuscript
 * tradition; drawing a face is not, and for the Prophet and the Imams a face is
 * prohibited outright. Nothing here draws one, so the same rule serves every entry and
 * there is no category of person the page has to special-case.
 *
 * <p>The monogram is the head of the person's shortest recorded Arabic name. A kunya
 * particle carries no information on its own - a seal reading أبو would fit hundreds of
 * people - so those take the following word with them.
 */
public final class NarratorSeal {

    /** Words that identify nobody by themselves. */
    private static final Set<String> CARRY_NEXT =
            Set.of("أبو", "أبا", "أبي", "ابن", "بن", "أم", "ابنة", "آل", "مولى", "عبد", "عبيد");

    /** How many ring treatments the roundel has; the person's id picks one. */
    private static final int VARIANTS = 6;

    private static final int MAX_CHARS = 12;

    private NarratorSeal() {
    }

    /** The monogram for a narrator, or null when no Arabic name is recorded. */
    public static String monogram(List<String> arabicNames, String displayAr) {
        String source = shortest(arabicNames);
        if (source == null || source.isBlank()) {
            source = displayAr;
        }
        if (source == null || source.isBlank()) {
            return null;
        }

        String[] words = source.trim().split("\\s+");
        StringBuilder mark = new StringBuilder(words[0]);
        // "عبد" alone is half a name, "أبو" alone is none; both take the next word.
        for (int i = 1; i < words.length && CARRY_NEXT.contains(stripHonorific(words[i - 1])); i++) {
            mark.append(' ').append(words[i]);
        }

        String result = mark.toString();
        return result.length() > MAX_CHARS ? words[0] : result;
    }

    /** A stable 0-based ring treatment for this person, so two seals side by side differ. */
    public static int variant(String personId) {
        if (personId == null || personId.isEmpty()) {
            return 0;
        }
        int hash = 7;
        for (int i = 0; i < personId.length(); i++) {
            hash = hash * 31 + personId.charAt(i);
        }
        return Math.floorMod(hash, VARIANTS);
    }

    /**
     * The shortest recorded name, which is the one closest to how the person is actually
     * referred to: the list runs from "أبو رافع" up to a full entry with a biography
     * attached to the end of it.
     */
    private static String shortest(List<String> names) {
        if (names == null || names.isEmpty()) {
            return null;
        }
        String best = null;
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            if (best == null || name.trim().length() < best.length()) {
                best = name.trim();
            }
        }
        return best;
    }

    private static String stripHonorific(String word) {
        return word.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}]", "");
    }
}
