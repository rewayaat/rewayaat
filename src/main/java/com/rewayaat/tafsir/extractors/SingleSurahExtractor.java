package com.rewayaat.tafsir.extractors;

import com.rewayaat.tafsir.TafsirDocument;
import com.rewayaat.tafsir.VerseReferenceParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generic extractor for single-surah commentaries from al-islam.org.
 * Handles various tafsirs that focus on a single surah.
 *
 * Configured with:
 * - The book slug/URL on al-islam.org
 * - The target surah number
 * - Display name
 *
 * Examples:
 * - Commentary of Suratul Jinn (Surah 72)
 * - Tafsir Surah Yusuf (Surah 12) by Shaykh Ali Abdur-Rasheed
 * - Tafsir Surah al-Kahf (Surah 18) by Shaykh Ali Abdur-Rasheed
 * - Tafsir Surah Maryam (Surah 19) by Shaykh Ali Abdur-Rasheed
 */
public class SingleSurahExtractor extends AlIslamHtmlExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(SingleSurahExtractor.class);
    private static final Pattern COMMENTARY_VERSE_HEADING =
            Pattern.compile("^commentary of verses?\\s+(\\d+)(?:\\s*(?:[-–—]|to|and|&)\\s*(\\d+))?.*",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final String urlSlug;
    private final int surahNumber;
    private final String displayName;
    private final String pdfUrl;

    public SingleSurahExtractor(String urlSlug, int surahNumber, String displayName) {
        this(urlSlug, surahNumber, displayName, null);
    }

    public SingleSurahExtractor(String urlSlug, int surahNumber, String displayName, String pdfUrl) {
        this.urlSlug = urlSlug;
        this.surahNumber = surahNumber;
        this.displayName = displayName;
        this.pdfUrl = pdfUrl;
    }

    @Override
    protected String getBaseUrl() {
        return "https://al-islam.org/" + urlSlug;
    }

    @Override
    protected int[] getVolumeNumbers() {
        return new int[]{1};
    }

    @Override
    public String getTafsirSlug() {
        return urlSlug.replaceAll("/", "-");
    }

    @Override
    public String getTafsirName() {
        return displayName;
    }

    @Override
    protected String getVolumeUrl(int volume) {
        return getBaseUrl();
    }

    @Override
    protected boolean isSectionPage(String url) {
        // For single-surah commentaries, include pages with verse references
        return super.isSectionPage(url)
                && !url.equals(getBaseUrl())
                && (url.contains("verse") || url.contains("aya") || url.contains("section"));
    }

    @Override
    public List<TafsirDocument> extract() throws ExtractionException {
        if (pdfUrl == null || pdfUrl.isBlank()) {
            if ("commentary-suratul-jinn-naser-makarem-shirazi".equals(urlSlug)) {
                return extractSingleHtmlPage();
            }
            return super.extract();
        }
        return extractFromPdf();
    }

    private List<TafsirDocument> extractSingleHtmlPage() throws ExtractionException {
        List<TafsirDocument> documents = new ArrayList<>();
        Document page = fetchDocument(getBaseUrl());
        if (page == null) {
            throw new ExtractionException("Failed to fetch page: " + getBaseUrl());
        }

        Element body = page.body();
        if (body == null) {
            return documents;
        }

        int matchedHeadings = 0;
        for (Element heading : body.select("h2, h3")) {
            Matcher matcher = COMMENTARY_VERSE_HEADING.matcher(heading.text().trim());
            if (!matcher.matches()) {
                continue;
            }
            matchedHeadings++;

            int ayahStart = Integer.parseInt(matcher.group(1));
            int ayahEnd = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : ayahStart;
            String commentary = extractContentAfterHeading(heading);
            if (commentary == null || commentary.length() < 100) {
                LOGGER.info("Skipping {} due to short commentary length {}", heading.text().trim(),
                        commentary == null ? 0 : commentary.length());
                continue;
            }

            TafsirDocument doc = createDocument(
                    new VerseReferenceParser.ParsedReference(surahNumber, ayahStart, ayahEnd),
                    commentary,
                    null,
                    heading.text().trim(),
                    getBaseUrl() + "#commentary-verse-" + ayahStart
            );
            if (doc != null) {
                documents.add(doc);
            }
        }

        LOGGER.info("Matched {} commentary headings on {}", matchedHeadings, getTafsirName());
        LOGGER.info("Extraction complete: {} documents from {}", documents.size(), getTafsirName());
        return documents;
    }

    private List<TafsirDocument> extractFromPdf() throws ExtractionException {
        List<TafsirDocument> documents = new ArrayList<>();
        Path pdfPath = cachePdf();

        if (pdfPath == null || !Files.exists(pdfPath)) {
            LOGGER.warn("PDF source unavailable for {}", getTafsirName());
            return documents;
        }

        try (PDDocument document = PDDocument.load(pdfPath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            String[] lines = text.split("\\r?\\n");
            List<SectionMarker> markers = findSectionMarkers(lines);

            for (int i = 0; i < markers.size(); i++) {
                SectionMarker marker = markers.get(i);
                int endIndex = (i + 1 < markers.size()) ? markers.get(i + 1).lineIndex : lines.length;
                SectionWindow window = expandWindowIfNeeded(marker, endIndex, i, markers, lines);
                String commentary = cleanCommentary(lines, marker.lineIndex + 1, window.endLineExclusive);

                if (commentary.length() < 120) {
                    continue;
                }

                TafsirDocument doc = new TafsirDocument();
                doc.setTafsirSlug(getTafsirSlug());
                doc.setTafsirName(getTafsirName());
                doc.setSurahNumber(surahNumber);
                doc.setAyahStart(marker.ayahStart);
                doc.setAyahEnd(window.ayahEnd);
                doc.setVerseKey(surahNumber + ":" + marker.ayahStart);
                doc.setVerseKeys(buildVerseKeys(marker.ayahStart, window.ayahEnd));
                doc.setCommentaryText(commentary);
                doc.setSectionTitle(formatSectionTitle(marker.ayahStart, window.ayahEnd));
                doc.setSourceUrl(pdfUrl + "#ayah-" + marker.ayahStart
                        + (window.ayahEnd > marker.ayahStart ? "-" + window.ayahEnd : ""));
                doc.setLanguage("en");
                doc.computeWordCount();
                documents.add(doc);
            }
        } catch (IOException e) {
            throw new ExtractionException("Failed to extract PDF tafsir from " + pdfUrl, e);
        }

        LOGGER.info("Extraction complete: {} documents from {}", documents.size(), getTafsirName());
        return documents;
    }

    private Path cachePdf() throws ExtractionException {
        String fileName = urlSlug.replace('/', '-') + ".pdf";
        Path cachePath = Paths.get(sourceDir, getTafsirSlug(), fileName);

        if (Files.exists(cachePath)) {
            return cachePath;
        }

        Path localFallback = resolveLocalFallbackPdf();
        if (localFallback != null && Files.exists(localFallback)) {
            try {
                Files.createDirectories(cachePath.getParent());
                Files.copy(localFallback, cachePath);
                return cachePath;
            } catch (IOException e) {
                throw new ExtractionException("Failed to copy local fallback PDF: " + localFallback, e);
            }
        }

        try {
            Files.createDirectories(cachePath.getParent());
            try (InputStream stream = new URL(pdfUrl).openStream()) {
                Files.copy(stream, cachePath);
            }
            return cachePath;
        } catch (IOException e) {
            throw new ExtractionException("Failed to download PDF: " + pdfUrl, e);
        }
    }

    private Path resolveLocalFallbackPdf() {
        return switch (surahNumber) {
            case 12 -> Paths.get("/tmp/yusuf.pdf");
            case 18 -> Paths.get("/tmp/kahf.pdf");
            case 19 -> Paths.get("/tmp/maryam.pdf");
            default -> null;
        };
    }

    private List<SectionMarker> findSectionMarkers(String[] lines) {
        List<SectionMarker> markers = new ArrayList<>();
        Pattern pattern = Pattern.compile(
                "(?<!\\d)" + surahNumber + "\\s*:\\s*(\\d+)(?:\\s*[-–]\\s*(\\d+))?",
                Pattern.CASE_INSENSITIVE);

        int lastAyah = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            Matcher matcher = pattern.matcher(line);
            if (!matcher.find()) {
                continue;
            }

            String trailing = line.substring(matcher.end()).trim();
            if (!trailing.isEmpty() && !trailing.matches("^[\\s\\[\\]\\(\\)\"'“”‘’.,;:!\\-−–—]*$")) {
                continue;
            }

            int ayahStart = Integer.parseInt(matcher.group(1));
            int ayahEnd = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : ayahStart;

            if (ayahStart < lastAyah) {
                continue;
            }

            markers.add(new SectionMarker(i, ayahStart, ayahEnd));
            lastAyah = ayahStart;
        }

        return markers;
    }

    private SectionWindow expandWindowIfNeeded(SectionMarker marker,
                                               int initialEndIndex,
                                               int markerIndex,
                                               List<SectionMarker> markers,
                                               String[] lines) {
        int endLineExclusive = initialEndIndex;
        int ayahEnd = marker.ayahEnd;
        int cursor = markerIndex;

        while (cursor + 1 < markers.size()) {
            String commentary = cleanCommentary(lines, marker.lineIndex + 1, endLineExclusive);
            if (isSubstantialCommentary(commentary)) {
                break;
            }

            SectionMarker next = markers.get(cursor + 1);
            if (next.ayahStart > ayahEnd + 1 || next.ayahEnd - marker.ayahStart > 3) {
                break;
            }
            ayahEnd = next.ayahEnd;
            endLineExclusive = (cursor + 2 < markers.size()) ? markers.get(cursor + 2).lineIndex : lines.length;
            cursor++;
        }

        return new SectionWindow(endLineExclusive, ayahEnd);
    }

    private boolean isSubstantialCommentary(String commentary) {
        if (commentary.length() >= 220) {
            return true;
        }
        String normalized = commentary.replace('\n', ' ').trim();
        if (normalized.isEmpty()) {
            return false;
        }
        return normalized.split("\\s+").length >= 35;
    }

    private String cleanCommentary(String[] lines, int startInclusive, int endExclusive) {
        List<String> cleaned = new ArrayList<>();

        for (int i = startInclusive; i < endExclusive && i < lines.length; i++) {
            String line = lines[i]
                    .replace('\f', ' ')
                    .replaceAll("\\s+", " ")
                    .trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.matches("^_{5,}$")) {
                continue;
            }
            if (line.matches("^\\d+$")) {
                continue;
            }
            if (line.matches("(?i)^commentary of verse(?:s)?\\s+\\d+.*$")) {
                break;
            }
            if (line.matches("(?i)^commentary on surah.*$")) {
                continue;
            }
            if (line.equals("***")) {
                continue;
            }
            if (line.matches(".*jÎn°M.*ÑiÌm.*")) {
                continue;
            }
            cleaned.add(line);
        }

        return normalizeCommentary(String.join("\n\n", cleaned));
    }

    private List<String> buildVerseKeys(int ayahStart, int ayahEnd) {
        List<String> verseKeys = new ArrayList<>();
        for (int ayah = ayahStart; ayah <= ayahEnd; ayah++) {
            verseKeys.add(surahNumber + ":" + ayah);
        }
        return verseKeys;
    }

    private String formatSectionTitle(int ayahStart, int ayahEnd) {
        if (ayahStart == ayahEnd) {
            return "Verse " + surahNumber + ":" + ayahStart;
        }
        return "Verses " + surahNumber + ":" + ayahStart + "-" + ayahEnd;
    }

    private record SectionMarker(int lineIndex, int ayahStart, int ayahEnd) {
    }

    private record SectionWindow(int endLineExclusive, int ayahEnd) {
    }

    /**
     * Factory method for Surah al-Jinn commentary.
     */
    public static SingleSurahExtractor forJinn() {
        return new SingleSurahExtractor(
            "commentary-suratul-jinn-naser-makarem-shirazi",
            72,
            "Commentary of Suratul Jinn (from Tafsir Nemuneh by Makarem Shirazi)",
            "https://al-islam.org/printpdf/book/export/html/118591"
        );
    }

    /**
     * Factory method for Surah Yusuf commentary.
     */
    public static SingleSurahExtractor forYusuf() {
        return new SingleSurahExtractor(
            "tafsir-surah-yusuf",
            12,
            "Tafsir Surah Yusuf (by Shaykh Ali Abdur-Rasheed)",
            "https://al-islam.org/sites/default/files/singles/633-yusuf.pdf"
        );
    }

    /**
     * Factory method for Surah al-Kahf commentary.
     */
    public static SingleSurahExtractor forKahf() {
        return new SingleSurahExtractor(
            "tafsir-surah-al-kahf",
            18,
            "Tafsir Surah al-Kahf (by Shaykh Ali Abdur-Rasheed)",
            "https://al-islam.org/sites/default/files/singles/631-kahf.pdf"
        );
    }

    /**
     * Factory method for Surah Maryam commentary.
     */
    public static SingleSurahExtractor forMaryam() {
        return new SingleSurahExtractor(
            "tafsir-surah-maryam",
            19,
            "Tafsir Surah Maryam (by Shaykh Ali Abdur-Rasheed)",
            "https://al-islam.org/sites/default/files/singles/632-maryam.pdf"
        );
    }
}
