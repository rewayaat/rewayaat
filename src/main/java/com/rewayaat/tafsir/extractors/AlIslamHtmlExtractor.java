package com.rewayaat.tafsir.extractors;

import com.rewayaat.tafsir.TafsirDocument;
import com.rewayaat.tafsir.VerseReferenceParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Base class for extracting tafsir content from al-islam.org HTML sources.
 * Handles common patterns: fetching, caching, parsing, rate limiting.
 */
public abstract class AlIslamHtmlExtractor implements TafsirExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(AlIslamHtmlExtractor.class);

    // Current volume URL for resolving relative URLs
    protected String currentVolumeUrl;

    private static final String DEFAULT_SOURCE_DIR = "/tmp/tafsir-sources";
    private static final int DEFAULT_FETCH_DELAY = 1000; // ms

    protected final String sourceDir;
    protected final int fetchDelay;
    protected final boolean dryRun;

    public AlIslamHtmlExtractor() {
        this.sourceDir = resolveSourceDir();
        this.fetchDelay = resolveFetchDelay();
        this.dryRun = resolveDryRun();
    }

    private static String resolveSourceDir() {
        String dir = System.getProperty("tafsir.source-dir");
        if (dir == null || dir.isEmpty()) {
            dir = System.getenv().get("TAFSIR_SOURCE_DIR");
        }
        return (dir != null && !dir.isEmpty()) ? dir : DEFAULT_SOURCE_DIR;
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

    /**
     * Returns the base URL pattern for this tafsir source.
     * Subclasses should implement to return the appropriate URL.
     */
    protected abstract String getBaseUrl();

    /**
     * Returns the volume numbers to extract.
     * Subclasses should implement to return the appropriate volume range.
     */
    protected abstract int[] getVolumeNumbers();

    /**
     * Extracts section URLs from a volume index page.
     * Subclasses can override for source-specific patterns.
     */
    protected List<String> extractSectionUrls(Document volumeIndex) {
        List<String> urls = new ArrayList<>();
        // Look for links to section pages
        Elements links = volumeIndex.select("a[href]");
        for (Element link : links) {
            String href = link.attr("href");
            // Filter for section pages (exclude external links, downloads, etc.)
            if (href.startsWith("/") || href.startsWith("http")) {
                // Note: This will be resolved against the volume URL in the calling code
                urls.add(href);
            }
        }
        return urls;
    }

    /**
     * Checks if a URL appears to be a section page (not an index, navigation, etc.).
     * Subclasses can override for source-specific filtering.
     */
    protected boolean isSectionPage(String url) {
        String lower = url.toLowerCase();

        // Exclude external links
        if (!lower.contains("al-islam.org")) {
            return false;
        }

        // Exclude common non-section pages and navigation links
        String[] excludePatterns = {
            "/print", "/download", "/export", "/pdf",
            "/library/", "/ask", "/tv", "/donate", "/contact", "/about",
            "/search", "/user", "/node/"
        };

        for (String pattern : excludePatterns) {
            if (lower.contains(pattern)) {
                return false;
            }
        }

        // Exclude simple top-level paths (like /library/, /authors/)
        if (lower.matches(".*al-islam.org/[^/]+/$")) {
            return false;
        }

        return true;
    }

    /**
     * Extracts tafsir documents from a section page.
     * Subclasses can override for source-specific parsing.
     */
    protected List<TafsirDocument> extractFromSectionPage(Document page, String url) {
        List<TafsirDocument> documents = new ArrayList<>();

        LOGGER.debug("Processing page: {}", url);

        // Try to find the main heading with verse reference
        String verseRef = extractVerseReferenceFromHeading(page);
        if (verseRef == null) {
            LOGGER.debug("No verse reference found in page: {}", url);
            LOGGER.debug("Page title: {}", page.title());
            // Log first few headings for debugging
            Elements headings = page.select("h1, h2, h3");
            LOGGER.debug("Found {} headings", headings.size());
            for (int i = 0; i < Math.min(3, headings.size()); i++) {
                LOGGER.debug("  Heading {}: {}", i+1, headings.get(i).text());
            }
            return documents;
        }

        // Parse the verse reference
        VerseReferenceParser.ParsedReference parsedRef = VerseReferenceParser.parse(verseRef);
        if (parsedRef == null || !parsedRef.isValid()) {
            LOGGER.warn("Could not parse verse reference: {} in {}", verseRef, url);
            return documents;
        }

        // Extract the commentary text
        String commentary = extractCommentaryText(page);
        if (commentary == null || commentary.trim().isEmpty()) {
            LOGGER.warn("No commentary text found in: {}", url);
            return documents;
        }

        // Extract verse text (if available)
        String verseText = extractVerseText(page);

        // Extract section title
        String sectionTitle = extractSectionTitle(page);

        // Create the document
        TafsirDocument doc = createDocument(parsedRef, commentary, verseText, sectionTitle, url);
        if (doc != null) {
            documents.add(doc);
        }

        return documents;
    }

    /**
     * Creates a TafsirDocument from parsed elements.
     */
    protected TafsirDocument createDocument(VerseReferenceParser.ParsedReference parsedRef,
                                           String commentary, String verseText,
                                           String sectionTitle, String url) {
        TafsirDocument doc = new TafsirDocument();
        doc.setTafsirSlug(getTafsirSlug());
        doc.setTafsirName(getTafsirName());
        doc.setSurahNumber(parsedRef.surahNumber);
        doc.setAyahStart(parsedRef.ayahStart);
        doc.setAyahEnd(parsedRef.ayahEnd);
        doc.setVerseKey(parsedRef.getVerseKey());
        doc.setVerseKeys(parsedRef.getVerseKeys());
        doc.setVerseTextEnglish(verseText);
        doc.setCommentaryText(commentary.trim());
        doc.setSectionTitle(sectionTitle);
        doc.setSourceUrl(url);
        doc.setLanguage("en");
        doc.computeWordCount();
        return doc;
    }

    /**
     * Extracts verse reference from the page heading.
     */
    protected String extractVerseReferenceFromHeading(Document page) {
        // Try various heading selectors
        String[] selectors = {
                "h1", "h2", "h3",
                ".title", ".heading", ".section-title",
                "[class*='title']",
                "title"
        };

        for (String selector : selectors) {
            Elements elements = page.select(selector);
            for (Element element : elements) {
                String text = element.text();
                if (VerseReferenceParser.containsVerseReference(text)) {
                    return text;
                }
            }
        }

        return null;
    }

    /**
     * Extracts the main commentary text from the page.
     */
    protected String extractCommentaryText(Document page) {
        // Try to find the main content area
        String[] contentSelectors = {
                ".content", ".main-content", ".article-content",
                "[class*='content']", "[class*='body']",
                "article", "main",
                ".field-item.even", ".field-item-odd"
        };

        for (String selector : contentSelectors) {
            Element content = page.selectFirst(selector);
            if (content != null) {
                // Remove navigation, footers, etc.
                content.select("nav, .navigation, .footer, .sidebar, .menu").remove();
                String text = content.text();
                if (text.length() > 100) { // Reasonable minimum
                    return text;
                }
            }
        }

        // Fallback: get the body text
        return page.body() != null ? page.body().text() : "";
    }

    /**
     * Extracts the verse text (Arabic and/or English translation) if present.
     */
    protected String extractVerseText(Document page) {
        // Look for verse text in common patterns
        String[] verseSelectors = {
                ".verse-text", ".quran-text", "[class*='verse']", "[class*='quran']"
        };

        for (String selector : verseSelectors) {
            Element verseElement = page.selectFirst(selector);
            if (verseElement != null) {
                return verseElement.text();
            }
        }

        return null;
    }

    /**
     * Extracts the section title from the page.
     */
    protected String extractSectionTitle(Document page) {
        Element titleElement = page.selectFirst("h1, h2, .title");
        return titleElement != null ? titleElement.text() : null;
    }

    @Override
    public List<TafsirDocument> extract() throws ExtractionException {
        List<TafsirDocument> allDocuments = new ArrayList<>();
        Set<String> processedPages = new java.util.LinkedHashSet<>();

        LOGGER.info("Extracting {} from al-islam.org", getTafsirName());
        LOGGER.info("Source directory: {}, Dry run: {}", sourceDir, dryRun);

        // Read limit from config for testing
        int maxSections = readInt("tafsir.max-sections", readInt("TAFSIR_MAX_SECTIONS", 0));
        int processedCount = 0;

        for (int volume : getVolumeNumbers()) {
            LOGGER.info("Processing volume {}", volume);

            String volumeUrl = getVolumeUrl(volume);
            currentVolumeUrl = volumeUrl; // Set for subclasses to use in ensureAbsoluteUrl
            Document volumeIndex = fetchDocument(volumeUrl);

            if (volumeIndex == null) {
                LOGGER.warn("Failed to fetch volume index: {}", volumeUrl);
                continue;
            }

            List<String> sectionUrls = extractSectionUrls(volumeIndex);
            LOGGER.info("Found {} section URLs in volume {}", sectionUrls.size(), volume);

            if (maxSections > 0) {
                LOGGER.info("Limiting to first {} sections for testing", maxSections);
                sectionUrls = sectionUrls.subList(0, Math.min(maxSections, sectionUrls.size()));
            }

            for (String sectionUrl : sectionUrls) {
                if (maxSections > 0 && processedCount >= maxSections) {
                    LOGGER.info("Reached limit of {} sections, stopping", maxSections);
                    break;
                }

                try {
                    String absoluteUrl = ensureAbsoluteUrl(sectionUrl, volumeUrl);

                    // Normalize URL by removing anchor to avoid duplicates
                    String normalizedUrl = absoluteUrl.replaceAll("#.*$", "");

                    if (processedPages.contains(normalizedUrl)) {
                        LOGGER.debug("Skipping duplicate page: {}", normalizedUrl);
                        processedCount++;
                        continue;
                    }

                    processedPages.add(normalizedUrl);
                    LOGGER.info("Processing section {}/{}: {}", processedCount + 1, sectionUrls.size(), absoluteUrl);
                    Document sectionPage = fetchDocument(absoluteUrl);
                    if (sectionPage != null) {
                        List<TafsirDocument> docs = extractFromSectionPage(sectionPage, absoluteUrl);
                        allDocuments.addAll(docs);
                        LOGGER.info("Extracted {} documents from section {}/{} (total: {})",
                                docs.size(), processedCount + 1, sectionUrls.size(), allDocuments.size());
                    }

                    processedCount++;

                    // Rate limiting
                    if (fetchDelay > 0) {
                        TimeUnit.MILLISECONDS.sleep(fetchDelay);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ExtractionException("Extraction interrupted", e);
                } catch (Exception e) {
                    LOGGER.error("Error processing section: {}", sectionUrl, e);
                }
            }

            LOGGER.info("Volume {} complete: {} documents extracted so far",
                    volume, allDocuments.size());

            if (maxSections > 0 && processedCount >= maxSections) {
                break;
            }
        }

        LOGGER.info("Extraction complete: {} documents from {}", allDocuments.size(), getTafsirName());
        return allDocuments;
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

    /**
     * Returns the URL for a specific volume.
     */
    protected String getVolumeUrl(int volume) {
        return getBaseUrl() + volume;
    }

    /**
     * Fetches a document from URL or local cache.
     */
    protected Document fetchDocument(String url) {
        try {
            // Check cache first
            String cacheKey = url.replaceAll("[^a-zA-Z0-9]", "_");
            Path cachePath = Paths.get(sourceDir, getTafsirSlug(), cacheKey + ".html");

            if (Files.exists(cachePath)) {
                LOGGER.debug("Using cached file: {}", cachePath);
                String cachedHtml = Files.readString(cachePath);
                return Jsoup.parse(cachedHtml, url);
            }

            // Fetch from network
            LOGGER.debug("Fetching: {}", url);
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0")
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
            LOGGER.error("Failed to fetch document: {}", url, e);
            return null;
        }
    }

    /**
     * Converts a relative URL to an absolute URL using the current volume URL.
     */
    protected String ensureAbsoluteUrl(String url) {
        return ensureAbsoluteUrl(url, currentVolumeUrl);
    }

    /**
     * Converts a relative URL to an absolute URL using the provided base URL.
     */
    protected String ensureAbsoluteUrl(String url, String baseUrl) {
        if (url.startsWith("http")) {
            return url;
        }
        // Resolve relative URL against the base URL
        try {
            java.net.URL base = new java.net.URL(baseUrl);
            return new java.net.URL(base, url).toString();
        } catch (java.net.MalformedURLException e) {
            LOGGER.warn("Failed to resolve URL: {} against {}", url, baseUrl);
            // Fallback: simple concatenation
            String base = baseUrl.replaceAll("/[^/]*$", "");
            return base + (url.startsWith("/") ? "" : "/") + url;
        }
    }
}
