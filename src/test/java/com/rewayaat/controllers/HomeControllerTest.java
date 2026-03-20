package com.rewayaat.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HomeControllerTest {

    @Test
    void home_populatesModelAttributes() {
        HomeController controller = new HomeController();
        Model model = new ExtendedModelMap();

        String view = controller.home("query", 2, "book:asc", null, model);

        assertEquals("index", view);
        assertEquals("query", model.getAttribute("query"));
        assertEquals(2, model.getAttribute("page"));
        assertEquals("book:asc", model.getAttribute("sort_fields"));
    }

    @Test
    void verifyRedirect_sendsTokenToSigninPage() {
        HomeController controller = new HomeController();

        String redirect = controller.verifyRedirect("abc123");

        assertEquals("redirect:/signin.html?verify_token=abc123", redirect);
    }

    @Test
    void resetRedirect_sendsTokenToSigninPage() {
        HomeController controller = new HomeController();

        String redirect = controller.resetRedirect("abc123");

        assertEquals("redirect:/signin.html?reset_token=abc123", redirect);
    }
}
