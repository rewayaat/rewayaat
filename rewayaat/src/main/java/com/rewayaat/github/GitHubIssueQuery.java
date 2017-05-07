package com.rewayaat.github;

import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;
import org.springframework.http.MediaType;

/**
 * Retrieves Issues from a GitHub repository.
 */
public class GitHubIssueQuery {

    private String repoOwner;
    private String repoName;
    private String state;
    private String[] labels;
    private int page;

    public GitHubIssueQuery(String repoName, String repoOwner, String token, int page) {
        this.repoName = repoName;
        this.repoOwner = repoOwner;
        this.page = page;
    }

    /**
     *
     * @param repoName
     *            The name of the repository
     * @param repoOwner
     *            The owner of the repository
     * @param labels
     *            returned issues must have one of these labels
     * @param state
     *            returned issues must have this state
     */
    public GitHubIssueQuery(String repoName, String repoOwner, String[] labels, String state, int page) {
        this.repoName = repoName;
        this.repoOwner = repoOwner;
        this.labels = labels;
        this.state = state;
        this.page = page;
    }

    public JSONObject result() throws Exception {
        HttpClient client = HttpClientBuilder.create().build();
        String requestURL = "https://" + "api.github.com" + "/repos" + this.repoOwner + "/" + repoName + "/issues"
                + "?per_page=100&page=" + page;
        if (labels != null) {
            requestURL += "&labels=" + StringUtils.join(labels, ",");
        }
        if (state != null) {
            requestURL += "&state=" + state;
        }
        HttpGet request = new HttpGet(requestURL);
        request.setHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
        HttpResponse response = client.execute(request);
        JSONObject object = new JSONObject(EntityUtils.toString(response.getEntity()));
        return object;
    }
}
