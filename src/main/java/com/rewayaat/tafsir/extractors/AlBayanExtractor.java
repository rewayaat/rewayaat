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
    private static final String CHAPTER_URL_PREFIX =
            "https://al-islam.org/al-bayan-fi-tafsir-al-quran-prolegomena-quran-";

    private static final String BASE_URL =
            "https://al-islam.org/al-bayan-fi-tafsir-al-quran-prolegomena-quran-ayatullah-sayyid-abulqasim-al-khui";

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
        Set<String> urls = new LinkedHashSet<>();

        // Look for top-level numbered chapter pages.
        Elements links = volumeIndex.select("a[href]");

        for (Element link : links) {
            String fullUrl = stripFragment(ensureAbsoluteUrl(link.attr("href")));
            if (isSectionPage(fullUrl)) {
                urls.add(fullUrl);
            }
        }

        // If discovery fails, keep the base page as a fallback.
        if (urls.isEmpty()) {
            urls.add(BASE_URL);
        }

        return new ArrayList<>(urls);
    }

    @Override
    protected boolean isSectionPage(String url) {
        if (!super.isSectionPage(url)) {
            return false;
        }
        if (!url.startsWith(CHAPTER_URL_PREFIX)) {
            return false;
        }
        return url.matches("^https://al-islam\\.org/al-bayan-fi-tafsir-al-quran-prolegomena-quran-[^/]+/[0-9]+-[^?#/]+$");
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

        TafsirDocument doc = createDocument(null, commentary, null, extractSectionTitle(page), url);
        if (doc != null) {
            documents.add(doc);
        }
        return documents;
    }

    @Override
    protected String extractCommentaryText(org.jsoup.nodes.Document page) {
        // Al-Bayan may have different content structure
        String content = super.extractCommentaryText(page);

        // If content is short, this might be a methodology chapter
        // Include it anyway as it's valuable reference material
        return content;
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
}
