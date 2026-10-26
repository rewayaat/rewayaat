package com.rewayaat.controllers.rest;

import com.rewayaat.service.NarratorService;
import com.rewayaat.service.UiMessages;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The narrator API. The page itself is {@link com.rewayaat.controllers.NarratorPageController}.
 *
 * What it returns is not a judgment of ours: it is what each Rijal book says of the man, under
 * that book's name and page. The narrations endpoint the June code carried is not restored — it
 * needs every chain resolved to a person, a later stage.
 */
@Controller
public class NarratorController {

    private static final int MAX_BRIEFS = 40;

    @Autowired
    private NarratorService narratorService;

    // Even an error a reader only meets as JSON is a string a reader meets, so it comes
    // from the catalogue like every other one.
    @Autowired
    private UiMessages messages;

    @Autowired
    private com.rewayaat.service.ProminentNarrators prominent;

    // Which of the Fourteen an id names, for the two client surfaces that would otherwise
    // read «kind: imam» and call the Prophet an Imam.
    @Autowired
    private com.rewayaat.service.Honorifics honorifics;

    /**
     * The little the preview card needs, and nothing else.
     *
     * <p>A reader who taps a name in an isnād wants to know who the man was before deciding
     * to leave the hadith. The full document answers that and also carries every entry: for
     * Zurāra that is 91 KB, most of it al-Kashshī's thirty-eight pages, and no phone should
     * fetch it to show six lines. This carries the name, what the books call him, where and
     * when he lived, and how much is written about him.
     */
    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @RequestMapping(value = "/v1/narrators/{id}/preview", method = RequestMethod.GET,
                    produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> preview(
            @Parameter(name = "id", description = "The permanent narrator id, «n000515»",
                       required = true)
            @PathVariable("id") String id) {
        Map<String, Object> narrator = narratorService.getNarrator(id);
        if (narrator == null) {
            Map<String, Object> missing = new LinkedHashMap<>();
            missing.put("ok", false);
            return new ResponseEntity<>(missing, HttpStatus.NOT_FOUND);
        }
        Map<String, Object> brief = briefOf(narrator);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("narrator", brief);
        return new ResponseEntity<>(payload, HttpStatus.OK);
    }

    /**
     * The same brief for a whole isnād at once.
     *
     * <p>The rijāl panel draws every man a chain names, and asking for them one at a time
     * would be eight round trips to show one narration. The projection is the preview's
     * own, through the same helper, so a brief fetched here fills the preview card's cache
     * and the card then costs nothing. An id no narrator holds is simply absent from the
     * answer rather than failing the rest, and the list is capped because the parameter is
     * a reader's to write: no real isnād is near forty names.
     */
    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @RequestMapping(value = "/v1/narrators/briefs", method = RequestMethod.GET,
                    produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> briefs(
            @Parameter(name = "ids", description = "Comma-separated narrator ids, at most 40",
                       required = true)
            @RequestParam(value = "ids", defaultValue = "") String ids) {
        Map<String, Object> found = new LinkedHashMap<>();
        Set<String> wanted = new LinkedHashSet<>();
        for (String id : ids.split(",")) {
            String trimmed = id.trim();
            if (!trimmed.isEmpty() && wanted.size() < MAX_BRIEFS) {
                wanted.add(trimmed);
            }
        }
        for (String id : wanted) {
            Map<String, Object> narrator = narratorService.getNarrator(id);
            if (narrator != null) {
                found.put(id, briefOf(narrator));
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("narrators", found);
        return new ResponseEntity<>(payload, HttpStatus.OK);
    }

    /**
     * The fields a card needs of a man, whichever endpoint is asking.
     *
     * <p>One list, because the preview card and the rijāl panel share a browser cache and
     * a brief from either must be usable by both.
     */
    private Map<String, Object> briefOf(Map<String, Object> narrator) {
        Map<String, Object> brief = new LinkedHashMap<>();
        for (String field : new String[]{"person_id", "display_ar", "display_en", "slug",
                                         "said_by", "standing", "standing_rests_on", "life",
                                         "entry_count", "kind", "moved_to", "imam_ordinals"}) {
            if (narrator.get(field) != null) {
                brief.put(field, narrator.get(field));
            }
        }
        // Prophet, Imam or Fāṭima — the index files all three as «imam» and the card and
        // the chain panel each need to say which.
        String house = honorifics.houseOf(narrator.get("person_id"));
        if (house != null) {
            brief.put("house", house);
        }
        // The seal's ring and the card's footer: narrations his name stands in.
        brief.put("chains", prominent.chainsOf(String.valueOf(narrator.get("person_id"))));
        brief.put("book_count", narrator.get("books") instanceof List<?> books ? books.size() : 0);
        return brief;
    }

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
            missing.put("message", messages.say("api.narrator.notFound"));
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
