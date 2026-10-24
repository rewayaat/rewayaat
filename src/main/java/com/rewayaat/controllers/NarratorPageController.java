package com.rewayaat.controllers;

import com.rewayaat.service.ProminentNarrators;
import com.rewayaat.service.ArabicNames;
import com.rewayaat.service.HadithCardFactory;
import com.rewayaat.service.LocaleDigits;
import com.rewayaat.service.NarratorArabic;
import com.rewayaat.service.PageLocale;
import com.rewayaat.service.StructuredData;
import com.rewayaat.service.QuranicInsightsService;
import com.rewayaat.service.TopicLabelSource;
import com.rewayaat.service.NarratorService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The narrator page.
 *
 * It publishes no judgment of its own: it shows what each Rijal book says of one man, under
 * that book's name and page, in the book's own words. Where the books disagree — and about most
 * men they do — the disagreement is what the page leads with.
 */
@Controller
public class NarratorPageController {

    private static final String BASE_URL = HomeController.BASE_URL;
    /** How many of the heaviest-carrying men the page offers when nothing is typed. */
    private static final int BROWSE = 60;

    /** Enough names to be useful to a crawler; the page itself lists them all. */
    private static final int MAX_ALTERNATE_NAMES = 12;

    private static final int PER_PAGE = 20;
    /** A compact row is a sixth the height of a card, so a page of them can be longer. */
    private static final int COMPACT_PER_PAGE = 50;

    @Autowired
    private NarratorService narratorService;

    @Autowired
    private HadithCardFactory cards;

    @Autowired
    private QuranicInsightsService quranicInsights;

    /** The Arabic of a topic slug, the same source the cards' own pills read. */
    @Autowired
    private TopicLabelSource topicLabels;

    @Autowired
    private ProminentNarrators prominent;

    @Autowired
    private MessageSource messages;

    /**
     * The way in: a name, and the men the books head under it.
     *
     * Most searches for a narrator are searches for a name several men carry, so the list
     * shows how many entries each holds — the measure of how much the books actually say
     * about him — rather than pretending the first hit is the man meant.
     */
    @GetMapping("/narrators")
    public String narratorSearch(@RequestParam(value = "q", defaultValue = "") String query,
                                 @RequestParam(value = "page", defaultValue = "1") int page,
                                 Model model, HttpServletRequest request) {
        PageLocale locale = PageLocale.of(request);
        String text = query == null ? "" : query.trim();
        Map<String, Object> found = text.isEmpty() ? Map.of("total", 0, "narrators", List.of())
                : narratorService.search(text, page, PER_PAGE);
        long total = ((Number) found.getOrDefault("total", 0)).longValue();

        model.addAttribute("query", text);
        // Landing here with nothing typed used to show a search box and an empty page, which
        // tells a reader who does not already have a name in mind nothing at all. The books
        // page lists its books; this lists the men these books lean on hardest, by the
        // chains they stand in, so the page can be browsed as well as searched. Same cards
        // the home page uses, so the two read as one site.
        List<Map<String, Object>> browse = text.isEmpty() ? prominent.top(BROWSE, locale)
                : List.of();
        if (text.isEmpty()) {
            model.addAttribute("prominentNarrators", browse);
        }
        model.addAttribute("results", found.get("narrators"));
        model.addAttribute("total", total);
        model.addAttribute("page", Math.max(page, 1));
        model.addAttribute("hasMore", (long) Math.max(page, 1) * PER_PAGE < total);
        // Every link a reader clicks has to keep him in the language he is reading.
        String hub = locale.prefix() + "/narrators";
        model.addAttribute("nextUrl", hub + "?q=" + encode(text) + "&page=" + (Math.max(page, 1) + 1));
        model.addAttribute("prevUrl", hub + "?q=" + encode(text) + "&page=" + (Math.max(page, 1) - 1));
        model.addAttribute("searchAction", hub);
        model.addAttribute("seoTitle", text.isEmpty() ? msg(locale, "seo.narrators.title")
                : msg(locale, "seo.narrators.titleNamed", text));
        model.addAttribute("seoDescription", msg(locale, "seo.narrators.description"));
        // The canonical and the hreflang pair are built together, from the one path both
        // languages share - see PageLocale. Setting canonicalUrl by hand here is what made
        // every Arabic page name the English one as the real one.
        locale.applyTo(model, "/narrators");
        List<Map<String, String>> crumbs = List.of(
                Map.of("name", msg(locale, "crumb.home"), "url", locale.prefix() + "/"),
                Map.of("name", msg(locale, "crumb.narrators"), "url", hub));
        model.addAttribute("breadcrumbs", crumbs);
        model.addAttribute("breadcrumbJsonLd", StructuredData.breadcrumbs(crumbs));
        // A typed name, or a page past the first, is a slice of this one — the canonical
        // already says so, and this says it in the other way a crawler reads. 15,518 men
        // against every name anyone might type is an unbounded set of pages that each say
        // what /narrators says. Same rule the home page's own search follows.
        model.addAttribute("robotsDirective",
                text.isEmpty() && Math.max(page, 1) == 1 ? null : "noindex, follow");
        model.addAttribute("jsonLd", browse.isEmpty()
                ? hubJsonLd(locale, hub) : hubJsonLd(locale, hub, browse));
        return "narrators";
    }

