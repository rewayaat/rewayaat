package com.rewayaat.controllers.rest;

import com.rewayaat.service.SupportCheckout;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SupportCheckoutControllerTest {

    /** Records what would have gone to Stripe instead of sending it. */
    static class FakeCheckout extends SupportCheckout {
        final List<String> calls = new ArrayList<>();
        FakeCheckout(String key) { super(key); }
        @Override
        public String open(Interval interval, long cents, String base, String prefix) {
            calls.add(interval + " " + cents + " " + base + prefix);
            return "https://checkout.stripe.com/c/pay/x";
        }
    }

    private static MockHttpServletRequest request(String host) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setServerName(host);
        r.setServerPort(host.equals("localhost") ? 8012 : 443);
        r.setScheme(host.equals("localhost") ? "http" : "https");
        r.setRemoteAddr("203.0.113.9");
        return r;
    }

    @Test
    void withNoKeyTheFormIsToldGivingIsClosed() {
        var res = new SupportCheckoutController(new FakeCheckout("")).open(
                new SupportCheckoutController.Gift("month", 10.0, "en"), request("rewayaat.info"));
        assertEquals(503, res.getStatusCode().value());
        assertEquals("closed", res.getBody().get("error"));
    }

    @Test
    void anAmountOutOfRangeOrAnUnknownIntervalIsRefused() {
        FakeCheckout fake = new FakeCheckout("sk_test_x");
        SupportCheckoutController c = new SupportCheckoutController(fake);
        assertEquals(400, c.open(new SupportCheckoutController.Gift("month", 4.99, "en"), request("rewayaat.info")).getStatusCode().value());
        assertEquals(400, c.open(new SupportCheckoutController.Gift("weekly", 10.0, "en"), request("rewayaat.info")).getStatusCode().value());
        assertEquals(400, c.open(new SupportCheckoutController.Gift("once", null, "en"), request("rewayaat.info")).getStatusCode().value());
        assertTrue(fake.calls.isEmpty());
    }

    @Test
    void aGiftOpensCheckoutOnTheCanonicalHostInTheReadersLanguage() {
        FakeCheckout fake = new FakeCheckout("sk_test_x");
        var res = new SupportCheckoutController(fake).open(
                new SupportCheckoutController.Gift("once", 37.5, "ar"), request("rewayaat.info"));
        assertEquals(200, res.getStatusCode().value());
        assertEquals(Map.of("url", "https://checkout.stripe.com/c/pay/x"), res.getBody());
        assertEquals(List.of("ONCE 3750 https://rewayaat.info/ar"), fake.calls);
    }

    @Test
    void onADevelopersMachineStripeSendsThemBackThere() {
        FakeCheckout fake = new FakeCheckout("sk_test_x");
        new SupportCheckoutController(fake).open(new SupportCheckoutController.Gift("month", 10.0, "en"), request("localhost"));
        assertEquals(List.of("MONTH 1000 http://localhost:8012"), fake.calls);
    }

    @Test
    void oneAddressCannotOpenCheckoutsWithoutLimit() {
        SupportCheckoutController c = new SupportCheckoutController(new FakeCheckout("sk_test_x"), () -> 1_000L);
        int ok = 0;
        for (int i = 0; i < 25; i++) {
            if (c.open(new SupportCheckoutController.Gift("month", 10.0, "en"), request("rewayaat.info")).getStatusCode().value() == 200) {
                ok++;
            }
        }
        assertEquals(20, ok);
    }
}
