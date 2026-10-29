package com.rewayaat.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContactFormTokenTest {

    private long now = 1_800_000_000_000L;

    private ContactFormToken tokens(String secret) {
        ContactFormToken tokens = new ContactFormToken(secret);
        tokens.setClock(() -> now);
        return tokens;
    }

    @Test
    void aTokenIsValidBetweenItsMinimumAndMaximumAge() {
        ContactFormToken tokens = tokens("secret");
        String token = tokens.issue();
        assertEquals(ContactFormToken.Check.TOO_FAST, tokens.check(token));
        now += ContactFormToken.MIN_AGE_MS;
        assertEquals(ContactFormToken.Check.VALID, tokens.check(token));
        now += ContactFormToken.MAX_AGE_MS;
        assertEquals(ContactFormToken.Check.EXPIRED, tokens.check(token));
    }

    @Test
    void anotherPodWithTheSameSecretAcceptsIt() {
        String token = tokens("secret").issue();
        now += 10_000L;
        assertEquals(ContactFormToken.Check.VALID, tokens("secret").check(token));
        assertEquals(ContactFormToken.Check.FORGED, tokens("other").check(token));
    }

    @Test
    void aChangedTimestampBreaksTheSignature() {
        ContactFormToken tokens = tokens("secret");
        String token = tokens.issue();
        now += 10_000L;
        String backdated = (Long.parseLong(token.substring(0, token.indexOf('.'))) - 5_000L)
                + token.substring(token.indexOf('.'));
        assertEquals(ContactFormToken.Check.FORGED, tokens.check(backdated));
    }

    @Test
    void garbageIsForged() {
        ContactFormToken tokens = tokens("secret");
        for (String junk : new String[] {null, "", ".", "abc", "123.", "x.y", "123.!!!"}) {
            assertEquals(ContactFormToken.Check.FORGED, tokens.check(junk), String.valueOf(junk));
        }
    }

    @Test
    void withoutASecretEachProcessSignsWithItsOwnKey() {
        String token = tokens("").issue();
        now += 10_000L;
        assertEquals(ContactFormToken.Check.FORGED, tokens("").check(token));
    }
}
