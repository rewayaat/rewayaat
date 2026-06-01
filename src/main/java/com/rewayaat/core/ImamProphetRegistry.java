package com.rewayaat.core;

import java.util.Set;

/**
 * Registry of the 14 Infallibles (Prophet Muhammad, Fatima Zahra, and the 12 Imams)
 * with known Arabic and English name variants for detection in hadith chains.
 */
public final class ImamProphetRegistry {

    private static final Set<String> NORMALIZED_ARABIC = Set.of(
            "محمد", "احمد",
            "علي بن ابي طالب", "علي بن ابى طالب", "امير المومنين", "امير المؤمنين",
            "الحسن بن علي", "الحسن بن على", "ابو محمد الحسن",
            "الحسين بن علي", "الحسين بن على", "ابو عبد الله الحسين",
            "علي بن الحسين", "علي بن الحسين", "زين العابدين", "ابو محمد علي", "السجاد",
            "محمد بن علي", "ابو جعفر محمد", "الباقر",
            "جعفر بن محمد", "ابو عبد الله جعفر", "الصادق",
            "موسى بن جعفر", "ابو ابراهيم موسى", "ابو الحسن موسى", "الكاظم", "الكاظيم",
            "علي بن موسى", "ابو الحسن علي بن موسى", "الرضا", "الرضي",
            "محمد بن علي", "ابو جعفر محمد بن علي", "الجواد", "التقي",
            "علي بن محمد", "ابو الحسن علي بن محمد", "الهادي", "النقي",
            "الحسن بن علي", "ابو محمد الحسن بن علي", "العسكري",
            "محمد بن الحسن", "ابو القاسم محمد", "المهدي", "القائم", "صاحب الزمان", "الحجه", "الحجة",
            "فاطمه بنت محمد", "فاطمة الزهراء", "فاطمه الزهراء", "الزهراء", "البتول"
    );

    private static final Set<String> NORMALIZED_ENGLISH = Set.of(
            "muhammad", "ahmad",
            "ali ibn abi talib", "ali ibn abu talib", "commander of the faithful",
            "amir al-muminin", "amir al muiminin",
            "hasan ibn ali", "al-hasan ibn ali", "abu muhammad al-hasan",
            "husayn ibn ali", "al-husayn ibn ali", "hussein ibn ali", "abu abd allah al-husayn",
            "ali ibn al-husayn", "ali ibn al husayn", "zayn al-abidin", "zayn al abidin",
            "abu muhammad ali", "al-sajjad",
            "muhammad ibn ali", "abu jafar muhammad", "al-baqir",
            "jafar ibn muhammad", "jaafar ibn muhammad", "abu abd allah jafar",
            "abu abdallah jafar", "al-sadiq",
            "musa ibn jafar", "abu ibrahim musa", "abu al-hasan musa", "al-kadhim", "al-kazim",
            "ali ibn musa", "abu al-hasan ali ibn musa", "al-rida", "al-riza",
            "muhammad ibn ali", "abu jafar muhammad ibn ali", "al-jawad", "al-taqi",
            "ali ibn muhammad", "abu al-hasan ali ibn muhammad", "al-hadi", "al-naqi",
            "hasan ibn ali", "abu muhammad al-hasan ibn ali", "al-askari",
            "muhammad ibn al-hasan", "abu al-qasim muhammad", "al-mahdi", "al-qa'im",
            "sahib al-zaman", "al-hujjah",
            "fatima bint muhammad", "fatima al-zahra", "fatimah al-zahra", "al-zahra", "al-batul"
    );

    private static final Set<String> ARABIC_KUNYAHS = Set.of(
            "ابو عبد الله", "ابو جعفر", "ابو ابراهيم", "ابو الحسن", "ابو محمد",
            "ابو القاسم", "ابو طالب"
    );

    private static final Set<String> ENGLISH_KUNYAHS = Set.of(
            "abu abd allah", "abu abdallah", "abu abdullah",
            "abu jafar",
            "abu ibrahim",
            "abu al-hasan", "abu al hasan",
            "abu muhammad", "abu muhammed",
            "abu al-qasim", "abu al qasim",
            "abu talib"
    );

    private static final Set<String> HONORIFICS_AR = Set.of(
            "(ع)", "(عليه السلام)", "(عليهم السلام)", "(صلى الله عليه وآله)",
            "(صلى الله عليه واله)", "(ص)", "(عج)", "(عليهما السلام)"
    );

    private static final Set<String> HONORIFICS_EN = Set.of(
            "(as)", "(a.s.)", "(pbuh)", "(p.b.u.h.)", "(peace be upon him)",
            "(peace be upon them)", "(af)", "(a.j.)"
    );

    private ImamProphetRegistry() {
    }

    public static boolean isImamOrProphet(String arabicName, String englishName) {
        if (arabicName != null && !arabicName.isBlank()) {
            String normAr = stripHonorificsArabic(normalizeArabic(arabicName));
            if (NORMALIZED_ARABIC.contains(normAr)) {
                return true;
            }
        }
        if (englishName != null && !englishName.isBlank()) {
            String normEn = stripHonorificsEnglish(normalizeEnglish(englishName));
            if (NORMALIZED_ENGLISH.contains(normEn)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasImamHonorific(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String h : HONORIFICS_AR) {
            if (text.contains(h)) {
                return true;
            }
        }
        String lower = text.toLowerCase();
        for (String h : HONORIFICS_EN) {
            if (lower.contains(h)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeArabic(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw
                .replaceAll("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]", "")
                .replace('\u0640', ' ')
                .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
                .replace('ى', 'ي').replace('ة', 'ه').replace('ؤ', 'و').replace('ئ', 'ي')
                .replaceAll("[^\\p{IsArabic}\\s]+", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String normalizeEnglish(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .replace("ʿ", "").replace("ʾ", "").replace("ʻ", "")
                .replace("'", "").replace("\u2019", "")
                .toLowerCase()
                .replaceAll("[^a-z0-9\\s-]+", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String stripHonorificsArabic(String name) {
        String result = name;
        for (String h : HONORIFICS_AR) {
            result = result.replace(h, "");
        }
        return result.trim();
    }

    private static String stripHonorificsEnglish(String name) {
        String result = name;
        String lower = result;
        for (String h : HONORIFICS_EN) {
            lower = lower.replace(h, "");
        }
        return lower.trim();
    }
}
