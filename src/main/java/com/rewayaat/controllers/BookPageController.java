package com.rewayaat.controllers;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.config.ESClientProvider;
import com.rewayaat.core.HadithDisplaySegmenter;
import com.rewayaat.service.BookCatalog;
import io.swagger.v3.oas.annotations.Hidden;
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

    /** Enough to be a useful chapter page without turning one into a 500-narration wall. */
    private static final int MAX_CHAPTER_NARRATIONS = 500;
    private static final int EXCERPT_CHARS = 220;

    private final BookCatalog catalog;
    private final BookBlurbs blurbs = new BookBlurbs();
    private final TopicLabels topicLabels = new TopicLabels();

    public BookPageController(BookCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/books")
    public String booksIndex(Model model) {
        List<BookCatalog.Book> books = catalog.books();

        model.addAttribute("books", books);
        model.addAttribute("totalNarrations", books.stream().mapToLong(BookCatalog.Book::count).sum());
        model.addAttribute("seoTitle", "Shia Hadith Books — Al-Kafi, Nahj al-Balagha and More");
        model.addAttribute("seoDescription",
                "Browse the primary Shia hadith collections: Al-Kafi, Nahj al-Balagha, "
                + "Man La Yahduruh al-Faqih, Al-Khisal, Al-Amali and more, in Arabic and English.");
        model.addAttribute("canonicalUrl", BASE_URL + "/books");
        model.addAttribute("jsonLd", booksIndexJsonLd(books));
        addBreadcrumbs(model, new LinkedHashMap<>());
        return "books";
    }

    @GetMapping("/books/{bookSlug}")
    public String bookPage(@PathVariable String bookSlug, Model model, HttpServletResponse response)
            throws IOException {
        Optional<BookCatalog.Book> found = catalog.book(bookSlug);
        if (found.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return null;
        }
        BookCatalog.Book book = found.get();

        // Al-Kafi alone has 2,693 chapters. Listing every one of them on the book page
        // made it a 637KB document with 2,693 outbound links — slow on mobile and a
        // thin spread of link equity. Where a book has volumes, they become the level
        // in between, so no page in the chain carries more than a few hundred links.
        List<String> volumes = book.volumes();
        model.addAttribute("book", book);
        model.addAttribute("volumes", volumes.stream()
                .map(v -> Map.of(
                        "label", "Volume " + v,
                        "url", "/books/" + bookSlug + "/volume/" + encode(v),
                        "chapterCount", book.chaptersInVolume(v).size()))
                .toList());
        model.addAttribute("chapters", volumes.isEmpty() ? book.chapters() : List.of());
        model.addAttribute("blurb", blurbs.forSlug(bookSlug));
        model.addAttribute("seoTitle", book.name() + " — Shia Hadith in Arabic & English");
        model.addAttribute("seoDescription", String.format(
                "Read %s in Arabic and English: %,d narrations across %,d chapters, "
                + "with similar narrations and Quranic insights for each hadith.",
                book.name(), book.count(), book.chapters().size()));
        model.addAttribute("canonicalUrl", BASE_URL + "/books/" + bookSlug);
        model.addAttribute("jsonLd", bookJsonLd(book));
        LinkedHashMap<String, String> trail = new LinkedHashMap<>();
        trail.put(book.name(), "/books/" + bookSlug);
        addBreadcrumbs(model, trail);
        return "book";
    }

    @GetMapping("/books/{bookSlug}/volume/{volume}")
    public String volumePage(@PathVariable String bookSlug, @PathVariable String volume,
                             Model model, HttpServletResponse response) throws IOException {
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

        model.addAttribute("book", book);
        model.addAttribute("volumeLabel", label);
        model.addAttribute("chapters", chapters);
        model.addAttribute("volumes", List.of());
        model.addAttribute("narrationCount", narrations);
        model.addAttribute("seoTitle", book.name() + " " + label + " — Shia Hadith in Arabic & English");
        model.addAttribute("seoDescription", String.format(
                "%s, %s: %,d narrations across %,d chapters, in Arabic and English.",
                book.name(), label, narrations, chapters.size()));
        model.addAttribute("canonicalUrl", BASE_URL + "/books/" + bookSlug + "/volume/" + encode(volume));

        LinkedHashMap<String, String> trail = new LinkedHashMap<>();
        trail.put(book.name(), "/books/" + bookSlug);
        trail.put(label, "/books/" + bookSlug + "/volume/" + encode(volume));
        addBreadcrumbs(model, trail);
        model.addAttribute("jsonLd", bookJsonLd(book));
        return "volume";
    }

    @GetMapping("/books/{bookSlug}/{chapterSlug}")
    public String chapterPage(@PathVariable String bookSlug, @PathVariable String chapterSlug,
                              @RequestParam(value = "tag", required = false) String tag,
                              Model model, HttpServletResponse response) throws IOException {
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
        model.addAttribute("clearTagUrl", chapter.url());
        // A filtered view is a slice of a page that is already indexed, so it points its
        // canonical back at the whole chapter rather than competing with it.
        model.addAttribute("robotsDirective", activeTag == null ? null : "noindex, follow");

        model.addAttribute("chapter", chapter);
        model.addAttribute("narrations", narrations);
        model.addAttribute("bookUrl", "/books/" + bookSlug);
        model.addAttribute("seoTitle", chapter.title() + " — " + chapter.bookName());
        model.addAttribute("seoDescription", String.format(
                "%s: %,d narration%s from %s, in Arabic and English with full chains of transmission.",
                chapter.title(), chapter.count(), chapter.count() == 1 ? "" : "s", chapter.bookName()));
        model.addAttribute("canonicalUrl", BASE_URL + chapter.url());
        model.addAttribute("jsonLd", chapterJsonLd(chapter, narrations));

        LinkedHashMap<String, String> trail = new LinkedHashMap<>();
        trail.put(chapter.bookName(), "/books/" + bookSlug);
        trail.put(chapter.title(), chapter.url());
        addBreadcrumbs(model, trail);
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
                results.add(cardModel(hit.id(), source));
            }
        }
        results.sort((a, b) -> compareNumbers(str(a.get("number")), str(b.get("number"))));
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

    /**
     * One narration shaped for the shared card fragment.
     *
     * <p>Mirrors what the Vue app hands its own card: the chain split from the matn, the
     * metadata rows in the same order with the same icons, topic tags with their taxonomy
     * labels. The two renderers agree on the class names and on this shape; see
     * fragments/hadith-card.html for why the duplication is bounded.
     */
    private Map<String, Object> cardModel(String id, Map<String, Object> source) {
        Map<String, Object> segmented = new LinkedHashMap<>();
        segmented.put("english", source.get("english"));
        segmented.put("arabic", source.get("arabic"));
        try {
            HadithDisplaySegmenter.enrich(segmented);
        } catch (Exception e) {
            LOGGER.debug("Could not segment narration {}", id, e);
        }

        String book = str(source.get("book"));
        String number = str(source.get("number"));

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("url", "/hadith/" + id);
        row.put("number", number);
        row.put("label", (book + (number.isBlank() ? "" : " #" + number)).trim());
        row.put("englishChain", str(segmented.get("englishChain")));
        row.put("english", firstNonBlank(str(segmented.get("englishContent")), str(source.get("english"))));
        row.put("arabicChain", str(segmented.get("arabicChain")));
        row.put("arabic", firstNonBlank(str(segmented.get("arabicContent")), str(source.get("arabic"))));
        row.put("notes", str(source.get("notes")));
        row.put("metadata", metadataRows(source, number));
        row.put("tags", topicTags(source));
        row.put("tagSlugs", tagSlugs(source));
        row.put("similarCount", source.get("llm_similar") instanceof List<?> l ? l.size() : 0);
        row.put("shareUrl", BASE_URL + "/hadith/" + id);
        row.put("reportHref", reportHref(id, book, number));
        // The copy actions work off the text already on the page, so the card carries it
        // in the markup rather than the script re-fetching what the reader can see.
        row.put("copyJson", write(Map.of(
                "english", stripHtml(str(source.get("english"))),
                "arabic", stripHtml(str(source.get("arabic"))))));
        return row;
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

    /** The same prefilled report mail the search card opens, built server-side. */
    private static String reportHref(String id, String book, String number) {
        String descriptor = (book + (number.isBlank() ? "" : " #" + number)).trim();
        String subject = "Hadith Report: " + (descriptor.isBlank() ? "Hadith " + id : descriptor);
        String body = String.join("\n",
                "Please review the hadith linked below.",
                "",
                "Hadith link: " + BASE_URL + "/hadith/" + id,
                "Hadith id: " + id,
                "",
                "Issue summary:",
                "- ",
                "",
                "What seems incorrect:",
                "- ",
                "",
                "Suggested correction (optional):",
                "- ");
        return "mailto:rewayaat.org@gmail.com?subject=" + encode(subject) + "&body=" + encode(body);
    }

    /**
     * The sidecar rows, in the order and with the icons the Vue card uses.
     *
     * <p>Book, volume and chapter link to their own pages — the same destinations the
     * search card's metadata rows now go to, and more internal links into the hubs.
     * Part and section have no page of their own, so they render as plain text.
     */
    private List<Map<String, String>> metadataRows(Map<String, Object> source, String number) {
        String book = str(source.get("book"));
        String volume = str(source.get("volume"));
        String chapter = str(source.get("chapter"));

        Optional<BookCatalog.Book> catalogued = catalog.bookByName(book);
        String bookUrl = catalogued.map(b -> "/books/" + b.slug()).orElse(null);
        String volumeUrl = bookUrl == null || volume.isBlank() ? null
                : bookUrl + "/volume/" + encode(volume);
        String chapterUrl = catalog.chapterFor(book, volume, str(source.get("part")),
                str(source.get("section")), chapter).map(BookCatalog.Chapter::url).orElse(null);

        List<Map<String, String>> rows = new ArrayList<>();
        addRow(rows, "fa fa-hashtag", "Hadith #", number, null);
        addRow(rows, "fa fa-book", "Book", book, bookUrl);
        addRow(rows, "fa fa-layer-group", "Volume", volume, volumeUrl);
        addRow(rows, "fa fa-bookmark", "Section", str(source.get("section")), null);
        addRow(rows, "fa fa-clone", "Part", str(source.get("part")), null);
        addRow(rows, "fa fa-heading", "Chapter", chapter, chapterUrl);
        addRow(rows, "fa fa-arrow-right-from-bracket", "Source", str(source.get("source")), null);
        addRow(rows, "fa fa-pen-to-square", "Edition", str(source.get("edition")), null);
        addRow(rows, "fa fa-building", "Publisher", str(source.get("publisher")), null);
        return rows;
    }

    private static void addRow(List<Map<String, String>> rows, String icon, String label,
                               String value, String url) {
        if (value == null || value.isBlank()) {
            return;
        }
        Map<String, String> row = new LinkedHashMap<>();
        row.put("icon", icon);
        row.put("label", label);
        row.put("value", value);
        if (url != null) {
            row.put("url", url);
        }
        rows.add(row);
    }

    /** Tags link into a search for that topic, which is what clicking one does in the app. */
    private List<Map<String, String>> topicTags(Map<String, Object> source) {
        List<Map<String, String>> tags = new ArrayList<>();
        if (!(source.get("topic_tags") instanceof List<?> raw)) {
            return tags;
        }
        for (Object slug : raw) {
            String value = str(slug);
            if (value.isBlank()) {
                continue;
            }
            tags.add(Map.of("label", topicLabels.label(value),
                    "query", encode("topic_tags:\"" + value + "\"")));
        }
        return tags;
    }

    /** Plain text for the copy actions; the card shows the marked-up version. */
    private static String stripHtml(String html) {
        return html == null ? "" : html.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }

    private static List<String> tagSlugs(Map<String, Object> source) {
        if (!(source.get("topic_tags") instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream().map(BookPageController::str).filter(v -> !v.isBlank()).toList();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : (second == null ? "" : second);
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

    /**
     * The visible breadcrumb trail and its BreadcrumbList, built from one list so the two
     * can never disagree — a mismatch between them is exactly what Google flags.
     */
    private void addBreadcrumbs(Model model, Map<String, String> trail) {
        List<Map<String, String>> crumbs = new ArrayList<>();
        crumbs.add(Map.of("name", "Home", "url", "/"));
        crumbs.add(Map.of("name", "Books", "url", "/books"));
        trail.forEach((name, url) -> crumbs.add(Map.of("name", name, "url", url)));
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
