package com.rewayaat.mcp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A narration's chain of transmission, shaped for a model that has to say who is in it and how
 * sure the site is.
 *
 * <p>The data is {@code chain_people}, written offline by the resolver: every step of the
 * isnād in the order it is printed, including the ones that resolved to nobody. This class
 * sorts those answers into five statuses and does not decide anything itself. Two rules follow
 * from that and are the point of the class:
 *
 * <ul>
 *   <li><b>A step is {@code identified} only when the resolver stored a person for it.</b>
 *       Everything else is one of four kinds of unanswered step, and each says which kind.
 *       There is no path by which a guess becomes a name.</li>
 *   <li><b>How the man was reached travels with the identification.</b> A plain name that only
 *       one man is filed under and no other man answers to ({@code sole_name}) is not the
 *       same claim as one his filing merely won ({@code filed_under}), nor as a name several men hold
 *       and the resolver chose between ({@code context}), nor as a bare name that in this
 *       author's chains is taken to mean one particular man ({@code convention}). The audits
 *       measured them at different accuracies, so the output keeps them apart.</li>
 * </ul>
 *
 * <p>Steps keep the order the chain is printed in, which is the reverse of the order the
 * report travelled: the first is the compiler's own source and the last is nearest the Imam.
 * That is said in the output, because a model that assumes the other direction will misreport
 * who heard from whom.
 *
 * <p>English is not resolved a second time. The English chain is aligned against the Arabic
 * one, so {@code chain_links_en} holds spans only for the names that were linked, and a step
 * the Arabic left unanswered has no English span at all. Such a step is given in Arabic, and
 * says so, rather than being dropped or having English invented for it.
 */
public final class ChainView {

    public static final String ARABIC = "ar";
    public static final String ENGLISH = "en";
    /**
     * English first, with the Arabic words and name beside it. For {@code fetch}, whose
     * schema ChatGPT fixes at one argument and so cannot take a language.
     */
    public static final String BOTH = "both";

    /** The ways a link was reached, as stored in {@code chain_people.o}, in the output's words. */
    private static final String SOLE_NAME = "sole_name";
    private static final String FILED_UNDER = "filed_under";
    private static final String CONTEXT = "context";
    private static final String CONVENTION = "convention";
    private static final String UNRECORDED = "unrecorded";

    private static final String ORDER = "Steps are in the order the chain is printed: the first "
            + "is the compiler's own source and the last is nearest the Imam. The report "
            + "travelled the other way.";

    private ChainView() {
    }

    /** One step of a chain before it is given names. */
    record Step(int index, String person, String slug, String kind, String outcome,
                String arabic, String english, String refersTo, String refNumber,
                String quotedFrom, int alsoAnswering) {

        /** The five things a step can be, decided only from what the resolver stored. */
        String status() {
            if (!quotedFrom.isEmpty() || "backref_chain".equals(kind)) {
                return "reference";
            }
            if ("collective".equals(kind) || "collective".equals(outcome)) {
                return "group";
            }
            if (!person.isEmpty()) {
                return "identified";
            }
            return "several".equals(outcome) ? "ambiguous" : "unidentified";
        }

        String basis() {
            return switch (outcome) {
                // `one` means one man was filed under the name. Where other men answer to it
                // too - their entries mention it, so the resolver never weighed them against
                // his filing - saying only one man bears it would be false. The loader counts
                // them; see chain_resolve.contested_by.
                case "one" -> alsoAnswering > 0 ? FILED_UNDER : SOLE_NAME;
                case "chosen" -> CONTEXT;
                case "convention" -> CONVENTION;
                default -> UNRECORDED;
            };
        }
    }

    /** Every narrator id the chain names, so a caller can fetch their names in one read. */
    public static Set<String> personIds(Map<String, Object> source) {
        Set<String> ids = new LinkedHashSet<>();
        for (Step step : steps(source)) {
            if (!step.person().isEmpty() && "identified".equals(step.status())) {
                ids.add(step.person());
            }
        }
        return ids;
    }

    /** Whether the narration carries any resolved chain at all. */
    public static boolean hasChain(Map<String, Object> source) {
        return !steps(source).isEmpty();
    }

