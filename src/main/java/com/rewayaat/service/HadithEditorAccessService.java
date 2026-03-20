package com.rewayaat.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves whether a user email is on the static hadith-editor allowlist.
 */
@Service
public class HadithEditorAccessService {

    private static final Logger LOGGER = LoggerFactory.getLogger(HadithEditorAccessService.class);

    @Value("${rewayaat.hadith-editor-allowlist:classpath:admins.txt}")
    private Resource allowlistResource;

    private Set<String> allowedEmails = Collections.emptySet();

    @PostConstruct
    public void loadAllowlist() {
        if (allowlistResource == null || !allowlistResource.exists()) {
            LOGGER.warn("Hadith editor allowlist resource is missing.");
            allowedEmails = Collections.emptySet();
            return;
        }
        LinkedHashSet<String> emails = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(allowlistResource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String normalized = normalizeEmail(line);
                if (!normalized.isEmpty()) {
                    emails.add(normalized);
                }
            }
            allowedEmails = Collections.unmodifiableSet(emails);
        } catch (Exception ex) {
            LOGGER.warn("Unable to load hadith editor allowlist.", ex);
            allowedEmails = Collections.emptySet();
        }
    }

    public boolean canEdit(String email) {
        String normalized = normalizeEmail(email);
        return !normalized.isEmpty() && allowedEmails.contains(normalized);
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            return "";
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
