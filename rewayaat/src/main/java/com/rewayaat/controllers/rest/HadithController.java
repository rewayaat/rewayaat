package com.rewayaat.controllers.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.RewayaatLogger;
import com.rewayaat.config.ClientProvider;
import com.rewayaat.core.HadithObjectCollection;
import com.rewayaat.core.QueryStringQueryResult;
import com.rewayaat.core.UpdateRequest;
import com.rewayaat.core.User;
import com.rewayaat.core.data.HadithObject;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.apache.log4j.spi.LoggerFactory;
import org.elasticsearch.action.get.GetResponse;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
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

/**
 * API for working with narrations.
 */
@Service
@org.springframework.stereotype.Controller
@RequestMapping("/v1/narrations")
public class HadithController {

    private static org.apache.log4j.Logger log = org.apache.log4j.Logger.getLogger(HadithController.class.getName(), new LoggerFactory() {
        @Override
        public org.apache.log4j.Logger makeNewLoggerInstance(String name) {
            return new RewayaatLogger(name);
        }
    });

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private CacheManager cacheManager;

    @CrossOrigin(origins = {"*"}, allowCredentials = "false")
    @ApiOperation(
<<<<<<< HEAD
            value = "Returns a list of narrations matching the given query.",
=======
            value = "Queries the hadith database.",
>>>>>>> c858816f79227837fd39ba664a1c576aa395c511
            response = HadithObjectCollection.class
    )
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Returns a list of narrations matching the given query."),
            @ApiResponse(code = 404, message = "Bad request"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    @Cacheable(value = "queries")
    @RequestMapping(method = RequestMethod.GET, produces = "application/json")
    @ResponseBody
    public HadithObjectCollection queryHadith(
            @ApiParam(name = "q", value = "The query to execute.")
            @RequestParam(value = "q", defaultValue = "") String query,
            @ApiParam(name = "page", value = "The number of the page to return.")
            @RequestParam(value = "page", defaultValue = "1") int page,
            @ApiParam(name = "per_page", value = "Number of hadith to include per page. Maximum of 100.")
            @RequestParam(value = "per_page", defaultValue = "20") int perPage) throws Exception {

        if (perPage > 100) {
            perPage = 100;
        }
        log.info("Entered hadith query API with query: " + query + " and page: " + page + " and per_page: " + perPage);
        return new QueryStringQueryResult(query, page - 1, perPage).result();
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
                log.info("Recieved Modification Request for hadith: " + hadithId);
                JSONObject modifiedHadith = new JSONObject(modifiedHadithStr);
                GetResponse response = ClientProvider.instance().getClient().prepareGet(ClientProvider.INDEX, ClientProvider.TYPE, hadithId)
                        .setOperationThreaded(false)
                        .get();
                String responseStr = new JSONObject(new String(response.getSourceAsBytes())).toString(2);
                log.info("Original hadith is:\n" + responseStr);
                log.info("Modification request:\n" + modifiedHadith.toString(2));
                JSONObject existingHadith = new JSONObject(responseStr);
                // add all the values from the modification object to the stored hadith object
                Iterator<?> keys = modifiedHadith.keys();
                while (keys.hasNext()) {
                    String key = (String) keys.next();
                    existingHadith.put(key, modifiedHadith.get(key));
                }
                // make sure we can still serialize a valid HadithObject from the new JSON data
                HadithObject newHadithObject = mapper.readValue(existingHadith.toString(), HadithObject.class);
                newHadithObject.insertHistoryNote("User " + new User(idToken).email() + " modified this hadith on " + new java.util.Date() + ". The orginal hadith:\n"
                        + responseStr + "\n\n The following properties were modified and saved to the database:\n\n" + modifiedHadithStr);
                new UpdateRequest(newHadithObject, hadithId).execute();
                // clear the cache
                cacheManager.getCacheNames().parallelStream().forEach(name -> cacheManager.getCache(name).clear());
                return new ResponseEntity<>("Successfully update hadith: " + hadithId, HttpStatus.OK);
            } else {
                throw new AuthenticationException("Unauthorized to modify hadith: " + hadithId);
            }
        } catch (Exception e) {
            log.error("Unable to modify hadith: " + hadithId, e);
            throw e;
        }
    }
}
