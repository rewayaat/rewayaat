package com.rewayaat.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the status code an unmapped URL answers with.
 *
 * <p>{@link GlobalExceptionHandler} used to be an unscoped {@code @RestControllerAdvice},
 * so its {@code Exception} handler also caught the {@code NoResourceFoundException} Spring
 * raises for an unmapped path: every 404 on the site answered {@code 500} with a JSON body.
 * Crawlers treat site-wide 5xx as an unhealthy origin and throttle the whole domain, so this
 * quietly capped how much of the 32k-page index Google would fetch. These tests fail if the
 * advice ever loses its package selector.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotFoundStatusTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void unmappedPath_answers404() {
        ResponseEntity<String> response = restTemplate.getForEntity("/no-such-page", String.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void unmappedHtmlPath_answers404() {
        ResponseEntity<String> response = restTemplate.getForEntity("/no-such-page.html", String.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    /** {@code /hadith} maps only as {@code /hadith/{id}}; the bare collection path has no handler. */
    @Test
    void hadithCollectionPath_answers404() {
        ResponseEntity<String> response = restTemplate.getForEntity("/hadith/", String.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    /** The JSON envelope the advice exists for still covers the API. */
    @Test
    void apiBadRequest_stillAnswersJsonEnvelope() {
        ResponseEntity<String> response = restTemplate.getForEntity("/v1/narrations?page=not-a-number", String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody() != null && response.getBody().contains("\"ok\":false"),
                "expected the API error envelope, got: " + response.getBody());
    }
}
