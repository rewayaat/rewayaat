package com.rewayaat.service;

import com.rewayaat.core.data.UserAccount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mail an account receives is written in the language that account chose.
 *
 * <p>{@code AuthServiceTest} builds its own message source, which proves the strings
 * exist but not that the application reaches them. This runs against the autoconfigured
 * bean the rest of the site renders through, so a basename or encoding that only works
 * in the unit test would fail here.
 */
@SpringBootTest
class AccountEmailLocaleIntegrationTest {

    @Autowired
    private AuthService authService;

    private static UserAccount account(String locale) {
        UserAccount user = new UserAccount();
        user.setEmail("reader@example.test");
        user.setDisplayName("Reader");
        user.setLocale(locale);
        return user;
    }

    @Test
    @DisplayName("an Arabic account is written to in Arabic, an English one in English")
    void mailFollowsTheAccountsLanguage() {
        Map<String, String> arabic =
                authService.accountEmail(account("ar"), "verify", "https://example.test/v", 48L);
        Map<String, String> english =
                authService.accountEmail(account("en"), "verify", "https://example.test/v", 48L);

        assertTrue(arabic.get("subject").matches(".*[\\u0600-\\u06FF].*"),
                "expected an Arabic subject, got: " + arabic.get("subject"));
        assertFalse(arabic.get("body").matches(".*[A-Za-z]{4}.*(?<!example)\\.test.*")
                        && arabic.get("body").contains("Assalamu"),
                "the English greeting leaked into an Arabic mail");
        assertTrue(english.get("subject").matches("[\\p{ASCII}]+"),
                "expected an ASCII subject for an English account, got: " + english.get("subject"));

        // The two have to actually differ; a bundle that silently fell back to English
        // would pass every check above except this one.
        assertFalse(arabic.get("subject").equals(english.get("subject")),
                "both languages produced the same subject, so the Arabic bundle was not reached");
    }

    @Test
    @DisplayName("the link in the mail lands on the site the reader signed up to")
    void theLinkFollowsTheAccountsLanguageToo() {
        // The prose was translated and the link was not, so an Arabic reader got Arabic
        // mail and an English page to verify on — and stayed on the English site
        // afterwards, because that is where the link had put them.
        String arabic = authService.buildVerifyUrl("tok", PageLocale.ARABIC);
        String english = authService.buildVerifyUrl("tok", PageLocale.ENGLISH);

        assertTrue(arabic.contains("/ar/auth/verify"),
                "an Arabic account's verify link must stay on the Arabic site: " + arabic);
        assertFalse(english.contains("/ar/"),
                "an English account's link must not acquire a language prefix: " + english);

        assertEquals(arabic, english.replace("/auth/verify", "/ar/auth/verify"),
                "the two links differ by more than the prefix");

        // And the same for the reset mail, which is the other half nobody looks at.
        assertTrue(authService.buildResetUrl("tok", PageLocale.ARABIC).contains("/ar/auth/reset"));
        assertFalse(authService.buildResetUrl("tok", PageLocale.ENGLISH).contains("/ar/"));
    }

    @Test
    @DisplayName("both of those paths are reachable in Arabic")
    void theLinkIsNotAFourOhFour() {
        // Prefixing the link is only an improvement if the prefixed path exists. It did
        // not: /ar/auth/verify answered 404 while /auth/verify redirected, so shipping
        // the prefix on its own would have replaced a wrong-language page with no page.
        assertTrue(PageLocale.hasArabicVersion("/auth/verify"),
                "the verify link is prefixed for Arabic accounts, so /ar/auth/verify has "
                        + "to be a path the prefix filter will serve rather than 404");
        assertTrue(PageLocale.hasArabicVersion("/auth/reset"),
                "likewise the reset link");
    }
}
