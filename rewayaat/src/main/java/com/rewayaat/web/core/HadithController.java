package com.rewayaat.web.core;

import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.rewayaat.web.data.hadith.HadithInfo;
import com.rewayaat.web.data.hadith.HadithRepository;

/**
 * API for the hadith resource.
 */
@Controller
@RequestMapping("/api/hadith")
public class HadithController {

    @Autowired
    HadithRepository hadithRepo;

    private static Logger log = Logger.getLogger(HadithController.class.getName());

    @RequestMapping(value = "/query", method = RequestMethod.GET, produces = "application/json")
    @ResponseBody
    public final List<HadithInfo> loadHadith(@RequestParam(value = "query", defaultValue = "") String query,
            @RequestParam(value = "page", defaultValue = "0") int page, HttpServletRequest request) {
        log.info("Entered hadith query API with query: " + query + " and page: " + page);
        return new QueryResult(query, hadithRepo, page).result();
    }
}
