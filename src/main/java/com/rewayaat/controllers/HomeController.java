package com.rewayaat.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Handles requests for the home page.
 */
@Hidden
@Controller
@RequestMapping(value = "/")
public class HomeController {

    private static final Logger log = LoggerFactory.getLogger(HomeController.class);

    @RequestMapping(method = RequestMethod.GET)
    public final String home(@RequestParam(value = "q", required = false, defaultValue = "") String query,
                             @RequestParam(value = "page", defaultValue = "1") int page,
                             @RequestParam(value = "sort_fields", required = false) String sortFields,
                             HttpServletRequest request, final Model model) {
        log.debug("Home request: query='{}', page={}, sort_fields='{}'", query, page, sortFields);
        model.addAttribute("query", query);
        model.addAttribute("page", page);
        model.addAttribute("sort_fields", sortFields);
        return "index";
    }

    @RequestMapping(value = "/auth/verify", method = RequestMethod.GET)
    public String verifyRedirect(@RequestParam(value = "token", required = false, defaultValue = "") String token) {
        return redirectToSigninWithToken("verify_token", token);
    }

    @RequestMapping(value = "/auth/reset", method = RequestMethod.GET)
    public String resetRedirect(@RequestParam(value = "token", required = false, defaultValue = "") String token) {
        return redirectToSigninWithToken("reset_token", token);
    }

    @RequestMapping(value = "/edit", method = RequestMethod.GET)
    public String editPage(@RequestParam(value = "id", required = false) String id, Model model) {
        if (id != null && !id.isBlank()) {
            model.addAttribute("hadithId", id);
        }
        return "edit";
    }

    private String redirectToSigninWithToken(String queryKey, String token) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/signin.html");
        if (token != null && !token.isBlank()) {
            builder.queryParam(queryKey, token);
        }
        return "redirect:" + builder.build().encode().toUriString();
    }
}
