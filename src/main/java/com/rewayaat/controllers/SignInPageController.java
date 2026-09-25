package com.rewayaat.controllers;

import com.rewayaat.service.PageLocale;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The sign-in, register and password-reset page.
 *
 * <p>A page rather than a static file. As a file it sat outside the {@code /ar} tree, so
 * a reader who had been on the Arabic site the whole way through arrived at a form that
 * was entirely English — and the server had no way to know which site had sent them,
 * which is why the language had to be smuggled in on a query parameter to seed the new
 * account. Served from here it has an Arabic twin like everything else, and the language
 * it was reached in is simply the URL.
 *
 * <p>Not indexed: it is a form, and the two language versions of it have nothing a search
 * engine wants. It still declares its pair, so a crawler that finds one knows the other.
 */
@Hidden
@Controller
public class SignInPageController {

    @GetMapping("/signin.html")
    public String signIn(Model model, HttpServletRequest request) {
        PageLocale.of(request).applyTo(model, "/signin.html");
        return "signin";
    }
}
