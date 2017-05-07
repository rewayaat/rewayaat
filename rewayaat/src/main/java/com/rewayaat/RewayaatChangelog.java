package com.rewayaat;

import org.json.JSONObject;

import com.rewayaat.github.GitHubIssueQuery;
import com.rewayaat.web.core.Changelog;
import com.rewayaat.web.core.ChangelogItem;

/**
 * Represents the Rewayaat Changelog.
 */
public class RewayaatChangelog {

    private Changelog changeLog;

    public RewayaatChangelog(int page) throws Exception {
        Changelog changelog = new Changelog();
        JSONObject githubResult = new GitHubIssueQuery("rewayaat", "rewayaat", new String[] {"changelog"}, "closed",
                page).result();
        githubResult.keys().forEachRemaining(k ->
        {
            JSONObject value = githubResult.getJSONObject(k);
            ChangelogItem changelogItem = new ChangelogItem(value.getString("closed_at"), value.getString("title"),
                    value.getString("body"), value.getString("url"));
            changelog.insertItem(changelogItem);
        });
        this.changeLog = changelog;
    }

    public Changelog changelog() {
        return this.changeLog;
    }
}
