
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
    "english",
    "rationale"
})
public class Translation implements Serializable
{

    @JsonProperty("english")
    private String english;
    @JsonProperty("rationale")
    private String rationale;
    @JsonIgnore
    private Map<String, Object> additionalProperties = new HashMap<String, Object>();
    private final static long serialVersionUID = 7960753341593758075L;

    /**
     * No args constructor for use in serialization
     * 
     */
    public Translation() {
    }

    /**
     * 
     * @param rationale
     * @param english
     */
    public Translation(String english, String rationale) {
        super();
        this.english = english;
        this.rationale = rationale;
    }

    @JsonProperty("english")
    public String getEnglish() {
        return english;
    }

    @JsonProperty("english")
    public void setEnglish(String english) {
        this.english = english;
    }

    @JsonProperty("rationale")
    public String getRationale() {
        return rationale;
    }

    @JsonProperty("rationale")
    public void setRationale(String rationale) {
        this.rationale = rationale;
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
