package com.rewayaat.tafsir.extractors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.tafsir.SurahNameResolver;
import com.rewayaat.tafsir.TafsirDocument;
import com.rewayaat.tafsir.TafsirSnippetSanitizer;
import com.rewayaat.tafsir.VerseReferenceParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extractor for Academy of Islam Quran Reflections.
 *
 * Source: https://academyofislam.com/quran-reflections/
 * Discovery: WordPress REST API category pagination
 * Coverage: English reflections category
 */
public class QuranicReflectionsExtractor implements TafsirExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuranicReflectionsExtractor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String API_BASE = "https://academyofislam.com/wp-json/wp/v2/posts";
    private static final int DEFAULT_CATEGORY_ID = 50; // Quran Reflections - English
    private static final int PAGE_SIZE = 100;
    private static final Pattern TITLE_AYAT_PATTERN =
            Pattern.compile("(?iu)ā?y(?:a|ā)?t\\s+(\\d+)\\s*:\\s*(\\d+)(?:\\s*(?:[-–—]|to|&)\\s*(?:(\\d+)\\s*:\\s*)?(\\d+))?");
    private static final Pattern TITLE_Q_PATTERN =
            Pattern.compile("(?i)\\bon\\s+q\\s*(\\d+)\\s*:\\s*(\\d+)(?:\\s*(?:[-–—]|to|&)\\s*(?:(\\d+)\\s*:\\s*)?(\\d+))?");
    private static final Pattern SURAH_LINE_PATTERN =
            Pattern.compile("(?iu)s[ūu]rat\\s+([^,]+),\\s*No\\.\\s*(\\d+),\\s*ā?yat\\s+(\\d+)(?:\\s*[-–—]\\s*(\\d+))?");
    private static final Pattern ISSUE_PATTERN =
            Pattern.compile("(?i)reflection\\s+no\\s*(\\d+)");
    private static final Pattern BISMILLAH_PATTERN =
            Pattern.compile("(?iu)^bismill[āa]h\\.?$");
    private static final Pattern PROMO_LINE_PATTERN =
            Pattern.compile("(?i)(tahajjud salat app|google play|apple store|download the latest copy of all tafsir sessions|visit www\\.academyofislam\\.com/ali-|have you registered(?: for .*?)?|registration:|seeking sponsorships for weekly quranic reflections|donations welcome|for our course learning arabic thru the quran|did you buy your copy|get digital copies of books|for more info and your order|academyofislam\\.com/publications|check https://academyofislam\\.com/publications|go to https://academyofislam\\.com/publications)");
    private static final Pattern PROMO_SECTION_PATTERN =
            Pattern.compile("(?i)^(ALI\\s+\\d+\\b|seeking sponsorships for weekly quranic reflections|the world federation\\b|registration\\b|schedule:\\b|cost:\\b|date:\\b|format:\\b)");

    private final HttpClient httpClient;
    private final String sourceDir;
    private final int categoryId;
    private final int maxPages;

    public QuranicReflectionsExtractor() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.sourceDir = resolveSourceDir();
        this.categoryId = readInt("QURANIC_REFLECTIONS_CATEGORY_ID", DEFAULT_CATEGORY_ID);
        this.maxPages = readInt("QURANIC_REFLECTIONS_MAX_PAGES", Integer.MAX_VALUE);
    }

    @Override
    public List<TafsirDocument> extract() throws ExtractionException {
        LOGGER.info("Extracting {} from Academy of Islam category {}", getTafsirName(), categoryId);

        ApiPage firstPage = fetchPage(1);
        int totalPages = Math.min(firstPage.totalPages(), maxPages);
        LOGGER.info("Quranic Reflections total pages: {} (processing {})", firstPage.totalPages(), totalPages);

        List<TafsirDocument> documents = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();

        processPosts(firstPage.posts(), documents, seenIds);
        for (int page = 2; page <= totalPages; page++) {
            ApiPage apiPage = fetchPage(page);
            processPosts(apiPage.posts(), documents, seenIds);
        }

        LOGGER.info("Extraction complete: {} documents from {}", documents.size(), getTafsirName());
        return documents;
    }

    @Override
    public String getTafsirSlug() {
        return "quranic-reflections";
    }

    @Override
    public String getTafsirName() {
        return "Quranic Reflections";
    }

    private void processPosts(JsonNode posts, List<TafsirDocument> documents, Set<String> seenIds) {
        if (posts == null || !posts.isArray()) {
            return;
        }

        for (JsonNode post : posts) {
            TafsirDocument doc = extractPost(post);
            if (doc == null) {
                continue;
            }
            if (seenIds.add(doc.getId())) {
                documents.add(doc);
            }
        }
    }

    private TafsirDocument extractPost(JsonNode post) {
        long postId = post.path("id").asLong(0L);
        String sourceUrl = post.path("link").asText("").trim();
        String title = htmlToText(post.path("title").path("rendered").asText(""));
        String renderedHtml = post.path("content").path("rendered").asText("");
        if (postId == 0L || sourceUrl.isBlank() || renderedHtml.isBlank()) {
            return null;
        }

        Document html = Jsoup.parseBodyFragment(renderedHtml);
        cleanupHtml(html);

        VerseReferenceParser.ParsedReference parsedRef = parseReference(title, html);
        if (parsedRef == null || !parsedRef.isValid()) {
            LOGGER.debug("Skipping Quranic Reflection without valid verse ref: {} ({})", title, sourceUrl);
            return null;
        }

        String verseTextEnglish = extractVerseTranslation(html);
        String commentary = extractCommentaryText(html, verseTextEnglish);
        if (commentary == null || commentary.isBlank()) {
            LOGGER.debug("Skipping Quranic Reflection without commentary body: {}", sourceUrl);
            return null;
        }

        TafsirDocument doc = new TafsirDocument();
        doc.setDocumentId(getTafsirSlug() + "_" + postId);
        doc.setTafsirSlug(getTafsirSlug());
        doc.setTafsirName(getTafsirName());
        doc.setSurahNumber(parsedRef.surahNumber);
        doc.setAyahStart(parsedRef.ayahStart);
        doc.setAyahEnd(parsedRef.ayahEnd);
        doc.setVerseKey(parsedRef.getVerseKey());
        doc.setVerseKeys(parsedRef.getVerseKeys());
        doc.setVerseTextEnglish(verseTextEnglish);
        doc.setCommentaryText(commentary);
        doc.setSectionTitle(title);
        doc.setSourceUrl(sourceUrl);
        doc.setVolume(extractIssueNumber(title));
        doc.setLanguage("en");
        TafsirSnippetSanitizer.sanitize(doc);
        return doc.getCommentaryWordCount() >= 40 ? doc : null;
    }

    private VerseReferenceParser.ParsedReference parseReference(String title, Document html) {
        VerseReferenceParser.ParsedReference fromTitle = parseTitleReference(title);
        if (fromTitle != null && fromTitle.isValid()) {
            return fromTitle;
        }

        Element verseMeta = html.selectFirst("p:matchesOwn((?i)Sūrat|Surat)");
        if (verseMeta != null) {
            VerseReferenceParser.ParsedReference fromLine = parseSurahLine(verseMeta.text());
            if (fromLine != null && fromLine.isValid()) {
                return fromLine;
            }
        }

        for (Element heading : html.select("h1, h2, h3, p")) {
            VerseReferenceParser.ParsedReference parsed = VerseReferenceParser.parse(heading.text());
            if (parsed != null && parsed.isValid()) {
                return parsed;
            }
        }

        return null;
    }

    private VerseReferenceParser.ParsedReference parseTitleReference(String title) {
        VerseReferenceParser.ParsedReference parsed = parseTitlePattern(TITLE_AYAT_PATTERN, title);
        if (parsed != null && parsed.isValid()) {
            return parsed;
        }
        return parseTitlePattern(TITLE_Q_PATTERN, title);
    }

    private VerseReferenceParser.ParsedReference parseTitlePattern(Pattern pattern, String title) {
        Matcher matcher = pattern.matcher(title);
        if (!matcher.find()) {
            return null;
        }
        int surahNumber = Integer.parseInt(matcher.group(1));
        int ayahStart = Integer.parseInt(matcher.group(2));
        if (matcher.group(4) == null) {
            return new VerseReferenceParser.ParsedReference(surahNumber, ayahStart, ayahStart);
        }

        Integer rangeSurah = matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : surahNumber;
        if (!rangeSurah.equals(surahNumber)) {
            return new VerseReferenceParser.ParsedReference(surahNumber, ayahStart, ayahStart);
        }
        int ayahEnd = Integer.parseInt(matcher.group(4));
        return new VerseReferenceParser.ParsedReference(surahNumber, ayahStart, ayahEnd);
    }

    private VerseReferenceParser.ParsedReference parseSurahLine(String line) {
        Matcher matcher = SURAH_LINE_PATTERN.matcher(line);
        if (matcher.find()) {
            int surahNumber = Integer.parseInt(matcher.group(2));
            int ayahStart = Integer.parseInt(matcher.group(3));
            Integer ayahEnd = matcher.group(4) != null ? Integer.parseInt(matcher.group(4)) : ayahStart;
            return new VerseReferenceParser.ParsedReference(surahNumber, ayahStart, ayahEnd);
        }

        String normalized = line.replace('\u00A0', ' ').trim();
        VerseReferenceParser.ParsedReference parsed = VerseReferenceParser.parse(normalized);
        if (parsed != null && parsed.isValid()) {
            return parsed;
        }

        String lower = normalized.toLowerCase(Locale.ROOT);
        if (!lower.contains("āyat") && !lower.contains("ayat")) {
            return null;
        }

        Integer surahNumber = null;
        Matcher surahMatcher = Pattern.compile("(?iu)s[ūu]rat\\s+([^,]+)").matcher(normalized);
        if (surahMatcher.find()) {
            surahNumber = SurahNameResolver.resolve(surahMatcher.group(1).trim());
        }
        Matcher ayahMatcher = Pattern.compile("(?iu)ā?yat\\s+(\\d+)(?:\\s*[-–—]\\s*(\\d+))?").matcher(normalized);
        if (surahNumber != null && ayahMatcher.find()) {
            int ayahStart = Integer.parseInt(ayahMatcher.group(1));
            Integer ayahEnd = ayahMatcher.group(2) != null ? Integer.parseInt(ayahMatcher.group(2)) : ayahStart;
            return new VerseReferenceParser.ParsedReference(surahNumber, ayahStart, ayahEnd);
        }

        return null;
    }

    private String extractVerseTranslation(Document html) {
        for (Element heading : html.select("h2")) {
            String text = cleanText(heading.text());
            if (text.isBlank()) {
                continue;
            }
            if (containsArabic(text)) {
                continue;
            }
            if (!text.contains(" ")) {
                continue;
            }
            if (text.toLowerCase(Locale.ROOT).startsWith("quranic reflection")) {
                continue;
            }
            return text;
        }
        return null;
    }

    private String extractCommentaryText(Document html, String verseTranslation) {
        List<String> blocks = new ArrayList<>();
        for (Element element : html.body().children()) {
            String tag = element.tagName();
            if ("style".equals(tag) || "script".equals(tag)) {
                continue;
            }

            String text = cleanText(element.text());
            if (text.isBlank()) {
                continue;
            }
            if (containsArabic(text)) {
                continue;
            }
            if (isVerseMetadataLine(text)) {
                continue;
            }
            if (verseTranslation != null && text.equals(verseTranslation)) {
                continue;
            }
            if (BISMILLAH_PATTERN.matcher(text).matches()) {
                continue;
            }
            if (isBoilerplateLine(text)) {
                if (!blocks.isEmpty()) {
                    break;
                }
                continue;
            }
            if (text.equalsIgnoreCase("Sources:")) {
                continue;
            }
            if (text.toLowerCase(Locale.ROOT).startsWith("sources: ")) {
                continue;
            }

            blocks.add(text);
        }

        return String.join("\n\n", blocks).trim();
    }

    private boolean isVerseMetadataLine(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.startsWith("sūrat ")
                || lower.startsWith("surat ")
                || lower.startsWith("surah ")
                || lower.startsWith("sura ")
                || lower.startsWith("quranic reflection no ");
    }

    private boolean isBoilerplateLine(String text) {
        String normalized = text.replace('\u00A0', ' ').trim();
        return PROMO_LINE_PATTERN.matcher(normalized).find()
                || PROMO_SECTION_PATTERN.matcher(normalized).find();
    }

    private void cleanupHtml(Document html) {
        html.select("style, script, .wp-block-kadence-spacer, hr").remove();
    }

    private boolean containsArabic(String text) {
        return text.codePoints().anyMatch(codePoint ->
                (codePoint >= 0x0600 && codePoint <= 0x06FF)
                        || (codePoint >= 0x0750 && codePoint <= 0x077F)
                        || (codePoint >= 0x08A0 && codePoint <= 0x08FF));
    }

    private String extractIssueNumber(String title) {
        Matcher matcher = ISSUE_PATTERN.matcher(title);
        return matcher.find() ? matcher.group(1) : null;
    }

    private ApiPage fetchPage(int pageNumber) throws ExtractionException {
        Path cachePath = Paths.get(sourceDir, "quranic-reflections", "academy-english", "page-" + pageNumber + ".json");
        try {
            Files.createDirectories(cachePath.getParent());
            if (Files.exists(cachePath)) {
                JsonNode cached = MAPPER.readTree(Files.readString(cachePath, StandardCharsets.UTF_8));
                JsonNode posts = cached.path("posts");
                int totalPages = cached.path("totalPages").asInt(pageNumber);
                return new ApiPage(posts, totalPages);
            }

            String query = "categories=" + categoryId
                    + "&per_page=" + PAGE_SIZE
                    + "&page=" + pageNumber
                    + "&_fields=" + urlEncode("id,link,slug,title,content,excerpt,date");
            HttpRequest request = HttpRequest.newBuilder(URI.create(API_BASE + "?" + query))
                    .header("Accept", "application/json")
                    .header("User-Agent", "rewayaat-tafsir-extractor/1.0")
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new ExtractionException("Failed fetching Quranic Reflections page " + pageNumber
                        + ": HTTP " + response.statusCode());
            }

            JsonNode posts = MAPPER.readTree(response.body());
            int totalPages = response.headers().firstValue("X-WP-TotalPages")
                    .map(Integer::parseInt)
                    .orElse(pageNumber);
            JsonNode wrapper = MAPPER.createObjectNode()
                    .set("posts", posts);
            ((com.fasterxml.jackson.databind.node.ObjectNode) wrapper).put("totalPages", totalPages);
            Files.writeString(cachePath, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(wrapper),
                    StandardCharsets.UTF_8);
            return new ApiPage(posts, totalPages);
        } catch (IOException e) {
            throw new ExtractionException("Failed reading/writing Quranic Reflections cache for page " + pageNumber, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExtractionException("Interrupted fetching Quranic Reflections page " + pageNumber, e);
        }
    }

    private static String htmlToText(String html) {
        return cleanText(Jsoup.parse(html).text());
    }

    private static String cleanText(String text) {
        return text.replace('\u00A0', ' ')
                .replaceAll("[ \\t]+", " ")
                .replaceAll(" *\\n *", "\n")
                .trim();
    }

    private static String resolveSourceDir() {
        String dir = System.getProperty("tafsir.source-dir");
        if (dir == null || dir.isEmpty()) {
            dir = System.getenv().get("TAFSIR_SOURCE_DIR");
        }
        return (dir != null && !dir.isEmpty()) ? dir : "/tmp/tafsir-sources";
    }

    private static int readInt(String name, int defaultValue) {
        String value = System.getProperty(name.toLowerCase(Locale.ROOT).replace('_', '.'));
        if (value == null || value.isBlank()) {
            value = System.getenv(name);
        }
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record ApiPage(JsonNode posts, int totalPages) {
    }
}
