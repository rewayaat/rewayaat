package com.rewayaat.mcp;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.DeleteIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.config.ESClientProvider;
import com.rewayaat.integration.TestElasticsearch;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MCP server over its real transport, speaking real JSON-RPC.
 *
 * <p>Most of what is asserted here is not our design but somebody else's requirement, and
 * that is the point of testing it. ChatGPT will only call two tools and only if they are
 * named, shaped and annotated its way; Claude reads {@code structuredContent} where ChatGPT
 * reads a JSON string out of {@code content}. Nothing in the codebase would otherwise stop a
 * well-meaning rename or a "redundant" field removal from silently uninstalling us from one
 * of the two clients we are building this for.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "quranic.insights.index=" + McpProtocolIntegrationTest.INSIGHTS_INDEX,
                "quran.index=" + McpProtocolIntegrationTest.QURAN_INDEX,
                "narrator.index=" + McpProtocolIntegrationTest.NARRATOR_INDEX
        })
// The two index properties above give this class a different @SpringBootTest signature from
// the rest of the suite, so Spring builds it a second application context instead of sharing
// the cached one. That matters because ESClientProvider.INDEX is a mutable static written by
// a @PostConstruct: with two contexts alive, whichever initialised last owns the index name,
// and the suites that follow read or write the wrong index. Without this annotation the
// sitemap and REST integration tests fail with "index [rewayaat] already exists" and stray
// documents. Dirtying the context closes this one and has the shared context rebuilt.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class McpProtocolIntegrationTest {

    /**
     * Point the application at the shared container too.
     *
     * <p>The client below is the test's own; this is what the Spring context under test
     * connects with. Without it the test talks to the container and the application
     * talks to localhost, which on CI is nothing at all.
     */
    @DynamicPropertySource
    static void elasticsearch(DynamicPropertyRegistry registry) {
        registry.add("elasticsearch.host", TestElasticsearch::host);
        registry.add("elasticsearch.port", TestElasticsearch::port);
        registry.add("ELASTIC_HOST", TestElasticsearch::host);
        registry.add("ELASTIC_PORT", () -> String.valueOf(TestElasticsearch.port()));
    }

    private static final String INDEX = "rewayaat_mcp_test";
    static final String INSIGHTS_INDEX = "rewayaat_mcp_test_insights";
    static final String QURAN_INDEX = "rewayaat_mcp_test_quran";
    static final String NARRATOR_INDEX = "rewayaat_mcp_test_narrators";
    private static final String MCP = "/mcp";

    @Autowired
    private TestRestTemplate restTemplate;

    private final ObjectMapper mapper = new ObjectMapper();
    private ElasticsearchClient client;
    private Rest5ClientTransport transport;
    private Rest5Client restClient;
    private String sessionId;
    private int requestId;

    /**
     * The MCP tools filter and sort on {@code book}, {@code chapter.keyword} and
     * {@code number}, so the mapping has to be explicit - a dynamic mapping would make
     * {@code book} a text field and every term filter in this suite would silently match
     * nothing.
     */
    private static final String MAPPING = """
            {"mappings":{"properties":{
              "book":{"type":"keyword"},
              "volume":{"type":"keyword"},
              "number":{"type":"keyword"},
              "section":{"type":"keyword"},
              "part":{"type":"keyword"},
              "topic_tags":{"type":"keyword"},
              "chapter":{"type":"text","fields":{"keyword":{"type":"keyword"}}},
              "english":{"type":"text"},
              "arabic":{"type":"text"},
              "notes":{"type":"text"},
              "history":{"type":"text"},
              "semantic_matn_source":{"type":"text"},
              "llm_similar":{"type":"nested","properties":{
                "id":{"type":"keyword"},"match_type":{"type":"keyword"},
                "reason":{"type":"text","index":false}}}
            }}}""";

    @BeforeEach
    void seed() throws Exception {
        System.setProperty("REWAYAAT_INDEX", INDEX);
        ESClientProvider.resetIndex();
        // The shared container, not a hardcoded localhost. This test was excluded from
        // CI for as long as CI had no Elasticsearch; the moment it stopped being
        // excluded it failed thirty-two times on connection refused.
        restClient = Rest5Client.builder(new HttpHost(
                "http", TestElasticsearch.host(), TestElasticsearch.port())).build();
        transport = new Rest5ClientTransport(restClient, new JacksonJsonpMapper());
        client = new ElasticsearchClient(transport);

        if (client.indices().exists(ExistsRequest.of(b -> b.index(INDEX))).value()) {
            client.indices().delete(DeleteIndexRequest.of(b -> b.index(INDEX)));
        }
        client.indices().create(CreateIndexRequest.of(b -> b.index(INDEX)
                .withJson(new StringReader(MAPPING))));

        // A three-narration chapter, so an exhaustive read has a known answer, plus one
        // narration outside it to prove the chapter filter actually bounds the result.
        index("Test-Book:1", """
                {"book":"Test Book","volume":"1","number":"1","chapter":"Chapter of Weeping",
                 "english":"The heavens wept for him.","arabic":"بكت السماء عليه",
                 "topic_tags":["weeping"],
                 "gradings":[{"grading":"صحيح","grader":"Majlisi"}],
                 "llm_similar":[{"id":"Test-Book:2","match_type":"wording",
                                 "reason":"Near-identical wording about weeping."}],
                 "englishContent":"duplicate","arabicChain":"chain",
                 "semantic_matn_source":"retrieval input"}""");
        index("Test-Book:2", """
                {"book":"Test Book","volume":"1","number":"2","chapter":"Chapter of Weeping",
                 "english":"The earth wept for him.","arabic":"بكت الأرض عليه",
                 "topic_tags":["weeping"]}""");
        index("Test-Book:3", """
                {"book":"Test Book","volume":"1","number":"3","chapter":"Chapter of Weeping",
                 "english":"The angels wept for him.","arabic":"بكت الملائكة عليه",
                 "topic_tags":["weeping"]}""");
        index("Test-Book:4", """
                {"book":"Test Book","volume":"1","number":"4","chapter":"Chapter of Fasting",
                 "english":"Fasting is a shield.","arabic":"الصوم جنة",
                 "topic_tags":["fasting"]}""");

        // The isnād pair. Yahya is the *subject* of :5 and merely a *transmitter* of :6.
        // Unweighted, a chain wins on term frequency alone - it is nothing but names.
        index("Test-Book:5", """
                {"book":"Test Book","volume":"1","number":"5","chapter":"Chapter of Prophets",
                 "english":"Yahya ibn Zakariyya was given wisdom as a child.",
                 "arabic":"يحيى بن زكريا أوتي الحكم صبيا",
                 "semantic_matn_source":"يحيى بن زكريا أوتي الحكم صبيا",
                 "topic_tags":["prophets"]}""");
        index("Test-Book:6", """
                {"book":"Test Book","volume":"1","number":"6","chapter":"Chapter of Prayer",
                 "english":"Muhammad ibn Yahya from Ahmad ibn Yahya from Yahya ibn Zakariyya al-Qattan said: prayer is a pillar.",
                 "arabic":"محمد بن يحيى عن أحمد بن يحيى عن يحيى بن زكريا القطان قال الصلاة عمود",
                 "semantic_matn_source":"الصلاة عمود",
                 "topic_tags":["prayer"]}""");

        // zzquux sits in `history`, outside the searched field set; zzcorpus sits in
        // `english`, inside it. The pair is what makes the field set falsifiable.
        index("Test-Book:7", """
                {"book":"Test Book","volume":"1","number":"7","chapter":"Chapter of Records",
                 "english":"A narration carrying the marker zzcorpus.","arabic":"رواية",
                 "history":"revised zzquux","topic_tags":["records"]}""");
        seedVerseIndices();
        seedChains();
        client.indices().refresh(r -> r.index(INDEX));
        client.indices().refresh(r -> r.index(NARRATOR_INDEX));

        sessionId = null;
        requestId = 0;
        initialize();
    }

    /**
     * Real narrations with their resolved chains, and the narrators those chains name.
     *
     * <p>Both files were copied out of the live index: {@code real-chains.json} holds the
     * chain fields of nine narrations exactly as Elasticsearch returned them, and
     * {@code real-narrators.json} the name fields of the men they resolve to. They are
     * indexed under their real ids, with the chain fields mapped dynamically as in
     * production - which gives {@code chain_people.p} the {@code .keyword} subfield the
     * narrator-to-narrations query depends on. Only {@code english} is replaced, by a fixed
     * marker, so the real wording cannot change what an unrelated search in this class
     * counts; the Arabic is kept because the chain's offsets refer to it.
     */
    private void seedChains() throws Exception {
        if (client.indices().exists(ExistsRequest.of(b -> b.index(NARRATOR_INDEX))).value()) {
            client.indices().delete(DeleteIndexRequest.of(b -> b.index(NARRATOR_INDEX)));
        }
        client.indices().create(CreateIndexRequest.of(b -> b.index(NARRATOR_INDEX)));

        Map<String, Map<String, Object>> narrations = readFixture("/mcp/real-chains.json");
        int n = 0;
        for (Map.Entry<String, Map<String, Object>> entry : narrations.entrySet()) {
            Map<String, Object> source = new java.util.LinkedHashMap<>(entry.getValue());
            source.put("english", "Chain fixture narration " + (++n) + ".");
            index(entry.getKey(), mapper.writeValueAsString(source));
        }
        for (Map.Entry<String, Map<String, Object>> entry
                : readFixture("/mcp/real-narrators.json").entrySet()) {
            index(NARRATOR_INDEX, entry.getKey(), mapper.writeValueAsString(entry.getValue()));
        }
        // A man who exists but stands in no chain here, so "linked to no narration" is a real
        // case. Invented, and the only invented record in the fixtures.
        index(NARRATOR_INDEX, "n000999", """
                {"person_id":"n000999","display_ar":"رجل لا يظهر","display_en":"A Man In No Chain",
                 "slug":"a-man-in-no-chain"}""");
    }

    private Map<String, Map<String, Object>> readFixture(String resource) throws Exception {
        try (java.io.InputStream in = getClass().getResourceAsStream(resource)) {
            assertNotNull(in, "missing test resource " + resource);
            return mapper.readValue(in, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        }
    }

    /**
     * The two indices hadith_for_verse joins across: the verse text, and the pre-computed
     * connections keyed by {@code top_verse_keys}. Both need explicit mappings for the same
     * reason the narration index does - a dynamic mapping makes the verse key a text field
     * and the term filter matches nothing.
     */
    private void seedVerseIndices() throws Exception {
        for (String index : List.of(INSIGHTS_INDEX, QURAN_INDEX)) {
            if (client.indices().exists(ExistsRequest.of(b -> b.index(index))).value()) {
                client.indices().delete(DeleteIndexRequest.of(b -> b.index(index)));
            }
        }
        client.indices().create(CreateIndexRequest.of(b -> b.index(QURAN_INDEX)
                .withJson(new StringReader("""
                        {"mappings":{"properties":{
                          "text_arabic":{"type":"text"},"text_english":{"type":"text"}}}}"""))));
        client.indices().create(CreateIndexRequest.of(b -> b.index(INSIGHTS_INDEX)
                .withJson(new StringReader("""
                        {"mappings":{"properties":{
                          "hadith_id":{"type":"keyword"},
                          "top_verse_keys":{"type":"keyword"}}}}"""))));

        index(QURAN_INDEX, "2:255", """
                {"text_arabic":"اللَّهُ لَا إِلَٰهَ إِلَّا هُوَ الْحَيُّ الْقَيُّومُ",
                 "text_english":"Allah - there is no deity except Him, the Ever-Living."}""");
        index(QURAN_INDEX, "19:12", """
                {"text_arabic":"يَا يَحْيَىٰ خُذِ الْكِتَابَ بِقُوَّةٍ",
                 "text_english":"O Yahya, take the Scripture with determination."}""");

        // 2:255 has two connected narrations, 19:12 has none - so an empty result and a
        // populated one are both exercised, and a verse that exists is distinguishable from
        // one that does not.
        index(INSIGHTS_INDEX, "Test-Book:1", """
                {"hadith_id":"Test-Book:1","top_verse_keys":["2:255"]}""");
        index(INSIGHTS_INDEX, "Test-Book:4", """
                {"hadith_id":"Test-Book:4","top_verse_keys":["2:255","3:1"]}""");
        index(INSIGHTS_INDEX, "Test-Book:6", """
                {"hadith_id":"Test-Book:6","top_verse_keys":[]}""");

        client.indices().refresh(r -> r.index(INSIGHTS_INDEX));
        client.indices().refresh(r -> r.index(QURAN_INDEX));
    }

    @AfterEach
    void cleanup() throws Exception {
        for (String index : List.of(INDEX, INSIGHTS_INDEX, QURAN_INDEX, NARRATOR_INDEX)) {
            if (client != null
                    && client.indices().exists(ExistsRequest.of(b -> b.index(index))).value()) {
                client.indices().delete(DeleteIndexRequest.of(b -> b.index(index)));
            }
        }
        if (transport != null) {
            transport.close();
        }
        if (restClient != null) {
            restClient.close();
        }
        System.clearProperty("REWAYAAT_INDEX");
        ESClientProvider.resetIndex();
    }

    // ---- the ChatGPT contract ----

    @Test
    void exposesSearchAndFetchUnderExactlyThoseNames() throws Exception {
        List<Map<String, Object>> tools = listTools();
        List<String> names = tools.stream().map(t -> (String) t.get("name")).toList();

        assertTrue(names.contains("search"),
                "ChatGPT's knowledge paths call a tool named exactly 'search'; renaming it "
                        + "uninstalls us there.");
        assertTrue(names.contains("fetch"),
                "ChatGPT's knowledge paths call a tool named exactly 'fetch'.");
    }

    @Test
    void searchAndFetchTakeTheSingleStringArgumentChatGptSends() throws Exception {
        Map<String, Object> search = tool("search");
        assertEquals(List.of("query"), required(search),
                "ChatGPT sends one string named 'query'.");
        Map<String, Object> fetch = tool("fetch");
        assertEquals(List.of("id"), required(fetch),
                "ChatGPT sends one string named 'id'.");
    }

    @Test
    void everyToolIsAnnotatedReadOnly() throws Exception {
        for (Map<String, Object> tool : listTools()) {
            Map<?, ?> annotations = (Map<?, ?>) tool.get("annotations");
            assertNotNull(annotations, tool.get("name") + " has no annotations.");
            assertEquals(Boolean.TRUE, annotations.get("readOnlyHint"),
                    tool.get("name") + " must be readOnlyHint:true - ChatGPT requires it to "
                            + "treat a tool as a knowledge source.");
        }
    }

    @Test
    void resultsCarryTheSameObjectAsStructuredContentAndAsJsonText() throws Exception {
        Map<String, Object> result = callRaw("search", Map.of("query", "wept"));

        Object structured = result.get("structuredContent");
        assertNotNull(structured, "Newer clients read structuredContent.");

        List<?> content = (List<?>) result.get("content");
        assertNotNull(content);
        assertFalse(content.isEmpty(), "ChatGPT reads the JSON string out of content.");
        Map<?, ?> first = (Map<?, ?>) content.get(0);
        assertEquals("text", first.get("type"));

        assertEquals(1, content.size(),
                "Exactly one text item. The SDK also derives content from structuredContent "
                        + "when it is empty, so a second encoding here would double it.");

        Map<?, ?> reparsed = mapper.readValue((String) first.get("text"), Map.class);
        assertEquals(structured, reparsed,
                "The two representations must carry the same object - clients read one or "
                        + "the other, never both.");
    }

    @Test
    void searchReturnsTheIdTitleUrlTripleChatGptExpects() throws Exception {
        Map<String, Object> out = call("search", Map.of("query", "wept"));
        List<?> results = (List<?>) out.get("results");
        assertFalse(results.isEmpty());

        Map<?, ?> row = (Map<?, ?>) results.get(0);
        assertTrue(row.containsKey("id"));
        assertTrue(row.containsKey("title"));
        assertTrue(row.containsKey("url"));
        assertTrue(String.valueOf(row.get("url")).startsWith("http"),
                "ChatGPT cites this URL, so it has to be absolute and openable.");
    }

    @Test
    void fetchReturnsTheDocumentShapeWithBothLanguagesInText() throws Exception {
        Map<String, Object> out = call("fetch", Map.of("id", "Test-Book:1"));

        assertEquals("Test-Book:1", out.get("id"));
        assertEquals("Test Book #1", out.get("title"));
        // Tagged on the way out, so a visit the link starts is attributed in GA4. This
        // handshake's client is "test" - neither Claude nor ChatGPT - hence "other".
        assertTrue(String.valueOf(out.get("url")).endsWith("/hadith/Test-Book:1"
                        + "?utm_source=other&utm_medium=ai-connector&utm_campaign=hadith-connector"
                        + "&utm_content=fetch"),
                String.valueOf(out.get("url")));

        String text = String.valueOf(out.get("text"));
        assertTrue(text.contains("بكت السماء"), "The Arabic matn is the primary text.");
        assertTrue(text.contains("heavens wept"), "The English belongs there too.");

        Map<?, ?> metadata = (Map<?, ?>) out.get("metadata");
        assertEquals("Test Book", metadata.get("book"));
        assertEquals("Chapter of Weeping", metadata.get("chapter"));
    }

    // ---- what the corpus is for ----

    @Test
    void getChapterReportsTheTrueChapterSizeAndBoundsTheResult() throws Exception {
        Map<String, Object> out = call("get_chapter",
                Map.of("book", "Test Book", "chapter", "Chapter of Weeping"));

        assertEquals(3, ((Number) out.get("chapter_size")).intValue(),
                "chapter_size is the whole point: it turns 'these appear to be the main "
                        + "ones' into an exhaustive answer.");
        assertEquals(3, ((Number) out.get("returned")).intValue());
        assertTrue(String.valueOf(out.get("note")).contains("Complete"));

        List<?> narrations = (List<?>) out.get("narrations");
        assertEquals(3, narrations.size());
        for (Object narration : narrations) {
            assertEquals("Chapter of Weeping", ((Map<?, ?>) narration).get("chapter"),
                    "The fasting narration must not leak into another chapter's count.");
        }
    }

    @Test
    void getChapterOrdersNarrationsNumericallyNotLexically() throws Exception {
        Map<String, Object> out = call("get_chapter",
                Map.of("book", "Test Book", "chapter", "Chapter of Weeping"));
        List<?> narrations = (List<?>) out.get("narrations");
        List<String> numbers = narrations.stream()
                .map(n -> String.valueOf(((Map<?, ?>) n).get("number"))).toList();
        assertEquals(List.of("1", "2", "3"), numbers);
    }

    @Test
    void searchHadithAlwaysReportsTotalMatches() throws Exception {
        Map<String, Object> out = call("search_hadith", Map.of("query", "wept", "limit", 1));

        assertEquals(3, ((Number) out.get("total_matches")).intValue(),
                "Without a denominator a model cannot tell one result from one of many.");
        assertEquals(1, ((Number) out.get("returned")).intValue());
        assertTrue(String.valueOf(out.get("note")).contains("offset"),
                "A truncated result must say how to see the rest.");
    }

    @Test
    void searchHadithNarrowsByBookAsAFilterRatherThanWideningTheQuery() throws Exception {
        Map<String, Object> matching = call("search_hadith",
                Map.of("query", "wept", "book", "Test Book"));
        assertEquals(3, ((Number) matching.get("total_matches")).intValue());

        // The regression this guards: written as `book:"..."` inside the query string, the
        // clause was OR-ed against the search terms and widened the result set instead.
        Map<String, Object> other = call("search_hadith",
                Map.of("query", "wept", "book", "Some Other Book"));
        assertEquals(0, ((Number) other.get("total_matches")).intValue());
    }

    @Test
    void searchHadithSupportsTheSameFieldScopingTheWebsiteDoes() throws Exception {
        // The regression this guards is a design one: an earlier version of the repository
        // built its own query instead of reusing QueryStringQueryResult, and silently lost
        // every field scope the site supports. Nothing failed - the searches just quietly
        // meant something different from the same words typed into the website.
        Map<String, Object> out = call("search_hadith",
                Map.of("query", "chapter:\"Chapter of Fasting\""));

        assertEquals(1, ((Number) out.get("total_matches")).intValue());
        List<?> results = (List<?>) out.get("results");
        assertEquals("Chapter of Fasting", ((Map<?, ?>) results.get(0)).get("chapter"));
    }

    @Test
    void searchHadithScopesOnMetadataFieldsBeyondBook() throws Exception {
        Map<String, Object> byVolume = call("search_hadith", Map.of("query", "volume:\"1\" wept"));
        assertEquals(3, ((Number) byVolume.get("total_matches")).intValue());

        Map<String, Object> byNumber = call("search_hadith", Map.of("query", "number:\"4\""));
        assertEquals(1, ((Number) byNumber.get("total_matches")).intValue());
    }

    @Test
    void searchHadithPreciseModeRequiresEveryTermWhereFlexibleDoesNot() throws Exception {
        // The website has always had match_mode; the connector hardcoded flexible, so a
        // model could not ask the question "does this exact wording occur" at all.
        Map<String, Object> flexible = call("search_hadith", Map.of("query", "heavens wept"));
        assertEquals(3, ((Number) flexible.get("total_matches")).intValue(),
                "flexible does not require every term, so all three weeping narrations match");

        Map<String, Object> precise = call("search_hadith",
                Map.of("query", "heavens wept", "match_mode", "precise"));
        assertEquals(1, ((Number) precise.get("total_matches")).intValue(),
                "precise requires both terms, which only the heavens narration has");
    }

    @Test
    void searchHadithPreciseModeDropsTheFuzzyExpansionToo() throws Exception {
        // The test above passes on term coverage alone. This one guards the other half of
        // strictness: enhanceQuery also stops wrapping each term as (term^6 OR term~), so a
        // precise search takes the spelling it was given. Threading the flag only as far as
        // the query and not into enhanceQuery is caught here, not above.
        Map<String, Object> flexible = call("search_hadith", Map.of("query", "weept"));
        assertEquals(3, ((Number) flexible.get("total_matches")).intValue(),
                "flexible fuzzy-matches the misspelling");

        Map<String, Object> precise = call("search_hadith",
                Map.of("query", "weept", "match_mode", "precise"));
        assertEquals(0, ((Number) precise.get("total_matches")).intValue(),
                "precise takes the term as written, and 'weept' is not in the corpus");
    }

    @Test
    void searchHadithReportsWhichMatchModeProducedTheCount() throws Exception {
        assertEquals("flexible", call("search_hadith", Map.of("query", "wept")).get("match_mode"));
        assertEquals("precise", call("search_hadith",
                Map.of("query", "wept", "match_mode", "precise")).get("match_mode"));
    }

    @Test
    void searchHadithSearchesADefinedFieldSetRatherThanEveryField() throws Exception {
        // The isnād weighting is only meaningful if the query is confined to fields we
        // chose. Left to Elasticsearch, a query_string searches every field in the document
        // and the boosts have nothing to bite on. `history` is deliberately outside the set.
        assertEquals(0, ((Number) call("search_hadith",
                        Map.of("query", "zzquux")).get("total_matches")).intValue(),
                "a term that appears only in an unsearched field must not match");

        assertEquals(1, ((Number) call("search_hadith",
                        Map.of("query", "zzcorpus")).get("total_matches")).intValue(),
                "the same shape of term inside the field set does match");
    }

    @Test
    void searchHadithSearchesTheMatnFieldWithTheIsnadStripped() throws Exception {
        // semantic_matn_source exists as an embedding input; this pins that it is also a
        // search field, which is what lets the matn outweigh the chain on the live corpus.
        // The ranking effect itself is measured there, not here - see NarrationRepository.
        Map<String, Object> out = call("search_hadith", Map.of("query", "\"الصلاة عمود\""));

        assertEquals(1, ((Number) out.get("total_matches")).intValue());
        List<?> results = (List<?>) out.get("results");
        assertEquals("Test-Book:6", ((Map<?, ?>) results.get(0)).get("id"));
    }

    @Test
    void searchHadithDropsTheFieldsThatExistForTheBrowser() throws Exception {
        Map<String, Object> out = call("search_hadith", Map.of("query", "wept"));
        List<?> results = (List<?>) out.get("results");
        Map<?, ?> row = (Map<?, ?>) results.get(0);

        for (String excluded : List.of("llm_similar", "englishContent", "arabicChain",
                "semantic_matn_source")) {
            assertFalse(row.containsKey(excluded),
                    excluded + " is in a search result; it is the reason a raw response is "
                            + "eight times larger than it needs to be.");
        }
    }

    @Test
    void hadithForVerseReturnsTheNarrationsJoinedToAVerseWithATrueCount() throws Exception {
        Map<String, Object> out = call("hadith_for_verse", Map.of("verse", "2:255"));

        assertEquals("2:255", ((Map<?, ?>) out.get("verse")).get("key"));
        assertEquals(2, ((Number) out.get("total_matches")).intValue());
        List<?> results = (List<?>) out.get("results");
        assertEquals(2, results.size());
        assertEquals("Test-Book:1", ((Map<?, ?>) results.get(0)).get("id"));
    }

    @Test
    void hadithForVerseEchoesTheVerseTextSoTheJoinIsLegible() throws Exception {
        Map<?, ?> verse = (Map<?, ?>) call("hadith_for_verse", Map.of("verse", "2:255")).get("verse");

        assertTrue(String.valueOf(verse.get("english")).contains("Ever-Living"));
        assertFalse(String.valueOf(verse.get("arabic")).isBlank());
    }

    @Test
    void hadithForVerseSeparatesAVerseWithNoConnectionsFromAVerseThatDoesNotExist()
            throws Exception {
        // The distinction the tool exists to make. A verse nothing was judged to connect to
        // is a finding; an unparseable reference is a mistake, and they must not look alike.
        Map<String, Object> none = call("hadith_for_verse", Map.of("verse", "19:12"));
        assertEquals(0, ((Number) none.get("total_matches")).intValue());
        assertTrue(String.valueOf(none.get("note")).contains("filter's verdict"));

        Map<String, Object> error = callRaw("hadith_for_verse", Map.of("verse", "999:1"));
        assertEquals(Boolean.TRUE, error.get("isError"));
    }

    @Test
    void hadithForVerseRejectsAReferenceThatIsNotSurahAyah() throws Exception {
        Map<String, Object> error = callRaw("hadith_for_verse", Map.of("verse", "Ayat al-Kursi"));
        assertEquals(Boolean.TRUE, error.get("isError"));
    }

    @Test
    void findSimilarReturnsTheJudgementReasonWhichIsTheWholePoint() throws Exception {
        Map<String, Object> out = call("find_similar", Map.of("id", "Test-Book:1"));

        assertEquals(1, ((Number) out.get("total_links")).intValue());
        List<?> similar = (List<?>) out.get("similar");
        Map<?, ?> link = (Map<?, ?>) similar.get(0);

        assertEquals("Test-Book:2", link.get("id"));
        assertEquals("wording", link.get("match_type"));
        assertTrue(String.valueOf(link.get("reason")).contains("Near-identical"),
                "The written reason is what no search engine can return.");
        assertTrue(link.containsKey("english"),
                "A link should resolve to readable text, not just an id.");
    }

    @Test
    void findSimilarFiltersByMatchType() throws Exception {
        Map<String, Object> out = call("find_similar",
                Map.of("id", "Test-Book:1", "match_type", "conceptual"));
        assertEquals(0, ((Number) out.get("returned")).intValue());
    }

    // ---- the corpus boundary ----

    @Test
    void anEmptySearchSaysWhatWasSearchedRatherThanThatNothingExists() throws Exception {
        Map<String, Object> out = call("search_hadith",
                Map.of("query", "zzzznotinthecorpuszzzz"));

        assertEquals(0, ((Number) out.get("total_matches")).intValue());
        String note = String.valueOf(out.get("note"));
        assertTrue(note.contains(String.valueOf(CorpusScope.BOOK_COUNT)),
                "An empty result must name the corpus it searched.");
        assertTrue(note.contains("do not restate"),
                "The evaluation in #66 found a model will report a corpus miss as proof a "
                        + "narration does not exist; the result has to say otherwise.");
    }

    @Test
    void anUnknownIdIsAToolErrorCarryingTheScopeNotAServerFailure() throws Exception {
        Map<String, Object> result = callRaw("fetch", Map.of("id", "No-Such-Book:99"));

        assertEquals(Boolean.TRUE, result.get("isError"));
        String text = (String) ((Map<?, ?>) ((List<?>) result.get("content")).get(0)).get("text");
        assertTrue(text.contains(String.valueOf(CorpusScope.BOOK_COUNT)));
        assertTrue(text.contains("do not restate"));
    }

    @Test
    void everyToolDescriptionCarriesTheCorpusBoundaryOrPointsAtIt() throws Exception {
        for (Map<String, Object> tool : listTools()) {
            String description = String.valueOf(tool.get("description"));
            assertTrue(description.length() > 120,
                    tool.get("name") + " has a thin description. It is the only channel we "
                            + "have - we do not control the host prompt.");
        }
    }

    @Test
    void anUnsupportedProtocolVersionHeaderIsRejectedWithFourHundred() throws Exception {
        // Spec, Streamable HTTP: "If the server receives a request with an invalid or
        // unsupported MCP-Protocol-Version, it MUST respond with 400 Bad Request." The SDK
        // transport answers 200, so McpProtocolVersionFilter enforces it.
        assertEquals(HttpStatus.BAD_REQUEST, postWithProtocolVersion("1999-01-01").getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, postWithProtocolVersion("not-a-date").getStatusCode());
    }

    @Test
    void everyVersionTheServerNegotiatesIsAlsoOneItAccepts() throws Exception {
        // The filter's allowlist and the SDK's negotiation are two lists that have to agree.
        // If they drift, the server negotiates a revision and then rejects requests naming
        // it back - a failure that would only appear against a real client.
        for (String version : McpProtocolVersionFilter.SUPPORTED) {
            sessionId = null;
            Map<String, Object> result = asMap(rpc(Map.of(
                    "jsonrpc", "2.0", "id", ++requestId, "method", "initialize",
                    "params", Map.of("protocolVersion", version, "capabilities", Map.of(),
                            "clientInfo", Map.of("name", "test", "version", "1.0"))))
                    .get("result"));
            assertEquals(version, result.get("protocolVersion"),
                    "server negotiated a different revision than the one requested");
            assertEquals(HttpStatus.OK, postWithProtocolVersion(version).getStatusCode(),
                    "server negotiates " + version + " but the filter rejects it");
        }
    }

    @Test
    void anAbsentProtocolVersionHeaderIsNotAnError() throws Exception {
        // The header is optional; the spec tells a server that receives none to assume
        // 2025-03-26 rather than fail. Rejecting its absence would break older clients.
        assertEquals(HttpStatus.OK, postWithProtocolVersion(null).getStatusCode());
    }

    private ResponseEntity<String> postWithProtocolVersion(String version) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.ACCEPT, "application/json, text/event-stream");
        if (version != null) {
            headers.set("MCP-Protocol-Version", version);
        }
        if (sessionId != null) {
            headers.set("Mcp-Session-Id", sessionId);
        }
        String body = mapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "id", ++requestId, "method", "tools/list"));
        return restTemplate.exchange(MCP, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void serverInstructionsStateTheCorpusBoundary() throws Exception {
        Map<String, Object> result = initialize();
        String instructions = String.valueOf(result.get("instructions"));
        assertTrue(instructions.contains("closed corpus"));
        assertTrue(instructions.contains("Cite narrations by the url"));
        assertTrue(instructions.contains("Ghurar al-Ḥikam"));
        assertTrue(instructions.contains(String.valueOf(CorpusScope.NARRATION_COUNT)));
    }

    @Test
    void serverAdvertisesItsTitleAndTheEmblemAsIcons() throws Exception {
        Map<String, Object> serverInfo = asMap(initialize().get("serverInfo"));
        assertEquals("rewayaat", serverInfo.get("name"),
                "The identifier a client may key on stays put.");
        assertEquals("The Hadith Database", serverInfo.get("title"));

        List<?> icons = (List<?>) serverInfo.get("icons");
        assertFalse(icons == null || icons.isEmpty(),
                "Without an icon, a client that shows one falls back to a generic mark.");
        for (Object item : icons) {
            Map<?, ?> icon = (Map<?, ?>) item;
            String src = String.valueOf(icon.get("src"));
            assertTrue(src.startsWith("http"), src);
            assertEquals("image/png", icon.get("mimeType"));
            // The advertised file has to ship with the site, or the client gets a 404 instead.
            String path = java.net.URI.create(src).getPath();
            assertNotNull(getClass().getResource("/static" + path), "missing " + path);
        }
    }

    // ---- the chain of transmission ----

    private static final String KAFI_10 = "Al-Kafi-Volume-1-Kulayni:10";
    private static final String KAFI_969 = "Al-Kafi-Volume-2-Kulayni:969";
    private static final String KAFI_878 = "Al-Kafi-Volume-1-Kulayni:878";
    private static final String KAMIL_409 = "Kamil-al-Ziyarat-Qummi:409";
    private static final String NAHJ_125 = "Nahj-al-Balagha-Radi:125";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> isnadOf(Map<String, Object> fetched) {
        return (Map<String, Object>) ((Map<String, Object>) fetched.get("metadata")).get("isnad");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> stepsOf(Map<String, Object> isnad) {
        return (List<Map<String, Object>>) isnad.get("steps");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> narratorOf(Map<String, Object> step) {
        return (Map<String, Object>) step.get("narrator");
    }

    @Test
    void fetchCarriesTheChainWithEveryManNamedLinkedAndOrdered() throws Exception {
        Map<String, Object> out = call("fetch", Map.of("id", KAFI_10));
        Map<String, Object> isnad = isnadOf(out);
        List<Map<String, Object>> steps = stepsOf(isnad);

        assertEquals("en+ar", isnad.get("language"));
        assertEquals(List.of("n000706", "n000034", "n029388", "n036590", "i06"),
                steps.stream().map(step -> narratorOf(step).get("id")).toList(),
                "Ali b. Ibrahim, his father, al-Nawfali, al-Sakuni, al-Sadiq, in printed order");
        assertEquals(List.of("Ali ibn Ibrahim ibn Hashim", "Ibrahim ibn Hashim",
                        "al-Husayn ibn Zayd al-Nawfali", "Ismail ibn Abi Ziyad al-Sakuni",
                        "Jaʿfar al-Ṣādiq"),
                steps.stream().map(step -> narratorOf(step).get("name")).toList());

        String url = String.valueOf(narratorOf(steps.get(0)).get("url"));
        assertTrue(url.contains("/narrator/n000706/ali-ibn-ibrahim-ibn-hashim"), url);
        assertTrue(url.endsWith("?utm_source=other&utm_medium=ai-connector"
                        + "&utm_campaign=hadith-connector&utm_content=fetch"),
                "A narrator link is this site's link, so the connector tags it like any other: "
                        + url);
    }

    @Test
    void fetchSaysHowEachManWasReachedSoConventionIsNotPassedOffAsAPlainMatch() throws Exception {
        List<Map<String, Object>> steps = stepsOf(isnadOf(call("fetch", Map.of("id", KAFI_10))));

        assertEquals(List.of("convention", "context", "sole_name", "sole_name", "sole_name"),
                steps.stream().map(step -> step.get("basis")).toList());
        @SuppressWarnings("unchecked")
        Map<String, String> legend = (Map<String, String>) isnadOf(
                call("fetch", Map.of("id", KAFI_10))).get("legend");
        assertTrue(legend.get("convention").contains("not on the name alone"));
        assertTrue(legend.get("context").contains("weaker identification"));
    }

    @Test
    void fetchMarksUnansweredStepsAndNamesNobodyForThem() throws Exception {
        Map<String, Object> isnad = isnadOf(call("fetch", Map.of("id", KAFI_878)));
        List<Map<String, Object>> steps = stepsOf(isnad);

        assertEquals(List.of("ambiguous", "ambiguous", "unidentified", "unidentified",
                        "unidentified", "group"),
                steps.stream().map(step -> step.get("status")).toList());
        for (Map<String, Object> step : steps) {
            assertFalse(step.containsKey("narrator"),
                    "step " + step.get("step") + " is unanswered and must name no one");
        }
        assertEquals("6 steps; 0 identified; 6 not identified (2 ambiguous, 3 unidentified, "
                + "1 group).", isnad.get("summary"));
    }

    @Test
    void fetchKeepsAnUnansweredStepInPlaceBetweenAnsweredOnes() throws Exception {
        List<Map<String, Object>> steps = stepsOf(isnadOf(call("fetch", Map.of("id", KAMIL_409))));

        assertEquals(List.of("identified", "identified", "identified", "group", "identified"),
                steps.stream().map(step -> step.get("status")).toList());
        assertEquals("i06", narratorOf(steps.get(4)).get("id"));
    }

    @Test
    void fetchGivesTheEnglishWordsAndFallsBackToArabicForAStepTheTranslationDrops()
            throws Exception {
        Map<String, Object> isnad = isnadOf(call("fetch", Map.of("id", KAFI_969)));
        List<Map<String, Object>> steps = stepsOf(isnad);

        assertEquals("Al-Husayn ibn Muhammad", steps.get(0).get("words"));
        assertEquals("Mu’alla ibn Muhammad", steps.get(1).get("words"));
        assertFalse(steps.get(0).containsKey("words_language"));
        assertEquals("ar", steps.get(5).get("words_language"),
                "The English translation carries no Imam here; the step must say its words "
                        + "are Arabic rather than present Arabic as English.");
        assertEquals("i05", narratorOf(steps.get(5)).get("id"));
        assertTrue(String.valueOf(isnad.get("note")).contains("given in Arabic"));
    }

    @Test
    void fetchGivesEnglishWithTheArabicBesideItBecauseItsSchemaCannotTakeALanguage()
            throws Exception {
        Map<String, Object> isnad = isnadOf(call("fetch", Map.of("id", KAFI_969)));
        List<Map<String, Object>> steps = stepsOf(isnad);

        assertEquals("en+ar", isnad.get("language"));
        assertEquals("Mu’alla ibn Muhammad", steps.get(1).get("words"));
        assertEquals("معلى بن محمد", bare(steps.get(1).get("words_ar")));
        assertEquals("Muḥammad al-Bāqir", narratorOf(steps.get(5)).get("name"));
        assertEquals("محمد بن علي الباقر", narratorOf(steps.get(5)).get("name_ar"));
    }

    @Test
    void searchInArabicGivesArabicWordsAndArabicNames() throws Exception {
        Map<String, Object> out = call("search_hadith", Map.of("query", "fixture",
                "book", "Al-Kāfi", "include_isnad", true, "language", "ar", "limit", 6));
        Map<?, ?> row = ((List<?>) out.get("results")).stream().map(r -> (Map<?, ?>) r)
                .filter(r -> KAFI_969.equals(r.get("id"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> isnad = (Map<String, Object>) row.get("isnad");
        List<Map<String, Object>> steps = stepsOf(isnad);

        assertEquals("ar", isnad.get("language"));
        assertEquals("محمد بن علي الباقر", narratorOf(steps.get(5)).get("name"));
        assertEquals("معلى بن محمد", narratorOf(steps.get(1)).get("name"));
        assertEquals("معلى بن محمد", bare(steps.get(1).get("words")),
                "the words are the printed ones; only the vowel marks are ignored here");
        assertNull(isnad.get("note"), "nothing falls back in Arabic");
    }

    @Test
    void fetchOfANarrationWithNoChainSaysNoAnalysedChainNotNoChain() throws Exception {
        Map<String, Object> isnad = isnadOf(call("fetch", Map.of("id", NAHJ_125)));

        assertEquals(List.of(), isnad.get("steps"));
        assertTrue(String.valueOf(isnad.get("note")).contains("does not mean it has none"));
    }

    @Test
    void fetchOfANarrationBeforeAnyChainWasSeededStillWorks() throws Exception {
        // Test-Book:1 has no chain fields at all, like the 5,900 narrations that predate or
        // fall outside the analysis.
        Map<String, Object> out = call("fetch", Map.of("id", "Test-Book:1"));
        assertEquals(List.of(), stepsOf(isnadOf(out)));
        assertTrue(String.valueOf(out.get("text")).contains("heavens wept"));
    }

    @Test
    void searchRejectsALanguageItDoesNotHave() throws Exception {
        Map<String, Object> result = callRaw("search_hadith",
                Map.of("query", "wept", "include_isnad", true, "language", "fr"));

        assertEquals(Boolean.TRUE, result.get("isError"));
        assertTrue(contentText(result).contains("/language"),
                "the protocol layer validates the enum from the published schema and names "
                        + "the argument: " + contentText(result));
    }

    @Test
    void fetchStillTakesOnlyIdAsRequiredSoChatGptsCallIsUnchanged() throws Exception {
        assertEquals(List.of("id"), required(tool("fetch")));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) ((Map<String, Object>) tool("fetch")
                .get("inputSchema")).get("properties");
        assertEquals(java.util.Set.of("id"), properties.keySet(),
                "ChatGPT's fixed schema: one argument, and ArchitectureRulesTest holds it there");
    }

    @Test
    void fetchDoesNotLeakTheRawChainFields() throws Exception {
        String json = mapper.writeValueAsString(call("fetch", Map.of("id", KAFI_10)));

        for (String raw : List.of("chain_people", "chain_links", "chain_links_en")) {
            assertFalse(json.contains(raw), raw + " is the store's shape; the tool returns steps");
        }
    }

    @Test
    void aSixStepChainWithNamesAndLinksStaysUnderAFixedCeiling()
            throws Exception {
        // Not a snapshot: a ceiling. The six-step chain of Kafi 969 with names and links is
        // what a client pays on every fetch, and doubling it should fail here, not in a
        // client's context window.
        int size = mapper.writeValueAsString(isnadOf(call("fetch", Map.of("id", KAFI_969)))).length();
        assertTrue(size < 4_500, "a six-step chain cost " + size + " characters");
    }

    // ---- the chain on lists: lean by default, opt-in ----

    @Test
    void searchResultsStayLeanByDefault() throws Exception {
        Map<String, Object> out = call("search_hadith", Map.of("query", "fixture"));

        assertEquals(8, ((Number) out.get("total_matches")).intValue());
        for (Object row : (List<?>) out.get("results")) {
            assertFalse(((Map<?, ?>) row).containsKey("isnad"),
                    "a chain on every hit is what the default must not pay for");
        }
    }

    @Test
    void searchCanReturnChainsOnRequestAndThenCapsThePageSize() throws Exception {
        Map<String, Object> out = call("search_hadith",
                Map.of("query", "fixture", "include_isnad", true, "limit", 15));
        List<?> results = (List<?>) out.get("results");

        assertEquals(6, results.size(), "limit 15 is clamped to 6 when chains are included");
        assertEquals(8, ((Number) out.get("total_matches")).intValue(),
                "the true total is still reported, so a small page is not a lossy one");
        for (Object row : results) {
            Map<?, ?> isnad = (Map<?, ?>) ((Map<?, ?>) row).get("isnad");
            assertNotNull(isnad, "every row carries its chain, even where it is empty");
            assertTrue(isnad.containsKey("steps"));
        }
    }

    @Test
    void searchWithChainsShowsTheSameStepsFetchDoes() throws Exception {
        Map<String, Object> out = call("search_hadith",
                Map.of("query", "\"Chain fixture narration\"", "book", "Kāmil al-Ziyārāt",
                        "include_isnad", true));
        Map<?, ?> row = (Map<?, ?>) ((List<?>) out.get("results")).get(0);
        assertEquals(KAMIL_409, row.get("id"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = stepsOf((Map<String, Object>) row.get("isnad"));
        assertEquals(List.of("identified", "identified", "identified", "group", "identified"),
                steps.stream().map(step -> step.get("status")).toList());
    }

    @Test
    void searchRejectsAnIncludeIsnadThatIsNotABoolean() throws Exception {
        Map<String, Object> result = callRaw("search_hadith",
                Map.of("query", "wept", "include_isnad", "perhaps"));
        assertEquals(Boolean.TRUE, result.get("isError"));
        assertTrue(contentText(result).contains("include_isnad"), contentText(result));
    }

    @Test
    void getChapterCanReturnChainsOnRequest() throws Exception {
        Map<String, Object> seeded = call("fetch", Map.of("id", KAFI_10));
        Map<String, Object> metadata = asMap(seeded.get("metadata"));
        Map<String, Object> out = call("get_chapter", Map.of(
                "book", metadata.get("book"), "chapter", metadata.get("chapter"),
                "include_isnad", true));

        Map<?, ?> row = ((List<?>) out.get("narrations")).stream()
                .map(n -> (Map<?, ?>) n)
                .filter(n -> KAFI_10.equals(n.get("id")))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = stepsOf((Map<String, Object>) row.get("isnad"));
        assertEquals("Ali ibn Ibrahim ibn Hashim", narratorOf(steps.get(0)).get("name"));
    }

    @Test
    void getChapterIsLeanWithoutTheFlag() throws Exception {
        Map<String, Object> metadata = asMap(call("fetch", Map.of("id", KAFI_10)).get("metadata"));
        Map<String, Object> out = call("get_chapter", Map.of(
                "book", metadata.get("book"), "chapter", metadata.get("chapter")));
        for (Object row : (List<?>) out.get("narrations")) {
            assertFalse(((Map<?, ?>) row).containsKey("isnad"));
        }
    }

    // ---- from a narrator to his narrations ----

    @Test
    void hadithOfNarratorListsTheNarrationsWhoseChainNamesHimWithATrueTotal() throws Exception {
        Map<String, Object> out = call("hadith_of_narrator", Map.of("id", "i06"));

        assertEquals("i06", asMap(out.get("narrator")).get("id"));
        assertEquals("Jaʿfar al-Ṣādiq", asMap(out.get("narrator")).get("name"));
        assertEquals(2, ((Number) out.get("total_narrations")).intValue(),
                "al-Sadiq closes the chains of Kafi 10 and Kamil 409 and no other seeded one");
        List<?> rows = (List<?>) out.get("narrations");
        assertEquals(List.of(KAFI_10, KAMIL_409),
                rows.stream().map(r -> ((Map<?, ?>) r).get("id")).toList(),
                "ordered by book then number: Al-Kāfi before Kāmil al-Ziyārāt");
        assertTrue(String.valueOf(out.get("note")).contains("lower bound"));
    }

    @Test
    void hadithOfNarratorSaysWhereHeStandsAndHowHeWasIdentifiedThere() throws Exception {
        Map<String, Object> out = call("hadith_of_narrator", Map.of("id", "n000034"));

        assertEquals(1, ((Number) out.get("total_narrations")).intValue());
        Map<?, ?> row = (Map<?, ?>) ((List<?>) out.get("narrations")).get(0);
        assertEquals(KAFI_10, row.get("id"));
        assertTrue(String.valueOf(row.get("english")).startsWith("Chain fixture"),
                "the row is a full narration, not an id");
        List<?> places = (List<?>) row.get("his_place");
        assertEquals(1, places.size());
        Map<?, ?> place = (Map<?, ?>) places.get(0);
        assertEquals(2, ((Number) place.get("step")).intValue());
        assertEquals(5, ((Number) place.get("of")).intValue());
        assertEquals("context", place.get("basis"),
                "Ibrahim b. Hashim is reached here through «his father», an inference");
        assertEquals("relative", place.get("kind"));
        assertEquals("ar", place.get("words_language"));
    }

    @Test
    void hadithOfNarratorSurfacesAConventionAsAConvention() throws Exception {
        Map<String, Object> out = call("hadith_of_narrator", Map.of("id", "n000706"));
        Map<?, ?> place = (Map<?, ?>) ((List<?>) ((Map<?, ?>) ((List<?>) out.get("narrations"))
                .get(0)).get("his_place")).get(0);

        assertEquals("convention", place.get("basis"));
        assertEquals("Ali ibn Ibrahim", place.get("words"));
        assertTrue(asMap(out.get("legend")).containsKey("convention"),
                "the legend travels with the codes the page uses");
    }

    @Test
    void hadithOfNarratorMatchesTheManAndNotTheWords() throws Exception {
        // Muhammad b. Yahya appears as a bare name in two chains of the seed, but only the
        // one resolved to al-Attar (n034836) is his. A text search for the words would find
        // every Muhammad b. Yahya; the id finds the man the resolver decided on.
        Map<String, Object> out = call("hadith_of_narrator", Map.of("id", "n034836"));
        assertEquals(1, ((Number) out.get("total_narrations")).intValue());
        assertEquals("Al-Kafi-Volume-6-Kulayni:1308",
                ((Map<?, ?>) ((List<?>) out.get("narrations")).get(0)).get("id"));
        Map<?, ?> place = (Map<?, ?>) ((List<?>) ((Map<?, ?>) ((List<?>) out.get("narrations"))
                .get(0)).get("his_place")).get(0);
        assertEquals("back_reference", place.get("kind"));
        assertEquals("convention", place.get("basis"));
    }

    @Test
    void hadithOfNarratorNarrowsByBookAndPages() throws Exception {
        Map<String, Object> inBook = call("hadith_of_narrator",
                Map.of("id", "i06", "book", "Kāmil al-Ziyārāt"));
        assertEquals(1, ((Number) inBook.get("total_narrations")).intValue());
        assertEquals(KAMIL_409, ((Map<?, ?>) ((List<?>) inBook.get("narrations")).get(0)).get("id"));

        Map<String, Object> second = call("hadith_of_narrator",
                Map.of("id", "i06", "limit", 1, "offset", 1));
        assertEquals(2, ((Number) second.get("total_narrations")).intValue());
        assertEquals(KAMIL_409, ((Map<?, ?>) ((List<?>) second.get("narrations")).get(0)).get("id"));

        Map<String, Object> first = call("hadith_of_narrator", Map.of("id", "i06", "limit", 1));
        assertEquals(KAFI_10, ((Map<?, ?>) ((List<?>) first.get("narrations")).get(0)).get("id"));
        assertTrue(String.valueOf(first.get("note")).contains("Page with `offset`"));
    }

    @Test
    void hadithOfNarratorInArabicNamesHimAndQuotesTheArabicWords() throws Exception {
        Map<String, Object> out = call("hadith_of_narrator", Map.of("id", "n029388", "language", "ar"));

        assertEquals("الحسين بن يزيد بن محمد بن عبد الملك النوفلي نوفل النخع",
                asMap(out.get("narrator")).get("name"));
        Map<?, ?> place = (Map<?, ?>) ((List<?>) ((Map<?, ?>) ((List<?>) out.get("narrations"))
                .get(0)).get("his_place")).get(0);
        assertEquals("النوفلي", bare(place.get("words")));
        assertNull(place.get("words_language"));
    }

    @Test
    void aManWhoStandsInNoChainGetsAZeroThatSaysItIsNotAFinding() throws Exception {
        Map<String, Object> out = call("hadith_of_narrator", Map.of("id", "n000999"));

        assertEquals(0, ((Number) out.get("total_narrations")).intValue());
        assertEquals(List.of(), out.get("narrations"));
        String note = String.valueOf(out.get("note"));
        assertTrue(note.contains("lower bound"), note);
        assertTrue(note.contains("can stand in many chains and show none here"), note);
    }

    @Test
    void hadithOfNarratorRejectsANameBecauseAnIdIsWhatItTakes() throws Exception {
        Map<String, Object> result = callRaw("hadith_of_narrator", Map.of("id", "Zurara"));

        assertEquals(Boolean.TRUE, result.get("isError"));
        assertTrue(contentText(result).contains("lookup_narrator"), contentText(result));
    }

    @Test
    void hadithOfNarratorRejectsAnIdThatLooksRightButIsNotInTheIndex() throws Exception {
        Map<String, Object> result = callRaw("hadith_of_narrator", Map.of("id", "n123456"));

        assertEquals(Boolean.TRUE, result.get("isError"));
        assertTrue(contentText(result).contains("No narrator with id 'n123456'"));
    }

    @Test
    void hadithOfNarratorRequiresAnId() throws Exception {
        Map<String, Object> result = callRaw("hadith_of_narrator", Map.of("limit", 3));

        assertEquals(Boolean.TRUE, result.get("isError"));
        assertTrue(contentText(result).contains("id"), contentText(result));
        assertEquals(List.of("id"), required(tool("hadith_of_narrator")));
    }

    @Test
    void hadithOfNarratorRejectsALimitThatIsNotANumber() throws Exception {
        Map<String, Object> result = callRaw("hadith_of_narrator",
                Map.of("id", "i06", "limit", "many"));
        assertEquals(Boolean.TRUE, result.get("isError"));
        assertTrue(contentText(result).contains("limit"), contentText(result));
    }

    @Test
    void theNarratorToolsDescribeTheirLimitsAndPointAtEachOther() throws Exception {
        String hadithOf = String.valueOf(tool("hadith_of_narrator").get("description"));
        assertTrue(hadithOf.contains("floor, not a census"));
        assertTrue(hadithOf.contains("`basis`"));
        assertTrue(hadithOf.contains("lookup_narrator"));

        assertTrue(String.valueOf(tool("lookup_narrator").get("description"))
                        .contains("hadith_of_narrator"),
                "a model that has just looked a man up must be told where his narrations are");
        assertTrue(String.valueOf(tool("search_hadith").get("description"))
                        .contains("hadith_of_narrator"),
                "search_hadith used to tell a model that searching a name was a poor way to "
                        + "find a narrator's narrations, with nothing better to offer");

        String fetch = String.valueOf(tool("fetch").get("description"));
        assertTrue(fetch.contains("`identified`") || fetch.contains("identified"));
        assertTrue(fetch.contains("unanswered on purpose"));
    }

    /** Arabic with its vowel marks removed, for comparing words without depending on mark order. */
    private static String bare(Object arabic) {
        return String.valueOf(arabic).replaceAll("[\\u064B-\\u0652\\u0670]", "").trim();
    }

    @Autowired
    private McpToolCatalog catalog;

    @Test
    void theToolsRejectBadArgumentsThemselvesWhenCalledWithoutTheProtocol() {
        // The website's chatbot calls the catalog directly, so the schema check the MCP
        // transport performs is not there. The tools must refuse on their own, in the same
        // way - an IllegalArgumentException the catalog turns into a readable tool error.
        assertThrows(IllegalArgumentException.class, () -> catalog.invoke("search_hadith",
                Map.of("query", "wept", "language", "fr")));
        assertThrows(IllegalArgumentException.class, () -> catalog.invoke("fetch", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> catalog.invoke("search_hadith",
                Map.of("query", "wept", "include_isnad", "perhaps")));
        assertThrows(IllegalArgumentException.class, () -> catalog.invoke("hadith_of_narrator", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> catalog.invoke("hadith_of_narrator",
                Map.of("id", "Zurara")));
        assertThrows(IllegalArgumentException.class, () -> catalog.invoke("hadith_of_narrator",
                Map.of("id", "i06", "limit", "many")));
        assertThrows(IllegalArgumentException.class, () -> catalog.invoke("hadith_of_narrator",
                Map.of("id", "i06", "language", "fr")));
        assertThrows(IllegalArgumentException.class, () -> catalog.invoke("hadith_of_narrator",
                Map.of("id", "n123456")));
    }

    @Test
    void theCatalogServesTheSameChainWithoutTheProtocolOrTheLinkTagging() throws Exception {
        Map<String, Object> out = catalog.invoke("fetch", Map.of("id", KAFI_10));
        Map<String, Object> first = stepsOf(isnadOf(out)).get(0);

        assertFalse(String.valueOf(narratorOf(first).get("url")).contains("utm_"),
                "the site's own chatbot is not connector traffic");
        assertEquals("n000706", narratorOf(first).get("id"));
    }

    private static String contentText(Map<String, Object> result) {
        return String.valueOf(((Map<?, ?>) ((List<?>) result.get("content")).get(0)).get("text"));
    }

    // ---- protocol plumbing ----

    private Map<String, Object> initialize() throws Exception {
        Map<String, Object> response = rpc(Map.of(
                "jsonrpc", "2.0", "id", ++requestId, "method", "initialize",
                "params", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "test", "version", "1.0"))));
        rpc(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
        return asMap(response.get("result"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listTools() throws Exception {
        Map<String, Object> response = rpc(Map.of(
                "jsonrpc", "2.0", "id", ++requestId, "method", "tools/list"));
        return (List<Map<String, Object>>) asMap(response.get("result")).get("tools");
    }

    private Map<String, Object> tool(String name) throws Exception {
        return listTools().stream()
                .filter(t -> name.equals(t.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No tool named " + name));
    }

    @SuppressWarnings("unchecked")
    private List<String> required(Map<String, Object> tool) {
        return (List<String>) ((Map<String, Object>) tool.get("inputSchema")).get("required");
    }

    /** The raw {@code CallToolResult}, for assertions about the envelope itself. */
    private Map<String, Object> callRaw(String name, Map<String, Object> arguments) throws Exception {
        Map<String, Object> response = rpc(Map.of(
                "jsonrpc", "2.0", "id", ++requestId, "method", "tools/call",
                "params", Map.of("name", name, "arguments", arguments)));
        return asMap(response.get("result"));
    }

    /** The tool's structured payload, which is what most assertions are about. */
    private Map<String, Object> call(String name, Map<String, Object> arguments) throws Exception {
        Map<String, Object> result = callRaw(name, arguments);
        assertFalse(Boolean.TRUE.equals(result.get("isError")),
                "Tool " + name + " failed: " + result.get("content"));
        return asMap(result.get("structuredContent"));
    }

    private Map<String, Object> rpc(Map<String, Object> payload) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.ACCEPT, "application/json, text/event-stream");
        headers.set("MCP-Protocol-Version", "2025-06-18");
        if (sessionId != null) {
            headers.set("Mcp-Session-Id", sessionId);
        }
        ResponseEntity<String> response = restTemplate.exchange(MCP, HttpMethod.POST,
                new HttpEntity<>(mapper.writeValueAsString(payload), headers), String.class);

        String returnedSession = response.getHeaders().getFirst("Mcp-Session-Id");
        if (returnedSession != null) {
            sessionId = returnedSession;
        }
        String body = response.getBody();
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        // Streamable HTTP answers either as JSON or as a one-event SSE stream, depending on
        // the method; a client has to read both, so the test does too.
        if (body.startsWith("event:") || body.contains("\ndata:") || body.startsWith("id:")) {
            for (String line : body.split("\n")) {
                if (line.startsWith("data:")) {
                    return asMap(mapper.readValue(line.substring(5).trim(), Map.class));
                }
            }
            return Map.of();
        }
        return asMap(mapper.readValue(body, Map.class));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        assertNotNull(value, "Expected an object, got null.");
        return (Map<String, Object>) value;
    }

    private void index(String id, String json) throws Exception {
        index(INDEX, id, json);
    }

    private void index(String index, String id, String json) throws Exception {
        client.index(i -> i.index(index).id(id).withJson(new StringReader(json)));
    }
}
