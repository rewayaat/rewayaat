package com.rewayaat.tafsir;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Represents a single tafsir document for indexing in Elasticsearch.
 * Each document is one commentary unit: a tafsir's commentary on a specific verse or verse range.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TafsirDocument {

    @JsonIgnore
    private String documentId;

    private String tafsirSlug;
    private String tafsirName;
    private Integer surahNumber;
    private Integer ayahStart;
    private Integer ayahEnd;
    private String verseKey;
    private List<String> verseKeys;
    private String verseTextEnglish;
    private String commentaryText;
    private String commentaryTextArabic;
    private String commentaryTextEnglish;
    private String sectionTitle;
    private Integer commentaryWordCount;
    private String volume;
    private String sourceUrl;
    private String language;

    public TafsirDocument() {
        // Language not set by default - must be explicitly set by extractor
        this.language = null;
    }

    public String getTafsirSlug() {
        return tafsirSlug;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public void setTafsirSlug(String tafsirSlug) {
        this.tafsirSlug = tafsirSlug;
    }

    public String getTafsirName() {
        return tafsirName;
    }

    public void setTafsirName(String tafsirName) {
        this.tafsirName = tafsirName;
    }

    public Integer getSurahNumber() {
        return surahNumber;
    }

    public void setSurahNumber(Integer surahNumber) {
        this.surahNumber = surahNumber;
    }

    public Integer getAyahStart() {
        return ayahStart;
    }

    public void setAyahStart(Integer ayahStart) {
        this.ayahStart = ayahStart;
    }

    public Integer getAyahEnd() {
        return ayahEnd;
    }

    public void setAyahEnd(Integer ayahEnd) {
        this.ayahEnd = ayahEnd;
    }

    public String getVerseKey() {
        return verseKey;
    }

    public void setVerseKey(String verseKey) {
        this.verseKey = verseKey;
    }

    public List<String> getVerseKeys() {
        return verseKeys;
    }

    public void setVerseKeys(List<String> verseKeys) {
        this.verseKeys = verseKeys;
    }

    public String getVerseTextEnglish() {
        return verseTextEnglish;
    }

    public void setVerseTextEnglish(String verseTextEnglish) {
        this.verseTextEnglish = verseTextEnglish;
    }

    public String getCommentaryText() {
        return commentaryText;
    }

    public void setCommentaryText(String commentaryText) {
        this.commentaryText = commentaryText;
    }

    public String getCommentaryTextArabic() {
        return commentaryTextArabic;
    }

    public void setCommentaryTextArabic(String commentaryTextArabic) {
        this.commentaryTextArabic = commentaryTextArabic;
    }

    public String getCommentaryTextEnglish() {
        return commentaryTextEnglish;
    }

    public void setCommentaryTextEnglish(String commentaryTextEnglish) {
        this.commentaryTextEnglish = commentaryTextEnglish;
    }

    public String getSectionTitle() {
        return sectionTitle;
    }

    public void setSectionTitle(String sectionTitle) {
        this.sectionTitle = sectionTitle;
    }

    public Integer getCommentaryWordCount() {
        return commentaryWordCount;
    }

    public void setCommentaryWordCount(Integer commentaryWordCount) {
        this.commentaryWordCount = commentaryWordCount;
    }

    public String getVolume() {
        return volume;
    }

    public void setVolume(String volume) {
        this.volume = volume;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    /**
     * Computes the deterministic _id for this document.
     * Format: tafsir_slug + "_" + verse_key
     * For multi-verse sections, uses the first verse (ayah_start).
     */
    public String getId() {
        if (documentId != null && !documentId.isBlank()) {
            return documentId;
        }
        if (tafsirSlug != null && verseKey != null) {
            return tafsirSlug + "_" + verseKey;
        }
        if (tafsirSlug != null) {
            String fallback = sourceUrl != null ? sourceUrl : sectionTitle;
            if (fallback != null && !fallback.isBlank()) {
                return tafsirSlug + "_" + sha1Hex(fallback).substring(0, 16);
            }
        }
        return null;
    }

    /**
     * Computes the commentary word count from the text.
     * Handles both English and Arabic text (whitespace-delimited in both languages).
     */
    public void computeWordCount() {
        if (commentaryText != null && !commentaryText.isEmpty()) {
            // Both Arabic and English use whitespace as word delimiter
            this.commentaryWordCount = commentaryText.trim().split("\\s+").length;
        } else {
            this.commentaryWordCount = 0;
        }
    }

    private static String sha1Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 algorithm not available", e);
        }
    }
}
