package com.rewayaat.tafsir.extractors;

import com.rewayaat.tafsir.TafsirDocument;
import com.rewayaat.tafsir.VerseReferenceParser;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;

/**
 * Extractor for "A Commentary on the Chapter of Praise (Tafsir Surah al-Hamd)"
 * by Imam Khomeini.
 *
 * Source: al-islam.org
 * Coverage: Surah al-Fatiha only (4-part mystical commentary)
 * Format: HTML with thematic rather than verse-by-verse structure
 *
 * Features:
 * - 4-part mystical/philosophical commentary on Surah al-Fatiha
 * - Not traditional verse-by-verse tafsir
 * - Extract as document-per-part with ayah_start=1, ayah_end=7 for entire Fatiha
 */
public class KhomeiniHamdExtractor extends AlIslamHtmlExtractor {

    private static final String BASE_URL = "https://al-islam.org/a-commentary-on-the-chapter-of-praise-tafsir-surah-al-hamd";

    @Override
    protected String getBaseUrl() {
        return BASE_URL;
    }

    @Override
    protected int[] getVolumeNumbers() {
        // Single volume with 4 parts
        return new int[]{1};
    }

    @Override
    public String getTafsirSlug() {
        return "khomeini-hamd";
    }

    @Override
    public String getTafsirName() {
        return "A Commentary on the Chapter of Praise (Tafsir Surah al-Hamd) - Imam Khomeini";
    }

    @Override
    protected String getVolumeUrl(int volume) {
        return BASE_URL;
    }

    @Override
    protected List<String> extractSectionUrls(org.jsoup.nodes.Document volumeIndex) {
        List<String> urls = new ArrayList<>();

        // Look for the 4 parts of the commentary
        Elements links = volumeIndex.select("a[href]");

        for (Element link : links) {
            String href = link.attr("href");
            String linkText = link.text().toLowerCase();

            // Look for section/part links
            if (linkText.contains("part") || linkText.contains("section") ||
                linkText.contains("chapter") || linkText.matches(".*\\d+.*")) {
                String fullUrl = ensureAbsoluteUrl(href);
                if (isSectionPage(fullUrl)) {
                    urls.add(fullUrl);
                }
            }
        }

        // If no sections found, the main page might have all content
        if (urls.isEmpty()) {
            urls.add(BASE_URL);
        }

        return urls;
    }

    @Override
    protected boolean isSectionPage(String url) {
        // Include the main page and any section pages
        return url.contains("al-islam.org") &&
               (url.contains("chapter-of-praise") || url.contains("tafsir-surah-al-hamd")) &&
               !url.contains("/print") && !url.contains("/download");
    }

    @Override
    protected TafsirDocument createDocument(VerseReferenceParser.ParsedReference parsedRef,
                                           String commentary, String verseText,
                                           String sectionTitle, String url) {
        // Khomeini's commentary covers the entire Fatiha
        // Set ayah range to cover all of Fatiha (1:1-1:7)
        TafsirDocument doc = new TafsirDocument();
        doc.setTafsirSlug(getTafsirSlug());
        doc.setTafsirName(getTafsirName());
        doc.setSurahNumber(1);
        doc.setAyahStart(1);
        doc.setAyahEnd(7);
        doc.setVerseKey("1:1");
        doc.setVerseKeys(java.util.Arrays.asList("1:1", "1:2", "1:3", "1:4", "1:5", "1:6", "1:7"));
        doc.setCommentaryText(commentary.trim());
        doc.setSectionTitle(sectionTitle);
        doc.setSourceUrl(url);
        doc.setLanguage("en");
        doc.computeWordCount();
        return doc;
    }
}
