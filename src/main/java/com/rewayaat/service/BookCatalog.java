package com.rewayaat.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.aggregations.CompositeAggregate;
import co.elastic.clients.elasticsearch._types.aggregations.CompositeAggregationSource;
import co.elastic.clients.elasticsearch._types.aggregations.CompositeBucket;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.util.NamedValue;
import com.rewayaat.config.ESClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The book / chapter structure of the corpus, addressable by URL slug.
 *
 * <p>Before this existed the site had no pages between the home page and the 32,519
 * narration pages: nothing could rank for a book name, and every narration was an
 * island reachable only from the XML sitemap, so no internal link equity reached any
 * of them. The catalog is what {@code /books}, {@code /books/{book}} and
 * {@code /books/{book}/{chapter}} are built from, and what puts those chapters in the
 * sitemap.
 *
 * <p>Held in memory because it is small — roughly 7,700 chapters across 18 books, a
 * couple of megabytes — and read on nearly every page render. One composite
 * aggregation builds the whole thing; the alternative, aggregating per request, put an
 * Elasticsearch round trip in front of every crawler hit.
 */
@Component
public class BookCatalog {

    private static final Logger LOGGER = LoggerFactory.getLogger(BookCatalog.class);
    private static final Duration CACHE_TTL = Duration.ofHours(6);
    private static final int COMPOSITE_PAGE_SIZE = 1000;

    /** Path segments the book routes claim, which a chapter slug must not collide with. */
    private static final Set<String> RESERVED_SEGMENTS = Set.of("volume", "part");

    /** A book, with the chapters that sit under it in reading order. */
    public record Book(String name, String slug, long count, List<Chapter> chapters) {

        /** Volumes present in this book, in reading order, or empty when it has none. */
        public List<String> volumes() {
            return chapters.stream()
                    .map(Chapter::volume)
                    .filter(v -> v != null && !v.isBlank())
                    .distinct()
                    .sorted(Comparator.comparing(BookCatalog::sortKey))
                    .toList();
        }

        public List<Chapter> chaptersInVolume(String volume) {
            return chapters.stream()
                    .filter(c -> volume == null ? c.volume() == null || c.volume().isBlank()
                                                : volume.equals(c.volume()))
                    .toList();
        }

        /**
         * The parts of this book, in reading order.
         *
         * <p>Thirteen of the eighteen books use parts, and for some the part IS the
         * organising principle — Al-Khisal is arranged as "On One-Numbered
         * Characteristics" through "On Twelve-Numbered". Listing its 908 chapters flat
         * under a volume threw that structure away and produced a 1.35MB page.
         */
        public List<Part> parts() {
            List<Part> out = new ArrayList<>();
            Map<String, Integer> used = new HashMap<>();
            for (Chapter chapter : chapters) {
                String title = chapter.part();
                if (title == null || title.isBlank()) {
                    continue;
                }
                String key = chapter.volume() + "\u0000" + title;
                if (used.containsKey(key)) {
                    continue;
                }
                used.put(key, 1);
                out.add(new Part(name, slug, partSlug(out, title), title, chapter.volume(),
                        chaptersInPart(chapter.volume(), title).size()));
            }
            return out;
        }

        public List<Part> partsInVolume(String volume) {
            return parts().stream()
                    .filter(p -> volume == null || volume.isBlank()
                            ? p.volume() == null || p.volume().isBlank()
                            : volume.equals(p.volume()))
                    .toList();
        }

        public List<Chapter> chaptersInPart(String volume, String partTitle) {
            return chapters.stream()
                    .filter(c -> sameFacet(c.volume(), volume) && sameFacet(c.part(), partTitle))
                    .toList();
        }

        /** Unique within a book: the same part title can recur across volumes. */
        private static String partSlug(List<Part> existing, String title) {
            String base = slugify(title);
            if (base.isBlank()) {
                base = "part";
            }
            String candidate = base;
            int n = 1;
            while (hasSlug(existing, candidate)) {
                candidate = base + "-" + (++n);
            }
            return candidate;
        }

        private static boolean hasSlug(List<Part> existing, String slug) {
            return existing.stream().anyMatch(p -> p.slug().equals(slug));
        }
    }

    /** One part of one book, between the volume and the chapters. */
    public record Part(String bookName, String bookSlug, String slug, String title,
                       String volume, int chapterCount) {

        public String url() {
            return "/books/" + bookSlug + "/part/" + slug;
        }
    }

