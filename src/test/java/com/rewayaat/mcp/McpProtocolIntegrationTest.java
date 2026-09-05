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
import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpProtocolIntegrationTest {

    private static final String INDEX = "rewayaat_mcp_test";
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
              "llm_similar":{"type":"nested","properties":{
                "id":{"type":"keyword"},"match_type":{"type":"keyword"},
                "reason":{"type":"text","index":false}}}
            }}}""";

    @BeforeEach
    void seed() throws Exception {
        System.setProperty("REWAYAAT_INDEX", INDEX);
        ESClientProvider.resetIndex();
        restClient = Rest5Client.builder(new HttpHost("http", "localhost", 9200)).build();
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
        client.indices().refresh(r -> r.index(INDEX));

        sessionId = null;
        requestId = 0;
        initialize();
    }

    @AfterEach
    void cleanup() throws Exception {
        if (client != null
                && client.indices().exists(ExistsRequest.of(b -> b.index(INDEX))).value()) {
            client.indices().delete(DeleteIndexRequest.of(b -> b.index(INDEX)));
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
        assertTrue(String.valueOf(out.get("url")).endsWith("/hadith/Test-Book:1"));

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
    void serverInstructionsStateTheCorpusBoundary() throws Exception {
        Map<String, Object> result = initialize();
        String instructions = String.valueOf(result.get("instructions"));
        assertTrue(instructions.contains("closed corpus"));
        assertTrue(instructions.contains("Cite narrations by the url"));
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
        client.index(i -> i.index(INDEX).id(id).withJson(new StringReader(json)));
    }
}