    /**
     * The chain as the connector returns it.
     *
     * @param language {@code en} or {@code ar}; the language the words and names are given in
     * @param names narrator id to the record {@code NarratorService.names} returns; a man
     *        missing from it is still identified, by id and page, just not by name
     */
    public static Map<String, Object> isnad(Map<String, Object> source, String language,
                                            Map<String, Map<String, Object>> names,
                                            String baseUrl) {
        boolean english = !ARABIC.equals(language);
        boolean both = BOTH.equals(language);
        List<Step> steps = steps(source);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("language", both ? "en+ar" : english ? ENGLISH : ARABIC);
        if (steps.isEmpty()) {
            out.put("steps", List.of());
            out.put("note", "No analysed chain is recorded for this narration. That does not "
                    + "mean it has none: some books give no chain (Nahj al-Balagha is sayings, "
                    + "not transmitted reports), and in others a chain that is printed has not "
                    + "been analysed. Read the Arabic text for it.");
            return out;
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> legend = new LinkedHashSet<>();
        int identified = 0;
        int fallback = 0;
        Map<String, Integer> bases = new LinkedHashMap<>();
        Map<String, Integer> unanswered = new LinkedHashMap<>();

        for (Step step : steps) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("step", step.index());
            String words = english && !step.english().isEmpty() ? step.english() : step.arabic();
            row.put("words", words);
            if (english && step.english().isEmpty()) {
                row.put("words_language", ARABIC);
                fallback++;
            } else if (both) {
                row.put("words_ar", step.arabic());
            }
            String status = step.status();
            row.put("status", status);
            if (!step.kind().isEmpty() && !"name".equals(step.kind())
                    && !"backref_chain".equals(step.kind()) && !"collective".equals(step.kind())) {
                row.put("kind", kindLabel(step.kind()));
            }
            if ("identified".equals(status)) {
                identified++;
                row.put("narrator", narrator(step, language, names, baseUrl));
                row.put("basis", step.basis());
                bases.merge(step.basis(), 1, Integer::sum);
                legend.add(step.basis());
            } else {
                unanswered.merge(status, 1, Integer::sum);
                legend.add(status);
            }
            if (!step.refersTo().isEmpty()) {
                Map<String, Object> refers = new LinkedHashMap<>();
                refers.put("id", step.refersTo());
                if (!step.refNumber().isEmpty()) {
                    refers.put("number", step.refNumber());
                }
                refers.put("url", NarrationView.url(step.refersTo(), baseUrl));
                row.put("refers_to", refers);
            }
            if (!step.quotedFrom().isEmpty()) {
                row.put("quoted_from", step.quotedFrom());
            }
            rows.add(row);
        }

        out.put("order", ORDER);
        out.put("steps", rows);
        out.put("summary", summary(steps.size(), identified, bases, unanswered));
        Map<String, String> explained = new LinkedHashMap<>();
        for (String key : legend) {
            explained.put(key, LEGEND.get(key));
        }
        out.put("legend", explained);
        if (fallback > 0) {
            out.put("note", fallback + (fallback == 1 ? " step is" : " steps are") + " given in "
                    + "Arabic because the English translation does not carry that step as a "
                    + "linked name - a name the translator dropped, or one the books leave "
                    + "unanswered. The English words are aligned to the Arabic, not resolved "
                    + "separately.");
        }
        return out;
    }

    /**
     * Adds an {@code isnad} to each already-shaped narration, with one name lookup for the
     * whole page rather than one per narration. {@code shaped} is parallel to {@code sources}.
     */
    public static void attach(List<NarrationRepository.Narration> sources,
                              List<Map<String, Object>> shaped, String language,
                              com.rewayaat.service.NarratorService narrators, String baseUrl) {
        Set<String> ids = new LinkedHashSet<>();
        for (NarrationRepository.Narration narration : sources) {
            ids.addAll(personIds(narration.source()));
        }
        Map<String, Map<String, Object>> names = narrators.names(ids);
        for (int i = 0; i < sources.size(); i++) {
            shaped.get(i).put("isnad", isnad(sources.get(i).source(), language, names, baseUrl));
        }
    }

    /**
     * Where one man stands in one narration: each step that is his, with how he was reached.
     * A man can stand twice in a chain, so this is a list.
     */
    public static List<Map<String, Object>> placesOf(Map<String, Object> source, String personId,
                                                     String language) {
        boolean english = !ARABIC.equals(language);
        List<Step> steps = steps(source);
        List<Map<String, Object>> places = new ArrayList<>();
        for (Step step : steps) {
            if (!personId.equals(step.person()) || !"identified".equals(step.status())) {
                continue;
            }
            Map<String, Object> place = new LinkedHashMap<>();
            place.put("step", step.index());
            place.put("of", steps.size());
            String words = english && !step.english().isEmpty() ? step.english() : step.arabic();
            place.put("words", words);
            if (english && step.english().isEmpty()) {
                place.put("words_language", ARABIC);
            }
            place.put("basis", step.basis());
            if (!step.kind().isEmpty() && !"name".equals(step.kind())) {
                place.put("kind", kindLabel(step.kind()));
            }
            places.add(place);
        }
        return places;
    }

    /** What each {@code basis} and each status means, given once per result and only if used. */
    static final Map<String, String> LEGEND = Map.of(
            SOLE_NAME, "Only one man in the Rijal books bears this name.",
            FILED_UNDER, "Only one man is filed under this name, and he is the one named here, "
                    + "but other men's entries mention the same name. The books' own filing "
                    + "was followed; it was not weighed against those men. Check it against "
                    + "the chain before resting anything on it.",
            CONTEXT, "Several men bear this name; this one was chosen because the books show "
                    + "him transmitting with the neighbouring name in a chain. A weaker "
                    + "identification than sole_name.",
            CONVENTION, "A bare name that, in this author's chains, is conventionally taken to "
                    + "mean one particular man. It rests on that convention, not on the name "
                    + "alone.",
            UNRECORDED, "A man was linked but the data does not record how.",
            "unidentified", "A step the books do not let us name: no Rijal entry is filed under "
                    + "these words. Left unanswered on purpose; not a missing record.",
            "ambiguous", "Several men bear this name and nothing in the books chooses between "
                    + "them. Left unanswered rather than guessed.",
            "group", "A group, not a man (for example 'some of our companions'). Nobody can be "
                    + "named behind it.",
            "reference", "Points to an earlier chain (for example 'by this chain') or to a "
                    + "chain in another book; it is not a man.");

