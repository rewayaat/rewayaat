package com.rewayaat.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

import com.rewayaat.service.BookCatalog;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Handles requests for the home page.
 */
@Hidden
@Controller
@RequestMapping(value = "/")
public class HomeController {

    private static final Logger log = LoggerFactory.getLogger(HomeController.class);

    private final BookCatalog catalog;

    public HomeController(BookCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * The host every canonical points at. The app also answers on rewayaat.info, and the
     * home page previously carried a relative {@code <link rel="canonical" href="/">},
     * which resolves against whichever host served it — so the mirror declared itself
     * canonical and the two domains competed for the same rankings.
     */
    static final String BASE_URL = "https://hadith.academyofislam.com";

    /**
     * Front-loaded with the phrase the site is trying to rank for. The old title opened
     * with "HDP", an acronym nobody searches, and never contained the word "Shia" at all —
     * neither did the description or the H1, so the only on-page use of the target phrase
     * was a {@code keywords} meta, which Google has ignored since 2009.
     */
    static final String HOME_TITLE =
            "Shia Hadith Database — Search 32,000+ Narrations in Arabic & English";

    static final String HOME_DESCRIPTION =
            "Search 32,000+ Shia hadith in Arabic and English. Browse Al-Kafi, Nahj al-Balagha, "
            + "Man La Yahduruh al-Faqih and more, with similar narrations and Quranic insights.";

    /**
     * Site-level structured data. {@code WebSite} with a {@code SearchAction} is what makes a
     * sitelinks search box eligible; {@code Organization} ties the site to the Academy for
     * Learning Islam as a publisher. The home page previously carried no JSON-LD at all.
     */
    private static final String HOME_JSON_LD = """
            [{
              "@context": "https://schema.org",
              "@type": "WebSite",
              "@id": "%1$s/#website",
              "url": "%1$s/",
              "name": "The Hadith Database",
              "alternateName": ["HDP", "HDP - The Hadith Database", "Shia Hadith Database"],
              "description": "%2$s",
              "inLanguage": ["en", "ar"],
              "publisher": { "@id": "%1$s/#organization" },
              "potentialAction": {
                "@type": "SearchAction",
                "target": {
                  "@type": "EntryPoint",
                  "urlTemplate": "%1$s/?q={search_term_string}"
                },
                "query-input": "required name=search_term_string"
              }
            },
            {
              "@context": "https://schema.org",
              "@type": ["Organization", "EducationalOrganization"],
              "@id": "%1$s/#organization",
              "name": "Academy for Learning Islam",
              "alternateName": "A.L.I.",
              "url": "https://academyofislam.com",
              "logo": "%1$s/img/mainlogo-transparent.png",
              "email": "rewayaat.org@gmail.com",
              "sameAs": ["https://github.com/rewayaat/rewayaat"]
            }]
            """.formatted(BASE_URL, HOME_DESCRIPTION);

    @RequestMapping(method = RequestMethod.GET)
    public final String home(@RequestParam(value = "q", required = false, defaultValue = "") String query,
                             @RequestParam(value = "page", defaultValue = "1") int page,
                             @RequestParam(value = "sort_fields", required = false) String sortFields,
                             HttpServletRequest request, final Model model) {
        log.debug("Home request: query='{}', page={}, sort_fields='{}'", query, page, sortFields);
        model.addAttribute("query", query);
        model.addAttribute("page", page);
        model.addAttribute("sort_fields", sortFields);

        // The home page body used to be fetched over XHR, so the served HTML carried no
        // content and no links. It is rendered from the catalog now, which also gives the
        // page its first internal links into the book hubs.
        List<BookCatalog.Book> books = catalog.books();
        model.addAttribute("books", books);
        model.addAttribute("totalNarrations", books.stream().mapToLong(BookCatalog.Book::count).sum());

        model.addAttribute("seoTitle", HOME_TITLE);
        model.addAttribute("seoDescription", HOME_DESCRIPTION);
        model.addAttribute("canonicalUrl", BASE_URL + "/");
        model.addAttribute("jsonLd", HOME_JSON_LD);

        // A search result page is thin, unbounded and duplicates the narration pages it
        // links to. The canonical already folds it into the home page; "noindex, follow"
        // makes that explicit while still letting crawlers walk through to the results.
        model.addAttribute("robotsDirective", query.isBlank() ? null : "noindex, follow");

        return "index";
    }

    @RequestMapping(value = "/auth/verify", method = RequestMethod.GET)
    public String verifyRedirect(@RequestParam(value = "token", required = false, defaultValue = "") String token) {
        return redirectToSigninWithToken("verify_token", token);
    }

    @RequestMapping(value = "/auth/reset", method = RequestMethod.GET)
    public String resetRedirect(@RequestParam(value = "token", required = false, defaultValue = "") String token) {
        return redirectToSigninWithToken("reset_token", token);
    }

    @RequestMapping(value = "/edit", method = RequestMethod.GET)
    public String editPage(@RequestParam(value = "id", required = false) String id,
                           @RequestParam(value = "returnTo", required = false) String returnTo,
                           Model model) {
        if (id != null && !id.isBlank()) {
            model.addAttribute("hadithId", id);
        }
        model.addAttribute("returnTo", returnTo != null ? returnTo : "/");
        return "edit";
    }

    private String redirectToSigninWithToken(String queryKey, String token) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/signin.html");
        if (token != null && !token.isBlank()) {
            builder.queryParam(queryKey, token);
        }
        return "redirect:" + builder.build().encode().toUriString();
    }
}
