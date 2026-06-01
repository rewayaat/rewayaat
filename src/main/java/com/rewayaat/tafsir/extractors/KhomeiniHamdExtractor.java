package com.rewayaat.tafsir.extractors;

import com.rewayaat.tafsir.TafsirDocument;
import com.rewayaat.tafsir.VerseReferenceParser;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
        Set<String> urls = new LinkedHashSet<>();

        // Look for the 4 parts of the commentary
        Elements links = volumeIndex.select("a[href]");

        for (Element link : links) {
            String href = link.attr("href");
            String fullUrl = stripFragment(ensureAbsoluteUrl(href));
            if (isSectionPage(fullUrl)) {
                urls.add(fullUrl);
            }
        }

        // If no parts were found, the main page might contain all content.
        if (urls.isEmpty()) {
            urls.add(BASE_URL);
        }

        return new ArrayList<>(urls);
    }

    @Override
    protected boolean isSectionPage(String url) {
        if (!url.startsWith(BASE_URL)) {
            return false;
        }
        if (url.contains("/print") || url.contains("/download")) {
            return false;
        }
        return url.matches("^" + java.util.regex.Pattern.quote(BASE_URL) + "/part-[^?#/]+.*$");
    }

    @Override
    protected List<TafsirDocument> extractFromSectionPage(Document page, String url) {
        List<TafsirDocument> documents = new ArrayList<>();
        Element body = selectPrimaryBody(page);
        if (body != null) {
            List<TafsirDocument> sections = extractStructuredSections(body, url);
            if (!sections.isEmpty()) {
                return sections;
            }
        }

        String commentary = extractCommentaryText(page);
        if (commentary == null || commentary.isBlank()) {
            return documents;
        }
        String sectionTitle = extractSectionTitle(page);
        TafsirDocument doc = createDocument(null, commentary, null, sectionTitle, url);
        if (doc != null) {
            documents.add(doc);
        }
        return documents;
    }

    private List<TafsirDocument> extractStructuredSections(Element body, String url) {
        List<TafsirDocument> documents = new ArrayList<>();
        Element currentHeading = null;
        StringBuilder currentContent = new StringBuilder();

        for (Element child : body.children()) {
            if (isSectionHeading(child)) {
                addStructuredDocument(documents, currentHeading, currentContent, url);
                currentHeading = child;
                currentContent = new StringBuilder();
                continue;
            }

            String text = child.text().trim();
            if (!text.isEmpty()) {
                if (currentContent.length() > 0) {
                    currentContent.append("\n\n");
                }
                currentContent.append(text);
            }
        }

        addStructuredDocument(documents, currentHeading, currentContent, url);
        return documents;
    }

    private Element selectPrimaryBody(Document page) {
        Element body = page.selectFirst(".field-name-body .field-item, .field-name-body .field-item.even");
        if (body != null) {
            return body;
        }
        body = page.selectFirst("article .field-item.even, article, main");
        return body != null ? body : page.body();
    }

    private void addStructuredDocument(List<TafsirDocument> documents,
                                       Element heading,
                                       StringBuilder content,
                                       String baseUrl) {
        if (heading == null) {
            return;
        }
        String commentary = content.toString().trim();
        if (commentary.length() < 120) {
            return;
        }

        String sourceUrl = buildFragmentUrl(baseUrl, heading);
        TafsirDocument doc = createDocument(null, commentary, null, heading.text().trim(), sourceUrl);
        if (doc != null) {
            documents.add(doc);
        }
    }

    private boolean isSectionHeading(Element element) {
        String tag = element.tagName();
        return "h2".equals(tag) || "h3".equals(tag) || "h4".equals(tag);
    }

    private String buildFragmentUrl(String baseUrl, Element heading) {
        Element anchor = heading.selectFirst("[id], a[id], a[name]");
        if (anchor == null) {
            return baseUrl;
        }
        String fragment = anchor.hasAttr("id") ? anchor.attr("id") : anchor.attr("name");
        return fragment == null || fragment.isBlank() ? baseUrl : baseUrl + "#" + fragment;
    }

    private String stripFragment(String url) {
        return url.replaceFirst("#.*$", "");
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
        doc.setVerseKeys(java.util.Arrays.asList("1:1", "1:2", "1:3", "1:4", "1:5", "1:6", "1:7"));
        doc.setCommentaryText(commentary.trim());
        doc.setSectionTitle(sectionTitle);
        doc.setSourceUrl(url);
        doc.setLanguage("en");
        doc.computeWordCount();
        return doc;
    }
}
