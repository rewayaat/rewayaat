package com.rewayaat;

import io.sentry.Sentry;
import io.sentry.SentryClient;
import io.sentry.SentryClientFactory;
import io.sentry.event.UserBuilder;

/**
 * Util class for interacting with Sentry.
 */
public class SentryUtil {

    private static SentryClient sentry;

    private static void loadSentryClient() {

        String dsn = "https://b0e8263fd0ca4b88b2c51043a51df738:2669f8ad7a8b491a8d484dfe38fce230@sentry.io/289790";
        Sentry.init(dsn);
        sentry = SentryClientFactory.sentryClient();
    }

    public static void logException(String message, Throwable e) {

        if (sentry == null) {
            loadSentryClient();
        }

        // Set the user in the current context.
        Sentry.getContext().setUser(
                new UserBuilder().setEmail("rewayaat.org@gmail.com").build()
        );

        Sentry.getContext().addExtra("message", message);
        /*
        This sends a simple event to Sentry using the statically stored instance
        that was created in the ``main`` method.
        */
        Sentry.capture(e);
    }
}
