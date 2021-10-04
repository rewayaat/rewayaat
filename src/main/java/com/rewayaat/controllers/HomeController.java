package com.rewayaat.controllers;

import org.apache.log4j.Logger;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import springfox.documentation.annotations.ApiIgnore;

import javax.servlet.http.HttpServletRequest;

/**
 * Handles requests for the home page.
 */
@ApiIgnore
@Controller
@RequestMapping(value = "/")
public class HomeController {

    private static Logger log = Logger.getLogger(HomeController.class.getName());

    @RequestMapping(method = RequestMethod.GET)
    public final String home(@RequestParam(value = "q", required = false, defaultValue = "") String query,
                             @RequestParam(value = "page", defaultValue = "1") int page,
                             @RequestParam(value = "sort_fields", required = false) String sortFields,
                             HttpServletRequest request, final Model model) {
        log.info("Entered home controller");
        log.info("Query param is " + query + ", returning query value in model.");
        model.addAttribute("query", query);
        log.info("Page param is " + page + ", returning page value in model.");
        model.addAttribute("page", page);
        log.info("Page sort_fields is " + sortFields + ", returning sort_fields value in model.");
        model.addAttribute("sort_fields", sortFields);
        return "index";
    }
}
