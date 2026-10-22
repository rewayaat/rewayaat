package com.rewayaat.service;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The narrator's seal: a small tile that says, before a word is read, when he lived and how
 * much of the corpus runs through him.
 *
 * <p>The tile's colour is his generation, read off the <i>earliest</i> Imam he transmitted
 * from (the lowest of {@code imam_ordinals}), on a ramp of manuscript pigments from lapis
 * through verdigris to ochre and cinnabar. A man with no Imam on record gets the site's navy,
 * which reads as «not placed» rather than as a generation. The letter is the first of his
 * name with the article dropped. The gold ring is how much of the corpus stands on him, on a
 * log scale: Ibrāhīm b. Hāshim is in 4,056 chains and a man with 12 would be an invisible
 * sliver on a linear one, whereas log(1+12)/log(1+8736) still draws a visible arc. 8,736 is
 * the most chains any narrator stands in.
 *
 * <p>Below 40px the ring is dropped and the tile fills the box: at 24 or 32px a 5-unit stroke
 * is under two device pixels and reads as dirt on the edge, not as a measure.
 *
 * <p>Generated from the record, never drawn per man. The browser has the same geometry in
 * {@code narrator-preview.js} for the preview card, which is built client-side; the two must
 * move together.
 *
 * <p>In a template: {@code th:utext="${@seals.of(id, nameAr, ordinals, chains, 44)}"}.
 */
@Component("seals")
public class NarratorSeal {

    /** hue, saturation %, lightness % — index is the Imam's ordinal, 0..12. */
    private static final int[][] PIGMENT = {
        {220, 52, 38}, {212, 50, 39}, {202, 48, 38}, {194, 46, 36}, {186, 42, 35},
        {172, 38, 33}, {152, 36, 33}, {104, 32, 34}, {58, 44, 35}, {40, 58, 38},
        {26, 58, 38}, {10, 52, 39}, {348, 42, 40}
    };
    private static final double MAX_CHAINS = 8736;
    private static final String TRACK = "M31,5h38a26,26 0 0 1 26,26v38a26,26 0 0 1 -26,26h-38"
            + "a26,26 0 0 1 -26,-26v-38a26,26 0 0 1 26,-26z";

    /** Aria-hidden: the name and the count stand beside it in text, so a screen reader gets them once. */
    public String of(Object personId, String nameAr, List<?> ordinals, Object chains, int size) {
        long count = chains instanceof Number n ? n.longValue() : 0;
        Integer era = null;
        if (ordinals != null) {
            for (Object o : ordinals) {
                if (o instanceof Number n && n.intValue() >= 0 && n.intValue() < PIGMENT.length
                        && (era == null || n.intValue() < era)) {
                    era = n.intValue();
                }
            }
        }
        String id = "sg" + String.valueOf(personId).replaceAll("[^A-Za-z0-9]", "") + size;
        boolean big = size >= 40;
        double x = big ? 15 : 3, w = big ? 70 : 94, r = big ? 20 : 26;
        StringBuilder svg = new StringBuilder(900);
        svg.append("<svg class=\"nseal\" viewBox=\"0 0 100 100\" width=\"").append(size)
           .append("\" height=\"").append(size).append("\" aria-hidden=\"true\" focusable=\"false\">")
           .append("<defs><linearGradient id=\"").append(id).append("\" x1=\"0\" y1=\"0\" x2=\"0.3\" y2=\"1\">")
           .append("<stop offset=\"0\" stop-color=\"").append(light(era)).append("\"/>")
           .append("<stop offset=\"1\" stop-color=\"").append(deep(era)).append("\"/></linearGradient></defs>");
        if (big) {
            double fill = Math.min(1, Math.log1p(Math.max(0, count)) / Math.log1p(MAX_CHAINS));
            svg.append("<path d=\"").append(TRACK).append("\" pathLength=\"100\" fill=\"none\" ")
               .append("class=\"nseal__track\" stroke-width=\"5\"/>")
               .append("<path d=\"").append(TRACK).append("\" pathLength=\"100\" fill=\"none\" ")
               .append("class=\"nseal__ring\" stroke-width=\"5\" stroke-linecap=\"round\" stroke-dasharray=\"")
               .append(String.format(java.util.Locale.ROOT, "%.1f", fill * 100)).append(" 100\"/>");
        }
        svg.append(String.format(java.util.Locale.ROOT,
                "<rect x=\"%s\" y=\"%s\" width=\"%s\" height=\"%s\" rx=\"%s\" fill=\"url(#%s)\"/>",
                n(x), n(x), n(w), n(w), n(r), id));
        svg.append(String.format(java.util.Locale.ROOT,
                "<path d=\"M%s %sQ%s %s %s %sh%sq%s 0 %s %sv5L%s %sZ\" fill=\"#fff\" opacity=\"0.16\"/>",
                n(x), n(x + r), n(x), n(x), n(x + r), n(x), n(w - 2 * r), n(r), n(r), n(r),
                n(x), n(x + w * 0.52)));
        svg.append(String.format(java.util.Locale.ROOT,
                "<text x=\"50\" y=\"%s\" text-anchor=\"middle\" font-family=\"Scheherazade New, Amiri, serif\" "
                + "font-size=\"%s\" fill=\"#fff\">%s</text></svg>",
                n(x + w * 0.76), n(w * 0.68), initial(nameAr)));
        return svg.toString();
    }

    /** First letter of the name, the article dropped; «؟» for a name with no letters. */
    static String initial(String nameAr) {
        String s = nameAr == null ? "" : nameAr.replaceFirst("^\\s*ال", "").trim();
        if (s.isEmpty()) {
            return "؟";
        }
        return new String(Character.toChars(s.codePointAt(0)))
                .replace("&", "&amp;").replace("<", "&lt;");
    }

    private static String light(Integer era) {
        if (era == null) {
            return "hsl(213 52% 34%)";
        }
        int[] p = PIGMENT[era];
        return "hsl(" + p[0] + " " + p[1] + "% " + p[2] + "%)";
    }

    private static String deep(Integer era) {
        if (era == null) {
            return "hsl(213 58% 22%)";
        }
        int[] p = PIGMENT[era];
        return "hsl(" + p[0] + " " + (p[1] + 6) + "% " + (p[2] - 13) + "%)";
    }

    private static String n(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
