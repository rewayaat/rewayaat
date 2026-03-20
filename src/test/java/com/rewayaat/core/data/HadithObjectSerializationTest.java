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
}
