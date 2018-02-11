package com.rewayaat.web.core;

import com.rewayaat.web.auth.GoogleTokenVerifier;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * Redirects to the changelog web page.
 */
@Controller
public class LoginController {

    public static final String AUTHENTICATED = "authenticated";
    public static final String USER_EMAIL = "user_email";

    private static Logger log = Logger.getLogger(LoginController.class.getName());


    @Autowired
    private ResourceLoader resourceLoader;

    @RequestMapping(value = "/google/signin", method = RequestMethod.POST)
    public final ResponseEntity<String> signin(HttpServletRequest request, @RequestParam(value = "idtoken") String idtoken) {
        log.info("User signed in with idtoken: " + idtoken);
        try {
            String email = new GoogleTokenVerifier().authenticate(idtoken);
            Resource resource = resourceLoader.getResource("classpath:admins.txt");
            List<String> lines = Arrays.asList(IOUtils.toString(resource.getInputStream(), "UTF-8").split("\n"));
            if (lines.contains(email)) {
                log.info("User is an administrator");
                request.getSession().setAttribute(AUTHENTICATED, true);
                request.getSession().setAttribute(USER_EMAIL, email);

                return new ResponseEntity<>("Authenticated as an admin.", HttpStatus.OK);
            } else {
                log.info("User is not an administrator");
                return new ResponseEntity<>("Not an admin.", HttpStatus.ACCEPTED);
            }
        } catch (Exception e) {
            log.info("Could not process id token.\n" + e);
            return new ResponseEntity<>("Invalid id token", HttpStatus.UNAUTHORIZED);
        }
    }

    @RequestMapping(value = "/google/reset", method = RequestMethod.POST)
    public final ResponseEntity<String> reset(HttpServletRequest request) {
        request.getSession().setAttribute(AUTHENTICATED, false);
        request.getSession().setAttribute(USER_EMAIL, null);
        return new ResponseEntity<>("Session successfully reset.", HttpStatus.OK);
    }
}