    /** The hub with nothing to list on it: a search page, or a page past the first. */
    private String hubJsonLd(PageLocale locale, String hub) {
        Map<String, Object> ld = new LinkedHashMap<>();
        ld.put("@context", "https://schema.org");
        ld.put("@type", "CollectionPage");
        ld.put("name", msg(locale, "seo.narrators.title"));
        ld.put("description", msg(locale, "seo.narrators.description"));
        ld.put("url", BASE_URL + hub);
        return StructuredData.write(ld);
    }

    /**
     * The hub as a reader first meets it, with the men it offers him named.
     *
     * <p>The browse list is the site's own answer to «who carries this corpus» — the men
     * ranked by the chains that name them — and it is the only route down to 15,518 narrator
     * pages that a crawler can follow without typing a name into a box.
     */
    private String hubJsonLd(PageLocale locale, String hub, List<Map<String, Object>> browse) {
        List<Map<String, Object>> items = new ArrayList<>();
        int position = 1;
        for (Map<String, Object> man : browse) {
            String id = text(man.get("person_id"));
            if (id.isEmpty()) {
                continue;
            }
            String slug = text(man.get("slug"));
            String name = text(man.get(locale.isArabic() ? "display_ar" : "display_en"));
            items.add(new LinkedHashMap<>(Map.of(
                    "@type", "ListItem",
                    "position", position++,
                    "name", name.isEmpty() ? text(man.get("display_ar")) : name,
                    "url", BASE_URL + locale.prefix() + "/narrator/" + id
                            + (slug.isEmpty() ? "" : "/" + slug))));
        }
        Map<String, Object> ld = new LinkedHashMap<>();
        ld.put("@context", "https://schema.org");
        ld.put("@type", "ItemList");
        ld.put("name", msg(locale, "seo.narrators.title"));
        ld.put("description", msg(locale, "seo.narrators.description"));
        ld.put("url", BASE_URL + hub);
        ld.put("numberOfItems", items.size());
        ld.put("itemListElement", items);
        return StructuredData.write(ld);
    }

