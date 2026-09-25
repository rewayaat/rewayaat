package com.rewayaat.service;

import com.rewayaat.core.data.UserAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure logic inside {@link AuthService} that does not require an
 * Elasticsearch connection (password policy, token hashing, public-user projection, etc.).
 */
class AuthServiceTest {

    private AuthService service;
    private HadithEditorAccessService hadithEditorAccessService;

    @BeforeEach
    void setUp() {
        service = new AuthService();
        hadithEditorAccessService = new HadithEditorAccessService();
        // Inject @Value defaults via ReflectionTestUtils so the service works without Spring context
        ReflectionTestUtils.setField(service, "usersIndex", "rewayaat_users");
        ReflectionTestUtils.setField(service, "appBaseUrl", "http://localhost:8080");
        ReflectionTestUtils.setField(service, "mailFrom", "no-reply@rewayaat.local");
        ReflectionTestUtils.setField(service, "verifyTokenHours", 48L);
        ReflectionTestUtils.setField(service, "resetTokenHours", 2L);
        ReflectionTestUtils.setField(service, "sessionHours", 720L);
        ReflectionTestUtils.setField(service, "passwordMinLength", 6);
        ReflectionTestUtils.setField(service, "exposeDebugTokens", false);
        ReflectionTestUtils.setField(
                hadithEditorAccessService,
                "allowedEmails",
                java.util.Set.of("test@example.com"));
        ReflectionTestUtils.setField(service, "hadithEditorAccessService", hadithEditorAccessService);

        // The real bundle, not a stub: what is being checked is that the Arabic strings
        // exist and are reached, which a stub would hide.
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        ReflectionTestUtils.setField(service, "messages", messages);
    }

    private static UserAccount account(String locale) {
        UserAccount user = new UserAccount();
        user.setEmail("test@example.com");
        user.setDisplayName("Tester");
        user.setVerified(true);
        user.setLocale(locale);
        return user;
    }

    // ---- the language an account is written to in ----

    @Test
    void accountEmail_isWrittenInTheAccountsLanguage() {
        Map<String, String> arabic =
                service.accountEmail(account("ar"), "verify", "https://example.test/v", 48L);

        assertTrue(arabic.get("subject").matches(".*[\\u0600-\\u06FF].*"),
                "an Arabic account should be sent an Arabic subject, got: " + arabic.get("subject"));
        assertFalse(arabic.get("body").contains("Assalamu alaykum"),
                "the English greeting leaked into an Arabic mail: " + arabic.get("body"));
        assertTrue(arabic.get("body").contains("https://example.test/v"),
                "the link has to survive translation");
    }

    @Test
    void accountEmail_fallsBackToEnglishForAccountsWithNoPreference() {
        // Every account created before the preference existed holds no tag, and English
        // is what those accounts have been receiving all along.
        Map<String, String> mail =
                service.accountEmail(account(null), "reset", "https://example.test/r", 2L);

        assertTrue(mail.get("subject").startsWith("Reset your"), mail.get("subject"));
        assertTrue(mail.get("body").contains("Assalamu alaykum Tester"), mail.get("body"));
    }

    @Test
    void accountEmail_namesTheSiteAsReadersKnowIt() {
        for (String locale : new String[]{null, "ar"}) {
            Map<String, String> mail =
                    service.accountEmail(account(locale), "verify", "https://example.test/v", 48L);
            assertFalse(mail.get("subject").contains("Rewayaat"),
                    "the repository's name is not the site's name: " + mail.get("subject"));
        }
    }

    @Test
    void publicUser_reportsTheStoredLanguage() {
        // The client needs to know which way the toggle is currently set for the account,
        // not merely which page it happens to be on.
        assertEquals("ar", service.publicUser(account("ar")).get("locale"));
        assertEquals("en", service.publicUser(account(null)).get("locale"));
    }

    // ---- publicUser ----

    @Test
    void publicUser_exposesExpectedFields() {
        UserAccount user = new UserAccount();
        user.setEmail("test@example.com");
        user.setDisplayName("Tester");
        user.setVerified(true);

        Map<String, Object> result = service.publicUser(user);

        assertEquals("test@example.com", result.get("email"));
        assertEquals("Tester", result.get("displayName"));
        assertEquals(Boolean.TRUE, result.get("verified"));
        assertEquals(Boolean.TRUE, result.get("canEditHadith"));
        assertFalse(result.containsKey("passwordHash"), "Password hash must not be exposed");
    }

    @Test
    void publicUser_treatsNullVerifiedAsFalse() {
        UserAccount user = new UserAccount();
        user.setEmail("a@b.com");
        user.setVerified(null);

        Map<String, Object> result = service.publicUser(user);

        assertEquals(Boolean.FALSE, result.get("verified"));
        assertEquals(Boolean.FALSE, result.get("canEditHadith"));
    }

    // ---- sessionTtlSeconds ----

    @Test
    void sessionTtlSeconds_isPositive() {
        assertTrue(service.sessionTtlSeconds() > 0);
    }

    @Test
    void sessionTtlSeconds_matchesConfiguredHours() {
        // Default is 720 hours = 2,592,000 seconds
        assertEquals(720L * 60L * 60L, service.sessionTtlSeconds());
    }

    // ---- AUTH_COOKIE constant ----

    @Test
    void authCookieConstantIsDefined() {
        assertNotNull(AuthService.AUTH_COOKIE);
        assertFalse(AuthService.AUTH_COOKIE.isBlank());
    }

    @Test
    void buildVerifyUrl_usesAuthVerifyRedirectPath() {
        assertEquals("http://localhost:8080/auth/verify?token=abc123", service.buildVerifyUrl("abc123"));
    }

    @Test
    void buildResetUrl_usesAuthResetRedirectPath() {
        assertEquals("http://localhost:8080/auth/reset?token=abc123", service.buildResetUrl("abc123"));
    }

    @Test
    void debugTokenPayload_returnsNullWhenDisabled() {
        assertEquals(null, service.debugTokenPayload("verificationToken", "verificationUrl", "abc", "http://localhost"));
    }

    @Test
    void debugTokenPayload_returnsTokenAndUrlWhenEnabled() {
        ReflectionTestUtils.setField(service, "exposeDebugTokens", true);

        Map<String, String> debug = service.debugTokenPayload(
                "verificationToken",
                "verificationUrl",
                "abc123",
                "http://localhost:8080/auth/verify?token=abc123");

        assertEquals("abc123", debug.get("verificationToken"));
        assertEquals("http://localhost:8080/auth/verify?token=abc123", debug.get("verificationUrl"));
    }
}