    /** The explanations for the given codes, in the order given, skipping any not recognised. */
    public static Map<String, String> legend(Iterable<String> codes) {
        Map<String, String> explained = new LinkedHashMap<>();
        for (String code : codes) {
            if (LEGEND.containsKey(code)) {
                explained.put(code, LEGEND.get(code));
            }
        }
        return explained;
    }

    private static String summary(int total, int identified, Map<String, Integer> bases,
                                  Map<String, Integer> unanswered) {
        StringBuilder text = new StringBuilder();
        text.append(total).append(total == 1 ? " step" : " steps").append("; ")
                .append(identified).append(" identified");
        if (!bases.isEmpty()) {
            List<String> parts = new ArrayList<>();
            bases.forEach((basis, count) -> parts.add(count + " by " + basis));
            text.append(" (").append(String.join(", ", parts)).append(")");
        }
        int open = unanswered.values().stream().mapToInt(Integer::intValue).sum();
        text.append("; ").append(open).append(" not identified");
        if (open > 0) {
            List<String> parts = new ArrayList<>();
            unanswered.forEach((status, count) -> parts.add(count + " " + status));
            text.append(" (").append(String.join(", ", parts)).append(")");
        }
        return text.append(".").toString();
    }

    private static String kindLabel(String kind) {
        return switch (kind) {
            case "imam" -> "imam";
            case "relative" -> "relative";
            case "backref_man" -> "back_reference";
            default -> kind;
        };
    }

    private static Map<String, Object> narrator(Step step, String language,
                                                Map<String, Map<String, Object>> names,
                                                String baseUrl) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", step.person());
        boolean both = BOTH.equals(language);
        Map<String, Object> record = names == null ? null : names.get(step.person());
        if (record != null) {
            String ar = NarrationView.str(record.get("display_ar"));
            String en = NarrationView.str(record.get("display_en"));
            String name = ARABIC.equals(language) ? (ar.isEmpty() ? en : ar)
                    : (en.isEmpty() ? ar : en);
            if (!name.isEmpty()) {
                out.put("name", name);
            }
            if (both && !ar.isEmpty()) {
                out.put("name_ar", ar);
            }
        }
        String slug = step.slug().isEmpty() && record != null
                ? NarrationView.str(record.get("slug")) : step.slug();
        out.put("url", baseUrl + "/narrator/" + step.person() + (slug.isEmpty() ? "" : "/" + slug));
        return out;
    }

    /** Parses {@code chain_people} into steps, in printed order, with English aligned to them. */
    static List<Step> steps(Map<String, Object> source) {
        List<Map<?, ?>> people = rows(source.get("chain_people"));
        if (people.isEmpty()) {
            return List.of();
        }
        people.sort(Comparator.comparingInt(row -> number(row.get("s"))));
        List<Map<?, ?>> english = rows(source.get("chain_links_en"));
        english.sort(Comparator.comparingInt(row -> number(row.get("s"))));

        List<Step> steps = new ArrayList<>();
        int cursor = 0;
        int index = 0;
        for (Map<?, ?> row : people) {
            String words = NarrationView.str(row.get("t"));
            if (words.isEmpty()) {
                continue;
            }
            String person = NarrationView.str(row.get("p"));
            // The English side is matched by person, walking forward, so a man who stands
            // twice in one chain gets his two English spans in order and not the first twice.
            String englishWords = "";
            if (!person.isEmpty()) {
                for (int at = cursor; at < english.size(); at++) {
                    if (person.equals(NarrationView.str(english.get(at).get("p")))) {
                        englishWords = NarrationView.str(english.get(at).get("t"));
                        cursor = at + 1;
                        break;
                    }
                }
            }
            steps.add(new Step(++index, person, NarrationView.str(row.get("g")),
                    NarrationView.str(row.get("k")), NarrationView.str(row.get("o")),
                    words, englishWords, NarrationView.str(row.get("r")),
                    NarrationView.str(row.get("rn")), NarrationView.str(row.get("qf")),
                    count(row.get("a"))));
        }
        return steps;
    }

    private static List<Map<?, ?>> rows(Object raw) {
        List<Map<?, ?>> rows = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    rows.add(map);
                }
            }
        }
        return rows;
    }

    /** A count, nought when the key is absent - unlike {@link #number}, whose default is the
     * end of the sort order. A step without {@code a} is a name no other man answers to. */
    private static int count(Object value) {
        return value instanceof Number n ? Math.max(n.intValue(), 0) : 0;
    }

    private static int number(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (RuntimeException ex) {
            return Integer.MAX_VALUE;
        }
    }
}
