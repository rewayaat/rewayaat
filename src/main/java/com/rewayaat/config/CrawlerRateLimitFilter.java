package com.rewayaat.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Holds one crawler, named by its user agent, to a number of requests a minute on this pod.
 *
 * <p>The ingress limits each address to 50 requests a minute, which a crawler that spreads
 * itself over many addresses never reaches. On 2026-09-30 {@code meta-externalagent} made
 * 10,850 requests in two and a half hours from 69 addresses, about two a minute each and 120 a
 * minute together, and took three quarters of the time this service spent answering anyone.
 * The heap sat above 90% for hours. Nothing about any one address was unusual; the crawler as a
 * whole was the load, so the crawler as a whole is what is counted here.
 *
 * <p>A crawler over its budget is answered 429 with a {@code Retry-After}, which a crawler
 * that announces itself honours, and the answer costs nothing to produce. It is limited and
 * not blocked, so the pages stay reachable to it at a pace the service can afford.
 * {@code /robots.txt} is never counted: a crawler that cannot read it has no way to learn
 * what it may fetch.
 *
 * <p>The count is per pod and in memory. Two pods each allowing ten is twenty a minute in
 * total, which is what the budget is set for; a count shared between pods would cost a
 * network call on every request to make a limit more exact than it needs to be.
 */
public class CrawlerRateLimitFilter implements Filter {

    private static final long WINDOW_MS = 60_000L;

    private final String userAgentToken;
    private final int perMinute;
    private final LongSupplier clock;
    /** The minute being counted, in the high bits, and the requests seen in it, in the low. */
    private final AtomicLong window = new AtomicLong();

    /**
     * @param userAgentToken what a user agent must contain, in any case, to be counted
     * @param perMinute      how many requests a minute this pod answers for it; zero or less
     *                       turns the limit off
     */
    public CrawlerRateLimitFilter(String userAgentToken, int perMinute) {
        this(userAgentToken, perMinute, System::currentTimeMillis);
    }

    CrawlerRateLimitFilter(String userAgentToken, int perMinute, LongSupplier clock) {
        this.userAgentToken = userAgentToken == null ? "" : userAgentToken.toLowerCase(Locale.ROOT);
        this.perMinute = perMinute;
        this.clock = clock;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        if (!counted(http)) {
            chain.doFilter(request, response);
            return;
        }
        long now = clock.getAsLong();
        if (admit(now)) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletResponse out = (HttpServletResponse) response;
        out.setStatus(429);
        out.setHeader("Retry-After", String.valueOf(Math.max(1L, (WINDOW_MS - now % WINDOW_MS + 999L) / 1000L)));
        out.setContentType("text/plain");
        out.getWriter().write("Too many requests from this crawler; retry later.\n");
    }

    private boolean counted(HttpServletRequest request) {
        if (perMinute <= 0 || userAgentToken.isEmpty() || "/robots.txt".equals(request.getRequestURI())) {
            return false;
        }
        String userAgent = request.getHeader("User-Agent");
        return userAgent != null && userAgent.toLowerCase(Locale.ROOT).contains(userAgentToken);
    }

    /** Counts one request in the minute {@code now} falls in, and says whether it is within budget. */
    private boolean admit(long now) {
        long minute = now / WINDOW_MS;
        while (true) {
            long seen = window.get();
            long next = (seen >>> 20) != minute ? (minute << 20) + 1
                    : (seen & 0xFFFFF) == 0xFFFFF ? seen : seen + 1;
            if (window.compareAndSet(seen, next)) {
                return (next & 0xFFFFF) <= perMinute;
            }
        }
    }
}
