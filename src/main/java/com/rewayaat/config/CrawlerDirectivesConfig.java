package com.rewayaat.config;

import java.util.List;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
 */
@Configuration
public class CrawlerDirectivesConfig {

    private static final List<String> NOINDEX_PATHS = List.of("/error/*");

    @Bean
    public FilterRegistrationBean<Filter> noindexFilter() {
        Filter filter = (request, response, chain) -> {
            ((HttpServletResponse) response).setHeader("X-Robots-Tag", "noindex");
            chain.doFilter(request, response);
        };
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
        NOINDEX_PATHS.forEach(registration::addUrlPatterns);
        return registration;
    }
}
