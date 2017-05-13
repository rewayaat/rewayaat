
package com.rewayaat.web.data.hadith;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.builder.ToStringBuilder;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.swagger.annotations.ApiModelProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "id", "book", "number", "part", "chapter", "section", "volume", "tags", "notes", "arabic",
        "english", "Commentaries", "gradings", "related" })
public class HadithObject implements Serializable {

    @JsonProperty("id")
    private String id;
    @ApiModelProperty(notes = "The book containing the narration")
    @JsonProperty("book")
    private String book;
    @ApiModelProperty(notes = "The number of the narration")
    @JsonProperty("number")
    private String number;
    @ApiModelProperty(notes = "The part of the book associated with the narration")
    @JsonProperty("part")
    private String part;
    @ApiModelProperty(notes = "The edition of the book associated with the narration")
    @JsonProperty("edition")
    private String edition;
    @ApiModelProperty(notes = "The chapter of the book associated with the narration")
    @JsonProperty("chapter")
    private String chapter;
    @ApiModelProperty(notes = "The section of the book associated with the narration")

    @JsonProperty("section")
    private String section;
    @ApiModelProperty(notes = "The volume of the book associated with the narration")
    @JsonProperty("volume")
    private String volume;
    @ApiModelProperty(notes = "relevant tags describing the narration's contents")
    @JsonProperty("tags")
    private List<Object> tags = new ArrayList<Object>();
    @ApiModelProperty(notes = "Relevant notes helpful in understanding the narration")
    @JsonProperty("notes")
    private String notes;
    @ApiModelProperty(notes = "Arabic text of the narration")
    @JsonProperty("arabic")
    private String arabic;
    @ApiModelProperty(notes = "English text of the narration")
    @JsonProperty("english")
    private String english;
    @ApiModelProperty(notes = "Gradings of the narration")
    @JsonProperty("gradings")
    private List<Grading> gradings = new ArrayList<Grading>();
    @ApiModelProperty(notes = "Further resources that are related to the narration")
    @JsonProperty("related")
    private List<Related> related = new ArrayList<Related>();
    @JsonIgnore
    private Map<String, Object> additionalProperties = new HashMap<String, Object>();
    private final static long serialVersionUID = 5990321989725337516L;

    /**
     * No args constructor for use in serialization
     * 
     */
    public HadithObject() {
    }

    /**
     * 
     * @param tags
     * @param id
     * @param arabic
     * @param chapter
     * @param commentaries
     * @param volume
     * @param book
     * @param number
     * @param gradings
     * @param edition
     * @param notes
     * @param part
     * @param english
     * @param related
     */
    public HadithObject(String id, String book, String number, String part, String chapter, String volume,
            List<Object> tags, String notes, String arabic, String english, List<Commentary> commentaries,
            List<Grading> gradings, List<Related> related, String section, String edition) {
        super();
        this.id = id;
        this.book = book;
        this.number = number;
        this.part = part;
        this.chapter = chapter;
        this.volume = volume;
        this.tags = tags;
        this.notes = notes;
        this.arabic = arabic;
        this.english = english;
        this.gradings = gradings;
        this.related = related;
        this.edition = edition;
        this.section = section;
    }

    @JsonProperty("id")
    public String getId() {
        return id;
    }

    @JsonProperty("id")
    public void setId(String id) {
        this.id = id;
    }

    @JsonProperty("book")
    public String getBook() {
        return book;
    }

    @JsonProperty("book")
    public void setBook(String book) {
        this.book = book;
    }

    @JsonProperty("number")
    public String getNumber() {
        return number;
    }

    public void insertEnglishText(String text) {
        if (this.english == null) {
            this.english = "";
        }
        this.english += text;
    }

    public void insertArabicText(String text) {
        if (this.arabic == null) {
            this.arabic = "";
        }
        this.arabic = this.arabic + " " + text;
    }

    @JsonProperty("number")
    public void setNumber(String number) {
        this.number = number;
    }

    @JsonProperty("section")
    public String getSection() {
        return section;
    }

    @JsonProperty("section")
    public void setSection(String section) {
        this.section = section;
    }

    @JsonProperty("part")
    public String getPart() {
        return part;
    }

    @JsonProperty("part")
    public void setPart(String part) {
        this.part = part;
    }

    @JsonProperty("chapter")
    public String getChapter() {
        return chapter;
    }

    @JsonProperty("chapter")
    public void setChapter(String chapter) {
        this.chapter = chapter;
    }

    @JsonProperty("volume")
    public String getVolume() {
        return volume;
    }

    @JsonProperty("volume")
    public void setVolume(String volume) {
        this.volume = volume;
    }

    @JsonProperty("tags")
    public List<Object> getTags() {
        return tags;
    }

    @JsonProperty("tags")
    public void setTags(List<Object> tags) {
        this.tags = tags;
    }

    @JsonProperty("notes")
    public String getNotes() {
        return notes;
    }

    @JsonProperty("notes")
    public void setNotes(String notes) {
        this.notes = notes;
    }

    @JsonProperty("arabic")
    public String getArabic() {
        return arabic;
    }

    @JsonProperty("arabic")
    public void setArabic(String arabic) {
        this.arabic = arabic;
    }

    @JsonProperty("english")
    public String getEnglish() {
        return english;
    }

    @JsonProperty("english")
    public void setEnglish(String english) {
        this.english = english;
    }

    @JsonProperty("gradings")
    public List<Grading> getGradings() {
        return gradings;
    }

    @JsonProperty("gradings")
    public void setGradings(List<Grading> gradings) {
        this.gradings = gradings;
    }

    @JsonProperty("related")
    public List<Related> getRelated() {
        return related;
    }

    @JsonProperty("related")
    public void setRelated(List<Related> related) {
        this.related = related;
    }

    @Override
    public String toString() {
        return ToStringBuilder.reflectionToString(this);
    }

    @JsonAnyGetter
    public Map<String, Object> getAdditionalProperties() {
        return this.additionalProperties;
    }

    @JsonAnySetter
    public void setAdditionalProperty(String name, Object value) {
        this.additionalProperties.put(name, value);
    }

}