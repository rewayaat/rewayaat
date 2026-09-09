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
                "(anger^6 OR anger~1) book:\"Nahj al-Balāgha\"",
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
                "(ghadir^6 OR ghadir~1)",
                service.enhanceQuery("ghadir", QueryMode.SEARCH, false)
        );
    }

    /**
     * Arabic is left alone in flexible mode.
     *
     * <p>An Arabic root is short and its neighbours are all real words, so one edit finds a
     * different word rather than the intended one: غدير matches 26 narrations and 2,143
     * with a single edit. The index already folds the variation a reader actually types -
     * diacritics, alef and teh marbuta - so there is nothing left for fuzziness to repair.
     */
    @Test
    void flexibleModeDoesNotFuzzyArabic() {
        assertEquals("غدير", service.enhanceQuery("غدير", QueryMode.SEARCH, false));
        assertEquals("الصلاة", service.enhanceQuery("الصلاة", QueryMode.SEARCH, false));
    }

    /** A Latin term beside an Arabic one keeps its own treatment. */
    @Test
    void aMixedQueryFuzziesOnlyTheLatinTerm() {
        assertEquals("(prayer^6 OR prayer~1) الصلاة",
                service.enhanceQuery("prayer الصلاة", QueryMode.SEARCH, false));
    }
}
