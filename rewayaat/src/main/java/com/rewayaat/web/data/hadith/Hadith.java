
package com.rewayaat.web.data.hadith;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
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
    "arabic",
    "translations"
})
public class Hadith implements Serializable
{

    @JsonProperty("arabic")
    private String arabic;
    @JsonProperty("translations")
    private List<Translation> translations = null;
    @JsonIgnore
    private Map<String, Object> additionalProperties = new HashMap<String, Object>();
    private final static long serialVersionUID = -8961362034647471527L;

    /**
     * No args constructor for use in serialization
     * 
     */
    public Hadith() {
    }

    /**
     * 
     * @param arabic
     * @param translations
     */
    public Hadith(String arabic, List<Translation> translations) {
        super();
        this.arabic = arabic;
        this.translations = translations;
    }

    @JsonProperty("arabic")
    public String getArabic() {
        return arabic;
    }

    @JsonProperty("arabic")
    public void setArabic(String arabic) {
        this.arabic = arabic;
    }

    @JsonProperty("translations")
    public List<Translation> getTranslations() {
        return translations;
    }

    @JsonProperty("translations")
    public void setTranslations(List<Translation> translations) {
        this.translations = translations;
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
