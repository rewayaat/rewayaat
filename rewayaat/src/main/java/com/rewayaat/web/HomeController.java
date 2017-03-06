package com.rewayaat.web;

import javax.servlet.http.HttpServletRequest;

import org.apache.log4j.Logger;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Handles requests for the home page.
 */
@Controller
public class HomeController {

    private static Logger log = Logger.getLogger(HomeController.class.getName());

    @RequestMapping(value = "/", method = RequestMethod.GET)
    public final String home(@RequestParam(value = "query", required = false) String query,
            HttpServletRequest request, final Model model) {
        log.info("Entered home controller");
        if (query != null && !query.isEmpty()) {
            // returning the query as a model attribute allows the front end to
            // consume the rewayaat REST API using that query and display the
            // results.
            log.info("Query param is " + query + ", returning query value in model.");
            model.addAttribute("query", query);
        }
        return "index";
    }
}
