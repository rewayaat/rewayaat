package com.rewayaat.web.core;

/**
 * A single entry item of a changelog.
 */
public class ChangelogItem {

    public String getDescription() {
        return description;
    }

    public String getTitle() {
        return title;
    }

    public String getDate() {
        return date;
    }

    private String description;
    private String title;
    private String date;
    private String url;

    public ChangelogItem(String date, String title, String description, String url) {
        this.date = date;
        this.title = title;
        this.description = description;
        this.url = url;
    }

    public String getUrl() {
        return url;
    }
}
