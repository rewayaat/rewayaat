package com.rewayaat.core;

import com.rewayaat.RewayaatLogger;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.spi.LoggerFactory;
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

/**
 * Redirects to the changelog controllers page.
 */
@Controller
public class LoginController {

    public static final String AUTHENTICATED = "authenticated";
    public static final String USER_EMAIL = "user_email";

    private static org.apache.log4j.Logger log = org.apache.log4j.Logger.getLogger(LoginController.class.getName(), new LoggerFactory() {
        @Override
        public org.apache.log4j.Logger makeNewLoggerInstance(String name) {
            return new RewayaatLogger(name);
        }
    });

    @Autowired
    private ResourceLoader resourceLoader;

    @RequestMapping(value = "/google/signin", method = RequestMethod.POST)
    public final ResponseEntity<String> signin(HttpServletRequest request, @RequestParam(value = "idtoken") String idtoken) {
        log.info("User signed in with idtoken: " + idtoken);
        try {
            String email = new com.rewayaat.config.GoogleTokenVerifier().authenticate(idtoken);
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
            log.error("Could not process id token.", e);
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