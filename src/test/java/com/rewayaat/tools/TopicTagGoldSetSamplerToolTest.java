package com.rewayaat.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicTagGoldSetSamplerToolTest {

    @TempDir
    Path tempDir;

    @Test
    void sample_includesGapAndRuleStrata() throws Exception {
        Path batchesDir = Files.createDirectories(tempDir.resolve("batches"));
        Files.writeString(batchesDir.resolve("batch_00001.jsonl"), """
                {"_id":"doc-1","_source":{"book":"Book of Prayer","chapter":"Chapter on Friday Prayer","english":"Narration about Friday prayer.","arabic":""}}
                {"_id":"doc-2","_source":{"book":"Book of Purity","chapter":"Water; Its Purity and Impurity","english":"Narration about pure and impure water.","arabic":""}}
                """, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("audit.json"), """
                {
                  "headingCoverage": {
                    "uncoveredBooks": [
                      {"book":"Book of Prayer","documents":1}
                    ]
                  },
                  "candidateGaps": [
                    {
                      "headingKey":"water its purity impurity",
                      "samples":[
                        {"id":"doc-2","book":"Book of Purity","chapter":"Water; Its Purity and Impurity","englishPreview":"Narration about pure and impure water."}
                      ]
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);

        List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy = TopicTaxonomySupport.parseTaxonomyProposal("""
                {"taxonomy":[
                  {"slug":"prayer","en":"Prayer","category":"worship"},
                  {"slug":"friday-prayer","en":"Friday Prayer","category":"worship","parent":"prayer"},
                  {"slug":"purification","en":"Purification","category":"worship"},
                  {"slug":"water-purity","en":"Water Purity and Impurity","category":"worship","parent":"purification"}
                ]}
                """);

        TopicTagGoldSetSamplerTool tool = new TopicTagGoldSetSamplerTool(
                batchesDir,
                tempDir.resolve("audit.json"),
                tempDir.resolve("gold.jsonl"));

        TopicTagGoldSetSamplerTool.SamplerReport report = tool.sample(taxonomy);

        assertFalse(report.entries().isEmpty());
        ObjectNode gapEntry = report.entries().stream()
                .filter(node -> "doc-2".equals(node.path("id").asText("")))
                .findFirst()
                .orElseThrow();
        assertTrue(gapEntry.withArray("strata").toString().contains("gap:water its purity impurity"));
        assertTrue(report.entries().stream()
                .anyMatch(node -> node.withArray("strata").toString().contains("root:")));
    }
}
