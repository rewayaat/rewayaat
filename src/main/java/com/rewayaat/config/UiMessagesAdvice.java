package com.rewayaat.config;

import com.rewayaat.service.PageLocale;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Hands the page's messages to the browser as {@code window.I18N}.
 *
 * <p>The search application builds most of what a reader sees - results, filters, the
 * collection dialogs - in the browser, so those strings never pass through Thymeleaf and
 * cannot use {@code #{...}}. They read from this map instead.
 *
 * <p>This is for interactive chrome only. Anything a crawler has to read is rendered by
 * the server (invariant 8): a string that only exists in {@code window.I18N} is invisible
 * to a client that does not run scripts, so it must never carry indexable content.
 *
 * <p>Every key is published rather than a curated subset. The bundle is a couple of
 * hundred short strings, and a filtered list is one more thing to keep in step with the
 * JavaScript - the failure mode being a silently missing label rather than an error.
 */
@ControllerAdvice
public class UiMessagesAdvice {

    private static final String BASENAME = "messages";

    @ModelAttribute
    public void uiMessages(HttpServletRequest request, Model model) {
        Locale locale = PageLocale.of(request).locale();
        model.addAttribute("uiMessages", bundleFor(locale));
    }

    private static Map<String, String> bundleFor(Locale locale) {
        Map<String, String> messages = new HashMap<>();
        try {
            // The English bundle first, so a key the Arabic file has not reached yet falls
            // back to English rather than disappearing from the interface.
            ResourceBundle english = ResourceBundle.getBundle(BASENAME, Locale.ENGLISH);
            for (String key : english.keySet()) {
                messages.put(key, english.getString(key));
            }
            if (!Locale.ENGLISH.getLanguage().equals(locale.getLanguage())) {
                ResourceBundle localised = ResourceBundle.getBundle(BASENAME, locale);
                for (String key : localised.keySet()) {
                    messages.put(key, localised.getString(key));
                }
            }
        } catch (MissingResourceException e) {
            // An empty map leaves the JavaScript on its inline fallbacks.
            return Map.of();
        }
        return messages;
    }
}
