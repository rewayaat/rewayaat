package com.rewayaat.controllers.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.config.ESClientProvider;
import com.rewayaat.core.HadithObjectCollection;
import com.rewayaat.core.QueryMode;
import com.rewayaat.core.QueryStringQueryResult;
import com.rewayaat.core.UpdateRequest;
import com.rewayaat.core.User;
import com.rewayaat.core.data.HadithObject;
import com.rewayaat.service.HadithQueryService;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.search.sort.SortBuilder;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import springfox.documentation.annotations.ApiIgnore;

import javax.naming.AuthenticationException;
import javax.servlet.http.HttpServletRequest;
import java.util.Iterator;
import java.util.List;

/**
 * API for working with narrations.
 */
@Service
@org.springframework.stereotype.Controller
@RequestMapping("/v1/narrations")
public class HadithController {

    private static final Logger LOGGER = LoggerFactory.getLogger(HadithController.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private HadithQueryService hadithQueryService;

    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @ApiOperation(
            value = "Returns a list of narrations matching the given query.",
            response = HadithObjectCollection.class
    )
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Returns a list of narrations matching the given query."),
            @ApiResponse(code = 404, message = "Bad request"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    //@Cacheable(value = "queries")
    @RequestMapping(method = RequestMethod.GET, produces = "application/json")
    @ResponseBody
    public HadithObjectCollection queryHadith(
            @ApiParam(name = "q", value = "The query to execute.")
            @RequestParam(value = "q", defaultValue = "") String query,
            @ApiParam(name = "sort_fields", hidden = true, required = false)
            @RequestParam(value = "sort_fields", defaultValue = "", required = false) String sortFields,
            @ApiParam(name = "page", value = "The number of the page to return.", required = false)
            @RequestParam(value = "page", defaultValue = "1") int page,
            @ApiParam(name = "per_page", value = "Number of hadith to include per page. Maximum of 100.")
            @RequestParam(value = "per_page", defaultValue = "20") int perPage) throws Exception {
        if (perPage > 100) {
            perPage = 100;
        }
        LOGGER.info("Entered hadith query API with query: " + query + ", page: " + page
                        + ", per_page: " + perPage + " and sort_fields: " + sortFields);
        List<SortBuilder> sortBuilders = hadithQueryService.setupSortBuilders(sortFields);
        QueryMode queryMode = QueryMode.SEARCH;
        if (!sortFields.isEmpty()) {
            // Assumption: If sort values are provided, a lookup query is required.
            queryMode = QueryMode.LOOKUP;
        }
        return new QueryStringQueryResult(
            hadithQueryService.enhanceQuery(query, queryMode),
            page - 1,
            perPage,
            sortBuilders).result();
    }

    @ApiIgnore
    @RequestMapping(method = RequestMethod.POST, consumes = "application/json")
    public ResponseEntity<String> modifyHadith(
            @RequestParam(value = "id_token", required = true) String idToken,
            @RequestParam(value = "hadith_id", required = true) String hadithId,
            @RequestBody String modifiedHadithStr, HttpServletRequest req) throws Exception {
        try {
            if (new User(idToken).isAdmin()) {
                modifiedHadithStr = Jsoup.parse(modifiedHadithStr).text();
                LOGGER.info("Received Modification Request for hadith: " + hadithId);
                JSONObject modifiedHadith = new JSONObject(modifiedHadithStr);
                GetResponse response =
                    ESClientProvider.instance().getClient().prepareGet(
                        ESClientProvider.INDEX, "_doc", hadithId)
                                    .get();
                String responseStr = new JSONObject(new String(response.getSourceAsBytes())).toString(2);
                LOGGER.info("Original hadith is:\n" + responseStr);
                LOGGER.info("Modification request:\n" + modifiedHadith.toString(2));
                JSONObject existingHadith = new JSONObject(responseStr);
                // add all the values from the modification object to the stored hadith object
                Iterator<?> keys = modifiedHadith.keys();
                while (keys.hasNext()) {
                    String key = (String) keys.next();
                    existingHadith.put(key, modifiedHadith.get(key));
                }
                // make sure we can still serialize a valid HadithObject from the new JSON data
                HadithObject newHadithObject = mapper.readValue(existingHadith.toString(), HadithObject.class);
                newHadithObject.insertHistoryNote(
                    "User " + new User(idToken).email() + " "
                        + "modified this hadith on " + new java.util.Date() + ". The original hadith:\n"
                        + responseStr + "\n\n The following properties were modified and saved to the database:\n\n" + modifiedHadithStr);
                new UpdateRequest(newHadithObject, hadithId).execute();
                // clear the cache
                cacheManager.getCacheNames().parallelStream().forEach(name -> cacheManager.getCache(name).clear());
                return new ResponseEntity<>("Successfully updated hadith: " + hadithId,
                                            HttpStatus.OK);
            } else {
                throw new AuthenticationException("Unauthorized to modify hadith: " + hadithId);
            }
        } catch (Exception e) {
            LOGGER.error("Unable to modify hadith: " + hadithId, e);
            throw e;
        }
    }
}
