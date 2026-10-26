package com.rewayaat.controllers;

import com.rewayaat.service.PageLocale;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one page every "report" link leads to.
 *
 * <p>A report used to be a {@code mailto:}, which does nothing for a reader without a
 * desktop mail client and throws the context away the moment the draft is edited. The
 * links now carry that context in the query string and this page echoes it into the form.
 *
 * <p>The four parameters come from the URL, so they are the reader's to forge. Each is
 * cut to {@link #MAX} characters and stripped of control characters, and the template only
 * ever prints them with {@code th:text} or as attribute values, never {@code th:utext}.
 * {@code url} is additionally only offered as a link when it is http(s) or site-relative,
 * so a {@code javascript:} value cannot become a clickable href.
 *
 * <p>The Arabic page is {@code /ar/contact}, reached through the interceptor that strips
 * the prefix; {@link PageLocale#hasArabicVersion} lists it.
 */
@Hidden
@Controller
public class ContactPageController {

    static final int MAX = 300;
    private static final Set<String> ABOUT = Set.of("narration", "narrator", "site");

    private final MessageSource messages;

    public ContactPageController(MessageSource messages) {
        this.messages = messages;
    }

    @GetMapping("/contact")
    public String contact(@RequestParam(required = false) String about,
                          @RequestParam(required = false) String id,
                          @RequestParam(required = false) String label,
                          @RequestParam(required = false) String url,
                          Model model, HttpServletRequest request) {
        PageLocale locale = PageLocale.of(request);
        String subject = clean(about);
        subject = ABOUT.contains(subject) ? subject : "site";
        String link = clean(url);

        model.addAttribute("about", subject);
        model.addAttribute("contactId", clean(id));
        model.addAttribute("contactLabel", clean(label));
        model.addAttribute("contactUrl", link);
        model.addAttribute("contactUrlSafe", isLinkable(link));
        model.addAttribute("contactLocale", locale.tag());
        model.addAttribute("seoTitle", msg(locale, "seo.contact.title"));
        model.addAttribute("seoDescription", msg(locale, "seo.contact.description"));
        model.addAttribute("breadcrumbs", crumbs(locale));
        // A prefilled form is the same page at another URL; only the bare one is indexed.
        if (!clean(id).isEmpty() || !clean(label).isEmpty() || !link.isEmpty()
                || !clean(about).isEmpty()) {
            model.addAttribute("robotsDirective", "noindex, follow");
        }
        locale.applyTo(model, "/contact");
        return "contact";
    }

    /** Capped, control characters removed, trimmed. Never null. */
    public static String clean(String value) {
        if (value == null) {
            return "";
        }
        String stripped = value.replaceAll("\\p{Cntrl}", " ").trim();
        if (stripped.length() > MAX) {
            stripped = stripped.substring(0, MAX);
            // Do not leave half of a surrogate pair at the cut.
            if (Character.isHighSurrogate(stripped.charAt(stripped.length() - 1))) {
                stripped = stripped.substring(0, stripped.length() - 1);
            }
        }
        return stripped.trim();
    }

    static boolean isLinkable(String url) {
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://")
                || (lower.startsWith("/") && !lower.startsWith("//"));
    }

    private List<Map<String, String>> crumbs(PageLocale locale) {
        List<Map<String, String>> trail = new ArrayList<>();
        trail.add(Map.of("name", msg(locale, "crumb.home"), "url", locale.prefix() + "/"));
        trail.add(Map.of("name", msg(locale, "contact.crumb"), "url", locale.prefix() + "/contact"));
        return trail;
    }

    private String msg(PageLocale locale, String key) {
        return messages.getMessage(key, null, locale.locale());
    }
}
