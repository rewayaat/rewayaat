package com.rewayaat.controllers;

import com.rewayaat.service.NarratorService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.io.IOException;
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

    @Autowired
    private NarratorService narratorService;

    @GetMapping("/narrator/{id}")
    public String narratorPage(@PathVariable("id") String id, Model model,
                               HttpServletResponse response) throws IOException {
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
        return "narrator";
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
