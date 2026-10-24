package com.rewayaat.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Whether a narration gets a rijāl tab at all.
 *
 * <p>Ghurar al-Ḥikam, Mīzān al-Ḥikma and Mishkāt al-Anwār carry no isnād, and Nahj al-Balāgha
 * has one on fifteen of 2,260. A tab that opens onto «0 links» is a third of the corpus
 * promising something it cannot show, so both renderers decide on the same thing: the list
 * {@link HadithCardFactory} builds from {@code chain_people}. An empty list means no button
 * and no panel. These tests pin the list; the templates are pinned to it below.
 */
class HadithCardFactoryChainTest {

    private static final Path SERVER_CARD =
            Path.of("src/main/resources/templates/fragments/hadith-card.html");
    private static final Path SEARCH_CARD = Path.of("src/main/resources/templates/index.html");
    private static final Path SEARCH_SCRIPT = Path.of("src/main/resources/static/js/rewayaat.js");

    private final HadithCardFactory factory = new HadithCardFactory(
            mock(BookCatalog.class), mock(TopicLabelSource.class), new StaticMessageSource());

    private static Map<String, Object> narration(Object chainPeople, Object chainLinks) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("book", "Al-Kāfī");
        source.put("number", "1");
        source.put("arabic", "عَلِيُّ بْنُ إِبْرَاهِيمَ عَنْ أَبِيهِ عَنِ الصَّادِقِ عليه السلام قَالَ كَذَا");
        source.put("english", "Ali b. Ibrahim from his father from al-Sadiq said thus.");
        if (chainPeople != null) {
            source.put("chain_people", chainPeople);
        }
        if (chainLinks != null) {
            source.put("chain_links", chainLinks);
        }
        return source;
    }

    private Map<String, Object> card(Map<String, Object> source) {
        return factory.build("id1", source, "/book/x", "https://example.test");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> chain(Map<String, Object> row) {
        return (List<Map<String, Object>>) row.get("chain");
    }

    @Test
    void aNarrationWithChainPeopleGetsTheTab() {
        Map<String, Object> row = card(narration(List.of(
                Map.of("t", "عَلِيُّ بْنُ إِبْرَاهِيمَ", "p", "123", "k", "name", "g", "ali"),
                Map.of("t", "الصَّادِقِ", "p", "9", "k", "imam")), null));
        assertEquals(2, chain(row).size());
        assertEquals("123,9", row.get("chainIds"));
    }

    @Test
    void aNarrationWithNoChainDataHasNoTab() {
        // Ghurar, Mīzān, Mishkāt: the field is absent altogether.
        assertTrue(chain(card(narration(null, null))).isEmpty());
        // And an empty list is the same as an absent one.
        assertTrue(chain(card(narration(List.of(), null))).isEmpty());
    }

    @Test
    void stepsWithNoWordsAreNotLinksTheTabCouldList() {
        Map<String, Object> row = card(narration(List.of(
                Map.of("t", "", "p", "123", "k", "name"),
                Map.of("t", "   ", "p", "9", "k", "imam")), null));
        assertTrue(chain(row).isEmpty());
    }

    @Test
    void linksThatChainLinksDropsDoNotTakeTheTabAway() {
        // ChainLinks drops a span whose text no longer matches the narration, and that only
        // un-links a name inside the isnād text. The tab is drawn from chain_people's own
        // words, not from those spans, so the men are still listed and the tab stays.
        List<Map<String, Object>> drifted = List.of(
                Map.of("s", 0, "e", 5, "p", "123", "t", "not what the text says"));
        Map<String, Object> row = card(narration(List.of(
                Map.of("t", "عَلِيُّ بْنُ إِبْرَاهِيمَ", "p", "123", "k", "name")), drifted));
        assertEquals(1, chain(row).size());
        assertFalse(((String) row.get("arabicChain")).contains("<a "),
                "the drifted span must have been dropped");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUndecidedStepIsNeitherNotFoundNorLost() {
        // The resolver found three men named Aḥmad b. Muḥammad and would not choose. The
        // panel used to call that "not found"; it now carries the three and links each.
        Map<String, Object> row = card(narration(List.of(
                Map.of("t", "أَحْمَدُ بْنُ مُحَمَّدٍ", "p", "", "k", "name", "o", "several",
                        "rf", "the book writes this name out, but not here",
                        "c", List.of(
                                Map.of("p", "n002014", "n", "أحمد بن محمد بن عيسى", "g", "ahmad-b-isa", "h", "القمي"),
                                Map.of("p", "n003502", "n", "أحمد بن محمد", "g", ""))),
                Map.of("t", "الصَّادِقِ", "p", "9", "k", "imam")), null));
        Map<String, Object> step = chain(row).get(0);
        assertEquals("several", step.get("kind"));
        assertEquals(0, row.get("chainNotFound"));
        assertEquals(1, row.get("chainSeveral"));
        assertEquals(0, row.get("chainIdentified"));
        assertEquals(true, step.get("weak"), "the joint is as open as an unidentified one");
        List<Map<String, String>> found = (List<Map<String, String>>) step.get("candidates");
        assertEquals(2, found.size());
        assertEquals("/narrator/n002014/ahmad-b-isa", found.get(0).get("href"));
        assertEquals("/narrator/n003502", found.get(1).get("href"));
        assertEquals("أحمد بن محمد بن عيسى", found.get(0).get("name"));
        assertEquals("القمي", found.get(0).get("title"));
        assertEquals("the book writes this name out, but not here", step.get("refused"));
        assertEquals("", row.get("chainIds").toString().replace("9", ""),
                "an undecided step has no man the preview card could fetch");
    }

    @Test
    void aBlankStepWithNoCandidatesIsStillNotFound() {
        Map<String, Object> row = card(narration(List.of(
                Map.of("t", "فُلَانٌ", "p", "", "k", "name", "o", "none")), null));
        assertEquals("unknown", chain(row).get(0).get("kind"));
        assertEquals(1, row.get("chainNotFound"));
        assertEquals(0, row.get("chainSeveral"));
    }

    // ---- the three renderers must key off the same list ----

    @Test
    void serverCardOmitsButtonAndPanelWhenThereIsNoChain() throws IOException {
        String card = Files.readString(SERVER_CARD);
        for (String marker : List.of("data-hub-panel=\"rijal\"", "data-hub-panel-body=\"rijal\"")) {
            int at = card.indexOf(marker);
            assertTrue(at > 0, marker + " is gone from the server card");
            String tag = card.substring(at, card.indexOf('>', at));
            assertTrue(tag.contains("th:if=\"${!#lists.isEmpty(narration.chain)}\""),
                    marker + " must be rendered only for a narration with a chain");
        }
    }

    @Test
    void searchCardOmitsTheButtonAndPanelWhenThereIsNoChain() throws IOException {
        String card = Files.readString(SEARCH_CARD);
        int button = card.indexOf("data-hub-panel=\"rijal\"");
        assertTrue(button > 0);
        assertTrue(card.substring(button, card.indexOf('>', button)).contains("v-if=\"chainOf(narration).length\""));
        // The panel only exists while its tab is the active one, and the active tab falls
        // back below, so a narration without a chain can never be showing it.
        String script = Files.readString(SEARCH_SCRIPT);
        int active = script.indexOf("activeNarrationSidecarTab: function");
        assertTrue(active > 0);
        String body = script.substring(active, script.indexOf("setNarrationSidecarTab: function", active));
        assertTrue(body.contains("isNarrationSidecarTabDisabled(narration, activeTab)"),
                "a tab that is disabled for this narration must not stay selected");
    }
}
