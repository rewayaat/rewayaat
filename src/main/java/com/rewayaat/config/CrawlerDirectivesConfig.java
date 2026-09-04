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
 * <p>{@code welcome.html} is a fragment the home page pulls in over XHR — it has no
 * {@code <html>}, {@code <head>} or {@code <title>} — yet it answers 200 on its own
 * URL, so a crawler can index it as a thin duplicate of the home page hero. The
 * {@code error/*} pages are reachable directly for the same reason.
 *
 * <p>Both are handled with a header rather than a {@code Disallow} in robots.txt,
 * because Disallow is the wrong tool twice over here: Googlebot renders the home
 * page and fetches the fragment while doing so, so blocking it would hide the hero
 * from the index, and a blocked URL can still be indexed from a link — a crawler
 * that cannot fetch the page never sees the noindex telling it to stay away.
 */
@Configuration
public class CrawlerDirectivesConfig {

    private static final List<String> NOINDEX_PATHS = List.of("/welcome.html", "/error/*");

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
