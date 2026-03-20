package com.rewayaat.tafsir.extractors;

import com.rewayaat.tafsir.TafsirDocument;
import com.rewayaat.tafsir.VerseReferenceParser;

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

    private static final String BASE_URL = "https://al-islam.org/fatima-zahra-noble-quran";

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
        // This source likely has chapters or sections for different verses
        return super.isSectionPage(url)
                && !url.equals(BASE_URL)
                && (url.contains("chapter") || url.contains("verse") || url.contains("surah"));
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
