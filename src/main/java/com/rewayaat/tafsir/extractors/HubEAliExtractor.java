package com.rewayaat.tafsir.extractors;

import com.rewayaat.tafsir.TafsirDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
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
    private static final String BASE_URL = "https://hubeali.com/books/English-Books/TafseerHub-e-Ali/";
    private static final Pattern VERSE_PATTERN = Pattern.compile("[\\[\\(](\\d+):(\\d+)[\\]\\)]");

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

        // Extract a sample of surahs for testing (can be extended to all 114)
        int[] testSurahs = {1, 2, 18, 36, 114}; // Fatiha, Baqarah, Kahf, Yasin, Nas

        for (int surahNumber : testSurahs) {
            try {
                List<TafsirDocument> surahDocs = extractSurah(surahNumber);
                documents.addAll(surahDocs);
                LOGGER.info("Extracted {} documents from Surah {}", surahDocs.size(), surahNumber);
            } catch (Exception e) {
                LOGGER.error("Failed to extract Surah {}", surahNumber, e);
            }
        }

        LOGGER.info("Extraction complete: {} documents from Hub-e-Ali", documents.size());
        return documents;
    }

    private List<TafsirDocument> extractSurah(int surahNumber) throws IOException {
        List<TafsirDocument> documents = new ArrayList<>();

        String pdfUrl = getPdfUrl(surahNumber);
        File pdfFile = downloadOrCachePdf(pdfUrl, surahNumber);

        if (!pdfFile.exists()) {
            LOGGER.warn("PDF file not found: {}", pdfFile);
            return documents;
        }

        try (PDDocument document = PDDocument.load(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);

            // Split by verse references
            List<VerseSection> sections = splitByVerses(text);

            for (VerseSection section : sections) {
                if (section.isValid()) {
                    TafsirDocument doc = createDocument(section, pdfUrl);
                    if (doc != null) {
                        documents.add(doc);
                    }
                }
            }
        }

        return documents;
    }

    private String getPdfUrl(int surahNumber) {
        // URL pattern: CH{N}_Sura{Name}_{details}.pdf
        // For simplicity, using a common pattern - adjust based on actual URLs
        return BASE_URL + "CH" + surahNumber + "_Sura" + getSurahName(surahNumber) + ".pdf";
    }

    private String getSurahName(int surahNumber) {
        // Simplified - in production would use SurahNameResolver
        String[] names = {"AlFatiha", "AlBaqarah", "AliImran", "AnNisa", "AlMaidah",
                         "AlAnam", "AlAraf", "AlAnfal", "AtTawbah", "Yunus",
                         "Hud", "Yusuf", "ArRad", "Ibrahim", "AlHijr",
                         "AnNahl", "AlIsra", "AlKahf", "Maryam", "TaHa",
                         "AlAnbiya", "AlHajj", "AlMuminun", "AnNur", "AlFurqan",
                         "AshShuara", "AnNaml", "AlQasas", "AlAnkabut", "ArRum",
                         "Luqman", "AsSajdah", "AlAhzab", "Saba", "Fatir",
                         "YaSin", "AsSaffat", "Sad", "AzZumar", "Ghafir",
                         "Fussilat", "AshShura", "AzZukhruf", "AdDukhan", "AlJathiyah",
                         "AlAhqaf", "Muhammad", "AlFath", "AlHujurat", "Qaf",
                         "AdhDhariyat", "AtTur", "AnNajm", "AlQamar", "ArRahman",
                         "AlWaqiah", "AlHadid", "AlMujadila", "AlHashr", "AlMumtahanah",
                         "AsSaff", "AlJumuah", "AlMunafiqun", "AtTaghabun", "AtTalaq",
                         "AtTahrim", "AlMulk", "AlQalam", "AlHaqqah", "AlMaarij",
                         "Nuh", "AlJinn", "AlMuzzammil", "AlMuddaththir", "AlQiyamah",
                         "AlInsan", "AlMursalat", "AnNaba", "AnNaziyat", "Abasa",
                         "AtTakwir", "AlInfitar", "AlMutaffifin", "AlInshiqaq", "AlBuruj",
                         "AtTariq", "AlAla", "AlGhashiyah", "AlFajr", "AlBalad",
                         "AshShams", "AlLayl", "AdDuhaa", "AshSharh", "AtTin",
                         "AlAlaq", "AlQadr", "AlBayyinah", "AzZalzalah", "AlAdiyat",
                         "AlQariah", "AtTakathur", "AlAsr", "AlHumazah", "AlFil",
                         "Quraysh", "AlMaun", "AlKawthar", "AlKafirun", "AnNasr",
                         "AlMasad", "AlIkhlas", "AlFalaq", "AnNas"};

        if (surahNumber >= 1 && surahNumber <= names.length) {
            return names[surahNumber - 1];
        }
        return "Sura" + surahNumber;
    }

    private File downloadOrCachePdf(String pdfUrl, int surahNumber) throws IOException {
        String fileName = "hubeali-surah-" + surahNumber + ".pdf";
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

    private List<VerseSection> splitByVerses(String text) {
        List<VerseSection> sections = new ArrayList<>();

        String[] lines = text.split("\\r?\\n");
        VerseSection currentSection = null;

        for (String line : lines) {
            Matcher matcher = VERSE_PATTERN.matcher(line);
            if (matcher.find()) {
                // Save previous section
                if (currentSection != null && currentSection.hasContent()) {
                    sections.add(currentSection);
                }

                // Start new section
                int surah = Integer.parseInt(matcher.group(1));
                int ayah = Integer.parseInt(matcher.group(2));
                currentSection = new VerseSection(surah, ayah);
            } else if (currentSection != null) {
                currentSection.addLine(line);
            }
        }

        // Add last section
        if (currentSection != null && currentSection.hasContent()) {
            sections.add(currentSection);
        }

        return sections;
    }

    private TafsirDocument createDocument(VerseSection section, String sourceUrl) {
        TafsirDocument doc = new TafsirDocument();
        doc.setTafsirSlug("hubeali");
        doc.setTafsirName("Tafseer Hub-e-Ali");
        doc.setSurahNumber(section.surahNumber);
        doc.setAyahStart(section.ayahNumber);
        doc.setAyahEnd(section.ayahNumber);
        doc.setVerseKey(section.surahNumber + ":" + section.ayahNumber);
        doc.setVerseKeys(java.util.Arrays.asList(doc.getVerseKey()));

        // Extract English commentary (separate from Arabic)
        String commentary = section.extractEnglishText();
        doc.setCommentaryText(commentary);
        doc.setSourceUrl(sourceUrl);
        doc.setLanguage("en");
        doc.computeWordCount();

        return doc;
    }

    private static class VerseSection {
        final int surahNumber;
        final int ayahNumber;
        final StringBuilder content = new StringBuilder();
        private static final Logger LOGGER = LoggerFactory.getLogger(VerseSection.class);

        VerseSection(int surahNumber, int ayahNumber) {
            this.surahNumber = surahNumber;
            this.ayahNumber = ayahNumber;
        }

        void addLine(String line) {
            if (content.length() > 0) {
                content.append(" ");
            }
            content.append(line.trim());
        }

        boolean hasContent() {
            return content.length() > 0;
        }

        boolean isValid() {
            return surahNumber > 0 && surahNumber <= 114 && ayahNumber > 0;
        }

        String extractEnglishText() {
            // Separate English from Arabic using Unicode block detection
            // Arabic is in Unicode block U+0600–U+06FF
            String text = content.toString();
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

            return english.toString().trim();
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
