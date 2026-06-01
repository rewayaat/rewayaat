package com.rewayaat.controllers.rest;

import com.rewayaat.core.DatabaseTopTerms;
import com.rewayaat.core.HighlySignificantTerms;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;

/**
 * API for working with narrations.
 */
@Service
@org.springframework.stereotype.Controller
@RequestMapping("/v1/terms")
public class TermsController {

    private static final Logger LOGGER = LoggerFactory.getLogger(TermsController.class);

    @Autowired
    private CacheManager cacheManager;

    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @Operation(summary = "Returns a list of top terms in the database based on the given prefix term.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Returns a list of top terms in the database based on the given prefix term."),
            @ApiResponse(responseCode = "400", description = "Bad request"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @Cacheable(value = "topterms")
    @RequestMapping(value = "/top", method = RequestMethod.GET, produces = "application/json")
    public ResponseEntity<String> topTerms(
            @Parameter(name = "size", description = "Number of top terms to include.")
            @RequestParam(value = "size", defaultValue = "10") @Min(1) @Max(10) int size,
            @Parameter(name = "term", description = "Term to filter results by, must be a minimum length of 2.")
            @RequestParam(value = "term", required = true) String prefix) throws Exception {

        if (prefix.length() < 2) {
            return new ResponseEntity<>("Prefix does not meet minimum length requirements!", HttpStatus.BAD_REQUEST);
        } else {
            return new ResponseEntity<>(new DatabaseTopTerms(size, prefix).terms().toString(), HttpStatus.OK);
        }
    }


    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @Operation(summary = "Returns a list of highly significant terms in the database based on the given input terms.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Returns a list of highly significant terms in the database."),
            @ApiResponse(responseCode = "400", description = "Bad request"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @Cacheable(value = "significantterms")
    @RequestMapping(value = "/significant", method = RequestMethod.GET, produces = "application/json")
    public ResponseEntity<String> significantTerms(
            @Parameter(name = "size", description = "Number of significant terms to include.")
            @RequestParam(value = "size", defaultValue = "5") @Min(1) @Max(10) int size,
            @Parameter(name = "inputTerms", description = "Comma separated list of input terms for which to retrieve highly significant terms.")
            @RequestParam(value = "inputTerms", required = true) String inputTerms) throws Exception {

        size = Math.max(1, Math.min(size, 3));
        String[] inputTermArr = inputTerms.split(",");
        if (inputTermArr.length < 1) {
            return new ResponseEntity<>("Input Terms parameter is empty!", HttpStatus.BAD_REQUEST);
        } else {
            return new ResponseEntity<>(new HighlySignificantTerms(size, inputTermArr)
                                            .terms().toString(), HttpStatus.OK);
        }
    }
}
