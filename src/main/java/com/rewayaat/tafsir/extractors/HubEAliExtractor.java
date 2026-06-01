package com.rewayaat.tafsir.extractors;

import com.rewayaat.tafsir.TafsirDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extractor for "Tafseer Hub-e-Ali".
 *
 * Source: hubeali.com
 * Coverage: Full Quran (114 surah PDFs)
 * Format: PDF
 *
 * Features:
 * - Individual surah PDFs at predictable URLs
 * - Verse boundaries detected by regex: [N:M] or (N:M) patterns
 * - Bilingual Arabic/English text
 * - Separates Arabic from English using Unicode block detection
 */
public class HubEAliExtractor implements TafsirExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(HubEAliExtractor.class);
    private static final String INDEX_URL = "https://hubeali.com/tafseer/";
    private static final Pattern PDF_LINK_PATTERN = Pattern.compile(
            "href=\"(https?://hubeali\\.com/books/English-Books/Tafseer-e-Quran/[^\"]+_CH%20(\\d+)(?:_P\\d+)?\\.pdf)\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SECTION_HEADING_PATTERN = Pattern.compile("^VERSES?\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIGIT_PATTERN = Pattern.compile("\\d+");
    private static final Pattern LEADING_ARTIFACT_PATTERN = Pattern.compile(
            "^(?:[\\}\\{\\[\\]\\.\\s]+|\\[[0-9]+:[0-9]+\\]\\s*|\\{\\d+\\}\\s*|\\d+\\s*out of\\s*\\d+\\s*)+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_VERSE_MARKER_PATTERN = Pattern.compile(
            "(?:\\[[0-9]+:[0-9]+\\]|\\{\\d+\\}|\\}\\s*\\}|\\{\\s*)");
    private static final Pattern PAGE_ARTIFACT_PATTERN = Pattern.compile(
            "(?:\\.?\\d+\\s*/\\s*\\.?\\d+\\s*:?)|(?:\\d+\\s*out of\\s*\\d+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_REFERENCE_PATTERN = Pattern.compile(
            "(?:\\s*(?:\\d+|\\.)+(?:\\s*/\\s*(?:\\d+|\\.))+\\s*)+$");
    private static final Pattern LEADING_NUMERIC_MARKER_PATTERN = Pattern.compile(
            "^(?:(?:\\d+:\\d+\\]?|\\d+\\]?|\\d+\\})\\s+)+");
    private static final Pattern INLINE_NUMERIC_MARKER_PATTERN = Pattern.compile(
            "(?<![A-Za-z])\\d+:\\d+\\]");
    private static final Pattern TRAILING_FOOTNOTE_PATTERN = Pattern.compile("(?:\\s*[\\.]?\\d+[:.]?)+$");
    private static final Pattern LEADING_BRACE_PATTERN = Pattern.compile("^[\\}\\{\\[\\]\\s]+");
    private static final Pattern INLINE_FOOTNOTE_PATTERN = Pattern.compile("(?<=[\\p{L}’'”])\\.\\d+(?=\\s)");
    private static final Pattern TRAILING_MIXED_NOTE_PATTERN = Pattern.compile("(?:[.’”'\\s]*\\d+(?::)?)+$");
    private static final Pattern SOURCE_REFERENCE_ONLY_PATTERN = Pattern.compile(
            "(?i)^(?:tafseer\\s+.*|al\\s+kafi\\s*[–-]?\\s*h|tafseer\\s+noor\\s+al\\s+saqalayn.*|\\(?\\.?\\d+[:.)\\s-]*\\)?|[0-9:().\\s-]+)$");

    private final String sourceDir;
    private final boolean dryRun;

    public HubEAliExtractor() {
        this.sourceDir = resolveSourceDir();
        this.dryRun = resolveDryRun();
    }

    private static String resolveSourceDir() {
        String dir = System.getProperty("tafsir.source-dir");
        if (dir == null || dir.isEmpty()) {
            dir = System.getenv().get("TAFSIR_SOURCE_DIR");
        }
        return (dir != null && !dir.isEmpty()) ? dir : "/tmp/tafsir-sources";
    }

    private static boolean resolveDryRun() {
        String dryRun = System.getProperty("tafsir.dry-run");
        if (dryRun == null || dryRun.isEmpty()) {
            dryRun = System.getenv().get("TAFSIR_DRY_RUN");
        }
        return "true".equalsIgnoreCase(dryRun);
    }

    @Override
    public List<TafsirDocument> extract() throws ExtractionException {
        List<TafsirDocument> documents = new ArrayList<>();

        LOGGER.info("Extracting Hub-e-Ali from PDFs");
        LOGGER.info("Source directory: {}, Dry run: {}", sourceDir, dryRun);

        Map<Integer, List<String>> pdfUrlsBySurah = loadPdfUrlsBySurah();
        if (pdfUrlsBySurah.isEmpty()) {
            LOGGER.warn("No Hub-e-Ali PDF URLs discovered");
            return documents;
        }

        List<Integer> surahs = pdfUrlsBySurah.keySet().stream().sorted(Comparator.naturalOrder()).toList();
        for (int surahNumber : surahs) {
            try {
                List<TafsirDocument> surahDocs = extractSurah(surahNumber, pdfUrlsBySurah.get(surahNumber));
                documents.addAll(surahDocs);
                LOGGER.info("Extracted {} documents from Surah {}", surahDocs.size(), surahNumber);
            } catch (Exception e) {
                LOGGER.error("Failed to extract Surah {}", surahNumber, e);
            }
        }

        LOGGER.info("Extraction complete: {} documents from Hub-e-Ali", documents.size());
        return documents;
    }

    private Map<Integer, List<String>> loadPdfUrlsBySurah() throws ExtractionException {
        String html = loadOrFetchIndexHtml();
        Matcher matcher = PDF_LINK_PATTERN.matcher(html);
        Map<Integer, List<String>> pdfUrlsBySurah = new LinkedHashMap<>();

        while (matcher.find()) {
            String pdfUrl = matcher.group(1).replace("http://", "https://");
            int surahNumber = Integer.parseInt(matcher.group(2));
            pdfUrlsBySurah.computeIfAbsent(surahNumber, ignored -> new ArrayList<>()).add(pdfUrl);
        }

        return pdfUrlsBySurah;
    }

    private String loadOrFetchIndexHtml() throws ExtractionException {
        Path cachePath = Paths.get(sourceDir, "hubeali", "tafseer-index.html");
        if (Files.exists(cachePath)) {
            try {
                return Files.readString(cachePath);
            } catch (IOException e) {
                throw new ExtractionException("Failed reading cached Hub-e-Ali index", e);
            }
        }

        Path localFallback = Paths.get("/tmp/hubeali-tafseer.html");
        if (Files.exists(localFallback)) {
            try {
                String html = Files.readString(localFallback);
                Files.createDirectories(cachePath.getParent());
                Files.writeString(cachePath, html);
                return html;
            } catch (IOException e) {
                throw new ExtractionException("Failed reading local Hub-e-Ali index fallback", e);
            }
        }

        try {
            Files.createDirectories(cachePath.getParent());
            try (InputStream stream = new URL(INDEX_URL).openStream()) {
                String html = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                Files.writeString(cachePath, html);
                return html;
            }
        } catch (IOException e) {
            throw new ExtractionException("Failed to fetch Hub-e-Ali index: " + INDEX_URL, e);
        }
    }

    private List<TafsirDocument> extractSurah(int surahNumber, List<String> pdfUrls) throws IOException {
        List<TafsirDocument> documents = new ArrayList<>();
        if (pdfUrls == null || pdfUrls.isEmpty()) {
            return documents;
        }

        for (String pdfUrl : pdfUrls) {
            File pdfFile = downloadOrCachePdf(pdfUrl);
            if (!pdfFile.exists()) {
                LOGGER.warn("PDF file not found: {}", pdfFile);
                continue;
            }

            try (PDDocument document = PDDocument.load(pdfFile)) {
                PDFTextStripper stripper = new PDFTextStripper();
                String text = stripper.getText(document);
                List<VerseSection> sections = splitBySections(text, surahNumber);

                for (VerseSection section : sections) {
                    if (section.isValid()) {
                        TafsirDocument doc = createDocument(section, pdfUrl);
                        if (doc != null) {
                            documents.add(doc);
                        }
                    }
                }
            }
        }

        return documents;
    }

    private File downloadOrCachePdf(String pdfUrl) throws IOException {
        String fileName = sanitizeFileName(pdfUrl);
        Path cachePath = Paths.get(sourceDir, "hubeali", fileName);

        if (Files.exists(cachePath)) {
            return cachePath.toFile();
        }

        if (dryRun) {
            return cachePath.toFile(); // Will not exist, handled by caller
        }

        // Download PDF
        Files.createDirectories(cachePath.getParent());
        try {
            URL url = new URL(pdfUrl);
            Files.copy(url.openStream(), cachePath);
            return cachePath.toFile();
        } catch (IOException e) {
            LOGGER.warn("Failed to download PDF from: {}", pdfUrl);
            return cachePath.toFile();
        }
    }

    private String sanitizeFileName(String pdfUrl) {
        String fileName = pdfUrl.substring(pdfUrl.lastIndexOf('/') + 1);
        String decoded = URLDecoder.decode(fileName, StandardCharsets.UTF_8);
        return decoded.replaceAll("[^A-Za-z0-9._-]+", "-");
    }

    private List<VerseSection> splitBySections(String text, int expectedSurahNumber) {
        List<VerseSection> sections = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");
        VerseSection currentSection = null;
        boolean contentStarted = false;

        for (String line : lines) {
            String normalized = line.replace('\f', ' ').trim();
            if (normalized.isEmpty()) {
                continue;
            }
            if (!contentStarted) {
                if (normalized.equalsIgnoreCase("CHAPTER " + expectedSurahNumber)) {
                    contentStarted = true;
                }
                continue;
            }
            if (normalized.startsWith("Tafseer Hub-e-Aliasws")) {
                continue;
            }
            if (normalized.matches(".*\\.{3,}.*")) {
                continue;
            }

            Matcher headingMatcher = SECTION_HEADING_PATTERN.matcher(normalized);
            if (headingMatcher.matches()) {
                VerseSection nextSection = createSectionFromHeading(expectedSurahNumber, headingMatcher.group(1));
                if (nextSection == null) {
                    continue;
                }
                if (currentSection != null && currentSection.hasSubstantiveContent()) {
                    sections.add(currentSection);
                }
                currentSection = nextSection;
                continue;
            }

            if (currentSection != null) {
                currentSection.addLine(normalized);
            }
        }

        if (currentSection != null && currentSection.hasSubstantiveContent()) {
            sections.add(currentSection);
        }

        return sections;
    }

    private VerseSection createSectionFromHeading(int surahNumber, String headingRemainder) {
        Matcher matcher = DIGIT_PATTERN.matcher(headingRemainder);
        List<Integer> numbers = new ArrayList<>();
        while (matcher.find()) {
            numbers.add(Integer.parseInt(matcher.group()));
        }
        if (numbers.isEmpty()) {
            return null;
        }
        int ayahStart = numbers.get(0);
        int ayahEnd = numbers.get(numbers.size() - 1);
        return new VerseSection(
                surahNumber,
                ayahStart,
                ayahEnd,
                "Verses " + surahNumber + ":" + ayahStart + (ayahEnd > ayahStart ? "-" + ayahEnd : "")
        );
    }

    private TafsirDocument createDocument(VerseSection section, String sourceUrl) {
        TafsirDocument doc = new TafsirDocument();
        doc.setTafsirSlug("hubeali");
        doc.setTafsirName("Tafseer Hub-e-Ali");
        doc.setSurahNumber(section.surahNumber);
        doc.setAyahStart(section.ayahStart);
        doc.setAyahEnd(section.ayahEnd);
        doc.setVerseKey(section.surahNumber + ":" + section.ayahStart);
        doc.setVerseKeys(section.getVerseKeys());
        String commentary = section.extractEnglishText();
        doc.setCommentaryText(commentary);
        doc.setSectionTitle(section.sectionTitle);
        doc.setSourceUrl(sourceUrl + "#ayah-" + section.ayahStart
                + (section.ayahEnd > section.ayahStart ? "-" + section.ayahEnd : ""));
        doc.setLanguage("en");
        doc.computeWordCount();

        return doc;
    }

    private static class VerseSection {
        final int surahNumber;
        final int ayahStart;
        final int ayahEnd;
        final String sectionTitle;
        final StringBuilder content = new StringBuilder();

        VerseSection(int surahNumber, int ayahStart, int ayahEnd, String sectionTitle) {
            this.surahNumber = surahNumber;
            this.ayahStart = ayahStart;
            this.ayahEnd = ayahEnd;
            this.sectionTitle = sectionTitle;
        }

        void addLine(String line) {
            if (content.length() > 0) {
                content.append("\n");
            }
            content.append(line.trim());
        }

        boolean hasSubstantiveContent() {
            return content.length() > 0;
        }

        boolean isValid() {
            return surahNumber > 0
                    && surahNumber <= 114
                    && ayahStart > 0
                    && ayahEnd >= ayahStart
                    && hasSubstantiveEnglishContent(extractEnglishText());
        }

        List<String> getVerseKeys() {
            List<String> verseKeys = new ArrayList<>();
            for (int ayah = ayahStart; ayah <= ayahEnd; ayah++) {
                verseKeys.add(surahNumber + ":" + ayah);
            }
            return verseKeys;
        }

        String extractEnglishText() {
            String text = content.toString()
                    .replaceAll("(?m)^\\d+\\s*$", "")
                    .replaceAll("(?m)^Tafseer Hub-e-Aliasws.*$", "")
                    .replaceAll("(?m)^www\\.hubeali\\.com.*$", "");
            StringBuilder english = new StringBuilder();

            String[] words = text.split("\\s+");
            for (String word : words) {
                if (!isArabicWord(word)) {
                    if (english.length() > 0) {
                        english.append(" ");
                    }
                    english.append(word);
                }
            }

            return normalizeEnglishText(english.toString());
        }

        private String normalizeEnglishText(String text) {
            String normalized = text
                    .replace('\u00A0', ' ')
                    .replaceAll("\\s+", " ")
                    .trim();
            normalized = LEADING_ARTIFACT_PATTERN.matcher(normalized).replaceFirst("");
            normalized = INLINE_VERSE_MARKER_PATTERN.matcher(normalized).replaceAll(" ");
            normalized = PAGE_ARTIFACT_PATTERN.matcher(normalized).replaceAll(" ");
            normalized = INLINE_NUMERIC_MARKER_PATTERN.matcher(normalized).replaceAll(" ");
            normalized = INLINE_FOOTNOTE_PATTERN.matcher(normalized).replaceAll("");
            normalized = LEADING_NUMERIC_MARKER_PATTERN.matcher(normalized).replaceFirst("");
            normalized = LEADING_BRACE_PATTERN.matcher(normalized).replaceFirst("");
            normalized = TRAILING_REFERENCE_PATTERN.matcher(normalized).replaceAll("");
            normalized = TRAILING_FOOTNOTE_PATTERN.matcher(normalized).replaceAll("");
            normalized = TRAILING_MIXED_NOTE_PATTERN.matcher(normalized).replaceAll("");
            return normalized
                    .replaceAll("\\s{2,}", " ")
                    .replaceAll("\\s+([,.;:!?])", "$1")
                    .trim();
        }

        private boolean hasSubstantiveEnglishContent(String text) {
            String normalized = text == null ? "" : text.trim();
            if (normalized.isEmpty()) {
                return false;
            }
            if (SOURCE_REFERENCE_ONLY_PATTERN.matcher(normalized).matches()) {
                return false;
            }

            int substantiveWords = 0;
            for (String token : normalized.split("\\s+")) {
                if (token.matches(".*[A-Za-z].*")) {
                    substantiveWords++;
                }
            }
            return substantiveWords >= 40;
        }

        private boolean isArabicWord(String word) {
            // Check if word contains Arabic characters
            for (char c : word.toCharArray()) {
                if (c >= 0x0600 && c <= 0x06FF) {
                    return true;
                }
            }
            return false;
        }
    }

    @Override
    public String getTafsirSlug() {
        return "hubeali";
    }

    @Override
    public String getTafsirName() {
        return "Tafseer Hub-e-Ali";
    }
}
