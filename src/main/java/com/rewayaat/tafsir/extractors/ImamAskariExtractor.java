package com.rewayaat.tafsir.extractors;

import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;

/**
 * Extractor for "Tafsir of Imam Hasan al-Askari".
 *
 * Source: al-islam.org
 * Coverage: Surah al-Fatiha and Surah al-Baqarah only
 * Format: HTML with large verse range groupings
 *
 * Features:
 * - Sections like "Exegesis of Surah Baqarah: Verses 1-62" group large verse ranges
 * - Covers only Surah 1 (al-Fatiha) and Surah 2 (al-Baqarah)
 */
public class ImamAskariExtractor extends AlIslamHtmlExtractor {

    private static final String BASE_URL = "https://al-islam.org/tafseer-imam-hasan-askari";

    @Override
    protected String getBaseUrl() {
        return BASE_URL;
    }

    @Override
    protected int[] getVolumeNumbers() {
        // Single volume covering Fatiha and Baqarah
        return new int[]{1};
    }

    @Override
    public String getTafsirSlug() {
        return "imam-askari";
    }

    @Override
    public String getTafsirName() {
        return "Tafsir of Imam Hasan al-Askari";
    }

    @Override
    protected String getVolumeUrl(int volume) {
        return BASE_URL;
    }

    @Override
    protected List<String> extractSectionUrls(org.jsoup.nodes.Document volumeIndex) {
        List<String> urls = new ArrayList<>();

        // Imam Askari has sections for large verse ranges
        Elements links = volumeIndex.select("a[href]");

        for (Element link : links) {
            String href = link.attr("href");
            String linkText = link.text().toLowerCase();

            // Look for links that mention verses, surahs, or exegesis
            if (linkText.contains("verse") || linkText.contains("surah") ||
                linkText.contains("exegesis") || linkText.contains("aya")) {
                String fullUrl = ensureAbsoluteUrl(href);
                if (isSectionPage(fullUrl)) {
                    urls.add(fullUrl);
                }
            }
        }

        return urls;
    }

    @Override
    protected boolean isSectionPage(String url) {
        // Exclude main page and navigation links
        return super.isSectionPage(url)
                && !url.equals(BASE_URL)
                && !url.matches(".*/$")
                && (url.contains("surah") || url.contains("verse") || url.contains("aya"));
    }
}
