package com.rewayaat.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.SimpleLocaleContext;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.LocaleContextResolver;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import java.util.Locale;

/**
 * Interceptor that detects the {@code /ar/} URL prefix and sets the locale to
 * Arabic. The prefix is stripped so that existing controller mappings continue
 * to work unchanged ({@code /ar/hadith/123} → locale=ar, forwards to
 * {@code /hadith/123}).
 *
 * <p>Also exposes a {@code prefixAr} model attribute so templates can build
 * correct Arabic URLs.
 */
@Component
public class ArabicLocaleInterceptor implements HandlerInterceptor {

    private static final String ARABIC_PREFIX = "/ar";
    private static final Locale ARABIC = Locale.forLanguageTag("ar");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        String uri = request.getRequestURI();

        if (uri.startsWith(ARABIC_PREFIX + "/") || uri.equals(ARABIC_PREFIX)) {
            // Store flag for the LocaleResolver to pick up
            request.setAttribute("arabicLocale", true);

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
            // Expose whether we're in Arabic mode for template URL building
            boolean isArabic = ARABIC.equals(request.getLocale());
            modelAndView.addObject("isArabic", isArabic);
            modelAndView.addObject("arPrefix", isArabic ? "/ar" : "");
        }
    }
}
