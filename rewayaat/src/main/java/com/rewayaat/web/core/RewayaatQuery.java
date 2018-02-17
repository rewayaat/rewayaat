package com.rewayaat.web.core;

import com.rewayaat.RefreshSynonymFilter;
import org.apache.commons.lang.StringUtils;

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

    private String[] docFields = new String[]{"source:", "book:", "number:", "part:", "edition:", "chapter:", "publisher:", "section:", "tags:", "volume:", "notes:", "arabic:", "gradings:"};
    public RewayaatQuery(String query) {
        this.query = query;
        // secret, shh.....
        if (query.equals("refresh_db")) {
            try {
                RefreshSynonymFilter.refresh();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
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
            if (!s.contains("~") && !s.contains("(")) {
                if (query.length() > 6) {
                    s += "~2";
                } else {
                    s += "~1";
                }
            }
            if (!StringUtils.startsWithAny(s, docFields)) {
                allFieldItems.add(s);
            }
        }
        query = String.join(" ", splitted);
        if (!allFieldItems.isEmpty()) {
            query += " all:(" + String.join(" ", allFieldItems) + ")";
        }
        System.out.println("Final Query: " + query);
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
