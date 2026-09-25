package com.rewayaat.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectorAnalyticsTest {

    @Test
    void oneSessionIsOneGa4Client() {
        Map<String, Object> payload = ConnectorAnalytics.payload("session-1", "claude", "search_hadith", "ok", 42);

        assertEquals("session-1", payload.get("client_id"));
        Map<?, ?> event = (Map<?, ?>) ((List<?>) payload.get("events")).get(0);
        assertEquals(ConnectorAnalytics.EVENT, event.get("name"));
        assertEquals(Map.of("client", "claude", "tool", "search_hadith", "outcome", "ok",
                "latency_ms", 42L, "engagement_time_msec", 1), event.get("params"));
    }

    @Test
    void aMissingSessionStillGetsAClientId() {
        Object clientId = ConnectorAnalytics.payload(null, "other", "fetch", "error", 0).get("client_id");

        assertFalse(((String) clientId).isBlank());
    }

    @Test
    void sendsNothingWithoutAnApiSecret() {
        assertFalse(new ConnectorAnalytics(new ObjectMapper(), "G-TEST", "").enabled());
        assertTrue(new ConnectorAnalytics(new ObjectMapper(), "G-TEST", "secret").enabled());
    }
}
