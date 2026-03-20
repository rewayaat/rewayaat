package com.rewayaat.core;

import com.rewayaat.core.data.HadithObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a collection of hadith results.
 */
public class HadithObjectCollection {

    private List<HadithObject> collection;
    private long totalResultSetSize;
    private Map<String, Long> topicTagFacets = new LinkedHashMap<>();

    public HadithObjectCollection(List<HadithObject> collection, long totalResultSize) {
        this.setCollection(collection);
        this.setTotalResultSetSize(totalResultSize);
    }

    public long getTotalResultSetSize() {
        return totalResultSetSize;
    }

    public void setTotalResultSetSize(long totalResultSetSize) {
        this.totalResultSetSize = totalResultSetSize;
    }

    public List<HadithObject> getCollection() {
        return collection;
    }

    public void setCollection(List<HadithObject> collection) {
        this.collection = collection;
    }

    public Map<String, Long> getTopicTagFacets() {
        return topicTagFacets;
    }

    public void setTopicTagFacets(Map<String, Long> topicTagFacets) {
        this.topicTagFacets = topicTagFacets == null ? new LinkedHashMap<>() : topicTagFacets;
    }
}
