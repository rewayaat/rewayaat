package com.rewayaat.controllers;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the domain-verification challenges that platforms ask us to publish.
 *
 * <p>Google's is a static file, because it never changes. OpenAI's is a configured value
 * instead: the token is issued by their portal at submission time, and a submission that
 * stalls on waiting for an image to build is a bad trade for a string. Set
 * {@code OPENAI_APPS_CHALLENGE} in k8s/deployment.yaml and the change is a sync.
 *
 * <p>Note this is answered by the website pods, not the MCP pod - the MCP ingress claims
 * only {@code /mcp}, and everything else on the host falls through to the site. OpenAI
 * accepts the challenge on the MCP host or an allowed parent of it, and this is the same
 * host, so that is satisfied.
 */
@RestController
public class WellKnownController {

    private final String openAiAppsChallenge;

    public WellKnownController(@Value("${openai.apps-challenge:}") String openAiAppsChallenge) {
        this.openAiAppsChallenge = openAiAppsChallenge == null ? "" : openAiAppsChallenge.trim();
    }

    /**
     * The token OpenAI's portal issues, verbatim and alone.
     *
     * <p>404 rather than an empty 200 when nothing is configured. A verifier reading a
     * blank body would report the token as wrong, which sends you looking at the token;
     * a 404 says the route is unconfigured, which is where the problem actually is.
     */
    @GetMapping(value = "/.well-known/openai-apps-challenge", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> openAiAppsChallenge() {
        if (openAiAppsChallenge.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(openAiAppsChallenge);
    }
}
