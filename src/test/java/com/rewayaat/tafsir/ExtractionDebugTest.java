package com.rewayaat.tafsir;

import com.rewayaat.tafsir.VerseReferenceParser;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.Jsoup;

/**
 * Simple debug test to check what we're getting from al-islam.org
 */
public class ExtractionDebugTest {
    public static void main(String[] args) throws Exception {
        // Test the surah page extraction
        String surahUrl = "https://al-islam.org/enlightening-commentary-light-holy-quran-vol-1/surah-al-fatihah-chapter-1";

        System.out.println("Testing surah page extraction");
        System.out.println("Fetching: " + surahUrl);

        Document page = Jsoup.connect(surahUrl)
                .userAgent("Mozilla/5.0")
                .timeout(30000)
                .get();

        System.out.println("Title: " + page.title());

        System.out.println("\n=== All headings (h2, h3) ===");
        page.select("h2, h3").forEach(h -> {
            String id = h.id();
            String text = h.text();
            System.out.println(h.tagName() + " [id=" + id + "]: " + (text.length() > 100 ? text.substring(0, 100) + "..." : text));
        });

        System.out.println("\n=== Verse headings with verse in text ===");
        page.select("h2, h3").forEach(h -> {
            String text = h.text();
            if (text.toLowerCase().contains("verse") || text.matches(".*\\d+:\\d+.*")) {
                System.out.println(h.tagName() + " [id=" + h.id() + "]: " + text);

                // Try to parse verse reference
                VerseReferenceParser.ParsedReference ref = VerseReferenceParser.parse(text);
                if (ref != null && ref.isValid()) {
                    System.out.println("  ✓ Parsed: surah=" + ref.surahNumber + ", ayah=" + ref.ayahStart);
                }
            }
        });

        // Test extracting content for first verse
        Element firstVerseHeading = page.selectFirst("h2:contains(Verse)");
        if (firstVerseHeading != null) {
            System.out.println("\n=== Content for: " + firstVerseHeading.text() + " ===");
            String content = extractContentAfterHeading(page, firstVerseHeading);
            System.out.println(content.substring(0, Math.min(800, content.length())) + "...");
            System.out.println("\nContent length: " + content.length() + " characters");
        }

        // Count all verses
        long verseCount = page.select("h2:contains(Verse)").stream()
                .map(h -> h.text())
                .filter(text -> text.contains("Verse"))
                .count();
        System.out.println("\nTotal verse headings found: " + verseCount);
    }

    private static String extractContentAfterHeading(Document page, Element heading) {
        StringBuilder content = new StringBuilder();
        boolean foundHeading = false;

        for (Element element : page.body().children()) {
            if (!foundHeading) {
                if (element.equals(heading)) {
                    foundHeading = true;
                }
                continue;
            }

            if (element.tagName().equals("h2") || element.tagName().equals("h3")) {
                break;
            }

            String className = element.className();
            if (className.contains("navigation") || className.contains("menu") || className.contains("breadcrumb")) {
                continue;
            }

            String text = element.text();
            if (text != null && !text.trim().isEmpty()) {
                if (content.length() > 0) {
                    content.append("\n");
                }
                content.append(text.trim());
            }
        }

        return content.toString();
    }
}
