package com.rewayaat.config;

import java.util.List;
import java.util.regex.Pattern;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.ResourceUrlEncodingFilter;
import org.springframework.web.servlet.resource.VersionResourceResolver;

/**
 * Fingerprints the asset URLs the templates emit, so a cached file can never be
 * paired with newer markup.
 *
 * <p>Each template used to carry a hand-maintained {@code ?v=N} on every stylesheet
 * and script. The server-rendered HTML is never cached, so forgetting one of those
 * bumps ships new markup to a browser still running the previous JavaScript — which
 * is how the results page went blank in production. Here the query-free URL carries
 * a hash of the file's own bytes, so it changes if and only if the file does, and
 * there is nothing left to remember.
 *
 * <p>Only the four asset directories are handled here. The JSON and HTML at the
 * static root ({@code /taxonomy.json} and friends) are fetched by fixed path at
 * runtime and stay on the Spring Boot defaults.
 */
@Configuration
public class StaticAssetConfig implements WebMvcConfigurer {

    private static final List<String> ASSET_DIRS = List.of("css", "js", "img", "fonts");

    /** Matches the {@code name-<md5>.ext} form that {@link VersionResourceResolver} produces. */
    private static final Pattern FINGERPRINTED = Pattern.compile("-[0-9a-f]{32}\\.[A-Za-z0-9]+$");

    private static final String CACHE_FOREVER = "max-age=31536000, public, immutable";

    private final boolean devMode;

    public StaticAssetConfig(Environment environment) {
        this.devMode = environment.acceptsProfiles(Profiles.of("dev"));
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        for (String dir : ASSET_DIRS) {
            // The resolved hash is memoised outside dev only, so that editing a file
            // locally still yields a fresh URL on the next reload.
            registry.addResourceHandler("/" + dir + "/**")
                    .addResourceLocations("classpath:/static/" + dir + "/")
                    .resourceChain(!devMode)
                    .addResolver(new VersionResourceResolver().addContentVersionStrategy("/**"));
        }
    }

    /**
     * Rewrites the plain paths in {@code th:href="@{/css/...}"} to their fingerprinted
     * form. Spring Boot registers this filter itself only when the resource chain is
     * switched on through properties, and the chain above is configured in code.
     */
    @Bean
    public FilterRegistrationBean<ResourceUrlEncodingFilter> resourceUrlEncodingFilter() {
        return new FilterRegistrationBean<>(new ResourceUrlEncodingFilter());
    }

    /**
     * Caches fingerprinted assets forever, since such a URL can only ever name one
     * version of the file.
     *
     * <p>The header is deliberately not set on the resource handler itself: the plain
     * {@code /css/manuscript.css} path stays reachable, and the static HTML pages that
     * Thymeleaf never sees ({@code signin.html}, {@code error/*.html}) still link to it
     * with a hand-written {@code ?v=N}. Freezing those for a year would turn a forgotten
     * bump into a permanent one, so they keep the browser's default heuristic caching.
     */
    @Bean
    public FilterRegistrationBean<Filter> fingerprintedAssetCacheFilter() {
        Filter filter = (request, response, chain) -> {
            String path = ((HttpServletRequest) request).getRequestURI();
            if (!devMode && FINGERPRINTED.matcher(path).find()) {
                ((HttpServletResponse) response).setHeader(HttpHeaders.CACHE_CONTROL, CACHE_FOREVER);
            }
            chain.doFilter(request, response);
        };
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
        ASSET_DIRS.forEach(dir -> registration.addUrlPatterns("/" + dir + "/*"));
        return registration;
    }
}
