package com.rewayaat.config;

import com.rewayaat.service.PageLocale;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;

import java.util.Locale;

/**
 * Serves the Arabic site from the {@code /ar} prefix.
 *
 * <p>The prefix is stripped and the request forwarded, so {@code /ar/books/al-kafi} is
 * answered by the same handler as {@code /books/al-kafi} and no route has to be declared
 * twice. What differs between the two is the language of the text and the canonical URL,
 * and both follow from {@link PageLocale}, which the forward carries on a request
 * attribute.
 *
 * <p><strong>A filter, not a {@code HandlerInterceptor}.</strong> Interceptors run after
 * handler mapping, and nothing is mapped to {@code /ar/**} - the request would be a 404
 * before an interceptor could rewrite it. A filter runs ahead of the dispatcher servlet,
 * which is the only place the prefix can be removed. Registered for {@code REQUEST} only,
 * the default, so the filter does not run again on the request it forwards and cannot
 * loop.
 */
@Configuration
public class ArabicSiteConfig {

    @Bean
    public FilterRegistrationBean<Filter> arabicPrefixFilter() {
        Filter filter = (request, response, chain) -> {
            HttpServletRequest http = (HttpServletRequest) request;
            String uri = http.getRequestURI();

            if (!PageLocale.isArabicPath(uri)) {
                chain.doFilter(request, response);
                return;
            }

            String target = PageLocale.stripArabicPrefix(uri);
            if (!PageLocale.hasArabicVersion(target)) {
                // Not a page that exists in Arabic. 404 rather than render the English
                // page under an Arabic URL, which would be a duplicate competing with it.
                ((jakarta.servlet.http.HttpServletResponse) response)
                        .sendError(jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND);
                return;
            }

            // Survives the forward: a forward reuses the request object, so the handler
            // and every controller downstream read the locale from here rather than from
            // a URI that no longer mentions it.
            http.setAttribute(PageLocale.REQUEST_ATTRIBUTE, PageLocale.ARABIC);

            // Query parameters survive a forward, so ?q= and ?page= are not lost here.
            http.getRequestDispatcher(target).forward(request, response);
        };

        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
        // After the noindex filter. That one marks the response while the URL still says
        // /ar; this one forwards to a handler that no longer knows it did.
        registration.setOrder(CrawlerDirectivesConfig.ARABIC_NOINDEX_ORDER + 1);
        registration.addUrlPatterns("/ar", "/ar/*");
        return registration;
    }

    /**
     * Marks JSON requests with the language of the page that made them.
     *
     * <p>The API lives outside the {@code /ar} tree — a page at {@code /ar/books/al-kafi}
     * posts to {@code /v1/...}, not to {@code /ar/v1/...} — so the prefix filter never
     * sees these and every message they return came back in English however the reader
     * was browsing.
     *
     * <p>The referrer is the page that made the call, and for a same-origin fetch the
     * browser sends it in full. It is a hint rather than an identity: a reader whose
     * browser withholds it gets English, which is the same answer they got before.
     * Nothing is trusted from it beyond the path prefix, and it is only read for
     * requests that are already same-origin.
     *
     * <p>The caching worry that keeps the page locale in the URL does not apply in the
     * same way here, because these responses are per-reader rather than shared, but the
     * ones this marks say so anyway.
     */
    @Bean
    public FilterRegistrationBean<Filter> apiLocaleFilter() {
        Filter filter = (request, response, chain) -> {
            HttpServletRequest http = (HttpServletRequest) request;
            if (http.getAttribute(PageLocale.REQUEST_ATTRIBUTE) == null
                    && PageLocale.isArabicReferrer(http.getHeader("Referer"), http)) {
                http.setAttribute(PageLocale.REQUEST_ATTRIBUTE, PageLocale.ARABIC);
                ((jakarta.servlet.http.HttpServletResponse) response).addHeader("Vary", "Referer");
            }
            chain.doFilter(request, response);
        };

        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/v1/*");
        return registration;
    }

    /**
     * Resolves the locale from the URL and nothing else.
     *
     * <p>Spring Boot's default is a {@code AcceptHeaderLocaleResolver}, and the usual
     * alternative is a cookie. Both make the language depend on who is asking: a shared
     * cache would hand one visitor's language to the next, and a reader who opened one
     * Arabic page would then see Arabic on English URLs - two pages, same content, each
     * claiming to be canonical. The URL is the only input here, so a given URL renders
     * the same bytes for everyone.
     */
    @Bean
    public LocaleResolver localeResolver() {
        return new LocaleResolver() {
            @Override
            public Locale resolveLocale(HttpServletRequest request) {
                return PageLocale.of(request).locale();
            }

            @Override
            public void setLocale(HttpServletRequest request,
                                  jakarta.servlet.http.HttpServletResponse response,
                                  Locale locale) {
                // Nothing to set. The URL already said which language this is, and a
                // request that wants the other one is a request for a different URL.
                throw new UnsupportedOperationException(
                        "the locale comes from the URL prefix; link to " + PageLocale.ARABIC_PREFIX
                                + " instead of setting it");
            }
        };
    }
}
