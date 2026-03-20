package com.rewayaat.tools;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicTagsBackfillToolTest {

    @Test
    void aiRefineAll_preservesExistingTagsWhenAiReturnsNothing() throws Exception {
        Object mode = mode("ai_refine_all");
        Method chooseTags = mode.getClass().getDeclaredMethod("chooseTags", List.class, List.class, List.class);
        chooseTags.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> chosen = (List<String>) chooseTags.invoke(mode,
                List.of("knowledge"),
                List.of("knowledge"),
                List.of());

        assertEquals(List.of("knowledge"), chosen);
    }

    @Test
    void aiRefineAll_replacesExistingTagsWhenAiReturnsSpecificTags() throws Exception {
        Object mode = mode("ai_refine_all");
        Method chooseTags = mode.getClass().getDeclaredMethod("chooseTags", List.class, List.class, List.class);
        chooseTags.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> chosen = (List<String>) chooseTags.invoke(mode,
                List.of("knowledge"),
                List.of("knowledge"),
                List.of("ghusl"));

        assertEquals(List.of("ghusl"), chosen);
    }

    @Test
    void aiRefineAll_reclassifiesTaggedDocumentsAndUsesPrecisionPrompt() throws Exception {
        Object mode = mode("ai_refine_all");

        Method reclassifiesTaggedDocs = mode.getClass().getDeclaredMethod("reclassifiesTaggedDocs");
        reclassifiesTaggedDocs.setAccessible(true);
        assertTrue((boolean) reclassifiesTaggedDocs.invoke(mode));

        Method instructions = mode.getClass().getDeclaredMethod("classificationInstructions");
        instructions.setAccessible(true);
        String text = (String) instructions.invoke(mode);

        assertTrue(text.contains("Treat existing_tags and rule_suggestions as weak hints"));
        assertTrue(text.contains("Each document must receive at least 1 slug"));
    }

    private Object mode(String value) throws Exception {
        Class<?> enumClass = Class.forName("com.rewayaat.tools.TopicTagsBackfillTool$TopicTaggingMode");
        Method fromExternal = enumClass.getDeclaredMethod("fromExternal", String.class);
        fromExternal.setAccessible(true);
        return fromExternal.invoke(null, value);
    }
}
