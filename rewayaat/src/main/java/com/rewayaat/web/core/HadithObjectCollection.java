package com.rewayaat.web.core;

import java.util.List;

import com.rewayaat.web.data.hadith.HadithObject;

public class HadithObjectCollection {

    public List<HadithObject> collection;
    public long totalResultSetSize;

    public HadithObjectCollection(List<HadithObject> collection, long totalResultSize) {
        this.collection = collection;
        this.totalResultSetSize = totalResultSize;
    }
}
