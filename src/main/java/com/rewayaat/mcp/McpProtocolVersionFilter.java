package com.rewayaat.mcp;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Set;

/**
 * Rejects a request that names a protocol revision this server does not speak.
 *
 * <p>The Streamable HTTP spec is unambiguous: "If the server receives a request with an
 * invalid or unsupported {@code MCP-Protocol-Version}, it MUST respond with 400 Bad
 * Request." The SDK's transport does not enforce it - measured against a running server, a
 * request carrying {@code MCP-Protocol-Version: 1999-01-01} was answered 200 - so this
 * filter does.
 *
 * <p>The header is optional, and its absence is not an error: the spec says a server that
 * receives no header should assume 2025-03-26, and both transports here already track the
 * revision negotiated during initialization. Only a header that is present and unrecognised
 * is rejected.
 *
 * <p>Practically no real client trips this, because a client sends back the revision the
 * server itself negotiated. It matters for the case where it is not a real client: a
 * mismatch answered 200 leaves whatever is on the other end to discover the disagreement by
 * misreading a response, which is a worse failure than being told.
 */
public class McpProtocolVersionFilter implements Filter {

    static final String HEADER = "MCP-Protocol-Version";

    /**
     * The revisions this server negotiates, confirmed by asking a running one for each.
     *
     * <p>If the SDK gains a revision, this set has to gain it too, or the server will
     * negotiate a version and then reject requests that name it back. The integration test
     * walks the same list to keep the two honest.
     */
    static final Set<String> SUPPORTED = Set.of(
            "2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25");

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        String version = http.getHeader(HEADER);

        if (version != null && !SUPPORTED.contains(version.trim())) {
            HttpServletResponse out = (HttpServletResponse) response;
            out.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.setContentType("application/json");
            out.getWriter().write("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,"
                    + "\"message\":\"Unsupported MCP-Protocol-Version: " + sanitise(version)
                    + "\"}}");
            return;
        }
        chain.doFilter(request, response);
    }

    /** The header is attacker-controlled and is being echoed into a JSON body. */
    private static String sanitise(String version) {
        String trimmed = version.length() > 32 ? version.substring(0, 32) : version;
        return trimmed.replaceAll("[^A-Za-z0-9._-]", "");
    }
}