    /**
     * One chapter of one book.
     *
     * <p>{@code volume}, {@code part} and {@code section} are carried because a chapter
     * title alone does not identify a chapter — the same title recurs across volumes —
     * and the narration query for a chapter page has to filter on all of them.
     */
    public record Chapter(String bookName, String bookSlug, String slug, String title,
                          String volume, String part, String section, long count) {

        public String url() {
            return "/books/" + bookSlug + "/" + slug;
        }

        /** Straight into the app's reading mode, scoped to this chapter. */
        public String readingUrl() {
            return readingModeUrl(bookName, volume, part, section, title);
        }
    }

    /**
     * The URL of the app's existing reading mode, scoped to a book, volume or chapter.
     *
     * <p>The reading experience is already built: the search interface enters it on a
     * scoped query with no keyword terms. These pages link into it rather than growing a
     * second one. Mirrors buildQueryFromFilters and buildSortFields in rewayaat.js — if
     * the query grammar there changes, this has to follow.
     */
    public static String readingModeUrl(String book, String volume, String part, String section, String chapter) {
        StringBuilder query = new StringBuilder();
        appendScope(query, "book", book);
        appendScope(query, "volume", volume);
        appendScope(query, "part", part);
        appendScope(query, "section", section);
        appendScope(query, "chapter", chapter);

        List<String> sort = new ArrayList<>();
        if (notBlank(volume)) { sort.add("volume:asc"); }
        if (notBlank(part)) { sort.add("part:asc"); }
        if (notBlank(section)) { sort.add("section:asc"); }
        if (notBlank(chapter)) { sort.add("chapter:asc"); }
        if (sort.isEmpty()) {
            sort.addAll(List.of("volume:asc", "part:asc", "section:asc", "chapter:asc"));
        }
        sort.add("number:asc");

        return "/?q=" + urlEncode(query.toString())
                + "&page=1"
                + "&sort_fields=" + urlEncode(String.join(",", sort))
                + "&mode=read"
                + "&match_mode=flexible"
                + "&entry=browse";
    }

