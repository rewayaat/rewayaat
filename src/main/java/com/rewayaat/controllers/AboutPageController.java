package com.rewayaat.controllers;

import com.rewayaat.service.PageLocale;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The About page, and the page a supporter returns to from Stripe.
 *
 * <p>The page is the same on every host, so the canonical and the crawlable text never
 * vary by host. The giving form is in the HTML everywhere and shown only on rewayaat.info,
 * by script: the Academy's host takes no money, and {@code SiteBrandAdvice} forbids letting
 * the host decide what a crawler reads.
 */
@Controller
public class AboutPageController {

    static final String PAGE_CSS = "/css/about.css";

    private final MessageSource messages;

    public AboutPageController(MessageSource messages) {
        this.messages = messages;
    }

    @GetMapping("/about")
    public String about(Model model, HttpServletRequest request) {
        PageLocale locale = PageLocale.of(request);
        model.addAttribute("seoTitle", messages.getMessage("seo.about.title", null, locale.locale()));
        model.addAttribute("seoDescription",
                messages.getMessage("seo.about.description", null, locale.locale()));
        model.addAttribute("navCurrent", "about");
        model.addAttribute("pageCss", PAGE_CSS);
        locale.applyTo(model, "/about");
        return "about";
    }

    /**
     * Where Stripe sends a supporter after paying. It says thank you and nothing else, and
     * is kept out of the index: it is only reachable from a payment.
     */
    @GetMapping("/about/thanks")
    public String thanks(Model model, HttpServletRequest request) {
        PageLocale locale = PageLocale.of(request);
        model.addAttribute("seoTitle",
                messages.getMessage("about.thanks.seoTitle", null, locale.locale()));
        model.addAttribute("seoDescription",
                messages.getMessage("seo.about.description", null, locale.locale()));
        model.addAttribute("robotsDirective", "noindex, follow");
        model.addAttribute("navCurrent", "about");
        model.addAttribute("pageCss", PAGE_CSS);
        locale.applyTo(model, "/about/thanks");
        return "about-thanks";
    }
}
