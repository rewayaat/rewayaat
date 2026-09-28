package com.rewayaat.controllers;

import com.rewayaat.service.BookCatalog;
import com.rewayaat.service.PageLocale;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * How to search, in the reader's language.
 *
 * <p>This was the last static HTML file the site served as a page, and so the last page
 * that could not be translated: the resource handler serves bytes and never sees a
 * locale. The Arabic footer linked an Arabic label - "إرشادات البحث" - to an English
 * page, and {@code /ar/search_tips.html} answered 404.
 *
 * <p>Served at the same URL, {@code .html} and all, for the reason
 * {@link UpdatesPageController} gives: the path is in {@code sitemap-static.xml} and in
 * the footer of every page, so renaming it would turn an indexed URL into a redirect for
 * no gain (invariant 4).
 *
 * <p>The counts in the hero come from the catalogue rather than from the markup. The
 * static file said "32,519 narrations" in a {@code <span>}, which is a number that goes
 * stale without anything reporting it.
 */
@Hidden
@Controller
public class SearchTipsPageController {

    private final BookCatalog catalog;
    private final MessageSource messages;

    public SearchTipsPageController(BookCatalog catalog, MessageSource messages) {
        this.catalog = catalog;
        this.messages = messages;
    }

    @GetMapping("/search_tips.html")
    public String searchTips(Model model, HttpServletRequest request) {
        PageLocale locale = PageLocale.of(request);
        List<BookCatalog.Book> books = catalog.books();

        model.addAttribute("totalNarrations", books.stream().mapToLong(BookCatalog.Book::count).sum());
        model.addAttribute("totalBooks", books.size());
        model.addAttribute("seoTitle", msg(locale, "seo.tips.title"));
        model.addAttribute("seoDescription", msg(locale, "seo.tips.description"));
        model.addAttribute("breadcrumbs", crumbs(locale));
        locale.applyTo(model, "/search_tips.html");
        return "search_tips";
    }

    /**
     * Home / Search Tips.
     *
     * <p>Visible only. No BreadcrumbList alongside it, because the static page carried
     * none either and a two-step trail to a guide is not a ranking signal worth a second
     * copy of the JSON-LD builder that lives in {@link BookPageController}.
     */
    private List<Map<String, String>> crumbs(PageLocale locale) {
        List<Map<String, String>> trail = new ArrayList<>();
        trail.add(Map.of("name", msg(locale, "crumb.home"), "url", locale.prefix() + "/"));
        trail.add(Map.of("name", msg(locale, "tips.crumb"),
                "url", locale.prefix() + "/search_tips.html"));
        return trail;
    }

    private String msg(PageLocale locale, String key) {
        return messages.getMessage(key, null, locale.locale());
    }
}
