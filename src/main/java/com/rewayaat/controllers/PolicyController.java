package com.rewayaat.controllers;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The privacy policy.
 *
 * <p>A page rather than a static file, so that it carries the site's own navigation and
 * footer from the shared fragments instead of a copy of them that drifts.
 *
 * <p>It exists because publishing the MCP connector to either app directory requires a
 * published privacy policy, but it describes the whole site: someone who follows the link
 * from a connector listing is owed an account of what the site does with an account, not
 * only what the connector does without one.
 */
@Hidden
@Controller
public class PolicyController {

    private static final String BASE_URL = "https://hadith.academyofislam.com";

    @GetMapping("/privacy")
    public String privacy(Model model) {
        model.addAttribute("seoTitle", "Privacy — Rewayaat");
        model.addAttribute("seoDescription",
                "What Rewayaat records and who else sees it. Reading and searching need no "
                + "account; the MCP connector is read-only and unauthenticated.");
        model.addAttribute("canonicalUrl", BASE_URL + "/privacy");
        return "privacy";
    }
}
