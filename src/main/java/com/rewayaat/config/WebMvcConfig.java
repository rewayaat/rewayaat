package com.rewayaat.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import java.util.Locale;

/**
 * Internationalisation configuration for Arabic / English support.
 *
 * <p>Locale resolution order:
 * <ol>
 *   <li>Cookie named {@code lang} (set by the language switcher)</li>
 *   <li>URL path prefix {@code /ar/} (handled by ArabicLocaleInterceptor)</li>
 *   <li>Accept-Language header</li>
 *   <li>Fallback: English</li>
 * </ol>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    static final String LANG_COOKIE = "lang";

    private final ArabicLocaleInterceptor arabicLocaleInterceptor;

    public WebMvcConfig(ArabicLocaleInterceptor arabicLocaleInterceptor) {
        this.arabicLocaleInterceptor = arabicLocaleInterceptor;
    }

    @Bean
    public MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setUseCodeAsDefaultMessage(true);
        return source;
    }

    @Bean
    public LocaleResolver localeResolver() {
        CookieLocaleResolver resolver = new CookieLocaleResolver(LANG_COOKIE);
        resolver.setDefaultLocale(Locale.ENGLISH);
        resolver.setCookieMaxAge(365 * 24 * 60 * 60); // 1 year
        resolver.setCookiePath("/");
        return resolver;
    }

    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName("lang");
        return interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Arabic /ar/ prefix interceptor must run FIRST (before locale change)
        registry.addInterceptor(arabicLocaleInterceptor)
                .addPathPatterns("/ar/**", "/ar");
        registry.addInterceptor(localeChangeInterceptor());
    }
}
