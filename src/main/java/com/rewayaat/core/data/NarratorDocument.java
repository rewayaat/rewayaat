package com.rewayaat.core.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class NarratorDocument implements Serializable {

    @JsonProperty("_id")
    private String id;
    @JsonProperty("primary_arabic_name")
    private String primaryArabicName;
    @JsonProperty("primary_english_name")
    private String primaryEnglishName;
    @JsonProperty("arabic_aliases")
    private List<String> arabicAliases = new ArrayList<>();
    @JsonProperty("english_aliases")
    private List<String> englishAliases = new ArrayList<>();
    @JsonProperty("kunyah_arabic")
    private String kunyahArabic;
    @JsonProperty("kunyah_english")
    private String kunyahEnglish;
    @JsonProperty("titles")
    private List<String> titles = new ArrayList<>();
    @JsonProperty("scholarly_names")
    private List<String> scholarlyNames = new ArrayList<>();
    @JsonProperty("is_imam_or_prophet")
    private boolean imamOrProphet;
    @JsonProperty("is_doubtful")
    private boolean doubtful;
    @JsonProperty("doubtful_reason")
    private String doubtfulReason;
    @JsonProperty("reliability_grade")
    private String reliabilityGrade;
    @JsonProperty("reliability_sources")
    private List<String> reliabilitySources = new ArrayList<>();
    @JsonProperty("source_assessments")
    private List<SourceAssessment> sourceAssessments = new ArrayList<>();
    @JsonProperty("biography_summary_en")
    private String biographySummaryEn;
    @JsonProperty("biography_summary_ar")
    private String biographySummaryAr;
    @JsonProperty("rijal_sources")
    private List<String> rijalSources = new ArrayList<>();
    @JsonProperty("death_year_hijri")
    private String deathYearHijri;
    @JsonProperty("birth_year_hijri")
    private String birthYearHijri;
    @JsonProperty("tribe_or_city")
    private String tribeOrCity;
    @JsonProperty("generation")
    private String generation;
    @JsonProperty("gender")
    private String gender;
    @JsonProperty("hadith_count")
    private int hadithCount;
    @JsonProperty("normalized_arabic")
    private String normalizedArabic;
    @JsonProperty("normalized_english")
    private String normalizedEnglish;
    @JsonProperty("created_at")
    private String createdAt;
    @JsonProperty("updated_at")
    private String updatedAt;

    private static final long serialVersionUID = 1L;

    public NarratorDocument() {
    }

    @JsonProperty("_id")
    public String getId() {
        return id;
    }

    @JsonProperty("_id")
    public void setId(String id) {
        this.id = id;
    }

    @JsonProperty("primary_arabic_name")
    public String getPrimaryArabicName() {
        return primaryArabicName;
    }

    @JsonProperty("primary_arabic_name")
    public void setPrimaryArabicName(String primaryArabicName) {
        this.primaryArabicName = primaryArabicName;
    }

    @JsonProperty("primary_english_name")
    public String getPrimaryEnglishName() {
        return primaryEnglishName;
    }

    @JsonProperty("primary_english_name")
    public void setPrimaryEnglishName(String primaryEnglishName) {
        this.primaryEnglishName = primaryEnglishName;
    }

    @JsonProperty("arabic_aliases")
    public List<String> getArabicAliases() {
        return arabicAliases;
    }

    @JsonProperty("arabic_aliases")
    public void setArabicAliases(List<String> arabicAliases) {
        this.arabicAliases = arabicAliases == null ? new ArrayList<>() : arabicAliases;
    }

    @JsonProperty("english_aliases")
    public List<String> getEnglishAliases() {
        return englishAliases;
    }

    @JsonProperty("english_aliases")
    public void setEnglishAliases(List<String> englishAliases) {
        this.englishAliases = englishAliases == null ? new ArrayList<>() : englishAliases;
    }

    @JsonProperty("kunyah_arabic")
    public String getKunyahArabic() {
        return kunyahArabic;
    }

    @JsonProperty("kunyah_arabic")
    public void setKunyahArabic(String kunyahArabic) {
        this.kunyahArabic = kunyahArabic;
    }

    @JsonProperty("kunyah_english")
    public String getKunyahEnglish() {
        return kunyahEnglish;
    }

    @JsonProperty("kunyah_english")
    public void setKunyahEnglish(String kunyahEnglish) {
        this.kunyahEnglish = kunyahEnglish;
    }

    @JsonProperty("titles")
    public List<String> getTitles() {
        return titles;
    }

    @JsonProperty("titles")
    public void setTitles(List<String> titles) {
        this.titles = titles == null ? new ArrayList<>() : titles;
    }

    @JsonProperty("scholarly_names")
    public List<String> getScholarlyNames() {
        return scholarlyNames;
    }

    @JsonProperty("scholarly_names")
    public void setScholarlyNames(List<String> scholarlyNames) {
        this.scholarlyNames = scholarlyNames == null ? new ArrayList<>() : scholarlyNames;
    }

    @JsonProperty("is_imam_or_prophet")
    public boolean isImamOrProphet() {
        return imamOrProphet;
    }

    @JsonProperty("is_imam_or_prophet")
    public void setImamOrProphet(boolean imamOrProphet) {
        this.imamOrProphet = imamOrProphet;
    }

    @JsonProperty("is_doubtful")
    public boolean isDoubtful() {
        return doubtful;
    }

    @JsonProperty("is_doubtful")
    public void setDoubtful(boolean doubtful) {
        this.doubtful = doubtful;
    }

    @JsonProperty("doubtful_reason")
    public String getDoubtfulReason() {
        return doubtfulReason;
    }

    @JsonProperty("doubtful_reason")
    public void setDoubtfulReason(String doubtfulReason) {
        this.doubtfulReason = doubtfulReason;
    }

    @JsonProperty("reliability_grade")
    public String getReliabilityGrade() {
        return reliabilityGrade;
    }

    @JsonProperty("reliability_grade")
    public void setReliabilityGrade(String reliabilityGrade) {
        this.reliabilityGrade = reliabilityGrade;
    }

    @JsonProperty("reliability_sources")
    public List<String> getReliabilitySources() {
        return reliabilitySources;
    }

    @JsonProperty("reliability_sources")
    public void setReliabilitySources(List<String> reliabilitySources) {
        this.reliabilitySources = reliabilitySources == null ? new ArrayList<>() : reliabilitySources;
    }

    @JsonProperty("source_assessments")
    public List<SourceAssessment> getSourceAssessments() {
        return sourceAssessments;
    }

    @JsonProperty("source_assessments")
    public void setSourceAssessments(List<SourceAssessment> sourceAssessments) {
        this.sourceAssessments = sourceAssessments == null ? new ArrayList<>() : sourceAssessments;
    }

    @JsonProperty("biography_summary_en")
    public String getBiographySummaryEn() {
        return biographySummaryEn;
    }

    @JsonProperty("biography_summary_en")
    public void setBiographySummaryEn(String biographySummaryEn) {
        this.biographySummaryEn = biographySummaryEn;
    }

    @JsonProperty("biography_summary_ar")
    public String getBiographySummaryAr() {
        return biographySummaryAr;
    }

    @JsonProperty("biography_summary_ar")
    public void setBiographySummaryAr(String biographySummaryAr) {
        this.biographySummaryAr = biographySummaryAr;
    }

    @JsonProperty("rijal_sources")
    public List<String> getRijalSources() {
        return rijalSources;
    }

    @JsonProperty("rijal_sources")
    public void setRijalSources(List<String> rijalSources) {
        this.rijalSources = rijalSources == null ? new ArrayList<>() : rijalSources;
    }

    @JsonProperty("death_year_hijri")
    public String getDeathYearHijri() {
        return deathYearHijri;
    }

    @JsonProperty("death_year_hijri")
    public void setDeathYearHijri(String deathYearHijri) {
        this.deathYearHijri = deathYearHijri;
    }

    @JsonProperty("birth_year_hijri")
    public String getBirthYearHijri() {
        return birthYearHijri;
    }

    @JsonProperty("birth_year_hijri")
    public void setBirthYearHijri(String birthYearHijri) {
        this.birthYearHijri = birthYearHijri;
    }

    @JsonProperty("tribe_or_city")
    public String getTribeOrCity() {
        return tribeOrCity;
    }

    @JsonProperty("tribe_or_city")
    public void setTribeOrCity(String tribeOrCity) {
        this.tribeOrCity = tribeOrCity;
    }

    @JsonProperty("generation")
    public String getGeneration() {
        return generation;
    }

    @JsonProperty("generation")
    public void setGeneration(String generation) {
        this.generation = generation;
    }

    @JsonProperty("gender")
    public String getGender() {
        return gender;
    }

    @JsonProperty("gender")
    public void setGender(String gender) {
        this.gender = gender;
    }

    @JsonProperty("hadith_count")
    public int getHadithCount() {
        return hadithCount;
    }

    @JsonProperty("hadith_count")
    public void setHadithCount(int hadithCount) {
        this.hadithCount = hadithCount;
    }

    @JsonProperty("normalized_arabic")
    public String getNormalizedArabic() {
        return normalizedArabic;
    }

    @JsonProperty("normalized_arabic")
    public void setNormalizedArabic(String normalizedArabic) {
        this.normalizedArabic = normalizedArabic;
    }

    @JsonProperty("normalized_english")
    public String getNormalizedEnglish() {
        return normalizedEnglish;
    }

    @JsonProperty("normalized_english")
    public void setNormalizedEnglish(String normalizedEnglish) {
        this.normalizedEnglish = normalizedEnglish;
    }

    @JsonProperty("created_at")
    public String getCreatedAt() {
        return createdAt;
    }

    @JsonProperty("created_at")
    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    @JsonProperty("updated_at")
    public String getUpdatedAt() {
        return updatedAt;
    }

    @JsonProperty("updated_at")
    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}
