package com.rewayaat.tafsir.extractors;

import com.rewayaat.tafsir.TafsirDocument;
import com.rewayaat.tafsir.VerseReferenceParser;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Extractor for "An Enlightening Commentary into the Light of the Holy Quran".
 *
 * Source: al-islam.org
 * Coverage: Full Quran (20 volumes)
 * Format: HTML with surah-based pages
 *
 * Structure:
 * - Volume pages contain links to surah pages (e.g., /surah-al-fatihah-chapter-1)
 * - Each surah page contains verse commentaries as anchor links
 * - Individual verses are sections within the surah page
 */
public class EnlighteningCommentaryExtractor extends AlIslamHtmlExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(EnlighteningCommentaryExtractor.class);
    private static final String BASE_URL = "https://al-islam.org/enlightening-commentary-light-holy-quran-vol-";
    private static final int[] VOLUMES = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20};

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
        return "enlightening-commentary";
    }

    @Override
    public String getTafsirName() {
        return "An Enlightening Commentary into the Light of the Holy Quran";
    }

    @Override
    protected String getVolumeUrl(int volume) {
        return BASE_URL + volume;
    }

    @Override
    protected boolean isSectionPage(String url) {
        // Only include surah pages and section pages
        String lower = url.toLowerCase();
        return lower.contains("/surah-") || lower.contains("/section-");
    }

    @Override
    protected List<String> extractSectionUrls(Document volumeIndex) {
        List<String> urls = new ArrayList<>();
        Elements links = volumeIndex.select("a[href]");

        for (Element link : links) {
            String href = link.attr("href");
            // Only include surah pages and section pages
            if (href.contains("/surah-") || href.contains("/section-")) {
                urls.add(href);
            }
        }

        return urls;
    }

    @Override
    protected List<TafsirDocument> extractFromSectionPage(Document page, String url) {
        List<TafsirDocument> documents = new ArrayList<>();

        // Look for h2 headings with "Verse" in the text (like "Surah Al-Fatiha, Verse 1")
        Elements verseHeadings = page.select("h2:contains(Verse)");

        LOGGER.debug("Found {} verse headings on page {}", verseHeadings.size(), url);

        if (verseHeadings.isEmpty()) {
            LOGGER.debug("No verse headings found, trying default extraction");
            return super.extractFromSectionPage(page, url);
        }

        // Extract content for each verse
        for (Element heading : verseHeadings) {
            String headingText = heading.text();

            // Try to parse verse reference from heading
            VerseReferenceParser.ParsedReference parsedRef = VerseReferenceParser.parse(headingText);
            if (parsedRef != null && parsedRef.isValid()) {
                LOGGER.debug("Parsed verse heading: {} -> {}", headingText, parsedRef);

                // Extract content after this heading until next h2
                String commentary = extractContentAfterHeading(page, heading, "h2");
                if (commentary != null && commentary.length() > 50) {
                    TafsirDocument doc = createDocument(parsedRef, commentary, null, headingText, url);
                    documents.add(doc);
                    LOGGER.debug("Created document for verse {}: {} words", parsedRef.getVerseKey(),
                            doc.getCommentaryWordCount());
                } else {
                    LOGGER.debug("Commentary too short for verse {}: {} chars", parsedRef.getVerseKey(),
                            commentary != null ? commentary.length() : 0);
                }
            } else {
                LOGGER.debug("Could not parse verse reference from: {}", headingText);
            }
        }

        LOGGER.info("Extracted {} documents from page", documents.size());
        return documents;
    }

    private String extractContentAfterHeading(Document page, Element heading, String stopTag) {
        StringBuilder content = new StringBuilder();

        LOGGER.debug("Extracting content after heading: {}", heading.text());

        // Use Jsoup's Element traversal methods
        Element current = heading.nextElementSibling();

        int iterations = 0;
        while (current != null && iterations < 100) { // Safety limit
            iterations++;

            LOGGER.debug("Processing element {}: {}", current.tagName(), current.className());

            // Stop at next heading of same level
            if (current.tagName().equals(stopTag)) {
                LOGGER.debug("Stopping at next {}", stopTag);
                break;
            }

            // Skip navigation and footer elements
            String className = current.className();
            if (className.contains("navigation") || className.contains("menu") ||
                className.contains("breadcrumb") || className.contains("footer")) {
                current = current.nextElementSibling();
                continue;
            }

            // Extract text content from this element and its children
            String text = current.text();
            if (text != null && !text.trim().isEmpty() && text.length() > 5) {
                if (content.length() > 0) {
                    content.append("\n\n");
                }
                content.append(text.trim());
                LOGGER.debug("Added {} characters of text", text.trim().length());
            }

            current = current.nextElementSibling();
        }

        LOGGER.debug("Extracted {} characters in {} iterations", content.length(), iterations);
        return content.toString();
    }
}
