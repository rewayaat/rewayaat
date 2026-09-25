package com.rewayaat.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reports each connector tool call to GA4, so connector usage is visible where the rest of
 * the site's traffic already is.
 *
 * <p>{@link ConnectorLinks} only counts the people who click through from an answer; most
 * connector use never produces a click. This records the use itself, as an
 * {@code mcp_tool_call} event sent through GA4's Measurement Protocol, with the parameters
 * {@code client} ({@code claude}, {@code chatgpt} or {@code other}), {@code tool},
 * {@code outcome} and {@code latency_ms}.
 *
 * <p>The GA4 client id is the MCP session id, so a GA4 "user" is one connector session -
 * roughly one conversation. That is the closest thing to a user this server can see: Claude
 * and ChatGPT call from shared egress addresses, and no identity crosses the protocol.
 *
 * <p>Sending is fire-and-forget. A slow or failing GA4 must never delay or fail a tool call,
 * so the request goes out asynchronously and a failure is logged at debug and dropped. With
 * no API secret configured - local runs, tests - nothing is sent at all.
 */
@Component
public class ConnectorAnalytics {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectorAnalytics.class);

    static final String EVENT = "mcp_tool_call";
    private static final String ENDPOINT = "https://www.google-analytics.com/mp/collect";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper;
    private final String measurementId;
    private final String apiSecret;

    public ConnectorAnalytics(ObjectMapper mapper,
                              @Value("${rewayaat.ga4.measurement-id:G-3HSRTQD7GM}") String measurementId,
                              @Value("${rewayaat.ga4.api-secret:}") String apiSecret) {
        this.mapper = mapper;
        this.measurementId = measurementId;
        this.apiSecret = apiSecret;
    }

    public boolean enabled() {
        return apiSecret != null && !apiSecret.isBlank();
    }

    /**
     * Records one tool call.
     *
     * @param sessionId the MCP session id; a random one stands in when the transport has none
     * @param source    the client, already reduced by {@link ConnectorLinks#source}
     * @param outcome   {@code ok}, {@code bad_arguments} or {@code error}
     */
    public void toolCall(String sessionId, String source, String tool, String outcome, long latencyMs) {
        if (!enabled()) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT
                            + "?measurement_id=" + encode(measurementId)
                            + "&api_secret=" + encode(apiSecret)))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(payload(sessionId, source, tool, outcome, latencyMs))))
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((response, ex) -> {
                        if (ex != null) {
                            LOGGER.debug("GA4 event for {} not sent: {}", tool, ex.getMessage());
                        }
                    });
        } catch (JsonProcessingException ex) {
            LOGGER.debug("GA4 event for {} not built: {}", tool, ex.getMessage());
        }
    }

    static Map<String, Object> payload(String sessionId, String source, String tool, String outcome,
                                       long latencyMs) {
        return Map.of(
                "client_id", sessionId == null || sessionId.isBlank()
                        ? UUID.randomUUID().toString()
                        : sessionId,
                "events", List.of(Map.of(
                        "name", EVENT,
                        "params", Map.of(
                                "client", source,
                                "tool", tool,
                                "outcome", outcome,
                                "latency_ms", latencyMs,
                                // Without engagement time GA4 records the event but does not
                                // count its client as an active user.
                                "engagement_time_msec", 1))));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
