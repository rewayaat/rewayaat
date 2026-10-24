package com.rewayaat.controllers.rest;

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
    }

    private static Map<String, Object> good() {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", "narration");
        body.put("message", "The Arabic text drops a word in the second line.");
        body.put("email", "reader@example.com");
        body.put("website", "");
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
                Map.of("kind", "site", "message", "The search box does nothing on my phone."),
                new MockHttpServletRequest());
        assertEquals(200, response.getStatusCode().value());
        assertTrue(String.valueOf(response.getBody()).startsWith("<!doctype html>"));
    }
}
