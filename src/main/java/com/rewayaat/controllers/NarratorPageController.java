package com.rewayaat.controllers;

import com.rewayaat.service.ArabicNames;
import com.rewayaat.service.HadithCardFactory;
import com.rewayaat.service.LocaleDigits;
import com.rewayaat.service.NarratorArabic;
import com.rewayaat.service.PageLocale;
import com.rewayaat.service.QuranicInsightsService;
import com.rewayaat.service.Chronology;
import com.rewayaat.service.NarratorSeal;
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
    private static final int PER_PAGE = 20;

    @Autowired
    private NarratorService narratorService;

    @Autowired
    private Chronology chronology;

    @Autowired
    private HadithCardFactory cards;

    @Autowired
    private QuranicInsightsService quranicInsights;

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
        model.addAttribute("breadcrumbs", List.of(
                Map.of("name", msg(locale, "crumb.home"), "url", locale.prefix() + "/"),
                Map.of("name", msg(locale, "crumb.narrators"), "url", hub)));
        return "narrators";
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
                               Model model, HttpServletRequest request,
                               HttpServletResponse response) throws IOException {
        PageLocale locale = PageLocale.of(request);
        Map<String, Object> narrator = narratorService.getNarrator(id);
        if (narrator == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return null;
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

        // Where he stands against the Prophet and the Imams. The rijal books say "a
        // companion of al-Sadiq" and leave the reader to know what that means; the band
        // says it in a picture. Both inputs are routinely absent and the band simply does
        // not render then.
        Chronology.Placement placement = chronology.place(ordinals(narrator),
                statedDeathYears(narrator));
        model.addAttribute("placement", placement);
        // The death year reaches the caption as text, so it is converted here rather than
        // appearing as 150 in the one Arabic sentence that quotes it.
        model.addAttribute("deathAh", LocaleDigits.in(locale, placement.deathAh()));

        // A name in a roundel, never a face - see NarratorSeal.
        model.addAttribute("seal", NarratorSeal.monogram(arabicNames(narrator), arabic));
        model.addAttribute("sealVariant", NarratorSeal.variant(text(narrator.get("person_id"))));
        String title = !english.isEmpty() ? english : arabic;

        model.addAttribute("narrator", narrator);
        model.addAttribute("narratorId", id);
        model.addAttribute("displayAr", arabic);
        model.addAttribute("displayEn", english);
        model.addAttribute("books", booksNamed(narrator));
        // The Arabic page leads with the Arabic name, which is the name the books print; the
        // English one leads with the transliteration, which is what an English reader can type.
        String heading = locale.isArabic() && !arabic.isEmpty() ? arabic : title;
        model.addAttribute("seoTitle", msg(locale, "seo.narrator.title", heading));
        model.addAttribute("seoDescription", summary(locale, heading, narrator));
        locale.applyTo(model, path);
        model.addAttribute("breadcrumbs", List.of(
                Map.of("name", msg(locale, "crumb.home"), "url", locale.prefix() + "/"),
                Map.of("name", msg(locale, "crumb.narrators"),
                        "url", locale.prefix() + "/narrators"),
                Map.of("name", heading, "url", base)));

        // The narrations that name him, as the same card the rest of the site renders. They
        // are found by his name, not by a resolved chain, so the section says so plainly.
        @SuppressWarnings("unchecked")
        List<String> names = (List<String>) narrator.getOrDefault("names_ar", List.of());
        // Two tabs, because the page had become a scroll: what the books say of him, and the
        // narrations he stands in. The tab is in the URL, so each is a page that can be sent to
        // someone, and the narrations are fetched only when they are the tab being read.
        // One tab set over the whole page. Everything used to sit in a column — the standing,
        // the entries, the names, the narrations — so a reader scrolled past three sections to
        // reach the one he came for, and the counters above led nowhere. Each tab is a URL, and
        // each figure in the ledger is a link into the tab that explains it.
        String view = switch (tab == null ? "" : tab) {
            case "books", "chains", "narrations", "names" -> tab;
            default -> "overview";
        };
        Map<String, Object> naming = "narrations".equals(view)
                ? narratorService.narrationsNaming(names, tag, book, page, PER_PAGE)
                : summaryOf(names);
        List<Map<String, Object>> narrations = new ArrayList<>();
        for (Object row : (List<?>) naming.getOrDefault("hits", List.of())) {
            Map<?, ?> hit = (Map<?, ?>) row;
            @SuppressWarnings("unchecked")
            Map<String, Object> source = (Map<String, Object>) hit.get("source");
            narrations.add(cards.build(String.valueOf(hit.get("id")), source, base, BASE_URL));
        }
        // the card shows a Quranic-insight count, so it has to be fetched as the other pages do
        Map<String, Integer> insightCounts = quranicInsights.insightCounts(
                narrations.stream().map(card -> String.valueOf(card.get("id"))).toList());
        narrations.forEach(card -> card.put("quranCount",
                insightCounts.getOrDefault(String.valueOf(card.get("id")), 0)));
        long narrationTotal = ((Number) naming.getOrDefault("total", 0)).longValue();
        String narrationsBase = base + "?tab=narrations";
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
        model.addAttribute("namesUrl", base + "?tab=names");
        model.addAttribute("narrationsUrl", narrationsBase);
        model.addAttribute("activeBook", book == null || book.isBlank() ? null : book);
        model.addAttribute("clearBookUrl", narrationsBase
                + (tag == null || tag.isBlank() ? "" : "&tag=" + encode(tag)));
        model.addAttribute("bookFacets",
                bookFacets(locale, naming.get("books"), narrationsBase, tag, book));
        model.addAttribute("tagFacets", tagFacets(naming.get("tags"), narrationsBase, tag, book));
        model.addAttribute("activeTag", tag == null || tag.isBlank() ? null : tag);
        model.addAttribute("clearTagUrl", narrationsBase);
        model.addAttribute("narrationsPage", page);
        model.addAttribute("narrationsNextUrl",
                narrationsBase + withTag + withBook + "&page=" + (page + 1));
        model.addAttribute("narrationsPrevUrl",
                narrationsBase + withTag + withBook + "&page=" + (page - 1));
        model.addAttribute("narrationsHasMore", (long) page * PER_PAGE < narrationTotal);
        return "narrator";
    }

    /** The narrations tab's count, and where those narrations sit, for the counters. */
    private Map<String, Object> summaryOf(List<String> names) {
        Map<String, Object> summary = narratorService.narrationSummary(names);
        Map<String, Object> out = new LinkedHashMap<>(summary);
        out.put("hits", List.of());
        out.put("tags", List.of());
        out.put("searched_for", List.of());
        return out;
    }

    /** The tag pills: each a link that filters these narrations, keeping the book filter. */
    private List<Map<String, Object>> tagFacets(Object buckets, String base, String active,
                                                String book) {
        String keep = book == null || book.isBlank() ? "" : "&book=" + encode(book);
        List<Map<String, Object>> facets = new ArrayList<>();
        for (Object row : buckets instanceof List<?> list ? list : List.of()) {
            Map<?, ?> bucket = (Map<?, ?>) row;
            String label = String.valueOf(bucket.get("label"));
            facets.add(Map.of("label", label,
                    "count", bucket.get("count"),
                    "url", base + keep + "&tag=" + encode(label),
                    "active", label.equals(active)));
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

    /** {@code imam_ordinals}: 0 for the Prophet, 1-12 for the Imams. Often absent. */
    @SuppressWarnings("unchecked")
    private static List<Integer> ordinals(Map<String, Object> narrator) {
        Object raw = narrator.get("imam_ordinals");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Number number) {
                out.add(number.intValue());
            }
        }
        return out;
    }

    /**
     * Every death year any book states. The books disagree often enough that this is a
     * list rather than a value, and the page says so when it holds more than one.
     */
    @SuppressWarnings("unchecked")
    private static List<Integer> statedDeathYears(Map<String, Object> narrator) {
        Object raw = narrator.get("death_years");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> entry && entry.get("value") instanceof Number number) {
                out.add(number.intValue());
            }
        }
        return out;
    }

    /** The Arabic names the books record for him, shortest of which becomes the seal. */
    @SuppressWarnings("unchecked")
    private static List<String> arabicNames(Map<String, Object> narrator) {
        Object raw = narrator.get("names_ar");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String name) {
                out.add(name);
            }
        }
        return out;
    }
}
