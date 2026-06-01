package com.rewayaat.tafsir.extractors;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.rewayaat.tafsir.TafsirDocument;
import com.rewayaat.tafsir.VerseReferenceParser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

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
    private static final Pattern CROSS_REFERENCE_ONLY = Pattern.compile("(?i)^(see commentary.*|see note.*|cf\\..*)$");
    private static final Pattern THIN_GLOSS = Pattern.compile(
            "(?i)^(?:this|these|it|may|the|for|regarding|one|all|in|do not|made|means|refers?|refer|when|what|who).*(?:\\(a\\.p\\.\\)|see |refer to |cross-reference|notes? on |commentary).*");

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
        return BASE_URL + juz + "-mirza-mahdi-pooya-sv-mir-ahmad-ali";
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

    @Override
    protected List<TafsirDocument> extractMultiSectionDocuments(Document page, String url) {
        List<TafsirDocument> documents = new ArrayList<>();
        Element body = page.selectFirst(".field-name-body .field-item, .field-item.even, article, main, body");
        if (body == null) {
            return documents;
        }

        VerseReferenceParser.ParsedReference pageContext = null;
        Elements rangeHeadings = body.select("h2");
        for (Element heading : rangeHeadings) {
            VerseReferenceParser.ParsedReference parsed = VerseReferenceParser.parse(heading.text());
            if (parsed != null && parsed.isValid()) {
                pageContext = parsed;
                break;
            }
        }

        if (pageContext == null) {
            return documents;
        }

        for (Element heading : body.select("h3")) {
            VerseReferenceParser.ParsedReference parsedRef =
                    parseRelativeReference(heading.text(), pageContext, pageContext);
            if (parsedRef == null || !parsedRef.isValid()) {
                continue;
            }

            String commentary = extractContentAfterHeading(heading);
            if (commentary == null || commentary.length() < 50) {
                continue;
            }

            TafsirDocument doc = createDocument(parsedRef, commentary, null, heading.text(), url);
            if (doc != null) {
                documents.add(doc);
            }
        }

        return mergeThinAdjacentDocuments(documents);
    }

    private List<TafsirDocument> mergeThinAdjacentDocuments(List<TafsirDocument> documents) {
        if (documents.size() < 2) {
            return documents;
        }

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

        String normalized = current.getCommentaryText() == null ? "" : current.getCommentaryText().replace('\n', ' ').trim();
        int wordCount = current.getCommentaryWordCount() == null ? 0 : current.getCommentaryWordCount();
        if (wordCount < 40) {
            return true;
        }

        return CROSS_REFERENCE_ONLY.matcher(normalized).matches()
                || THIN_GLOSS.matcher(normalized).matches();
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
        merged.setCommentaryText(normalizeCommentary(
                first.getCommentaryText().trim() + "\n\n" + second.getCommentaryText().trim()));
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
