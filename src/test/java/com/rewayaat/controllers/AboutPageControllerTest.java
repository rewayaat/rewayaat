package com.rewayaat.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AboutPageControllerTest {

    private static AboutPageController controller() {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        return new AboutPageController(messages);
    }

    @Test
    void servesTheAboutPageWithItsOwnStylesheet() {
        Model model = new ExtendedModelMap();
        assertEquals("about", controller().about(model, new MockHttpServletRequest()));
        assertEquals("about", model.getAttribute("navCurrent"));
        assertEquals("/css/about.css", model.getAttribute("pageCss"));
        assertEquals("https://rewayaat.info/about", model.getAttribute("canonicalUrl"));
        assertNull(model.getAttribute("robotsDirective"), "the About page is indexable");
    }

    @Test
    void theThankYouPageIsKeptOutOfTheIndex() {
        Model model = new ExtendedModelMap();
        assertEquals("about-thanks", controller().thanks(model, new MockHttpServletRequest()));
        assertEquals("noindex, follow", model.getAttribute("robotsDirective"));
    }

    @Test
    void theGivingFormIsInThePageOnEveryHostAndHiddenUntilTheScriptShowsIt() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/templates/about.html"));
        assertTrue(html.contains("id=\"support\" hidden data-support"),
                "the ask must ship hidden; js/about-give.js reveals it off the Academy's host");
        assertFalse(html.contains("academyHost"), "the server must not decide what the page says by host");
    }
}
