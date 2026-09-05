package com.rewayaat.mcp;

import java.util.Map;

/**
 * One tool exposed over MCP.
 *
 * <p>Implementations return plain maps rather than MCP result objects so that the same tool
 * can be called by something that is not an MCP client - the website's own chatbot talks to
 * {@link McpToolCatalog} directly, with no protocol in the way. Wrapping a map into the
 * content/structuredContent pair an MCP client expects is {@link McpToolCatalog}'s job.
 */
public interface McpTool {

    /** Tool name as the client sees it. Stable: clients and prompts refer to it. */
    String name();

    /** Human-readable title shown in client UIs. */
    String title();

    /**
     * What the tool does, in the terms a model needs to choose it correctly.
     *
     * <p>This is the only place we can state the corpus boundary. The host prompt is not
     * ours, so if a description does not say that a miss means "not in these 18 books"
     * rather than "does not exist", nothing else will.
     */
    String description();

    /** JSON Schema for the arguments. */
    Map<String, Object> inputSchema();

    /**
     * JSON Schema for the returned object, or {@code null} when the tool does not declare
     * one. ChatGPT's company-knowledge path wants an output schema on any tool that returns
     * structured content, so the two tools it can see always declare theirs.
     */
    default Map<String, Object> outputSchema() {
        return null;
    }

    /** Executes the tool. The returned map is serialised as the structured result. */
    Map<String, Object> call(Map<String, Object> arguments) throws Exception;
}
