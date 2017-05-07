package com.rewayaat.web.core;

import java.util.List;

/**
 * Represents a change log for a project.
 */
public class Changelog {

    private List<ChangelogItem> changelog;

    public List<ChangelogItem> getChangelog() {
        return changelog;
    }

    public void setChangelog(List<ChangelogItem> changelog) {
        this.changelog = changelog;
    }

    public void insertItem(ChangelogItem changelogItem) {
        this.changelog.add(changelogItem);
    }
}
