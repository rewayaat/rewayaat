package com.rewayaat.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manual sampling harness for chain extraction review.
 *
 * Usage:
 * mvn -Dtest=HadithDisplaySegmenterSamplingTest test \
 *   -Dsegmenter.books="Al-Kāfi|Al-Tawḥīd" -Dsegmenter.count=20 -Dsegmenter.seed=101
 */
class HadithDisplaySegmenterSamplingTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final Path BATCH_DIR = Path.of("batches");

    @Test
    void printRandomSamplesForManualReview() throws Exception {
        List<String> books = selectedBooks();
        int count = Integer.getInteger("segmenter.count", 20);
        long seed = Long.getLong("segmenter.seed", 1234L);

        Map<String, List<Map<String, Object>>> byBook = loadEntriesByBook();
        for (String book : books) {
            List<Map<String, Object>> entries = byBook.get(book);
            if (entries == null || entries.isEmpty()) {
                throw new IllegalArgumentException("Unknown or empty book: " + book);
            }
            List<Map<String, Object>> samples = sample(entries, count, seedForBook(seed, book));
            printSamples(book, samples);
        }
    }

    private static List<String> selectedBooks() throws IOException {
        String raw = System.getProperty("segmenter.books", "").trim();
        if (!raw.isEmpty()) {
            return Stream.of(raw.split("\\|"))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .collect(Collectors.toList());
        }
        try (Stream<Path> paths = Files.list(BATCH_DIR)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .flatMap(path -> readBooks(path).stream())
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private static List<String> readBooks(Path path) {
        Set<String> books = new LinkedHashSet<>();
        try (Stream<String> lines = Files.lines(path)) {
            lines.filter(line -> !line.isBlank()).forEach(line -> {
                try {
                    Map<String, Object> row = JSON.readValue(line, MAP_TYPE);
                    Map<String, Object> source = asMap(row.get("_source"));
                    String book = stringValue(source.get("book"));
                    if (!book.isBlank()) {
                        books.add(book);
                    }
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            });
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        return new ArrayList<>(books);
    }

    private static Map<String, List<Map<String, Object>>> loadEntriesByBook() throws IOException {
        Map<String, List<Map<String, Object>>> byBook = new HashMap<>();
        try (Stream<Path> paths = Files.list(BATCH_DIR)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".jsonl")).sorted().toList()) {
                try (Stream<String> lines = Files.lines(path)) {
                    lines.filter(line -> !line.isBlank()).forEach(line -> {
                        try {
                            Map<String, Object> row = JSON.readValue(line, MAP_TYPE);
                            Map<String, Object> source = new HashMap<>(asMap(row.get("_source")));
                            String book = stringValue(source.get("book"));
                            String id = stringValue(row.get("_id"));
                            if (book.isBlank() || id.isBlank()) {
                                return;
                            }
                            source.put("_id", id);
                            byBook.computeIfAbsent(book, key -> new ArrayList<>()).add(source);
                        } catch (IOException ex) {
                            throw new RuntimeException(ex);
                        }
                    });
                }
            }
        }
        return byBook;
    }

    private static List<Map<String, Object>> sample(List<Map<String, Object>> entries, int count, long seed) {
        List<Map<String, Object>> copy = new ArrayList<>(entries);
        Collections.shuffle(copy, new Random(seed));
        return copy.subList(0, Math.min(count, copy.size()));
    }

    private static long seedForBook(long baseSeed, String book) {
        return baseSeed ^ book.toLowerCase(Locale.ROOT).hashCode();
    }

    private static void printSamples(String book, List<Map<String, Object>> samples) {
        System.out.println("==============================");
        System.out.println("BOOK: " + book);
        System.out.println("SAMPLES: " + samples.size());
        System.out.println("==============================");
        System.out.println();

        for (int i = 0; i < samples.size(); i++) {
            Map<String, Object> source = new HashMap<>(samples.get(i));
            HadithDisplaySegmenter.enrich(source);
            System.out.println("[" + (i + 1) + "] " + stringValue(source.get("_id")));
            System.out.println("chapter: " + stringValue(source.get("chapter")));
            System.out.println("raw english: " + truncated(source.get("english")));
            System.out.println("english chain: " + truncated(source.get("englishChain")));
            System.out.println("english content: " + truncated(firstNonBlank(source.get("englishContent"), source.get("english"))));
            System.out.println("raw arabic: " + truncated(source.get("arabic")));
            System.out.println("arabic chain: " + truncated(source.get("arabicChain")));
            System.out.println("arabic content: " + truncated(firstNonBlank(source.get("arabicContent"), source.get("arabic"))));
            System.out.println();
        }
    }

    private static Object firstNonBlank(Object first, Object fallback) {
        String firstValue = stringValue(first);
        return firstValue.isBlank() ? fallback : first;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String truncated(Object value) {
        String normalized = stringValue(value).replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 220) {
            return normalized;
        }
        return normalized.substring(0, 217) + "...";
    }
}
