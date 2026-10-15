package com.rewayaat.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Puts a link on each name in an isnād, where the resolver decided whose name it is.
 *
 * <p>The spans are precomputed and stored on the narration as {@code chain_links}: start and
 * end offsets into the chain text, the text they cover, and the person they belong to. The
 * offsets were taken from {@link HadithDisplaySegmenter}'s own output, which is what this
 * renders into, but the two are computed at different times and the text is the contract: a
 * span whose covered text no longer matches is dropped rather than trusted. An offset that has
 * drifted would otherwise slice a name in half or wrap a link around the wrong word.
 *
 * <p>Everything outside a link is escaped. The chain reached the page unescaped before this
 * class existed, since it is our own corpus text, but injecting markup into it makes the
 * distinction matter.
 */
public final class ChainLinks {

    private static final String CLASS = "chain-name";

    private ChainLinks() {
    }

    /** The chain as HTML, with a link on every name whose man is known. */
    public static String render(String chain, Object stored, String prefix) {
        if (chain == null || chain.isBlank()) {
            return chain == null ? "" : chain;
        }
        List<Span> spans = spans(chain, stored);
        if (spans.isEmpty()) {
            return escape(chain);
        }
        StringBuilder out = new StringBuilder(chain.length() + spans.size() * 48);
        int at = 0;
        for (Span span : spans) {
            out.append(escape(chain.substring(at, span.start)));
            out.append("<a class=\"").append(CLASS).append("\" href=\"")
                    .append(escape(prefix == null ? "" : prefix)).append("/narrator/")
                    .append(escape(span.person))
                    .append(span.slug.isEmpty() ? "" : "/" + escape(span.slug))
                    .append("\">")
                    .append(escape(chain.substring(span.start, span.end)))
                    .append("</a>");
            at = span.end;
        }
        out.append(escape(chain.substring(at)));
        return out.toString();
    }

    private static List<Span> spans(String chain, Object stored) {
        List<Span> spans = new ArrayList<>();
        if (!(stored instanceof List<?> rows)) {
            return spans;
        }
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> link)) {
                continue;
            }
            int start = number(link.get("s"));
            int end = number(link.get("e"));
            String person = text(link.get("p"));
            String covers = text(link.get("t"));
            if (person.isEmpty() || start < 0 || end <= start || end > chain.length()) {
                continue;
            }
            if (!covers.isEmpty() && !chain.substring(start, end).equals(covers)) {
                continue;  // the text moved under the offsets; leave this name unlinked
            }
            spans.add(new Span(start, end, person, text(link.get("g"))));
        }
        spans.sort(Comparator.comparingInt(span -> span.start));
        List<Span> kept = new ArrayList<>(spans.size());
        int reached = 0;
        for (Span span : spans) {
            if (span.start >= reached) {
                kept.add(span);
                reached = span.end;
            }
        }
        return kept;
    }

    private static int number(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String escape(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private record Span(int start, int end, String person, String slug) {
    }
}
