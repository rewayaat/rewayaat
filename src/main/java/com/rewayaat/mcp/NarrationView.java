package com.rewayaat.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shape a narration takes when it leaves the MCP server.
 *
 * <p>A hadith {@code _source} is built for the website, which renders the matn, the isnād
 * and the similarity panel from separate pre-split fields. A language model needs none of
 * that: it needs the text, enough metadata to cite it, and a URL. Measured on a five-result
 * search, the raw documents are 42,018 bytes and this shape is 5,396 - and the difference is
 * almost entirely fields that exist to save the browser some work:
 *
 * <ul>
 *   <li>{@code llm_similar} - the similarity payload, which {@code find_similar} returns
 *       deliberately and no other tool should pay for.</li>
 *   <li>{@code englishContent} / {@code arabicContent} - the matn again, pre-segmented.</li>
 *   <li>{@code englishChain} / {@code arabicChain} - the isnād, split out for rendering.</li>
 *   <li>{@code semantic_matn_source} and friends - retrieval inputs, not content.</li>
 * </ul>
 *
 * <p>This matters against a hard limit rather than as a nicety: Claude caps a tool result at
 * roughly 150,000 characters, and the raw shape reaches that inside twenty narrations.
 *
 * <p>Shaping lives here rather than behind a query parameter on {@code /v1/narrations}
 * because it is a protocol concern. The website's API should not grow options that exist
 * only to serve a language model.
 */
public final class NarrationView {

    private NarrationView() {
    }

    /**
     * Builds the compact form used by search results, where the matn is the payload and
     * every other field exists to make the citation resolvable.
     */
    public static Map<String, Object> summary(String id, Map<String, Object> source, String baseUrl) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("book", str(source.get("book")));
        putIfPresent(out, "volume", str(source.get("volume")));
        putIfPresent(out, "part", str(source.get("part")));
        putIfPresent(out, "chapter", str(source.get("chapter")));
        putIfPresent(out, "number", str(source.get("number")));
        out.put("english", str(source.get("english")));
        putIfPresent(out, "arabic", str(source.get("arabic")));
        List<String> gradings = gradings(source.get("gradings"));
        if (!gradings.isEmpty()) {
            out.put("gradings", gradings);
        }
        List<String> topics = strings(source.get("topic_tags"));
        if (!topics.isEmpty()) {
            out.put("topic_tags", topics);
        }
        out.put("url", url(id, baseUrl));
        return out;
    }

    /**
     * The citation label a model should use in prose: {@code Al-Kāfi #690}. The narration
     * id already ends in the number, but the label spells out the book so a citation stays
     * readable when it is lifted out of the tool result.
     */
    public static String label(Map<String, Object> source) {
        String book = str(source.get("book"));
        String number = str(source.get("number"));
        if (book.isEmpty()) {
            return number.isEmpty() ? "" : "#" + number;
        }
        return number.isEmpty() ? book : book + " #" + number;
    }

    /** The canonical, user-openable page for a narration - what a client cites. */
    public static String url(String id, String baseUrl) {
        return baseUrl + "/hadith/" + id;
    }

    private static List<String> gradings(Object raw) {
        List<String> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String grading = str(map.get("grading"));
            String grader = str(map.get("grader"));
            if (grading.isEmpty()) {
                continue;
            }
            out.add(grader.isEmpty() ? grading : grading + " (" + grader + ")");
        }
        return out;
    }

    private static List<String> strings(Object raw) {
        List<String> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            String value = str(item);
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return out;
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (!value.isEmpty()) {
            target.put(key, value);
        }
    }

    /** Trims a raw {@code _source} value to a string, treating a missing value as empty. */
    public static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
