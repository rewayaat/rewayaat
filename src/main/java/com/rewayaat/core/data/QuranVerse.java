package com.rewayaat.core.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Represents a Quranic verse (ayah) for storage in Elasticsearch.
 * Used for the Quranic Context feature to cross-reference hadith with relevant Quranic verses.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QuranVerse {

    @JsonProperty("surah_number")
    private final int surahNumber;

    @JsonProperty("ayah_number")
    private final int ayahNumber;

    @JsonProperty("ayah_index")
    private final int ayahIndex;

    @JsonProperty("text_arabic")
    private final String textArabic;

    @JsonProperty("text_english")
    private final String textEnglish;

    @JsonProperty("surah_name_arabic")
    private final String surahNameArabic;

    @JsonProperty("surah_name_english")
    private final String surahNameEnglish;

    @JsonProperty("surah_name_english_transliteration")
    private final String surahNameEnglishTransliteration;

    @JsonProperty("juz_number")
    private final Integer juzNumber;

    @JsonProperty("hizb_number")
    private final Integer hizbNumber;

    @JsonProperty("page_number")
    private final Integer pageNumber;

    @JsonProperty("revelation_type")
    private final String revelationType;

    @JsonProperty("topic_tags")
    private List<String> topicTags;

    private QuranVerse(Builder builder) {
        this.surahNumber = builder.surahNumber;
        this.ayahNumber = builder.ayahNumber;
        this.ayahIndex = builder.ayahIndex;
        this.textArabic = builder.textArabic;
        this.textEnglish = builder.textEnglish;
        this.surahNameArabic = builder.surahNameArabic;
        this.surahNameEnglish = builder.surahNameEnglish;
        this.surahNameEnglishTransliteration = builder.surahNameEnglishTransliteration;
        this.juzNumber = builder.juzNumber;
        this.hizbNumber = builder.hizbNumber;
        this.pageNumber = builder.pageNumber;
        this.revelationType = builder.revelationType;
        this.topicTags = builder.topicTags;
    }

    public int getSurahNumber() {
        return surahNumber;
    }

    public int getAyahNumber() {
        return ayahNumber;
    }

    public int getAyahIndex() {
        return ayahIndex;
    }

    public String getTextArabic() {
        return textArabic;
    }

    public String getTextEnglish() {
        return textEnglish;
    }

    public String getSurahNameArabic() {
        return surahNameArabic;
    }

    public String getSurahNameEnglish() {
        return surahNameEnglish;
    }

    public String getSurahNameEnglishTransliteration() {
        return surahNameEnglishTransliteration;
    }

    public Integer getJuzNumber() {
        return juzNumber;
    }

    public Integer getHizbNumber() {
        return hizbNumber;
    }

    public Integer getPageNumber() {
        return pageNumber;
    }

    public String getRevelationType() {
        return revelationType;
    }

    public List<String> getTopicTags() {
        return topicTags;
    }

    public void setTopicTags(List<String> topicTags) {
        this.topicTags = topicTags;
    }

    /**
     * Returns a unique identifier for this verse in format "surah:ayah" (e.g., "2:255")
     */
    public String getVerseId() {
        return surahNumber + ":" + ayahNumber;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int surahNumber;
        private int ayahNumber;
        private int ayahIndex;
        private String textArabic;
        private String textEnglish;
        private String surahNameArabic;
        private String surahNameEnglish;
        private String surahNameEnglishTransliteration;
        private Integer juzNumber;
        private Integer hizbNumber;
        private Integer pageNumber;
        private String revelationType;
        private List<String> topicTags;

        public Builder surahNumber(int surahNumber) {
            this.surahNumber = surahNumber;
            return this;
        }

        public Builder ayahNumber(int ayahNumber) {
            this.ayahNumber = ayahNumber;
            return this;
        }

        public Builder ayahIndex(int ayahIndex) {
            this.ayahIndex = ayahIndex;
            return this;
        }

        public Builder textArabic(String textArabic) {
            this.textArabic = textArabic;
            return this;
        }

        public Builder textEnglish(String textEnglish) {
            this.textEnglish = textEnglish;
            return this;
        }

        public Builder surahNameArabic(String surahNameArabic) {
            this.surahNameArabic = surahNameArabic;
            return this;
        }

        public Builder surahNameEnglish(String surahNameEnglish) {
            this.surahNameEnglish = surahNameEnglish;
            return this;
        }

        public Builder surahNameEnglishTransliteration(String surahNameEnglishTransliteration) {
            this.surahNameEnglishTransliteration = surahNameEnglishTransliteration;
            return this;
        }

        public Builder juzNumber(Integer juzNumber) {
            this.juzNumber = juzNumber;
            return this;
        }

        public Builder hizbNumber(Integer hizbNumber) {
            this.hizbNumber = hizbNumber;
            return this;
        }

        public Builder pageNumber(Integer pageNumber) {
            this.pageNumber = pageNumber;
            return this;
        }

        public Builder revelationType(String revelationType) {
            this.revelationType = revelationType;
            return this;
        }

        public Builder topicTags(List<String> topicTags) {
            this.topicTags = topicTags;
            return this;
        }

        public QuranVerse build() {
            if (textArabic == null || textArabic.isBlank()) {
                throw new IllegalArgumentException("textArabic is required");
            }
            if (textEnglish == null || textEnglish.isBlank()) {
                throw new IllegalArgumentException("textEnglish is required");
            }
            return new QuranVerse(this);
        }
    }
}
