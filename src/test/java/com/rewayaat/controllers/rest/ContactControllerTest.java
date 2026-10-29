package com.rewayaat.controllers.rest;

import com.rewayaat.service.ContactFormToken;
import com.rewayaat.service.UiMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContactControllerTest {

    private ContactController controller;
    private ContactFormToken tokens;
    private long now = 1_800_000_000_000L;
    private String token;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        controller = new ContactController();
        ReflectionTestUtils.setField(controller, "ui", new UiMessages(messages));
        // Blank key: the controller must log and not call Resend.
        ReflectionTestUtils.setField(controller, "resendApiKey", "");
        ReflectionTestUtils.setField(controller, "mailFrom", "no-reply@rewayaat.info");
        tokens = new ContactFormToken("test-secret");
        tokens.setClock(() -> now);
        ReflectionTestUtils.setField(controller, "tokens", tokens);
        token = tokens.issue();
        now += 30_000L;
    }

    private Map<String, Object> good() {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", "narration");
        body.put("message", "The Arabic text drops a word in the second line.");
        body.put("email", "reader@example.com");
        body.put("website", "");
        body.put("token", token);
        return body;
    }

    private ResponseEntity<?> send(Map<String, Object> body) {
        return controller.submitJson(body, new MockHttpServletRequest());
    }

    @Test
    void acceptsAGoodPayloadWithoutCallingResend() {
        ResponseEntity<?> response = send(good());
        assertEquals(200, response.getStatusCode().value());
        assertEquals(Map.of("ok", true), response.getBody());
    }

    @Test
    void rejectsAnEmptyMessage() {
        Map<String, Object> body = good();
        body.put("message", "   ");
        assertEquals(400, send(body).getStatusCode().value());
    }

    @Test
    void rejectsATooLongMessage() {
        Map<String, Object> body = good();
        body.put("message", "x".repeat(4001));
        assertEquals(400, send(body).getStatusCode().value());
    }

    @Test
    void rejectsABadEmail() {
        Map<String, Object> body = good();
        body.put("email", "not-an-email");
        assertEquals(400, send(body).getStatusCode().value());
    }

    @Test
    void acceptsNoEmail() {
        Map<String, Object> body = good();
        body.put("email", "");
        assertEquals(200, send(body).getStatusCode().value());
    }

    @Test
    void rejectsAFilledHoneypot() {
        Map<String, Object> body = good();
        body.put("website", "http://spam.example");
        assertEquals(400, send(body).getStatusCode().value());
    }

    @Test
    void rejectsAnUnknownKind() {
        Map<String, Object> body = good();
        body.put("kind", "invoice");
        assertEquals(400, send(body).getStatusCode().value());
    }

    @Test
    void limitsAnAddressToFiveAnHour() {
        for (int i = 0; i < 5; i++) {
            assertEquals(200, send(good()).getStatusCode().value());
        }
        assertEquals(429, send(good()).getStatusCode().value());
    }

    @Test
    void aPlainFormPostIsAnsweredWithHtml() {
        ResponseEntity<?> response = controller.submitForm(
                Map.of("kind", "site", "message", "The search box does nothing on my phone.", "token", token),
                new MockHttpServletRequest());
        assertEquals(200, response.getStatusCode().value());
        assertTrue(String.valueOf(response.getBody()).startsWith("<!doctype html>"));
    }

    // The spam that prompted the token: posted straight to the endpoint, a random
    // mixed-case string for message and name. Each kind is answered as if sent.

    @Test
    void aPostWithoutATokenIsDroppedButLooksSent() {
        Map<String, Object> body = good();
        body.remove("token");
        ResponseEntity<?> response = send(body);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(Map.of("ok", true), response.getBody());
    }

    @Test
    void aForgedTokenIsDropped() {
        Map<String, Object> body = good();
        body.put("token", (now - 60_000L) + ".AAAA");
        assertEquals(200, send(body).getStatusCode().value());
        // Not counted against the address either: the limit is only for real sends.
        for (int i = 0; i < 5; i++) {
            assertEquals(200, send(good()).getStatusCode().value());
        }
        assertEquals(429, send(good()).getStatusCode().value());
    }

    @Test
    void aPostSecondsAfterThePageLoadedIsDropped() {
        String fresh = tokens.issue();
        Map<String, Object> body = good();
        body.put("token", fresh);
        assertEquals(200, send(body).getStatusCode().value());
        for (int i = 0; i < 5; i++) {
            assertEquals(200, send(good()).getStatusCode().value());
        }
        assertEquals(429, send(good()).getStatusCode().value());
    }

    @Test
    void anExpiredTokenGetsARealError() {
        now += ContactFormToken.MAX_AGE_MS;
        assertEquals(400, send(good()).getStatusCode().value());
    }

    @Test
    void nonsenseMessagesAreRecognised() {
        assertTrue(ContactController.isNonsense("ZBbCPjvpWuSqXYxl"));
        assertTrue(ContactController.isNonsense("rSWqQhEUvgjAYggEBpnmmUM"));
        assertTrue(ContactController.isNonsense("gfTVGdVMpzqSTbnXLE"));
        assertFalse(ContactController.isNonsense("Assalamualaikum"));
        assertFalse(ContactController.isNonsense("JAZAKALLAHKHAIR"));
        assertFalse(ContactController.isNonsense("The Arabic drops a word."));
        assertFalse(ContactController.isNonsense("الحديثضعيفالسند"));
    }

    @Test
    void aNonsenseMessageIsDroppedButLooksSent() {
        Map<String, Object> body = good();
        body.put("message", "aGKEeyjhlCQEviLZduXtaEOm");
        assertEquals(Map.of("ok", true), send(body).getBody());
        for (int i = 0; i < 5; i++) {
            assertEquals(200, send(good()).getStatusCode().value());
        }
        assertEquals(429, send(good()).getStatusCode().value());
    }
}
