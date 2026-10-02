package com.rewayaat.config;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.List;

/**
 * Sends readers outside Canada from the Academy's host to the canonical one.
 *
 * <p>A 301 is the only thing that carries a page's accumulated ranking signals - including
 * the value of every external link pointing at the old URL - to a new address. The
 * canonical tag both hosts already serve consolidates more slowly and may be declined
 * outright while the old host holds the history. This moves the part of the audience that
 * can be moved, and the crawler with it.
 *
 * <p>Restricted to visitors outside Canada so that the Academy's own community, which is
 * in Ontario, keeps reaching the site at the address it knows. Googlebot crawls
 * predominantly from the United States, so it is redirected and the index follows.
 *
 * <p>This is a site move, not cloaking: the response depends on where a request comes
 * from, never on whether it looks like a crawler. A reader in the United States and
 * Googlebot in the United States get the identical 301.
 *
 * <p>Two things it must not touch, both of which would fail silently and expensively:
 *
 * <ul>
 *   <li><strong>{@code /.well-known/}</strong> - Let's Encrypt validates this host over
 *       HTTP from outside Canada. Redirect the challenge and the certificate for
 *       hadith.academyofislam.com stops renewing, and TLS there breaks about sixty days
 *       later with nothing in the application to explain why.
 *   <li><strong>{@code /mcp}</strong> - a connector URL is configured once and lives in
 *       someone's client indefinitely. MCP clients vary in whether they follow redirects
 *       on a POST, so the endpoint answers where it was configured to answer.
 * </ul>
 */
@Configuration
public class AcademyHostRedirectConfig {

    private static final String ACADEMY_HOST = "hadith.academyofislam.com";
    private static final String CANONICAL_ORIGIN = "https://rewayaat.info";

    /** Paths that answer on the Academy's host wherever the request comes from. */
    private static final List<String> NEVER_REDIRECTED = List.of(
            "/.well-known/", "/mcp", "/actuator", "/health");

    private final CanadianAddresses canada;

    public AcademyHostRedirectConfig(CanadianAddresses canada) {
        this.canada = canada;
    }

    /**
     * The address nginx recorded for the client. {@code getRemoteAddr} is the ingress pod
     * behind a proxy, so the forwarded headers are read directly rather than relying on
     * {@code server.use-forward-headers}, which is a Spring Boot 2 property that Boot 3
     * ignores.
     *
     * <p>A client can put whatever it likes in X-Forwarded-For. That is tolerable here
     * because the worst a forged value buys is staying on the host being retired.
     */
    static String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        return request.getRemoteAddr();
    }

    static boolean isExempt(String path) {
        return NEVER_REDIRECTED.stream().anyMatch(path::startsWith);
    }

    @Bean
    public FilterRegistrationBean<Filter> academyHostRedirectFilter() {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
        registration.setFilter((request, response, chain) -> {
            HttpServletRequest http = (HttpServletRequest) request;
            String path = http.getRequestURI();
            boolean redirect = ACADEMY_HOST.equalsIgnoreCase(http.getServerName())
                    && !isExempt(path)
                    && !canada.contains(clientAddress(http));
            if (redirect) {
                String query = http.getQueryString();
                HttpServletResponse out = (HttpServletResponse) response;
                out.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
                out.setHeader("Location", CANONICAL_ORIGIN + path + (query == null ? "" : "?" + query));
                // The response varies by where the request came from, so a shared cache
                // must not serve one visitor's redirect to another.
                out.setHeader("Cache-Control", "private, no-store");
                return;
            }
            chain.doFilter(request, response);
        });
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
