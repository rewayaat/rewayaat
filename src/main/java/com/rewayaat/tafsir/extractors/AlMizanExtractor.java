package com.rewayaat.tafsir.extractors;

import com.rewayaat.tafsir.TafsirDocument;
import com.rewayaat.tafsir.VerseReferenceParser;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Extractor for "Tafsir al-Mizan (WOFIS)".
 *
 * Source: al-islam.org
 * Coverage: Surahs 1-3 only (7 published English volumes currently reachable on al-islam.org)
 * Format: HTML with section-based pages
 *
 * Features:
 * - Very long commentary per verse (10-30 pages)
 * - Philosophical and cross-referential style
 * - May need to handle extremely long documents
 */
public class AlMizanExtractor extends AlIslamHtmlExtractor {

    private static final String BASE_URL = "https://al-islam.org/al-mizan-exegesis-quran-volume-";
    private static final int[] VOLUMES = {1, 2, 3, 4, 5, 6, 7};
    private static final Pattern GLOSS_ONLY = Pattern.compile("^[^\\n]{1,120}\\s*=\\s*[^\\n]{1,120}$");

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
        return BASE_URL + volume + "-sayyid-muhammad-husayn-tabatabai";
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

    @Override
    protected List<TafsirDocument> extractMultiSectionDocuments(Document page, String url) {
        List<TafsirDocument> baseDocuments = super.extractMultiSectionDocuments(page, url);
        if (baseDocuments.size() < 2) {
            return baseDocuments;
        }
        return mergeThinAdjacentDocuments(baseDocuments);
    }

    private List<TafsirDocument> mergeThinAdjacentDocuments(List<TafsirDocument> documents) {
        List<TafsirDocument> merged = new ArrayList<>();
        TafsirDocument pending = null;

        for (TafsirDocument current : documents) {
            if (pending == null) {
                pending = current;
                continue;
            }

            if (shouldMerge(pending, current)) {
                pending = mergeDocuments(pending, current);
                continue;
            }

            merged.add(pending);
            pending = current;
        }

        if (pending != null) {
            merged.add(pending);
        }

        return merged;
    }

    private boolean shouldMerge(TafsirDocument current, TafsirDocument next) {
        if (!current.getSurahNumber().equals(next.getSurahNumber())) {
            return false;
        }
        if (current.getAyahEnd() + 1 != next.getAyahStart()) {
            return false;
        }
        if (current.getCommentaryWordCount() != null && current.getCommentaryWordCount() >= 25) {
            return false;
        }
        if (!isGlossOnly(current.getCommentaryText()) && (current.getCommentaryWordCount() == null
                || current.getCommentaryWordCount() >= 18)) {
            return false;
        }
        return next.getCommentaryWordCount() != null && next.getCommentaryWordCount() >= 25;
    }

    private boolean isGlossOnly(String text) {
        String normalized = text == null ? "" : text.replace('\n', ' ').trim();
        return GLOSS_ONLY.matcher(normalized).matches();
    }

    private TafsirDocument mergeDocuments(TafsirDocument first, TafsirDocument second) {
        TafsirDocument merged = new TafsirDocument();
        merged.setTafsirSlug(first.getTafsirSlug());
        merged.setTafsirName(first.getTafsirName());
        merged.setSurahNumber(first.getSurahNumber());
        merged.setAyahStart(first.getAyahStart());
        merged.setAyahEnd(second.getAyahEnd());
        merged.setVerseKey(first.getSurahNumber() + ":" + first.getAyahStart());
        merged.setVerseKeys(buildVerseKeys(first.getSurahNumber(), first.getAyahStart(), second.getAyahEnd()));
        merged.setVerseTextEnglish(second.getVerseTextEnglish());
        merged.setCommentaryText(first.getCommentaryText().trim() + "\n\n" + second.getCommentaryText().trim());
        merged.setSectionTitle("Verses " + first.getSurahNumber() + ":" + first.getAyahStart() + "-" + second.getAyahEnd());
        merged.setSourceUrl(first.getSourceUrl());
        merged.setLanguage(first.getLanguage());
        merged.computeWordCount();
        return merged;
    }

    private List<String> buildVerseKeys(int surahNumber, int ayahStart, int ayahEnd) {
        List<String> verseKeys = new ArrayList<>();
        for (int ayah = ayahStart; ayah <= ayahEnd; ayah++) {
            verseKeys.add(surahNumber + ":" + ayah);
        }
        return verseKeys;
    }
}
