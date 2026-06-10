package com.rewayaat.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.LocaleResolver;

import java.util.Locale;

/**
 * Interceptor that detects the {@code /ar/} URL prefix and sets the locale to
 * Arabic. The prefix is stripped so that existing controller mappings continue
 * to work unchanged ({@code /ar/hadith/123} → locale=ar, forwards to
 * {@code /hadith/123}).
 *
 * <p>The locale is set on the {@link LocaleResolver} before forwarding, so
 * Thymeleaf resolves {@code #{...}} to Arabic and {@code #locale} returns
 * Arabic on the forwarded request. The {@link #postHandle} method runs on
 * every request and sets the {@code isArabic} / {@code arPrefix} model
 * attributes by checking the current locale.
 */
@Component
public class ArabicLocaleInterceptor implements HandlerInterceptor {

    private static final String ARABIC_PREFIX = "/ar";
    private static final Locale ARABIC = Locale.forLanguageTag("ar");

    private final LocaleResolver localeResolver;

    public ArabicLocaleInterceptor(LocaleResolver localeResolver) {
        this.localeResolver = localeResolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        String uri = request.getRequestURI();

        if (uri.startsWith(ARABIC_PREFIX + "/") || uri.equals(ARABIC_PREFIX)) {
            // Set locale on the resolver BEFORE forwarding so the forwarded
            // request inherits it. This ensures Thymeleaf #{...} resolves to
            // Arabic and #locale returns Arabic.
            localeResolver.setLocale(request, response, ARABIC);

            // Strip /ar prefix and forward to the real handler
            String newPath = uri.substring(ARABIC_PREFIX.length());
            if (newPath.isEmpty()) {
                newPath = "/";
            }
            request.getRequestDispatcher(newPath).forward(request, response);
            return false; // stop current chain, forwarded request takes over
        }

        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response,
                           Object handler, ModelAndView modelAndView) throws Exception {
        if (modelAndView != null) {
            // Check locale (works for both /ar/ forwarded requests and regular
            // requests where locale was set via cookie or Accept-Language).
            boolean isArabic = ARABIC.equals(localeResolver.resolveLocale(request));
            modelAndView.addObject("isArabic", isArabic);
            modelAndView.addObject("arPrefix", isArabic ? "/ar" : "");
        }
    }
}
