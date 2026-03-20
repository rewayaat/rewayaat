package com.rewayaat.tafsir.extractors;

/**
 * Extractor for "The Glorious Quran - A Translation and Commentary with Arabic Text by S.V. Mir Ahmed Ali"
 * Also known as "Divine Lights" by Chinoy.
 *
 * Source: al-islam.org
 * Coverage: Full Quran (7 manzil sets)
 * Format: HTML organized by manzil (7 divisions of Quran)
 *
 * Features:
 * - 7 manzil sets covering the entire Quran
 * - Organized by traditional Quranic divisions
 */
public class DivineLightsExtractor extends AlIslamHtmlExtractor {

    private static final String BASE_URL = "https://al-islam.org/glorious-quran-translated-commentary-divine-lights-set-";
    private static final int[] MANZIL_SETS = {1, 2, 3, 4, 5, 6, 7};

    @Override
    protected String getBaseUrl() {
        return BASE_URL;
    }

    @Override
    protected int[] getVolumeNumbers() {
        return MANZIL_SETS;
    }

    @Override
    public String getTafsirSlug() {
        return "divine-lights";
    }

    @Override
    public String getTafsirName() {
        return "The Glorious Quran - Divine Lights (Chinoy)";
    }

    @Override
    protected String getVolumeUrl(int manzil) {
        return BASE_URL + manzil;
    }

    @Override
    protected boolean isSectionPage(String url) {
        // Divine Lights section pages for individual surahs or verse groups
        return super.isSectionPage(url)
                && !url.matches(".*set-\\d+/?$");
    }
}
