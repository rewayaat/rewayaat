
package com.rewayaat.core.data;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.annotations.ApiModelProperty;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"grader", "rationale", "grading"})
public class Grading implements Serializable {

    @ApiModelProperty(notes = "Grader's full name")
    @JsonProperty("grader")
    private String grader;
    @ApiModelProperty(notes = "Rationale used for the grading")
    @JsonProperty("rationale")
    private String rationale;
    @ApiModelProperty(notes = "The grading value(eg: hassan, dhaeef, etc..)")
    @JsonProperty("grading")
    private String grading;
    @JsonIgnore
    private Map<String, Object> additionalProperties = new HashMap<String, Object>();
    private final static long serialVersionUID = -4608238425517677384L;

    /**
     * No args constructor for use in serialization
     */
    public Grading() {
    }

    /**
     * @param grading
     * @param rationale
     * @param grader
     */
    public Grading(String grader, String rationale, String grading) {
        super();
        this.grader = grader;
        this.rationale = rationale;
        this.grading = grading;
    }

    @JsonProperty("grader")
    public String getGrader() {
        return grader;
    }

    @JsonProperty("grader")
    public void setGrader(String grader) {
        this.grader = grader;
    }

    @JsonProperty("rationale")
    public String getRationale() {
        return rationale;
    }

    @JsonProperty("rationale")
    public void setRationale(String rationale) {
        this.rationale = rationale;
    }

    @JsonProperty("grading")
    public String getGrading() {
        return grading;
    }

    @JsonProperty("grading")
    public void setGrading(String grading) {
        this.grading = grading;
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
