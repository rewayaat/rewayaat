package com.rewayaat.tools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicTaxonomySupportTest {

    @Test
    void loadBundledTaxonomy_returnsCurrentFrozenEntries() throws Exception {
        List<TopicTaxonomySupport.TopicTaxonomyEntry> entries = TopicTaxonomySupport.loadBundledTaxonomy();
        assertTrue(entries.size() >= 40);
        assertTrue(entries.stream().anyMatch(entry -> "prayer".equals(entry.slug())));
        assertTrue(entries.stream().anyMatch(entry -> "ahl-al-bayt".equals(entry.slug())));
        assertTrue(entries.stream().anyMatch(entry ->
                "water-purity".equals(entry.slug()) && "purification".equals(entry.parentSlug())));
        assertTrue(entries.stream().anyMatch(entry ->
                "twelve-imams".equals(entry.slug()) && "imamate".equals(entry.parentSlug())));
        assertTrue(entries.stream().anyMatch(entry ->
                "friday-prayer".equals(entry.slug()) && "prayer".equals(entry.parentSlug())));
    }

    @Test
    void parseSelectedTags_keepsOnlyAllowedNormalizedSlugs() throws Exception {
        List<String> tags = TopicTaxonomySupport.parseSelectedTags(
                "{\"tags\":[\"Prayer\",\"invented-tag\",\"ahl al bayt\"]}",
                Set.of("prayer", "ahl-al-bayt"));

        assertEquals(List.of("prayer", "ahl-al-bayt"), tags);
    }

    @Test
    void parseTaxonomyProposal_normalizesAndDeduplicatesEntries() throws Exception {
        List<TopicTaxonomySupport.TopicTaxonomyEntry> entries = TopicTaxonomySupport.parseTaxonomyProposal(
                "{\"taxonomy\":[" +
                        "{\"slug\":\"Good Character\",\"en\":\"Good Character\",\"ar\":\"حسن الخلق\",\"category\":\"Ethics\"}," +
                        "{\"slug\":\"good-character\",\"en\":\"Good Character\",\"ar\":\"حسن الخلق\",\"category\":\"ethics\"}," +
                        "{\"slug\":\"Prayer\",\"en\":\"Prayer\",\"ar\":\"صلاة\",\"category\":\"Worship\"}" +
                        "]}");

        assertEquals(2, entries.size());
        assertEquals("good-character", entries.get(0).slug());
        assertEquals("ethics", entries.get(0).category());
        assertEquals("prayer", entries.get(1).slug());
    }

    @Test
    void expandWithAncestors_addsMissingParentsInOrder() throws Exception {
        List<TopicTaxonomySupport.TopicTaxonomyEntry> entries = TopicTaxonomySupport.loadBundledTaxonomy();
        Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> bySlug = TopicTaxonomySupport.indexBySlug(entries);

        List<String> expanded = TopicTaxonomySupport.expandWithAncestors(
                List.of("friday-prayer", "imam-ali"),
                bySlug);

        assertEquals(List.of("friday-prayer", "prayer", "imam-ali", "ahl-al-bayt"), expanded);
    }

    @Test
    void parseTagAssignments_keepsOnlyAllowedNormalizedSlugsPerDocument() throws Exception {
        Map<String, List<String>> assignments = TopicTaxonomySupport.parseTagAssignments(
                "{\"documents\":[" +
                        "{\"id\":\"doc-1\",\"tags\":[\"Friday Prayer\",\"invented\"]}," +
                        "{\"id\":\"doc-2\",\"tags\":[\"imam ali\",\"ahl al bayt\"]}" +
                        "]}",
                Set.of("friday-prayer", "imam-ali", "ahl-al-bayt"));

        assertEquals(List.of("friday-prayer"), assignments.get("doc-1"));
        assertEquals(List.of("imam-ali", "ahl-al-bayt"), assignments.get("doc-2"));
    }

    @Test
    void compactPromptTaxonomy_includesHierarchyDetails() throws Exception {
        List<TopicTaxonomySupport.TopicTaxonomyEntry> entries = TopicTaxonomySupport.loadBundledTaxonomy();

        List<String> compact = TopicTaxonomySupport.compactPromptTaxonomy(entries);

        assertTrue(compact.stream().anyMatch(line -> line.contains("friday-prayer") && line.contains("parent=prayer")));
        assertTrue(compact.stream().anyMatch(line -> line.contains("ahl-al-bayt")));
    }

    @Test
    void normalizeEnglishForMatch_foldsCommonTransliterationMarks() {
        String normalized = TopicTaxonomySupport.normalizeEnglishForMatch(
                "Kāmil al-Ziyārāt and Al-Tawḥīd with Shiʿa and Qur’an references");

        assertEquals("kamil al ziyarat and al tawhid with shia and quran references", normalized);
    }
}
