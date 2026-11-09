package com.rewayaat.integration;

import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.DeleteIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import com.rewayaat.service.NarratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reader looking a narrator up types his name the way he met it, and the index has to meet
 * him there.
 *
 * <p>Nothing tested this. {@code LookupNarratorToolTest} stubs {@code NarratorService.search}
 * and checks only how the answer is phrased, so the matching itself — which man a name brings
 * back, and in which order — had no test at all, and three classes of name silently failed.
 * Measured over 686 lookups whose right answer the index itself gives, by putting back every
 * name a man is filed under: a name copied from a printed chain with its vowel marks returned
 * nothing whatever, 100% of the time; «ابن» written for «بن» returned nothing 57% of the time;
 * and a name that is one of a man's own aliases brought back a different man 19% of the time.
 *
 * <p>So this indexes the men the failures were found on, through the same mapping production
 * builds, and asks for them the four ways a reader writes a name. The men are small but real:
 * Ibn ʿAbdūn, whom al-Ṭūsī and al-Najāshī heard from, is filed under his nasab and carries
 * «أحمد بن عبدون» among his aliases, while an unrelated Aḥmad b. ʿAbdūs — one letter away,
 * and holding more entries — used to take the query off him.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NarratorNameLookupIntegrationTest extends ElasticsearchTestSupport {

    /** The settings and field types production builds the narrator index with. */
    private static final Path PRODUCTION_MAPPING =
            Path.of("scripts/search/narrators_mapping.json");

    /**
     * An index of this test's own, never the name production uses. Where no container is
     * running these tests reach the Elasticsearch on localhost, which on a developer's machine
     * holds the real narrator index; this class drops its index before each test, and under
     * the production name that silently replaced 15,509 narrators with the three below.
     */
    private static final String NARRATORS = "rewayaat_narrators_itest";

    @DynamicPropertySource
    static void useOurOwnIndex(DynamicPropertyRegistry registry) {
        registry.add("narrator.index", () -> NARRATORS);
    }

    /** Ibn ʿAbdūn: filed under his nasab, cited by the kunya his aliases carry. */
    private static final String IBN_ABDUN = "n000233";
    /** Aḥmad b. ʿAbdūs al-Khalanjī: one letter away, and holds more entries. */
    private static final String AL_KHALANJI = "n000219";
    private static final String SAHL = "n000515";

    @Autowired
    private NarratorService narrators;

    @BeforeEach
    void loadNarrators() throws Exception {
        if (client.indices().exists(ExistsRequest.of(b -> b.index(NARRATORS))).value()) {
            client.indices().delete(DeleteIndexRequest.of(b -> b.index(NARRATORS)));
        }
        String mapping = Files.readString(PRODUCTION_MAPPING, StandardCharsets.UTF_8);
        client.indices().create(CreateIndexRequest.of(b -> b
                .index(NARRATORS)
                .withJson(new StringReader(mapping))));

        index(IBN_ABDUN, "أحمد بن عبد الواحد بن أحمد البزاز", 6,
                List.of("أحمد بن عبدون", "أحمد بن عبدون أبو عبد الله", "ابن عبدون"));
        index(AL_KHALANJI, "أحمد بن عبدوس", 8, List.of("أحمد بن عبدوس الخلنجي"));
        index(SAHL, "سهل بن زياد", 12, List.of("سهل بن زياد الآدمي", "أبو سعيد الآدمي"));
    }

    private void index(String personId, String displayAr, int entries, List<String> alsoNamed)
            throws Exception {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("person_id", personId);
        doc.put("display_ar", displayAr);
        doc.put("names_ar", new ArrayList<>(alsoNamed));
        doc.put("entry_count", entries);
        doc.put("holds_entry", true);
        doc.put("kind", "narrator");
        client.index(IndexRequest.of(b -> b
                .index(NARRATORS)
                .id(personId)
                .document(doc)
                .refresh(Refresh.True)));
    }

    /** The ids the search answers with, best first. */
    private List<String> found(String name) {
        Map<String, Object> result = narrators.search(name, 1, 5);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("narrators");
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            ids.add(String.valueOf(row.get("person_id")));
        }
        return ids;
    }

    @Test
    @DisplayName("a name a man is filed under brings back that man, not the one spelt alike")
    void anAliasOutranksAManSpeltAlike() {
        List<String> ids = found("أحمد بن عبدون");
        assertFalse(ids.isEmpty(), "«أحمد بن عبدون» found nobody");
        assertEquals(IBN_ABDUN, ids.get(0),
                "the man whose own aliases carry this name must come first, ahead of "
                        + AL_KHALANJI + ", who is one letter away and holds more entries");
        assertTrue(ids.contains(AL_KHALANJI), "the man spelt alike is still worth offering");
    }

    @Test
    @DisplayName("a name carrying the vowels a printed chain gives it still finds the man")
    void aVowelledNameFindsHim() {
        assertEquals(List.of(IBN_ABDUN).get(0),
                first(found("أَحْمَدَ بْنِ عَبْدِ الْوَاحِدِ")),
                "a name copied from a printed chain must reach the man it names");
    }

    @Test
    @DisplayName("«ابن» written for «بن» still finds the man")
    void ibnWrittenForBnFindsHim() {
        assertEquals(SAHL, first(found("سهل ابن زياد")),
                "the books write both, so a reader may write either");
    }

    @Test
    @DisplayName("a name written without its hamzas still finds the man")
    void aNameWithoutHamzasFindsHim() {
        assertEquals(IBN_ABDUN, first(found("احمد بن عبد الواحد")),
                "a reader who leaves the hamzas off is naming the same man");
    }

    @Test
    @DisplayName("a man is found by a kunya his entry records")
    void aKunyaFindsHim() {
        assertTrue(found("أبو سعيد الآدمي").contains(SAHL),
                "Sahl b. Ziyad is cited by this kunya as often as by his name");
    }

    private void indexDoc(Map<String, Object> doc) throws Exception {
        doc.putIfAbsent("holds_entry", true);
        doc.putIfAbsent("kind", "narrator");
        client.index(IndexRequest.of(b -> b
                .index(NARRATORS)
                .id(String.valueOf(doc.get("person_id")))
                .document(doc)
                .refresh(Refresh.True)));
    }

    private static Map<String, Object> man(String id, String ar, String en, int entries,
                                           int chains, List<String> namesAr,
                                           List<String> namesEn) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("person_id", id);
        doc.put("display_ar", ar);
        doc.put("display_en", en);
        doc.put("names_ar", new ArrayList<>(namesAr));
        doc.put("names_en", new ArrayList<>(namesEn));
        doc.put("entry_count", entries);
        doc.put("chain_count", chains);
        return doc;
    }

    @Test
    @DisplayName("«عبدالله» joined and a name without its «ال» still find the man")
    void readersSpellingsOfArabicFindHim() throws Exception {
        indexDoc(man("n000492", "سعد بن عبد الله بن أبي خلف", "Sa'd ibn 'Abd Allah", 6, 1463,
                List.of("سعد بن عبد الله"), List.of()));
        indexDoc(man("n067140", "الحسين بن سعيد", "al-Husayn ibn Sa'id", 8, 815,
                List.of("الحسين بن سعيد الأهوازي"), List.of()));
        assertEquals("n000492", first(found("سعد بن عبدالله")));
        assertEquals("n067140", first(found("حسين بن سعيد")));
    }

    @Test
    @DisplayName("English spelt by its consonants: Himyari for Hamiri, b. for ibn, Saeed for Sa'id")
    void transliterationsThatDifferInVowelsFindHim() throws Exception {
        indexDoc(man("n000597", "عبد الله بن جعفر الحميري", "'Abd Allah ibn Ja'far al-Hamiri", 13,
                432, List.of(), List.of("Abdullah ibn Ja'far al-Hamiri")));
        indexDoc(man("n000907", "محمد بن مسلم بن رباح", "Muhammad ibn Muslim ibn Rabah", 9, 1014,
                List.of(), List.of("Muhammad ibn Muslim")));
        assertEquals("n000597", first(found("al-Himyari")));
        assertEquals("n000907", first(found("Muhammad b. Muslim")));
    }

    @Test
    @DisplayName("a man whose own name starts with the query outranks a son who carries it")
    void theManHimselfOutranksHisSons() throws Exception {
        indexDoc(man("n000488", "زرارة بن أعين", "Zurarah ibn A'yan", 8, 1000,
                List.of("زرارة"), List.of("Zurara")));
        indexDoc(man("n002413", "الحسين بن زرارة", "Al-Husayn ibn Zurara", 5, 4,
                List.of(), List.of()));
        indexDoc(man("n011175", "عمير بن زرارة", "Umayr b. Zurara", 1, 0, List.of(), List.of()));
        assertEquals("n000488", first(found("Zurara")));
        assertEquals("n000488", first(found("زرارة")));
    }

    @Test
    @DisplayName("among men of one name, the one the chains name most comes first")
    void chainsDecideBetweenNamesakes() throws Exception {
        indexDoc(man("n034836", "محمد بن يحيى أبو جعفر العطار القمي", "Muhammad ibn Yahya al-Attar",
                6, 4204, List.of("محمد بن يحيى"), List.of()));
        indexDoc(man("n000989", "محمد بن يحيى بن سلمان الخثعمي", "Muhammad ibn Yahya al-Khath'ami",
                6, 22, List.of("محمد بن يحيى"), List.of()));
        assertEquals("n034836", first(found("محمد بن يحيى")));
    }

    @Test
    @DisplayName("a later scholar keeps his page but is not offered as a narrator")
    void aLaterScholarIsNotANarrator() throws Exception {
        Map<String, Object> scholar = man("n037778", "عبد الجبار بن الحسين", "Abd al-Jabbar ibn al-Husayn",
                1, 0, List.of(), List.of());
        scholar.put("kind", "scholar");
        indexDoc(scholar);
        assertTrue(found("عبد الجبار بن الحسين").isEmpty(),
                "a scholar of the late lists stands in no chain and is not a narrator");
    }

    private String first(List<String> ids) {
        assertFalse(ids.isEmpty(), "the search found nobody");
        return ids.get(0);
    }
}
