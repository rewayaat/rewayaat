package com.rewayaat.config;

import java.util.List;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Marks the pages that serve a purpose but should never appear in search results.
 *
 * <p>The {@code error/*} pages are reachable on their own URLs and answer 200 there, so
 * a crawler can index them as thin content.
 *
 * <p>Handled with a header rather than a {@code Disallow} in robots.txt, because a
 * blocked URL can still be indexed from a link — a crawler that cannot fetch the page
 * never sees the noindex telling it to stay away.
 *
 * <p>{@code welcome.html} used to be listed here too. It was the home page body, pulled
 * in over XHR, which is why the home page served no content to a crawler; the body is
 * rendered by the server now and the file is gone.
 *
 * <p>{@code /edit} and {@code /signin.html} were {@code Disallow}ed in robots.txt instead,
 * and Search Console duly reported them under "Indexed, though blocked by robots.txt" —
 * the precise failure the paragraph above describes. Both answer 200 with about seventy
 * words, and {@code /edit} is linked from every card's action rail, so blocking the fetch
 * only hid the noindex from the crawler that needed to read it.
 */
@Configuration
public class CrawlerDirectivesConfig {

    // /auth/verify and /auth/reset carry a one-time token and redirect to the sign-in
    // page. They became Arabic-capable so that a link in an Arabic mail keeps the reader
    // on the Arabic site, and PageLocale is also what the sitemap reads — so without
    // this they would be advertised for crawling, which is the last thing a tokenised
    // URL should be. SitemapIntegrationTest caught exactly that.
    private static final List<String> NOINDEX_PATHS = List.of(
            "/error/*", "/edit", "/signin.html", "/auth/verify", "/auth/reset");

    /** While the Arabic site is being released, its whole tree joins that list. */
    private static final List<String> ARABIC_PATHS = List.of("/ar", "/ar/*");

    /** Runs before {@code ArabicSiteConfig.arabicPrefixFilter}, which is one higher. */
    static final int ARABIC_NOINDEX_ORDER = 10;

    private final ArabicIndexing arabicIndexing;

    public CrawlerDirectivesConfig(ArabicIndexing arabicIndexing) {
        this.arabicIndexing = arabicIndexing;
    }

    @Bean
    public FilterRegistrationBean<Filter> noindexFilter() {
        Filter filter = (request, response, chain) -> {
            ((HttpServletResponse) response).setHeader("X-Robots-Tag", "noindex");
            chain.doFilter(request, response);
        };
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
        // Ahead of the prefix filter, which answers /ar by forwarding to the English
        // handler. Both default to the lowest precedence, so without an explicit order
        // the header would be set, or not, depending on which happened to run first.
        registration.setOrder(ARABIC_NOINDEX_ORDER);
        NOINDEX_PATHS.forEach(registration::addUrlPatterns);
        // The release switch. See ArabicIndexing: the Arabic site is reachable and
        // testable in production before it is indexable, and this is the half of that
        // which keeps it out of results. Registered on the prefixed paths, so it marks
        // the response before ArabicSiteConfig's filter forwards to the English handler.
        if (!arabicIndexing.isIndexable()) {
            ARABIC_PATHS.forEach(registration::addUrlPatterns);
        }
        return registration;
    }

    /**
     * Limits Meta's AI crawler as a whole, ahead of everything else a request passes through.
     * See {@link CrawlerRateLimitFilter} for why an address limit does not reach it.
     *
     * @param perMinute requests a minute each pod answers for it; zero turns the limit off
     */
    @Bean
    public FilterRegistrationBean<Filter> metaCrawlerRateLimitFilter(
            @Value("${rewayaat.crawlers.meta-externalagent.per-minute:10}") int perMinute) {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(
                new CrawlerRateLimitFilter("meta-externalagent", perMinute));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
