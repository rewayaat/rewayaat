package com.rewayaat.controllers;

import com.rewayaat.service.PageLocale;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The "what's new" page.
 *
 * <p>It was a static file under {@code static/}, which meant it could not be translated:
 * the resource handler serves bytes and never sees a locale. It is a template now, served
 * by this controller <strong>at the same URL</strong>. The {@code .html} suffix is kept
 * deliberately - the path is in {@code sitemap-static.xml} and linked from the navigation,
 * the footer, the home page and the narration page, so renaming it to {@code /updates}
 * would turn an indexed URL into a redirect for no gain (invariant 4).
 *
 * <p>The entries themselves come from {@code /recent_updates.json}, fetched by the page's
 * own script, and carry their Arabic in the same file under an {@code _ar} suffix - the
 * pattern {@code announcement.json} and {@code book_blurbs.json} already use.
 */
@Controller
public class UpdatesPageController {

    private final MessageSource messages;

    public UpdatesPageController(MessageSource messages) {
        this.messages = messages;
    }

    @GetMapping("/updates.html")
    public String updates(Model model, HttpServletRequest request) {
        PageLocale locale = PageLocale.of(request);
        model.addAttribute("seoTitle",
                messages.getMessage("seo.updates.title", null, locale.locale()));
        model.addAttribute("seoDescription",
                messages.getMessage("seo.updates.description", null, locale.locale()));
        locale.applyTo(model, "/updates.html");
        return "updates";
    }
}
