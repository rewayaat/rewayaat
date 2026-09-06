package com.rewayaat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.core.HadithDisplaySegmenter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shapes one narration for the shared hadith card.
 *
 * <p>Lives here rather than in a controller because three surfaces render the same
 * card: chapter pages, the narration's own page, and — through the same class names —
 * the search results. Building the model in two places is how the surfaces started to
 * drift the first time.
 */
@Component
public class HadithCardFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(HadithCardFactory.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final BookCatalog catalog;
    private final TopicLabelSource topicLabels;

    public HadithCardFactory(BookCatalog catalog, TopicLabelSource topicLabels) {
        this.catalog = catalog;
        this.topicLabels = topicLabels;
    }

    /**
     * @param tagBase the page a tag pill filters — the page the card is on. A tag pill
     *                filters in place rather than running a search, because the search
     *                backend has no topic_tags field syntax.
     */
    public Map<String, Object> build(String id, Map<String, Object> source, String tagBase, String baseUrl) {
        Map<String, Object> segmented = new LinkedHashMap<>();
        segmented.put("english", source.get("english"));
        segmented.put("arabic", source.get("arabic"));
        try {
            HadithDisplaySegmenter.enrich(segmented);
        } catch (Exception e) {
            LOGGER.debug("Could not segment narration {}", id, e);
        }

        String book = str(source.get("book"));
        String number = str(source.get("number"));

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("url", "/hadith/" + id);
        row.put("number", number);
        row.put("label", (book + (number.isBlank() ? "" : " #" + number)).trim());
        row.put("englishChain", str(segmented.get("englishChain")));
        row.put("english", firstNonBlank(str(segmented.get("englishContent")), str(source.get("english"))));
        row.put("arabicChain", str(segmented.get("arabicChain")));
        row.put("arabic", firstNonBlank(str(segmented.get("arabicContent")), str(source.get("arabic"))));
        row.put("notes", str(source.get("notes")));
        row.put("metadata", metadataRows(source, number));
        row.put("tags", topicTags(source, tagBase));
        row.put("tagSlugs", tagSlugs(source));
        row.put("similarCount", source.get("llm_similar") instanceof List<?> l ? l.size() : 0);
        row.put("shareUrl", baseUrl + "/hadith/" + id);
        // The generated share card, in the two themes the share menu offers. Dark is the
        // default and is what og:image points at; light exists for the newsletter, whose
        // templates are white.
        row.put("darkCardUrl", "/hadith/" + id + "/card.png");
        row.put("lightCardUrl", "/hadith/" + id + "/card.png?theme=light");
        row.put("reportHref", reportHref(id, book, number, baseUrl));
        row.put("copyJson", write(Map.of(
                "english", stripHtml(str(source.get("english"))),
                "arabic", stripHtml(str(source.get("arabic"))))));
        return row;
    }

    /**
     * The sidecar rows, in the order and with the icons the search card uses.
     *
     * <p>Book, volume and chapter carry a URL; part and section have no page of their
     * own, so they render as plain text rather than as links that lead nowhere useful.
     */
    private List<Map<String, String>> metadataRows(Map<String, Object> source, String number) {
        String book = str(source.get("book"));
        String volume = str(source.get("volume"));
        String chapter = str(source.get("chapter"));

        Optional<BookCatalog.Book> catalogued = catalog.bookByName(book);
        String bookUrl = catalogued.map(b -> "/books/" + b.slug()).orElse(null);
        String volumeUrl = bookUrl == null || volume.isBlank() || catalogued.get().volumes().size() <= 1
                ? null : bookUrl + "/volume/" + encode(volume);
        String chapterUrl = catalog.chapterFor(book, volume, str(source.get("part")),
                str(source.get("section")), chapter).map(BookCatalog.Chapter::url).orElse(null);

        List<Map<String, String>> rows = new ArrayList<>();
        addRow(rows, "fa fa-hashtag", "Hadith #", number, null);
        addRow(rows, "fa fa-book", "Book", book, bookUrl);
        addRow(rows, "fa fa-layer-group", "Volume", volume, volumeUrl);
        addRow(rows, "fa fa-bookmark", "Section", str(source.get("section")), null);
        addRow(rows, "fa fa-clone", "Part", str(source.get("part")), null);
        addRow(rows, "fa fa-heading", "Chapter", chapter, chapterUrl);
        addRow(rows, "fa fa-arrow-right-from-bracket", "Source", str(source.get("source")), null);
        addRow(rows, "fa fa-pen-to-square", "Edition", str(source.get("edition")), null);
        addRow(rows, "fa fa-building", "Publisher", str(source.get("publisher")), null);
        return rows;
    }

    private static void addRow(List<Map<String, String>> rows, String icon, String label,
                               String value, String url) {
        if (value == null || value.isBlank()) {
            return;
        }
        Map<String, String> row = new LinkedHashMap<>();
        row.put("icon", icon);
        row.put("label", label);
        row.put("value", value);
        if (url != null) {
            row.put("url", url);
        }
        rows.add(row);
    }

    private List<Map<String, String>> topicTags(Map<String, Object> source, String tagBase) {
        List<Map<String, String>> tags = new ArrayList<>();
        if (!(source.get("topic_tags") instanceof List<?> raw) || tagBase == null) {
            return tags;
        }
        for (Object slug : raw) {
            String value = str(slug);
            if (value.isBlank()) {
                continue;
            }
            tags.add(Map.of("label", topicLabels.label(value),
                    "url", tagBase + "?tag=" + encode(value)));
        }
        return tags;
    }

    private static List<String> tagSlugs(Map<String, Object> source) {
        if (!(source.get("topic_tags") instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream().map(HadithCardFactory::str).filter(v -> !v.isBlank()).toList();
    }

    private static String reportHref(String id, String book, String number, String baseUrl) {
        String descriptor = (book + (number.isBlank() ? "" : " #" + number)).trim();
        String subject = "Hadith Report: " + (descriptor.isBlank() ? "Hadith " + id : descriptor);
        String body = String.join("\n",
                "Please review the hadith linked below.", "",
                "Hadith link: " + baseUrl + "/hadith/" + id,
                "Hadith id: " + id, "",
                "Issue summary:", "- ", "",
                "What seems incorrect:", "- ", "",
                "Suggested correction (optional):", "- ");
        return "mailto:rewayaat.org@gmail.com?subject=" + encode(subject) + "&body=" + encode(body);
    }

    private String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            LOGGER.warn("Could not serialise card data", e);
            return "{}";
        }
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String stripHtml(String html) {
        return html == null ? "" : html.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : (second == null ? "" : second);
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }
}
