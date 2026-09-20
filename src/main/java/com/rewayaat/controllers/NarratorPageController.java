package com.rewayaat.controllers;

import com.rewayaat.service.HadithCardFactory;
import com.rewayaat.service.QuranicInsightsService;
import com.rewayaat.service.NarratorService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

    @GetMapping("/narrator/{id}")
    public String narratorPage(@PathVariable("id") String id,
                               @RequestParam(value = "tag", required = false) String tag,
                               @RequestParam(value = "page", defaultValue = "1") int page,
                               Model model, HttpServletResponse response) throws IOException {
        Map<String, Object> narrator = narratorService.getNarrator(id);
        if (narrator == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return null;
        }
        String arabic = text(narrator.get("display_ar"));
        String english = text(narrator.get("display_en"));
        String title = !english.isEmpty() ? english : arabic;

        model.addAttribute("narrator", narrator);
        model.addAttribute("narratorId", id);
        model.addAttribute("displayAr", arabic);
        model.addAttribute("displayEn", english);
        model.addAttribute("books", booksNamed(narrator));
        model.addAttribute("seoTitle", title + " — a narrator in the Shia Rijal books");
        model.addAttribute("seoDescription", summary(title, narrator));
        model.addAttribute("canonicalUrl", BASE_URL + "/narrator/" + id);
        model.addAttribute("breadcrumbs", List.of(
                Map.of("name", "Home", "url", "/"),
                Map.of("name", "Narrators", "url", "/narrators"),
                Map.of("name", title, "url", "/narrator/" + id)));

        // The narrations that name him, as the same card the rest of the site renders. They
        // are found by his name, not by a resolved chain, so the section says so plainly.
        @SuppressWarnings("unchecked")
        List<String> names = (List<String>) narrator.getOrDefault("names_ar", List.of());
        Map<String, Object> naming = narratorService.narrationsNaming(names, tag, page, PER_PAGE);
        List<Map<String, Object>> narrations = new ArrayList<>();
        for (Object row : (List<?>) naming.getOrDefault("hits", List.of())) {
            Map<?, ?> hit = (Map<?, ?>) row;
            @SuppressWarnings("unchecked")
            Map<String, Object> source = (Map<String, Object>) hit.get("source");
            narrations.add(cards.build(String.valueOf(hit.get("id")), source,
                    "/narrator/" + id, BASE_URL));
        }
        // the card shows a Quranic-insight count, so it has to be fetched as the other pages do
        Map<String, Integer> insightCounts = quranicInsights.insightCounts(
                narrations.stream().map(card -> String.valueOf(card.get("id"))).toList());
        narrations.forEach(card -> card.put("quranCount",
                insightCounts.getOrDefault(String.valueOf(card.get("id")), 0)));
        long narrationTotal = ((Number) naming.getOrDefault("total", 0)).longValue();
        String base = "/narrator/" + id;
        String withTag = tag == null || tag.isBlank() ? "" : "?tag=" + encode(tag);

        model.addAttribute("narrations", narrations);
        model.addAttribute("narrationTotal", narrationTotal);
        model.addAttribute("searchedNames", naming.get("searched_for"));
        model.addAttribute("tagFacets", tagFacets(naming.get("tags"), base, tag));
        model.addAttribute("activeTag", tag == null || tag.isBlank() ? null : tag);
        model.addAttribute("clearTagUrl", base);
        model.addAttribute("narrationsPage", page);
        model.addAttribute("narrationsNextUrl", base + (withTag.isEmpty() ? "?" : withTag + "&")
                + "page=" + (page + 1));
        model.addAttribute("narrationsPrevUrl", base + (withTag.isEmpty() ? "?" : withTag + "&")
                + "page=" + (page - 1));
        model.addAttribute("narrationsHasMore", (long) page * PER_PAGE < narrationTotal);
        return "narrator";
    }

    /** The tag bar's pills: the tags these narrations carry, each a link that filters them. */
    private List<Map<String, Object>> tagFacets(Object buckets, String base, String active) {
        List<Map<String, Object>> facets = new ArrayList<>();
        for (Object row : buckets instanceof List<?> list ? list : List.of()) {
            Map<?, ?> bucket = (Map<?, ?>) row;
            String label = String.valueOf(bucket.get("label"));
            facets.add(Map.of("label", label,
                    "count", bucket.get("count"),
                    "url", base + "?tag=" + encode(label),
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
}
