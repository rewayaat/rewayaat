package com.rewayaat.controllers;

import com.rewayaat.config.ESClientProvider;
import com.rewayaat.core.HadithDisplaySegmenter;
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server-rendered hadith pages for SEO.
 */
@Hidden
@Controller
@RequestMapping("/hadith")
public class HadithPageController {

    private static final Logger LOGGER = LoggerFactory.getLogger(HadithPageController.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE_URL = "https://hadith.academyofislam.com";

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

        // SEO title: "Book #Number | HDP"
        String bookRef = buildBookRef(hadith);
        String seoTitle = (bookRef.isEmpty() ? truncate(englishFull, 80) : bookRef) + " | HDP - The Hadith Database";

        // SEO description: matn text (not chain), first 160 chars
        String seoDescription = truncate(englishContent.isEmpty() ? englishFull : englishContent, 160);

        // Canonical URL
        String canonicalUrl = BASE_URL + "/hadith/" + id;

        // JSON-LD structured data
        String jsonLd = buildJsonLd(hadith, canonicalUrl);

        // Breadcrumb segments
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
