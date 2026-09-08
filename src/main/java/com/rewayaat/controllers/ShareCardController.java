package com.rewayaat.controllers;

import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.rewayaat.config.ESClientProvider;
import com.rewayaat.service.BookCatalog;
import com.rewayaat.service.HadithCardFactory;
import com.rewayaat.service.ShareCardRenderer;
import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

/**
 * The per-narration share image that {@code og:image} and {@code twitter:image} point at.
 *
 * <p>Before this, all 32,519 narration pages shared one generic logo card, so every
 * WhatsApp forward and every tweet of a <em>different</em> narration previewed the same
 * picture. The same asset is what makes the Friday newsletter linkable: email clients
 * strip scripts and stylesheets, but an image inside a link renders everywhere.
 *
 * <p>Nothing is pre-generated. 32,519 PNGs is a lot of storage for images most of which
 * are never requested, so a card is drawn on the first request for it and then kept in
 * memory.
 */
@Hidden
@Controller
public class ShareCardController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShareCardController.class);
    private static final String BASE_URL = HomeController.BASE_URL;

    /** Drawn opposite the ALI mark, so a screenshotted card still says where it came from. */
    private static final String DOMAIN = BASE_URL.replaceFirst("^https?://", "");

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * The list marker many narrations open with — "1. " in English, "1ـ " in Arabic.
     * The citation in the eyebrow already carries the number, and leaving it in front of
     * the matn wastes the first characters of the line on it.
     */
    private static final Pattern LEADING_ORDINAL =
            Pattern.compile("^[\\d\\u0660-\\u0669]+\\s*[.\\-\\u2013\\u2014\\u0640:)\\]]\\s*");

    private final ShareCardRenderer renderer;
    private final HadithCardFactory cards;
    private final BookCatalog catalog;
    private final BookBlurbs blurbs = new BookBlurbs();
    private final CardCache cache = new CardCache();

    public ShareCardController(ShareCardRenderer renderer, HadithCardFactory cards,
                               BookCatalog catalog) {
        this.renderer = renderer;
        this.cards = cards;
        this.catalog = catalog;
    }

    /**
     * @param theme {@code light} for the cream card the newsletter wants, absent for the
     *              navy default. {@code og:image} deliberately stays on the default: navy
     *              is more striking in a feed, and existing links must not change.
     */
    @GetMapping(value = "/hadith/{id}/card.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> narrationCard(@PathVariable("id") String id,
                                                @RequestParam(value = "theme",
                                                        required = false) String theme,
                                                @RequestParam(value = "lang",
                                                        required = false) String lang,
                                                @RequestParam(value = "full",
                                                        required = false) String full,
                                                @RequestParam(value = "chain",
                                                        required = false) String chain,
                                                @RequestHeader(value = "If-None-Match",
                                                        required = false) String ifNoneMatch) {
        Map<String, Object> source = narration(id);
        if (source.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // The card renders the same matn/isnad split the page does, through the same
        // factory. A card that opened with "A number of our people have narrated from
        // Ahmad ibn Muhammad..." would spend the whole image on boilerplate that is
        // identical across thousands of narrations.
        Map<String, Object> card = cards.build(id, source, null, BASE_URL);
        boolean withChain = isTrue(chain);

        return respond(new ShareCardRenderer.Card(narrationEyebrow(source),
                        withChain(clean(str(card.get("arabicChain"))),
                                clean(str(card.get("arabic"))), withChain),
                        withChain(clean(str(card.get("englishChain"))),
                                clean(str(card.get("english"))), withChain),
                        DOMAIN),
                theme(theme), options(lang, full), ifNoneMatch);
    }

    /**
     * Puts the isnād back in front of the matn when the sharer asked for it.
     *
     * <p>Off by default, and that is the important half: a card is 1200x630, and an isnād
     * runs to a line of names that is near-identical across thousands of narrations, so
     * including it by default would spend most of the image on the part a reader skims.
     * But a chain is the evidence for a narration, and someone sharing one to argue about
     * its transmission needs it visible - hence a choice rather than a rule.
     *
     * <p>Nothing else has to change for the cache to follow: respond() hashes the card's
     * own text, so a card with the chain hashes differently and gets its own ETag and its
     * own entry, without chain becoming part of the key by hand.
     */
    private static String withChain(String chainText, String matn, boolean include) {
        if (!include || chainText == null || chainText.isBlank()) {
            return matn;
        }
        if (matn == null || matn.isBlank()) {
            return chainText;
        }
        return chainText.trim() + " " + matn.trim();
    }

    /** Query flags arrive as "true", "1" or "on" depending on who wrote the link. */
    private static boolean isTrue(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        return v.equals("true") || v.equals("1") || v.equals("on") || v.equals("yes");
    }

    /**
     * The share dialog's two other choices. Anything unrecognised falls back to the
     * defaults rather than erroring, because these arrive from links people have edited
     * by hand as often as from the dialog.
     */
    private static ShareCardRenderer.Options options(String lang, String full) {
        ShareCardRenderer.Language language = ShareCardRenderer.Language.BOTH;
        if (lang != null) {
            String wanted = lang.trim().toLowerCase(java.util.Locale.ROOT);
            if (wanted.equals("ar") || wanted.equals("arabic")) {
                language = ShareCardRenderer.Language.ARABIC;
            } else if (wanted.equals("en") || wanted.equals("english")) {
                language = ShareCardRenderer.Language.ENGLISH;
            }
        }
        boolean whole = full != null
                && (full.isBlank() || "true".equalsIgnoreCase(full.trim())
                    || "1".equals(full.trim()) || "yes".equalsIgnoreCase(full.trim()));
        return new ShareCardRenderer.Options(language, whole);
    }

    /** Anything but an explicit {@code light} is the dark card, including a typo. */
    private static ShareCardRenderer.Theme theme(String requested) {
        return "light".equalsIgnoreCase(requested)
                ? ShareCardRenderer.Theme.LIGHT : ShareCardRenderer.Theme.DARK;
    }

    /**
     * A book-level card, so a shared hub page previews as something other than the logo.
     *
     * <p>The literal {@code card.png} beats {@code /books/{bookSlug}/{chapterSlug}} in
     * Spring's pattern comparator, so this does not shadow a chapter page.
     */
    @GetMapping(value = "/books/{bookSlug}/card.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> bookCard(@PathVariable("bookSlug") String bookSlug,
                                           @RequestParam(value = "theme",
                                                   required = false) String theme,
                                           @RequestHeader(value = "If-None-Match",
                                                   required = false) String ifNoneMatch) {
        Optional<BookCatalog.Book> found = catalog.book(bookSlug);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        BookCatalog.Book book = found.get();

        // The blurb opens with an <h2> repeating the title the eyebrow already carries.
        String blurb = blurbs.forSlug(bookSlug);
        String body = blurb == null ? "" : clean(blurb.replaceFirst("(?is)^.*?</h2>", ""));

        String eyebrow = String.format(Locale.ROOT, "%s · %,d narrations", book.name(), book.count());
        return respond(new ShareCardRenderer.Card(
                eyebrow.toUpperCase(Locale.ROOT), arabicTitle(book.name()), body, DOMAIN),
                theme(theme), ifNoneMatch);
    }

    /**
     * Cards for the three levels between a book and a narration.
     *
     * <p>Every one of the 8,035 volume, part and chapter pages previewed as the same site
     * mark before this, which made a shared chapter link indistinguishable from a shared
     * anything-else link. Each now opens with the first narration it contains: the eyebrow
     * says which page it is, and the body shows a reader what is actually in there. The
     * alternative — a list of chapter titles — needs a layout the renderer does not have,
     * and reads as a table of contents rather than as hadith.
     *
     * <p>The literal {@code card.png} outranks {@code /books/&#123;bookSlug&#125;/&#123;chapterSlug&#125;}
     * in Spring's pattern comparator, so none of these shadow a page.
     */
    @GetMapping(value = "/books/{bookSlug}/volume/{volume}/card.png",
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> volumeCard(@PathVariable("bookSlug") String bookSlug,
                                             @PathVariable("volume") String volume,
                                             @RequestParam(value = "theme",
                                                     required = false) String theme,
                                             @RequestParam(value = "lang",
                                                     required = false) String lang,
                                             @RequestParam(value = "full",
                                                     required = false) String full,
                                             @RequestHeader(value = "If-None-Match",
                                                     required = false) String ifNoneMatch) {
        Optional<BookCatalog.Book> found = catalog.book(bookSlug);
        if (found.isEmpty() || !found.get().volumes().contains(volume)) {
            return ResponseEntity.notFound().build();
        }
        BookCatalog.Book book = found.get();
        long count = book.chaptersInVolume(volume).stream()
                .mapToLong(BookCatalog.Chapter::count).sum();
        String eyebrow = String.format(Locale.ROOT, "%s · Volume %s · %,d narrations",
                book.name(), volume, count);

        // A volume page is an outline, so the card is one too. Parts where the volume has
        // them, chapters where it does not, which is the same split the page itself makes.
        List<BookCatalog.Part> parts = book.partsInVolume(volume);
        List<String> entries = parts.isEmpty()
                ? book.chaptersInVolume(volume).stream().map(BookCatalog.Chapter::title).toList()
                : parts.stream().map(BookCatalog.Part::title).toList();
        return outlineCard(eyebrow, book.name(), entries, theme, lang, full, ifNoneMatch);
    }

    @GetMapping(value = "/books/{bookSlug}/part/{partSlug}/card.png",
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> partCard(@PathVariable("bookSlug") String bookSlug,
                                           @PathVariable("partSlug") String partSlug,
                                           @RequestParam(value = "theme",
                                                   required = false) String theme,
                                           @RequestParam(value = "lang",
                                                   required = false) String lang,
                                           @RequestParam(value = "full",
                                                   required = false) String full,
                                           @RequestHeader(value = "If-None-Match",
                                                   required = false) String ifNoneMatch) {
        Optional<BookCatalog.Part> found = catalog.part(bookSlug, partSlug);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        BookCatalog.Part part = found.get();
        String eyebrow = String.format(Locale.ROOT, "%s · %s · %,d chapters",
                part.bookName(), part.title(), part.chapterCount());
        Optional<BookCatalog.Book> book = catalog.book(bookSlug);
        List<String> entries = book.isEmpty() ? List.of()
                : book.get().chaptersInPart(part.volume(), part.title()).stream()
                        .map(BookCatalog.Chapter::title).toList();
        return outlineCard(eyebrow, part.bookName(), entries, theme, lang, full, ifNoneMatch);
    }

    @GetMapping(value = "/books/{bookSlug}/{chapterSlug}/card.png",
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> chapterCard(@PathVariable("bookSlug") String bookSlug,
                                              @PathVariable("chapterSlug") String chapterSlug,
                                              @RequestParam(value = "theme",
                                                      required = false) String theme,
                                              @RequestParam(value = "lang",
                                                      required = false) String lang,
                                              @RequestParam(value = "full",
                                                      required = false) String full,
                                              @RequestHeader(value = "If-None-Match",
                                                      required = false) String ifNoneMatch) {
        Optional<BookCatalog.Chapter> found = catalog.chapter(bookSlug, chapterSlug);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        BookCatalog.Chapter chapter = found.get();
        String eyebrow = String.format(Locale.ROOT, "%s · %s · %,d narrations",
                chapter.bookName(), chapter.title(), chapter.count());

        // Blank facets are carried rather than dropped: the chapter page treats "no
        // volume" as a distinction in its own right, and omitting it here would match
        // every volume and open the card with a narration from a different chapter.
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("book", chapter.bookName());
        filters.put("chapter", chapter.title());
        filters.put("volume", chapter.volume() == null ? "" : chapter.volume());
        filters.put("part", chapter.part() == null ? "" : chapter.part());
        filters.put("section", chapter.section() == null ? "" : chapter.section());
        return openingCard(eyebrow, filters, theme, lang, full, ifNoneMatch);
    }

    /**
     * A card for a page that is a list rather than a text: the book's name in Arabic over
     * what the page contains.
     *
     * <p>The titles run together separated by a middle dot rather than stacking as a real
     * list, because the renderer lays out two blocks of prose and a list layout would be a
     * third thing to keep working. Run together they still read as an outline, and the
     * count in the eyebrow says how much of it is showing.
     */
    private ResponseEntity<byte[]> outlineCard(String eyebrow, String bookName,
                                               List<String> entries, String theme, String lang,
                                               String full, String ifNoneMatch) {
        String outline = entries.stream()
                .map(ShareCardController::clean)
                .filter(entry -> !entry.isBlank())
                .collect(Collectors.joining("  ·  "));
        return respond(new ShareCardRenderer.Card(eyebrow.toUpperCase(Locale.ROOT),
                        arabicTitle(bookName), outline, DOMAIN),
                theme(theme), options(lang, full), ifNoneMatch);
    }

    /**
     * Draws a card headed by {@code eyebrow} and bodied by the lowest-numbered narration
     * matching {@code filters}. An empty result still gets a card: the eyebrow alone says
     * which page it is, which beats falling back to the site mark.
     */
    private ResponseEntity<byte[]> openingCard(String eyebrow, Map<String, String> filters,
                                               String theme, String lang, String full,
                                               String ifNoneMatch) {
        Map<String, Object> source = openingNarration(filters);
        String arabic = "";
        String english = "";
        if (!source.isEmpty()) {
            Map<String, Object> card = cards.build("", source, null, BASE_URL);
            arabic = clean(str(card.get("arabic")));
            english = clean(str(card.get("english")));
        }
        return respond(new ShareCardRenderer.Card(
                        eyebrow.toUpperCase(Locale.ROOT), arabic, english, DOMAIN),
                theme(theme), options(lang, full), ifNoneMatch);
    }

    /**
     * The opening narration of a chapter, part or volume.
     *
     * <p>Sorted in memory rather than by Elasticsearch because {@code number} is a string
     * field in this index — "10" sorts before "9" lexically — and the page listings use
     * the same numeric comparison to order themselves. A card that opened with a different
     * narration from the one at the top of the page would be a small lie.
     */
    private Map<String, Object> openingNarration(Map<String, String> filters) {
        try (ESClientProvider provider = new ESClientProvider()) {
            SearchResponse<Map> response = provider.client().search(s -> s
                    .index(ESClientProvider.INDEX)
                    .size(OPENING_CANDIDATES)
                    .trackTotalHits(t -> t.enabled(false))
                    .source(src -> src.filter(f -> f.includes(
                            "book", "number", "english", "arabic", "volume", "part",
                            "section", "chapter")))
                    .query(q -> q.bool(b -> {
                        filters.forEach((field, value) -> {
                            if (value == null || value.isBlank()) {
                                b.mustNot(m -> m.exists(e -> e.field(field)));
                                return;
                            }
                            b.filter(f -> f.term(t -> t
                                    .field(TEXT_FIELDS.contains(field) ? field + ".keyword" : field)
                                    .value(value)));
                        });
                        return b;
                    })), Map.class);

            Map<String, Object> best = null;
            String bestNumber = null;
            for (Hit<Map> hit : response.hits().hits()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> source = hit.source();
                if (source == null) {
                    continue;
                }
                String number = str(source.get("number"));
                if (best == null || compareNumbers(number, bestNumber) < 0) {
                    best = source;
                    bestNumber = number;
                }
            }
            return best == null ? Map.of() : best;
        } catch (Exception e) {
            LOGGER.debug("Could not read the opening narration for {}", filters, e);
            return Map.of();
        }
    }

    /** Numeric where both sides are numbers, so "9" precedes "10". */
    private static int compareNumbers(String a, String b) {
        if (b == null) {
            return -1;
        }
        try {
            return Long.compare(Long.parseLong(a.trim()), Long.parseLong(b.trim()));
        } catch (RuntimeException e) {
            return String.valueOf(a).compareTo(b);
        }
    }

    // ── Response ────────────────────────────────────────────────────────────

    /**
     * Serves the card, drawing it only if it is not already in memory.
     *
     * <p>The ETag is a hash of the card's own text, theme, language and completeness, and
     * it is also the in-memory cache key. That is what makes an edit to a narration
     * invalidate the render: changed text hashes differently, so it misses the cache and no
     * longer matches a stored ETag.
     *
     * <p>What that does <em>not</em> do on its own is reach caches outside this process.
     * The URL is stable across an edit - {@code /hadith/{id}/card.png} plus the theme,
     * language and completeness parameters, eight variants of one narration - so a browser
     * or CDN holding the old PNG will keep serving it until it revalidates. This response
     * was previously marked {@code immutable} with a year's {@code max-age}, which tells a
     * client in as many words never to revalidate: the ETag was then unreachable, and an
     * edited narration would have shown its old card for a year with no way to force the
     * issue short of changing the URL.
     *
     * <p>{@code immutable} is a promise that the bytes behind a URL will never change, and
     * it is only safe when the URL carries a content hash. This one does not, so the header
     * says what is true instead: cache briefly, then revalidate. The ETag makes that
     * revalidation cheap - a 304 with no body - and {@code stale-while-revalidate} lets a
     * shared cache serve the old card while it fetches the new one, so correcting the
     * header costs latency nowhere.
     */
    /** Says whether the default card cut this narration short. Read by the share dialog. */
    private static final String TRIMMED_HEADER = "X-Card-Trimmed";

    /**
     * How long a card may be served without asking us again.
     *
     * <p>Short, because the URL does not change when a narration is edited, and long enough
     * that a page embedding several cards does not revalidate each one on every view.
     */
    private static final Duration CARD_MAX_AGE = Duration.ofMinutes(10);

    /**
     * How long a shared cache may serve a stale card while it fetches a fresh one.
     *
     * <p>This is what keeps the correction free: a CDN answers immediately from what it
     * has and refreshes behind the request, so an edit propagates within minutes without
     * anyone waiting on a render.
     */
    private static final Duration CARD_STALE_WHILE_REVALIDATE = Duration.ofDays(1);

    /** Enough to find the lowest-numbered narration without paging a whole volume. */
    private static final int OPENING_CANDIDATES = 60;

    /**
     * The one analysed field among the facets, so the only one matched on a keyword
     * sub-field. {@code part} and {@code section} are plain keywords and have none;
     * appending .keyword to them matches nothing at all, silently.
     */
    private static final Set<String> TEXT_FIELDS = Set.of("chapter");

    private ResponseEntity<byte[]> respond(ShareCardRenderer.Card card,
                                           ShareCardRenderer.Theme theme, String ifNoneMatch) {
        return respond(card, theme, ShareCardRenderer.Options.DEFAULT, ifNoneMatch);
    }

    private ResponseEntity<byte[]> respond(ShareCardRenderer.Card card,
                                           ShareCardRenderer.Theme theme,
                                           ShareCardRenderer.Options options, String ifNoneMatch) {
        // Theme, language and completeness are part of the key as well as the text: they
        // are different images behind one URL, and a shared ETag would serve one for
        // another.
        String hash = hash(theme + " " + options.language() + " " + options.full() + " "
                + card.eyebrow() + " " + card.arabic() + " " + card.english());
        String etag = "\"" + hash + "\"";
        CacheControl caching = CacheControl.maxAge(CARD_MAX_AGE).cachePublic()
                .staleWhileRevalidate(CARD_STALE_WHILE_REVALIDATE);

        // A conditional request may quote the tag weakly ("W/..."), and a client is
        // allowed to send several.
        if (ifNoneMatch != null && ifNoneMatch.contains(hash)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(caching)
                    .header(TRIMMED_HEADER, Boolean.toString(renderer.truncates(card, options)))
                    .build();
        }

        byte[] png = cache.get(hash);
        if (png == null) {
            long started = System.nanoTime();
            png = renderer.render(card, theme, options);
            cache.put(hash, png);
            LOGGER.debug("Drew share card {} in {} ms ({} bytes)", hash,
                    (System.nanoTime() - started) / 1_000_000, png.length);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .eTag(etag)
                .cacheControl(caching)
                // Lets the share dialog drop the trimmed/full choice when this narration
                // fits either way, rather than offering a control that changes nothing.
                .header(TRIMMED_HEADER, Boolean.toString(renderer.truncates(card, options)))
                .body(png);
    }

    // ── Content ─────────────────────────────────────────────────────────────

    /** "AL-KĀFI · VOLUME 2 · HADITH 81" — the citation, so a screenshot stays attributable. */
    /**
     * The citation across the top of a narration card.
     *
     * <p>Carries the part and the chapter as well as the book and volume, because a card
     * shared without them says which collection a narration is from but not what it is
     * about - and the chapter title is usually the best one-line answer to that. The
     * renderer drops segments from the middle when the line is too long for the card, so
     * a sixty-character Al-Kāfi chapter title is safe to include here.
     */
    private static String narrationEyebrow(Map<String, Object> source) {
        StringBuilder eyebrow = new StringBuilder(str(source.get("book")));
        appendCitationPart(eyebrow, "Volume ", str(source.get("volume")));
        appendCitationPart(eyebrow, "", str(source.get("part")));
        appendCitationPart(eyebrow, "", str(source.get("chapter")));
        appendCitationPart(eyebrow, "Hadith ", str(source.get("number")));
        return eyebrow.toString().trim().toUpperCase(Locale.ROOT);
    }

    private static void appendCitationPart(StringBuilder eyebrow, String prefix, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        eyebrow.append(" · ").append(prefix).append(value.trim());
    }

    private Map<String, Object> narration(String id) {
        String narrationId = id == null ? "" : id.trim();
        if (narrationId.isEmpty()) {
            return Map.of();
        }
        try (ESClientProvider provider = new ESClientProvider()) {
            var response = provider.client().get(g -> g
                    .index(ESClientProvider.INDEX)
                    .id(narrationId)
                    .sourceIncludes("arabic", "english", "book", "volume", "part", "chapter",
                            "number", "topic_tags"),
                    Map.class);
            if (!response.found() || response.source() == null) {
                return Map.of();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> source = new LinkedHashMap<>(response.source());
            return source;
        } catch (Exception e) {
            LOGGER.error("Could not load narration {} for its share card", narrationId, e);
            return Map.of();
        }
    }

    /**
     * The book's Arabic title, taken from any one of its narrations.
     *
     * <p>{@code book_ar} is a per-document field rather than something the catalogue
     * aggregates, so this reads one document to get it. A book with no Arabic title
     * simply renders as a single-language card.
     */
    private String arabicTitle(String bookName) {
        try (ESClientProvider provider = new ESClientProvider()) {
            SearchResponse<Map> response = provider.client().search(s -> s
                    .index(ESClientProvider.INDEX)
                    .size(1)
                    .source(src -> src.filter(f -> f.includes("book_ar")))
                    .query(q -> q.term(t -> t.field("book").value(bookName))), Map.class);
            for (Hit<Map> hit : response.hits().hits()) {
                Object title = hit.source() == null ? null : hit.source().get("book_ar");
                if (title != null) {
                    return title.toString();
                }
            }
            return "";
        } catch (Exception e) {
            LOGGER.warn("Could not read the Arabic title for {}", bookName, e);
            return "";
        }
    }

    /** Markup, the list marker and runs of whitespace all have to go before anything is drawn. */
    private static String clean(String text) {
        if (text == null) {
            return "";
        }
        String stripped = WHITESPACE.matcher(HTML_TAG.matcher(text).replaceAll(" ")).replaceAll(" ").trim();
        return LEADING_ORDINAL.matcher(stripped).replaceFirst("").trim();
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 22);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    /**
     * The drawn cards, newest-used first, bounded by total bytes rather than by count.
     *
     * <p>Drawing takes tens of milliseconds and crawlers revisit the same handful of
     * narrations, so the second request for one costs nothing. The bound is on bytes
     * because a card's size varies by a factor of two with how much text is on it, and
     * the pod has a 2Gi limit to stay well inside.
     */
    private static final class CardCache {

        private static final long MAX_BYTES = 48L * 1024 * 1024;

        private final LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>(64, 0.75f, true);
        private long bytes;

        synchronized byte[] get(String key) {
            return entries.get(key);
        }

        synchronized void put(String key, byte[] png) {
            byte[] previous = entries.put(key, png);
            bytes += png.length - (previous == null ? 0 : previous.length);
            Iterator<Map.Entry<String, byte[]>> oldest = entries.entrySet().iterator();
            while (bytes > MAX_BYTES && oldest.hasNext()) {
                bytes -= oldest.next().getValue().length;
                oldest.remove();
            }
        }
    }
}
