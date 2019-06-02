package com.rewayaat.controllers.rest;

import com.rewayaat.RewayaatLogger;
import com.rewayaat.core.DatabaseTopTerms;
import com.rewayaat.core.HighlySignificantTerms;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.apache.log4j.spi.LoggerFactory;
import org.hibernate.validator.constraints.Range;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * API for working with narrations.
 */
@Service
@org.springframework.stereotype.Controller
@RequestMapping("/v1/terms")
public class TermsController {

    private static org.apache.log4j.Logger log = org.apache.log4j.Logger.getLogger(TermsController.class.getName(), new LoggerFactory() {
        @Override
        public org.apache.log4j.Logger makeNewLoggerInstance(String name) {
            return new RewayaatLogger(name);
        }
    });

    @Autowired
    private CacheManager cacheManager;

    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @ApiOperation(
            value = "Returns a list of top terms in the database based on the given prefix term.",
            response = List.class
    )
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Returns a list of top terms in the database based on the given prefix term."),
            @ApiResponse(code = 400, message = "Bad request"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    @Cacheable(value = "topterms")
    @RequestMapping(value = "/top", method = RequestMethod.GET, produces = "application/json")
    public ResponseEntity<String> topTerms(
            @ApiParam(name = "size", value = "Number of top terms to include.")
            @RequestParam(value = "size", defaultValue = "10") @Range(min = 1, max = 10) int size,
            @ApiParam(name = "term", value = "Term to filter results by, must be a minimum length of 2.")
            @RequestParam(value = "term", required = true) String prefix) throws Exception {

        if (prefix.length() < 2) {
            return new ResponseEntity<>("Prefix does not meet minimum length requirements!", HttpStatus.BAD_REQUEST);
        } else {
            return new ResponseEntity<>(new DatabaseTopTerms(size, prefix).terms().toString(), HttpStatus.OK);
        }
    }


    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @ApiOperation(
            value = "Returns a list of highly significant terms in the database.",
            response = List.class
    )
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Returns a list of highly significant terms in the database."),
            @ApiResponse(code = 400, message = "Bad request"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    @Cacheable(value = "significantterms")
    @RequestMapping(value = "/significant", method = RequestMethod.GET, produces = "application/json")
    public ResponseEntity<String> significantTerms(
            @ApiParam(name = "size", value = "Number of significant terms to include.")
            @RequestParam(value = "size", defaultValue = "5") @Range(min = 1, max = 10) int size,
            @ApiParam(name = "inputTerms", value = "Comma separated list of input terms for which to retrieve highly significant terms.")
            @RequestParam(value = "inputTerms", required = true) String inputTerms) throws Exception {

        String[] inputTermArr = inputTerms.split(",");
        if (inputTermArr.length < 1) {
            return new ResponseEntity<>("Input Terms parameter is empty!", HttpStatus.BAD_REQUEST);
        } else {
            return new ResponseEntity<>(new HighlySignificantTerms(size, inputTermArr).terms().toString(), HttpStatus.OK);
        }
    }
}
