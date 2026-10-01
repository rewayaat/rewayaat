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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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

    @Value("${rewayaat.resend-api-key:}")
    private String resendApiKey;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Autowired
    private HadithEditorAccessService hadithEditorAccessService;

    @Autowired
    private UiMessages ui;

    /**
     * The language to write to this account in.
     *
     * <p>The account's own preference, not the language of whatever page triggered the
     * mail. A password reset is requested from a signed-out form that may be on either
     * site, and someone who set their account to Arabic should not get an English mail
     * because they happened to follow an English link.
     */
    private PageLocale localeOf(UserAccount user) {
        return PageLocale.ofTag(user == null ? null : user.getLocale());
    }

    /**
     * A message for the caller to show, in the language of the request.
     *
     * <p>Distinct from {@link #msg}, which writes in the account's own language. A
     * message follows the click; mail follows the reader.
     */
    private String say(String key, Object... args) {
        return ui.say(key, args);
    }

    private String msg(PageLocale locale, String key, Object... args) {
        return ui.in(locale, key, args);
    }

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
        return register(displayName, email, password, PageLocale.ENGLISH);
    }

    /**
     * @param signedUpIn the site the registration form was on, which seeds the account's
     *                   language. It is only a seed: the preference is theirs to change
     *                   afterwards, and {@link #updateLocale} is what changes it.
     */
    public Map<String, Object> register(String displayName, String email, String password,
                                        PageLocale signedUpIn) throws Exception {
        String normalizedEmail = normalizeEmail(email);
        if (normalizedEmail.isEmpty()) {
            return error(say("api.auth.invalidRegistration"));
        }
        String passwordIssue = validatePasswordPolicy(password);
        if (!passwordIssue.isEmpty()) {
            return error(passwordIssue);
        }

        UserAccount existing = findByEmail(normalizedEmail);
        if (existing != null && Boolean.TRUE.equals(existing.getVerified())) {
            return error(say("api.auth.emailTaken"));
        }

        long now = System.currentTimeMillis();
        String rawVerificationToken = generateToken();
        UserAccount user = existing == null ? new UserAccount() : existing;
        user.setEmail(normalizedEmail);
        user.setDisplayName(safeDisplayName(displayName, normalizedEmail));
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setLocale((signedUpIn == null ? PageLocale.ENGLISH : signedUpIn).tag());
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

        sendVerificationEmail(user, rawVerificationToken);
        Map<String, Object> payload = new HashMap<>();
        payload.put("ok", true);
        payload.put("message", say("api.auth.registered"));
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
            return error(say("api.auth.verifyMissing"));
        }
        UserAccount user = findByTokenHashField("verification_token_hash", hashToken(rawToken.trim()));
        if (user == null) {
            return error(say("api.auth.verifyInvalid"));
        }
        long now = System.currentTimeMillis();
        if (user.getVerificationTokenExpiry() == null || user.getVerificationTokenExpiry() < now) {
            return error(say("api.auth.verifyExpired"));
        }
        user.setVerified(true);
        user.setVerificationTokenHash(null);
        user.setVerificationTokenExpiry(null);
        user.setUpdatedAt(now);
        saveUser(user);
        Map<String, Object> payload = new HashMap<>();
        payload.put("ok", true);
        payload.put("message", say("api.auth.verified"));
        return payload;
    }

    public Map<String, Object> login(String email, String password) throws Exception {
        String normalizedEmail = normalizeEmail(email);
        if (normalizedEmail.isEmpty() || password == null || password.isEmpty()) {
            return error(say("api.auth.credentialsRequired"));
        }

        UserAccount user = findByEmail(normalizedEmail);
        if (user == null || user.getPasswordHash() == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            return error(say("api.auth.credentialsInvalid"));
        }
        if (!Boolean.TRUE.equals(user.getVerified())) {
            return error(say("api.auth.verifyFirst"));
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
                sendPasswordResetEmail(user, rawResetToken);
            }
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("ok", true);
        payload.put("message", say("api.auth.resetSent"));
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
            return error(say("api.auth.resetMissing"));
        }
        String passwordIssue = validatePasswordPolicy(newPassword);
        if (!passwordIssue.isEmpty()) {
            return error(passwordIssue);
        }
        UserAccount user = findByTokenHashField("reset_token_hash", hashToken(rawToken.trim()));
        if (user == null) {
            return error(say("api.auth.resetInvalid"));
        }
        long now = System.currentTimeMillis();
        if (user.getResetTokenExpiry() == null || user.getResetTokenExpiry() < now) {
            return error(say("api.auth.resetExpired"));
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
        payload.put("message", say("api.auth.passwordUpdated"));
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
        payload.put("locale", localeOf(user).tag());
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

    /**
     * The subject and body of one account mail, in the account's language.
     *
     * <p>Separate from sending so it can be read back in a test: the language of a mail
     * is not something the send path can be asked about afterwards.
     */
    Map<String, String> accountEmail(UserAccount user, String kind, String link, long hours) {
        PageLocale locale = localeOf(user);
        return Map.of(
                "subject", msg(locale, "email." + kind + ".subject"),
                "body", msg(locale, "email.greeting", user.getDisplayName()) + "\n\n"
                        + msg(locale, "email." + kind + ".body") + "\n"
                        + link + "\n\n"
                        + msg(locale, "email.expires", hours));
    }

    private void sendVerificationEmail(UserAccount user, String rawToken) {
        // Use a path-based URL to avoid tokens leaking via Referer headers
        String verifyUrl = buildVerifyUrl(rawToken);
        Map<String, String> mail = accountEmail(user, "verify", verifyUrl, verifyTokenHours);
        sendEmail(user.getEmail(), mail.get("subject"), mail.get("body"), verifyUrl);
    }

    private void sendPasswordResetEmail(UserAccount user, String rawToken) {
        // Use a path-based URL to avoid tokens leaking via Referer headers
        String resetUrl = buildResetUrl(rawToken);
        Map<String, String> mail = accountEmail(user, "reset", resetUrl, resetTokenHours);
        sendEmail(user.getEmail(), mail.get("subject"), mail.get("body"), resetUrl);
    }

    /**
     * Changes the language this account is written to in.
     *
     * <p>Reached both from the settings control and from the language switcher, so that
     * a reader who moves to the Arabic site while signed in is not then sent English
     * mail by an account preference they never knew they had.
     */
    public Map<String, Object> updateLocale(String sessionToken, String tag) throws Exception {
        UserAccount user = authenticatedUser(sessionToken);
        if (user == null) {
            return error(say("api.auth.signInToChangeLanguage"));
        }
        PageLocale chosen = PageLocale.ofTag(tag);
        // ofTag trims and lowercases before matching, so the check has to compare the same
        // form: "AR" is a language this site speaks, not an unsupported one.
        if (tag == null || !chosen.tag().equals(tag.trim().toLowerCase(java.util.Locale.ROOT))) {
            return error(say("api.auth.unsupportedLanguage", tag));
        }
        user.setLocale(chosen.tag());
        user.setUpdatedAt(System.currentTimeMillis());
        saveUser(user);
        Map<String, Object> payload = new HashMap<>();
        payload.put("ok", true);
        payload.put("locale", chosen.tag());
        return payload;
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
        if (resendApiKey == null || resendApiKey.isBlank()) {
            // The subject as well as the link: without it there is no way to tell locally
            // which language an account is being written to in.
            LOGGER.warn("Resend API key not configured. Email for {} [{}]: {}", to, subject, fallbackLink);
            return;
        }
        try {
            String jsonBody = mapper.writeValueAsString(Map.of(
                    "from", mailFrom,
                    "to", List.of(to),
                    "subject", subject,
                    "text", body));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.resend.com/emails"))
                    .header("Authorization", "Bearer " + resendApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                LOGGER.info("Email sent to {} via Resend.", to);
            } else {
                LOGGER.warn("Resend API returned {} for {}: {}", response.statusCode(), to, response.body());
            }
        } catch (Exception ex) {
            LOGGER.warn("Unable to send email to {} via Resend.", to, ex);
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
            return say("api.auth.passwordTooShort", passwordMinLength);
        }
        if (password.matches(".*\\s+.*")) {
            return say("api.auth.passwordHasSpaces");
        }
        return "";
    }
}
