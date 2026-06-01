package com.rewayaat.tools;

import com.rewayaat.tafsir.TafsirDocument;
import com.rewayaat.tools.TafsirAuditTool.AuditFinding;
import com.rewayaat.tools.TafsirAuditTool.AuditReport;
import com.rewayaat.tools.TafsirAuditTool.CheckType;
import com.rewayaat.tools.TafsirAuditTool.Result;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TafsirAuditToolTest {

    @Test
    void testVerseKeyConsistency_validDoc() {
        TafsirDocument doc = createDoc("enlightening-commentary_2:255", 2, 255, 255, "2:255");
        AuditReport report = TafsirAuditTool.runAudit(List.of(doc));
        List<AuditFinding> consistencyFindings = findingsByType(report, CheckType.VERSE_KEY_CONSISTENCY);
        assertTrue(consistencyFindings.isEmpty(), "Valid doc should have no consistency findings");
    }

    @Test
    void testVerseKeyConsistency_mismatchDoc() {
        TafsirDocument doc = createDoc("test_3:1", 2, 1, 1, "3:1");
        AuditReport report = TafsirAuditTool.runAudit(List.of(doc));
        List<AuditFinding> findings = findingsByType(report, CheckType.VERSE_KEY_CONSISTENCY);
        assertEquals(1, findings.size(), "Should detect verseKey mismatch");
        assertEquals(Result.FAIL, findings.get(0).result());
        assertTrue(findings.get(0).message().contains("does not match"));
    }

    @Test
    void testVerseKeysCoverage_correct() {
        TafsirDocument doc = createDoc("test_2:1", 2, 1, 5, "2:1");
        doc.setVerseKeys(List.of("2:1", "2:2", "2:3", "2:4", "2:5"));
        AuditReport report = TafsirAuditTool.runAudit(List.of(doc));
        List<AuditFinding> findings = findingsByType(report, CheckType.VERSE_KEYS_COVERAGE);
        assertTrue(findings.isEmpty(), "Correct verseKeys should have no coverage findings");
    }

    @Test
    void testVerseKeysCoverage_incomplete() {
        TafsirDocument doc = createDoc("test_2:1", 2, 1, 5, "2:1");
        doc.setVerseKeys(List.of("2:1", "2:3")); // Missing 2:2, 2:4, 2:5
        AuditReport report = TafsirAuditTool.runAudit(List.of(doc));
        List<AuditFinding> findings = findingsByType(report, CheckType.VERSE_KEYS_COVERAGE);
        assertEquals(1, findings.size());
        assertEquals(Result.WARNING, findings.get(0).result());
    }

    @Test
    void testIndexability_shortText() {
        TafsirDocument doc = createDoc("test_2:1", 2, 1, 1, "2:1");
        doc.setCommentaryText("Short text"); // Only 2 words, below 10 minimum
        doc.computeWordCount();
        AuditReport report = TafsirAuditTool.runAudit(List.of(doc));
        List<AuditFinding> findings = findingsByType(report, CheckType.INDEXABILITY);
        assertFalse(findings.isEmpty(), "Short text should fail indexability");
    }

    @Test
    void testWordCountAccuracy_match() {
        TafsirDocument doc = createDoc("test_2:1", 2, 1, 1, "2:1");
        doc.setCommentaryText("This is a test commentary with enough words to be substantive and meaningful");
        doc.computeWordCount();
        AuditReport report = TafsirAuditTool.runAudit(List.of(doc));
        List<AuditFinding> findings = findingsByType(report, CheckType.WORD_COUNT_ACCURACY);
        assertTrue(findings.isEmpty(), "Accurate word count should have no findings");
    }

    @Test
    void testWordCountAccuracy_mismatch() {
        TafsirDocument doc = createDoc("test_2:1", 2, 1, 1, "2:1");
        doc.setCommentaryText("This is a test commentary with enough words to be substantive");
        doc.computeWordCount();
        doc.setCommentaryWordCount(500); // Override with wrong value
        AuditReport report = TafsirAuditTool.runAudit(List.of(doc));
        List<AuditFinding> findings = findingsByType(report, CheckType.WORD_COUNT_ACCURACY);
        assertFalse(findings.isEmpty(), "Mismatched word count should produce finding");
    }

    @Test
    void testDuplicateDetection() {
        TafsirDocument doc1 = createDoc("test_2:1", 2, 1, 1, "2:1");
        TafsirDocument doc2 = createDoc("test_2:1", 2, 1, 1, "2:1"); // Same ID
        AuditReport report = TafsirAuditTool.runAudit(List.of(doc1, doc2));
        List<AuditFinding> findings = findingsByType(report, CheckType.DUPLICATE_DETECTION);
        assertEquals(1, findings.size(), "Should detect duplicate ID");
        assertEquals(Result.FAIL, findings.get(0).result());
    }

    @Test
    void testAllChecksPass() {
        TafsirDocument doc = createDoc("test_2:255", 2, 255, 255, "2:255");
        doc.setVerseKeys(List.of("2:255"));
        AuditReport report = TafsirAuditTool.runAudit(List.of(doc));
        assertEquals(1, report.totalSampled());
        assertEquals(1, report.passCount());
        assertEquals(0, report.failCount());
        assertEquals(0, report.warningCount());
    }

    private TafsirDocument createDoc(String id, int surah, int ayahStart, int ayahEnd, String verseKey) {
        TafsirDocument doc = new TafsirDocument();
        doc.setDocumentId(id);
        doc.setTafsirSlug("test");
        doc.setTafsirName("Test Tafsir");
        doc.setSurahNumber(surah);
        doc.setAyahStart(ayahStart);
        doc.setAyahEnd(ayahEnd);
        doc.setVerseKey(verseKey);
        doc.setCommentaryText("This is a sample commentary text that has enough words to pass the indexability check and be considered substantive for testing purposes.");
        doc.setLanguage("en");
        doc.computeWordCount();
        return doc;
    }

    private List<AuditFinding> findingsByType(AuditReport report, CheckType type) {
        return report.findings().stream()
                .filter(f -> f.checkType() == type)
                .toList();
    }
}
