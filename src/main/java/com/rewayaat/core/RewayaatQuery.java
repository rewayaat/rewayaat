package com.rewayaat.core;

import com.rewayaat.controllers.HomeController;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;

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

    private static Logger log = Logger.getLogger(HomeController.class.getName());
    private final QueryMode queryMode;

    private String query;

    private String[] docFields = new String[]{"_id:", "source:", "book:", "number:", "part:",
        "edition:", "chapter:", "publisher:", "section:", "tags:", "volume:", "notes:", "arabic:",
        "gradings:"};

    public RewayaatQuery(String query, QueryMode queryMode) {
        this.query = query.trim();
        this.queryMode = queryMode;
    }

    public String query() {
        // splits query by all spaces that are not enclosed by double quotes or brackets
        List<String> splitted = new ArrayList<>();
        List<String> allFieldItems = new ArrayList<>();
        int nextingLevel = 0;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < query.length(); i++) {
            char curr_c = query.charAt(i);
            Character next_c = null;
            if (i != query.length() - 1) {
                next_c = query.charAt(i + 1);
            }
            if (curr_c == ' ' && nextingLevel == 0) {
                splitted.add(result.toString());
                result.setLength(0);
            } else {
                if (curr_c == ')' | curr_c == ']' ||
                    (curr_c == '\"' && (next_c == null || next_c == ' '))) {
                    nextingLevel--;
                } else if (curr_c == '(' | curr_c == '[' | curr_c == '\"') {
                    nextingLevel++;
                }
                result.append(curr_c);
            }
        }
        splitted.add(result.toString());
        for (String s : splitted) {
            s = s.trim();
            if (!s.contains("~") && !s.contains(":") && !s.contains("(") && !s.contains("\"") && !s.trim().startsWith("+") && !s.trim().startsWith("-")) {
                s += "~";
            }
            allFieldItems.add(s);
        }
        if (queryMode == QueryMode.LOOKUP) {
            query = String.join(" AND ", allFieldItems);
        } else {
            query = String.join(" ", allFieldItems);
        }
        log.info("Final query post modifications: " + query);
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
