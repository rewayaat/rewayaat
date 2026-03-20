package com.rewayaat.controllers.rest;

import com.rewayaat.config.ESClientProvider;
import com.rewayaat.core.HadithObjectCollection;
import com.rewayaat.core.QueryMode;
import com.rewayaat.core.QueryStringQueryResult;
import com.rewayaat.core.UpdateRequest;
import com.rewayaat.core.data.HadithObject;
import com.rewayaat.core.data.UserAccount;
import com.rewayaat.service.AuthService;
import com.rewayaat.service.HadithEditorAccessService;
import com.rewayaat.service.HadithQueryService;
import com.rewayaat.service.SimilarHadithService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SearchType;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;

/**
 * API for working with narrations.
 */
@Service
@org.springframework.stereotype.Controller
@RequestMapping("/v1/narrations")
public class HadithController {

    private static final Logger LOGGER = LoggerFactory.getLogger(HadithController.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final int SEARCH_MODE_MAX_RESULTS = 50;
    private static final Set<String> NON_PERSISTED_FIELDS = Set.of(
            "_id",
            "id",
            "englishContent",
            "englishChain",
            "arabicContent",
            "arabicChain",
            "_resultOrdinal",
            "expanded",
            "detailsOpen",
            "relatedOpen",
            "similarOpen",
            "similarCount",
            "similarCountLoading",
            "similarItemsLoading",
            "similarItemsLoaded",
            "similarItems",
            "similarActiveIndex",
            "similarError",
            "similarHighlightKey",
            "similarHighlightTone",
            "_similarPrefetched");
    private static final List<String> TEXT_FIELDS = List.of(
            "source",
            "book",
            "number",
            "part",
            "edition",
            "chapter",
            "publisher",
            "section",
            "volume",
            "notes",
            "arabic",
            "english");

    @Autowired
    private HadithQueryService hadithQueryService;
    @Autowired
    private SimilarHadithService similarHadithService;
    @Autowired
    private AuthService authService;
    @Autowired
    private HadithEditorAccessService hadithEditorAccessService;

    @CrossOrigin(origins = { "*" }, allowCredentials = "false")
    @Operation(summary = "Returns a list of narrations matching the given query.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Returns a list of narrations matching the given query."),
            @ApiResponse(responseCode = "404", description = "Bad request"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    // @Cacheable(value = "queries")
    @RequestMapping(method = RequestMethod.GET, produces = "application/json")
    @ResponseBody
    public HadithObjectCollection queryHadith(
            @Parameter(name = "q", description = "The query to execute.") @RequestParam(value = "q", defaultValue = "") String query,
            @Parameter(name = "sort_fields", description = "Sort fields for lookup queries.", required = false) @RequestParam(value = "sort_fields", defaultValue = "", required = false) String sortFields,
            @Parameter(name = "mode", description = "Display mode: search (default) or read.", required = false) @RequestParam(value = "mode", defaultValue = "search", required = false) String mode,
            @Parameter(name = "match_mode", description = "Search strictness: permissive (default) or strict.", required = false) @RequestParam(value = "match_mode", defaultValue = "permissive", required = false) String matchMode,
            @Parameter(name = "page", description = "The number of the page to return.", required = false) @RequestParam(value = "page", defaultValue = "1") int page,
            @Parameter(name = "per_page", description = "Number of hadith to include per page. Maximum of 100.") @RequestParam(value = "per_page", defaultValue = "20") int perPage,
            @Parameter(name = "topic_tags", description = "Controlled topic tags that all must match.", required = false) @RequestParam(value = "topic_tags", required = false) List<String> topicTags,
            @Parameter(name = "topic_tags_any", description = "Controlled topic tags where any may match.", required = false) @RequestParam(value = "topic_tags_any", required = false) List<String> topicTagsAny)
            throws Exception {
        if (perPage > 100) {
            perPage = 100;
        }
        LOGGER.debug("Hadith query: q='{}', page={}, per_page={}, sort_fields='{}', topic_tags={}, topic_tags_any={}",
                query, page, perPage, sortFields, topicTags, topicTagsAny);
        List<SortOptions> sortBuilders = hadithQueryService.setupSortBuilders(sortFields);
        QueryMode queryMode = QueryMode.SEARCH;
        if (!sortFields.isEmpty()) {
            // Assumption: If sort values are provided, a lookup query is required.
            queryMode = QueryMode.LOOKUP;
        }
        boolean strictMatchMode = isStrictMatchMode(matchMode);
        int maxResults = isReadingMode(mode) ? 0 : SEARCH_MODE_MAX_RESULTS;
        return new QueryStringQueryResult(
                hadithQueryService.enhanceQuery(query, queryMode, strictMatchMode),
                page - 1,
                perPage,
                sortBuilders,
                strictMatchMode,
                maxResults,
                topicTags,
                topicTagsAny).result();
    }

    @CrossOrigin(origins = { "*" }, allowCredentials = "false")
    @Operation(summary = "Fetches one narration by id.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Narration returned."),
            @ApiResponse(responseCode = "404", description = "Narration not found")
    })
    @RequestMapping(value = "/{id}", method = RequestMethod.GET, produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> hadithById(
            @Parameter(name = "id", description = "The narration id.", required = true)
            @PathVariable("id") String id) throws Exception {
        HadithObject narration = loadNarration(id);
        if (narration == null) {
            return notFound("Narration not found.");
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("ok", true);
        payload.put("narration", narration);
        return new ResponseEntity<>(payload, HttpStatus.OK);
    }

    @CrossOrigin(origins = { "*" }, allowCredentials = "false")
    @Operation(summary = "Updates one narration by id.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Narration updated."),
            @ApiResponse(responseCode = "400", description = "Invalid payload"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to edit narrations"),
            @ApiResponse(responseCode = "404", description = "Narration not found")
    })
    @RequestMapping(value = "/{id}", method = RequestMethod.PUT, produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateHadith(
            @CookieValue(value = AuthService.AUTH_COOKIE, required = false) String sessionToken,
            @Parameter(name = "id", description = "The narration id.", required = true)
            @PathVariable("id") String id,
            @RequestBody(required = false) Map<String, Object> payload) throws Exception {
        UserAccount user = authService.authenticatedUser(sessionToken);
        if (user == null) {
            return unauthorized("Authentication required.");
        }
        if (hadithEditorAccessService == null || !hadithEditorAccessService.canEdit(user.getEmail())) {
            return forbidden("Your account is not allowed to edit narrations.");
        }
        String narrationId = id == null ? "" : id.trim();
        if (narrationId.isEmpty()) {
            return badRequest("Narration id is required.");
        }
        HadithObject existing = loadNarration(narrationId);
        if (existing == null) {
            return notFound("Narration not found.");
        }
        if (payload == null || payload.isEmpty()) {
            return badRequest("Narration payload is required.");
        }
        Map<String, Object> merged = new LinkedHashMap<>(JSON.convertValue(existing, Map.class));
        merged.putAll(sanitizeEditablePayload(payload));
        NON_PERSISTED_FIELDS.forEach(merged::remove);
        HadithObject updated = JSON.convertValue(merged, HadithObject.class);
        new UpdateRequest(updated, narrationId).execute();

        Map<String, Object> response = new HashMap<>();
        response.put("ok", true);
        response.put("narration", loadNarration(narrationId));
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @CrossOrigin(origins = { "*" }, allowCredentials = "false")
    @Operation(summary = "Returns narrations similar to a given narration id.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Returns similar narrations."),
            @ApiResponse(responseCode = "400", description = "Bad request"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @RequestMapping(value = "/similar", method = RequestMethod.GET, produces = "application/json")
    @ResponseBody
    public HadithObjectCollection similarHadith(
            @Parameter(name = "id", description = "The source narration id.", required = true)
            @RequestParam(value = "id") String id,
            @Parameter(name = "page", description = "The page to return.")
            @RequestParam(value = "page", defaultValue = "1", required = false) int page,
            @Parameter(name = "per_page", description = "Number of similar hadith to include per page.")
            @RequestParam(value = "per_page", defaultValue = "8", required = false) int perPage) throws Exception {
        if (page < 1) {
            page = 1;
        }
        if (perPage > 25) {
            perPage = 25;
        }
        if (perPage < 0) {
            perPage = 0;
        }
        return similarHadithService.findSimilar(id, page - 1, perPage);
    }

    @CrossOrigin(origins = { "*" }, allowCredentials = "false")
    @Operation(summary = "Finds which result page contains a target narration id for a given query and sort.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Returns page information for the target narration."),
            @ApiResponse(responseCode = "400", description = "Bad request"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @RequestMapping(value = "/page_for_id", method = RequestMethod.GET, produces = "application/json")
    @ResponseBody
    public Map<String, Object> pageForHadithId(
            @Parameter(name = "id", description = "The target narration id.", required = true)
            @RequestParam(value = "id") String id,
            @Parameter(name = "q", description = "The query scope used for navigation.", required = true)
            @RequestParam(value = "q") String query,
            @Parameter(name = "sort_fields", description = "Sort fields for lookup queries.", required = false)
            @RequestParam(value = "sort_fields", defaultValue = "", required = false) String sortFields,
            @Parameter(name = "mode", description = "Display mode: search (default) or read.", required = false)
            @RequestParam(value = "mode", defaultValue = "search", required = false) String mode,
            @Parameter(name = "match_mode", description = "Search strictness: permissive (default) or strict.", required = false)
            @RequestParam(value = "match_mode", defaultValue = "permissive", required = false) String matchMode,
            @Parameter(name = "per_page", description = "Number of hadith per page.")
            @RequestParam(value = "per_page", defaultValue = "20", required = false) int perPage) throws Exception {
        if (perPage < 1) {
            perPage = 20;
        }
        if (perPage > 100) {
            perPage = 100;
        }
        Map<String, Object> payload = new HashMap<>();
        int maxScanned = isReadingMode(mode) ? 10000 : SEARCH_MODE_MAX_RESULTS;
        payload.put("ok", true);
        payload.put("page", 1);
        payload.put("per_page", perPage);
        payload.put("found", false);
        payload.put("maxScanned", maxScanned);

        String targetId = id == null ? "" : id.trim();
        String safeQuery = query == null ? "" : query.trim();
        if (targetId.isEmpty() || safeQuery.isEmpty()) {
            return payload;
        }

        List<SortOptions> sortBuilders = hadithQueryService.setupSortBuilders(sortFields);
        QueryMode queryMode = sortFields == null || sortFields.isEmpty() ? QueryMode.SEARCH : QueryMode.LOOKUP;
        boolean strictMatchMode = isStrictMatchMode(matchMode);
        String enhancedQuery = hadithQueryService.enhanceQuery(safeQuery, queryMode, strictMatchMode);

        SearchRequest.Builder searchBuilder = new SearchRequest.Builder()
                .index(ESClientProvider.INDEX)
                .searchType(SearchType.DfsQueryThenFetch)
                .query(q -> q.bool(b -> b.should(s -> s.queryString(qs -> qs.query(enhancedQuery)))))
                .size(maxScanned)
                .source(sc -> sc.fetch(false));
        for (SortOptions sortOption : sortBuilders) {
            searchBuilder.sort(sortOption);
        }

        try (ESClientProvider provider = new ESClientProvider()) {
            SearchResponse<Map> response = provider.client().search(searchBuilder.build(), Map.class);
            List<Hit<Map>> hits = response.hits().hits();
            for (int i = 0; i < hits.size(); i++) {
                if (targetId.equals(hits.get(i).id())) {
                    payload.put("found", true);
                    payload.put("page", (i / perPage) + 1);
                    break;
                }
            }
        }
        return payload;
    }

    private boolean isStrictMatchMode(String matchMode) {
        return "strict".equalsIgnoreCase(matchMode == null ? "" : matchMode.trim());
    }

    private boolean isReadingMode(String mode) {
        return "read".equalsIgnoreCase(mode == null ? "" : mode.trim());
    }

    private HadithObject loadNarration(String id) throws Exception {
        String narrationId = id == null ? "" : id.trim();
        if (narrationId.isEmpty()) {
            return null;
        }
        try (ESClientProvider provider = new ESClientProvider()) {
            GetResponse<Map> response = provider.client().get(g -> g.index(ESClientProvider.INDEX).id(narrationId), Map.class);
            if (!response.found() || response.source() == null) {
                return null;
            }
            Map<String, Object> map = new LinkedHashMap<>(response.source());
            map.put("_id", narrationId);
            return JSON.convertValue(map, HadithObject.class);
        }
    }

    private Map<String, Object> sanitizeEditablePayload(Map<String, Object> payload) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (payload == null || payload.isEmpty()) {
            return sanitized;
        }
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            String key = entry.getKey();
            if (key == null || NON_PERSISTED_FIELDS.contains(key)) {
                continue;
            }
            sanitized.put(key, entry.getValue());
        }
        for (String field : TEXT_FIELDS) {
            if (sanitized.containsKey(field)) {
                sanitized.put(field, normalizeTextValue(sanitized.get(field)));
            }
        }
        if (sanitized.containsKey("topic_tags")) {
            sanitized.put("topic_tags", sanitizeStringList(sanitized.get("topic_tags")));
        }
        if (sanitized.containsKey("history")) {
            sanitized.put("history", sanitizeStringList(sanitized.get("history")));
        }
        if (sanitized.containsKey("tags")) {
            sanitized.put("tags", sanitizeFlexibleTagList(sanitized.get("tags")));
        }
        if (sanitized.containsKey("gradings")) {
            sanitized.put("gradings", sanitizeObjectList(sanitized.get("gradings")));
        }
        if (sanitized.containsKey("related")) {
            sanitized.put("related", sanitizeObjectList(sanitized.get("related")));
        }
        return sanitized;
    }

    private String normalizeTextValue(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }

    private List<String> sanitizeStringList(Object raw) {
        if (!(raw instanceof List<?> items)) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (Object item : items) {
            if (item == null) {
                continue;
            }
            String value = String.valueOf(item).trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return new ArrayList<>(values);
    }

    private List<Object> sanitizeFlexibleTagList(Object raw) {
        if (!(raw instanceof List<?> items)) {
            return Collections.emptyList();
        }
        List<Object> values = new ArrayList<>();
        for (Object item : items) {
            if (item == null) {
                continue;
            }
            if (item instanceof Map<?, ?> mapValue) {
                if (!mapValue.isEmpty()) {
                    values.add(new LinkedHashMap<>(mapValue));
                }
                continue;
            }
            String value = String.valueOf(item).trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    private List<Map<String, Object>> sanitizeObjectList(Object raw) {
        if (!(raw instanceof List<?> items)) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> values = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> rawMap) || rawMap.isEmpty()) {
                continue;
            }
            Map<String, Object> value = new LinkedHashMap<>();
            rawMap.forEach((key, entryValue) -> {
                if (key == null) {
                    return;
                }
                if (entryValue instanceof String strValue) {
                    String trimmed = strValue.trim();
                    if (!trimmed.isEmpty()) {
                        value.put(String.valueOf(key), trimmed);
                    }
                    return;
                }
                if (entryValue != null) {
                    value.put(String.valueOf(key), entryValue);
                }
            });
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        return response(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseEntity<Map<String, Object>> unauthorized(String message) {
        return response(HttpStatus.UNAUTHORIZED, message);
    }

    private ResponseEntity<Map<String, Object>> forbidden(String message) {
        return response(HttpStatus.FORBIDDEN, message);
    }

    private ResponseEntity<Map<String, Object>> notFound(String message) {
        return response(HttpStatus.NOT_FOUND, message);
    }

    private ResponseEntity<Map<String, Object>> response(HttpStatus status, String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("ok", false);
        payload.put("message", Objects.toString(message, ""));
        return new ResponseEntity<>(payload, status);
    }
}
