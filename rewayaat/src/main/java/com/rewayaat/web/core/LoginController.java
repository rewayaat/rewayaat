package com.rewayaat.web.core;

import com.rewayaat.web.auth.GoogleTokenVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Logger;

/**
 * Redirects to the changelog web page.
 */
@Controller
public class LoginController {

    public static final String AUTHENTICATED = "authenticated";
    private static Logger log = Logger.getLogger(LoginController.class.getName());
    @Value("classpath:admins.txt")
    private Resource res;

    @RequestMapping(value = "/google/signin", method = RequestMethod.POST)
    public final ResponseEntity<String> home(HttpServletRequest request, @RequestParam(value = "idtoken") String idtoken) {
        log.info("User signed in with idtoken: " + idtoken);
        try {
            String email = new GoogleTokenVerifier().authenticate(idtoken);
            List<String> lines = Files.readAllLines(Paths.get(res.getURI()),
                    StandardCharsets.UTF_8);
            if (lines.contains(email)) {
                request.getSession().setAttribute(AUTHENTICATED, true);
                return new ResponseEntity<>("Authenticated as an admin.", HttpStatus.OK);
            } else {
                return new ResponseEntity<>("Not an admin.", HttpStatus.ACCEPTED);
            }
        } catch (Exception e) {
            return new ResponseEntity<>("Invalid id token", HttpStatus.UNAUTHORIZED);
        }
    }
}