    /** Quotes are the query grammar's own delimiter, so a value cannot carry them. */
    private static void appendScope(StringBuilder query, String field, String value) {
        if (!notBlank(value)) {
            return;
        }
        if (query.length() > 0) {
            query.append(' ');
        }
        query.append(field).append(":\"").append(value.replace("\"", "").trim()).append('"');
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private volatile List<Book> books = List.of();
    private volatile Map<String, Book> booksBySlug = Map.of();
    private volatile Map<String, Chapter> chaptersBySlug = Map.of();
    private volatile Instant loadedAt = Instant.EPOCH;

    public List<Book> books() {
        ensureLoaded();
        return books;
    }

    public Optional<Book> book(String slug) {
        ensureLoaded();
        return Optional.ofNullable(booksBySlug.get(slug));
    }

    public Optional<Part> part(String bookSlug, String partSlug) {
        return book(bookSlug).flatMap(b -> b.parts().stream()
                .filter(p -> p.slug().equals(partSlug))
                .findFirst());
    }

    public Optional<Chapter> chapter(String bookSlug, String chapterSlug) {
        ensureLoaded();
        return Optional.ofNullable(chaptersBySlug.get(bookSlug + "/" + chapterSlug));
    }

    /**
     * The chapters either side of this one, in reading order within its book.
     *
     * <p>Reading a book straight through meant going up to the volume and back down for
     * every chapter; these are the links that were missing, and they thread the hub
     * pages together for a crawler as well as a reader.
     */
    public Optional<Chapter> siblingChapter(Chapter chapter, int offset) {
        return book(chapter.bookSlug()).flatMap(b -> {
            List<Chapter> all = b.chapters();
            int i = -1;
            for (int n = 0; n < all.size(); n++) {
                if (all.get(n).slug().equals(chapter.slug())) {
                    i = n;
                    break;
                }
            }
            int target = i + offset;
            return i < 0 || target < 0 || target >= all.size()
                    ? Optional.<Chapter>empty() : Optional.of(all.get(target));
        });
    }

    /** Every chapter in the corpus, for the sitemap. */
    public List<Chapter> allChapters() {
        ensureLoaded();
        return books.stream().flatMap(b -> b.chapters().stream()).toList();
    }

    /** The slug a narration's own book is reachable at, so a hadith page can link up. */
    public Optional<Book> bookByName(String name) {
        ensureLoaded();
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return books.stream().filter(b -> name.equals(b.name())).findFirst();
    }

    /** The chapter a narration belongs to, matched on the same tuple the catalog is keyed by. */
    public Optional<Chapter> chapterFor(String book, String volume, String part, String section, String chapter) {
        if (chapter == null || chapter.isBlank()) {
            return Optional.empty();
        }
        return bookByName(book).flatMap(b -> b.chapters().stream()
                .filter(c -> chapter.equals(c.title())
                        && sameFacet(volume, c.volume())
                        && sameFacet(part, c.part())
                        && sameFacet(section, c.section()))
                .findFirst());
    }

    private static boolean sameFacet(String a, String b) {
        String left = a == null ? "" : a.trim();
        String right = b == null ? "" : b.trim();
        return left.equals(right);
    }

    private void ensureLoaded() {
        if (!books.isEmpty() && Duration.between(loadedAt, Instant.now()).compareTo(CACHE_TTL) < 0) {
            return;
        }
        synchronized (this) {
            if (!books.isEmpty() && Duration.between(loadedAt, Instant.now()).compareTo(CACHE_TTL) < 0) {
                return;
            }
            try {
                load();
            } catch (Exception e) {
                // Serving a stale or empty catalog beats a 500 on every page. An empty
                // catalog makes the hub pages 404, which is at least an honest answer.
                LOGGER.error("Could not build the book catalog; keeping the previous one", e);
            }
        }
    }

    private void load() throws IOException {
        long startedAt = System.currentTimeMillis();
        List<CompositeBucket> buckets = scanChapterBuckets();

        Map<String, List<Chapter>> byBook = new LinkedHashMap<>();
        Map<String, Long> bookCounts = new LinkedHashMap<>();
        // Slugs are unique per book, not globally: two books may both have a "Chapter on
        // Prayer". Within a book a repeated title gets a numeric suffix, assigned in the
        // composite aggregation's sort order so the URL stays stable across rebuilds.
        Map<String, Integer> slugUses = new HashMap<>();

        for (CompositeBucket bucket : buckets) {
            Map<String, FieldValue> key = bucket.key();
            String bookName = text(key.get("book"));
            if (bookName.isBlank()) {
                continue;
            }
            String bookSlug = slugify(bookName);
            String title = text(key.get("chapter"));
            long count = bucket.docCount();
            bookCounts.merge(bookSlug, count, Long::sum);

            if (title.isBlank()) {
                // Narrations with no chapter still count towards the book, but there is
                // no chapter page for them; the book page links them via its own listing.
                continue;
            }

            String base = slugify(title);
            if (base.isBlank()) {
                continue;
            }
            if (RESERVED_SEGMENTS.contains(base)) {
                // /books/{book}/volume/{n} is a route; a chapter that slugged to
                // "volume" would be unreachable behind it.
                base = base + "-chapter";
            }
            String slugKey = bookSlug + "/" + base;
            int used = slugUses.merge(slugKey, 1, Integer::sum);
            String slug = used == 1 ? base : base + "-" + used;

            byBook.computeIfAbsent(bookSlug, k -> new ArrayList<>())
                    .add(new Chapter(bookName, bookSlug, slug, title,
                            text(key.get("volume")), text(key.get("part")), text(key.get("section")), count));
        }

        List<Book> loaded = new ArrayList<>();
        Map<String, Book> bySlug = new LinkedHashMap<>();
        Map<String, Chapter> chapterIndex = new LinkedHashMap<>();

        for (Map.Entry<String, List<Chapter>> entry : byBook.entrySet()) {
            String bookSlug = entry.getKey();
            List<Chapter> chapters = List.copyOf(entry.getValue());
            String bookName = chapters.isEmpty() ? bookSlug : chapters.get(0).bookName();
            Book book = new Book(bookName, bookSlug, bookCounts.getOrDefault(bookSlug, 0L), chapters);
            loaded.add(book);
            bySlug.put(bookSlug, book);
            for (Chapter chapter : chapters) {
                chapterIndex.put(bookSlug + "/" + chapter.slug(), chapter);
            }
        }
        loaded.sort(Comparator.comparingLong(Book::count).reversed());

        this.books = List.copyOf(loaded);
        this.booksBySlug = Map.copyOf(bySlug);
        this.chaptersBySlug = Map.copyOf(chapterIndex);
        this.loadedAt = Instant.now();

        LOGGER.info("Book catalog built: {} books, {} chapters in {}ms",
                books.size(), chaptersBySlug.size(), System.currentTimeMillis() - startedAt);
    }

    /**
     * Walks every distinct (book, volume, part, section, chapter) tuple.
     *
     * <p>A composite aggregation is the only terms aggregation that pages, and there are
     * far more tuples than the 10,000-bucket ceiling a plain terms aggregation allows.
     */
    private List<CompositeBucket> scanChapterBuckets() throws IOException {
        List<CompositeBucket> all = new ArrayList<>();
        try (ESClientProvider provider = new ESClientProvider()) {
            ElasticsearchClient client = provider.client();
            Map<String, FieldValue> after = null;

            while (true) {
                final Map<String, FieldValue> cursor = after;
                SearchResponse<Void> response = client.search(s -> s
                        .index(ESClientProvider.INDEX)
                        .size(0)
                        .trackTotalHits(t -> t.enabled(false))
                        .aggregations("chapters", a -> a.composite(c -> {
                            c.size(COMPOSITE_PAGE_SIZE).sources(compositeSources());
                            if (cursor != null) {
                                c.after(cursor);
                            }
                            return c;
                        })), Void.class);

                CompositeAggregate aggregate = response.aggregations().get("chapters").composite();
                List<CompositeBucket> page = aggregate.buckets().array();
                if (page.isEmpty()) {
                    break;
                }
                all.addAll(page);
                after = aggregate.afterKey();
                if (after == null || after.isEmpty()) {
                    break;
                }
            }
        }
        return all;
    }

    /**
     * {@code chapter} is a text field with a keyword sub-field; the others are keywords
     * already. Aggregating on the analysed field would bucket one chapter per word.
     */
    private static List<NamedValue<CompositeAggregationSource>> compositeSources() {
        return List.of(
                source("book", "book"),
                source("volume", "volume"),
                source("part", "part"),
                source("section", "section"),
                source("chapter", "chapter.keyword"));
    }

    private static NamedValue<CompositeAggregationSource> source(String name, String field) {
        return NamedValue.of(name, CompositeAggregationSource.of(s -> s
                .terms(t -> t.field(field).missingBucket(true))));
    }

    private static String text(FieldValue value) {
        if (value == null || value.isNull()) {
            return "";
        }
        return value.stringValue() == null ? "" : value.stringValue().trim();
    }

    /**
     * A URL-safe slug.
     *
     * <p>Book and chapter titles are transliterated Arabic full of combining marks and
     * dots below — "Man Lā Yaḥḍuruh al-Faqīh", "ʿUyūn akhbār al-Riḍā". Stripping the
     * diacritics before slugifying gives the plain-ASCII spelling people actually type
     * and link to, so the URL matches the query.
     */
    public static String slugify(String input) {
        if (input == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(input, Normalizer.Form.NFD);
        StringBuilder ascii = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            int type = Character.getType(c);
            if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK) {
                continue;
            }
            ascii.append(c);
        }
        String slug = ascii.toString()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        // Chapter titles run long; a slug past this adds nothing a crawler or a reader uses.
        return slug.length() > 80 ? slug.substring(0, slug.lastIndexOf('-', 80) < 20 ? 80 : slug.lastIndexOf('-', 80)) : slug;
    }

    /** Sorts "Volume 10" after "Volume 9" rather than between 1 and 2. */
    private static String sortKey(String value) {
        StringBuilder out = new StringBuilder();
        for (String token : value.split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)")) {
            if (!token.isEmpty() && Character.isDigit(token.charAt(0))) {
                out.append(String.format("%08d", Long.parseLong(token.replaceAll("\\D", "0"))));
            } else {
                out.append(token);
            }
        }
        return out.toString();
    }

    /** Builds the catalog off the request path, the way the sitemap cache is warmed. */
    @EventListener(ApplicationReadyEvent.class)
    public void warmOnStartup() {
        Thread warmer = new Thread(this::ensureLoaded, "book-catalog-warmer");
        warmer.setDaemon(true);
        warmer.start();
    }
}
