package com.rewayaat.controllers;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.config.ESClientProvider;
import com.rewayaat.core.HadithDisplaySegmenter;
import com.rewayaat.service.BookCatalog;
import com.rewayaat.service.HadithCardFactory;
import com.rewayaat.service.QuranicInsightsService;
import com.rewayaat.service.TopicLabelSource;
import io.swagger.v3.oas.annotations.Hidden;
import com.rewayaat.service.ArabicNames;
import com.rewayaat.service.PageLocale;
import org.springframework.context.MessageSource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Server-rendered book and chapter pages.
 *
 * <p>These are the pages the site was missing entirely. Without them nothing stood
 * between the home page and 32,519 narration pages: no URL could rank for a book or
 * chapter name, and the narrations themselves were reachable only from the XML sitemap,
 * so no internal link reached any of them. Every page here is plain server-rendered
 * HTML with real {@code <a href>} links, because the rest of the site renders its
 * content over XHR and a crawler that does not run scripts sees none of it.
 */
@Hidden
@Controller
public class BookPageController {

    private static final Logger LOGGER = LoggerFactory.getLogger(BookPageController.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE_URL = HomeController.BASE_URL;

    private final MessageSource messages;

    /** Enough to be a useful chapter page without turning one into a 500-narration wall. */
    private static final int MAX_CHAPTER_NARRATIONS = 500;
    private static final int EXCERPT_CHARS = 220;

    private final BookCatalog catalog;
    private final HadithCardFactory cards;
    private final TopicLabelSource topicLabels;
    private final BookBlurbs blurbs = new BookBlurbs();

    private final QuranicInsightsService quranicInsights;

    public BookPageController(BookCatalog catalog, HadithCardFactory cards,
                              TopicLabelSource topicLabels, QuranicInsightsService quranicInsights,
                              MessageSource messages) {
        this.messages = messages;
        this.catalog = catalog;
        this.cards = cards;
        this.topicLabels = topicLabels;
        this.quranicInsights = quranicInsights;
    }

    @GetMapping("/books")
    public String booksIndex(Model model, HttpServletRequest request) {
        List<BookCatalog.Book> books = catalog.books();

        model.addAttribute("books", books);
        model.addAttribute("totalNarrations", books.stream().mapToLong(BookCatalog.Book::count).sum());
        PageLocale locale = PageLocale.of(request);
        model.addAttribute("seoTitle", msg(locale, "seo.books.title"));
        model.addAttribute("seoDescription", msg(locale, "seo.books.description"));
        locale.applyTo(model, "/books");
        model.addAttribute("jsonLd", booksIndexJsonLd(books));
        addBreadcrumbs(model, new LinkedHashMap<>(), locale);
        return "books";
    }

    @GetMapping("/books/{bookSlug}")
    public String bookPage(@PathVariable String bookSlug, Model model, HttpServletResponse response,
                           HttpServletRequest request)
            throws IOException {
        Optional<BookCatalog.Book> found = catalog.book(bookSlug);
        if (found.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return null;
        }
        BookCatalog.Book book = found.get();

        // One level at a time, and only levels that exist. Al-Kafi has eight volumes;
        // Al-Khisal has one, so a volume level there is a page with a single card on it,
        // and its 908 chapters listed flat made a 1.35MB document. Books fall through to
        // whichever level actually divides them.
        List<String> volumes = book.volumes();
        List<BookCatalog.Part> parts = book.parts();
        boolean useVolumes = volumes.size() > 1;
        boolean useParts = !useVolumes && parts.size() > 1;

        PageLocale locale = PageLocale.of(request);
        model.addAttribute("book", book);
        model.addAttribute("volumes", useVolumes ? volumes.stream()
                .map(v -> Map.of(
                        "label", msg(locale, "book.volumeNumber", v),
                        "url", "/books/" + bookSlug + "/volume/" + encode(v),
                        "chapterCount", book.chaptersInVolume(v).size()))
                .toList() : List.of());
        model.addAttribute("parts", useParts ? parts : List.of());
        model.addAttribute("chapters", useVolumes || useParts ? List.of() : book.chapters());
        model.addAttribute("blurb", blurbs.forSlug(bookSlug, locale.isArabic()));
        // book_summaries.json has no Arabic. The Arabic blurb is a written introduction in
        // its own right, so the Arabic page leads with that rather than English prose.
        model.addAttribute("bookSummary", locale.isArabic()
                ? blurbs.forSlug(bookSlug, true) : blurbs.summaryForSlug(bookSlug));
        String bookName = named(locale, book.name(), book.nameAr());
        model.addAttribute("bookTitle", bookName);
        model.addAttribute("seoTitle", msg(locale, "seo.book.title", bookName));
        model.addAttribute("seoDescription", msg(locale, "seo.book.description",
                bookName, count(book.count()), count(book.chapters().size())));
        locale.applyTo(model, "/books/" + bookSlug);
        model.addAttribute("shareImageUrl", BASE_URL + "/books/" + bookSlug + "/card.png");
        model.addAttribute("jsonLd", bookJsonLd(book));
        LinkedHashMap<String, String> trail = new LinkedHashMap<>();
        trail.put(bookName, "/books/" + bookSlug);
        addBreadcrumbs(model, trail, locale);
        return "book";
    }

    @GetMapping("/books/{bookSlug}/volume/{volume}")
    public String volumePage(@PathVariable String bookSlug, @PathVariable String volume,
                             Model model, HttpServletResponse response,
                             HttpServletRequest request) throws IOException {
        Optional<BookCatalog.Book> found = catalog.book(bookSlug);
        if (found.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return null;
        }
        BookCatalog.Book book = found.get();
        List<BookCatalog.Chapter> chapters = book.chaptersInVolume(volume);
        if (chapters.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return null;
        }
        String label = "Volume " + volume;
        long narrations = chapters.stream().mapToLong(BookCatalog.Chapter::count).sum();
        List<BookCatalog.Part> parts = book.partsInVolume(volume);
        boolean useParts = parts.size() > 1;

        model.addAttribute("book", book);
        model.addAttribute("volumeLabel", label);
        model.addAttribute("parts", useParts ? parts : List.of());
        model.addAttribute("chapters", useParts ? List.of() : chapters);
        model.addAttribute("volumes", List.of());
        model.addAttribute("narrationCount", narrations);
        // The real chapter count, not #lists.size(chapters). When a volume splits into
        // parts the chapter list is handed to the part pages instead, so the hero counted
        // an empty list and announced "0 chapters" on a volume holding 1,448 narrations —
        // seven of Al-Kafi's eight volume pages read that way, while the meta description
        // built from chapters.size() gave the true figure on the very same page.
        model.addAttribute("chapterCount", chapters.size());
        PageLocale locale = PageLocale.of(request);
        String bookName = named(locale, book.name(), book.nameAr());
        String volumeLabel = locale.isArabic() ? msg(locale, "book.volumeNumber", volume) : label;
        model.addAttribute("bookTitle", bookName);
        model.addAttribute("volumeLabel", volumeLabel);
        model.addAttribute("seoTitle", msg(locale, "seo.volume.title", bookName, volumeLabel));
        model.addAttribute("seoDescription", msg(locale, "seo.volume.description",
                bookName, volumeLabel, count(narrations), count(chapters.size())));
        locale.applyTo(model, "/books/" + bookSlug + "/volume/" + encode(volume));
        model.addAttribute("sectionSummary",
                blurbs.sectionSummaryForPath("books/" + bookSlug + "/volume/" + volume));
        model.addAttribute("shareImageUrl", BASE_URL + "/books/" + bookSlug + "/volume/" + encode(volume) + "/card.png");

        LinkedHashMap<String, String> trail = new LinkedHashMap<>();
        trail.put(bookName, "/books/" + bookSlug);
        trail.put(volumeLabel, "/books/" + bookSlug + "/volume/" + encode(volume));
        addBreadcrumbs(model, trail, locale);
        model.addAttribute("jsonLd", bookJsonLd(book));
        return "volume";
    }

    @GetMapping("/books/{bookSlug}/part/{partSlug}")
    public String partPage(@PathVariable String bookSlug, @PathVariable String partSlug,
                           Model model, HttpServletResponse response,
                           HttpServletRequest request) throws IOException {
        Optional<BookCatalog.Book> foundBook = catalog.book(bookSlug);
        Optional<BookCatalog.Part> foundPart = catalog.part(bookSlug, partSlug);
        if (foundBook.isEmpty() || foundPart.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return null;
        }
        BookCatalog.Book book = foundBook.get();
        BookCatalog.Part part = foundPart.get();
        List<BookCatalog.Chapter> chapters = book.chaptersInPart(part.volume(), part.title());
        long narrations = chapters.stream().mapToLong(BookCatalog.Chapter::count).sum();

        model.addAttribute("book", book);
        // The part page reuses the volume template, whose heading is volumeLabel.
        PageLocale locale = PageLocale.of(request);
        String partTitle = named(locale, part.title(), part.titleAr());
        model.addAttribute("volumeLabel", partTitle);
        model.addAttribute("parts", List.of());
        model.addAttribute("chapters", chapters);
        model.addAttribute("volumes", List.of());
        model.addAttribute("narrationCount", narrations);
        // A part always lists its own chapters, so this equals #lists.size(chapters) here.
        // It is set anyway because volume.html is shared with volumePage, where the two
        // genuinely differ; a template reading one attribute on one route and another on
        // the other is how the "0 chapters" bug survived as long as it did.
        model.addAttribute("chapterCount", chapters.size());
        String bookName = named(locale, book.name(), book.nameAr());
        model.addAttribute("bookTitle", bookName);
        model.addAttribute("partTitle", partTitle);
        model.addAttribute("seoTitle", msg(locale, "seo.part.title", partTitle, bookName));
        model.addAttribute("seoDescription", msg(locale, "seo.part.description",
                bookName, partTitle, count(narrations), count(chapters.size())));
        locale.applyTo(model, part.url());
        model.addAttribute("sectionSummary", blurbs.sectionSummaryForPath(part.url()));
        model.addAttribute("shareImageUrl", BASE_URL + part.url() + "/card.png");
        model.addAttribute("jsonLd", bookJsonLd(book));

        LinkedHashMap<String, String> trail = new LinkedHashMap<>();
        trail.put(bookName, "/books/" + bookSlug);
        if (part.volume() != null && !part.volume().isBlank() && book.volumes().size() > 1) {
            trail.put(msg(locale, "book.volumeNumber", part.volume()),
                    "/books/" + bookSlug + "/volume/" + encode(part.volume()));
        }
        trail.put(partTitle, part.url());
        addBreadcrumbs(model, trail, locale);
        return "volume";
    }

    @GetMapping("/books/{bookSlug}/{chapterSlug}")
    public String chapterPage(@PathVariable String bookSlug, @PathVariable String chapterSlug,
                              @RequestParam(value = "tag", required = false) String tag,
                              Model model, HttpServletResponse response,
                              HttpServletRequest request) throws IOException {
        Optional<BookCatalog.Chapter> found = catalog.chapter(bookSlug, chapterSlug);
        if (found.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return null;
        }
        BookCatalog.Chapter chapter = found.get();
        List<Map<String, Object>> all = narrationsIn(chapter);

        // The tag facet, counted over the whole chapter so the counts do not change as
        // you filter — the same behaviour the search page's tag bar has.
        List<Map<String, Object>> facets = tagFacets(all, tag, chapter);
        String activeTag = tag == null || tag.isBlank() ? null : tag.trim();
        List<Map<String, Object>> narrations = activeTag == null ? all : all.stream()
                .filter(n -> hasTag(n, activeTag))
                .toList();

        model.addAttribute("tagFacets", facets);
        model.addAttribute("activeTag", activeTag);
        model.addAttribute("activeTagLabel", activeTag == null ? null : topicLabels.label(activeTag));
        PageLocale locale = PageLocale.of(request);
        model.addAttribute("clearTagUrl", locale.prefix() + chapter.url());
        // A filtered view is a slice of a page that is already indexed, so it points its
        // canonical back at the whole chapter rather than competing with it.
        model.addAttribute("robotsDirective", activeTag == null ? null : "noindex, follow");

        model.addAttribute("chapter", chapter);
        model.addAttribute("narrations", narrations);
        model.addAttribute("bookUrl", locale.prefix() + "/books/" + bookSlug);
        // The hero chips navigate where a destination exists: the book name and the
        // volume have pages, the section does not.
        model.addAttribute("volumeUrl", chapter.volume() == null || chapter.volume().isBlank()
                ? null : locale.prefix() + "/books/" + bookSlug + "/volume/" + encode(chapter.volume()));
        String chapterTitle = named(locale, chapter.title(), chapter.titleAr());
        String chapterBook = named(locale, chapter.bookName(), chapter.bookNameAr());
        model.addAttribute("chapterTitle", chapterTitle);
        model.addAttribute("bookTitle", chapterBook);
        model.addAttribute("seoTitle", msg(locale, "seo.chapter.title", chapterTitle, chapterBook));
        model.addAttribute("seoDescription", msg(locale, "seo.chapter.description",
                chapterTitle, count(chapter.count()), chapterBook));
        // A chapter holding a single narration *is* that narration: the two pages carry
        // the same text, both were self-canonical, and both sat in a sitemap, so roughly
        // 4,000 pairs competed with each other and Search Console reported the whole
        // corpus as "Discovered - currently not indexed". The narration wins the pair: it
        // is the URL the MCP server cites, the share card names, and other narrations
        // link to. The chapter page stays crawlable and followed, so the hub graph of
        // invariant 5 is untouched — only the duplicate signal goes away.
        //
        // Gated on the catalog's predicate, the one booksSitemap excludes by, so no chapter
        // can be both listed in the sitemap and canonical to somewhere else. The size check
        // confirms the page really shows that one narration: if the index and the catalog
        // briefly disagree, the chapter stays self-canonical and merely unlisted, which is
        // the harmless way round.
        String canonicalPath = chapter.holdsSingleNarration() && all.size() == 1
                ? str(all.get(0).get("url")) : chapter.url();
        locale.applyTo(model, canonicalPath);
        model.addAttribute("shareImageUrl", BASE_URL + chapter.url() + "/card.png");
        model.addAttribute("jsonLd", chapterJsonLd(chapter, narrations));

        LinkedHashMap<String, String> trail = new LinkedHashMap<>();
        trail.put(chapterBook, "/books/" + bookSlug);
        trail.put(chapterTitle, chapter.url());
        // Previous/next stay inside the language the reader is in, title and URL alike.
        catalog.siblingChapter(chapter, -1).ifPresent(prev -> {
            model.addAttribute("prevUrl", locale.prefix() + prev.url());
            model.addAttribute("prevLabel", named(locale, prev.title(), prev.titleAr()));
        });
        catalog.siblingChapter(chapter, 1).ifPresent(next -> {
            model.addAttribute("nextUrl", locale.prefix() + next.url());
            model.addAttribute("nextLabel", named(locale, next.title(), next.titleAr()));
        });

        addBreadcrumbs(model, trail, locale);
        return "chapter";
    }

    /**
     * The narrations of one chapter, in the book's own numbering order.
     *
     * <p>Filtered on the same tuple the catalog is keyed by — a chapter title alone is
     * not unique, the same title recurs across volumes of the same book.
     */
    private List<Map<String, Object>> narrationsIn(BookCatalog.Chapter chapter) throws IOException {
        List<Map<String, Object>> results = new ArrayList<>();
        try (ESClientProvider provider = new ESClientProvider()) {
            ElasticsearchClient client = provider.client();
            SearchResponse<Map> response = client.search(s -> s
                    .index(ESClientProvider.INDEX)
                    .size(MAX_CHAPTER_NARRATIONS)
                    .trackTotalHits(t -> t.enabled(false))
                    .source(src -> src.filter(f -> f.includes(
                            "book", "number", "english", "arabic", "notes", "volume", "part",
                            "section", "chapter", "source", "edition", "publisher",
                            "topic_tags", "llm_similar")))
                    .query(q -> q.bool(b -> {
                        b.filter(f -> f.term(t -> t.field("book").value(chapter.bookName())));
                        b.filter(f -> f.term(t -> t.field("chapter.keyword").value(chapter.title())));
                        addFacetFilter(b, "volume", chapter.volume());
                        addFacetFilter(b, "part", chapter.part());
                        addFacetFilter(b, "section", chapter.section());
                        return b;
                    })), Map.class);

            for (Hit<Map> hit : response.hits().hits()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> source = hit.source();
                if (source == null) {
                    continue;
                }
                results.add(cards.build(hit.id(), source, chapter.url(), BASE_URL));
            }
        }
        results.sort((a, b) -> compareNumbers(str(a.get("number")), str(b.get("number"))));

        // One query for the whole page: the TAFSIR rail carries a count like RELATED does.
        Map<String, Integer> counts = quranicInsights.insightCounts(
                results.stream().map(r -> str(r.get("id"))).toList());
        results.forEach(r -> r.put("quranCount", counts.getOrDefault(str(r.get("id")), 0)));
        return results;
    }

    private static void addFacetFilter(co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder b,
                                       String field, String value) {
        if (value == null || value.isBlank()) {
            // A missing facet is a real distinction: "no volume" must not match every
            // volume, or a chapter page would list the whole book.
            b.mustNot(m -> m.exists(e -> e.field(field)));
            return;
        }
        b.filter(f -> f.term(t -> t.field(field).value(value)));
    }

    /** Sorts hadith 2 before hadith 10, falling back to text order for non-numeric labels. */
    static int compareNumbers(String a, String b) {
        try {
            return Long.compare(Long.parseLong(a.trim()), Long.parseLong(b.trim()));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }

    /** Volume labels are free text in the index, so they cannot go into a path raw. */
    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * The narration itself, with the chain of transmission stripped.
     *
     * <p>Excerpting the raw English would open every entry with its isnad — "A number of
     * our people have narrated from…" — which is near-identical boilerplate across
     * thousands of narrations, and would make every chapter page look like a page of
     * duplicates to a reader and to a crawler alike.
     */
    private static String matn(Map<String, Object> source) {
        Map<String, Object> segmented = new LinkedHashMap<>();
        segmented.put("english", source.get("english"));
        segmented.put("arabic", source.get("arabic"));
        try {
            HadithDisplaySegmenter.enrich(segmented);
        } catch (Exception e) {
            LOGGER.debug("Could not segment a narration for its excerpt", e);
        }
        String content = str(segmented.getOrDefault("englishContent", ""));
        return content.isBlank() ? str(source.get("english")) : content;
    }


    /** Topic tags present in this chapter, with counts, most common first. */
    private List<Map<String, Object>> tagFacets(List<Map<String, Object>> narrations,
                                                String activeTag, BookCatalog.Chapter chapter) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> narration : narrations) {
            for (String slug : slugsOf(narration)) {
                counts.merge(slug, 1L, Long::sum);
            }
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(e -> Map.<String, Object>of(
                        "slug", e.getKey(),
                        "label", topicLabels.label(e.getKey()),
                        "count", e.getValue(),
                        "active", e.getKey().equals(activeTag),
                        "url", chapter.url() + "?tag=" + encode(e.getKey())))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> slugsOf(Map<String, Object> narration) {
        Object raw = narration.get("tagSlugs");
        return raw instanceof List<?> list ? (List<String>) list : List.of();
    }

    private static boolean hasTag(Map<String, Object> narration, String tag) {
        return slugsOf(narration).contains(tag);
    }








    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String excerpt(String html) {
        String text = html.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
        if (text.length() <= EXCERPT_CHARS) {
            return text;
        }
        int cut = text.lastIndexOf(' ', EXCERPT_CHARS);
        return text.substring(0, cut <= 0 ? EXCERPT_CHARS : cut) + "…";
    }

    /** A message in the page's language. Arguments are pre-formatted strings. */
    private String msg(PageLocale locale, String key, Object... args) {
        return messages.getMessage(key, args, locale.locale());
    }

    /** The Arabic name when there is one and the page is Arabic; the English one otherwise. */
    private static String named(PageLocale locale, String english, String arabic) {
        return locale.isArabic() && arabic != null && !arabic.isBlank() ? arabic : english;
    }

    /** Thousands-separated, in Latin digits, because these land in titles a crawler reads. */
    private static String count(long value) {
        return String.format("%,d", value);
    }

    /**
     * The visible breadcrumb trail and its BreadcrumbList, built from one list so the two
     * can never disagree — a mismatch between them is exactly what Google flags.
     */
    private void addBreadcrumbs(Model model, Map<String, String> trail, PageLocale locale) {
        List<Map<String, String>> crumbs = new ArrayList<>();
        // Every URL in the trail carries the prefix, so a reader following it back up
        // stays in Arabic, and the BreadcrumbList describes the page it is actually on.
        String prefix = locale.prefix();
        crumbs.add(Map.of("name", msg(locale, "crumb.home"), "url", prefix + "/"));
        crumbs.add(Map.of("name", msg(locale, "nav.books"), "url", prefix + "/books"));
        trail.forEach((name, url) -> crumbs.add(Map.of("name", name, "url", prefix + url)));
        model.addAttribute("breadcrumbs", crumbs);
        model.addAttribute("breadcrumbJsonLd", breadcrumbJsonLd(crumbs));
    }

    private String breadcrumbJsonLd(List<Map<String, String>> crumbs) {
        List<Map<String, Object>> items = new ArrayList<>();
        int position = 1;
        for (Map<String, String> crumb : crumbs) {
            items.add(new LinkedHashMap<>(Map.of(
                    "@type", "ListItem",
                    "position", position++,
                    "name", crumb.get("name"),
                    "item", BASE_URL + crumb.get("url"))));
        }
        Map<String, Object> ld = new LinkedHashMap<>();
        ld.put("@context", "https://schema.org");
        ld.put("@type", "BreadcrumbList");
        ld.put("itemListElement", items);
        return write(ld);
    }

    private String booksIndexJsonLd(List<BookCatalog.Book> books) {
        List<Map<String, Object>> items = new ArrayList<>();
        int position = 1;
        for (BookCatalog.Book book : books) {
            items.add(new LinkedHashMap<>(Map.of(
                    "@type", "ListItem",
                    "position", position++,
                    "name", book.name(),
                    "url", BASE_URL + "/books/" + book.slug())));
        }
        return write(new LinkedHashMap<>(Map.of(
                "@context", "https://schema.org",
                "@type", "ItemList",
                "name", "Shia hadith collections",
                "numberOfItems", books.size(),
                "itemListElement", items)));
    }

    private String bookJsonLd(BookCatalog.Book book) {
        Map<String, Object> ld = new LinkedHashMap<>();
        ld.put("@context", "https://schema.org");
        ld.put("@type", "Book");
        ld.put("name", book.name());
        ld.put("url", BASE_URL + "/books/" + book.slug());
        ld.put("numberOfPages", book.count());
        ld.put("inLanguage", List.of("ar", "en"));
        ld.put("genre", "Hadith");
        return write(ld);
    }

    private String chapterJsonLd(BookCatalog.Chapter chapter, List<Map<String, Object>> narrations) {
        List<Map<String, Object>> items = new ArrayList<>();
        int position = 1;
        for (Map<String, Object> narration : narrations) {
            items.add(new LinkedHashMap<>(Map.of(
                    "@type", "ListItem",
                    "position", position++,
                    "url", BASE_URL + narration.get("url"))));
        }
        Map<String, Object> ld = new LinkedHashMap<>();
        ld.put("@context", "https://schema.org");
        ld.put("@type", "ItemList");
        ld.put("name", chapter.title());
        ld.put("numberOfItems", narrations.size());
        ld.put("itemListElement", items);
        return write(ld);
    }

    private String write(Object value) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            LOGGER.warn("Could not serialise JSON-LD", e);
            return "{}";
        }
    }
}
