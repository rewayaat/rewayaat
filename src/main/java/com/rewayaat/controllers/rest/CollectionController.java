package com.rewayaat.controllers.rest;

import com.rewayaat.core.HadithObjectCollection;
import com.rewayaat.core.data.UserAccount;
import com.rewayaat.core.data.UserCollection;
import com.rewayaat.service.AuthService;
import com.rewayaat.service.UserCollectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * APIs for user-owned hadith collections.
 */
@Service
@org.springframework.stereotype.Controller
@RequestMapping("/v1/collections")
public class CollectionController {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserCollectionService userCollectionService;

    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @Operation(summary = "List collections for the logged-in user.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Collections returned."),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @RequestMapping(method = RequestMethod.GET, produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> listCollections(
            @CookieValue(value = AuthService.AUTH_COOKIE, required = false) String sessionToken) throws Exception {
        UserAccount user = authService.authenticatedUser(sessionToken);
        if (user == null) {
            return unauthorized();
        }
        List<UserCollection> collections = userCollectionService.listCollections(user.getEmail());
        Map<String, Object> payload = new HashMap<>();
        payload.put("ok", true);
        payload.put("collections", collections);
        return new ResponseEntity<>(payload, HttpStatus.OK);
    }

    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @Operation(summary = "Fetch one collection for the logged-in user.")
    @RequestMapping(value = "/{id}", method = RequestMethod.GET, produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getCollection(
            @CookieValue(value = AuthService.AUTH_COOKIE, required = false) String sessionToken,
            @PathVariable("id") String collectionId) throws Exception {
        UserAccount user = authService.authenticatedUser(sessionToken);
        if (user == null) {
            return unauthorized();
        }
        UserCollection collection = userCollectionService.getCollection(user.getEmail(), collectionId);
        Map<String, Object> payload = new HashMap<>();
        if (collection == null) {
            payload.put("ok", false);
            payload.put("message", "Collection not found.");
            return new ResponseEntity<>(payload, HttpStatus.NOT_FOUND);
        }
        payload.put("ok", true);
        payload.put("collection", collection);
        return new ResponseEntity<>(payload, HttpStatus.OK);
    }

    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @Operation(summary = "Create a new user collection.")
    @RequestMapping(method = RequestMethod.POST, produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> createCollection(
            @CookieValue(value = AuthService.AUTH_COOKIE, required = false) String sessionToken,
            @RequestBody Map<String, String> payload) throws Exception {
        UserAccount user = authService.authenticatedUser(sessionToken);
        if (user == null) {
            return unauthorized();
        }
        UserCollection collection = userCollectionService.createCollection(user.getEmail(), valueStringMap(payload, "name"));
        Map<String, Object> response = new HashMap<>();
        response.put("ok", true);
        response.put("collection", collection);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @Operation(summary = "Quick-save a hadith to a named collection.")
    @RequestMapping(value = "/quick-save", method = RequestMethod.POST, produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> quickSave(
            @CookieValue(value = AuthService.AUTH_COOKIE, required = false) String sessionToken,
            @RequestBody Map<String, String> payload) throws Exception {
        UserAccount user = authService.authenticatedUser(sessionToken);
        if (user == null) {
            return unauthorized();
        }
        String hadithId = valueStringMap(payload, "hadithId");
        if (hadithId.isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("ok", false);
            error.put("message", "hadithId is required.");
            return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
        }
        UserCollection collection = userCollectionService.quickSaveHadith(
                user.getEmail(),
                valueStringMap(payload, "collectionName"),
                hadithId);
        Map<String, Object> response = new HashMap<>();
        response.put("ok", true);
        response.put("collection", collection);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @Operation(summary = "Quick-save multiple hadith to a named collection.")
    @RequestMapping(value = "/quick-save-bulk", method = RequestMethod.POST, produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> quickSaveBulk(
            @CookieValue(value = AuthService.AUTH_COOKIE, required = false) String sessionToken,
            @RequestBody Map<String, Object> payload) throws Exception {
        UserAccount user = authService.authenticatedUser(sessionToken);
        if (user == null) {
            return unauthorized();
        }
        List<String> hadithIds = stringListValue(payload, "hadithIds");
        if (hadithIds.isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("ok", false);
            error.put("message", "hadithIds is required.");
            return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
        }
        UserCollection collection = userCollectionService.quickSaveHadithBatch(
                user.getEmail(),
                valueObjectMap(payload, "collectionName"),
                hadithIds);
        Map<String, Object> response = new HashMap<>();
        response.put("ok", true);
        response.put("savedCount", hadithIds.size());
        response.put("collection", collection);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @Operation(summary = "Delete a collection.")
    @RequestMapping(value = "/{id}", method = RequestMethod.DELETE, produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteCollection(
            @CookieValue(value = AuthService.AUTH_COOKIE, required = false) String sessionToken,
            @PathVariable("id") String collectionId) throws Exception {
        UserAccount user = authService.authenticatedUser(sessionToken);
        if (user == null) {
            return unauthorized();
        }
        boolean deleted = userCollectionService.deleteCollection(user.getEmail(), collectionId);
        Map<String, Object> response = new HashMap<>();
        response.put("ok", deleted);
        if (!deleted) {
            response.put("message", "Collection not found.");
            return new ResponseEntity<>(response, HttpStatus.NOT_FOUND);
        }
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @Operation(summary = "Remove a hadith from a collection.")
    @RequestMapping(value = "/{id}/hadith/{hadithId}", method = RequestMethod.DELETE, produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> removeHadithFromCollection(
            @CookieValue(value = AuthService.AUTH_COOKIE, required = false) String sessionToken,
            @PathVariable("id") String collectionId,
            @PathVariable("hadithId") String hadithId) throws Exception {
        UserAccount user = authService.authenticatedUser(sessionToken);
        if (user == null) {
            return unauthorized();
        }
        UserCollection collection = userCollectionService.removeHadith(user.getEmail(), collectionId, hadithId);
        Map<String, Object> response = new HashMap<>();
        if (collection == null) {
            response.put("ok", false);
            response.put("message", "Collection not found.");
            return new ResponseEntity<>(response, HttpStatus.NOT_FOUND);
        }
        response.put("ok", true);
        response.put("collection", collection);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @Operation(summary = "Fetch hadiths in a collection.")
    @RequestMapping(value = "/{id}/hadith", method = RequestMethod.GET, produces = "application/json")
    @ResponseBody
    public ResponseEntity<HadithObjectCollection> collectionHadith(
            @CookieValue(value = AuthService.AUTH_COOKIE, required = false) String sessionToken,
            @PathVariable("id") String collectionId,
            @RequestParam(value = "page", defaultValue = "1", required = false) int page,
            @RequestParam(value = "per_page", defaultValue = "20", required = false) int perPage,
            @RequestParam(value = "topic_tags", required = false) List<String> topicTags) throws Exception {
        UserAccount user = authService.authenticatedUser(sessionToken);
        if (user == null) {
            return new ResponseEntity<>(new HadithObjectCollection(new java.util.ArrayList<>(), 0), HttpStatus.UNAUTHORIZED);
        }
        if (page < 1) {
            page = 1;
        }
        if (perPage < 1) {
            perPage = 1;
        }
        if (perPage > 100) {
            perPage = 100;
        }
        return new ResponseEntity<>(
                userCollectionService.collectionHadith(user.getEmail(), collectionId, page - 1, perPage, topicTags),
                HttpStatus.OK);
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("ok", false);
        payload.put("message", "Authentication required.");
        return new ResponseEntity<>(payload, HttpStatus.UNAUTHORIZED);
    }

    private String valueStringMap(Map<String, String> payload, String key) {
        if (payload == null) {
            return "";
        }
        String value = payload.get(key);
        return value == null ? "" : value.trim();
    }

    private String valueObjectMap(Map<String, Object> payload, String key) {
        if (payload == null) {
            return "";
        }
        Object value = payload.get(key);
        if (value == null) {
            return "";
        }
        return String.valueOf(value).trim();
    }

    private List<String> stringListValue(Map<String, Object> payload, String key) {
        List<String> values = new ArrayList<>();
        if (payload == null) {
            return values;
        }
        Object raw = payload.get(key);
        if (!(raw instanceof List<?>)) {
            return values;
        }
        for (Object item : (List<?>) raw) {
            if (item == null) {
                continue;
            }
            String value = String.valueOf(item).trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }
}
