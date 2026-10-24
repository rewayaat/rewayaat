package com.rewayaat.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The salutation that follows one of the Fourteen, wherever this site names them.
 *
 * <p>The pages were printing «al-Jawād» and «al-Hādī» bare — in the generations key, in
 * the chains tab, beside a book's entry — which is not how a Shiʿi reader writes those
 * names and not how any of the books this site quotes writes them. The index holds the
 * bare form because it is a key; the salutation belongs to the reading of it, so it is
 * added here rather than written into the data, and no reindex is needed to change it.
 *
 * <p>Three salutations, not one: the Prophet takes the full ṣalawāt, Fāṭima takes the
 * feminine, and the Imams take «عليه السلام». A name this does not recognise is returned
 * untouched, because a narrator is not saluted and most names passing through here are
 * narrators' — the test for «is this one of the Fourteen» has to be exact.
 */
@Component("honorifics")
public class Honorifics {

    private static final String IMAM_EN = "(ʿa)";
    private static final String PROPHET_EN = "(ṣ)";
    private static final String IMAM_AR = "عليه السلام";
    private static final String LADY_AR = "عليها السلام";
    private static final String PROPHET_AR =
            "صلى الله عليه وآله";

    /** Every form the narrator index actually stores, in both scripts, and Fāṭima. */
    private static final Map<String, String> EN = Map.ofEntries(
            Map.entry("the Prophet", PROPHET_EN),
            Map.entry("ʿAlī", IMAM_EN),
            Map.entry("al-Ḥasan", IMAM_EN),
            Map.entry("al-Ḥusayn", IMAM_EN),
            Map.entry("al-Sajjād", IMAM_EN),
            Map.entry("al-Bāqir", IMAM_EN),
            Map.entry("al-Ṣādiq", IMAM_EN),
            Map.entry("al-Kāẓim", IMAM_EN),
            Map.entry("al-Riḍā", IMAM_EN),
            Map.entry("al-Jawād", IMAM_EN),
            Map.entry("al-Hādī", IMAM_EN),
            Map.entry("al-ʿAskarī", IMAM_EN),
            Map.entry("the Mahdī", IMAM_EN),
            Map.entry("Fāṭima al-Zahrāʾ", IMAM_EN));

    private static final Map<String, String> AR = Map.ofEntries(
            Map.entry("النبي", PROPHET_AR),
            Map.entry("علي", IMAM_AR),
            Map.entry("الحسن", IMAM_AR),
            Map.entry("الحسين", IMAM_AR),
            Map.entry("السجاد", IMAM_AR),
            Map.entry("الباقر", IMAM_AR),
            Map.entry("الصادق", IMAM_AR),
            Map.entry("الكاظم", IMAM_AR),
            Map.entry("الرضا", IMAM_AR),
            Map.entry("الجواد", IMAM_AR),
            Map.entry("الهادي", IMAM_AR),
            Map.entry("العسكري", IMAM_AR),
            Map.entry("المهدي", IMAM_AR),
            Map.entry("فاطمة", LADY_AR),
            Map.entry("الزهراء", LADY_AR));

    /** One name, saluted if it is one of the Fourteen and left alone if it is not. */
    public String of(Object name, boolean arabic) {
        String text = name == null ? "" : name.toString().trim();
        if (text.isEmpty()) {
            return "";
        }
        String salute = (arabic ? AR : EN).get(text);
        return salute == null ? text : text + " " + salute;
    }

    /** A list of them, each saluted, joined as the caller would have joined them. */
    public String join(List<?> names, String separator, boolean arabic) {
        if (names == null || names.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Object name : names) {
            String one = of(name, arabic);
            if (one.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(separator);
            }
            out.append(one);
        }
        return out.toString();
    }
    /**
     * The titles that name one of the Fourteen and nobody else, for saluting inside a
     * sentence.
     *
     * <p>Deliberately not every form the maps above hold. «ʿAlī», «al-Ḥasan» and
     * «al-Ḥusayn» are borne by hundreds of narrators — al-Ḥasan ibn Maḥbūb among them —
     * and a blind replacement would salute them in their own names. These nine are
     * titles no narrator carries, and the lookahead keeps even those from firing on a
     * «al-Hādī ibn …» that is somebody's father.
     */
    private static final java.util.regex.Pattern TITLES_EN = java.util.regex.Pattern.compile(
            "(al-(?:\u1e62\u0101diq|B\u0101qir|Ri\u1e0d\u0101|K\u0101\u1e93im|Jaw\u0101d|H\u0101d\u012b"
            + "|\u02bfAskar\u012b|Sajj\u0101d|Mahd\u012b))(?!\\s*\\(|\\s+ibn\\b|\\s+b\\.)");

    private static final java.util.regex.Pattern TITLES_AR = java.util.regex.Pattern.compile(
            "(\u0627\u0644(?:\u0635\u0627\u062f\u0642|\u0628\u0627\u0642\u0631|\u0631\u0636\u0627"
            + "|\u0643\u0627\u0638\u0645|\u062c\u0648\u0627\u062f|\u0647\u0627\u062f\u064a"
            + "|\u0639\u0633\u0643\u0631\u064a|\u0633\u062c\u0627\u062f|\u0645\u0647\u062f\u064a))"
            + "(?!\\s*(?:\u0639\u0644\u064a\u0647|\u0635\u0644\u0649)|\\s+\u0628\u0646\\b)");

    /**
     * A sentence with one of them in it, saluted in place.
     *
     * <p>For prose the index supplies — the one-line summary a page opens with, which
     * says who a man kept company with. The salutation belongs to the reading of that
     * sentence as much as to a name printed on its own.
     */
    public String salute(Object text, boolean arabic) {
        String body = text == null ? "" : text.toString();
        if (body.isEmpty()) {
            return body;
        }
        return arabic
                ? TITLES_AR.matcher(body).replaceAll("$1 " + IMAM_AR)
                : TITLES_EN.matcher(body).replaceAll("$1 " + IMAM_EN);
    }

}
