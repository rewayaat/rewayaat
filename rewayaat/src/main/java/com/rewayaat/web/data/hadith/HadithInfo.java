
package com.rewayaat.web.data.hadith;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.builder.ToStringBuilder;
import org.springframework.data.elasticsearch.annotations.Document;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "book", "number", "chapter", "volume", "transmission_chain", "tags", "hadith", "gradings",
    "present_day_location", "date", "source" })
@Document(indexName = "records", type = "hadith")
public class HadithInfo implements Serializable {

    @JsonProperty("book")
    private String book;
    @JsonProperty("number")
    private int number;
    @JsonProperty("chapter")
    private int chapter;
    @JsonProperty("volume")
    private int volume;
    @JsonProperty("part")
    private int part;
    @JsonProperty("transmission_chain")
    private List<Object> transmissionChain = null;
    @JsonProperty("tags")
    private List<Object> tags = null;
    @JsonProperty("hadith")
    private Hadith hadith;
    @JsonProperty("gradings")
    private List<Grading> gradings = null;
    @JsonProperty("present_day_location")
    private String presentDayLocation;
    @JsonProperty("date")
    private String date;
    @JsonProperty("id")
    private String id;
    @JsonProperty("source")
    private String source;
    @JsonIgnore
    private Map<String, Object> additionalProperties = new HashMap<String, Object>();
    private final static long serialVersionUID = 7552981875740961865L;

    /**
     * No args constructor for use in serialization
     *
     */
    public HadithInfo() {
    }

    public HadithInfo(String book, int number, int chapter, int volume, List<Object> transmissionChain,
            List<Object> tags, Hadith hadith, List<Grading> gradings, String presentDayLocation, String date,
            String source, String id, int part) {
        super();
        this.book = book;
        this.number = number;
        this.chapter = chapter;
        this.volume = volume;
        this.transmissionChain = transmissionChain;
        this.tags = tags;
        this.hadith = hadith;
        this.gradings = gradings;
        this.presentDayLocation = presentDayLocation;
        this.date = date;
        this.source = source;
        this.part = part;
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

    @JsonProperty("part")
    public int getPart() {
        return part;
    }

    @JsonProperty("part")
    public void setPart(int part) {
        this.part = part;
    }

    @JsonProperty("id")
    public String getId() {
        return id;
    }

    @JsonProperty("id")
    public void setId(String id) {
        this.id = id;
    }

    @JsonProperty("number")
    public int getNumber() {
        return number;
    }

    @JsonProperty("number")
    public void setNumber(int number) {
        this.number = number;
    }

    @JsonProperty("chapter")
    public int getChapter() {
        return chapter;
    }

    @JsonProperty("chapter")
    public void setChapter(int chapter) {
        this.chapter = chapter;
    }

    @JsonProperty("volume")
    public int getVolume() {
        return volume;
    }

    @JsonProperty("volume")
    public void setVolume(int volume) {
        this.volume = volume;
    }

    @JsonProperty("transmission_chain")
    public List<Object> getTransmissionChain() {
        return transmissionChain;
    }

    @JsonProperty("transmission_chain")
    public void setTransmissionChain(List<Object> transmissionChain) {
        this.transmissionChain = transmissionChain;
    }

    @JsonProperty("tags")
    public List<Object> getTags() {
        return tags;
    }

    @JsonProperty("tags")
    public void setTags(List<Object> tags) {
        this.tags = tags;
    }

    @JsonProperty("hadith")
    public Hadith getHadith() {
        return hadith;
    }

    @JsonProperty("hadith")
    public void setHadith(Hadith hadith) {
        this.hadith = hadith;
    }

    @JsonProperty("gradings")
    public List<Grading> getGradings() {
        return gradings;
    }

    @JsonProperty("gradings")
    public void setGradings(List<Grading> gradings) {
        this.gradings = gradings;
    }

    @JsonProperty("present_day_location")
    public String getPresentDayLocation() {
        return presentDayLocation;
    }

    @JsonProperty("present_day_location")
    public void setPresentDayLocation(String presentDayLocation) {
        this.presentDayLocation = presentDayLocation;
    }

    @JsonProperty("date")
    public String getDate() {
        return date;
    }

    @JsonProperty("date")
    public void setDate(String date) {
        this.date = date;
    }

    @JsonProperty("source")
    public String getSource() {
        return source;
    }

    @JsonProperty("source")
    public void setSource(String source) {
        this.source = source;
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
