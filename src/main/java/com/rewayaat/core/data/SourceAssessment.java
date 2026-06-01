package com.rewayaat.core.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single Rijal source's assessment of a narrator.
 * Factual — only reports what the source says, no independent judgments.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SourceAssessment {

    @JsonProperty("source_name")
    private String sourceName;
    @JsonProperty("author")
    private String author;
    @JsonProperty("assessment_en")
    private String assessmentEn;
    @JsonProperty("assessment_ar")
    private String assessmentAr;

    public SourceAssessment() {
    }

    public SourceAssessment(String sourceName, String author, String assessmentEn, String assessmentAr) {
        this.sourceName = sourceName;
        this.author = author;
        this.assessmentEn = assessmentEn;
        this.assessmentAr = assessmentAr;
    }

    @JsonProperty("source_name")
    public String getSourceName() {
        return sourceName;
    }

    @JsonProperty("source_name")
    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    @JsonProperty("author")
    public String getAuthor() {
        return author;
    }

    @JsonProperty("author")
    public void setAuthor(String author) {
        this.author = author;
    }

    @JsonProperty("assessment_en")
    public String getAssessmentEn() {
        return assessmentEn;
    }

    @JsonProperty("assessment_en")
    public void setAssessmentEn(String assessmentEn) {
        this.assessmentEn = assessmentEn;
    }

    @JsonProperty("assessment_ar")
    public String getAssessmentAr() {
        return assessmentAr;
    }

    @JsonProperty("assessment_ar")
    public void setAssessmentAr(String assessmentAr) {
        this.assessmentAr = assessmentAr;
    }
}
