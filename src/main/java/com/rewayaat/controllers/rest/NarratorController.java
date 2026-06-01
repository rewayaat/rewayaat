package com.rewayaat.controllers.rest;

import com.rewayaat.core.HadithObjectCollection;
import com.rewayaat.core.data.NarratorDocument;
import com.rewayaat.service.NarratorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST API and page controller for narrator documents.
 */
@Controller
public class NarratorController {

    private static final Logger LOGGER = LoggerFactory.getLogger(NarratorController.class);

    @Autowired
    private NarratorService narratorService;

    // ---- Page endpoint ----

    @Hidden
    @RequestMapping(value = "/narrator/{id}", method = RequestMethod.GET)
    public String narratorPage(
            @PathVariable("id") String id,
            @RequestParam(value = "page", defaultValue = "1") int page,
            Model model) {
        model.addAttribute("narratorId", id);
        model.addAttribute("page", page);
        return "narrator";
    }

    // ---- REST API endpoints ----

    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @Operation(summary = "Get a narrator by ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Narrator returned"),
            @ApiResponse(responseCode = "404", description = "Narrator not found")
    })
    @RequestMapping(value = "/v1/narrators/{id}", method = RequestMethod.GET, produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getNarrator(
            @Parameter(name = "id", description = "The narrator ID", required = true)
            @PathVariable("id") String id) {
        NarratorDocument narrator = narratorService.getNarrator(id);
        if (narrator == null) {
            return notFound("Narrator not found.");
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("ok", true);
        payload.put("narrator", narrator);
        return new ResponseEntity<>(payload, HttpStatus.OK);
    }

    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @Operation(summary = "Search hadiths narrated by a specific narrator")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Returns narrations"),
            @ApiResponse(responseCode = "404", description = "Narrator not found")
    })
    @RequestMapping(value = "/v1/narrators/{id}/narrations", method = RequestMethod.GET, produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> narratorNarrations(
            @Parameter(name = "id", description = "The narrator ID", required = true)
            @PathVariable("id") String id,
            @Parameter(name = "page", description = "Page number")
            @RequestParam(value = "page", defaultValue = "1") int page,
            @Parameter(name = "per_page", description = "Results per page")
            @RequestParam(value = "per_page", defaultValue = "20") int perPage) throws Exception {
        if (perPage > 100) {
            perPage = 100;
        }
        NarratorDocument narrator = narratorService.getNarrator(id);
        if (narrator == null) {
            return notFound("Narrator not found.");
        }
        HadithObjectCollection narrations = narratorService.searchHadithsByNarrator(id, page - 1, perPage);
        Map<String, Object> payload = new HashMap<>();
        payload.put("ok", true);
        payload.put("narrator", narrator);
        payload.put("narrations", narrations);
        return new ResponseEntity<>(payload, HttpStatus.OK);
    }

    private ResponseEntity<Map<String, Object>> notFound(String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("ok", false);
        payload.put("message", message);
        return new ResponseEntity<>(payload, HttpStatus.NOT_FOUND);
    }
}
