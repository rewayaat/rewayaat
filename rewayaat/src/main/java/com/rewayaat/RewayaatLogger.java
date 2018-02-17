package com.rewayaat;

import org.apache.log4j.Logger;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Logs Errors to Sentry.
 */
public class RewayaatLogger extends Logger {
    public RewayaatLogger(String name) {
        super(name);
    }

    public static String returnStackTraceAsString(Throwable t) {
        try {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            sw.flush();

            return sw.toString();
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    public static String getRootExceptionMessage(Throwable e) {
        String rootMessage = null;

        if (e == null) {
            return null;
        }
        if (e.getCause() != null) {
            rootMessage = getRootExceptionMessage(e.getCause());
        } else {
            return e.getMessage();
        }
        return rootMessage;
    }

    @Override
    public void error(Object errorMessage, Throwable t) {
        String sanitizedRootCause = "";
        if (getRootExceptionMessage(t.getCause()) != null) {
            sanitizedRootCause = getRootExceptionMessage(t.getCause()).replace("\"", "");
        } else {
            sanitizedRootCause = null;
        }
        String sanitizedThrowable = returnStackTraceAsString(t).replace("\"", "");
        String sanitizedErrorMessage = ((String) errorMessage).replace("\"", "");
        SentryUtil.logException((String) errorMessage, t);
        super.error(sanitizedErrorMessage + " - (Root cause:" + sanitizedRootCause + ") " + sanitizedThrowable);
    }
}