package com.rewayaat.service;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The one way to read a string a reader will see.
 *
 * <p>Every such string lives in {@code src/main/resources/i18n} — {@code messages.properties}
 * and {@code messages_ar.properties} — and nowhere else. Templates reach them with
 * {@code th:text="#{key}"}, browser code with {@code t('key', 'English fallback')}, and
 * anything on the server with this. Keeping one catalogue is what makes translating the
 * site a matter of filling in a file rather than re-reading the codebase: the last two
 * sweeps found English hardcoded in templates, inside JavaScript strings, and in the
 * messages the API hands back, each of which looked fine until somebody read the Arabic
 * page.
 *
 * <p>{@link #say} resolves in the language of the request being served. That is the right
 * default for anything the caller is about to show. It is not the right default for mail:
 * a message follows the click, but mail follows the reader, and the reader's own language
 * is stored on their account. Use {@link #in} with that.
 *
 * <p>A missing key returns the key itself rather than throwing. A key that shows up in the
 * interface is a loud, harmless bug; a 500 on a page because somebody renamed a string is
 * not.
 */
@Service
public class UiMessages {

    private final MessageSource messages;

    public UiMessages(MessageSource messages) {
        this.messages = messages;
    }

    /** A string in the language of the request being served. */
    public String say(String key, Object... args) {
        return in(current(), key, args);
    }

    /** A string in a language chosen by the caller, such as an account's own. */
    public String in(PageLocale locale, String key, Object... args) {
        return messages.getMessage(key, args, key, locale.locale());
    }

    /**
     * The language of the request being served, English outside one.
     *
     * <p>A background job or a test has nobody to show a message to, so there is nothing
     * to resolve against and English is the answer.
     */
    public PageLocale current() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes == null ? PageLocale.ENGLISH : PageLocale.of(attributes.getRequest());
    }
}
