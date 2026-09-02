package com.rewayaat.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import co.elastic.clients.elasticsearch._types.Refresh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HadithApiIntegrationTest extends ElasticsearchTestSupport {

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void seedData() throws Exception {
        indexDoc("1", "{\"english\":\"hello world\",\"arabic\":\"سلام\",\"book\":\"Book A\",\"topic_tags\":[\"prayer\",\"charity\"]}");
        indexDoc("2", "{\"english\":\"hello there\",\"arabic\":\"مرحبا\",\"book\":\"Book B\",\"topic_tags\":[\"fasting\"]}");
        indexDoc("3", "{\"english\":\"help needed\",\"arabic\":\"سلام\",\"book\":\"Book C\"}");
    }

    @Test
    void queryHadith_returnsResults() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/v1/narrations?q=hello", Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map body = response.getBody();
        assertNotNull(body);
        List collection = (List) body.get("collection");
        assertNotNull(collection);
        assertTrue(collection.size() >= 2);
        Number total = (Number) body.get("totalResultSetSize");
        assertNotNull(total);
        assertTrue(total.longValue() >= 2L);
        assertNotNull(body.get("topicTagFacets"));
    }

    @Test
    void queryHadith_filtersByTopicTagsAndReturnsFacets() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/v1/narrations?q=hello&topic_tags=prayer", Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map body = response.getBody();
        assertNotNull(body);
        List collection = (List) body.get("collection");
        assertEquals(1, collection.size());
        Map first = (Map) collection.get(0);
        assertEquals("1", first.get("_id"));
        Map facets = (Map) body.get("topicTagFacets");
        assertNotNull(facets);
        assertEquals(1, ((Number) facets.get("prayer")).intValue());
        assertEquals(1, ((Number) facets.get("charity")).intValue());
    }

    @Test
    void queryHadith_filtersByTopicTagsAny() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/v1/narrations?q=hello&topic_tags_any=prayer&topic_tags_any=fasting", Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map body = response.getBody();
        assertNotNull(body);
        List collection = (List) body.get("collection");
        assertEquals(2, collection.size());
    }

    @Test
    void topTerms_returnsMatchingTerms() {
        ResponseEntity<List> response = restTemplate.getForEntity("/v1/terms/top?term=he&size=5", List.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        List body = response.getBody();
        assertNotNull(body);
        assertTrue(body.contains("hello"));
    }

    @Test
    void significantTerms_returnsArray() {
        ResponseEntity<List> response = restTemplate.getForEntity("/v1/terms/significant?inputTerms=hello&size=5", List.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        List body = response.getBody();
        assertNotNull(body);
    }

    @Test
    void similarHadith_returnsSimilarNarrations() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/v1/narrations/similar?id=1&per_page=5", Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map body = response.getBody();
        assertNotNull(body);
        List collection = (List) body.get("collection");
        assertNotNull(collection);
        // If similar hadiths are found, none should be the source document
        for (Object item : collection) {
            Map doc = (Map) item;
            String id = (String) doc.get("_id");
            assertNotNull(id);
            assertFalse("1".equals(id), () -> "Source document should not be in similar results");
        }
    }

    @Test
    void similarHadith_allFlagReturnsCollection() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/v1/narrations/similar?id=1&all=true", Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map body = response.getBody();
        assertNotNull(body);
        assertNotNull(body.get("collection"));
    }

    @Test
    void narrationResponses_neverExposeSemanticVector() throws Exception {
        indexDoc("4", "{\"english\":\"hello vector\",\"arabic\":\"سلام\",\"book\":\"Book D\","
                + "\"semantic_vector\":[0.1,0.2,0.3],"
                + "\"llm_similar\":[{\"id\":\"1\",\"match_type\":\"wording\",\"reason\":\"same wording\"}]}");

        // The vector really is stored, so the assertions below prove it is filtered out on
        // the way to the client rather than simply absent from the document.
        assertTrue(client.get(g -> g.index(INDEX).id("4"), Map.class).source().containsKey("semantic_vector"));

        ResponseEntity<String> search = restTemplate.getForEntity("/v1/narrations?q=vector", String.class);
        assertEquals(HttpStatus.OK, search.getStatusCode());
        assertNotNull(search.getBody());
        assertTrue(search.getBody().contains("Book D"));
        assertFalse(search.getBody().contains("semantic_vector"));

        ResponseEntity<String> byId = restTemplate.getForEntity("/v1/narrations/4", String.class);
        assertEquals(HttpStatus.OK, byId.getStatusCode());
        assertNotNull(byId.getBody());
        assertTrue(byId.getBody().contains("Book D"));
        assertFalse(byId.getBody().contains("semantic_vector"));

        ResponseEntity<String> similar = restTemplate.getForEntity("/v1/narrations/similar?id=4&per_page=5", String.class);
        assertEquals(HttpStatus.OK, similar.getStatusCode());
        assertNotNull(similar.getBody());
        // Similar-hadith lookup still resolves the pre-computed match...
        assertTrue(similar.getBody().contains("\"_id\":\"1\""));
        assertTrue(similar.getBody().contains("wording"));
        // ...without leaking the vector.
        assertFalse(similar.getBody().contains("semantic_vector"));
    }

    private void indexDoc(String id, String json) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> doc = mapper.readValue(json, new TypeReference<Map<String, Object>>() {
        });
        client.index(i -> i.index(INDEX).id(id).document(doc).refresh(Refresh.True));
    }
}
