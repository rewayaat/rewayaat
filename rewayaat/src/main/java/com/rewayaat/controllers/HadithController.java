package com.rewayaat.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.RewayaatLogger;
import com.rewayaat.config.ClientProvider;
import com.rewayaat.controllers.data.hadith.HadithObject;
import com.rewayaat.core.HadithObjectCollection;
import com.rewayaat.core.LoginController;
import com.rewayaat.core.QueryStringQueryResult;
import org.apache.log4j.spi.LoggerFactory;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

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

    @Cacheable(value = "queries")
    @RequestMapping(method = RequestMethod.GET, produces = "application/json")
    @ResponseBody
    public HadithObjectCollection loadHadith(
            @RequestParam(value = "q", defaultValue = "") String query,
            @RequestParam(value = "page", defaultValue = "0") int page) throws Exception {
        log.info("Entered hadith query API with query: " + query + " and page: " + page);
        return new QueryStringQueryResult(query, page).result();
    }

    @RequestMapping(method = RequestMethod.POST, consumes = "application/json")
    public ResponseEntity<String> modifyHadith(
            @RequestParam(value = "id", required = true) String id,
            @RequestBody String modifiedHadithStr, HttpServletRequest req) throws Exception {

        try {
            if (req.getSession().getAttribute(LoginController.AUTHENTICATED) == null || !(boolean) req.getSession().getAttribute(LoginController.AUTHENTICATED)) {
                return new ResponseEntity<>("Unauthorized", HttpStatus.UNAUTHORIZED);
            }
            modifiedHadithStr = Jsoup.parse(modifiedHadithStr).text();
            log.info("Recieved Modification Request for hadith: " + id);
            JSONObject modifiedHadith = new JSONObject(modifiedHadithStr);
            GetResponse response = ClientProvider.instance().getClient().prepareGet(ClientProvider.INDEX, ClientProvider.TYPE, id)
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
            newHadithObject.insertHistoryNote("User " + (String) req.getSession().getAttribute(LoginController.USER_EMAIL) + " modified this hadith on " + new java.util.Date() + ". The orginal hadith:\n"
                    + responseStr + "\n\n The following properties were modified and saved to the database:\n\n" + modifiedHadithStr);
            // save new hadith
            UpdateRequest updateRequest = new UpdateRequest();
            updateRequest.index(ClientProvider.INDEX);
            updateRequest.type(ClientProvider.TYPE);
            updateRequest.id(id);
            updateRequest.doc(mapper.writeValueAsBytes(newHadithObject));
            ClientProvider.instance().getClient().update(updateRequest).get();
            // clear the cache
            cacheManager.getCacheNames().parallelStream().forEach(name -> cacheManager.getCache(name).clear());
            return new ResponseEntity<>("Successfully update hadith: " + id, HttpStatus.OK);
        } catch (Exception e) {
            log.error("Unable to modify hadith: " + id, e);
            throw e;
        }
    }
}
