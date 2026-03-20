package com.rewayaat.controllers.rest;

import com.rewayaat.core.BrowseFacets;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * API for browsing books and sections.
 */
@Service
@org.springframework.stereotype.Controller
@RequestMapping("/v1/browse")
public class BrowseController {

    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @Operation(summary = "Returns all books with counts.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Returns all books with counts."),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @RequestMapping(value = "/books", method = RequestMethod.GET, produces = "application/json")
    public ResponseEntity<String> books() throws Exception {
        return new ResponseEntity<>(new BrowseFacets().books().toString(), HttpStatus.OK);
    }

    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @Operation(summary = "Returns facets (section, chapter, volume, part) for a given book.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Returns facets for a given book."),
            @ApiResponse(responseCode = "400", description = "Bad request"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @RequestMapping(value = "/facets", method = RequestMethod.GET, produces = "application/json")
    public ResponseEntity<String> facets(
            @Parameter(name = "book", description = "Book name to fetch facets for.")
            @RequestParam(value = "book", required = true) String book,
            @Parameter(name = "volume", description = "Optional volume filter.")
            @RequestParam(value = "volume", required = false) String volume,
            @Parameter(name = "part", description = "Optional part filter.")
            @RequestParam(value = "part", required = false) String part,
            @Parameter(name = "section", description = "Optional section filter.")
            @RequestParam(value = "section", required = false) String section,
            @Parameter(name = "chapter", description = "Optional chapter filter.")
            @RequestParam(value = "chapter", required = false) String chapter) throws Exception {
        if (book == null || book.trim().isEmpty()) {
            return new ResponseEntity<>("Book is required.", HttpStatus.BAD_REQUEST);
        }
        return new ResponseEntity<>(new BrowseFacets().facets(book, volume, part, section, chapter).toString(),
                HttpStatus.OK);
    }
}
