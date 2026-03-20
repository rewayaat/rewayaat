package com.rewayaat.tools;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TopicTagsQaToolTest {

    @Test
    void analyze_reportsCoreQaBucketsAndUsage() throws Exception {
        List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy = TopicTaxonomySupport.parseTaxonomyProposal("""
                {"taxonomy":[
                  {"slug":"prayer","en":"Prayer","category":"worship"},
                  {"slug":"friday-prayer","en":"Friday Prayer","category":"worship","parent":"prayer"},
                  {"slug":"knowledge","en":"Knowledge","category":"ethics"},
                  {"slug":"family","en":"Family","category":"ethics"},
                  {"slug":"trade","en":"Trade","category":"law"},
                  {"slug":"fasting","en":"Fasting","category":"worship"}
                ]}
                """);

        List<TopicTagsQaTool.NarrationSummary> narrations = List.of(
                new TopicTagsQaTool.NarrationSummary("doc-1", "Book A", "Chapter A", List.of()),
                new TopicTagsQaTool.NarrationSummary("doc-2", "Book A", "Chapter B", List.of("prayer")),
                new TopicTagsQaTool.NarrationSummary("doc-3", "Book B", "Chapter C", List.of("friday-prayer", "prayer")),
                new TopicTagsQaTool.NarrationSummary("doc-4", "Book B", "Chapter D", List.of("knowledge", "family", "trade", "prayer", "friday-prayer", "unknown-extra")),
                new TopicTagsQaTool.NarrationSummary("doc-5", "Book C", "Chapter E", List.of("knowledge", "family", "trade", "prayer"))
        );

        TopicTagsQaTool tool = new TopicTagsQaTool(Path.of("/tmp/qa-test.json"));
        TopicTagsQaTool.QaReport report = tool.analyze(taxonomy, narrations);

        assertEquals(5, report.corpus().documents());
        assertEquals(1, report.corpus().documentsWithoutTags());
        assertEquals(1, report.buckets().withoutTags().count());
        assertEquals(1, report.buckets().umbrellaOnly().count());
        assertEquals(1, report.buckets().overTagged().count());
        assertEquals(2, report.buckets().highRootSpread().count());
        assertEquals(1, report.taxonomyUsage().unusedTaxonomyNodes());
        assertEquals(List.of("trade"), report.taxonomyUsage().rareUsedTags().stream()
                .filter(stat -> "trade".equals(stat.slug()))
                .map(TopicTagsQaTool.CountStat::slug)
                .toList());
        assertFalse(report.topBooksByUmbrellaOnly().isEmpty());
    }
}
