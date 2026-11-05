package com.rewayaat.controllers.rest;

import org.springframework.beans.factory.annotation.Autowired;
import com.rewayaat.service.PageLocale;
import com.rewayaat.service.SupportCheckout;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The About page's giving form posts here, and is sent on to Stripe Checkout.
 *
 * <p>Every answer is JSON the page's script reads: {@code {"url": ...}} to go to Stripe,
 * or {@code {"error": ...}} naming what to tell the reader. Nothing is charged here; the
 * supporter pays on Stripe's own page.
 */
@Hidden
@RestController
public class SupportCheckoutController {

    private static final Logger LOGGER = LoggerFactory.getLogger(SupportCheckoutController.class);
    private static final long WINDOW_MS = 60 * 60 * 1000L;
    /** Opening a checkout is cheap, but each one is a call to Stripe on our key. */
    private static final int PER_HOUR = 20;

    public record Gift(String interval, Double amount, String locale) { }

    private final SupportCheckout checkout;
    private final LongSupplier clock;
    private final Map<String, ArrayDeque<Long>> seen = new HashMap<>();

    @Autowired
    public SupportCheckoutController(SupportCheckout checkout) {
        this(checkout, System::currentTimeMillis);
    }

    SupportCheckoutController(SupportCheckout checkout, LongSupplier clock) {
        this.checkout = checkout;
        this.clock = clock;
    }

    @PostMapping(value = "/about/checkout", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> open(@RequestBody Gift gift, HttpServletRequest request) {
        if (!checkout.isOpen()) {
            return ResponseEntity.status(503).body(Map.of("error", "closed"));
        }
        SupportCheckout.Interval interval = "once".equals(gift.interval())
                ? SupportCheckout.Interval.ONCE
                : "month".equals(gift.interval()) ? SupportCheckout.Interval.MONTH : null;
        long cents = gift.amount() == null || gift.amount().isNaN() ? -1 : Math.round(gift.amount() * 100);
        if (interval == null || !SupportCheckout.acceptable(cents)) {
            return ResponseEntity.badRequest().body(Map.of("error", "amount"));
        }
        if (!admit(ContactController.clientAddress(request))) {
            return ResponseEntity.status(429).body(Map.of("error", "busy"));
        }
        try {
            String url = checkout.open(interval, cents, base(request),
                    PageLocale.ofTag(gift.locale()).prefix());
            return ResponseEntity.ok(Map.of("url", url));
        } catch (SupportCheckout.CheckoutFailed e) {
            LOGGER.warn("Stripe checkout failed: {}", e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "stripe"));
        }
    }

    /**
     * Where Stripe sends the supporter back to. The canonical host, except on a developer's
     * own machine, which would otherwise be sent to production after paying.
     */
    static String base(HttpServletRequest request) {
        String host = request.getServerName();
        if ("localhost".equals(host) || "127.0.0.1".equals(host)) {
            int port = request.getServerPort();
            return request.getScheme() + "://" + host + (port == 80 || port == 443 ? "" : ":" + port);
        }
        return PageLocale.BASE_URL;
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
}
