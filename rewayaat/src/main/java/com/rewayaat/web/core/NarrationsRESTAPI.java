package com.rewayaat.web.core;

import org.apache.log4j.Logger;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.rewayaat.web.data.hadith.HadithObject;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;

/**
 * API for working with narrations.
 */

@RestController
@RequestMapping("/v1/narrations")
@Api(value = "Narrations Database", description = "Operations pertaining to querying narrations")
public class NarrationsRESTAPI {

    private static Logger log = Logger.getLogger(NarrationsRESTAPI.class.getName());

    @RequestMapping(method = RequestMethod.GET, produces = "application/json")
    @ApiOperation(value = "Execute a query against the Rewayaat Hadith Database", response = HadithObject.class, responseContainer = "List")
    @ResponseBody
    public final HadithObjectCollection loadHadith(
            @ApiParam(name = "q", value = "Valid Elastic Search query string query to execute") @RequestParam(value = "q", defaultValue = "") String query,
            @ApiParam(name = "page", value = "page number") @RequestParam(value = "page", defaultValue = "0") int page,
            Model model) throws Exception {
        log.info("Entered hadith query API with query: " + query + " and page: " + page);
        return new QueryStringQueryResult(query, page).result();
    }
}
