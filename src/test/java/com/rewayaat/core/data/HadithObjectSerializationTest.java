package com.rewayaat.core.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HadithObjectSerializationTest {

    @Test
    void preservesUnderscoreIdProperty() {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> source = new HashMap<>();
        source.put("_id", "hadith-123");
        source.put("book", "al-kafi");
        source.put("number", "1");

        HadithObject hadith = mapper.convertValue(source, HadithObject.class);
        Map<String, Object> result = mapper.convertValue(hadith, new TypeReference<Map<String, Object>>() {});

        assertEquals("hadith-123", result.get("_id"));
        assertFalse(result.containsKey("id"));
    }

    @Test
    void serializesAndDeserializesTopicTagsUsingSnakeCase() {
        ObjectMapper mapper = new ObjectMapper();
        HadithObject hadith = new HadithObject();
        hadith.setTopicTags(List.of("prayer", "fasting"));

        Map<String, Object> result = mapper.convertValue(hadith, new TypeReference<Map<String, Object>>() { });
        assertEquals(List.of("prayer", "fasting"), result.get("topic_tags"));
        assertFalse(result.containsKey("topicTags"));

        Map<String, Object> source = new HashMap<>();
        source.put("topic_tags", List.of("charity"));
        HadithObject restored = mapper.convertValue(source, HadithObject.class);
        assertEquals(List.of("charity"), restored.getTopicTags());
        assertTrue(restored.getTags().isEmpty());
    }

    /**
     * The edit path re-indexes a narration from a HadithObject, so a field the class does
     * not declare survives only through its additional properties. The citation must.
     */
    @Test
    void anEditKeepsTheCitation() {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> source = new HashMap<>();
        source.put("english", "before");
        source.put("cited_from", "Kanz al-Ummal, number:29331");
        source.put("cited_from_ar", "كنز العمال، ح 29331");

        HadithObject hadith = mapper.convertValue(source, HadithObject.class);
        Map<String, Object> merged = mapper.convertValue(hadith, new TypeReference<Map<String, Object>>() { });
        merged.put("english", "after");
        Map<String, Object> saved = mapper.convertValue(mapper.convertValue(merged, HadithObject.class),
                new TypeReference<Map<String, Object>>() { });

        assertEquals("after", saved.get("english"));
        assertEquals("Kanz al-Ummal, number:29331", saved.get("cited_from"));
        assertEquals("كنز العمال، ح 29331", saved.get("cited_from_ar"));
    }
}
