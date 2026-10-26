package com.rewayaat.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Imam a narration rests on when the isnād does not name him.
 *
 * <p>A chain usually ends on an Imam: «… عن أبي عبد الله عليه السلام قال». Often enough it
 * does not, and the Imam is named in the narration instead — «… عن أحمد بن سليمان القمي
 * الكوفي قال: سمعت أبا عبد الله جعفر بن محمد عليهما السلام يقول». The isnād is complete
 * either way and the report rests on the same man, but the chain panel could only see the
 * first kind, so 2,632 narrations here showed a chain that appeared to stop at a narrator.
 *
 * <p>This reads the opening of the body for a name carrying a blessing. Only the opening:
 * an Imam named three sentences into a narration is being talked about, not transmitted
 * from, and «قال رسول الله صلى الله عليه وآله» inside the words of al-Ṣādiq is a quotation
 * within the report rather than its authority. The blessing is required for the same
 * reason — «جعفر بن محمد» without one is a narrator's name far more often than an Imam's.
 *
 * <p>The roster is generated from {@code scripts/narrators/imams.py}, which is where the
 * chain resolver reads it from too; see {@code scripts/narrators/export_imams.py}. Keeping
 * a second copy by hand is how the two come to disagree about which «أبو جعفر» is meant.
 */
public final class ImamInBody {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImamInBody.class);

    /** How far into the body an Imam still counts as the one it is being reported from. */
    private static final int OPENING = 160;

    private static final String MARKS =
            "[\\u064B-\\u0655\\u0670\\u0640\\u06DF-\\u06E8\\u06EA-\\u06ED]*";

    /** «عليه السلام» and its kin, which is what marks a name as one of the fourteen's. */
    private static final Pattern BLESSED = Pattern.compile(
            "(عليه|عليها|عليهم|عليهما|صلوات|صلى)" + MARKS + "\\s*(ال)?" + MARKS
                    + "(سلام|الله|اللّه)");

    private static final List<Imam> ROSTER = load();

    private ImamInBody() {
    }

    /** One of the fourteen: who he is, and the forms that name him in a narration. */
    public record Imam(String id, String slug, String nameAr, String nameEn, Pattern named) {
    }

    private static List<Imam> load() {
        List<Imam> roster = new ArrayList<>();
        try (InputStream in = new ClassPathResource("data/imams.json").getInputStream()) {
            for (JsonNode node : new ObjectMapper().readTree(in).path("imams")) {
                StringBuilder forms = new StringBuilder();
                for (JsonNode form : node.path("exact")) {
                    String text = form.asText("");
                    // One-word forms are left out. «علي» and «الحسين» name an Imam in a
                    // chain, where the shape of the clause says so, and name somebody's
                    // son anywhere else; in running prose they would fire constantly.
                    if (text.isBlank() || text.split("\\s+").length < 2) {
                        continue;
                    }
                    if (forms.length() > 0) {
                        forms.append('|');
                    }
                    forms.append(arabic(text));
                }
                if (forms.length() == 0) {
                    continue;
                }
                roster.add(new Imam(node.path("id").asText(""), node.path("slug").asText(""),
                        node.path("nameAr").asText(""), node.path("nameEn").asText(""),
                        Pattern.compile("(?:" + forms + ")")));
            }
        } catch (Exception ex) {
            LOGGER.warn("Could not read the Imam roster; chains will not name an Imam that "
                    + "stands in the body", ex);
        }
        return List.copyOf(roster);
    }

    /**
     * The same diacritic-blind matching {@link ChainConventions} uses: the corpus is
     * typeset fully vowelled in some books and bare in others, and the alif forms vary
     * within one word across editions.
     */
    private static String arabic(String phrase) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < phrase.length(); i++) {
            char c = phrase.charAt(i);
            if (c == ' ') {
                out.append("\\s+");
                continue;
            }
            switch (c) {
                case 'ا', 'أ', 'إ', 'آ' -> out.append("[\\u0627\\u0623\\u0625\\u0622]");
                case 'ي', 'ى' -> out.append("[\\u064A\\u0649]");
                case 'ه', 'ة' -> out.append("[\\u0647\\u0629]");
                default -> out.append(Pattern.quote(String.valueOf(c)));
            }
            out.append(MARKS);
        }
        return out.toString();
    }

    /**
     * The Imam a chain ends on but does not name, ready to render, or {@code null}.
     *
     * <p>Takes the stored {@code chain_people} rather than a built chain, so that the
     * search card — which builds its chain in the browser — and the server-rendered card
     * answer the same question the same way.
     */
    public static Map<String, String> endsInBody(Object chainPeople, String body, String prefix) {
        if (!(chainPeople instanceof List<?> steps) || steps.isEmpty()) {
            return null;
        }
        for (Object step : steps) {
            if (step instanceof Map<?, ?> row && "imam".equals(String.valueOf(row.get("k")))
                    && !String.valueOf(row.get("p")).isBlank()) {
                return null;  // the chain names one itself
            }
        }
        Imam imam = inOpening(body);
        if (imam == null) {
            return null;
        }
        Map<String, String> ends = new LinkedHashMap<>();
        ends.put("words", imam.nameEn());
        ends.put("wordsAr", imam.nameAr());
        ends.put("person", imam.id());
        ends.put("href", (prefix == null ? "" : prefix) + "/narrator/" + imam.id()
                + (imam.slug().isBlank() ? "" : "/" + imam.slug()));
        return ends;
    }

    /**
     * The Imam named at the head of this narration, or {@code null}.
     *
     * <p>The earliest match wins, and it has to be followed closely by a blessing: «أبا عبد
     * الله» with «عليه السلام» two words later is the man being heard from, and the same
     * words without one are a kunya in a sentence.
     */
    public static Imam inOpening(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String head = body.length() > OPENING ? body.substring(0, OPENING) : body;
        Imam earliest = null;
        int at = Integer.MAX_VALUE;
        for (Imam imam : ROSTER) {
            Matcher matcher = imam.named().matcher(head);
            if (!matcher.find() || matcher.start() >= at) {
                continue;
            }
            // The blessing has to follow the name within a few words, not merely appear
            // somewhere in the opening.
            int from = matcher.end();
            int to = Math.min(head.length(), from + 40);
            if (!BLESSED.matcher(head.substring(from, to)).find()) {
                continue;
            }
            earliest = imam;
            at = matcher.start();
        }
        return earliest;
    }
}
