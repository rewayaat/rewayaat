package com.rewayaat.core;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Uses Elastic Search Query Syntax to Modify user queries to improve search
 * results. This is done mostly by removing weird characters, adding <a href=
 * "https://www.elastic.co/guide/en/elasticsearch/guide/current/fuzziness.html">fuzziness</a>,
 * and adding <a href=
 * "https://www.elastic.co/guide/en/elasticsearch/guide/current/slop.html">slop</a>
 * to phrases.
 */
public class RewayaatQuery {

    private String query;

    private String[] docFields = new String[]{"_id:", "source:", "book:", "number:", "part:", "edition:", "chapter:", "publisher:", "section:", "tags:", "volume:", "notes:", "arabic:", "gradings:"};

    public RewayaatQuery(String query) {
        this.query = query;
    }

    public String query() {
        // splits query by all spaces that are not enclosed by double quotes or brackets
        List<String> splitted = new ArrayList<>();
        List<String> allFieldItems = new ArrayList<>();
        int nextingLevel = 0;
        StringBuilder result = new StringBuilder();
        for (char c : query.toCharArray()) {
            if (c == ' ' && nextingLevel == 0) {
                splitted.add(result.toString());
                result.setLength(0);
            } else {
                if (c == '(' | c == '[' | c == '\"') {
                    nextingLevel++;
                } else if (c == ')' | c == ']' | c == '\"') {
                    nextingLevel--;
                }
                result.append(c);
            }
        }
        splitted.add(result.toString());
        for (String s : splitted) {
            s = s.trim();
            if (!s.contains("~") && !s.contains("(")) {
                s += "~";
            }
            if (!StringUtils.startsWithAny(s, docFields)) {
                allFieldItems.add(s);
            }
        }
        query = String.join(" ", allFieldItems);
        System.out.println("Final fuzzied query: " + query);
        return query;
    }

    public static boolean isProbablyArabic(String s) {
        for (int i = 0; i < s.length();) {
            int c = s.codePointAt(i);
            if (c >= 0x0600 && c <= 0x06E0) {
                return true;
            }
            i += Character.charCount(c);
        }
        return false;
    }
}