    private String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    /**
     * A narrator's page, at «/narrator/n000515/sahl-ibn-ziyad».
     *
     * The permanent id stays the authority — it is what survives two people becoming one, and
     * what every recorded decision keys on — so the name in the URL is the readable half and
     * nothing resolves by it. A bare «/narrator/n000515», or one carrying a name he no longer
     * goes by, is moved permanently to the canonical form rather than serving the page twice.
     */
    @GetMapping({"/narrator/{id}", "/narrator/{id}/{slug}"})
    public Object narratorPage(@PathVariable("id") String id,
                               @PathVariable(value = "slug", required = false) String slug,
                               @RequestParam(value = "tab", defaultValue = "overview") String tab,
                               @RequestParam(value = "tag", required = false) String tag,
                               @RequestParam(value = "book", required = false) String book,
                               @RequestParam(value = "page", defaultValue = "1") int page,
                               @RequestParam(value = "layout", required = false) String layout,
                               Model model, HttpServletRequest request,
                               HttpServletResponse response) throws IOException {
        PageLocale locale = PageLocale.of(request);
        Map<String, Object> narrator = narratorService.getNarrator(id);
        if (narrator == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return null;
        }
        // One of the fourteen, filed under a narrator's identifier because a rijālī catalogued
        // him among the companions he was assessing. The page he deserves is the roster's, and
        // the link that brought the reader here goes on working.
        String movedTo = text(narrator.get("moved_to"));
        if (!movedTo.isEmpty() && !movedTo.equals(id)) {
            Map<String, Object> roster = narratorService.getNarrator(movedTo);
            if (roster != null) {
                String rosterSlug = text(roster.get("slug"));
                RedirectView moved = new RedirectView(locale.prefix() + "/narrator/" + movedTo
                        + (rosterSlug.isEmpty() ? "" : "/" + rosterSlug));
                moved.setStatusCode(HttpStatus.MOVED_PERMANENTLY);
                return moved;
            }
        }
        String canonicalSlug = text(narrator.get("slug"));
        if (!canonicalSlug.isEmpty() && !canonicalSlug.equals(slug)) {
            String query = request.getQueryString();
            // 301, not 302: the canonical address of a narrator does not change from request to
            // request, and a link that has been shared should end up pointing at the right one.
            // It keeps the prefix: the forward that selects Arabic has already stripped it from
            // the URI, so a redirect built without it drops an Arabic reader onto English.
            RedirectView moved = new RedirectView(locale.prefix() + "/narrator/" + id + "/"
                    + canonicalSlug + (query == null || query.isBlank() ? "" : "?" + query));
            moved.setStatusCode(HttpStatus.MOVED_PERMANENTLY);
            return moved;
        }

        // The Arabic twin of every field whose value is words, moved into the field the
        // template reads - see NarratorArabic. Numbers stay numbers.
        narrator = NarratorArabic.localise(narrator, locale);

        // The path both languages share, for the canonical and the alternates; and the same
        // path prefixed, for every link on the page.
        String path = "/narrator/" + id + (canonicalSlug.isEmpty() ? "" : "/" + canonicalSlug);
        String base = locale.prefix() + path;
        String arabic = text(narrator.get("display_ar"));
        String english = text(narrator.get("display_en"));

        // A reader who knows the books better than this index does needs somewhere to say
        // so, and a rijāl page is where he will notice: a wrong verdict, a man fused with
        // his son, a city the entry does not support. The mail carries the page and the
        // permanent id so a report can be traced back to the record behind it.
        model.addAttribute("reportHref", reportHref(id, arabic, english, path));
        String title = !english.isEmpty() ? english : arabic;

        model.addAttribute("narrator", narrator);
        model.addAttribute("narratorId", id);
        // The seal's ring is how much of the corpus runs through him, and the grid cards draw
        // it from the chain index. The page used the narration total instead, so the same man's
        // seal was one size on the card he was tapped from and another on the page it opened:
        // Ibrāhīm b. Hāshim stands in 4,056 chains and 548 narrations name him. One mark, one
        // measure - this is the figure the card uses.
        // Counted once: the seal draws its ring from it, and the Narrations tab and
        // its badge are now listed and counted by it too.
        long chainsThrough = prominent.chainsOf(id);
        model.addAttribute("chainsThrough", chainsThrough);
        model.addAttribute("displayAr", arabic);
        model.addAttribute("displayEn", english);
        model.addAttribute("books", booksNamed(narrator));
        // The Arabic page leads with the Arabic name, which is the name the books print; the
        // English one leads with the transliteration, which is what an English reader can type.
        String heading = locale.isArabic() && !arabic.isEmpty() ? arabic : title;
        model.addAttribute("seoTitle", msg(locale, "seo.narrator.title", heading));
        model.addAttribute("seoDescription", summary(locale, heading, narrator));
        locale.applyTo(model, path);
        List<Map<String, String>> crumbs = List.of(
                Map.of("name", msg(locale, "crumb.home"), "url", locale.prefix() + "/"),
                Map.of("name", msg(locale, "crumb.narrators"),
                        "url", locale.prefix() + "/narrators"),
                Map.of("name", heading, "url", base));
        model.addAttribute("breadcrumbs", crumbs);
        model.addAttribute("breadcrumbJsonLd", StructuredData.breadcrumbs(crumbs));
        model.addAttribute("jsonLd", personJsonLd(narrator, heading, base,
                summary(locale, heading, narrator)));

        // The narrations that name him, as the same card the rest of the site renders. They
        // are found by his name, not by a resolved chain, so the section says so plainly.
        //
        // Searched by the names a book headed an entry with, never by every name written
        // inside one. Khoei's entry on Zurara carries «محمد بن مسلم» among its names, and
        // the chain search obligingly put a thousand of Muhammad b. Muslim's narrations on
        // Zurara's page. Headings cost nothing in reach: the short forms the chains actually
        // use are headings too. Older documents have no headings_ar and fall back.
        @SuppressWarnings("unchecked")
        List<String> vetted = (List<String>) narrator.getOrDefault("search_ar", List.of());
        @SuppressWarnings("unchecked")
        List<String> headings = (List<String>) narrator.getOrDefault("headings_ar", List.of());
        @SuppressWarnings("unchecked")
        List<String> names = !vetted.isEmpty() ? vetted
                : !headings.isEmpty() ? headings
                : (List<String>) narrator.getOrDefault("names_ar", List.of());
        // «Also named» counted 79 for al-Barqi: 35 Arabic headings plus 44 Latin
        // renderings of those same headings, with six of the Arabic differing from
        // another only by the shadda an edition prints on «اللّه». He is not headed
        // under 79 names. The count is of distinct Arabic headings — 29 for him — and
        // the Latin is shown as the reading of those names, not added to them.
        @SuppressWarnings("unchecked")
        List<String> alsoAr = distinctNames(
                (List<String>) narrator.getOrDefault("names_ar", List.of()));
        @SuppressWarnings("unchecked")
        List<String> alsoEn = distinctNames(
                (List<String>) narrator.getOrDefault("names_en", List.of()));
        model.addAttribute("alsoNamesAr", alsoAr);
        model.addAttribute("alsoNamesEn", alsoEn);
        model.addAttribute("alsoNameTotal", alsoAr.size());

        // Two tabs, because the page had become a scroll: what the books say of him, and the
        // narrations he stands in. The tab is in the URL, so each is a page that can be sent to
        // someone, and the narrations are fetched only when they are the tab being read.
        // One tab set over the whole page. Everything used to sit in a column — the standing,
        // the entries, the names, the narrations — so a reader scrolled past three sections to
        // reach the one he came for, and the counters above led nowhere. Each tab is a URL, and
        // each figure in the ledger is a link into the tab that explains it.
        // «?tab=names» was a tab of its own until its chips moved into the overview as «Also
        // named». The address has been shared and bookmarked, so it goes to the section that
        // replaced it, permanently, instead of answering 404 or quietly showing the overview
        // with the reader unsure whether he has the page he asked for.
        if ("names".equals(tab)) {
            RedirectView renamed = new RedirectView(base + "#also-named");
            renamed.setStatusCode(HttpStatus.MOVED_PERMANENTLY);
            return renamed;
        }
        String view = switch (tab == null ? "" : tab) {
            case "books", "chains", "narrations" -> tab;
            default -> "overview";
        };
        // The card by default, and «?layout=index» for the compact list.
        //
        // It was the other way round for a day, because a thousand cards at 570px each read
        // as a landing page rather than an index. But the card is what carries the rijāl
        // panel — the chain drawn link by link, which is the point of this whole system —
        // and a compact row has no sidecar to put it in. Losing it on the one tab where a
        // reader is looking at a man's own narrations was too much to pay for the scrolling.
        // The index is still a click away and still in the URL, so a link shows the reader
        // what the sender saw.
        boolean cardLayout = !"index".equals(layout);
        int perPage = cardLayout ? PER_PAGE : COMPACT_PER_PAGE;
        Map<String, Object> naming = "narrations".equals(view)
                // The chain index when it knows him — exact, complete, and the same figure
                // his seal draws — and his name forms only when it does not.
                ? narratorService.narrationsNaming(names, chainsThrough > 0 ? id : null,
                                                   tag, book, page, perPage)
                : summaryOf(names, chainsThrough > 0 ? id : null);
        List<Map<String, Object>> narrations = new ArrayList<>();
        for (Object row : (List<?>) naming.getOrDefault("hits", List.of())) {
            Map<?, ?> hit = (Map<?, ?>) row;
            @SuppressWarnings("unchecked")
            Map<String, Object> source = (Map<String, Object>) hit.get("source");
            // With the locale. The four-argument build is English by definition, so an
            // Arabic narrator page was listing every one of his narrations with English book
            // names, English chapters and English metadata under an Arabic heading.
            // `path`, not `base`: the card puts the locale prefix on a topic link itself,
            // and handing it one that already carries it produced «/ar/ar/narrator/…».
            Map<String, Object> card = cards.build(String.valueOf(hit.get("id")), source, path,
                                                   BASE_URL, locale);
            card.putAll(compactFields(card, locale));
            narrations.add(card);
        }
        // the card shows a Quranic-insight count, so it has to be fetched as the other pages do
        Map<String, Integer> insightCounts = quranicInsights.insightCounts(
                narrations.stream().map(card -> String.valueOf(card.get("id"))).toList());
        narrations.forEach(card -> card.put("quranCount",
                insightCounts.getOrDefault(String.valueOf(card.get("id")), 0)));
        long narrationTotal = ((Number) naming.getOrDefault("total", 0)).longValue();
        String narrationsUrl = base + "?tab=narrations";
        // Every link inside the tab keeps the presentation the reader chose.
        String narrationsBase = narrationsUrl + (cardLayout ? "" : "&layout=index");
        String withTag = tag == null || tag.isBlank() ? "" : "&tag=" + encode(tag);
        String withBook = book == null || book.isBlank() ? "" : "&book=" + encode(book);

        model.addAttribute("narrations", narrations);
        model.addAttribute("narrationTotal", narrationTotal);
        model.addAttribute("searchedNames", naming.get("searched_for"));
        model.addAttribute("narrationBooks", booksRead(locale, naming.get("books")));
        String filters = (tag == null || tag.isBlank() ? "" : "&tag=" + encode(tag))
                + (book == null || book.isBlank() ? "" : "&book=" + encode(book));
        model.addAttribute("view", view);
        model.addAttribute("overviewUrl", base);
        model.addAttribute("booksUrl", base + "?tab=books");
        model.addAttribute("chainsUrl", base + "?tab=chains");
        model.addAttribute("narrationsUrl", narrationsUrl);
        model.addAttribute("cardLayout", cardLayout);
        // From the cards the toggle offers the index, and from the index the cards.
        model.addAttribute("layoutToggleUrl", cardLayout ? narrationsUrl + "&layout=index"
                + (tag == null || tag.isBlank() ? "" : "&tag=" + encode(tag))
                + (book == null || book.isBlank() ? "" : "&book=" + encode(book))
                : narrationsUrl
                + (tag == null || tag.isBlank() ? "" : "&tag=" + encode(tag))
                + (book == null || book.isBlank() ? "" : "&book=" + encode(book)));
        model.addAttribute("activeBook", book == null || book.isBlank() ? null : book);
        model.addAttribute("clearBookUrl", narrationsBase
                + (tag == null || tag.isBlank() ? "" : "&tag=" + encode(tag)));
        model.addAttribute("bookFacets",
                bookFacets(locale, naming.get("books"), narrationsBase, tag, book));
        model.addAttribute("tagFacets",
                tagFacets(locale, naming.get("tags"), narrationsBase, tag, book));
        model.addAttribute("activeTag", tag == null || tag.isBlank() ? null : tag);
        model.addAttribute("clearTagUrl", narrationsBase);
        model.addAttribute("narrationsPage", page);
        model.addAttribute("narrationsNextUrl",
                narrationsBase + withTag + withBook + "&page=" + (page + 1));
        model.addAttribute("narrationsPrevUrl",
                narrationsBase + withTag + withBook + "&page=" + (page - 1));
        model.addAttribute("narrationsHasMore", (long) page * perPage < narrationTotal);
        return "narrator";
    }

