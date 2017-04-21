
package com.rewayaat.web.data.hadith;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.apache.commons.lang.builder.ToStringBuilder;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
    "source",
    "commentator",
    "commentary"
})
public class Commentary implements Serializable
{

    @JsonProperty("source")
    private String source;
    @JsonProperty("commentator")
    private String commentator;
    @JsonProperty("commentary")
    private String commentary;
    @JsonIgnore
    private Map<String, Object> additionalProperties = new HashMap<String, Object>();
    private final static long serialVersionUID = -1705983085572657295L;

    /**
     * No args constructor for use in serialization
     * 
     */
    public Commentary() {
    }

    /**
     * 
     * @param commentator
     * @param commentary
     * @param source
     */
    public Commentary(String source, String commentator, String commentary) {
        super();
        this.source = source;
        this.commentator = commentator;
        this.commentary = commentary;
    }

    @JsonProperty("source")
    public String getSource() {
        return source;
    }

    @JsonProperty("source")
    public void setSource(String source) {
        this.source = source;
    }

    @JsonProperty("commentator")
    public String getCommentator() {
        return commentator;
    }

    @JsonProperty("commentator")
    public void setCommentator(String commentator) {
        this.commentator = commentator;
    }

    @JsonProperty("commentary")
    public String getCommentary() {
        return commentary;
    }

    @JsonProperty("commentary")
    public void setCommentary(String commentary) {
        this.commentary = commentary;
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
