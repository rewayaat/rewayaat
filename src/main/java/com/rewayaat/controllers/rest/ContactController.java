package com.rewayaat.controllers.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.controllers.ContactPageController;
import com.rewayaat.service.ContactFormToken;
import com.rewayaat.service.PageLocale;
import com.rewayaat.service.UiMessages;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Receives the contact form and mails it to the editors.
 *
 * <p>Takes JSON from the page's {@code fetch}, or a plain form post from the
 * {@code <noscript>} fallback. The fallback is answered with a small HTML page rather than
 * JSON, because a browser that navigated here has nothing to render JSON with.
 *
 * <p>Nothing from the client is trusted: message length, address shape, the honeypot and
 * the set of kinds are all checked here. Five submissions an hour per address are accepted,
 * counted in memory per pod - enough to stop a script flooding the inbox, and not worth a
 * shared store.
 *
 * <p>Two bots got past all of that by posting a random mixed-case string as the message,
 * slowly and from many addresses. Posts without a valid {@link ContactFormToken}, posts
 * sent within seconds of the page loading, and messages that are one mixed-case nonsense
 * word are dropped - and answered as if sent, so the script learns nothing to adapt to.
 * Only an expired token gets a real error: that is a reader with an old tab, who would
 * otherwise lose the message without knowing.
 */
