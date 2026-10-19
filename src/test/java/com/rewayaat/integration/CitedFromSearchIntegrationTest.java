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
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.DeleteIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;

import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mīzān al-Ḥikma quotes every narration from an earlier book, and the reference is a
 * field of its own, {@code cited_from}. A reader who remembers only the source book
 * ("Kanz al-Ummal") has to be able to find the narration by it, in either language.
 *
 * <p>Built on {@code scripts/search/v2_mapping.json}, the mapping production runs, for
 * the reason {@link MigrationPreservesSearchIntegrationTest} gives: the dynamic mapping
 * {@link ElasticsearchTestSupport} creates has no {@code .text} sub-fields.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CitedFromSearchIntegrationTest extends ElasticsearchTestSupport {

    private static final Path PRODUCTION_MAPPING = Path.of("scripts/search/v2_mapping.json");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void buildOnTheProductionMapping() throws Exception {
        if (client.indices().exists(ExistsRequest.of(e -> e.index(INDEX))).value()) {
            client.indices().delete(DeleteIndexRequest.of(b -> b.index(INDEX)));
        }
        String mapping = Files.readString(PRODUCTION_MAPPING, StandardCharsets.UTF_8);
        client.indices().create(CreateIndexRequest.of(b -> b
                .index(INDEX)
                .withJson(new StringReader(mapping))));
        client.index(IndexRequest.of(b -> b
                .index(INDEX).id("Mizan-al-Hikma-Rayshahri:1")
                .withJson(new StringReader("""
                        {"book":"Mīzān al-Ḥikma","number":"1","volume":"1","chapter":"Banes",
                         "english":"Imam Ali (AS) said, the bane of knowledge is forgetfulness.",
                         "arabic":"آفة العلم النسيان",
                         "source":"Muḥammad Muḥammadī Rayshahrī",
                         "cited_from":"Kanz al-Ummal, number:29331",
                         "cited_from_ar":"كنز العمال، ح 29331"}"""))
                .refresh(Refresh.True)));
    }

    @Test
    @DisplayName("the mapping declares the citation and the Arabic publisher")
    void mappingDeclaresTheNewFields() throws Exception {
        Map<String, Property> properties = client.indices().getMapping(m -> m.index(INDEX))
                .get(INDEX).mappings().properties();
        assertTrue(properties.get("cited_from").keyword().fields().containsKey("text"));
        assertTrue(properties.get("cited_from_ar").keyword().fields().containsKey("text"));
        assertTrue(properties.containsKey("publisher_ar"));
    }

    @Test
    @DisplayName("a narration is found by the book it is cited from, in English and Arabic")
    void citationIsSearchable() throws Exception {
        assertEquals(1, totalFor("ummal"));
        assertEquals(1, totalFor("العمال"));
    }

    private int totalFor(String query) throws Exception {
        JsonNode body = JSON.readTree(search(query));
        return body.path("totalResultSetSize").asInt();
    }

    /** A URI, not a string: see {@code MigrationPreservesSearchIntegrationTest.search}. */
    private String search(String query) {
        URI uri = URI.create(restTemplate.getRootUri() + "/v1/narrations?page=1&q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8));
        String body = restTemplate.getForObject(uri, String.class);
        assertNotNull(body, "the search endpoint returned nothing for: " + query);
        return body;
    }
}
