package com.rewayaat.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The architecture rules from {@code docs/architecture/adr-001-mcp-connector.md}, as tests.
 *
 * <p>Prose does not stop drift. These are the rules from that record that can be checked
 * mechanically, checked. They read the source rather than the compiled classes deliberately:
 * the rules are about how code is written, not about what it links to at runtime, and this
 * needs no new dependency.
 *
 * <p>Where a rule already has a violation, it is named in an exemption set rather than the
 * rule being weakened to accommodate it. The rule still bites for new code, and the exemption
 * list is a visible to-do instead of a silence.
 */
class ArchitectureRulesTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    @Test
    @DisplayName("ADR-001 §1: free-text search is built in one place")
    void searchQueriesAreNotAssembledOutsideTheSharedQueryBuilder() throws IOException {
        // The expensive rule. A second query builder does not fail - it quietly means
        // something different from the same words typed into the website, and it is how
        // field scoping was lost once already.
        Set<String> exempt = Set.of(
                // The one query builder that is allowed to build one.
                "com/rewayaat/core/QueryStringQueryResult.java",

                // Known violation, recorded in ADR-001 under "Known exemptions".
                // pageForHadithId builds its own query_string and so never parses field
                // scopes: page_for_id on a field-scoped query computes a page number
                // against a different result set than the one being paged. Fixing it means
                // routing it through the shared builder; until then it is named here rather
                // than allowed to make the rule meaningless.
                "com/rewayaat/controllers/rest/HadithController.java");

        List<String> offenders = sourceFilesContaining(".queryString(", exempt);

        assertTrue(offenders.isEmpty(),
                "These build their own query_string instead of going through "
                        + "QueryStringQueryResult. A narrow field list is a parameter "
                        + "(queryFields), and a different output shape is a seam (rawResult) "
                        + "- neither is a reason to fork the query: " + offenders);
    }

    @Test
    @DisplayName("ADR-001 §2: MCP tools shape responses, they do not fetch them")
    void mcpToolsDoNotReachElasticsearchDirectly() throws IOException {
        // Every read goes through NarrationRepository, which is where the shaped field list
        // lives. A tool that queried Elasticsearch itself would be free to return a full
        // _source - roughly 42,000 bytes where 5,400 will do, against a 25,000-token cap.
        List<String> offenders = new ArrayList<>();
        for (String marker : List.of("ESClientProvider", "SearchRequest")) {
            offenders.addAll(sourceFilesContaining(marker, Set.of()).stream()
                    .filter(path -> path.startsWith("com/rewayaat/mcp/tools/"))
                    .toList());
        }

        assertTrue(offenders.isEmpty(),
                "MCP tools must read through NarrationRepository so that every result is "
                        + "shaped by the same field list: " + offenders);
    }

    @Test
    @DisplayName("ADR-001 §3: the ChatGPT facade takes one string and does not grow")
    void theChatGptFacadeToolsKeepTheirFixedSchemas() throws IOException {
        // ChatGPT's company-knowledge path calls only `search` and `fetch`, each with a
        // single string argument. Adding a second argument does not fail loudly - it makes
        // the server silently unusable as a knowledge source, which is the whole reason
        // these two tools exist. A richer search belongs in search_hadith.
        assertSingleArgumentFacade("SearchTool.java", "query");
        assertSingleArgumentFacade("FetchTool.java", "id");
    }

    private void assertSingleArgumentFacade(String fileName, String argument) throws IOException {
        Path file = SOURCE_ROOT.resolve("com/rewayaat/mcp/tools").resolve(fileName);
        String source = Files.readString(file, StandardCharsets.UTF_8);
        int schemaStart = source.indexOf("inputSchema()");
        // Stop at whichever method follows, so outputSchema - which legitimately declares
        // several strings, being ChatGPT's result shape - is not counted as an argument.
        int schemaEnd = Stream.of("outputSchema()", "public Map<String, Object> call")
                .mapToInt(marker -> source.indexOf(marker, schemaStart + 1))
                .filter(index -> index > schemaStart)
                .min()
                .orElse(-1);
        assertTrue(schemaStart >= 0 && schemaEnd > schemaStart,
                fileName + ": could not locate inputSchema(), so this rule is not actually "
                        + "checking anything - fix the test rather than deleting it");

        String schema = source.substring(schemaStart, schemaEnd);
        long properties = schema.split("\"type\", \"string\"", -1).length - 1;
        assertTrue(properties == 1,
                fileName + " declares " + properties + " string properties; ChatGPT's "
                        + "compatibility schema allows exactly one (" + argument + "). "
                        + "A richer search belongs in search_hadith.");
    }

    /** Source paths, repo-relative with '/' separators, containing {@code marker}. */
    private List<String> sourceFilesContaining(String marker, Set<String> exempt)
            throws IOException {
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            List<String> matches = new ArrayList<>();
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java"))
                    .toList()) {
                String relative = SOURCE_ROOT.relativize(file).toString().replace('\\', '/');
                if (exempt.contains(relative)) {
                    continue;
                }
                if (Files.readString(file, StandardCharsets.UTF_8).contains(marker)) {
                    matches.add(relative);
                }
            }
            return matches;
        }
    }
}
