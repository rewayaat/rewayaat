package com.rewayaat.core;

import java.text.Normalizer;
import java.util.Locale;

/**
 * URL slugs for book and chapter titles.
 *
 * <p>Lives in {@code core} rather than beside {@link com.rewayaat.service.BookCatalog}
 * because the browse facets need it too, and {@code core} depending on {@code service}
 * inverts the direction every other class in these packages runs — service builds on
 * core, not the other way about. Both callers reach down to here instead.
 *
 * <p>Slugs are public URLs. Changing what this produces 404s every book and chapter page
 * that search engines have indexed; {@code BookCatalogSlugTest} pins the shape.
 */
public final class Slugs {

    /** Longer than this adds nothing a crawler or a reader uses. */
    private static final int MAX_LENGTH = 80;

    private Slugs() {
    }

    /**
     * Book and chapter titles are transliterated Arabic full of combining marks and dots
     * below — "Man Lā Yaḥḍuruh al-Faqīh", "ʿUyūn akhbār al-Riḍā". Stripping the
     * diacritics first gives the plain-ASCII spelling people actually type and link to,
     * so the URL matches the query.
     */
    public static String slugify(String input) {
        if (input == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(input, Normalizer.Form.NFD);
        StringBuilder ascii = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            int type = Character.getType(c);
            if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK) {
                continue;
            }
            ascii.append(c);
        }
        String slug = ascii.toString()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.length() <= MAX_LENGTH) {
            return slug;
        }
        int cut = slug.lastIndexOf('-', MAX_LENGTH);
        return slug.substring(0, cut < 20 ? MAX_LENGTH : cut);
    }
}
