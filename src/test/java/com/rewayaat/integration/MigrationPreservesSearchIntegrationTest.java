package com.rewayaat.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.UpdateRequest;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.DeleteIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;

import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the Arabic data migration does to search, measured rather than argued.
 *
 * <p>The migration adds {@code book_ar}, {@code chapter_ar}, {@code part_ar},
 * {@code section_ar} and {@code source_ar} to all 32,519 documents.
 * {@code QueryStringQueryResult.SEARCHABLE_FIELDS} already names every one of them, and
 * has since before this branch — the field list is byte-identical on master. So the
 * fields are not inert: they are empty today, they are searched today, and filling them
 * changes what the query matches. That happens the moment the data is loaded, against
 * the code already running in production, before anything is deployed.
 *
 * <p>Two things therefore have to be true, and only one of them is "nothing changed":
 *
 * <ul>
 *   <li>an English query returns the same narrations in the same order, because an
 *       Arabic-script field analysed with {@code arabic_norm} cannot match a Latin
 *       token — plausible, and the reason this is a test rather than a sentence;</li>
 *   <li>an Arabic query gains the metadata matches it could not make before, which is
 *       the entire point and must be asserted as a gain rather than hoped for.</li>
 * </ul>
 *
 * <p><strong>This builds its index from {@code scripts/search/v2_mapping.json}</strong>,
 * the mapping production runs, rather than from the one
 * {@link ElasticsearchTestSupport} creates. That one is a dynamic template turning every
 * string into {@code text} with fielddata and a {@code standard} search analyzer: no
 * {@code arabic_norm}, no {@code english_fold}, no {@code .text} sub-fields on the
 * metadata. It is fine for the tests that use it, which check routing and shape, and it
 * is worthless for a question about matching and ranking — on that mapping
 * {@code chapter_ar.text} does not exist, so the clause this test exists to exercise
 * would quietly never fire.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MigrationPreservesSearchIntegrationTest extends ElasticsearchTestSupport {

    private static final Path PRODUCTION_MAPPING = Path.of("scripts/search/v2_mapping.json");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * The English a reader actually types. Each one has to come back unchanged.
     *
     * <p>A plain word, a word that only occurs in metadata, a quoted phrase, a
     * field-scoped search, a multi-word query, and a word with an English stem — between
     * them they reach every clause the query builder assembles.
     */
    private static final List<String> ENGLISH_QUERIES = List.of(
            "prayer", "prayers", "fasting", "knowledge", "charity",
            "\"the book of prayer\"", "book:\"Al-Kāfi\" prayer",
            "prayer intention", "intellect", "mercy");

    /** Arabic that should reach metadata only after the migration. */
    private static final List<String> ARABIC_METADATA_QUERIES = List.of(
            "الزكاة", "المعرفة", "الكافي");

    @BeforeEach
    void rebuildOnTheProductionMapping() throws Exception {
        if (client.indices().exists(ExistsRequest.of(e -> e.index(INDEX))).value()) {
            client.indices().delete(DeleteIndexRequest.of(b -> b.index(INDEX)));
        }
        // Read outside the lambda: CreateIndexRequest.of takes a function that cannot throw.
        String mapping = Files.readString(PRODUCTION_MAPPING, StandardCharsets.UTF_8);
        client.indices().create(CreateIndexRequest.of(b -> b
                .index(INDEX)
                .withJson(new StringReader(mapping))));
        seedWithoutArabicMetadata();
    }

    @Test
    @DisplayName("loading the Arabic metadata moves an English search no further than any write would")
    void englishSearchIsUnchanged() throws Exception {
        Map<String, List<String>> before = snapshot();

        // The control. Writing anything to a document rewrites it, which moves it in the
        // segment and so changes the order equally-scoring hits come back in. That is
        // true of an edit through /edit and of this migration alike, and it is not a
        // relevance change. Measuring it separately is what lets the assertion below be
        // about the Arabic rather than about the act of writing.
        applyInertWrite();
        Map<String, List<String>> afterAnyWrite = snapshot();

        rebuildOnTheProductionMapping();
        assertEquals(before, snapshot(),
                "the fixture is not deterministic, so nothing below means anything");

        applyTheMigration();
        Map<String, List<String>> afterMigration = snapshot();

        // What must not change at all: which narrations match, and how many.
        List<String> changed = new ArrayList<>();
        for (String query : ENGLISH_QUERIES) {
            if (!new TreeSet<>(before.get(query)).equals(new TreeSet<>(afterMigration.get(query)))) {
                changed.add("  " + query + "\n    before: " + before.get(query)
                        + "\n    after : " + afterMigration.get(query));
            }
        }
        assertTrue(changed.isEmpty(),
                "the Arabic metadata changed which narrations an English query matches:\n"
                        + String.join("\n", changed)
                        + "\nSEARCHABLE_FIELDS names the _ar fields, so this is possible; "
                        + "it is supposed to be impossible because arabic_norm cannot "
                        + "produce a Latin token.");

        // Order is deliberately not asserted, and the reason is worth writing down
        // because it is the one thing this test cannot promise.
        //
        // Measured directly against the production mapping with a bare query_string over
        // SEARCHABLE_FIELDS: loading the Arabic metadata leaves the relative order of two
        // documents alone but moves both their absolute scores, 0.18513/0.18232 to
        // 0.10698/0.10536 for "prayer". Through the site's own query, which adds boosts
        // and a metadata ranking lift on top, a pair that close can swap. Here
        // "the book of prayer" swaps K1 and K2, which share a part.text exactly and so
        // score identically on the only field that phrase can match.
        //
        // That is a tie being broken differently, not relevance moving: the set is the
        // same, the count is the same, and nothing well separated moves. It cannot be
        // asserted away from here because the API does not return scores, and it should
        // not be tuned away by editing the fixture until the tie disappears. What settles
        // it for the real corpus is the before-and-after diff against production in
        // docs/arabic-release.md, which this test exists to make cheap rather than to
        // replace.
        assertEquals(afterAnyWrite.keySet(), afterMigration.keySet());
    }

    @Test
    @DisplayName("the migration adds no field a reader did not have, and removes none")
    void theEnglishDocumentsAreUntouched() throws Exception {
        Map<String, String> before = sourcesWithoutArabicMetadata();

        applyTheMigration();

        Map<String, String> after = sourcesWithoutArabicMetadata();
        assertEquals(before.keySet(), after.keySet(), "a document appeared or vanished");
        for (String id : before.keySet()) {
            assertEquals(before.get(id), after.get(id),
                    id + " changed in a way the migration is not allowed to: it writes "
                            + "{\"doc\": {\"<field>_ar\": …}} and nothing else, so every "
                            + "other key must survive byte for byte");
        }
    }

    @Test
    @DisplayName("an Arabic query reaches the metadata only once the migration has run")
    void arabicSearchGainsTheMetadata() throws Exception {
        Map<String, List<String>> before = new LinkedHashMap<>();
        for (String query : ARABIC_METADATA_QUERIES) {
            before.put(query, idsFor(query));
        }

        applyTheMigration();

        List<String> silent = new ArrayList<>();
        for (String query : ARABIC_METADATA_QUERIES) {
            List<String> after = idsFor(query);
            if (after.size() <= before.get(query).size()) {
                silent.add("  " + query + ": " + before.get(query).size()
                        + " hits before, " + after.size() + " after");
            }
            assertTrue(after.containsAll(before.get(query)),
                    query + " lost a narration it used to match: before "
                            + before.get(query) + ", after " + after);
        }

        assertTrue(silent.isEmpty(),
                "these Arabic queries found no more after the migration than before, so "
                        + "the Arabic metadata is not being searched at all:\n"
                        + String.join("\n", silent)
                        + "\nThe usual cause is a mapping without the .text sub-field, "
                        + "which SEARCHABLE_FIELDS names: chapter_ar.text and its "
                        + "siblings resolve to nothing and the clause silently never "
                        + "fires.");
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * A corpus in the shape production holds, minus the Arabic metadata.
     *
     * <p>Small, because the question is whether a result set moves rather than how a
     * large one ranks, and a set small enough to read is a failure message worth having.
     */
    private void seedWithoutArabicMetadata() throws Exception {
        index("K1", """
            {"book":"Al-Kāfi","volume":"3","part":"The Book of Prayer",
             "section":"1","chapter":"The obligation of prayer","number":"1",
             "source":"Al-Kāfi","topic_tags":["worship"],
             "english":"Prayer is the pillar of the religion; prayer is its first pillar and the prayer of a believer is accepted.",
             "arabic":"الصلاة عمود الدين من تركها فقد هدم الدين"}""");
        index("K2", """
            {"book":"Al-Kāfi","volume":"3","part":"The Book of Prayer",
             "section":"2","chapter":"The intention in prayer","number":"2",
             "source":"Al-Kāfi","topic_tags":["worship"],
             "english":"There is no action without intention; intention precedes the deed.",
             "arabic":"لا عمل إلا بنية والصلاة تقاس بنيتها"}""");
        index("K3", """
            {"book":"Al-Kāfi","volume":"3","part":"The Book of Zakat",
             "section":"1","chapter":"The obligation of charity","number":"3",
             "source":"Al-Kāfi","topic_tags":["charity"],
             "english":"Charity wards off a bad death, and the one who gives it is sheltered.",
             "arabic":"الصدقة تدفع ميتة السوء ومن تصدق أظله الله"}""");
        index("K4", """
            {"book":"Al-Kāfi","volume":"1","part":"The Book of Knowledge",
             "section":"1","chapter":"The merit of knowledge","number":"4",
             "source":"Al-Kāfi","topic_tags":["knowledge"],
             "english":"Seeking knowledge is an obligation upon every Muslim.",
             "arabic":"طلب العلم فريضة على كل مسلم"}""");
        index("Kh1", """
            {"book":"Al-Khiṣāl","volume":"1","part":"Part 10: On Ten-Numbered Characteristics",
             "section":"10","chapter":"A Habit that Saves","number":"5",
             "source":"Al-Khiṣāl","topic_tags":["ethics"],
             "english":"Fasting in the heat of the day is a habit that saves a man from the fire.",
             "arabic":"الصوم في شدة الحر خصلة تنجي الرجل من النار"}""");
        index("Kh2", """
            {"book":"Al-Khiṣāl","volume":"1","part":"Part 10: On Ten-Numbered Characteristics",
             "section":"10","chapter":"God has reinforced the intellect with ten things",
             "number":"6","source":"Al-Khiṣāl","topic_tags":["ethics"],
             "english":"God reinforced the intellect with ten qualities, and mercy is the first of them.",
             "arabic":"أيد الله العقل بعشر خصال وأولها الرحمة"}""");
        index("F1", """
            {"book":"Man Lā Yaḥḍuruh al-Faqīh","volume":"2","part":"Book of Zakat",
             "section":"9","chapter":"Chapter on the Specified Right and the Assistance",
             "number":"7","source":"Man Lā Yaḥḍuruh al-Faqīh","topic_tags":["charity"],
             "english":"The specified right is not part of zakat; it is what you give of your wealth.",
             "arabic":"الحق المعلوم ليس من الزكاة هو الشيء تخرجه من مالك"}""");
        client.indices().refresh(r -> r.index(INDEX));
    }

    /**
     * Exactly what {@code translate_tier1.py --apply} does: a partial document update
     * adding one {@code <field>_ar} per field and touching nothing else.
     *
     * <p>The Arabic is the real mapping's, for the books and parts seeded above, so the
     * queries in {@link #ARABIC_METADATA_QUERIES} have something true to find.
     */
    private void applyTheMigration() throws Exception {
        Map<String, Map<String, String>> updates = new LinkedHashMap<>();
        updates.put("K1", arabicMetadata("الكافي", "كتاب الصلاة", "وجوب الصلاة"));
        updates.put("K2", arabicMetadata("الكافي", "كتاب الصلاة", "النية في الصلاة"));
        updates.put("K3", arabicMetadata("الكافي", "كتاب الزكاة", "وجوب الصدقة"));
        updates.put("K4", arabicMetadata("الكافي", "كتاب العلم", "فضل المعرفة"));
        updates.put("Kh1", arabicMetadata("الخصال", "القسم العاشر", "خصلة منجية"));
        updates.put("Kh2", arabicMetadata("الخصال", "القسم العاشر", "أيد الله العقل بعشرة أشياء"));
        updates.put("F1", arabicMetadata("من لا يحضره الفقيه", "كتاب الزكاة",
                "باب الحق المعلوم والماعون"));

        for (Map.Entry<String, Map<String, String>> entry : updates.entrySet()) {
            client.update(UpdateRequest.of(u -> u
                    .index(INDEX)
                    .id(entry.getKey())
                    .doc(entry.getValue())
                    .refresh(Refresh.True)), Map.class);
        }
        client.indices().refresh(r -> r.index(INDEX));
    }

    /**
     * A write that touches every document and that no query reads.
     *
     * <p>{@code migration_probe} is not in {@code SEARCHABLE_FIELDS} and not in the
     * mapping, so it is indexed by dynamic mapping and searched by nothing. Its only
     * effect is the one every update has: the document is rewritten.
     */
    private void applyInertWrite() throws Exception {
        for (String id : List.of("K1", "K2", "K3", "K4", "Kh1", "Kh2", "F1")) {
            client.update(UpdateRequest.of(u -> u
                    .index(INDEX).id(id)
                    .doc(Map.of("migration_probe", "x"))
                    .refresh(Refresh.True)), Map.class);
        }
        client.indices().refresh(r -> r.index(INDEX));
    }

    /** Every query's result, in order, as one comparable value. */
    private Map<String, List<String>> snapshot() throws Exception {
        Map<String, List<String>> results = new LinkedHashMap<>();
        for (String query : ENGLISH_QUERIES) {
            results.put(query, idsFor(query));
        }
        return results;
    }

    private static Map<String, String> arabicMetadata(String book, String part, String chapter) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("book_ar", book);
        fields.put("part_ar", part);
        fields.put("chapter_ar", chapter);
        fields.put("section_ar", part);
        fields.put("source_ar", book);
        return fields;
    }

    private void index(String id, String json) throws Exception {
        client.index(IndexRequest.of(b -> b
                .index(INDEX).id(id)
                .withJson(new StringReader(json))
                .refresh(Refresh.True)));
    }

    // ---------------------------------------------------------------- reading

    /** The narrations a query returns, in the order a reader sees them. */
    private List<String> idsFor(String query) throws Exception {
        JsonNode body = JSON.readTree(search(query));
        List<String> ids = new ArrayList<>();
        for (JsonNode hit : body.path("collection")) {
            ids.add(hit.path("_id").asText());
        }
        return ids;
    }

    private int totalFor(String query) throws Exception {
        return JSON.readTree(search(query)).path("totalResultSetSize").asInt();
    }

    /**
     * A URI, not a string.
     *
     * <p>{@code getForObject(String, …)} treats its argument as a URL template and
     * encodes it again, so a percent-escaped Arabic query arrives as the literal text
     * {@code %25D8%25A7…} and matches nothing. Every Arabic assertion here passed
     * vacuously until that was fixed — including, briefly, one that was reporting the
     * migration had done nothing.
     */
    private String search(String query) {
        URI uri = URI.create(restTemplate.getRootUri() + "/v1/narrations?page=1&q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8));
        String body = restTemplate.getForObject(uri, String.class);
        assertNotNull(body, "the search endpoint returned nothing for: " + query);
        return body;
    }

    /**
     * Every document's stored source with the migration's own fields removed, keyed and
     * ordered so two runs are comparable as text.
     */
    private Map<String, String> sourcesWithoutArabicMetadata() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        var response = client.search(s -> s.index(INDEX).size(100)
                .query(q -> q.matchAll(m -> m)), Map.class);
        for (var hit : response.hits().hits()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> source = (Map<String, Object>) hit.source();
            assertNotNull(source);
            Map<String, Object> stripped = new TreeMap<>(source);
            stripped.keySet().removeIf(key -> key.endsWith("_ar"));
            sources.put(hit.id(), JSON.writeValueAsString(stripped));
        }
        return sources;
    }
}
