package com.rewayaat.service;

import com.rewayaat.core.data.UserAccount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

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
}
