package com.rewayaat.controllers;

import com.rewayaat.service.HadithCardFactory;
import com.rewayaat.service.QuranicInsightsService;
import com.rewayaat.service.Chronology;
import com.rewayaat.service.NarratorSeal;
import com.rewayaat.service.NarratorService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
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
                                 Model model) {
        String text = query == null ? "" : query.trim();
        Map<String, Object> found = text.isEmpty() ? Map.of("total", 0, "narrators", List.of())
                : narratorService.search(text, page, PER_PAGE);
        long total = ((Number) found.getOrDefault("total", 0)).longValue();

        model.addAttribute("query", text);
        model.addAttribute("results", found.get("narrators"));
        model.addAttribute("total", total);
        model.addAttribute("page", Math.max(page, 1));
        model.addAttribute("hasMore", (long) Math.max(page, 1) * PER_PAGE < total);
        model.addAttribute("nextUrl", "/narrators?q=" + encode(text) + "&page=" + (Math.max(page, 1) + 1));
        model.addAttribute("prevUrl", "/narrators?q=" + encode(text) + "&page=" + (Math.max(page, 1) - 1));
        model.addAttribute("seoTitle", text.isEmpty() ? "Narrators of the Shia hadith corpus"
                : "Narrators named " + text);
        model.addAttribute("seoDescription", "Look up a narrator and read what each of the eight "
                + "Shia Rijal books says about him, with the page it is written on.");
        model.addAttribute("canonicalUrl", BASE_URL + "/narrators");
        model.addAttribute("breadcrumbs", List.of(
                Map.of("name", "Home", "url", "/"),
                Map.of("name", "Narrators", "url", "/narrators")));
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
                               @RequestParam(value = "tab", defaultValue = "books") String tab,
                               @RequestParam(value = "tag", required = false) String tag,
                               @RequestParam(value = "page", defaultValue = "1") int page,
                               Model model, HttpServletRequest request,
                               HttpServletResponse response) throws IOException {
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
            RedirectView moved = new RedirectView("/narrator/" + id + "/" + canonicalSlug
                    + (query == null || query.isBlank() ? "" : "?" + query));
            moved.setStatusCode(HttpStatus.MOVED_PERMANENTLY);
            return moved;
        }

        String base = "/narrator/" + id + (canonicalSlug.isEmpty() ? "" : "/" + canonicalSlug);
        String arabic = text(narrator.get("display_ar"));
        String english = text(narrator.get("display_en"));

        // Where he stands against the Prophet and the Imams. The rijal books say "a
        // companion of al-Sadiq" and leave the reader to know what that means; the band
        // says it in a picture. Both inputs are routinely absent and the band simply does
        // not render then.
        model.addAttribute("placement",
                chronology.place(ordinals(narrator), statedDeathYears(narrator)));

        // A name in a roundel, never a face - see NarratorSeal.
        model.addAttribute("seal", NarratorSeal.monogram(arabicNames(narrator), arabic));
        model.addAttribute("sealVariant", NarratorSeal.variant(text(narrator.get("person_id"))));
        String title = !english.isEmpty() ? english : arabic;

        model.addAttribute("narrator", narrator);
        model.addAttribute("narratorId", id);
        model.addAttribute("displayAr", arabic);
        model.addAttribute("displayEn", english);
        model.addAttribute("books", booksNamed(narrator));
        model.addAttribute("seoTitle", title + " — a narrator in the Shia Rijal books");
        model.addAttribute("seoDescription", summary(title, narrator));
        model.addAttribute("canonicalUrl", BASE_URL + base);
        model.addAttribute("breadcrumbs", List.of(
                Map.of("name", "Home", "url", "/"),
                Map.of("name", "Narrators", "url", "/narrators"),
                Map.of("name", title, "url", base)));

        // The narrations that name him, as the same card the rest of the site renders. They
        // are found by his name, not by a resolved chain, so the section says so plainly.
        @SuppressWarnings("unchecked")
        List<String> names = (List<String>) narrator.getOrDefault("names_ar", List.of());
        // Two tabs, because the page had become a scroll: what the books say of him, and the
        // narrations he stands in. The tab is in the URL, so each is a page that can be sent to
        // someone, and the narrations are fetched only when they are the tab being read.
        String view = "narrations".equals(tab) ? "narrations" : "books";
        Map<String, Object> naming = "narrations".equals(view)
                ? narratorService.narrationsNaming(names, tag, page, PER_PAGE)
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

        model.addAttribute("narrations", narrations);
        model.addAttribute("narrationTotal", narrationTotal);
        model.addAttribute("searchedNames", naming.get("searched_for"));
        model.addAttribute("narrationBooks", naming.getOrDefault("books", List.of()));
        model.addAttribute("view", view);
        model.addAttribute("booksUrl", base);
        model.addAttribute("narrationsUrl", narrationsBase);
        model.addAttribute("tagFacets", tagFacets(naming.get("tags"), narrationsBase, tag));
        model.addAttribute("activeTag", tag == null || tag.isBlank() ? null : tag);
        model.addAttribute("clearTagUrl", narrationsBase);
        model.addAttribute("narrationsPage", page);
        model.addAttribute("narrationsNextUrl", narrationsBase + withTag + "&page=" + (page + 1));
        model.addAttribute("narrationsPrevUrl", narrationsBase + withTag + "&page=" + (page - 1));
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

    /** The tag bar's pills: the tags these narrations carry, each a link that filters them. */
    private List<Map<String, Object>> tagFacets(Object buckets, String base, String active) {
        List<Map<String, Object>> facets = new ArrayList<>();
        for (Object row : buckets instanceof List<?> list ? list : List.of()) {
            Map<?, ?> bucket = (Map<?, ?>) row;
            String label = String.valueOf(bucket.get("label"));
            facets.add(Map.of("label", label,
                    "count", bucket.get("count"),
                    "url", base + "&tag=" + encode(label),
                    "active", label.equals(active)));
        }
        return facets;
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

    private String summary(String title, Map<String, Object> narrator) {
        List<String> named = booksNamed(narrator);
        String where = named.isEmpty() ? "the Shia Rijal books" : String.join(", ", named);
        return "What " + where + " say about " + title + ", each verdict shown with the book "
                + "and page it was read from.";
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
