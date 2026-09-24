package com.rewayaat.controllers;

import com.rewayaat.config.ESClientProvider;
import com.rewayaat.core.HadithDisplaySegmenter;
import com.rewayaat.core.HadithObjectCollection;
import com.rewayaat.service.ArabicNames;
import com.rewayaat.service.BookCatalog;
import com.rewayaat.service.PageLocale;
import com.rewayaat.service.HadithCardFactory;
import com.rewayaat.service.QuranicInsightsService;
import com.rewayaat.service.SimilarHadithService;
import com.rewayaat.core.HadithSourceFilter;
import com.rewayaat.core.data.HadithObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
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
    private final HadithCardFactory cards;
    private final QuranicInsightsService quranicInsights;
    private final MessageSource messages;

    public HadithPageController(BookCatalog catalog, SimilarHadithService similarHadith,
                                HadithCardFactory cards, QuranicInsightsService quranicInsights,
                                MessageSource messages) {
        this.catalog = catalog;
        this.similarHadith = similarHadith;
        this.cards = cards;
        this.quranicInsights = quranicInsights;
        this.messages = messages;
    }

    private String msg(PageLocale locale, String key, Object... args) {
        return messages.getMessage(key, args, locale.locale());
    }

    /** A number in the digits the page is set in, for labels assembled here. */
    private static String digits(PageLocale locale, String value) {
        if (!locale.isArabic() || value == null) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        for (char character : value.toCharArray()) {
            out.append(character >= '0' && character <= '9'
                    ? (char) ('\u0660' + (character - '0')) : character);
        }
        return out.toString();
    }

    @RequestMapping(value = "/{id}", method = RequestMethod.GET)
    public String hadithPage(@PathVariable("id") String id, Model model, HttpServletResponse response,
                             HttpServletRequest request)
            throws IOException {
        HadithObject hadith = loadNarration(id);
        if (hadith == null) {
            // sendError runs the container's error dispatch, which serves
            // static/error/404.html under a real 404. Redirecting there instead
            // answered 302 -> 200, which crawlers read as a soft 404.
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return null;
        }

        // Resolved before anything user-visible is built: the title, the description,
        // the breadcrumbs and the card all differ by language.
        PageLocale locale = PageLocale.of(request);

        // Split chain from matn using the display segmenter
        Map<String, Object> segMap = new LinkedHashMap<>();
        segMap.put("english", hadith.getEnglish());
        segMap.put("arabic", hadith.getArabic());
        HadithDisplaySegmenter.enrich(segMap);

        String englishContent = stripHtml((String) segMap.getOrDefault("englishContent", hadith.getEnglish()));
        String englishFull = stripHtml(hadith.getEnglish());

        // "HDP" is an acronym with no search volume, and it was eating the end of every
        // title. The slot goes to words people actually search for instead.
        String bookRef = buildBookRef(hadith, locale);
        String seoTitle = (bookRef.isEmpty() ? truncate(englishFull, 80) : bookRef)
                + " — " + msg(locale, "seo.hadith.suffix");

        // The description quotes the narration, so on the Arabic page it quotes the
        // Arabic. Quoting the translation there described the page in a language the
        // page no longer shows.
        String arabicFull = stripHtml(hadith.getArabic());
        String summary = locale.isArabic() && !arabicFull.isBlank()
                ? arabicFull
                : (englishContent.isEmpty() ? englishFull : englishContent);
        String seoDescription = truncate(summary, 160);

        // Canonical URL
        // The Arabic narration page is reachable but not indexable, so it is canonical to
        // itself rather than to the English page - a noindex page pointing its canonical
        // elsewhere sends two contradictory instructions - and it publishes no hreflang
        // pair, because hreflang describes pages that are meant to be indexed.
        String canonicalUrl = locale.urlFor("/hadith/" + id);
        if (locale.isArabic()) {
            model.addAttribute("robotsDirective", "noindex, follow");
        }
        model.addAttribute("htmlLang", locale.tag());
        model.addAttribute("htmlDir", locale.direction());
        model.addAttribute("isArabic", locale.isArabic());
        model.addAttribute("arPrefix", locale.prefix());

        // JSON-LD structured data
        String jsonLd = buildJsonLd(hadith, canonicalUrl);

        // The page used to have no internal links at all beyond three copies of "/",
        // which left all 32,519 of them as dead ends. These give a crawler somewhere to
        // go and give the hub pages a route back down.
        Optional<BookCatalog.Book> book = catalog.bookByName(hadith.getBook());
        Optional<BookCatalog.Chapter> chapter = catalog.chapterFor(hadith.getBook(), hadith.getVolume(),
                hadith.getPart(), hadith.getSection(), hadith.getChapter());

        // Every rung was written in English with an unprefixed URL, so the trail at the
        // top of an Arabic page read Home / Books / Al-Khisal and led back out of it.
        String prefix = locale.prefix();
        List<Map<String, String>> crumbs = new ArrayList<>();
        crumbs.add(Map.of("name", msg(locale, "crumb.home"), "url", prefix + "/"));
        crumbs.add(Map.of("name", msg(locale, "nav.books"), "url", prefix + "/books"));
        book.ifPresent(b -> {
            String name = locale.isArabic() && b.nameAr() != null && !b.nameAr().isBlank()
                    ? b.nameAr() : b.name();
            crumbs.add(Map.of("name", name, "url", prefix + "/books/" + b.slug()));
            if (hadith.getVolume() != null && !hadith.getVolume().isBlank()) {
                String label = locale.isArabic()
                        ? msg(locale, "book.volumeNumber", digits(locale, hadith.getVolume()))
                        : "Volume " + hadith.getVolume();
                crumbs.add(Map.of("name", label,
                        "url", prefix + "/books/" + b.slug() + "/volume/" + encode(hadith.getVolume())));
            }
        });
        chapter.ifPresent(c -> {
            String title = locale.isArabic() && c.titleAr() != null && !c.titleAr().isBlank()
                    ? c.titleAr() : c.title();
            crumbs.add(Map.of("name", title, "url", prefix + c.url()));
        });
        String number = hadith.getNumber() == null ? id : hadith.getNumber();
        crumbs.add(Map.of("name", msg(locale, "crumb.hadithNumber", digits(locale, number)),
                "url", prefix + "/hadith/" + id));

        // The narration renders through the same card as a chapter page and the search
        // results, rather than the hand-rolled layout this page used to carry. Its tag
        // pills filter the chapter the narration belongs to, since this page has nothing
        // to filter itself.
        Map<String, Object> card = cards.build(id, rawSource(id),
                chapter.map(BookCatalog.Chapter::url).orElse(null), BASE_URL, locale);
        card.put("quranCount", quranicInsights.insightCounts(List.of(id)).getOrDefault(id, 0));
        model.addAttribute("card", card);

        model.addAttribute("breadcrumbs", crumbs);
        model.addAttribute("breadcrumbJsonLd", breadcrumbJsonLd(crumbs));
        model.addAttribute("chapterUrl",
                chapter.map(c -> locale.prefix() + c.url()).orElse(null));
        // The link back up to the chapter named it in English on the Arabic page, the
        // one string left over once the card and the trail were translated.
        model.addAttribute("chapterName", chapter
                .map(c -> locale.isArabic() && c.titleAr() != null && !c.titleAr().isBlank()
                        ? c.titleAr() : c.title())
                .orElse(hadith.getChapter()));
        model.addAttribute("similar", similarLinks(id, locale));

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

    /** The raw document, which the card factory shapes. */
    private Map<String, Object> rawSource(String id) {
        try (ESClientProvider provider = new ESClientProvider()) {
            var response = provider.client().get(g -> g.index(ESClientProvider.INDEX).id(id), Map.class);
            if (!response.found() || response.source() == null) {
                return Map.of();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> source = new LinkedHashMap<>(response.source());
            return source;
        } catch (Exception e) {
            LOGGER.error("Error loading raw narration {}", id, e);
            return Map.of();
        }
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

    private String buildBookRef(HadithObject hadith, PageLocale locale) {
        StringBuilder sb = new StringBuilder();
        if (hadith.getBook() != null && !hadith.getBook().isBlank()) {
            String arabic = locale.isArabic() ? ArabicNames.book(hadith.getBook()) : null;
            sb.append(arabic == null || arabic.isBlank() ? hadith.getBook() : arabic);
        }
        if (hadith.getNumber() != null && !hadith.getNumber().isBlank()) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append("#").append(digits(locale, hadith.getNumber()));
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
    private List<Map<String, String>> similarLinks(String id, PageLocale locale) {
        List<Map<String, String>> links = new ArrayList<>();
        try {
            HadithObjectCollection related = similarHadith.findSimilar(id, 0, MAX_SIMILAR_LINKS);
            for (HadithObject other : related.getCollection()) {
                String label = buildBookRef(other, locale);
                // SimilarHadithService already ran the display segmenter over these, so
                // the matn is available; excerpting the raw English would open every
                // entry with its chain of transmission instead.
                // On the Arabic page the excerpt is the matn, for the same reason the
                // card no longer shows the translation there.
                Object segmented = other.getAdditionalProperties()
                        .get(locale.isArabic() ? "arabicContent" : "englishContent");
                String fallback = locale.isArabic() ? other.getArabic() : other.getEnglish();
                String body = segmented == null || segmented.toString().isBlank()
                        ? fallback : segmented.toString();
                String excerpt = truncate(stripHtml(body), 140);
                links.add(Map.of(
                        "url", locale.prefix() + "/hadith/" + other.getId(),
                        "label", label.isBlank() ? msg(locale, "hadith.relatedNarration") : label,
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
