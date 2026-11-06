package com.rewayaat.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SupportCheckoutTest {

    @Test
    void aMonthlyGiftIsASubscriptionAtTheChosenAmount() {
        Map<String, String> p = SupportCheckout.params(SupportCheckout.Interval.MONTH, 1000, "https://rewayaat.info", "");
        assertEquals("subscription", p.get("mode"));
        assertEquals("1000", p.get("line_items[0][price_data][unit_amount]"));
        assertEquals("usd", p.get("line_items[0][price_data][currency]"));
        assertEquals("month", p.get("line_items[0][price_data][recurring][interval]"));
        assertFalse(p.containsKey("customer_creation"), "Stripe rejects customer_creation on a subscription");
    }

    @Test
    void aOneTimeGiftIsAPaymentWithNoRecurrence() {
        Map<String, String> p = SupportCheckout.params(SupportCheckout.Interval.ONCE, 4321, "https://rewayaat.info", "");
        assertEquals("payment", p.get("mode"));
        assertEquals("4321", p.get("line_items[0][price_data][unit_amount]"));
        assertNull(p.get("line_items[0][price_data][recurring][interval]"));
    }

    @Test
    void bothKindsOfGiftTakeAPromotionCode() {
        for (SupportCheckout.Interval interval : SupportCheckout.Interval.values()) {
            Map<String, String> p = SupportCheckout.params(interval, 1000, "https://rewayaat.info", "");
            assertEquals("true", p.get("allow_promotion_codes"), interval.name());
        }
    }

    @Test
    void theSupporterReturnsInTheLanguageTheyGaveIn() {
        Map<String, String> p = SupportCheckout.params(SupportCheckout.Interval.ONCE, 500, "https://rewayaat.info", "/ar");
        assertEquals("https://rewayaat.info/ar/about/thanks?session_id={CHECKOUT_SESSION_ID}", p.get("success_url"));
        assertEquals("https://rewayaat.info/ar/about#support", p.get("cancel_url"));
        assertEquals("auto", p.get("locale"), "Stripe Checkout rejects \"ar\"; it must stay auto");
    }


    @Test
    void giftsOutsideFiveToTenThousandDollarsAreRefused() {
        assertFalse(SupportCheckout.acceptable(499));
        assertTrue(SupportCheckout.acceptable(500));
        assertTrue(SupportCheckout.acceptable(1_000_000));
        assertFalse(SupportCheckout.acceptable(1_000_001));
    }

    @Test
    void withNoKeyGivingIsClosedAndNothingIsSent() {
        SupportCheckout closed = new SupportCheckout("  ");
        assertFalse(closed.isOpen());
        assertThrows(SupportCheckout.CheckoutFailed.class,
                () -> closed.open(SupportCheckout.Interval.ONCE, 500, "b", ""));
    }

    @Test
    void sendsTheKeyAndTheFormAndReturnsStripesPage() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>(), body = new AtomicReference<>();
        HttpServer stripe = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stripe.createContext("/v1/checkout/sessions", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"id\":\"cs_test\",\"url\":\"https://checkout.stripe.com/c/pay/cs_test\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        stripe.start();
        try {
            SupportCheckout checkout = new SupportCheckout("sk_test_abc", HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + stripe.getAddress().getPort() + "/v1/checkout/sessions"));
            String url = checkout.open(SupportCheckout.Interval.MONTH, 2500, "https://rewayaat.info", "");
            assertEquals("https://checkout.stripe.com/c/pay/cs_test", url);
            assertEquals("Bearer sk_test_abc", auth.get());
            String form = URLDecoder.decode(body.get(), StandardCharsets.UTF_8);
            assertTrue(form.contains("line_items[0][price_data][unit_amount]=2500"), form);
            assertFalse(form.contains("in_name_of"), form);
        } finally {
            stripe.stop(0);
        }
    }

    @Test
    void aStripeRefusalIsReportedNotSwallowed() throws Exception {
        HttpServer stripe = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stripe.createContext("/", ex -> {
            byte[] out = "{\"error\":{\"message\":\"Invalid API Key provided\"}}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(401, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        stripe.start();
        try {
            SupportCheckout checkout = new SupportCheckout("sk_test_bad", HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + stripe.getAddress().getPort() + "/"));
            SupportCheckout.CheckoutFailed failed = assertThrows(SupportCheckout.CheckoutFailed.class,
                    () -> checkout.open(SupportCheckout.Interval.ONCE, 500, "b", ""));
            assertTrue(failed.getMessage().contains("Invalid API Key"));
        } finally {
            stripe.stop(0);
        }
    }
}
