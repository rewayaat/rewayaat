package com.rewayaat.controllers.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/feedback")
@Tag(name = "Feedback")
public class FeedbackController {

    private static final Logger LOGGER = LoggerFactory.getLogger(FeedbackController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FEEDBACK_TO = "rewayaat.org@gmail.com";
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    @Value("${rewayaat.resend-api-key:}")
    private String resendApiKey;

    @Value("${rewayaat.mail-from:no-reply@rewayaat.info}")
    private String mailFrom;

    @PostMapping
    @Operation(summary = "Submit user feedback")
    public ResponseEntity<Map<String, Object>> submit(
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        try {
            int rating = body.get("rating") instanceof Number
                    ? ((Number) body.get("rating")).intValue() : 0;
            if (rating < 1 || rating > 5) {
                return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "Rating must be 1-5"));
            }

            String comment = String.valueOf(body.getOrDefault("comment", "")).trim();
            String name = String.valueOf(body.getOrDefault("name", "")).trim();
            String email = String.valueOf(body.getOrDefault("email", "")).trim();
            String page = request.getHeader("Referer");

            String stars = "\u2B50".repeat(rating) + "\u2606".repeat(5 - rating);
            String text = stars + " (" + rating + "/5)\n\n";
            if (!name.isEmpty()) {
                text += "Name: " + name + "\n";
            }
            if (!email.isEmpty()) {
                text += "Email: " + email + "\n";
            }
            if (!name.isEmpty() || !email.isEmpty()) {
                text += "\n";
            }
            if (!comment.isEmpty()) {
                text += comment + "\n\n";
            }
            if (page != null && !page.isEmpty()) {
                text += "Page: " + page + "\n";
            }
            text += "Time: " + Instant.now().toString();

            sendFeedbackEmail(text);

            LOGGER.info("Feedback submitted: rating={}/5 comment={}", rating,
                    comment.substring(0, Math.min(80, comment.length())));

            return ResponseEntity.ok(Map.of("ok", true));
        } catch (Exception e) {
            LOGGER.error("Error submitting feedback", e);
            return ResponseEntity.internalServerError().body(Map.of("ok", false, "message", "Failed to submit feedback"));
        }
    }

    private void sendFeedbackEmail(String body) throws Exception {
        if (resendApiKey == null || resendApiKey.isBlank()) {
            LOGGER.warn("Resend API key not configured. Feedback: {}", body);
            return;
        }
        String jsonBody = MAPPER.writeValueAsString(Map.of(
                "from", mailFrom,
                "to", List.of(FEEDBACK_TO),
                "subject", "HDP Feedback",
                "text", body));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.resend.com/emails"))
                .header("Authorization", "Bearer " + resendApiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            LOGGER.info("Feedback email sent via Resend.");
        } else {
            LOGGER.warn("Resend returned {}: {}", response.statusCode(), response.body());
        }
    }
}
