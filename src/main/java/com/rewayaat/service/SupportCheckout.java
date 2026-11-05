package com.rewayaat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Opens a Stripe Checkout page for a gift from the About page.
 *
 * <p>Each gift carries its own amount ({@code price_data}), so a supporter can give any
 * amount, monthly or once, without a price having to exist in Stripe first. The money goes
 * to the Rewayaat Stripe account, whose key is {@code REWAYAAT_STRIPE_SECRET_KEY}; with no
 * key set, giving is closed and nothing is sent anywhere.
 *
 * <p>Statement and receipt branding (REWAYAAT) belong to that account's settings, not to
 * anything sent here.
 */
@Service
public class SupportCheckout {

    /** Five dollars, the smallest gift, in cents. */
    public static final long MIN_CENTS = 500;
    /** Ten thousand dollars, the largest gift taken in one go, in cents. */
    public static final long MAX_CENTS = 1_000_000;
    /** The longest "in the name of" kept; Stripe metadata values allow 500. */
    public static final int MAX_DEDICATION = 120;

    public enum Interval { MONTH, ONCE }

    /** Stripe declined, or could not be reached. */
    public static class CheckoutFailed extends Exception {
        public CheckoutFailed(String message) {
            super(message);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String secretKey;
    private final HttpClient http;
    private final URI endpoint;

    public SupportCheckout(@Value("${rewayaat.stripe.secret-key:}") String secretKey) {
        this(secretKey, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                URI.create("https://api.stripe.com/v1/checkout/sessions"));
    }

    SupportCheckout(String secretKey, HttpClient http, URI endpoint) {
        this.secretKey = secretKey == null ? "" : secretKey.trim();
        this.http = http;
        this.endpoint = endpoint;
    }

    /** Whether giving is open: a key is configured. */
    public boolean isOpen() {
        return !secretKey.isEmpty();
    }

    /** Whether a gift of this many cents is accepted. */
    public static boolean acceptable(long cents) {
        return cents >= MIN_CENTS && cents <= MAX_CENTS;
    }

    /**
     * Creates the Checkout Session and returns the page to send the supporter to.
     *
     * @param base where the site is being served, e.g. {@code https://rewayaat.info}
     * @param prefix the language prefix the supporter is reading in, {@code ""} or {@code /ar}
     */
    public String open(Interval interval, long cents, String dedication, String base, String prefix)
            throws CheckoutFailed {
        if (!isOpen()) {
            throw new CheckoutFailed("giving is closed: no Stripe key");
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + secretKey)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        encode(params(interval, cents, dedication, base, prefix))))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = JSON.readTree(response.body());
            if (response.statusCode() / 100 != 2 || !body.hasNonNull("url")) {
                String reason = body.path("error").path("message").asText("status " + response.statusCode());
                throw new CheckoutFailed(reason);
            }
            return body.get("url").asText();
        } catch (IOException e) {
            throw new CheckoutFailed(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CheckoutFailed("interrupted");
        }
    }

    /** The form fields of the Checkout Session, in a stable order. */
    static Map<String, String> params(Interval interval, long cents, String dedication,
                                     String base, String prefix) {
        boolean monthly = interval == Interval.MONTH;
        String name = dedication == null ? "" : dedication.trim();
        Map<String, String> p = new LinkedHashMap<>();
        p.put("mode", monthly ? "subscription" : "payment");
        p.put("line_items[0][quantity]", "1");
        p.put("line_items[0][price_data][currency]", "usd");
        p.put("line_items[0][price_data][unit_amount]", Long.toString(cents));
        p.put("line_items[0][price_data][product_data][name]",
                monthly ? "Monthly support for Rewayaat" : "Support for Rewayaat");
        if (monthly) {
            p.put("line_items[0][price_data][recurring][interval]", "month");
        }
        p.put("success_url", base + prefix + "/about/thanks?session_id={CHECKOUT_SESSION_ID}");
        p.put("cancel_url", base + prefix + "/about#support");
        p.put("locale", "auto");
        p.put("metadata[source]", "about");
        String carried = monthly ? "subscription_data" : "payment_intent_data";
        p.put(carried + "[metadata][source]", "about");
        if (!name.isEmpty()) {
            p.put("metadata[in_name_of]", name);
            p.put(carried + "[metadata][in_name_of]", name);
        }
        if (!monthly) {
            p.put("customer_creation", "always");
        }
        return p;
    }

    static String encode(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }
}
