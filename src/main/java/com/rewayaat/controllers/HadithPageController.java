package com.rewayaat.controllers;

import com.rewayaat.config.ESClientProvider;
import com.rewayaat.core.HadithDisplaySegmenter;
import com.rewayaat.core.HadithObjectCollection;
import com.rewayaat.service.BookCatalog;
import com.rewayaat.service.SimilarHadithService;
import com.rewayaat.core.HadithSourceFilter;
import com.rewayaat.core.data.HadithObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Server-rendered hadith pages for SEO.
 */
@Hidden
@Controller
@RequestMapping("/hadith")
public class HadithPageController {

    private static final Logger LOGGER = LoggerFactory.getLogger(HadithPageController.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE_URL = HomeController.BASE_URL;

    /** Enough related reading to be useful without turning the page into a link farm. */
    private static final int MAX_SIMILAR_LINKS = 8;

    private final BookCatalog catalog;
    private final SimilarHadithService similarHadith;

    public HadithPageController(BookCatalog catalog, SimilarHadithService similarHadith) {
        this.catalog = catalog;
        this.similarHadith = similarHadith;
    }

    @RequestMapping(value = "/{id}", method = RequestMethod.GET)
    public String hadithPage(@PathVariable("id") String id, Model model, HttpServletResponse response)
            throws IOException {
        HadithObject hadith = loadNarration(id);
        if (hadith == null) {
            // sendError runs the container's error dispatch, which serves
            // static/error/404.html under a real 404. Redirecting there instead
            // answered 302 -> 200, which crawlers read as a soft 404.
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return null;
        }

        // Split chain from matn using the display segmenter
        Map<String, Object> segMap = new LinkedHashMap<>();
        segMap.put("english", hadith.getEnglish());
        segMap.put("arabic", hadith.getArabic());
        HadithDisplaySegmenter.enrich(segMap);

        String englishContent = stripHtml((String) segMap.getOrDefault("englishContent", hadith.getEnglish()));
        String englishFull = stripHtml(hadith.getEnglish());

        // "HDP" is an acronym with no search volume, and it was eating the end of every
        // title. The slot goes to words people actually search for instead.
        String bookRef = buildBookRef(hadith);
        String seoTitle = (bookRef.isEmpty() ? truncate(englishFull, 80) : bookRef)
                + " — Shia Hadith in Arabic & English";

        // SEO description: matn text (not chain), first 160 chars
        String seoDescription = truncate(englishContent.isEmpty() ? englishFull : englishContent, 160);

        // Canonical URL
        String canonicalUrl = BASE_URL + "/hadith/" + id;

        // JSON-LD structured data
        String jsonLd = buildJsonLd(hadith, canonicalUrl);

        // The page used to have no internal links at all beyond three copies of "/",
        // which left all 32,519 of them as dead ends. These give a crawler somewhere to
        // go and give the hub pages a route back down.
        Optional<BookCatalog.Book> book = catalog.bookByName(hadith.getBook());
        Optional<BookCatalog.Chapter> chapter = catalog.chapterFor(hadith.getBook(), hadith.getVolume(),
                hadith.getPart(), hadith.getSection(), hadith.getChapter());

        List<Map<String, String>> crumbs = new ArrayList<>();
        crumbs.add(Map.of("name", "Home", "url", "/"));
        crumbs.add(Map.of("name", "Books", "url", "/books"));
        book.ifPresent(b -> {
            crumbs.add(Map.of("name", b.name(), "url", "/books/" + b.slug()));
            if (hadith.getVolume() != null && !hadith.getVolume().isBlank()) {
                crumbs.add(Map.of("name", "Volume " + hadith.getVolume(),
                        "url", "/books/" + b.slug() + "/volume/" + encode(hadith.getVolume())));
            }
        });
        chapter.ifPresent(c -> crumbs.add(Map.of("name", c.title(), "url", c.url())));
        crumbs.add(Map.of("name", "Hadith " + (hadith.getNumber() == null ? id : hadith.getNumber()),
                "url", "/hadith/" + id));

        model.addAttribute("breadcrumbs", crumbs);
        model.addAttribute("breadcrumbJsonLd", breadcrumbJsonLd(crumbs));
        model.addAttribute("chapterUrl", chapter.map(BookCatalog.Chapter::url).orElse(null));
        model.addAttribute("similar", similarLinks(id));

        model.addAttribute("hadith", hadith);
        model.addAttribute("hadithId", id);
        model.addAttribute("seoTitle", seoTitle);
        model.addAttribute("seoDescription", seoDescription);
        model.addAttribute("canonicalUrl", canonicalUrl);
        model.addAttribute("jsonLd", jsonLd);
        model.addAttribute("bookRef", bookRef);
        model.addAttribute("baseUrl", BASE_URL);

        return "hadith";
    }

    private HadithObject loadNarration(String id) {
        String narrationId = id == null ? "" : id.trim();
        if (narrationId.isEmpty()) {
            return null;
        }
        try (ESClientProvider provider = new ESClientProvider()) {
            var response = provider.client().get(g -> g
                    .index(ESClientProvider.INDEX)
                    .id(narrationId)
                    .sourceExcludes(HadithSourceFilter.excludes()), Map.class);
            if (!response.found() || response.source() == null) {
                return null;
            }
            Map<String, Object> map = new LinkedHashMap<>(response.source());
            map.put("_id", narrationId);
            return JSON.convertValue(map, HadithObject.class);
        } catch (Exception e) {
            LOGGER.error("Error loading hadith {}", narrationId, e);
            return null;
        }
    }

    private String buildBookRef(HadithObject hadith) {
        StringBuilder sb = new StringBuilder();
        if (hadith.getBook() != null && !hadith.getBook().isBlank()) {
            sb.append(hadith.getBook());
        }
        if (hadith.getNumber() != null && !hadith.getNumber().isBlank()) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append("#").append(hadith.getNumber());
        }
        return sb.toString();
    }