@RestController
@RequestMapping("/v1/contact")
/** The site's own contact form posts here. Not a public API. */
@Hidden
@Tag(name = "Contact")
public class ContactController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContactController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FEEDBACK_TO = "rewayaat.org@gmail.com";
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    static final Set<String> KINDS = Set.of("narration", "narrator", "site", "other");
    static final int MIN_MESSAGE = 10;
    static final int MAX_MESSAGE = 4000;
    static final int PER_HOUR = 5;
    private static final long WINDOW_MS = 3_600_000L;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern ONE_LATIN_WORD = Pattern.compile("^[A-Za-z]+$");

    @Value("${rewayaat.resend-api-key:}")
    private String resendApiKey;

    @Value("${rewayaat.mail-from:no-reply@rewayaat.info}")
    private String mailFrom;

    @Autowired
    private UiMessages ui;

    @Autowired
    private ContactFormToken tokens;

    private final Map<String, ArrayDeque<Long>> seen = new HashMap<>();
    private LongSupplier clock = System::currentTimeMillis;

    void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Send a message to the editors (JSON)")
    public ResponseEntity<?> submitJson(@RequestBody Map<String, Object> body,
                                        HttpServletRequest request) {
        Map<String, String> fields = new HashMap<>();
        body.forEach((key, value) -> fields.put(key, value == null ? "" : String.valueOf(value)));
        return handle(fields, request, false);
    }

    @PostMapping(consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @Operation(summary = "Send a message to the editors (plain form post)")
    public ResponseEntity<?> submitForm(@RequestParam Map<String, String> fields,
                                        HttpServletRequest request) {
        return handle(fields, request, true);
    }

    private ResponseEntity<?> handle(Map<String, String> fields, HttpServletRequest request,
                                     boolean html) {
        PageLocale locale = PageLocale.ofTag(fields.get("locale"));
        try {
            String kind = ContactPageController.clean(fields.get("kind"));
            String message = strip(fields.get("message"));
            String email = ContactPageController.clean(fields.get("email"));
            String name = ContactPageController.clean(fields.get("name"));
            String honeypot = fields.getOrDefault("website", "");

            String problem = null;
            ContactFormToken.Check token = tokens.check(fields.get("token"));
            if (!honeypot.isBlank()) {
                problem = "api.contact.rejected";
            } else if (token == ContactFormToken.Check.EXPIRED) {
                problem = "api.contact.expired";
            } else if (token != ContactFormToken.Check.VALID || isNonsense(message)) {
                LOGGER.info("Contact message dropped: token={} message={}", token,
                        message.substring(0, Math.min(40, message.length())));
                return reply(200, null, html, locale);
            } else if (!KINDS.contains(kind)) {
                problem = "api.contact.kind";
            } else if (message.length() < MIN_MESSAGE || message.length() > MAX_MESSAGE) {
                problem = "api.contact.messageLength";
            } else if (!email.isEmpty() && !EMAIL.matcher(email).matches()) {
                problem = "api.contact.email";
            }
            if (problem != null) {
                return reply(400, ui.in(locale, problem, MIN_MESSAGE, MAX_MESSAGE), html, locale);
            }
            if (!admit(clientAddress(request))) {
                return reply(429, ui.in(locale, "api.contact.rateLimited"), html, locale);
            }

            String label = ContactPageController.clean(fields.get("label"));
            String id = ContactPageController.clean(fields.get("id"));
            String url = ContactPageController.clean(fields.get("url"));
            String text = message + "\n\n--\n"
                    + "Kind: " + kind + "\n"
                    + "Id: " + id + "\n"
                    + "Label: " + label + "\n"
                    + "Url: " + url + "\n"
                    + "Name: " + name + "\n"
                    + "Email: " + email + "\n"
                    + "Referer: " + nullToEmpty(request.getHeader("Referer")) + "\n"
                    + "Locale: " + locale.tag() + "\n"
                    + "User agent: " + nullToEmpty(request.getHeader("User-Agent")) + "\n"
                    + "Time: " + Instant.now();
            String subject = "[HDP] " + kind + " — " + (label.isEmpty() ? "site" : label);

            sendEmail(subject, text, email);
            LOGGER.info("Contact message: kind={} label={}", kind, label);
            return reply(200, null, html, locale);
        } catch (Exception e) {
            LOGGER.error("Error sending contact message", e);
            return reply(500, ui.in(locale, "api.contact.failed"), html, locale);
        }
    }

    /**
     * One run of Latin letters, with capitals scattered past the first letter:
     * {@code ZBbCPjvpWuSqXYxl}. A real one-word message is capitalised at most at the start.
     */
    static boolean isNonsense(String message) {
        if (!ONE_LATIN_WORD.matcher(message).matches()) {
            return false;
        }
        long innerCapitals = message.substring(1).chars().filter(Character::isUpperCase).count();
        return innerCapitals >= 2 && innerCapitals < message.length() - 1;
    }

    private ResponseEntity<?> reply(int status, String message, boolean html, PageLocale locale) {
        boolean ok = status == 200;
        if (html) {
            String shown = ok ? ui.in(locale, "contact.success.body") : message;
            String title = ok ? ui.in(locale, "contact.success.title") : ui.in(locale, "contact.error.title");
            String page = "<!doctype html><html lang=\"" + locale.tag() + "\" dir=\"" + locale.direction()
                    + "\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                    + "<meta name=\"robots\" content=\"noindex\"><title>" + escape(title) + "</title></head>"
                    + "<body style=\"font-family:sans-serif;max-width:40rem;margin:3rem auto;padding:0 1rem\">"
                    + "<h1>" + escape(title) + "</h1><p>" + escape(shown) + "</p>"
                    + "<p><a href=\"" + locale.prefix() + "/contact\">" + escape(ui.in(locale, "contact.back"))
                    + "</a></p></body></html>";
            return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).body(page);
        }
        Map<String, Object> body = ok ? Map.of("ok", true) : Map.of("ok", false, "message", message);
        return ResponseEntity.status(status).body(body);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : ContactPageController.clean(value);
    }

    /** Trimmed, with CR/LF kept: this is the one field that is allowed to be multi-line. */
    private static String strip(String value) {
        return value == null ? "" : value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "").trim();
    }

    /**
     * The address nginx recorded. The rightmost X-Forwarded-For entry is the one the
     * proxy itself appended; anything to its left was supplied by the client.
     */
    static String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            return parts[parts.length - 1].trim();
        }
        return request.getRemoteAddr();
    }

    private synchronized boolean admit(String address) {
        long now = clock.getAsLong();
        if (seen.size() > 10_000) {
            seen.values().removeIf(q -> q.isEmpty() || now - q.peekLast() > WINDOW_MS);
        }
        ArrayDeque<Long> times = seen.computeIfAbsent(address, k -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() > WINDOW_MS) {
            times.pollFirst();
        }
        if (times.size() >= PER_HOUR) {
            return false;
        }
        times.addLast(now);
        return true;
    }

    private void sendEmail(String subject, String body, String replyTo) throws Exception {
        if (resendApiKey == null || resendApiKey.isBlank()) {
            LOGGER.warn("Resend API key not configured. Contact: {} / {}", subject, body);
            return;
        }
        Map<String, Object> payload = new HashMap<>(Map.of(
                "from", mailFrom,
                "to", List.of(FEEDBACK_TO),
                "subject", subject,
                "text", body));
        if (!replyTo.isEmpty()) {
            payload.put("reply_to", replyTo);
        }
        String jsonBody = MAPPER.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.resend.com/emails"))
                .header("Authorization", "Bearer " + resendApiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            LOGGER.info("Contact email sent via Resend.");
        } else {
            LOGGER.warn("Resend returned {}: {}", response.statusCode(), response.body());
        }
    }
}
