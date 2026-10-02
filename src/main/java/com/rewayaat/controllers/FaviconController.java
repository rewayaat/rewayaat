package com.rewayaat.controllers;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Duration;

/**
 * Serves the tab icon the host is entitled to.
 *
 * <p>hadith.academyofislam.com keeps the Academy's icon. Every other host gets the
 * database's own emblem, the same mark the masthead carries there.
 *
 * <p>A controller rather than a {@code <link rel="icon">} in the templates. Eleven
 * templates carry their own {@code <head>} rather than sharing one, so a link tag would
 * have to be repeated - and missed - in each of them, while a browser asks for
 * {@code /favicon.ico} on its own whatever the page says. One mapping covers every page,
 * including the ones nobody remembers to update.
 *
 * <p>This maps the conventional path for both, so the icon is replaced rather than added
 * to: a second icon at a different path would leave the old one still being served.
 */
@Controller
public class FaviconController {

    private static final String ACADEMY_HOST = "hadith.academyofislam.com";
    private static final Resource ACADEMY_ICON = new ClassPathResource("static/favicon.ico");
    private static final Resource OWN_ICON = new ClassPathResource("static/img/hdb-favicon.png");

    @GetMapping("/favicon.ico")
    public ResponseEntity<Resource> favicon(HttpServletRequest request) {
        boolean academy = ACADEMY_HOST.equalsIgnoreCase(request.getServerName());
        Resource icon = academy ? ACADEMY_ICON : OWN_ICON;
        if (!icon.exists()) {
            return ResponseEntity.notFound().build();
        }
        // Vary on nothing: the icon is chosen by host, and a host is a separate cache key
        // in every cache that matters, so the two never collide.
        return ResponseEntity.ok()
                .contentType(academy ? MediaType.valueOf("image/x-icon") : MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .body(icon);
    }
}
