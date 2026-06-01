package com.rewayaat.tafsir.extractors;

import com.rewayaat.tafsir.SurahNameResolver;
import com.rewayaat.tafsir.TafsirDocument;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simplified extractor for hodaalquran.com Arabic tafsir content.
 * The site uses specific HTML structure with uppercase <P> tags.
 */
public class HodaAlQuranExtractor implements TafsirExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(HodaAlQuranExtractor.class);

    private static final String BASE_URL = "https://www.hodaalquran.com";
    private static final int DEFAULT_FETCH_DELAY = 1500;

    // Arabic patterns for parsing ayah titles
    private static final Pattern SINGLE_AYAH = Pattern.compile("\\u0627\\u0644\\u0622\\u064A\\u0629\\s+(\\d+)");
    private static final Pattern MULTI_AYAH = Pattern.compile(
            "\\u0627\\u0644\\u0622\\u064A\\u0627\\u062A\\s+(\\d+)\\s*[\\-–—]\\s*(\\d+)");

    protected final String sourceDir;
    protected final int fetchDelay;
    protected final boolean dryRun;
    protected final int bookId;
    protected final String tafsirSlug;
    protected final String tafsirName;

    private HodaAlQuranExtractor(int bookId, String tafsirSlug, String tafsirName) {
        this.bookId = bookId;
        this.tafsirSlug = tafsirSlug;
        this.tafsirName = tafsirName;
        this.sourceDir = resolveSourceDir();
        this.fetchDelay = resolveFetchDelay();
        this.dryRun = resolveDryRun();
    }

    // Factory methods
    public static HodaAlQuranExtractor amthal() {
        return new HodaAlQuranExtractor(299, "ar-amthal", "تفسير الأمثل");
    }

    public static HodaAlQuranExtractor majmaBayan() {
        return new HodaAlQuranExtractor(297, "ar-majma-al-bayan", "مجمع البيان");
    }

    public static HodaAlQuranExtractor qummi() {
        return new HodaAlQuranExtractor(307, "ar-tafsir-al-qummi", "تفسير القمي");
    }

    public static HodaAlQuranExtractor khomeini() {
        return new HodaAlQuranExtractor(306, "ar-khomeini-tafsir", "تفسير القرآن الكريم");
    }

    public static HodaAlQuranExtractor jawami() {
        return new HodaAlQuranExtractor(305, "ar-jawami-al-jami", "جوامع الجامع");
    }

    public static HodaAlQuranExtractor tibyan() {
        return new HodaAlQuranExtractor(304, "ar-al-tibyan", "التبيان");
    }

    public static HodaAlQuranExtractor safi() {
        return new HodaAlQuranExtractor(303, "ar-tafsir-al-safi", "تفسير الصافي");
    }

    public static HodaAlQuranExtractor kanz() {
        return new HodaAlQuranExtractor(302, "ar-kanz-al-daqaiq", "كنز الدقائق");
    }

    public static HodaAlQuranExtractor noor() {
        return new HodaAlQuranExtractor(301, "ar-noor-al-thaqalayn", "نور الثقلين");
    }

    public static HodaAlQuranExtractor ghareeb() {
        return new HodaAlQuranExtractor(300, "ar-ghareeb-al-quran", "غريب القرآن");
    }

    public static HodaAlQuranExtractor forBook(int bookId, String slug, String name) {
        return new HodaAlQuranExtractor(bookId, slug, name);
    }

    @Override
    public String getTafsirSlug() {
        return tafsirSlug;
    }

    @Override
    public String getTafsirName() {
        return tafsirName;
    }

    @Override
    public List<TafsirDocument> extract() throws ExtractionException {
        List<TafsirDocument> allDocuments = new ArrayList<>();

        LOGGER.info("Extracting {} ({}) from hodaalquran.com", tafsirName, tafsirSlug);
        LOGGER.info("Book ID: {}, Source directory: {}, Dry run: {}", bookId, sourceDir, dryRun);

        try {
            // Read limit from config for testing
            int maxAyahs = readInt("tafsir.max-ayahs", readInt("TAFSIR_MAX_AYAHS", 0));
            int processedCount = 0;

            // LEVEL 1: Fetch book TOC
            String bookUrl = BASE_URL + "/book/" + bookId;
            LOGGER.info("Fetching book TOC: {}", bookUrl);
            Document bookToc = fetchDocument(bookUrl);

            if (bookToc == null) {
                throw new ExtractionException("Failed to fetch book TOC: " + bookUrl);
            }

            // Parse surah links from book TOC
            List<SurahLink> surahLinks = parseBookToc(bookToc);
            LOGGER.info("Found {} surah entries in book TOC", surahLinks.size());

            for (int i = 0; i < surahLinks.size(); i++) {
                SurahLink surahLink = surahLinks.get(i);
                int surahNumber = i + 1; // Sequential numbering based on TOC position

                LOGGER.info("Processing surah {}/{}: {} (content ID: {})",
                        surahNumber, surahLinks.size(), surahLink.name, surahLink.contentId);

                // LEVEL 2: Fetch surah TOC
                String surahUrl = BASE_URL + "/book/content/" + surahLink.contentId;
                Document surahToc = fetchDocument(surahUrl);

                if (surahToc == null) {
                    LOGGER.warn("Failed to fetch surah TOC: {}", surahUrl);
                    continue;
                }

                // Parse ayah links from surah TOC
                List<AyahLink> ayahLinks = parseSurahToc(surahToc);
                LOGGER.info("Found {} ayah entries for surah {}", ayahLinks.size(), surahNumber);

                for (AyahLink ayahLink : ayahLinks) {
                    if (maxAyahs > 0 && processedCount >= maxAyahs) {
                        LOGGER.info("Reached limit of {} ayahs, stopping", maxAyahs);
                        break;
                    }

                    // Parse ayah number from title
                    AyahRange ayahRange = parseAyahNumber(ayahLink.title);
                    if (ayahRange == null) {
                        // Not an ayah entry (likely surah intro)
                        LOGGER.debug("Skipping non-ayah entry: {}", ayahLink.title);
                        continue;
                    }

                    // LEVEL 3: Fetch ayah page
                    String ayahUrl = BASE_URL + "/book/content/" + ayahLink.contentId;
                    Document ayahPage = fetchDocument(ayahUrl);

                    if (ayahPage == null) {
                        LOGGER.warn("Failed to fetch ayah page: {}", ayahUrl);
                        continue;
                    }

                    // Extract content from ayah page
                    TafsirDocument doc = extractFromAyahPage(ayahPage, surahNumber, ayahRange, ayahUrl);
                    if (doc != null) {
                        allDocuments.add(doc);
                        processedCount++;
                        LOGGER.debug("Extracted: {}:{} - {} words",
                                surahNumber, ayahRange.start, doc.getCommentaryWordCount());
                    }

                    // Rate limiting
                    if (fetchDelay > 0) {
                        TimeUnit.MILLISECONDS.sleep(fetchDelay);
                    }
                }

                LOGGER.info("Surah {} complete: {} documents extracted so far",
                        surahNumber, allDocuments.size());

                if (maxAyahs > 0 && processedCount >= maxAyahs) {
                    break;
                }
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExtractionException("Extraction interrupted", e);
        } catch (Exception e) {
            throw new ExtractionException("Extraction failed", e);
        }

        LOGGER.info("Extraction complete: {} documents from {}", allDocuments.size(), tafsirName);
        return allDocuments;
    }

    /**
     * Parses the book TOC page to extract surah links.
     */
    protected List<SurahLink> parseBookToc(Document bookToc) {
        List<SurahLink> links = new ArrayList<>();

        // The TOC contains links in table cells
        // Each row has a number and a link with the surah name
        Elements tds = bookToc.select("td");
        for (Element td : tds) {
            Element link = td.selectFirst("a[href*='/book/content/']");
            if (link != null) {
                String href = link.attr("href");
                String name = link.text().trim();
                String contentId = extractContentId(href);

                // Filter for valid Arabic surah names
                if (!contentId.isEmpty() && !name.isEmpty() &&
                    name.matches(".*[\\u0600-\\u06FF].*") && // Contains Arabic
                    !name.contains("كتب التفسير")) { // Skip header
                    links.add(new SurahLink(name, contentId));
                }
            }
        }

        return links;
    }

    /**
     * Parses the surah TOC page to extract ayah links.
     */
    protected List<AyahLink> parseSurahToc(Document surahToc) {
        List<AyahLink> links = new ArrayList<>();

        Elements anchors = surahToc.select("a[href*='/book/content/']");
        for (Element anchor : anchors) {
            String href = anchor.attr("href");
            String title = anchor.text().trim();
            String contentId = extractContentId(href);

            if (!contentId.isEmpty() && !title.isEmpty()) {
                links.add(new AyahLink(title, contentId));
            }
        }

        return links;
    }

    /**
     * Extracts content ID from URL path.
     */
    protected String extractContentId(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        Pattern pattern = Pattern.compile("/book/content/(\\d+)");
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    /**
     * Parses ayah number from Arabic title.
     */
    protected AyahRange parseAyahNumber(String title) {
        if (title == null || title.isEmpty()) {
            return null;
        }

        // Try multi-ayah pattern first
        Matcher multiMatcher = MULTI_AYAH.matcher(title);
        if (multiMatcher.find()) {
            int start = Integer.parseInt(multiMatcher.group(1));
            int end = Integer.parseInt(multiMatcher.group(2));
            return new AyahRange(start, end);
        }

        // Try single ayah pattern
        Matcher singleMatcher = SINGLE_AYAH.matcher(title);
        if (singleMatcher.find()) {
            int ayah = Integer.parseInt(singleMatcher.group(1));
            return new AyahRange(ayah, ayah);
        }

        return null;
    }

    /**
     * Extracts tafsir document from an ayah page.
     * The hodaalquran.com site uses uppercase <P> tags.
     */
    protected TafsirDocument extractFromAyahPage(Document page, int surahNumber, AyahRange ayahRange, String url) {
        // Get all <P> tags (case-insensitive)
        Elements pTags = page.select("p");

        List<String> paragraphs = new ArrayList<>();
        String sectionTitle = null;
        boolean foundTafsir = false;
        boolean skippedFirstAfterTafsir = false;

        for (Element p : pTags) {
            String text = p.text().trim();
            if (text.isEmpty()) {
                continue;
            }

            // Check for tafsir heading
            if (text.equals("التّفسير") || text.equals("التفسير")) {
                foundTafsir = true;
                continue;
            }

            // After finding tafsir, skip first non-tafsir paragraph (section title)
            if (foundTafsir && !skippedFirstAfterTafsir) {
                if (!text.contains("المشاهدات") && text.length() < 100) {
                    sectionTitle = text;
                }
                skippedFirstAfterTafsir = true;
                continue;
            }

            // Collect content after tafsir heading
            if (foundTafsir && skippedFirstAfterTafsir) {
                // Stop at footer/breadcrumb content
                if (text.contains("المكتبة المقروءة") ||
                    text.contains("كتب التفسير") ||
                    text.matches(".*\\d+\\s+يوليو.*")) {
                    break;
                }
                paragraphs.add(text);
            }
        }

        // If no content found with tafsir heading, try fallback extraction
        // for tafsirs with different structure (e.g., majma-bayan)
        if (paragraphs.isEmpty()) {
            return extractFromAyahPageFallback(page, surahNumber, ayahRange, url);
        }

        String commentary = String.join("\n\n", paragraphs);

        // Validate we have substantial content
        if (commentary.length() < 50) {
            LOGGER.warn("Commentary too short for: {}", url);
            return null;
        }

        // Create document
        TafsirDocument doc = new TafsirDocument();
        doc.setTafsirSlug(tafsirSlug);
        doc.setTafsirName(tafsirName);
        doc.setSurahNumber(surahNumber);
        doc.setAyahStart(ayahRange.start);
        doc.setAyahEnd(ayahRange.end);

        // Build verse key
        if (ayahRange.start == ayahRange.end) {
            doc.setVerseKey(surahNumber + ":" + ayahRange.start);
        } else {
            doc.setVerseKey(surahNumber + ":" + ayahRange.start);
            List<String> verseKeys = new ArrayList<>();
            for (int i = ayahRange.start; i <= ayahRange.end; i++) {
                verseKeys.add(surahNumber + ":" + i);
            }
            doc.setVerseKeys(verseKeys);
        }

        doc.setCommentaryText(commentary);
        doc.setSectionTitle(sectionTitle);
        doc.setSourceUrl(url);
        doc.setLanguage("ar");
        doc.computeWordCount();

        return doc;
    }

    /**
     * Fallback extraction for tafsirs without the standard "التفسير" heading.
     * Extracts content by finding the ayah title and collecting subsequent paragraphs.
     */
    protected TafsirDocument extractFromAyahPageFallback(Document page, int surahNumber, AyahRange ayahRange, String url) {
        Elements pTags = page.select("p");

        List<String> paragraphs = new ArrayList<>();
        String sectionTitle = null;
        boolean foundTitle = false;
        boolean collecting = false;
        boolean foundVerse = false;

        for (Element p : pTags) {
            String text = p.text().trim();
            if (text.isEmpty()) {
                continue;
            }

            // Skip metadata paragraphs at the start
            if (!foundTitle && (text.contains("الشيخ") || text.contains("مشاهدة") ||
                text.matches(".*\\d+\\s+(?:يناير|فبراير|مارس|أبريل|مايو|يونيو|يوليو|أغسطس|سبتمبر|أكتوبر|نوفمبر|ديسمبر).*") ||
                text.contains("دقائق للقراءة"))) {
                continue;
            }

            // Stop at footer/breadcrumb content (only after we've found the title)
            if (foundTitle && (text.contains("المكتبة المقروءة") || text.contains("كتب التفسير"))) {
                break;
            }

            // Look for title paragraph (e.g., "الآيات 26-30")
            if (!foundTitle) {
                if (text.contains("الآيات") || text.matches(".*[\\d]+\\s*-\\s*[\\d]+.*")) {
                    sectionTitle = text;
                    foundTitle = true;
                    continue;
                }
            }

            // After title, skip the verse text (contains Quranic Arabic with ayah markers)
            if (foundTitle && !foundVerse) {
                // Verse text typically has ﴿...﴾ brackets
                if (text.contains("﴿") || text.contains("﴾")) {
                    foundVerse = true;
                    continue;
                }
                // If very short text after title, skip (likely section headers)
                if (text.length() < 30) {
                    continue;
                }
                // Otherwise start collecting
                foundVerse = true;
            }

            // Collect commentary content (skip very short section headers)
            if (foundVerse && text.length() >= 10) {
                paragraphs.add(text);
            }
        }

        if (paragraphs.isEmpty()) {
            LOGGER.debug("Fallback: No commentary found for: {} (foundTitle={}, foundVerse={})", url, foundTitle, foundVerse);
            return null;
        }

        String commentary = String.join("\n\n", paragraphs);

        // Validate we have substantial content
        if (commentary.length() < 30) {
            LOGGER.debug("Fallback: Commentary too short for: {}", url);
            return null;
        }

        // Create document
        TafsirDocument doc = new TafsirDocument();
        doc.setTafsirSlug(tafsirSlug);
        doc.setTafsirName(tafsirName);
        doc.setSurahNumber(surahNumber);
        doc.setAyahStart(ayahRange.start);
        doc.setAyahEnd(ayahRange.end);

        if (ayahRange.start == ayahRange.end) {
            doc.setVerseKey(surahNumber + ":" + ayahRange.start);
        } else {
            doc.setVerseKey(surahNumber + ":" + ayahRange.start);
            List<String> verseKeys = new ArrayList<>();
            for (int i = ayahRange.start; i <= ayahRange.end; i++) {
                verseKeys.add(surahNumber + ":" + i);
            }
            doc.setVerseKeys(verseKeys);
        }

        doc.setCommentaryText(commentary);
        doc.setSectionTitle(sectionTitle);
        doc.setSourceUrl(url);
        doc.setLanguage("ar");
        doc.computeWordCount();

        return doc;
    }

    /**
     * Fetches a document from URL or local cache.
     */
    protected Document fetchDocument(String url) throws IOException {
        // Check cache first
        String cacheKey = sha1Hex(url);
        Path cachePath = Paths.get(sourceDir, "hodaalquran", tafsirSlug, cacheKey + ".html");

        if (Files.exists(cachePath)) {
            LOGGER.debug("Using cached file: {}", cachePath);
            String cachedHtml = Files.readString(cachePath);
            return Jsoup.parse(cachedHtml, url);
        }

        // Fetch from network
        LOGGER.debug("Fetching: {}", url);
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("Rewayaat-Tafsir-Extractor/1.0 (+https://rewayaat.com)")
                    .timeout(30000)
                    .get();

            // Cache the result
            if (!dryRun) {
                Files.createDirectories(cachePath.getParent());
                Files.writeString(cachePath, doc.outerHtml());
                LOGGER.debug("Cached to: {}", cachePath);
            }

            return doc;
        } catch (IOException e) {
            LOGGER.error("Failed to fetch: {}", url, e);
            throw e;
        }
    }

    private static String sha1Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 algorithm not available", e);
        }
    }

    private static int readInt(String key, int defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.isEmpty()) {
            value = System.getenv().get(key.replace('.', '_').toUpperCase());
        }
        try {
            return value != null ? Integer.parseInt(value) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String resolveSourceDir() {
        String dir = System.getProperty("tafsir.source-dir");
        if (dir == null || dir.isEmpty()) {
            dir = System.getenv().get("TAFSIR_SOURCE_DIR");
        }
        return (dir != null && !dir.isEmpty()) ? dir : "/tmp/tafsir-sources";
    }

    private static int resolveFetchDelay() {
        String delay = System.getProperty("tafsir.fetch-delay");
        if (delay == null || delay.isEmpty()) {
            delay = System.getenv().get("TAFSIR_FETCH_DELAY");
        }
        try {
            return (delay != null && !delay.isEmpty()) ? Integer.parseInt(delay) : DEFAULT_FETCH_DELAY;
        } catch (NumberFormatException e) {
            return DEFAULT_FETCH_DELAY;
        }
    }

    private static boolean resolveDryRun() {
        String dryRun = System.getProperty("tafsir.dry-run");
        if (dryRun == null || dryRun.isEmpty()) {
            dryRun = System.getenv().get("TAFSIR_DRY_RUN");
        }
        return "true".equalsIgnoreCase(dryRun);
    }

    // Inner classes
    protected static class SurahLink {
        final String name;
        final String contentId;
        SurahLink(String name, String contentId) {
            this.name = name;
            this.contentId = contentId;
        }
    }

    protected static class AyahLink {
        final String title;
        final String contentId;
        AyahLink(String title, String contentId) {
            this.title = title;
            this.contentId = contentId;
        }
    }

    protected static class AyahRange {
        final int start;
        final int end;
        AyahRange(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }
}
