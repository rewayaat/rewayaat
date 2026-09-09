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
    private static final int FLEXIBLE_EXACT_BOOST = 6;

    /**
     * How far flexible mode will bend a word: one edit, not Lucene's AUTO.
     *
     * <p>AUTO allows two edits on anything six characters or longer, which stops being a
     * typo and starts being a different word. Measured against the exact hit count:
     * ghadir returned 625 where 24 narrations contain it, narration 3,037 against 864,
     * believer 3,595 against 2,099. One edit brings those to 31, 864 and 3,077 - it still
     * forgives a slip, which is the point of the mode, without burying the 24 real hits
     * for ghadir under six hundred that merely look like it.
     *
     * <p>Precise mode does not fuzz at all, so this only sets how loose the loose end is.
     */
    private static final String FLEXIBLE_FUZZINESS = "~1";

    // Fields that are already keyword type (no .keyword subfield needed)
    private static final String[] KEYWORD_ONLY_FIELDS = new String[]{"book", "volume", "part", "section", "number", "edition", "publisher"};

    private String[] docFields = new String[]{"_id:", "source:", "book:", "number:", "part:",
        "edition:", "chapter:", "publisher:", "section:", "tags:", "volume:", "notes:", "arabic:",
        "gradings:"};

    private boolean isKeywordOnlyField(String fieldName) {
        for (String kwField : KEYWORD_ONLY_FIELDS) {
            if (kwField.equals(fieldName)) {
                return true;
            }
        }
        return false;
    }

    private String getSortField(String fieldName) {
        // For keyword-only fields, use the field name directly
        // For text fields with keyword subfields, use .keyword suffix
        if (isKeywordOnlyField(fieldName)) {
            return fieldName;
        }
        // For number field, keep as is (it's keyword type but we handle it specially)
        if ("number".equals(fieldName)) {
            return fieldName + ".keyword";
        }
        // Default: try .keyword subfield
        return fieldName + ".keyword";
    }

    public List<SortOptions> setupSortBuilders(String sortFields) {
        List<SortOptions> sortBuilders = new ArrayList<>();
        if (sortFields == null || sortFields.isEmpty()) {
            sortBuilders.add(SortOptions.of(s -> s.score(sc -> sc.order(SortOrder.Desc))));
        } else {
            String[] fieldSorts = sortFields.split(",");
            for (String fieldSort : fieldSorts) {
                String field = fieldSort.split(":")[0];
                String sortField = getSortField(field);
                if (field.startsWith("number")) {
                    sortBuilders.add(SortOptions.of(s -> s.script(ss -> ss
                            .type(ScriptSortType.Number)
                            .order(parseOrder(fieldSort))
                            .script(Script.of(sc -> sc.source(src -> src.scriptString(
                                    "Integer.parseInt(doc['number'].value)")))))));
                } else {
                    sortBuilders.add(SortOptions.of(s -> s.field(f -> f
                            .field(sortField)
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
            if (s.isEmpty()) {
                continue; // Skip empty strings to avoid invalid "~" queries
            }
            s = normalizeFieldAlias(s);
            s = stripArabicDiacritics(s);
            if (!strictMatchMode && !isArabicScript(s) &&
                    !s.contains("~") && !s.contains(":") && !s.contains("^") && !s.contains("(") && !s.contains("\"") &&
                    !s.startsWith("+") && !s.startsWith("-")) {
                s = "(" + s + "^" + FLEXIBLE_EXACT_BOOST + " OR " + s + FLEXIBLE_FUZZINESS + ")";
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

    /** Any Arabic letter marks the term as Arabic; a mixed term is treated as Arabic. */
    private static final java.util.regex.Pattern ARABIC_LETTER =
            java.util.regex.Pattern.compile("[\\u0621-\\u064A\\u0660-\\u0669\\u06D5]");

    /**
     * Whether a term is written in Arabic script, and so should not be fuzzied.
     *
     * <p>One edit lands on a different Arabic word far more often than it lands on the
     * intended one, because the roots are short and densely packed: غدير matched 26
     * narrations exactly and 2,143 with a single edit, الصوم 155 against 1,546. The same
     * edit on an English word is usually a typo. Nothing is lost by dropping it - the
     * index normalizes diacritics and alef and teh marbuta variants already, which is what
     * a reader actually types differently.
     */
    private boolean isArabicScript(String token) {
        return token != null && ARABIC_LETTER.matcher(token).find();
    }

    /**
     * Arabic combining marks: fatha through sukun, the daggers, and tatweel.
     */
    private static final java.util.regex.Pattern ARABIC_DIACRITICS =
            java.util.regex.Pattern.compile("[\\u064B-\\u0652\\u0670\\u0640\\u06D6-\\u06ED]");

    /**
     * Strips Arabic vowel marks from a term before the fuzzy operator is attached.
     *
     * <p>The index normalizes diacritics away, so this changes nothing about what a plain
     * term matches. It matters because {@code ~} does not go through the analyzer: Lucene
     * sizes AUTO fuzziness from the raw string, where a mark is a character like any other.
     * مسلم is four characters and gets an edit distance of one; مُسلِم is six and gets two.
     * The same word typed two ways then returned 2,953 and 5,938 results - not because the
     * index disagreed, but because one of them was searched twice as loosely.
     *
     * <p>Applied to every term, not only fuzzied ones, so that a quoted phrase and a bare
     * term are cut the same way and the query the user sees explained stays honest.
     */
    private String stripArabicDiacritics(String token) {
        if (token == null || token.isEmpty()) {
            return token;
        }
        return ARABIC_DIACRITICS.matcher(token).replaceAll("");
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

    /**
     * Whether a caller's {@code match_mode} asks for strict matching.
     *
     * <p>Lives here because more than one surface accepts the parameter - the REST endpoint
     * and the MCP {@code search_hadith} tool - and "precise" has to mean the same thing to
     * both. A second copy of this test is a second definition of strictness.
     */
    public boolean isPreciseMatchMode(String matchMode) {
        String normalized = matchMode == null ? "" : matchMode.trim().toLowerCase();
        return "precise".equals(normalized) || "strict".equals(normalized) || "exact".equals(normalized);
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
