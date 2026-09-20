package com.rewayaat.controllers.rest;

import com.rewayaat.service.NarratorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The narrator API. The page itself is {@link com.rewayaat.controllers.NarratorPageController}.
 *
 * What it returns is not a judgment of ours: it is what each Rijal book says of the man, under
 * that book's name and page. The narrations endpoint the June code carried is not restored — it
 * needs every chain resolved to a person, a later stage.
 */
@Controller
public class NarratorController {

    @Autowired
    private NarratorService narratorService;

    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @Operation(summary = "Get a narrator by his permanent id")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The narrator, with what each book says of him"),
            @ApiResponse(responseCode = "404", description = "No narrator of that id")
    })
    @RequestMapping(value = "/v1/narrators/{id}", method = RequestMethod.GET, produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getNarrator(
            @Parameter(name = "id", description = "The permanent narrator id, «n000515»", required = true)
            @PathVariable("id") String id) {
        Map<String, Object> narrator = narratorService.getNarrator(id);
        if (narrator == null) {
            Map<String, Object> missing = new LinkedHashMap<>();
            missing.put("ok", false);
            missing.put("message", "No narrator of that id.");
            return new ResponseEntity<>(missing, HttpStatus.NOT_FOUND);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("narrator", narrator);
        return new ResponseEntity<>(payload, HttpStatus.OK);
    }

    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @Operation(summary = "Search narrators by any name a book heads them under")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Matching narrators"))
    @RequestMapping(value = "/v1/narrators", method = RequestMethod.GET, produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> searchNarrators(
            @Parameter(name = "q", description = "A name, in Arabic or transliterated")
            @RequestParam(value = "q", defaultValue = "") String query,
            @Parameter(name = "page", description = "Page number, from 1")
            @RequestParam(value = "page", defaultValue = "1") int page,
            @Parameter(name = "per_page", description = "Results per page, at most 50")
            @RequestParam(value = "per_page", defaultValue = "20") int perPage) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.putAll(narratorService.search(query, page, perPage));
        return new ResponseEntity<>(payload, HttpStatus.OK);
    }
}
