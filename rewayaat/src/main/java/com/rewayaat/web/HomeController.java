package com.rewayaat.web;

import java.util.Locale;

import javax.servlet.http.HttpServletRequest;

import org.apache.log4j.Logger;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * Handles requests for the home page.
 */
@Controller
public class HomeController {

    private static Logger log = Logger.getLogger(HomeController.class.getName());

    @RequestMapping(value = "/", method = RequestMethod.GET)
    public final String home(final Locale locale, HttpServletRequest request, final Model model) {

        log.info("Entered home controller, returning index.jsp");
        return "index";
    }
}
