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
}
