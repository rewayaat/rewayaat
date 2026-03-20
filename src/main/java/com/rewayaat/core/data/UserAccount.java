package com.rewayaat.core.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * User account document persisted in Elasticsearch.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserAccount {

    @JsonProperty("email")
    private String email;

    @JsonProperty("display_name")
    private String displayName;

    @JsonProperty("password_hash")
    private String passwordHash;

    @JsonProperty("verified")
    private Boolean verified = false;

    @JsonProperty("verification_token_hash")
    private String verificationTokenHash;

    @JsonProperty("verification_token_expiry")
    private Long verificationTokenExpiry;

    @JsonProperty("reset_token_hash")
    private String resetTokenHash;

    @JsonProperty("reset_token_expiry")
    private Long resetTokenExpiry;

    @JsonProperty("session_token_hash")
    private String sessionTokenHash;

    @JsonProperty("session_token_expiry")
    private Long sessionTokenExpiry;

    @JsonProperty("created_at")
    private Long createdAt;

    @JsonProperty("updated_at")
    private Long updatedAt;

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Boolean getVerified() {
        return verified;
    }

    public void setVerified(Boolean verified) {
        this.verified = verified;
    }

    public String getVerificationTokenHash() {
        return verificationTokenHash;
    }

    public void setVerificationTokenHash(String verificationTokenHash) {
        this.verificationTokenHash = verificationTokenHash;
    }

    public Long getVerificationTokenExpiry() {
        return verificationTokenExpiry;
    }

    public void setVerificationTokenExpiry(Long verificationTokenExpiry) {
        this.verificationTokenExpiry = verificationTokenExpiry;
    }

    public String getResetTokenHash() {
        return resetTokenHash;
    }

    public void setResetTokenHash(String resetTokenHash) {
        this.resetTokenHash = resetTokenHash;
    }

    public Long getResetTokenExpiry() {
        return resetTokenExpiry;
    }

    public void setResetTokenExpiry(Long resetTokenExpiry) {
        this.resetTokenExpiry = resetTokenExpiry;
    }

    public String getSessionTokenHash() {
        return sessionTokenHash;
    }

    public void setSessionTokenHash(String sessionTokenHash) {
        this.sessionTokenHash = sessionTokenHash;
    }

    public Long getSessionTokenExpiry() {
        return sessionTokenExpiry;
    }

    public void setSessionTokenExpiry(Long sessionTokenExpiry) {
        this.sessionTokenExpiry = sessionTokenExpiry;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
