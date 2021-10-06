package com.rewayaat.service;

import com.rewayaat.core.QueryMode;
import org.apache.log4j.Logger;
import org.elasticsearch.script.Script;
import org.elasticsearch.search.sort.ScriptSortBuilder;
import org.elasticsearch.search.sort.SortBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Service for querying hadith.
 */
@Service
public class HadithQueryService {

    private static Logger log = Logger.getLogger(HadithQueryService.class.getName());

    private String[] docFields = new String[]{"_id:", "source:", "book:", "number:", "part:",
        "edition:", "chapter:", "publisher:", "section:", "tags:", "volume:", "notes:", "arabic:",
        "gradings:"};

    public List<SortBuilder> setupSortBuilders(String sortFields) {
        List<SortBuilder> sortBuilders = new ArrayList<>();
        if (sortFields == null || sortFields.isEmpty()) {
            sortBuilders.add(SortBuilders.scoreSort());
        } else {
            String[] fieldSorts = sortFields.split(",");
            for (String fieldSort : fieldSorts) {
                String field = fieldSort.split(":")[0] + ".keyword";
                if (field.startsWith("number")) {
                    sortBuilders.add(SortBuilders.scriptSort(
                        new Script("Integer.parseInt(doc['number.keyword'].value)"),
                        ScriptSortBuilder.ScriptSortType.NUMBER)
                    );
                } else if (field.startsWith("chapter")) {
                    sortBuilders.add(SortBuilders.scriptSort(
                        new Script(
                            "def m = /([0-9]+) +[-–—–]/.matcher(doc['chapter.keyword']"
                                + ".value); "
                                + "if(m.find()) { "
                                + "return Integer.parseInt(m.group(1))"
                                + " } else { "
                                + "return 0 }"
                        ),
                        ScriptSortBuilder.ScriptSortType.NUMBER)
                    );
                } else {
                    SortOrder sortOrder = SortOrder.fromString(fieldSort.split(":")[1]);
                    sortBuilders.add(SortBuilders.fieldSort(field).order(sortOrder));
                }
            }
        }
        return sortBuilders;
    }

    public String enhanceQuery(String query, QueryMode queryMode) {
        // splits query by all spaces that are not enclosed by double quotes or brackets
        List<String> splitted = new ArrayList<>();
        List<String> allFieldItems = new ArrayList<>();
        int nextingLevel = 0;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < query.length(); i++) {
            char currChar = query.charAt(i);
            Character nextChar = null;
            if (i != query.length() - 1) {
                nextChar = query.charAt(i + 1);
            }
            if (currChar == ' ' && nextingLevel == 0) {
                splitted.add(result.toString());
                result.setLength(0);
            } else {
                if (currChar == ')' | currChar == ']'
                    || (currChar == '\"' && (nextChar == null || nextChar == ' '))) {
                    nextingLevel--;
                } else if (currChar == '(' | currChar == '[' | currChar == '\"') {
                    nextingLevel++;
                }
                result.append(currChar);
            }
        }
        splitted.add(result.toString());
        for (String s : splitted) {
            s = s.trim();
            if (!s.contains("~") && !s.contains(":") && !s.contains("^") && !s.contains("(") && !s.contains("\"") && !s.trim().startsWith("+") && !s.trim().startsWith("-")) {
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

    public boolean isProbablyArabic(String s) {
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
