package com.rewayaat.core;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.util.Arrays;
import java.util.List;

/**
 * Represents a user of the system.
 */
public class User {

    private static final Logger LOGGER = LoggerFactory.getLogger(User.class);
    private final String email;

    public User(String idToken) throws Exception {
        this.email = new com.rewayaat.config.GoogleTokenVerifier().authenticate(idToken);
    }

    public boolean isAdmin() {
        try {
            Resource resource = new ClassPathResource("admins.txt");
            List<String> lines = Arrays.asList(IOUtils.toString(resource.getInputStream(), "UTF-8").split("\n"));
            if (lines.contains(email)) {
                LOGGER.info("User is an administrator");
                return true;
            } else {
                LOGGER.info("User is not an administrator");
                return false;
            }
        } catch (Exception e) {
            LOGGER.error("Could not process id token.", e);
            return false;
        }
    }

    public String email() {
        return this.email;
    }
}
