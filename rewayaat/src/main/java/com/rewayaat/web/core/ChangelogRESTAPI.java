package com.rewayaat.web.core;

import com.rewayaat.RewayaatChangelog;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * API for working with the Rewayaat changelog.
 */

@Controller
@RequestMapping("/v1/changelog")
@Api(value = "Changelog Operations", description = "Operations pertaining to changelog information")
public class ChangelogRESTAPI {

    private static Logger log = Logger.getLogger(ChangelogRESTAPI.class.getName());


    @RequestMapping(method = RequestMethod.GET, produces = "application/json")
    @ApiOperation(value = "Retrieve Rewayaat changelog data", response = Changelog.class)
    @ApiResponses(value = { @ApiResponse(code = 200, message = "Successfully retrieved list"),
            @ApiResponse(code = 401, message = "You are not authorized to view the resource"),
            @ApiResponse(code = 403, message = "Accessing the resource you were trying to reach is forbidden"),
            @ApiResponse(code = 404, message = "The resource you were trying to reach is not found"),
            @ApiResponse(code = 500, message = "An error was encountered while processing your request") })
    @ResponseBody
    public final Changelog loadChangeLog(
            @ApiParam(name = "page", value = "page number") @RequestParam(value = "page", defaultValue = "0") int page)
            throws Exception {
        log.info("Entered changelog API with page: " + page);
        return new RewayaatChangelog(page).changelog();
    }
}
