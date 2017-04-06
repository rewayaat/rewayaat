package com.rewayaat.web.core;

import java.io.IOException;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.web.HomeController;
import com.rewayaat.web.data.hadith.HadithObject;
import com.rewayaat.web.data.hadith.HadithRepository;

@Controller
public class HadithLoaderController {

    @Autowired
    HadithRepository hadithRepo;

    private static Logger log = Logger.getLogger(HadithLoaderController.class.getName());

    @RequestMapping(value = "/load", method = RequestMethod.GET)
    public final ResponseEntity<String> loadHadith() {

        log.info("Entered hadith controller, loading hadith on class path into the database");
        ClassLoader classLoader = HomeController.class.getClassLoader();
        try {
            ObjectMapper mapper = new ObjectMapper();
            String result = IOUtils.toString(classLoader.getResourceAsStream("rewayaat.json"));
            HadithObject[] obs = mapper.readValue(result, HadithObject[].class);
            for (HadithObject hadithInfo : obs) {
                hadithRepo.save(hadithInfo);
            }
        } catch (IOException e) {
            e.printStackTrace();
            return new ResponseEntity<String>("Error", HttpStatus.BAD_REQUEST);
        }
        return new ResponseEntity<String>("Success", HttpStatus.OK);
    }
}
