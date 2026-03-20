package com.rewayaat.tafsir.extractors;

import com.rewayaat.tafsir.TafsirDocument;
import com.rewayaat.tafsir.VerseReferenceParser;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;

/**
 * Extractor for "Al-Bayan fi Tafsir al-Quran" (The Elucidation of the Exegesis of the Quran).
 *
 * Source: al-islam.org
 * Coverage: Quranic sciences methodology (not verse-by-verse)
 * Author: Al-Khoei
 * Format: HTML with chapter-based organization
 *
 * Features:
 * - Not a traditional verse-by-verse tafsir
 * - Work on Quranic sciences, exegesis methodology
 * - Extract as reference document per chapter rather than per verse
 */
public class AlBayanExtractor extends AlIslamHtmlExtractor {

    private static final String BASE_URL = "https://al-islam.org/al-bayan-tafsir-quran";

    @Override
    protected String getBaseUrl() {
        return BASE_URL;
    }

    @Override
    protected int[] getVolumeNumbers() {
        return new int[]{1};
    }

    @Override
    public String getTafsirSlug() {
        return "al-bayan";
    }

    @Override
    public String getTafsirName() {
        return "Al-Bayan fi Tafsir al-Quran (The Elucidation of Quran Exegesis)";
    }

    @Override
    protected String getVolumeUrl(int volume) {
        return BASE_URL;
    }

    @Override
    protected List<String> extractSectionUrls(org.jsoup.nodes.Document volumeIndex) {
        List<String> urls = new ArrayList<>();

        // Look for chapter links
        Elements links = volumeIndex.select("a[href]");

        for (Element link : links) {
            String href = link.attr("href");
            String linkText = link.text().toLowerCase();

            // Look for chapter/section links
            if (linkText.contains("chapter") || linkText.contains("section") ||
                linkText.contains("part") || linkText.contains("topic")) {
                String fullUrl = ensureAbsoluteUrl(href);
                if (isSectionPage(fullUrl)) {
                    urls.add(fullUrl);
                }
            }
        }

        // Include main page if no sections found
        if (urls.isEmpty()) {
            urls.add(BASE_URL);
        }

        return urls;
    }

    @Override
    protected boolean isSectionPage(String url) {
        // Include pages that look like content chapters
        return super.isSectionPage(url)
                && !url.equals(BASE_URL)
                && !url.matches(".*/$")
                && (url.contains("chapter") || url.contains("section") || url.contains("al-bayan"));
    }

    @Override
    protected TafsirDocument createDocument(VerseReferenceParser.ParsedReference parsedRef,
                                           String commentary, String verseText,
                                           String sectionTitle, String url) {
        // Al-Bayan is not verse-by-verse, so documents don't always have verse references
        TafsirDocument doc = new TafsirDocument();
        doc.setTafsirSlug(getTafsirSlug());
        doc.setTafsirName(getTafsirName());
        doc.setCommentaryText(commentary.trim());
        doc.setSectionTitle(sectionTitle);
        doc.setSourceUrl(url);
        doc.setLanguage("en");
        doc.computeWordCount();

        // If verse reference was found, include it
        if (parsedRef != null && parsedRef.isValid()) {
            doc.setSurahNumber(parsedRef.surahNumber);
            doc.setAyahStart(parsedRef.ayahStart);
            doc.setAyahEnd(parsedRef.ayahEnd);
            doc.setVerseKey(parsedRef.getVerseKey());
            doc.setVerseKeys(parsedRef.getVerseKeys());
        }

        return doc;
    }

    @Override
    protected String extractCommentaryText(org.jsoup.nodes.Document page) {
        // Al-Bayan may have different content structure
        String content = super.extractCommentaryText(page);

        // If content is short, this might be a methodology chapter
        // Include it anyway as it's valuable reference material
        return content;
    }
}
