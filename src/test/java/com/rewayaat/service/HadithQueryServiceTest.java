package com.rewayaat.service;

import com.rewayaat.core.QueryMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HadithQueryServiceTest {

    private final HadithQueryService service = new HadithQueryService();

    @Test
    void strictModePreservesKeywordAndScopedBookFilter() {
        assertEquals(
                "anger AND book:\"Nahj al-Balāgha\"",
                service.enhanceQuery("anger book:\"Nahj al-Balāgha\"", QueryMode.SEARCH, true)
        );
    }

    @Test
    void permissiveModeOnlyFuzziesKeywordTerms() {
        assertEquals(
                "anger~ book:\"Nahj al-Balāgha\"",
                service.enhanceQuery("anger book:\"Nahj al-Balāgha\"", QueryMode.SEARCH, false)
        );
    }
}
