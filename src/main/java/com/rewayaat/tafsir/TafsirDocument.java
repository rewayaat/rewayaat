package com.rewayaat.tafsir;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Represents a single tafsir document for indexing in Elasticsearch.
 * Each document is one commentary unit: a tafsir's commentary on a specific verse or verse range.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TafsirDocument {

    private String tafsirSlug;
    private String tafsirName;
    private Integer surahNumber;
    private Integer ayahStart;
    private Integer ayahEnd;
    private String verseKey;
    private List<String> verseKeys;
    private String verseTextEnglish;
    private String commentaryText;
    private String sectionTitle;
    private Integer commentaryWordCount;
    private String volume;
    private String sourceUrl;
    private String language;

    public TafsirDocument() {
        this.language = "en";
    }

    public String getTafsirSlug() {
        return tafsirSlug;
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
        if (tafsirSlug != null && verseKey != null) {
            return tafsirSlug + "_" + verseKey;
        }
        return null;
    }

    /**
     * Computes the commentary word count from the text.
     */
    public void computeWordCount() {
        if (commentaryText != null && !commentaryText.isEmpty()) {
            this.commentaryWordCount = commentaryText.split("\\s+").length;
        } else {
            this.commentaryWordCount = 0;
        }
    }
}
