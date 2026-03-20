package com.rewayaat.service;

import com.rewayaat.core.data.UserAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