    /**
     * What one row of the compact narrations list prints: the isnād, the opening of the text
     * and the citation. The opening is cut at a word, because a scholar scanning forty rows
     * for one he half remembers reads the first words and a cut inside a word reads as a
     * broken page; and it is read in the page's language with the other as the fallback,
     * since a narration with no English text still has to show something.
     */
    private Map<String, Object> compactFields(Map<String, Object> card, PageLocale locale) {
        boolean arabic = locale.isArabic();
        String chainPrimary = text(card.get(arabic ? "arabicChain" : "englishChain"));
        // The Arabic page never falls back to English: narration 605 has no Arabic isnād
        // segment, and the fallback printed «Hadith.605 - Zurarah ibn A'yan narrated…» in
        // Latin under an Arabic heading. A row without a chain is better than a wrong script.
        String chainFallback = arabic ? "" : text(card.get("arabicChain"));
        boolean chainInArabic = arabic || chainPrimary.isBlank();
        String matnPrimary = plain(text(card.get(arabic ? "arabic" : "english")));
        String matnFallback = arabic ? "" : plain(text(card.get("arabic")));
        boolean matnInArabic = arabic || matnPrimary.isBlank();
        String matn = matnPrimary.isBlank() ? matnFallback : matnPrimary;
        if (matn.length() > COMPACT_MATN) {
            int cut = matn.lastIndexOf(' ', COMPACT_MATN);
            matn = matn.substring(0, cut > COMPACT_MATN / 2 ? cut : COMPACT_MATN) + "\u2026";
        }
        String volume = "";
        if (card.get("metadata") instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> item
                        && String.valueOf(item.get("icon")).contains("layer-group")) {
                    volume = text(item.get("value"));
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("compactChain", chainPrimary.isBlank() ? chainFallback : chainPrimary);
        out.put("compactChainArabic", chainInArabic);
        out.put("compactMatn", matn);
        out.put("compactMatnArabic", matnInArabic);
        out.put("compactVolume", volume);
        return out;
    }

    private static final int COMPACT_MATN = 120;

    /** Text with its markup and its runs of whitespace taken out. */
    private static String plain(String html) {
        return org.springframework.web.util.HtmlUtils.htmlUnescape(html.replaceAll("<[^>]*>", " "))
                .replace('\u00a0', ' ')
                .replaceAll("\\s+", " ").trim();
    }

    /** The narrations tab's count, and where those narrations sit, for the counters. */
    private Map<String, Object> summaryOf(List<String> names, String personId) {
        Map<String, Object> summary = narratorService.narrationSummary(names, personId);
        Map<String, Object> out = new LinkedHashMap<>(summary);
        out.put("hits", List.of());
        out.put("tags", List.of());
        out.put("searched_for", List.of());
        return out;
    }

    /** The tag pills: each a link that filters these narrations, keeping the book filter. */
    private List<Map<String, Object>> tagFacets(PageLocale locale, Object buckets, String base,
                                                String active, String book) {
        String keep = book == null || book.isBlank() ? "" : "&book=" + encode(book);
        List<Map<String, Object>> facets = new ArrayList<>();
        for (Object row : buckets instanceof List<?> list ? list : List.of()) {
            Map<?, ?> bucket = (Map<?, ?>) row;
            // The chip is read; the filter is queried. They are the same topic under two
            // names, exactly as the book facets below handle it — and this one was printing
            // the slug, so an Arabic page offered «marriage ١١٧» beside its Arabic pills.
            String slug = String.valueOf(bucket.get("label"));
            facets.add(Map.of("label", topicLabels.label(slug, locale),
                    "count", bucket.get("count"),
                    "url", base + keep + "&tag=" + encode(slug),
                    "active", slug.equals(active)));
        }
        return facets;
    }

    /** The same, for the books his narrations sit in: «93% in al-Kafi» made clickable. */
    private List<Map<String, Object>> bookFacets(PageLocale locale, Object buckets, String base,
                                                 String tag, String active) {
        String keep = tag == null || tag.isBlank() ? "" : "&tag=" + encode(tag);
        List<Map<String, Object>> facets = new ArrayList<>();
        for (Object row : buckets instanceof List<?> list ? list : List.of()) {
            Map<?, ?> bucket = (Map<?, ?>) row;
            // The pill is read; the filter is queried. They are the same book under two
            // names, and sending the Arabic one to Elasticsearch matches nothing.
            String english = String.valueOf(bucket.get("book"));
            facets.add(Map.of("label", bookNamed(locale, english),
                    "count", bucket.get("count"),
                    "share", bucket.get("share") == null ? 0 : bucket.get("share"),
                    "url", base + keep + "&book=" + encode(english),
                    "active", english.equals(active)));
        }
        return facets;
    }

    /** A hadith book as the page names it, falling back to the English where we have no Arabic. */
    private static String bookNamed(PageLocale locale, String english) {
        if (!locale.isArabic()) {
            return english;
        }
        String arabic = ArabicNames.book(english);
        return arabic == null || arabic.isBlank() ? english : arabic;
    }

    /** The same books for the ledger's «28% in al-Kafi», which is read and never queried. */
    private static List<Map<String, Object>> booksRead(PageLocale locale, Object buckets) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object row : buckets instanceof List<?> list ? list : List.of()) {
            Map<?, ?> bucket = (Map<?, ?>) row;
            Map<String, Object> named = new LinkedHashMap<>();
            bucket.forEach((key, value) -> named.put(String.valueOf(key), value));
            named.put("book", bookNamed(locale, String.valueOf(bucket.get("book"))));
            out.add(named);
        }
        return out;
    }

    /** The books that head this man, as they are named on the page. */
    private List<String> booksNamed(Map<String, Object> narrator) {
        List<String> named = new ArrayList<>();
        Object entries = narrator.get("entries");
        if (entries instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> record) {
                    String title = text(record.get("book_title"));
                    if (!title.isEmpty() && !named.contains(title)) {
                        named.add(title);
                    }
                }
            }
        }
        return named;
    }

    /**
     * The meta description: which books are quoted, and about whom.
     *
     * <p>The books are named from the document itself, which has already been read in this
     * locale, so the Arabic description names them in Arabic. Where no book heads him the
     * phrase falls back to naming the shelf rather than leaving a gap mid-sentence.
     */
    /**
     * The man himself, for a crawler: a {@code Person} and the names the books call him by.
     *
     * <p>No {@code deathDate}. The books give a year of the Hijra and schema.org wants a
     * Gregorian one, and converting it would put a date on the page that no rijālī wrote —
     * the one thing these pages are built not to do. The year stands in the description in
     * the form the book states it.
     */
    private String personJsonLd(Map<String, Object> narrator, String heading, String path,
                                String fallbackDescription) {
        Map<String, Object> ld = new LinkedHashMap<>();
        ld.put("@context", "https://schema.org");
        ld.put("@type", "Person");
        ld.put("name", heading);

        // The names a reader might search him by: his other-language name, the kunyas the
        // books give him, and the names those books head their entries with. The heading
        // itself is not one of his alternates.
        List<String> also = new ArrayList<>();
        for (Object name : alternateNames(narrator)) {
            String text = text(name).trim();
            if (!text.isEmpty() && !text.equals(heading) && !also.contains(text)) {
                also.add(text);
            }
        }
        if (!also.isEmpty()) {
            ld.put("alternateName", also.size() > MAX_ALTERNATE_NAMES
                    ? also.subList(0, MAX_ALTERNATE_NAMES) : also);
        }

        // The blurb is what the entry actually says of him; the SEO sentence names the books
        // and is the fallback for the men who have no blurb.
        String blurb = text(narrator.get("blurb")).trim();
        ld.put("description", blurb.isEmpty() ? fallbackDescription : blurb);
        ld.put("url", BASE_URL + path);
        ld.put("mainEntityOfPage", BASE_URL + path);
        return StructuredData.write(ld);
    }

    /** His other-language name, his kunyas, and the names the books head entries with. */
    private List<Object> alternateNames(Map<String, Object> narrator) {
        List<Object> names = new ArrayList<>();
        names.add(narrator.get("display_ar"));
        names.add(narrator.get("display_en"));
        for (String field : List.of("kunyahs_ar", "headings_ar")) {
            if (narrator.get(field) instanceof List<?> list) {
                names.addAll(list);
            }
        }
        return names;
    }

    private String summary(PageLocale locale, String title, Map<String, Object> narrator) {
        List<String> named = booksNamed(narrator);
        // Arabic separates a list with \u060c, not with a comma.
        String separator = locale.isArabic() ? "\u060c " : ", ";
        String where = named.isEmpty() ? msg(locale, "seo.narrator.descriptionBooks")
                : String.join(separator, named);
        return msg(locale, "seo.narrator.description", where, title);
    }

    private String msg(PageLocale locale, String key, Object... args) {
        return messages.getMessage(key, args, locale.locale());
    }

    private String text(Object value) {
        return value == null ? "" : value.toString();
    }

    /**
     * A link to the contact form, prefilled with this man and this page.
     *
     * <p>The corrections this page will attract are not typos: they are «this verdict is
     * al-Khoei quoting al-Najashi, not his own», «these are two men», «the entry says Kufa
     * and the page says Qum». Each of those is answerable only against the record behind
     * the page, so the form carries the permanent id and the address, and asks for the
     * book and page the reader is reading from — a rijali correction without a citation
     * cannot be acted on.
     */
    private static String reportHref(String id, String arabicName, String englishName,
                                     String path) {
        String named = !englishName.isEmpty() ? englishName : arabicName;
        return "/contact?about=narrator&id=" + forMail(id)
                + "&label=" + forMail(named.isEmpty() ? id : named)
                + "&url=" + forMail(PageLocale.BASE_URL + path);
    }

    /** As {@link #encode} but with real spaces: «+» reads as a plus in a mail body. */
    private static String forMail(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Arabic diacritics, and the letter forms an edition chooses between. */
    private static final java.util.regex.Pattern MARKS =
            java.util.regex.Pattern.compile("[\\u064B-\\u0652\\u0670\\u0640]");

    /**
     * The same heading written twice is one name.
     *
     * <p>Editions differ over the shadda on «اللّه», over «أ» and «ا», over «ة» and «ه»,
     * and over where they put a space. None of those is another name a book heads him
     * under, and counting them as such told a reader al-Barqī answered to 79.
     * Order is kept: the first spelling of each name is the one shown.
     */
    private static List<String> distinctNames(List<String> names) {
        List<String> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String folded = MARKS.matcher(name).replaceAll("")
                    .replaceAll("[\\u0622\\u0623\\u0625\\u0671]", "\\u0627")
                    .replace('\u0649', '\u064a')
                    .replace('\u0629', '\u0647')
                    .replaceAll("\\s+", " ")
                    .trim()
                    .toLowerCase(java.util.Locale.ROOT);
            if (seen.add(folded)) {
                out.add(name.trim());
            }
        }
        return out;
    }

}
