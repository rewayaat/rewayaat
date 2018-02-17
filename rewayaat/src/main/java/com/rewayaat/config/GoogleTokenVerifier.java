package com.rewayaat.config;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.rewayaat.RewayaatLogger;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggerFactory;

import java.util.Collections;

/**
 * Verifies Google token ID's.
 */
public class GoogleTokenVerifier {

    private static Logger log = Logger.getLogger(GoogleTokenVerifier.class.getName(), new LoggerFactory() {
        @Override
        public Logger makeNewLoggerInstance(String name) {
            return new RewayaatLogger(name);
        }
    });

    public static final String CLIENTID = "776365081062-5fc66doo0k5jpg4nfnimkag81e02sb81.apps.googleusercontent.com";

    public String authenticate(String idTokenString) throws Exception {
        // Set up the HTTP transport and JSON factory
        HttpTransport transport = new NetHttpTransport();
        JsonFactory jsonFactory = new JacksonFactory();

        GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(transport, jsonFactory)
                .setAudience(Collections.singletonList(CLIENTID))
                .build();

        // (Receive idTokenString by HTTPS POST)
        GoogleIdToken idToken = verifier.verify(idTokenString);
        if (idToken != null) {
            GoogleIdToken.Payload payload = idToken.getPayload();

            // Print user identifier
            String userId = payload.getSubject();
            log.info("User ID: " + userId);

            // Get profile information from payload
            String email = payload.getEmail();
            return email;
        } else {
            throw new Exception("Could not authenticate this tokenid!");
        }
    }
}