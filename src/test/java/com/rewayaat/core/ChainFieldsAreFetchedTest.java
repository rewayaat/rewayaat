package com.rewayaat.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A route that narrows {@code _source} has to ask for the spans the chain is drawn from.
 *
 * <p>{@link ChainLinks} puts the links on the isnād from {@code chain_links}, and the RIJĀL
 * panel beside it is built from {@code chain_people}. Those are separate fields, so a field
 * list naming only the second produces a page where the panel is complete and correct and
 * the chain above it is plain text — a failure that looks like a resolver that found nobody
 * rather than like a missing field. That is what the chapter pages did: all 5,445 of them,
 * across nineteen books, served 0 links from a corpus that had 101,592, because
 * {@code BookPageController}'s own include list named {@code chain_people} and stopped.
 *
 * <p>Asserted against the source text rather than a rendered page because it holds for
 * every route at once, including the ones nobody has written a page test for, and costs
 * no Elasticsearch to check. {@code ChapterChainLinksIntegrationTest} is the other half:
 * it boots the app and reads the href out of real chapter HTML, which is the only way to
 * know the fetched field reaches the page.
 */
class ChainFieldsAreFetchedTest {

    private static final Path MAIN = Path.of("src/main/java");

    /**
     * What marks a file as owning a field list rather than merely reading one. {@code ChainView}
     * names every chain field there is and fetches none of them; it is the renderer at the far
     * end of the MCP list, and a guard that cannot tell the two apart has nothing to say about
     * either.
     */
    private static final String FETCHES = ".includes(";

    /** The field a narrowed list is near-certain to name, and the two that get forgotten. */
    private static final String PANEL = "\"chain_people\"";
    private static final String ARABIC_SPANS = "\"chain_links\"";
    private static final String ENGLISH_SPANS = "\"chain_links_en\"";

    /**
     * The MCP tools build the chain themselves out of {@code chain_people} and never touch
     * the Arabic offsets, so their list is right to leave {@code chain_links} out of a
     * payload a language model pays for by the token. The exemption is held to that claim
     * by {@link #theOneExemptionStillEarnsIt()} rather than by this list alone.
     */
    private static final Path MCP_LIST = MAIN.resolve("com/rewayaat/mcp/NarrationRepository.java");
    private static final Path CHAIN_VIEW = MAIN.resolve("com/rewayaat/mcp/ChainView.java");

    @Test
    @DisplayName("a field list that fetches the chain panel fetches the links too")
    void everyNarrowedListAsksForTheSpans() throws IOException {
        Set<String> missing = new TreeSet<>();
        Set<String> seen = new TreeSet<>();

        for (Path file : javaSources()) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            if (!source.contains(PANEL) || !source.contains(FETCHES)) {
                continue;
            }
            seen.add(MAIN.relativize(file).toString());
            boolean exempt = file.equals(MCP_LIST);
            if (!exempt && !source.contains(ARABIC_SPANS)) {
                missing.add(MAIN.relativize(file) + " names chain_people but not chain_links");
            }
            if (!source.contains(ENGLISH_SPANS)) {
                missing.add(MAIN.relativize(file) + " names chain_people but not chain_links_en");
            }
        }

        assertEquals(Set.of(), missing,
                "these fetch the chain panel and not the links that go on the names:\n"
                        + String.join("\n", missing)
                        + "\nAdd the fields to the _source include list. The page will render "
                        + "either way — the panel beside the chain is built from chain_people — "
                        + "so nothing else will tell you.");

        // And the sweep has to have swept something. A refactor that moves every include
        // list behind a builder would otherwise leave this test passing over nothing.
        assertFalse(seen.isEmpty(), "no file in src/main/java fetches chain_people at all");
    }

    @Test
    @DisplayName("the one exemption still earns it")
    void theOneExemptionStillEarnsIt() throws IOException {
        // NarrationRepository may omit chain_links only for as long as the MCP renderer
        // has no use for it. The day ChainView reads the Arabic offsets, the test above
        // has to start demanding them, and this is what notices.
        String view = Files.readString(CHAIN_VIEW, StandardCharsets.UTF_8);
        assertFalse(view.contains("get(" + ARABIC_SPANS + ")"),
                "ChainView now reads chain_links, so NarrationRepository must fetch it: drop "
                        + "the MCP_LIST exemption above and add the field to CHAIN_FIELDS.");
    }

    @Test
    @DisplayName("the fields this guards are the ones the renderer actually reads")
    void theGuardNamesTheFieldsTheRendererReads() throws IOException {
        // Without this the two tests above pass just as well against a pair of field names
        // that nothing has used since someone renamed them.
        List<Path> renderers = new ArrayList<>();
        for (Path file : javaSources()) {
            if (Files.readString(file, StandardCharsets.UTF_8).contains("ChainLinks.render")) {
                renderers.add(file);
            }
        }
        assertFalse(renderers.isEmpty(), "nothing calls ChainLinks.render any more");

        for (Path renderer : renderers) {
            String source = Files.readString(renderer, StandardCharsets.UTF_8);
            assertTrue(source.contains("get(" + ARABIC_SPANS + ")")
                            || source.contains("get(" + ENGLISH_SPANS + ")"),
                    MAIN.relativize(renderer) + " renders a chain from neither chain_links nor "
                            + "chain_links_en, so the field names this test guards are stale");
        }
    }

    private static List<Path> javaSources() throws IOException {
        try (Stream<Path> walk = Files.walk(MAIN)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }
}
