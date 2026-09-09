package com.rewayaat.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds every {@link McpTool} and adapts it to the two callers we have.
 *
 * <p>The MCP server gets {@link #specifications()}. The website's own chatbot gets
 * {@link #invoke}, which skips the protocol entirely - it is in the same JVM, so making it
 * speak JSON-RPC to itself would buy nothing.
 *
 * <p>Every MCP result carries the same object twice: once as {@code structuredContent} and
 * once JSON-encoded in the {@code content} array. That is not redundancy for its own sake -
 * ChatGPT's company-knowledge path reads the JSON string out of {@code content}, while
 * clients on newer protocol revisions read {@code structuredContent}. Sending one without
 * the other breaks a real client.
 *
 * <p>The SDK happens to fill {@code content} from {@code structuredContent} when we leave it
 * empty, so the second call below is redundant today - verified by removing it and watching
 * the JSON string still arrive. It stays because that behaviour is the SDK's to change and
 * the requirement is ours to meet: a silent upstream change here would uninstall us from
 * ChatGPT, with nothing failing to say so.
 */
@Component
public class McpToolCatalog {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpToolCatalog.class);

    private final Map<String, McpTool> tools = new LinkedHashMap<>();
    private final ObjectMapper mapper;

    public McpToolCatalog(List<McpTool> tools, ObjectMapper mapper) {
        this.mapper = mapper;
        for (McpTool tool : tools) {
            this.tools.put(tool.name(), tool);
        }
        LOGGER.info("MCP tool catalog: {}", this.tools.keySet());
    }

    /** Tool names, in registration order. */
    public List<String> names() {
        return new ArrayList<>(tools.keySet());
    }

    public McpTool tool(String name) {
        return tools.get(name);
    }

    /**
     * Calls a tool by name and returns its structured result.
     *
     * @throws IllegalArgumentException when no such tool is registered
     */
    public Map<String, Object> invoke(String name, Map<String, Object> arguments) throws Exception {
        McpTool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown tool: " + name);
        }
        return tool.call(arguments == null ? Map.of() : arguments);
    }

    /** Adapts every registered tool into the SDK's server-side representation. */
    public List<McpServerFeatures.SyncToolSpecification> specifications() {
        List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>();
        for (McpTool tool : tools.values()) {
            specs.add(specification(tool));
        }
        return specs;
    }

    private McpServerFeatures.SyncToolSpecification specification(McpTool tool) {
        McpSchema.Tool.Builder builder = McpSchema.Tool.builder()
                .name(tool.name())
                .title(tool.title())
                .description(tool.description())
                .inputSchema(tool.inputSchema())
                // Everything here reads; nothing writes. ChatGPT requires readOnlyHint to
                // consider a tool as a company-knowledge source, and Claude surfaces it as
                // a safety affordance.
                .annotations(McpSchema.ToolAnnotations.builder()
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build());
        Map<String, Object> outputSchema = tool.outputSchema();
        if (outputSchema != null) {
            builder.outputSchema(outputSchema);
        }
        return new McpServerFeatures.SyncToolSpecification(builder.build(),
                (exchange, request) -> execute(tool, request));
    }

    private McpSchema.CallToolResult execute(McpTool tool, McpSchema.CallToolRequest request) {
        try {
            Map<String, Object> result = tool.call(request.arguments() == null
                    ? Map.of()
                    : request.arguments());
            return McpSchema.CallToolResult.builder()
                    .structuredContent(result)
                    .addTextContent(mapper.writeValueAsString(result))
                    .build();
        } catch (IllegalArgumentException ex) {
            // A bad argument is the caller's to fix, so it comes back as a tool error the
            // model can read and retry from, not as a transport-level failure.
            LOGGER.debug("MCP tool {} rejected arguments: {}", tool.name(), ex.getMessage());
            return error(ex.getMessage());
        } catch (Exception ex) {
            LOGGER.error("MCP tool {} failed.", tool.name(), ex);
            return error("The " + tool.name() + " tool failed. This is a server-side error, "
                    + "not a statement about the corpus - do not report it as 'not found'.");
        }
    }

    private McpSchema.CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder()
                .isError(true)
                .addTextContent(message)
                .build();
    }
}
