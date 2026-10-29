package com.rewayaat.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.function.LongSupplier;

/**
 * A signed note of when the contact page was served, carried back by the form.
 *
 * <p>The spam this stops posts straight to {@code /v1/contact} without loading the page,
 * so it never has a token; a script that does load the page still has to wait
 * {@link #MIN_AGE_MS} before posting, which no reader typing a message ever notices.
 *
 * <p>The token is {@code <millis>.<signature>}. It must verify on whichever pod receives
 * the post, so the key is derived from a secret every pod already shares, the Resend key;
 * without one (development) each process makes its own.
 */
@Component
public class ContactFormToken {

    public enum Check { VALID, FORGED, TOO_FAST, EXPIRED }

    public static final long MIN_AGE_MS = 3_000L;
    /** Long enough for a tab left open overnight, or restored from the back button. */
    public static final long MAX_AGE_MS = 2 * 24 * 3_600_000L;

    private final byte[] key;
    private LongSupplier clock = System::currentTimeMillis;

    public ContactFormToken(@Value("${rewayaat.resend-api-key:}") String sharedSecret) {
        if (sharedSecret == null || sharedSecret.isBlank()) {
            key = new byte[32];
            new SecureRandom().nextBytes(key);
        } else {
            // A key of its own, so a token never reveals anything about the Resend key.
            key = sign(sharedSecret.getBytes(StandardCharsets.UTF_8), "contact-form-token");
        }
    }

    public void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    public String issue() {
        String at = Long.toString(clock.getAsLong());
        return at + "." + encode(sign(key, at));
    }

    public Check check(String token) {
        if (token == null) {
            return Check.FORGED;
        }
        int dot = token.indexOf('.');
        if (dot <= 0) {
            return Check.FORGED;
        }
        String at = token.substring(0, dot);
        byte[] given;
        long issued;
        try {
            given = Base64.getUrlDecoder().decode(token.substring(dot + 1));
            issued = Long.parseLong(at);
        } catch (IllegalArgumentException e) {
            return Check.FORGED;
        }
        if (!MessageDigest.isEqual(given, sign(key, at))) {
            return Check.FORGED;
        }
        long age = clock.getAsLong() - issued;
        if (age < MIN_AGE_MS) {
            return Check.TOO_FAST;
        }
        return age > MAX_AGE_MS ? Check.EXPIRED : Check.VALID;
    }

    private static byte[] sign(byte[] key, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
