package com.rewayaat.service;

import com.rewayaat.core.QueryMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import co.elastic.clients.elasticsearch._types.Script;
import co.elastic.clients.elasticsearch._types.ScriptSortType;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;

import java.util.ArrayList;
import java.util.List;

/**
 * Service for querying hadith.
 */
@Service
public class HadithQueryService {

    private static final Logger log = LoggerFactory.getLogger(HadithQueryService.class);

    private String[] docFields = new String[]{"_id:", "source:", "book:", "number:", "part:",
        "edition:", "chapter:", "publisher:", "section:", "tags:", "volume:", "notes:", "arabic:",
        "gradings:"};

    public List<SortOptions> setupSortBuilders(String sortFields) {
        List<SortOptions> sortBuilders = new ArrayList<>();
        if (sortFields == null || sortFields.isEmpty()) {
            sortBuilders.add(SortOptions.of(s -> s.score(sc -> sc.order(SortOrder.Desc))));
        } else {
            String[] fieldSorts = sortFields.split(",");
            for (String fieldSort : fieldSorts) {
                String field = fieldSort.split(":")[0] + ".keyword";
                if (field.startsWith("number")) {
                    sortBuilders.add(SortOptions.of(s -> s.script(ss -> ss
                            .type(ScriptSortType.Number)
                            .order(parseOrder(fieldSort))
                            .script(Script.of(sc -> sc.source(src -> src.scriptString(
                                    "Integer.parseInt(doc['number.keyword'].value)")))))));
                } else {
                    sortBuilders.add(SortOptions.of(s -> s.field(f -> f
                            .field(field)
                            .order(parseOrder(fieldSort)))));
                }
            }
        }
        return sortBuilders;
    }

    private SortOrder parseOrder(String fieldSort) {
        String[] parts = fieldSort.split(":");
        if (parts.length < 2) {
            return SortOrder.Desc;
        }
        String order = parts[1].trim().toLowerCase();
        return "asc".equals(order) ? SortOrder.Asc : SortOrder.Desc;
    }

    public String enhanceQuery(String query, QueryMode queryMode, boolean strictMatchMode) {
        if (query == null) {
            query = "";
        }
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
            s = normalizeFieldAlias(s);
            if (!strictMatchMode &&
                    !s.contains("~") && !s.contains(":") && !s.contains("^") && !s.contains("(") && !s.contains("\"") &&
                    !s.trim().startsWith("+") && !s.trim().startsWith("-")) {
                s += "~";
            }
            allFieldItems.add(s);
        }
        if (strictMatchMode || queryMode == QueryMode.LOOKUP) {
            query = String.join(" AND ", allFieldItems);
        } else {
            query = String.join(" ", allFieldItems);
        }
        log.debug("Final query post modifications: {}", query);
        return query;
    }

    private String normalizeFieldAlias(String token) {
        if (token == null || token.isEmpty()) {
            return token;
        }
        String prefix = "";
        String body = token;
        if (token.startsWith("+") || token.startsWith("-")) {
            prefix = token.substring(0, 1);
            body = token.substring(1);
        }
        if (body.regionMatches(true, 0, "id:", 0, 3)) {
            return prefix + "_id:" + body.substring(3);
        }
        return token;
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
