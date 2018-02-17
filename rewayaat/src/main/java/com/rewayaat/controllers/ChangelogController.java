package com.rewayaat.controllers;

import org.apache.log4j.Logger;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import javax.servlet.http.HttpServletRequest;

/**
 * Redirects to the changelog controllers page.
 */
@Controller
public class ChangelogController {

    private static Logger log = Logger.getLogger(HomeController.class.getName());

    @RequestMapping(value = "/changelog", method = RequestMethod.GET)
    public final String home(HttpServletRequest request, final Model model) {
        log.info("Entered changelog controller");
        return "changelog";
    }
}
