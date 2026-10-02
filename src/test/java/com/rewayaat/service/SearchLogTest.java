package com.rewayaat.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What reaches the search log, and more importantly what does not.
 *
 * <p>These all exercise {@code record} without Elasticsearch: the point of the queue is
 * that a search is answered whether or not anything is written, so the decision about
 * whether a row is worth keeping has to be testable on its own.
 */
class SearchLogTest {

    private static final String BROWSER =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/133.0 Safari/537.36";

    private static SearchLog log() {
        // No Elasticsearch: nothing here starts the writer thread.
        return new SearchLog(null);
    }

    @Test
    void aSearchIsQueued() {
        SearchLog log = log();
        log.record("al-kafi", 42, 1, "en", "rewayaat.info", BROWSER);
        assertEquals(1, log.queued());
    }

    @Test
    void anEmptyQueryIsNotASearch() {
        SearchLog log = log();
        log.record("", 0, 1, "en", "rewayaat.info", BROWSER);
        log.record("   ", 0, 1, "en", "rewayaat.info", BROWSER);
        log.record(null, 0, 1, "en", "rewayaat.info", BROWSER);
        assertEquals(0, log.queued());
    }

    /** Paging through results is one search, not three. */
    @Test
    void onlyTheFirstPageCounts() {
        SearchLog log = log();
        log.record("zakat", 10, 1, "en", "rewayaat.info", BROWSER);
        log.record("zakat", 10, 2, "en", "rewayaat.info", BROWSER);
        log.record("zakat", 10, 7, "en", "rewayaat.info", BROWSER);
        assertEquals(1, log.queued());
    }

    /**
     * Most requests to this site are automated. A table whose popular terms were really a
     * crawler would be a confident wrong answer, and this number is meant to be shown to
     * someone being asked to pay for a placement.
     */
    @Test
    void crawlersAreNotReaders() {
        SearchLog log = log();
        for (String agent : new String[]{
                "Mozilla/5.0 (compatible; Baiduspider-render/2.0; +http://www.baidu.com/search/spider.html)",
                "Mozilla/5.0 (compatible; GPTBot/1.0; +https://openai.com/gptbot)",
                "Mozilla/5.0 (compatible; SemrushBot/7~bl)",
                "python-requests/2.31.0",
                "curl/8.4.0",
                "Mozilla/5.0 (compatible; PetalBot;+https://webmaster.petalsearch.com/site/petalbot)"}) {
            log.record("al-kafi", 1, 1, "en", "rewayaat.info", agent);
        }
        assertEquals(0, log.queued(), "a crawler's search must not reach the log");

        log.record("al-kafi", 1, 1, "en", "rewayaat.info", BROWSER);
        assertEquals(1, log.queued(), "a reader's search must");
    }

    @Test
    void aMissingUserAgentIsTreatedAsAReader() {
        SearchLog log = log();
        log.record("tawhid", 3, 1, "en", "rewayaat.info", null);
        assertEquals(1, log.queued());
    }

    @Test
    void termsAreFoldedSoTheyCountTogether() {
        assertEquals("al-kafi", SearchLog.normalise("  Al-Kafi "));
        assertEquals("al-kafi", SearchLog.normalise("AL-KAFI"));
        assertEquals("imam ali", SearchLog.normalise("Imam    Ali"));
        assertEquals("imam ali", SearchLog.normalise("imam\tali"));
    }

    @Test
    void crawlerDetectionIsCaseInsensitiveAndDoesNotCatchReaders() {
        assertTrue(SearchLog.isCrawler("SomeBot/1.0"));
        assertTrue(SearchLog.isCrawler("YANDEX"));
        assertFalse(SearchLog.isCrawler(BROWSER));
        assertFalse(SearchLog.isCrawler(null));
        assertFalse(SearchLog.isCrawler(""));
    }

    /**
     * A backlog means something is already wrong; it must not become a stalled search.
     * The queue drops and counts instead of blocking.
     */
    @Test
    void afullQueueDropsRatherThanBlocks() {
        SearchLog log = log();
        for (int i = 0; i < 700; i++) {
            log.record("term " + i, 1, 1, "en", "rewayaat.info", BROWSER);
        }
        assertEquals(500, log.queued(), "the queue is bounded");
        assertTrue(log.droppedCount() >= 200, "drops are counted: " + log.droppedCount());
    }

    @Test
    void aSearchThatFoundNothingIsStillRecorded() {
        SearchLog log = log();
        log.record("qwertyuiop", 0, 1, "en", "rewayaat.info", BROWSER);
        assertEquals(1, log.queued(), "a search with no results is the interesting one");
    }
}
