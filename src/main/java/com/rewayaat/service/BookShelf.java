package com.rewayaat.service;

import org.springframework.core.io.ClassPathResource;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The books as they stand on the bookcase drawn on the home page and on {@code /books}.
 *
 * <p>Largest collection first, and each book drawn to its size: a book's height and the
 * thickness of its page block grow with the log of its narration count, so al-Kāfi is
 * the full height with a deep page block and Faḍāʾil al-Shīʿa, at 45 narrations, is a
 * slim volume. The covers are drawn by {@code scripts/book-covers}; a book that has none
 * yet (a newly added one) still stands on the shelf in plain leather with its name on it.
 */
public final class BookShelf {

    /** A book on the shelf: whether its cover exists, and the inline style that sizes it. */
    public record Shelved(BookCatalog.Book book, boolean hasCover, String style) {
    }

    /**
     * The book drawn small at the start of each volume and part card: the same cover, with
     * a page block as thick as the share of the book that card holds.
     */
    public record Thumb(String src, String leather, boolean hasCover) {

        /** The inline style for a card holding {@code chapters} of the book's chapters. */
        public String style(long chapters) {
            // Logarithmic, like the shelf: 1 chapter is a pamphlet, 600 is a full volume.
            double reach = Math.max(0, Math.min(1, Math.log10(Math.max(chapters, 1)) / Math.log10(600)));
            return String.format(Locale.ROOT, "--pages:%.1fpx", 2 + 6 * reach);
        }
    }

    /** The leather each cover is drawn in, as scripts/book-covers/leather.py has it. */
    private static final Map<String, String> LEATHER = Map.ofEntries(
            Map.entry("al-kafi", "#142c20"),
            Map.entry("man-la-yahduruh-al-faqih", "#5c1416"),
            Map.entry("nahj-al-balagha", "#191817"),
            Map.entry("al-amali", "#16213d"),
            Map.entry("al-khisal", "#45121b"),
            Map.entry("kitab-al-ghayba", "#111827"),
            Map.entry("thawab-al-a-mal-wa-iqab-al-a-mal", "#5c1416"),
            Map.entry("uyun-akhbar-al-rida", "#16213d"),
            Map.entry("ma-ani-al-akhbar", "#191817"),
            Map.entry("kamil-al-ziyarat", "#142c20"),
            Map.entry("al-tawhid", "#191817"),
            Map.entry("mu-jam-al-ahadith-al-mu-tabara", "#45121b"),
            Map.entry("kitab-al-zuhd", "#111827"),
            Map.entry("kitab-al-du-afa", "#191817"),
            Map.entry("kitab-al-mu-min", "#5c1416"),
            Map.entry("sifat-al-shi-a", "#16213d"),
            Map.entry("risalat-al-huquq", "#45121b"),
            Map.entry("fada-il-al-shi-a", "#142c20"),
            Map.entry("ghurar-al-hikam", "#111827"),
            Map.entry("mizan-al-hikma", "#16213d"),
            Map.entry("mishkat-al-anwar", "#5c1416"));
    private static final String PLAIN_LEATHER = "#16213d";

    private static final String COVERS = "static/img/books/covers/";
    private static final String GILT = "/img/books/gilt/";

    /** log10 of the smallest collection the scale is drawn for; anything smaller is the minimum. */
    private static final double SMALLEST_LOG = 1.6;

    private static final Map<String, Boolean> COVER_EXISTS = new ConcurrentHashMap<>();

    private BookShelf() {
    }

    public static List<Shelved> arrange(List<BookCatalog.Book> books) {
        long largest = books.stream().mapToLong(BookCatalog.Book::count).max().orElse(1);
        return books.stream()
                .sorted(Comparator.comparingLong(BookCatalog.Book::count).reversed())
                .map(book -> new Shelved(book, hasCover(book.slug()), style(book, largest)))
                .toList();
    }

    public static Thumb thumb(String slug) {
        return new Thumb("/img/books/covers/" + slug + "-160.webp",
                LEATHER.getOrDefault(slug, PLAIN_LEATHER), hasCover(slug));
    }

    static boolean hasCover(String slug) {
        return COVER_EXISTS.computeIfAbsent(slug,
                s -> new ClassPathResource(COVERS + s + ".webp").exists());
    }

    static String style(BookCatalog.Book book, long largest) {
        double top = Math.log10(Math.max(largest, 100));
        double reach = Math.max(0, Math.min(1, (Math.log10(Math.max(book.count(), 1)) - SMALLEST_LOG) / (top - SMALLEST_LOG)));
        // A slight, fixed per-book wobble in height, so books of near-equal size don't line up exactly.
        double wobble = ((book.slug().hashCode() & 0xffff) / 65535.0 * 2 - 1) * 0.015;
        // 0.80 floor: at 0.86 the log scale was real but a 45-narration book and al-Kafi differed by
        // a hand's breadth at most; this makes the difference readable across the room.
        double scale = Math.min(1.0, 0.80 + 0.19 * reach + wobble);
        // The page block is a side face, so its depth is a length: 4px for the slimmest volume, 20px
        // for al-Kafi. --rise is how far its far edge climbs the board (the shelf is seen from above),
        // and --tilt the same as a slope, so the side face can be sheared without dividing lengths in CSS.
        double depth = 4 + 16 * reach;
        double rise = 2.5 + 0.25 * depth;
        return String.format(Locale.ROOT, "--scale:%.3f;--depth:%.1fpx;--rise:%.1fpx;--tilt:%.3f;--gilt:url(%s%s.webp)",
                scale, depth, rise, rise / depth, GILT, book.slug());
    }
}
