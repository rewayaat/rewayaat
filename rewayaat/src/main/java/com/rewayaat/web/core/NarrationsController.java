package com.rewayaat.web.core;

import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.apache.log4j.Logger;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.rewayaat.web.data.hadith.HadithObject;

/**
 * API for working with narrations.
 */
@Controller
@RequestMapping("/api/narrations")
public class NarrationsController {

    private static Logger log = Logger.getLogger(NarrationsController.class.getName());

    @RequestMapping(method = RequestMethod.GET, produces = "application/json")
    @ResponseBody
    public final List<HadithObject> loadHadith(@RequestParam(value = "q", defaultValue = "") String query,
            @RequestParam(value = "page", defaultValue = "0") int page, HttpServletRequest request) throws Exception {
        log.info("Entered hadith query API with query: " + query + " and page: " + page);
        return new QueryStringQueryResult(new RewayaatQuery(query).query(), page).result();
    }
}
