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
    private long baseResultSetSize;
    private Map<String, Long> topicTagFacets = new LinkedHashMap<>();

    public HadithObjectCollection(List<HadithObject> collection, long totalResultSize) {
        this.setCollection(collection);
        this.setTotalResultSetSize(totalResultSize);
        this.baseResultSetSize = totalResultSize;
    }

    public HadithObjectCollection(List<HadithObject> collection, long totalResultSize, long baseResultSize) {
        this.setCollection(collection);
        this.setTotalResultSetSize(totalResultSize);
        this.setBaseResultSetSize(baseResultSize);
    }

    public long getTotalResultSetSize() {
        return totalResultSetSize;
    }

    public void setTotalResultSetSize(long totalResultSetSize) {
        this.totalResultSetSize = totalResultSetSize;
    }

    public long getBaseResultSetSize() {
        return baseResultSetSize;
    }

    public void setBaseResultSetSize(long baseResultSetSize) {
        this.baseResultSetSize = baseResultSetSize;
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