    private String buildJsonLd(HadithObject hadith, String url) {
        try {
            Map<String, Object> ld = new LinkedHashMap<>();
            ld.put("@context", "https://schema.org");
            ld.put("@type", "ScholarlyArticle");

            if (hadith.getBook() != null) {
                String headline = hadith.getBook();
                if (hadith.getNumber() != null) {
                    headline += " #" + hadith.getNumber();
                }
                ld.put("headline", headline);
            }

            String englishText = stripHtml(hadith.getEnglish());
            if (englishText != null && !englishText.isBlank()) {
                ld.put("description", truncate(englishText, 300));
            }

            ld.put("url", url);
            ld.put("inLanguage", "en");

            if (hadith.getSource() != null) {
                ld.put("sourceOrganization", hadith.getSource());
            }

            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(ld);
        } catch (Exception e) {
            LOGGER.warn("Error building JSON-LD", e);
            return "{}";
        }
    }

    /**
     * A handful of the pre-computed LLM-judged similar narrations, as links.
     *
     * <p>47,522 judged-similar pairs were already sitting in the index and reachable only
     * through an XHR the crawler never makes. Rendering a few of them server-side turns
     * the strongest signal the corpus has about which narrations belong together into an
     * internal link graph.
     */
    private List<Map<String, String>> similarLinks(String id) {
        List<Map<String, String>> links = new ArrayList<>();
        try {
            HadithObjectCollection related = similarHadith.findSimilar(id, 0, MAX_SIMILAR_LINKS);
            for (HadithObject other : related.getCollection()) {
                String label = buildBookRef(other);
                String excerpt = truncate(stripHtml(other.getEnglish()), 140);
                links.add(Map.of(
                        "url", "/hadith/" + other.getId(),
                        "label", label.isBlank() ? "Related narration" : label,
                        "excerpt", excerpt));
            }
        } catch (Exception e) {
            // Related reading is a bonus; never fail the page over it.
            LOGGER.warn("Could not load similar narrations for {}", id, e);
        }
        return links;
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
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(ld);
        } catch (Exception e) {
            LOGGER.warn("Could not build the breadcrumb JSON-LD", e);
            return "{}";
        }
    }

    private static String encode(String segment) {
        return java.net.URLEncoder.encode(segment, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String stripHtml(String text) {
        if (text == null) return "";
        return text.replaceAll("<[^>]*>", "").trim();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        // Truncate at word boundary
        int cut = text.lastIndexOf(' ', maxLen);
        if (cut <= 0) cut = maxLen;
        return text.substring(0, cut) + "...";
    }
}
