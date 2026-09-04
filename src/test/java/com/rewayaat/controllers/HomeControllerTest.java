package com.rewayaat.controllers;

import com.rewayaat.service.BookCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomeControllerTest {

    /** The catalog is only read for the rendered book list; an empty one is enough here. */
    private static HomeController controller() {
        return new HomeController(new BookCatalog());
    }

    @Test
    void home_populatesModelAttributes() {
        HomeController controller = controller();
        Model model = new ExtendedModelMap();

        String view = controller.home("query", 2, "book:asc", null, model);

        assertEquals("index", view);
        assertEquals("query", model.getAttribute("query"));
        assertEquals(2, model.getAttribute("page"));
        assertEquals("book:asc", model.getAttribute("sort_fields"));
    }

    /**
     * The home page is the site's candidate for "shia hadith", and used to open its title
     * with "HDP" while never using the word "Shia" outside an ignored keywords meta.
     */
    @Test
    void home_titleAndDescriptionCarryTheTargetPhrase() {
        Model model = new ExtendedModelMap();

        controller().home("", 1, null, null, model);

        String title = (String) model.getAttribute("seoTitle");
        String description = (String) model.getAttribute("seoDescription");
        assertTrue(title.toLowerCase().startsWith("shia hadith"),
                "title should lead with the target phrase, was: " + title);
        assertTrue(description.toLowerCase().contains("shia hadith"),
                "description should contain the target phrase, was: " + description);
    }

    /**
     * A relative canonical resolves against whichever host served the page, so the
     * rewayaat.info mirror was declaring itself canonical instead of consolidating.
     */
    @Test
    void home_canonicalIsAbsoluteAndOnTheCanonicalHost() {
        Model model = new ExtendedModelMap();

        controller().home("", 1, null, null, model);

        assertEquals("https://hadith.academyofislam.com/", model.getAttribute("canonicalUrl"));
    }

    @Test
    void home_publishesWebsiteAndOrganizationStructuredData() {
        Model model = new ExtendedModelMap();

        controller().home("", 1, null, null, model);

        String jsonLd = (String) model.getAttribute("jsonLd");
        assertTrue(jsonLd.contains("\"WebSite\""), "expected WebSite node");
        assertTrue(jsonLd.contains("SearchAction"), "expected a sitelinks SearchAction");
        assertTrue(jsonLd.contains("Organization"), "expected an Organization node");
    }

    /** Search result pages are thin and unbounded; only the bare home page is indexable. */
    @Test
    void home_searchResultPagesAreNoindexed() {
        Model bare = new ExtendedModelMap();
        Model search = new ExtendedModelMap();

        controller().home("", 1, null, null, bare);
        controller().home("prayer", 1, null, null, search);

        assertNull(bare.getAttribute("robotsDirective"));
        assertEquals("noindex, follow", search.getAttribute("robotsDirective"));
    }

    @Test
    void verifyRedirect_sendsTokenToSigninPage() {
        HomeController controller = controller();

        String redirect = controller.verifyRedirect("abc123");

        assertEquals("redirect:/signin.html?verify_token=abc123", redirect);
    }

    @Test
    void resetRedirect_sendsTokenToSigninPage() {
        HomeController controller = controller();

        String redirect = controller.resetRedirect("abc123");

        assertEquals("redirect:/signin.html?reset_token=abc123", redirect);
    }
}
