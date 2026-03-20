package com.rewayaat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimilarHadithRerankerServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesRankedJsonAndClampsScores() {
        String response = "```json\n"
                + "{\"ranked\":[{\"id\":\"h1\",\"score\":104.2},{\"id\":\"h2\",\"score\":-7},{\"id\":\"h3\",\"score\":33.4}]}\n"
                + "```";

        SimilarHadithRerankerService.RerankDecision decision =
                SimilarHadithRerankerService.parseRerankResponse(response, mapper);

        assertTrue(decision.success());
        assertEquals(3, decision.rankedScores().size());
        assertEquals(100d, decision.rankedScores().get("h1"));
        assertEquals(0d, decision.rankedScores().get("h2"));
        assertEquals(33.4d, decision.rankedScores().get("h3"));
    }

    @Test
    void keepsBackwardCompatibilityWithLegacyKeepSchema() {
        String response = "{\"kept\":[{\"id\":\"h1\",\"score\":91.1},{\"id\":\"h2\",\"score\":24.6}],\"dropped\":[\"h3\"]}";

        SimilarHadithRerankerService.RerankDecision decision =
                SimilarHadithRerankerService.parseRerankResponse(response, mapper);

        assertTrue(decision.success());
        assertEquals(2, decision.rankedScores().size());
        assertEquals(91.1d, decision.rankedScores().get("h1"));
        assertEquals(24.6d, decision.rankedScores().get("h2"));
    }

    @Test
    void returnsFailureForNonJsonResponse() {
        SimilarHadithRerankerService.RerankDecision decision =
                SimilarHadithRerankerService.parseRerankResponse("empty", mapper);
        assertFalse(decision.success());
        assertTrue(decision.rankedScores().isEmpty());
    }
}
