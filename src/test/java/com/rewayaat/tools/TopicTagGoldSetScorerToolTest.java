package com.rewayaat.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TopicTagGoldSetScorerToolTest {

    @TempDir
    Path tempDir;

    @Test
    void score_reportsRawAndHierarchyAwareMetrics() throws Exception {
        Path gold = tempDir.resolve("gold.jsonl");
        Path predicted = tempDir.resolve("predicted.jsonl");
        Files.writeString(gold, """
                {"id":"doc-1","review_status":"reviewed","gold_topic_tags":["friday-prayer"]}
                {"id":"doc-2","review_status":"reviewed","gold_topic_tags":["washing-deceased"]}
                {"id":"doc-3","review_status":"pending","gold_topic_tags":["water-purity"]}
                """, StandardCharsets.UTF_8);
        Files.writeString(predicted, """
                {"id":"doc-1","topic_tags":["prayer"]}
                {"id":"doc-2","topic_tags":["washing-deceased"]}
                """, StandardCharsets.UTF_8);

        List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy = TopicTaxonomySupport.parseTaxonomyProposal("""
                {"taxonomy":[
                  {"slug":"prayer","en":"Prayer","category":"worship"},
                  {"slug":"friday-prayer","en":"Friday Prayer","category":"worship","parent":"prayer"},
                  {"slug":"funeral-rites","en":"Funeral Rites","category":"worship"},
                  {"slug":"washing-deceased","en":"Washing the Deceased","category":"worship","parent":"funeral-rites"}
                ]}
                """);

        TopicTagGoldSetScorerTool tool = new TopicTagGoldSetScorerTool(gold, predicted, tempDir.resolve("score.json"));
        TopicTagGoldSetScorerTool.ScoreReport report = tool.score(taxonomy);

        assertEquals(2, report.corpus().reviewedDocuments());
        assertEquals(1, report.corpus().pendingDocuments());
        assertEquals(0.5d, report.raw().exactMatchRate());
        assertEquals(0.5d, report.raw().microPrecision());
        assertEquals(0.5d, report.raw().microRecall());
        assertEquals(0.5d, report.raw().microF1());
        assertEquals(1.0d, report.hierarchyAware().microPrecision());
        assertEquals(0.75d, report.hierarchyAware().microRecall());
        assertEquals(0.8571d, report.hierarchyAware().microF1());
    }
}
