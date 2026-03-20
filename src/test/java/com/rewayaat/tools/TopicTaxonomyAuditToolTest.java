package com.rewayaat.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicTaxonomyAuditToolTest {

    @TempDir
    Path tempDir;

    @Test
    void buildReport_identifiesCoveredAndGapHeadings() throws Exception {
        Path batchesDir = Files.createDirectories(tempDir.resolve("batches"));
        Files.writeString(batchesDir.resolve("manifest.json"), """
                {
                  "documents": 3,
                  "batches": 1
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(batchesDir.resolve("batch_00001.jsonl"), """
                {"_id":"doc-1","_source":{"book":"Book of Prayer","chapter":"Chapter on Friday Prayer","english":"Narration about jumu'a prayer.","arabic":""}}
                {"_id":"doc-2","_source":{"book":"Book of Family Duties","chapter":"Chapter on Guardianship of Orphans","english":"Narration about guarding the rights of orphans.","arabic":""}}
                {"_id":"doc-3","_source":{"book":"Al-Amali","chapter":"The Third Assembly, the Assembly of Tuesday, the Twenty-Sixth of Muharram, 368 AH.","english":"Assembly narration.","arabic":""}}
                """, StandardCharsets.UTF_8);

        List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy = TopicTaxonomySupport.parseTaxonomyProposal("""
                {"taxonomy":[
                  {"slug":"prayer","en":"Prayer","category":"worship"},
                  {"slug":"friday-prayer","en":"Friday Prayer","category":"worship","parent":"prayer"}
                ]}
                """);

        TopicTaxonomyAuditTool tool = new TopicTaxonomyAuditTool(
                batchesDir.resolve("manifest.json"),
                batchesDir,
                tempDir.resolve("audit.json"),
                10,
                2,
                1);

        TopicTaxonomyAuditTool.AuditReport report = tool.buildReport(taxonomy);

        assertEquals(3L, report.corpus().documents());
        assertEquals(1L, report.headingCoverage().coveredDocuments());
        assertEquals(2L, report.headingCoverage().uncoveredDocuments());
        assertEquals(1L, report.headingCoverage().genericUncoveredDocuments());
        assertTrue(report.candidateGaps().stream()
                .anyMatch(candidate -> "guardianship orphans".equals(candidate.headingKey())));
        assertFalse(report.candidateGaps().stream()
                .anyMatch(candidate -> candidate.headingKey().contains("assembly")));
    }

    @Test
    void normalizeHeadingKey_dropsGenericAssemblyMetadata() {
        assertEquals("", TopicTaxonomyAuditTool.normalizeHeadingKey(
                "The Third Assembly, the Assembly of Tuesday, the Twenty-Sixth of Muharram, 368 AH."));
        assertEquals("guardianship orphans", TopicTaxonomyAuditTool.normalizeHeadingKey(
                "Chapter on Guardianship of Orphans"));
    }
}
