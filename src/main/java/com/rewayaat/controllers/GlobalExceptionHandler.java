package com.rewayaat.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/**
 * Translates unhandled exceptions from the JSON API into structured error responses
 * so that callers always receive a consistent {@code {"ok": false, "message": "..."}} envelope
 * rather than a Spring default error page.
 *
 * <p>Scoped to {@code controllers.rest} on purpose. Unscoped, this advice also caught the
 * {@code NoResourceFoundException} Spring raises for an unmapped URL — with no handler
 * method, and so no package to filter on, every 404 on the site answered 500 with a JSON
 * body instead. Crawlers read site-wide 5xx as an unhealthy origin and cut their crawl
 * rate for the whole domain, so the page-level SEO work was being served through a
 * throttle. The selector also keeps the server-rendered pages (home, hadith, sitemap) on
 * the container's error dispatch, which serves {@code static/error/*.html} under the real
 * status code.
 */
@RestControllerAdvice(basePackages = "com.rewayaat.controllers.rest")
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException ex) {
        return badRequest("Missing required parameter: " + ex.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return badRequest("Invalid value for parameter '" + ex.getName() + "': " + ex.getValue());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return badRequest(ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        LOGGER.error("Unhandled exception in controller", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("ok", false, "message", "An internal error occurred. Please try again later."));
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest()
                .body(Map.of("ok", false, "message", message != null ? message : "Bad request"));
    }
}
