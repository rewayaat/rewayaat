package com.rewayaat.mcp.tools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Argument handling, which is where a model's mistakes arrive.
 *
 * <p>Every rejection has to be an {@link IllegalArgumentException} carrying a readable
 * sentence, because the catalog turns exactly that into a tool error the model can act on.
 * Anything else surfaces as a server failure and tells the caller nothing.
 */
class ToolArgumentsTest {

    @Test
    void requiredString_rejectsMissingBlankAndAbsentArguments() {
        for (Map<String, Object> arguments : List.of(
                Map.<String, Object>of(),
                Map.<String, Object>of("query", ""),
                Map.<String, Object>of("query", "   "))) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> ToolArguments.requiredString(arguments, "query"));
            assertTrue(thrown.getMessage().contains("query"),
                    "The message must name the argument so the model can correct it.");
        }
    }

    @Test
    void requiredString_trims() {
        assertEquals("prayer", ToolArguments.requiredString(Map.of("query", "  prayer  "), "query"));
    }

    @Test
    void boundedInt_clampsRatherThanRejecting() {
        // A model asking for 500 results wants as many as it can have, not an error.
        assertEquals(15, ToolArguments.boundedInt(Map.of("limit", 500), "limit", 8, 1, 15));
        assertEquals(1, ToolArguments.boundedInt(Map.of("limit", -20), "limit", 8, 1, 15));
        assertEquals(8, ToolArguments.boundedInt(Map.of(), "limit", 8, 1, 15));
    }

    @Test
    void boundedInt_acceptsNumbersSentAsStrings() {
        // JSON-RPC clients are not consistent about this.
        assertEquals(5, ToolArguments.boundedInt(Map.of("limit", "5"), "limit", 8, 1, 15));
    }

    @Test
    void boundedInt_rejectsSomethingThatIsNotANumber() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> ToolArguments.boundedInt(Map.of("limit", "lots"), "limit", 8, 1, 15));
        assertTrue(thrown.getMessage().contains("limit"));
    }

    @Test
    void stringList_acceptsBothAListAndABareString() {
        assertEquals(List.of("fasting", "prayer"),
                ToolArguments.stringList(Map.of("topic_tags", List.of("fasting", "prayer")), "topic_tags"));
        assertEquals(List.of("fasting"),
                ToolArguments.stringList(Map.of("topic_tags", "fasting"), "topic_tags"));
        assertEquals(List.of(), ToolArguments.stringList(Map.of(), "topic_tags"));
    }

    @Test
    void stringList_dropsBlanksRatherThanFilteringOnThem() {
        assertEquals(List.of("fasting"),
                ToolArguments.stringList(Map.of("topic_tags", List.of("fasting", "", "  ")), "topic_tags"));
    }
}
