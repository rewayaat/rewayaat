package com.rewayaat.tafsir.extractors;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import java.util.List;

/**
 * Extractor for "The Holy Quran: The Final Testament - English Translation of the
 * Meanings of the Quran with Commentary by Pooya/Mir Ahmad Ali".
 *
 * Source: al-islam.org
 * Coverage: Full Quran (30 juz-based segments)
 * Format: HTML with juz-based structure
 *
 * Features:
 * - Organized by juz (30 divisions)
 * - Includes Arabic text + English translation + Pooya commentary notes
 * - Sections follow Quranic order within each juz
 */
public class PooyaYazdiExtractor extends AlIslamHtmlExtractor {

    private static final String BASE_URL = "https://al-islam.org/holy-quran-final-testament-juz-";
    private static final int[] JUZ_NUMBERS = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                                               11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
                                               21, 22, 23, 24, 25, 26, 27, 28, 29, 30};

    @Override
    protected String getBaseUrl() {
        return BASE_URL;
    }

    @Override
    protected int[] getVolumeNumbers() {
        return JUZ_NUMBERS;
    }

    @Override
    public String getTafsirSlug() {
        return "pooya-mir-ahmad-ali";
    }

    @Override
    public String getTafsirName() {
        return "The Holy Quran: The Final Testament - Pooya/Mir Ahmad Ali";
    }

    @Override
    protected String getVolumeUrl(int juz) {
        // URL pattern: holy-quran-final-testament-juz-{N}
        return BASE_URL + juz;
    }

    @Override
    protected List<String> extractSectionUrls(Document volumeIndex) {
        // Pooya structure may have different navigation
        // Try to find links that look like verse sections
        List<String> urls = super.extractSectionUrls(volumeIndex);

        // Filter for URLs that look like verse sections
        return urls.stream()
                .filter(url -> url.contains("surah") ||
                             url.matches(".*\\d+.*"))
                .toList();
    }

    @Override
    protected String extractCommentaryText(Document page) {
        // Pooya commentary is often in specific blocks
        // Try to extract the commentary notes separately from translation
        String[] commentarySelectors = {
                ".commentary", ".notes", ".tafsir",
                "[class*='commentary']", "[class*='tafsir']"
        };

        for (String selector : commentarySelectors) {
            Element element = page.selectFirst(selector);
            if (element != null) {
                String text = element.text();
                if (text.length() > 100) {
                    return text;
                }
            }
        }

        // Fallback to default extraction
        return super.extractCommentaryText(page);
    }
}
