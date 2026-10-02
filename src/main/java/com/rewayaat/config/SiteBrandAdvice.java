package com.rewayaat.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Tells a page which host is serving it, so the masthead can carry the right mark.
 *
 * <p>One application answers on several hosts. hadith.academyofislam.com is the host the
 * Academy for Learning Islam presents, and keeps the Academy's logo. rewayaat.info is the
 * database's own host and carries the database's own emblem.
 *
 * <p>This decides a logo and nothing else. It must never gate content, a canonical, or
 * anything a crawler reads: every host serves the same pages with the same canonical, and
 * varying that by host is cloaking. The canonical lives in {@link
 * com.rewayaat.service.PageLocale} and points at one host whichever host answered.
 */
@ControllerAdvice
public class SiteBrandAdvice {

    /**
     * The host the Academy presents. Compared against the request's own server name rather
     * than the Host header, so a proxy that rewrites it cannot flip the masthead.
     */
    private static final String ACADEMY_HOST = "hadith.academyofislam.com";

    @ModelAttribute
    public void siteBrand(HttpServletRequest request, Model model) {
        String host = request.getServerName();
        model.addAttribute("academyHost", ACADEMY_HOST.equalsIgnoreCase(host));
    }
}
