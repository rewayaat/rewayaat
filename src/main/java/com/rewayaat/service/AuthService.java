package com.rewayaat.service;

import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.config.ESClientProvider;
import com.rewayaat.core.data.UserAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Authentication and account management using Elasticsearch as the backing store.
 */
@Service
public class AuthService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthService.class);

    public static final String AUTH_COOKIE = "RWY_SESSION";

    @Value("${rewayaat.users-index:rewayaat_users}")
    private String usersIndex;

    @Value("${rewayaat.app-base-url:http://localhost:8080}")
    private String appBaseUrl;

    @Value("${rewayaat.mail-from:no-reply@rewayaat.local}")
    private String mailFrom;

    @Value("${rewayaat.auth.verify-token-hours:48}")
    private long verifyTokenHours;

    @Value("${rewayaat.auth.reset-token-hours:2}")
    private long resetTokenHours;

    @Value("${rewayaat.auth.session-hours:720}")
    private long sessionHours;

    @Value("${rewayaat.auth.password-min-length:6}")
    private int passwordMinLength;

    @Value("${rewayaat.auth.expose-debug-tokens:false}")
    private boolean exposeDebugTokens;

    private final SecureRandom secureRandom = new SecureRandom();
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Autowired
    private HadithEditorAccessService hadithEditorAccessService;

    private long verifyTtlMs() {
        return verifyTokenHours * 60L * 60L * 1000L;
    }

    private long resetTtlMs() {
        return resetTokenHours * 60L * 60L * 1000L;
    }

    private long sessionTtlMs() {
        return sessionHours * 60L * 60L * 1000L;
    }

    public Map<String, Object> register(String displayName, String email, String password) throws Exception {
        String normalizedEmail = normalizeEmail(email);
        if (normalizedEmail.isEmpty()) {
            return error("Invalid registration payload. Use a valid email.");
        }
        String passwordIssue = validatePasswordPolicy(password);
        if (!passwordIssue.isEmpty()) {
            return error(passwordIssue);
        }

        UserAccount existing = findByEmail(normalizedEmail);
        if (existing != null && Boolean.TRUE.equals(existing.getVerified())) {
            return error("An account with this email already exists.");
        }

        long now = System.currentTimeMillis();
        String rawVerificationToken = generateToken();
        UserAccount user = existing == null ? new UserAccount() : existing;
        user.setEmail(normalizedEmail);
        user.setDisplayName(safeDisplayName(displayName, normalizedEmail));
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setVerified(false);
        user.setVerificationTokenHash(hashToken(rawVerificationToken));
        user.setVerificationTokenExpiry(now + verifyTtlMs());
        user.setResetTokenHash(null);
        user.setResetTokenExpiry(null);
        user.setSessionTokenHash(null);
        user.setSessionTokenExpiry(null);
        if (user.getCreatedAt() == null) {
            user.setCreatedAt(now);
        }
        user.setUpdatedAt(now);
        saveUser(user);

        sendVerificationEmail(user.getEmail(), user.getDisplayName(), rawVerificationToken);
        Map<String, Object> payload = new HashMap<>();
        payload.put("ok", true);
        payload.put("message", "Registration successful. Check your email to verify your account.");
        Map<String, String> debug = debugTokenPayload(
                "verificationToken",
                "verificationUrl",
                rawVerificationToken,
                buildVerifyUrl(rawVerificationToken));
        if (debug != null) {
            payload.put("debug", debug);
        }
        return payload;
    }

    public Map<String, Object> verifyEmailToken(String rawToken) throws Exception {
        if (rawToken == null || rawToken.trim().isEmpty()) {
            return error("Verification token is missing.");
        }
        UserAccount user = findByTokenHashField("verification_token_hash", hashToken(rawToken.trim()));
        if (user == null) {
            return error("Verification token is invalid.");
        }
        long now = System.currentTimeMillis();
        if (user.getVerificationTokenExpiry() == null || user.getVerificationTokenExpiry() < now) {
            return error("Verification token has expired.");
        }
        user.setVerified(true);
        user.setVerificationTokenHash(null);
        user.setVerificationTokenExpiry(null);
        user.setUpdatedAt(now);
        saveUser(user);
        Map<String, Object> payload = new HashMap<>();
        payload.put("ok", true);
        payload.put("message", "Email verified successfully. You can now log in.");
        return payload;
    }

    public Map<String, Object> login(String email, String password) throws Exception {
        String normalizedEmail = normalizeEmail(email);
        if (normalizedEmail.isEmpty() || password == null || password.isEmpty()) {
            return error("Email and password are required.");
        }

        UserAccount user = findByEmail(normalizedEmail);
        if (user == null || user.getPasswordHash() == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            return error("Invalid email or password.");
        }
        if (!Boolean.TRUE.equals(user.getVerified())) {
            return error("Please verify your email before logging in.");
        }

        long now = System.currentTimeMillis();
        String rawSessionToken = generateToken();
        user.setSessionTokenHash(hashToken(rawSessionToken));
        user.setSessionTokenExpiry(now + sessionTtlMs());
        user.setUpdatedAt(now);
        saveUser(user);

        Map<String, Object> payload = new HashMap<>();
        payload.put("ok", true);
        payload.put("token", rawSessionToken);
        payload.put("user", publicUser(user));
        return payload;
    }

    public void logout(String rawSessionToken) throws Exception {
        UserAccount user = authenticatedUser(rawSessionToken);
        if (user == null) {
            return;
        }
        user.setSessionTokenHash(null);
        user.setSessionTokenExpiry(null);
        user.setUpdatedAt(System.currentTimeMillis());
        saveUser(user);
    }

    public Map<String, Object> requestPasswordReset(String email) throws Exception {
        String normalizedEmail = normalizeEmail(email);
        String rawResetToken = null;
        if (!normalizedEmail.isEmpty()) {
            UserAccount user = findByEmail(normalizedEmail);
            if (user != null && Boolean.TRUE.equals(user.getVerified())) {
                long now = System.currentTimeMillis();
                rawResetToken = generateToken();
                user.setResetTokenHash(hashToken(rawResetToken));
                user.setResetTokenExpiry(now + resetTtlMs());
                user.setUpdatedAt(now);
                saveUser(user);
                sendPasswordResetEmail(user.getEmail(), user.getDisplayName(), rawResetToken);
            }
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("ok", true);
        payload.put("message", "If an account exists, a reset email has been sent.");
        Map<String, String> debug = debugTokenPayload(
                "resetToken",
                "resetUrl",
                rawResetToken,
                buildResetUrl(rawResetToken));
        if (debug != null) {
            payload.put("debug", debug);
        }
        return payload;
    }

    public Map<String, Object> confirmPasswordReset(String rawToken, String newPassword) throws Exception {
        if (rawToken == null || rawToken.trim().isEmpty()) {
            return error("Reset token is missing.");
        }
        String passwordIssue = validatePasswordPolicy(newPassword);
        if (!passwordIssue.isEmpty()) {
            return error(passwordIssue);
        }
        UserAccount user = findByTokenHashField("reset_token_hash", hashToken(rawToken.trim()));
        if (user == null) {
            return error("Reset token is invalid.");
        }
        long now = System.currentTimeMillis();
        if (user.getResetTokenExpiry() == null || user.getResetTokenExpiry() < now) {
            return error("Reset token has expired.");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setResetTokenHash(null);
        user.setResetTokenExpiry(null);
        user.setSessionTokenHash(null);
        user.setSessionTokenExpiry(null);
        user.setUpdatedAt(now);
        saveUser(user);
        Map<String, Object> payload = new HashMap<>();
        payload.put("ok", true);
        payload.put("message", "Password updated. Please log in with your new password.");
        return payload;
    }

    public UserAccount authenticatedUser(String rawSessionToken) throws Exception {
        if (rawSessionToken == null || rawSessionToken.trim().isEmpty()) {
            return null;
        }
        UserAccount user = findByTokenHashField("session_token_hash", hashToken(rawSessionToken.trim()));
        if (user == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (user.getSessionTokenExpiry() == null || user.getSessionTokenExpiry() < now) {
            user.setSessionTokenHash(null);
            user.setSessionTokenExpiry(null);
            user.setUpdatedAt(now);
            saveUser(user);
            return null;
        }
        return user;
    }

    public Map<String, Object> publicUser(UserAccount user) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("email", user.getEmail());
        payload.put("displayName", user.getDisplayName());
        payload.put("verified", Boolean.TRUE.equals(user.getVerified()));
        payload.put("canEditHadith", hadithEditorAccessService != null
                && hadithEditorAccessService.canEdit(user.getEmail()));
        return payload;
    }

    public long sessionTtlSeconds() {
        return Math.max(sessionTtlMs() / 1000L, 60L);
    }

    private void saveUser(UserAccount user) throws Exception {
        try (ESClientProvider provider = new ESClientProvider()) {
            provider.client().index(i -> i
                    .index(usersIndex)
                    .id(user.getEmail())
                    .document(user)
                    .refresh(Refresh.True));
        }
    }

    private UserAccount findByEmail(String normalizedEmail) throws Exception {
        if (normalizedEmail == null || normalizedEmail.isEmpty()) {
            return null;
        }
        try (ESClientProvider provider = new ESClientProvider()) {
            GetResponse<Map> resp = provider.client().get(g -> g.index(usersIndex).id(normalizedEmail), Map.class);
            if (!resp.found() || resp.source() == null) {
                return null;
            }
            Map<String, Object> map = new HashMap<>(resp.source());
            map.put("email", normalizedEmail);
            return mapper.convertValue(map, UserAccount.class);
        } catch (Exception ex) {
            if (isIndexMissing(ex)) {
                return null;
            }
            throw ex;
        }
    }

    private UserAccount findByTokenHashField(String field, String tokenHash) throws Exception {
        if (tokenHash == null || tokenHash.isEmpty()) {
            return null;
        }
        try (ESClientProvider provider = new ESClientProvider()) {
            SearchResponse<Map> response = provider.client().search(s -> s
                    .index(usersIndex)
                    .size(1)
                    .query(q -> q.term(t -> t.field(field + ".keyword").value(tokenHash))), Map.class);
            List<Hit<Map>> hits = response.hits().hits();
            if (hits == null || hits.isEmpty()) {
                return null;
            }
            Hit<Map> hit = hits.get(0);
            Map<String, Object> source = hit.source();
            if (source == null) {
                return null;
            }
            Map<String, Object> map = new HashMap<>(source);
            map.put("email", hit.id());
            return mapper.convertValue(map, UserAccount.class);
        } catch (Exception ex) {
            if (isIndexMissing(ex)) {
                return null;
            }
            throw ex;
        }
    }

    private void sendVerificationEmail(String to, String displayName, String rawToken) {
        String subject = "Verify your Rewayaat account";
        // Use a path-based URL to avoid tokens leaking via Referer headers
        String verifyUrl = buildVerifyUrl(rawToken);
        String body = "Assalamu alaykum " + displayName + ",\n\n"
                + "Please verify your account by opening this link:\n"
                + verifyUrl + "\n\n"
                + "This link expires in " + verifyTokenHours + " hours.";
        sendEmail(to, subject, body, verifyUrl);
    }

    private void sendPasswordResetEmail(String to, String displayName, String rawToken) {
        String subject = "Reset your Rewayaat password";
        // Use a path-based URL to avoid tokens leaking via Referer headers
        String resetUrl = buildResetUrl(rawToken);
        String body = "Assalamu alaykum " + displayName + ",\n\n"
                + "Reset your password using this link:\n"
                + resetUrl + "\n\n"
                + "This link expires in " + resetTokenHours + " hours.";
        sendEmail(to, subject, body, resetUrl);
    }

    String buildVerifyUrl(String rawToken) {
        return appBaseUrl + "/auth/verify?token=" + rawToken;
    }

    String buildResetUrl(String rawToken) {
        return appBaseUrl + "/auth/reset?token=" + rawToken;
    }

    Map<String, String> debugTokenPayload(String tokenKey, String urlKey, String rawToken, String rawUrl) {
        if (!exposeDebugTokens || rawToken == null || rawToken.isBlank() || rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        Map<String, String> debug = new HashMap<>();
        debug.put(tokenKey, rawToken);
        debug.put(urlKey, rawUrl);
        return debug;
    }

    private void sendEmail(String to, String subject, String body, String fallbackLink) {
        if (mailSender == null) {
            LOGGER.warn("Mail sender is not configured. Email link for {}: {}", to, fallbackLink);
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setFrom(mailFrom);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
        } catch (Exception ex) {
            LOGGER.warn("Unable to send email to {}.", to, ex);
        }
    }

    private boolean isIndexMissing(Exception ex) {
        String message = ex.getMessage();
        if (message == null) {
            return false;
        }
        return message.contains("index_not_found_exception") || message.contains("no such index");
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String rawToken) {
        // SHA-256 of a 32-byte cryptographically random token is sufficient for
        // lookup-only token storage (no need for bcrypt's slow KDF here).
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
        } catch (java.security.NoSuchAlgorithmException ex) {
            // SHA-256 is always available in the JVM
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("ok", false);
        payload.put("message", message);
        return payload;
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            return "";
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String safeDisplayName(String displayName, String email) {
        if (displayName != null && !displayName.trim().isEmpty()) {
            return displayName.trim();
        }
        int at = email.indexOf("@");
        if (at > 0) {
            return email.substring(0, at);
        }
        return email;
    }

    private String validatePasswordPolicy(String password) {
        if (password == null || password.length() < passwordMinLength) {
            return "Password must be at least " + passwordMinLength + " characters.";
        }
        if (password.matches(".*\\s+.*")) {
            return "Password cannot contain spaces.";
        }
        return "";
    }
}
