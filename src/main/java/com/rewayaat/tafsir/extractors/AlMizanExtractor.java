package com.rewayaat.tafsir.extractors;

import com.rewayaat.tafsir.TafsirDocument;
import com.rewayaat.tafsir.VerseReferenceParser;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;

/**
 * Extractor for "Tafsir al-Mizan (WOFIS)".
 *
 * Source: al-islam.org
 * Coverage: Surahs 1-3 only (13 volumes)
 * Format: HTML with section-based pages
 *
 * Features:
 * - Very long commentary per verse (10-30 pages)
 * - Philosophical and cross-referential style
 * - May need to handle extremely long documents
 */
public class AlMizanExtractor extends AlIslamHtmlExtractor {

    private static final String BASE_URL = "https://al-islam.org/tafsir-al-mizan-vol-";
    private static final int[] VOLUMES = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13};

    @Override
    protected String getBaseUrl() {
        return BASE_URL;
    }

    @Override
    protected int[] getVolumeNumbers() {
        return VOLUMES;
    }

    @Override
    public String getTafsirSlug() {
        return "al-mizan";
    }

    @Override
    public String getTafsirName() {
        return "Tafsir al-Mizan (WOFIS)";
    }

    @Override
    protected String getVolumeUrl(int volume) {
        return BASE_URL + volume;
    }

    @Override
    protected boolean isSectionPage(String url) {
        // Al-Mizan section URLs often contain verse references or section numbers
        return super.isSectionPage(url)
                && !url.matches(".*vol-\\d+/?$")
                && (url.contains("verse") || url.contains("surah") || url.contains("section"));
    }

    @Override
    protected List<String> extractSectionUrls(Document volumeIndex) {
        List<String> urls = new ArrayList<>();

        // Al-Mizan has specific navigation patterns
        // Look for links that are verse/section specific
        Elements links = volumeIndex.select("a[href]");

        for (Element link : links) {
            String href = link.attr("href");
            String fullUrl = ensureAbsoluteUrl(href);

            // Filter for content pages (exclude navigation, external links)
            if (isAlMizanContentPage(href, link.text())) {
                urls.add(fullUrl);
            }
        }

        return urls;
    }

    private boolean isAlMizanContentPage(String href, String linkText) {
        // Exclude navigation and utility links
        if (href.startsWith("/print") || href.startsWith("/download") ||
            href.startsWith("http") && !href.contains("al-islam.org")) {
            return false;
        }

        // Include links that look like verse/section references
        String lower = href.toLowerCase();
        String lowerText = linkText.toLowerCase();

        return lower.contains("surah") || lower.contains("verse") ||
               lower.contains("section") || lowerText.matches(".*\\d+:\\d+.*") ||
               lowerText.matches(".*chapter.*\\d+.*");
    }

    @Override
    protected String extractCommentaryText(Document page) {
        // Al-Mizan has very long commentary - ensure we get the full content
        String[] contentSelectors = {
                ".content", ".field-item", ".article-content",
                "[class*='tafsir']", "[class*='commentary']"
        };

        for (String selector : contentSelectors) {
            Element content = page.selectFirst(selector);
            if (content != null) {
                // Remove navigation elements
                content.select("nav, .pagination, .breadcrumb").remove();

                String text = content.text();
                if (text.length() > 200) { // Al-Mizan has long commentaries
                    return text;
                }
            }
        }

        return super.extractCommentaryText(page);
    }

    @Override
    protected TafsirDocument createDocument(VerseReferenceParser.ParsedReference parsedRef,
                                           String commentary, String verseText,
                                           String sectionTitle, String url) {
        TafsirDocument doc = super.createDocument(parsedRef, commentary, verseText, sectionTitle, url);

        // Al-Mizan has very long commentaries - ensure word count is accurate
        // No truncation at extraction time, store full text
        if (doc.getCommentaryText() != null) {
            doc.computeWordCount();
        }

        return doc;
    }
}
