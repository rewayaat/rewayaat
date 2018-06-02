package com.rewayaat.core;

import java.util.List;

/**
 * Represents a collection of hadith results.
 */
public class HadithObjectCollection {

    private List<HadithObject> collection;
    private long totalResultSetSize;

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
}