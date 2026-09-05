package com.rewayaat.mcp.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Argument reading for tools.
 *
 * <p>Every failure here throws {@link IllegalArgumentException}, which the catalog turns into
 * a tool error the model can read and correct - a mistyped argument should come back as a
 * sentence, not as a stack trace or a transport failure.
 */
final class ToolArguments {

    private ToolArguments() {
    }

    static String requiredString(Map<String, Object> arguments, String key) {
        String value = optionalString(arguments, key, "");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("The '" + key + "' argument is required.");
        }
        return value;
    }

    static String optionalString(Map<String, Object> arguments, String key, String fallback) {
        if (arguments == null) {
            return fallback;
        }
        Object raw = arguments.get(key);
        if (raw == null) {
            return fallback;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? fallback : value;
    }

    /** Reads an integer, clamped into {@code [min, max]} so a tool cannot be asked for 10,000 results. */
    static int boundedInt(Map<String, Object> arguments, String key, int fallback, int min, int max) {
        if (arguments == null || arguments.get(key) == null) {
            return fallback;
        }
        Object raw = arguments.get(key);
        int value;
        if (raw instanceof Number number) {
            value = number.intValue();
        } else {
            try {
                value = Integer.parseInt(String.valueOf(raw).trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("The '" + key + "' argument must be a number.");
            }
        }
        return Math.max(min, Math.min(max, value));
    }

    static List<String> stringList(Map<String, Object> arguments, String key) {
        List<String> values = new ArrayList<>();
        if (arguments == null || arguments.get(key) == null) {
            return values;
        }
        Object raw = arguments.get(key);
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                String value = String.valueOf(item).trim();
                if (!value.isEmpty()) {
                    values.add(value);
                }
            }
        } else {
            String value = String.valueOf(raw).trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }
}
