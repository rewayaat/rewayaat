package com.rewayaat.core;

import com.rewayaat.RewayaatLogger;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.spi.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.util.Arrays;
import java.util.List;

/**
 * Represents a user of the system.
 */
public class User {

    private static org.apache.log4j.Logger log = org.apache.log4j.Logger.getLogger(User.class.getName(), new LoggerFactory() {
        @Override
        public org.apache.log4j.Logger makeNewLoggerInstance(String name) {
            return new RewayaatLogger(name);
        }
    });
    private final String email;

    public User(String idToken) throws Exception {
        this.email = new com.rewayaat.config.GoogleTokenVerifier().authenticate(idToken);
    }

    public boolean isAdmin() {
        try {
            Resource resource = new ClassPathResource("admins.txt");
            List<String> lines = Arrays.asList(IOUtils.toString(resource.getInputStream(), "UTF-8").split("\n"));
            if (lines.contains(email)) {
                log.info("User is an administrator");
                return true;
            } else {
                log.info("User is not an administrator");
                return false;
            }
        } catch (Exception e) {
            log.error("Could not process id token.", e);
            return false;
        }
    }

    public String email() {
        return this.email;
    }
}
