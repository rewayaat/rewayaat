package com.rewayaat.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chain as a model is handed it, checked against narrations copied out of the live index.
 *
 * <p>The fixtures are not invented. {@code real-chains.json} holds the chain fields of eight
 * narrations exactly as Elasticsearch returned them, and {@code real-narrators.json} the name
 * fields of the men they resolve to. A synthetic chain would pass tests that the real shapes
 * fail - the English side in particular drops steps in ways nobody would think to invent.
 *
 * <p>What is asserted is the claim each step makes, because the risk in this class is not a
 * crash: it is an answer that reads as more certain than the data is.
 */
class ChainViewTest {

    private static final String BASE = "https://example.test";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Map<String, Map<String, Object>> NARRATIONS = load("/mcp/real-chains.json");
    private static final Map<String, Map<String, Object>> NARRATORS = load("/mcp/real-narrators.json");

    private static Map<String, Map<String, Object>> load(String resource) {
        try (InputStream in = ChainViewTest.class.getResourceAsStream(resource)) {
            return MAPPER.readValue(in, new TypeReference<>() { });
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> steps(String id, String language) {
        Map<String, Object> isnad = ChainView.isnad(NARRATIONS.get(id), language, NARRATORS, BASE);
        return (List<Map<String, Object>>) isnad.get("steps");
    }

    private static Map<String, Object> isnad(String id, String language) {
        return ChainView.isnad(NARRATIONS.get(id), language, NARRATORS, BASE);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> narrator(Map<String, Object> step) {
        return (Map<String, Object>) step.get("narrator");
    }

    // ---- a full chain ----

    @Test
    void aFullChainComesBackInPrintedOrderWithEveryManNamedAndLinked() {
        List<Map<String, Object>> steps = steps("Al-Kafi-Volume-1-Kulayni:10", "en");

        assertEquals(5, steps.size());
        assertEquals(List.of(1, 2, 3, 4, 5),
                steps.stream().map(s -> s.get("step")).collect(Collectors.toList()));
        assertEquals(List.of("n000706", "n000034", "n029388", "n036590", "i06"),
                steps.stream().map(s -> narrator(s).get("id")).collect(Collectors.toList()),
                "Ali b. Ibrahim, his father Ibrahim b. Hashim, al-Nawfali, al-Sakuni, "
                        + "al-Sadiq - in the order the chain is printed.");
        assertEquals("Ali ibn Ibrahim ibn Hashim", narrator(steps.get(0)).get("name"));
        assertEquals(BASE + "/narrator/n000706/ali-ibn-ibrahim-ibn-hashim",
                narrator(steps.get(0)).get("url"));
        assertEquals(BASE + "/narrator/i06/jafar-al-sadiq", narrator(steps.get(4)).get("url"),
                "An Imam is a person with a page like any other.");
    }

    @Test
    void theOrderIsLabelledBecauseItIsTheReverseOfTheDirectionTheReportTravelled() {
        String order = String.valueOf(isnad("Al-Kafi-Volume-1-Kulayni:10", "en").get("order"));
        assertTrue(order.contains("compiler's own source"), order);
        assertTrue(order.contains("nearest the Imam"), order);
    }

    // ---- how sure ----

    @Test
    void eachIdentifiedStepSaysHowTheManWasReached() {
        List<Map<String, Object>> steps = steps("Al-Kafi-Volume-1-Kulayni:10", "en");

        assertEquals("convention", steps.get(0).get("basis"),
                "Ali b. Ibrahim opening a chain of al-Kulayni rests on a convention, and the "
                        + "output must not present it as a plain name match.");
        assertEquals("context", steps.get(1).get("basis"),
                "«his father» is read off the man before him, not named.");
        assertEquals("relative", steps.get(1).get("kind"));
        assertEquals("sole_name", steps.get(2).get("basis"));
        assertEquals("imam", steps.get(4).get("kind"));
    }

    @Test
    void theLegendExplainsOnlyTheCodesTheChainUses() {
        @SuppressWarnings("unchecked")
        Map<String, String> legend = (Map<String, String>) isnad("Al-Kafi-Volume-1-Kulayni:10", "en")
                .get("legend");

        assertEquals(List.of("convention", "context", "sole_name"), List.copyOf(legend.keySet()));
        assertTrue(legend.get("convention").contains("not on the name alone"));
        assertTrue(legend.get("context").contains("weaker identification"));
        assertFalse(legend.containsKey("unidentified"),
                "A chain with no unanswered step should not pay for explaining them.");
    }

    @Test
    void theSummaryCountsWhatWasIdentifiedAndByWhatBasis() {
        assertEquals("5 steps; 5 identified (1 by convention, 1 by context, 3 by sole_name); "
                        + "0 not identified.",
                isnad("Al-Kafi-Volume-1-Kulayni:10", "en").get("summary"));
    }

    // ---- unanswered steps ----

    @Test
    void unansweredStepsAreMarkedAndNeverGivenANarrator() {
        List<Map<String, Object>> steps = steps("Al-Kafi-Volume-1-Kulayni:878", "en");

        assertEquals(List.of("ambiguous", "ambiguous", "unidentified", "unidentified",
                        "unidentified", "group"),
                steps.stream().map(s -> s.get("status")).collect(Collectors.toList()),
                "Two names several men hold, three the books do not cover, and «a man of the "
                        + "people of Fars whom he named».");
        for (Map<String, Object> step : steps) {
            assertNull(step.get("narrator"), "Step " + step.get("step") + " is unanswered; "
                    + "naming a man for it would be a guess presented as an identification.");
            assertNull(step.get("basis"));
            assertFalse(String.valueOf(step.get("words")).isBlank(),
                    "The words as printed are always given, so the reader can see what was "
                            + "left unanswered.");
        }
    }

    @Test
    void anAllUnansweredChainSaysSoInItsSummaryAndExplainsEachKind() {
        Map<String, Object> isnad = isnad("Al-Kafi-Volume-1-Kulayni:878", "en");

        assertEquals("6 steps; 0 identified; 6 not identified (2 ambiguous, 3 unidentified, "
                + "1 group).", isnad.get("summary"));
        @SuppressWarnings("unchecked")
        Map<String, String> legend = (Map<String, String>) isnad.get("legend");
        assertTrue(legend.get("ambiguous").contains("rather than guessed"));
        assertTrue(legend.get("group").contains("Nobody can be named"));
    }

    @Test
    void aChainMixingAnsweredAndUnansweredStepsKeepsThePositionOfEach() {
        List<Map<String, Object>> steps = steps("Kamil-al-Ziyarat-Qummi:409", "en");

        assertEquals(List.of("identified", "identified", "identified", "group", "identified"),
                steps.stream().map(s -> s.get("status")).collect(Collectors.toList()));
        assertEquals("i06", narrator(steps.get(4)).get("id"),
                "The unanswered step sits between al-Khashshab and the Imam, where the chain "
                        + "has it - it is not collected at the end.");
    }

    // ---- references that are not men ----

    @Test
    @SuppressWarnings("unchecked")
    void aRepeatedChainPointsAtTheNarrationItRepeats() {
        List<Map<String, Object>> steps = steps("Uyun-akhbar-al-Rida-Volume-2-Saduq:139", "en");

        assertEquals("reference", steps.get(0).get("status"));
        assertNull(steps.get(0).get("narrator"), "«by this chain» is not a man");
        Map<String, Object> refers = (Map<String, Object>) steps.get(0).get("refers_to");
        assertEquals("Uyun-akhbar-al-Rida-Volume-2-Saduq:4", refers.get("id"));
        assertEquals("4", refers.get("number"));
        assertEquals(BASE + "/hadith/Uyun-akhbar-al-Rida-Volume-2-Saduq:4", refers.get("url"));
        assertEquals("i01", narrator(steps.get(1)).get("id"));
    }

    @Test
    void aStepQuotedFromAnotherBookNamesTheBook() {
        List<Map<String, Object>> steps = steps("Mujam-al-Ahadith-al-Mutabara-Muhsini:268", "en");

        assertEquals("reference", steps.get(0).get("status"));
        assertEquals("تهذيب الاحكام", steps.get(0).get("quoted_from"));
        assertEquals("sole_name", steps.get(1).get("basis"));
        assertEquals("context", steps.get(2).get("basis"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPronounResolvedToAManIsIdentifiedByConventionAndSaysWhichNarrationItReaches() {
        List<Map<String, Object>> steps = steps("Al-Kafi-Volume-6-Kulayni:1308", "en");

        assertEquals("identified", steps.get(0).get("status"));
        assertEquals("n034836", narrator(steps.get(0)).get("id"));
        assertEquals("convention", steps.get(0).get("basis"));
        assertEquals("back_reference", steps.get(0).get("kind"));
        assertEquals("Al-Kafi-Volume-6-Kulayni:1307",
                ((Map<String, Object>) steps.get(0).get("refers_to")).get("id"),
                "«عنه» points at the previous narration's man; the step must say where.");
    }

    // ---- both languages ----

    @Test
    void englishGivesTheEnglishWordsWhereTheTranslationCarriesTheName() {
        List<Map<String, Object>> steps = steps("Al-Kafi-Volume-2-Kulayni:969", "en");

        assertEquals("Al-Husayn ibn Muhammad", steps.get(0).get("words"));
        assertEquals("al- Washsha", steps.get(2).get("words"),
                "Words are given as printed in the translation, typo and all.");
        assertNull(steps.get(0).get("words_language"));
    }

    @Test
    void aStepTheTranslationDropsIsGivenInArabicAndSaysSo() {
        Map<String, Object> isnad = isnad("Al-Kafi-Volume-2-Kulayni:969", "en");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) isnad.get("steps");

        Map<String, Object> imam = steps.get(5);
        assertEquals("أَبِي جَعْفَرٍ", imam.get("words"),
                "The English chain does not carry the Imam, so there are no English words to "
                        + "give; inventing «Abu Ja'far» would be the pipeline's claim, not ours.");
        assertEquals("ar", imam.get("words_language"));
        assertEquals("i05", narrator(imam).get("id"), "He is still identified.");
        assertTrue(String.valueOf(isnad.get("note")).contains("1 step is given in Arabic"));
    }

    @Test
    void arabicGivesArabicWordsAndArabicNamesWithoutAnyFallbackNote() {
        Map<String, Object> isnad = isnad("Al-Kafi-Volume-2-Kulayni:969", "ar");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) isnad.get("steps");

        assertEquals("ar", isnad.get("language"));
        // Taken from the fixture rather than typed: the vowel marks have more than one valid
        // order, and the point is that the words are the stored ones, untouched.
        Map<?, ?> stored = (Map<?, ?>) ((List<?>) NARRATIONS.get("Al-Kafi-Volume-2-Kulayni:969")
                .get("chain_people")).get(0);
        assertEquals(stored.get("t"), steps.get(0).get("words"));
        assertTrue(String.valueOf(steps.get(0).get("words")).contains("بْنُ"));
        assertEquals("محمد بن علي الباقر", narrator(steps.get(5)).get("name"));
        assertNull(isnad.get("note"));
        for (Map<String, Object> step : steps) {
            assertNull(step.get("words_language"));
        }
    }

    @Test
    void theEnglishSpansAreMatchedToStepsByManAndNotByPosition() {
        // Kamil 409: the English chain has three spans and the Arabic five steps, with the
        // unanswered group between the third and the Imam. A positional zip would hand the
        // group the Imam's slot or shift every later step.
        List<Map<String, Object>> steps = steps("Kamil-al-Ziyarat-Qummi:409", "en");

        assertEquals("Muḥammad ibn Ḥasan ibn Walīd", steps.get(0).get("words"));
        assertEquals("Ḥasan ibn Mūsā Al-Khashāb", steps.get(2).get("words"));
        assertEquals("بَعْضِ رِجَالِهِ", steps.get(3).get("words"));
        assertEquals("ar", steps.get(3).get("words_language"));
        assertEquals("ar", steps.get(4).get("words_language"));
    }

    // ---- no chain ----

    @Test
    void aNarrationWithNoChainSaysNoAnalysedChainRatherThanNoChain() {
        Map<String, Object> isnad = isnad("Nahj-al-Balagha-Radi:125", "en");

        assertEquals(List.of(), isnad.get("steps"));
        String note = String.valueOf(isnad.get("note"));
        assertTrue(note.contains("No analysed chain"), note);
        assertTrue(note.contains("does not mean it has none"),
                "Absence of data is not absence of a chain: the Kafi has 272 narrations that "
                        + "print a chain this analysis does not cover.");
        assertFalse(isnad.containsKey("legend"));
        assertFalse(ChainView.hasChain(NARRATIONS.get("Nahj-al-Balagha-Radi:125")));
    }

    // ---- the narrator-to-narration direction ----

    @Test
    void placesOfReportsTheStepTheBasisAndTheWordsPrintedThere() {
        List<Map<String, Object>> places = ChainView.placesOf(
                NARRATIONS.get("Al-Kafi-Volume-1-Kulayni:10"), "n029388", "en");

        assertEquals(1, places.size());
        Map<String, Object> place = places.get(0);
        assertEquals(3, place.get("step"));
        assertEquals(5, place.get("of"));
        assertEquals("al-Nawfali", place.get("words"));
        assertEquals("sole_name", place.get("basis"));
    }

    @Test
    void placesOfSaysConventionWhereTheManWasReachedByConvention() {
        Map<String, Object> place = ChainView.placesOf(
                NARRATIONS.get("Al-Kafi-Volume-1-Kulayni:10"), "n000706", "en").get(0);
        assertEquals("convention", place.get("basis"));
        assertEquals(1, place.get("step"));
    }

    @Test
    void placesOfIsEmptyForAManNotInTheChain() {
        assertEquals(List.of(), ChainView.placesOf(
                NARRATIONS.get("Al-Kafi-Volume-1-Kulayni:10"), "n999999", "en"));
    }

    @Test
    void personIdsListsEveryManOnceAndNoUnansweredStep() {
        assertEquals(List.of("n000170", "n001142", "n000093", "n015846", "n030321", "i05"),
                List.copyOf(ChainView.personIds(NARRATIONS.get("Al-Kafi-Volume-2-Kulayni:969"))));
        assertEquals(List.of(), List.copyOf(
                ChainView.personIds(NARRATIONS.get("Al-Kafi-Volume-1-Kulayni:878"))));
    }

    // ---- robustness ----

    @Test
    void aManAbsentFromTheNameIndexIsStillIdentifiedByIdAndPage() {
        Map<String, Object> isnad = ChainView.isnad(
                NARRATIONS.get("Al-Kafi-Volume-1-Kulayni:10"), "en", Map.of(), BASE);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) ((List<?>) isnad.get("steps")).get(0);

        assertEquals("identified", first.get("status"));
        assertNull(narrator(first).get("name"));
        assertEquals(BASE + "/narrator/n000706/ali-ibn-ibrahim-ibn-hashim", narrator(first).get("url"));
    }

    @Test
    void anOutcomeTheViewDoesNotKnowIsNeverRenderedAsAnIdentification() {
        // A future resolver outcome must degrade to "unidentified", not to a name.
        Map<String, Object> source = Map.of("chain_people", List.of(Map.of(
                "p", "", "s", 0, "e", 5, "t", "فلان", "g", "", "k", "name", "o", "some_new_outcome")));
        @SuppressWarnings("unchecked")
        Map<String, Object> step = ((List<Map<String, Object>>) ChainView
                .isnad(source, "en", Map.of(), BASE).get("steps")).get(0);

        assertEquals("unidentified", step.get("status"));
        assertNull(step.get("narrator"));
    }
}
