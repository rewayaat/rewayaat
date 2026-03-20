package com.rewayaat.tafsir.extractors;

/**
 * Generic extractor for single-surah commentaries from al-islam.org.
 * Handles various tafsirs that focus on a single surah.
 *
 * Configured with:
 * - The book slug/URL on al-islam.org
 * - The target surah number
 * - Display name
 *
 * Examples:
 * - Commentary of Suratul Jinn (Surah 72)
 * - Tafsir Surah Yusuf (Surah 12) by Shaykh Ali Abdur-Rasheed
 * - Tafsir Surah al-Kahf (Surah 18) by Shaykh Ali Abdur-Rasheed
 * - Tafsir Surah Maryam (Surah 19) by Shaykh Ali Abdur-Rasheed
 */
public class SingleSurahExtractor extends AlIslamHtmlExtractor {

    private final String urlSlug;
    private final int surahNumber;
    private final String displayName;

    public SingleSurahExtractor(String urlSlug, int surahNumber, String displayName) {
        this.urlSlug = urlSlug;
        this.surahNumber = surahNumber;
        this.displayName = displayName;
    }

    @Override
    protected String getBaseUrl() {
        return "https://al-islam.org/" + urlSlug;
    }

    @Override
    protected int[] getVolumeNumbers() {
        return new int[]{1};
    }

    @Override
    public String getTafsirSlug() {
        return urlSlug.replaceAll("/", "-");
    }

    @Override
    public String getTafsirName() {
        return displayName;
    }

    @Override
    protected String getVolumeUrl(int volume) {
        return getBaseUrl();
    }

    @Override
    protected boolean isSectionPage(String url) {
        // For single-surah commentaries, include pages with verse references
        return super.isSectionPage(url)
                && !url.equals(getBaseUrl())
                && (url.contains("verse") || url.contains("aya") || url.contains("section"));
    }

    /**
     * Factory method for Surah al-Jinn commentary.
     */
    public static SingleSurahExtractor forJinn() {
        return new SingleSurahExtractor(
            "commentary-suratul-jinn",
            72,
            "Commentary of Suratul Jinn (from Tafsir Nemuneh by Makarem Shirazi)"
        );
    }

    /**
     * Factory method for Surah Yusuf commentary.
     */
    public static SingleSurahExtractor forYusuf() {
        return new SingleSurahExtractor(
            "tafsir-surah-yusuf",
            12,
            "Tafsir Surah Yusuf (by Shaykh Ali Abdur-Rasheed)"
        );
    }

    /**
     * Factory method for Surah al-Kahf commentary.
     */
    public static SingleSurahExtractor forKahf() {
        return new SingleSurahExtractor(
            "tafsir-surah-al-kahf",
            18,
            "Tafsir Surah al-Kahf (by Shaykh Ali Abdur-Rasheed)"
        );
    }

    /**
     * Factory method for Surah Maryam commentary.
     */
    public static SingleSurahExtractor forMaryam() {
        return new SingleSurahExtractor(
            "tafsir-surah-maryam",
            19,
            "Tafsir Surah Maryam (by Shaykh Ali Abdur-Rasheed)"
        );
    }
}
