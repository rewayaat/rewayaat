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
        query = balanceDelimiters(query);
        query = dropHonorifics(query);
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
            // A delimiter that balanceDelimiters has escaped is text, not punctuation:
            // counting it would keep the term open and swallow the rest of the query.
            boolean escaped = i > 0 && query.charAt(i - 1) == '\\';
            if (currChar == ' ' && nextingLevel == 0) {
                splitted.add(result.toString());
                result.setLength(0);
            } else {
                if (!escaped) {
                    if (currChar == ')' | currChar == ']'
                        || (currChar == '\"' && (nextChar == null || nextChar == ' '))) {
                        nextingLevel--;
                    } else if (currChar == '(' | currChar == '[' | currChar == '\"') {
                        nextingLevel++;
                    }
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
            s = escapePunctuation(s);
            if (s.isEmpty() || !hasWordCharacter(s)) {
                // A term can empty out here and not before: «ـ», a tatweel on its own, is
                // all diacritic and nothing else, and the salutation leaves its punctuation
                // behind when it goes. An empty term between two operators leaves
                // «AND  AND», which the query parser refuses, and a term of pure
                // punctuation searches for nothing.
                continue;
            }
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

    /**
     * The salutation after a name, in every form the corpus and its readers write it.
     *
     * <p>Bracketed or bare, abbreviated or spelled out: «(ع)», «[ع]», «عليه السلام»,
     * «صلى الله عليه وآله». Diacritics are already gone by the time this runs.
     */
    private static final java.util.regex.Pattern HONORIFIC = java.util.regex.Pattern.compile(
            "[\\(\\[]\\s*(?:ع|ص|س|عج|رض|عليه\\s+السلام|عليها\\s+السلام|عليهم\\s+السلام|"
            + "عليهما\\s+السلام|صلى\\s+الله\\s+عليه\\s+وآله(?:\\s+وسلم)?|رضي\\s+الله\\s+عنه)\\s*[\\)\\]]"
            + "|(?<![\\u0621-\\u064a])(?:عليه\\s+السلام|عليها\\s+السلام|عليهم\\s+السلام|"
            + "عليهما\\s+السلام|صلى\\s+الله\\s+عليه\\s+وآله(?:\\s+وسلم)?|رضي\\s+الله\\s+عنه)"
            + "(?![\\u0621-\\u064a])");

    /**
     * Takes the salutation out of a reader's query.
     *
     * <p>The corpus does not write it one way. «عليه السلام» stands in 9,447 narrations,
     * «(عليه السلام)» in 3,605, «( عليه السلام )» in 2,361, «(ع)» in 806 — so a phrase
     * copied out of one book cannot match the same hadith in another, and a reader who
     * pastes a narration back into the search gets nothing. It carries no search signal
     * either way: it follows every name in twenty thousand narrations. Dropping it from
     * the query lets all the forms match each other.
     *
     * <p>A query that is nothing but a salutation keeps it, so that searching for the
     * phrase itself still works.
     */
    private String dropHonorifics(String query) {
        String stripped = HONORIFIC.matcher(query).replaceAll(" ").replaceAll("\\s+", " ").trim();
        return stripped.isEmpty() ? query : stripped;
    }

    /** Whether a term has anything in it to search for, once punctuation is discounted. */
    private boolean hasWordCharacter(String token) {
        for (int i = 0; i < token.length(); i++) {
            if (Character.isLetterOrDigit(token.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** A field name is ASCII: {@code book:}, {@code id:}. Arabic before a colon never is. */
    private static final java.util.regex.Pattern FIELD_PREFIX =
            java.util.regex.Pattern.compile("^[+-]?[A-Za-z_][A-Za-z0-9_.]*:");

    /**
     * Makes one term safe for the query parser without taking away the syntax we offer.
     *
     * <p>Readers paste hadith, and hadith are punctuated: «قَالَ:» ends a clause, «[ع]»
     * abbreviates a salutation. The parser reads a colon as a field separator and a
     * bracket as a range, so «قال: AND إن» is a syntax error and the whole search fails
     * with a 500 rather than a result. Punctuation only keeps its meaning where it could
     * plausibly have been meant: a colon after an ASCII field name that actually has a
     * value, and nothing else.
     */
    private String escapePunctuation(String token) {
        if (token == null || token.isEmpty()) {
            return token == null ? "" : token;
        }
        StringBuilder out = new StringBuilder(token.length() + 4);
        boolean fieldColonAllowed = false;
        java.util.regex.Matcher field = FIELD_PREFIX.matcher(token);
        if (field.find()) {
            // ...but only when something follows it; «book:» alone is as broken as «قال:»
            fieldColonAllowed = token.length() > field.end();
        }
        boolean inQuotes = false;
        boolean colonSpent = false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                out.append(c);
                continue;
            }
            if (inQuotes || c == '\\') {
                out.append(c);
                continue;
            }
            if (c == ':' && fieldColonAllowed && !colonSpent) {
                colonSpent = true;          // the first colon names the field; later ones are text
                out.append(c);
                continue;
            }
            if (c == ':' || c == '[' || c == ']' || c == '{' || c == '}') {
                out.append('\\').append(c);
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * Escapes quotes or brackets the reader left unclosed, across the whole query.
     *
     * <p>Half a quoted phrase is not a phrase, and the parser says so by failing. Which
     * half was meant is unknowable, so both are read as ordinary text.
     */
    private String balanceDelimiters(String query) {
        long quotes = query.chars().filter(c -> c == '"').count();
        if (quotes % 2 != 0) {
            query = query.replace("\"", "\\\"");
        }
        long open = query.chars().filter(c -> c == '(').count();
        long close = query.chars().filter(c -> c == ')').count();
        if (open != close) {
            query = query.replace("(", "\\(").replace(")", "\\)");
        }
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
