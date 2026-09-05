package com.rewayaat.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Mounts the MCP server inside the existing application.
 *
 * <p>It runs in-process rather than as a separate service because the tools need the data,
 * not the API: {@code find_similar} reads a nested field that {@code /v1/narrations} does
 * not expose on its own terms, and shaping a response after 42 KB has already crossed a
 * network hop saves nothing. The tools call the same Elasticsearch client the website uses.
 *
 * <h2>Two transports, deliberately</h2>
 *
 * <p>Streamable HTTP at {@code /mcp} is the current transport and what both Claude and
 * ChatGPT prefer. The legacy HTTP+SSE pair at {@code /mcp/sse} + {@code /mcp/message} is
 * deprecated but still what several clients reach for first, and OpenAI's own connector
 * documentation still shows {@code /sse/} URLs. Running both costs one extra servlet.
 *
 * <h2>No authentication</h2>
 *
 * <p>The corpus is public and every tool is read-only, so there is no user identity to
 * establish and nothing to authorise. Claude's connector guidance is explicit that a server
 * exposing only public, non-sensitive operations may skip OAuth, and skipping it removes the
 * single most common reason a connector fails to install. Abuse control belongs at the
 * ingress, where the rest of the site's rate limiting already lives.
 */
@Configuration
public class McpServerConfig {

    /** Path clients point at. Kept off {@code /v1/} so robots.txt's Disallow stays meaningful. */
    public static final String STREAMABLE_ENDPOINT = "/mcp";
    private static final String SSE_ENDPOINT = "/mcp/sse";
    private static final String SSE_MESSAGE_ENDPOINT = "/mcp/message";

    private static final String SERVER_NAME = "rewayaat";
    private static final String SERVER_VERSION = "1.0";

    /**
     * Shown to the user in the client's connector UI and prepended to the model's context.
     * It is the one place we can set expectations before any tool is called, so it states
     * the corpus boundary rather than advertising features.
     */
    private static final String INSTRUCTIONS = """
            Rewayaat indexes %d Shia hadith books - %d narrations - with Arabic and English \
            text, chapter structure, gradings, LLM-judged similarity links between \
            narrations, and Qur'anic verse connections.

            Two things this corpus does that a web search cannot. It answers exhaustively - \
            a chapter result carries its true size, so "these are all of them" is a fact \
            rather than an impression. And it answers in the negative, because the corpus is \
            closed and known.

            %s

            Cite narrations by the url field in every result. Do not cite by number alone - \
            the numbering here follows this edition and may not match a printed one.\
            """.formatted(CorpusScope.BOOK_COUNT, 32519, CorpusScope.SCOPE_SENTENCE);

    /**
     * Keeps an idle server-to-client stream from being closed underneath us. A client holds a
     * GET open to receive messages, and both nginx in front of this and whatever sits in front
     * of that will drop a connection that goes quiet - our own ingress at 300s. A comment
     * frame every 30s is cheap and keeps the path open.
     */
    private static final Duration KEEP_ALIVE = Duration.ofSeconds(30);

    @Bean
    public McpJsonMapper mcpJsonMapper(ObjectMapper objectMapper) {
        return new JacksonMcpJsonMapper(objectMapper);
    }

    @Bean
    public HttpServletStreamableServerTransportProvider mcpStreamableTransport(McpJsonMapper jsonMapper) {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(jsonMapper)
                .mcpEndpoint(STREAMABLE_ENDPOINT)
                // The default validator allowlists Origin and Host to stop a web page in a
                // user's browser from driving a server bound to their loopback interface.
                // This server is public, unauthenticated and read-only, so there is no
                // ambient authority for such a page to borrow - and an allowlist here would
                // reject the remote clients we exist to serve, whose Host is our own
                // ingress hostname and whose Origin we do not control.
                .securityValidator(ServerTransportSecurityValidator.NOOP)
                .keepAliveInterval(KEEP_ALIVE)
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpStreamableServlet(
            HttpServletStreamableServerTransportProvider transport) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(transport, STREAMABLE_ENDPOINT);
        registration.setName("mcpStreamableTransport");
        registration.setAsyncSupported(true);
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean
    public HttpServletSseServerTransportProvider mcpSseTransport(McpJsonMapper jsonMapper) {
        return HttpServletSseServerTransportProvider.builder()
                .jsonMapper(jsonMapper)
                .sseEndpoint(SSE_ENDPOINT)
                .messageEndpoint(SSE_MESSAGE_ENDPOINT)
                .securityValidator(ServerTransportSecurityValidator.NOOP)
                .keepAliveInterval(KEEP_ALIVE)
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletSseServerTransportProvider> mcpSseServlet(
            HttpServletSseServerTransportProvider transport) {
        ServletRegistrationBean<HttpServletSseServerTransportProvider> registration =
                new ServletRegistrationBean<>(transport, SSE_ENDPOINT, SSE_MESSAGE_ENDPOINT);
        registration.setName("mcpSseTransport");
        registration.setAsyncSupported(true);
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean
    public McpSyncServer mcpStreamableServer(HttpServletStreamableServerTransportProvider transport,
                                             McpToolCatalog catalog,
                                             McpJsonMapper jsonMapper) {
        return build(McpServer.sync(transport), catalog, jsonMapper);
    }

    @Bean
    public McpSyncServer mcpSseServer(HttpServletSseServerTransportProvider transport,
                                      McpToolCatalog catalog,
                                      McpJsonMapper jsonMapper) {
        return build(McpServer.sync(transport), catalog, jsonMapper);
    }

    private McpSyncServer build(McpServer.SyncSpecification<?> spec,
                                McpToolCatalog catalog,
                                McpJsonMapper jsonMapper) {
        return spec
                .serverInfo(new McpSchema.Implementation(SERVER_NAME, SERVER_VERSION))
                .jsonMapper(jsonMapper)
                .instructions(INSTRUCTIONS)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(catalog.specifications())
                .build();
    }
}
