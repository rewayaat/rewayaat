package com.rewayaat.service;

import com.rewayaat.core.QueryMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                "(anger^6 OR anger~) book:\"Nahj al-Balāgha\"",
                service.enhanceQuery("anger book:\"Nahj al-Balāgha\"", QueryMode.SEARCH, false)
        );
    }

    @Test
    void preciseModeAcceptsCanonicalAndLegacyAliases() {
        // Moved here from HadithControllerMatchModeTest when the check moved out of the
        // controller: the REST endpoint and the MCP search_hadith tool both read a
        // match_mode, and "precise" has to mean the same thing to both.
        assertTrue(service.isPreciseMatchMode("precise"));
        assertTrue(service.isPreciseMatchMode("strict"));
        assertTrue(service.isPreciseMatchMode("exact"));
        assertTrue(service.isPreciseMatchMode("  PRECISE  "));
        assertFalse(service.isPreciseMatchMode("flexible"));
        assertFalse(service.isPreciseMatchMode("permissive"));
        assertFalse(service.isPreciseMatchMode(""));
        assertFalse(service.isPreciseMatchMode(null));
    }

    @Test
    void flexibleModeBoostsExactTokenBeforeFuzzyFallback() {
        assertEquals(
                "(غدير^6 OR غدير~)",
                service.enhanceQuery("غدير", QueryMode.SEARCH, false)
        );
    }
}
