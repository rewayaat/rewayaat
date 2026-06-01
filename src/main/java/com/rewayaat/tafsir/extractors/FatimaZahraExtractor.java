package com.rewayaat.tafsir.extractors;

import com.rewayaat.tafsir.TafsirDocument;
import com.rewayaat.tafsir.VerseReferenceParser;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extractor for "Fatima Zahra in the Noble Quran".
 *
 * Source: al-islam.org
 * Coverage: Verses connected to Fatima al-Zahra (Surahs 76, 97, 108)
 * Format: HTML with selected verses
 *
 * Features:
 * - Focuses on verses with relevance to Fatima al-Zahra
 * - Covers Surah al-Insan (76), Surah al-Qadr (97), Surah al-Kawthar (108)
 * - Not a continuous verse-by-verse tafsir
 */
public class FatimaZahraExtractor extends AlIslamHtmlExtractor {

    private static final String BASE_URL = "https://al-islam.org/fatima-zahra-noble-quran-naser-makarem-shirazi";
    private static final Pattern VERSE_REF = Pattern.compile("\\b(76|97|108):(\\d+)\\b");

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
        return "fatima-zahra";
    }

    @Override
    public String getTafsirName() {
        return "Fatima Zahra in the Noble Quran";
    }

    @Override
    protected String getVolumeUrl(int volume) {
        return BASE_URL;
    }

    @Override
    protected boolean isSectionPage(String url) {
        return super.isSectionPage(url)
                && !url.equals(BASE_URL)
                && (url.contains("chapter") || url.contains("verse") || url.contains("surah")
                || url.contains("contents") || url.contains("commentary") || url.contains("points"));
    }

    @Override
    protected List<TafsirDocument> extractFromSectionPage(Document page, String url) {
        List<TafsirDocument> documents = new ArrayList<>();
        Element body = page.selectFirst(".field-name-body .field-item, .field-item.even, article, main, body");
        if (body == null) {
            return documents;
        }

        for (Element heading : body.select("h2, h3, h4")) {
            String headingText = heading.text().trim();
            if (headingText.isEmpty()) {
                continue;
            }

            String commentary = extractContentAfterHeading(heading);
            if (commentary == null || commentary.length() < 80) {
                continue;
            }

            TafsirDocument doc = createSectionDocument(headingText, commentary, url, heading);
            if (doc != null) {
                documents.add(doc);
            }
        }

        return documents;
    }

    private TafsirDocument createSectionDocument(String sectionTitle, String commentary, String pageUrl, Element heading) {
        TafsirDocument doc = new TafsirDocument();
        doc.setTafsirSlug(getTafsirSlug());
        doc.setTafsirName(getTafsirName());
        doc.setCommentaryText(commentary.trim());
        doc.setSectionTitle(sectionTitle);
        doc.setLanguage("en");

        String anchor = heading.id();
        if (anchor == null || anchor.isBlank()) {
            Element anchorElement = heading.selectFirst("a[id], a[name]");
            if (anchorElement != null) {
                anchor = anchorElement.id();
                if (anchor == null || anchor.isBlank()) {
                    anchor = anchorElement.attr("name");
                }
            }
        }
        doc.setSourceUrl((anchor != null && !anchor.isBlank()) ? pageUrl + "#" + anchor : pageUrl + "#" + slugify(sectionTitle));

        Matcher matcher = VERSE_REF.matcher(sectionTitle + "\n" + commentary);
        Integer surah = null;
        Integer ayahStart = null;
        Integer ayahEnd = null;
        List<String> verseKeys = new ArrayList<>();

        while (matcher.find()) {
            int currentSurah = Integer.parseInt(matcher.group(1));
            int currentAyah = Integer.parseInt(matcher.group(2));

            if (surah == null) {
                surah = currentSurah;
                ayahStart = currentAyah;
                ayahEnd = currentAyah;
            } else if (surah.equals(currentSurah)) {
                ayahStart = Math.min(ayahStart, currentAyah);
                ayahEnd = Math.max(ayahEnd, currentAyah);
            } else {
                surah = null;
                ayahStart = null;
                ayahEnd = null;
                verseKeys.clear();
                break;
            }

            String verseKey = currentSurah + ":" + currentAyah;
            if (!verseKeys.contains(verseKey)) {
                verseKeys.add(verseKey);
            }
        }

        if (surah != null && ayahStart != null && ayahEnd != null) {
            doc.setSurahNumber(surah);
            doc.setAyahStart(ayahStart);
            doc.setAyahEnd(ayahEnd);
            if (!verseKeys.isEmpty()) {
                doc.setVerseKeys(verseKeys);
            }
        }

        doc.computeWordCount();
        return doc;
    }

    private String slugify(String value) {
        return value.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }

    @Override
    protected TafsirDocument createDocument(VerseReferenceParser.ParsedReference parsedRef,
                                           String commentary, String verseText,
                                           String sectionTitle, String url) {
        // Ensure only Surahs 76, 97, 108 are included
        if (parsedRef != null && parsedRef.isValid()) {
            int surah = parsedRef.surahNumber;
            if (surah == 76 || surah == 97 || surah == 108) {
                return super.createDocument(parsedRef, commentary, verseText, sectionTitle, url);
            }
        }

        // For sections without clear verse references, create with metadata only
        TafsirDocument doc = new TafsirDocument();
        doc.setTafsirSlug(getTafsirSlug());
        doc.setTafsirName(getTafsirName());
        doc.setCommentaryText(commentary.trim());
        doc.setSectionTitle(sectionTitle);
        doc.setSourceUrl(url);
        doc.setLanguage("en");
        doc.computeWordCount();
        return doc;
    }
}
