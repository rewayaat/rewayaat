package com.rewayaat.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConnectorLinksTest {

    private static final String BASE = "https://hadith.academyofislam.com";
    private static final String TAGS =
            "utm_source=claude&utm_medium=ai-connector&utm_campaign=hadith-connector&utm_content=get_chapter";

    @Test
    void sourceIsTheVendorNotTheRawClientName() {
        assertEquals("claude", ConnectorLinks.source("claude-ai"));
        assertEquals("claude", ConnectorLinks.source("Anthropic/ClaudeAI"));
        assertEquals("chatgpt", ConnectorLinks.source("openai-mcp"));
        assertEquals("chatgpt", ConnectorLinks.source("ChatGPT"));
        assertEquals("other", ConnectorLinks.source("mcp-inspector"));
        assertEquals("other", ConnectorLinks.source(null));
    }

    @Test
    void tagsEveryOwnSiteLinkHoweverDeeplyNested() {
        Map<String, Object> result = Map.of(
                "url", BASE + "/hadith/A:1",
                "narrations", List.of(
                        Map.of("id", "A:2", "url", BASE + "/hadith/A:2"),
                        Map.of("id", "A:3", "url", BASE + "/hadith/A:3")));

        Map<String, Object> tagged = ConnectorLinks.tag(result, BASE, "claude", "get_chapter");

        assertEquals(BASE + "/hadith/A:1?" + TAGS, tagged.get("url"));
        List<?> narrations = (List<?>) tagged.get("narrations");
        assertEquals(BASE + "/hadith/A:2?" + TAGS, ((Map<?, ?>) narrations.get(0)).get("url"));
        assertEquals(BASE + "/hadith/A:3?" + TAGS, ((Map<?, ?>) narrations.get(1)).get("url"));
    }

    @Test
    void leavesOtherSitesLinksAndOtherFieldsAlone() {
        Map<String, Object> result = Map.of(
                "commentary", List.of(Map.of("tafsir", "al-Mizan", "url", "https://example.org/tafsir/2/255")),
                "title", BASE + "/hadith/A:1",
                "chapter_size", 7);

        Map<String, Object> tagged = ConnectorLinks.tag(result, BASE, "claude", "verses_for_hadith");

        assertEquals(result, tagged,
                "A tafsir source_url is someone else's page, and a non-url field is not a link.");
    }

    @Test
    void appendsToAnExistingQueryAndNeverTagsTwice() {
        assertEquals(BASE + "/search?q=x&" + TAGS,
                ConnectorLinks.tagUrl(BASE + "/search?q=x", BASE, "claude", "get_chapter"));

        String once = ConnectorLinks.tagUrl(BASE + "/hadith/A:1", BASE, "claude", "get_chapter");
        assertEquals(once, ConnectorLinks.tagUrl(once, BASE, "chatgpt", "search"));
    }

    @Test
    void aLookalikeHostIsNotOurs() {
        String lookalike = BASE + ".example.com/hadith/A:1";
        assertEquals(lookalike, ConnectorLinks.tagUrl(lookalike, BASE, "claude", "search"));
    }
}
