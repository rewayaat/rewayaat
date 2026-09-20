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

            // Survives the forward: a forward reuses the request object, so the handler
            // and every controller downstream read the locale from here rather than from
            // a URI that no longer mentions it.
            http.setAttribute(PageLocale.REQUEST_ATTRIBUTE, PageLocale.ARABIC);

            // Query parameters survive a forward, so ?q= and ?page= are not lost here.
            http.getRequestDispatcher(PageLocale.stripArabicPrefix(uri)).forward(request, response);
        };

        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/ar", "/ar/*");
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
