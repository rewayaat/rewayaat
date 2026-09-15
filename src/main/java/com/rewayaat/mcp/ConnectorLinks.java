package com.rewayaat.mcp;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tags the links the connector hands to a model, so that a visit which starts from a
 * connector answer shows up in analytics as one.
 *
 * <p>The site's GA4 tag already runs on every narration page, and GA4 reads UTM parameters
 * off the landing URL by itself - so tagging the link is the whole job. A visit arrives with
 * {@code utm_source} set to the client that asked ({@code claude}, {@code chatgpt} or
 * {@code other}), {@code utm_medium=ai-connector}, {@code utm_campaign=hadith-connector}, and
 * {@code utm_content} set to the tool whose result carried the link.
 *
 * <p>It runs at the MCP edge, over a finished result, rather than inside
 * {@link NarrationView#url}: the edge is the only place the client is known, and the view is
 * shared with the site's own chatbot, whose links should not be counted as connector
 * traffic. Only this site's links are tagged - a tafsīr {@code source_url} belongs to someone
 * else, and their analytics are not ours to write into. The canonical URL of a narration page
 * carries no query string, so a tagged link is never indexed as a second page.
 */
public final class ConnectorLinks {

    static final String MEDIUM = "ai-connector";
    static final String CAMPAIGN = "hadith-connector";

    private ConnectorLinks() {
    }

    /**
     * The client that asked, reduced to a source name that stays stable across its versions.
     * The raw name is whatever the client put in the MCP handshake; only the vendor matters
     * here.
     */
    public static String source(String clientName) {
        if (clientName == null) {
            return "other";
        }
        String name = clientName.toLowerCase(Locale.ROOT);
        if (name.contains("claude") || name.contains("anthropic")) {
            return "claude";
        }
        if (name.contains("openai") || name.contains("chatgpt")) {
            return "chatgpt";
        }
        return "other";
    }

    /**
     * A copy of {@code result} in which every {@code url} pointing at {@code baseUrl} carries
     * UTM parameters. The input is left untouched - tools build their results from immutable
     * maps.
     */
    public static Map<String, Object> tag(Map<String, Object> result, String baseUrl,
                                          String source, String tool) {
        @SuppressWarnings("unchecked")
        Map<String, Object> tagged = (Map<String, Object>) walk(result, baseUrl, source, tool);
        return tagged;
    }

    private static Object walk(Object value, String baseUrl, String source, String tool) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object item = entry.getValue();
                copy.put(key, "url".equals(key) && item instanceof String url
                        ? tagUrl(url, baseUrl, source, tool)
                        : walk(item, baseUrl, source, tool));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(walk(item, baseUrl, source, tool));
            }
            return copy;
        }
        return value;
    }

    static String tagUrl(String url, String baseUrl, String source, String tool) {
        // Already tagged means a tool passed a tagged link through; tagging twice would split
        // one visit across two sources.
        if (!url.startsWith(baseUrl + "/") || url.contains("utm_source=")) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?")
                + "utm_source=" + encode(source)
                + "&utm_medium=" + MEDIUM
                + "&utm_campaign=" + CAMPAIGN
                + "&utm_content=" + encode(tool);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
