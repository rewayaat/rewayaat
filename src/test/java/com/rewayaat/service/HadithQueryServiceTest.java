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

    // Punctuation a reader pastes. Each of these built a query the parser refused, and a
    // refused query is a 500 on the search page, not an empty result.

    @Test
    void aColonEndingAnArabicWordIsText() {
        // «قَالَ:» ends a clause; it does not name a field, and «قال: AND إن» cannot parse
        assertEquals(
                "قال\\: AND إن AND كره",
                service.enhanceQuery("قَالَ: إِنَّ كَرِهَ", QueryMode.SEARCH, true)
        );
    }

    @Test
    void aSalutationAndItsPunctuationBothGo() {
        // the salutation is dropped, and the colon it leaves behind is not a search term
        assertEquals(
                "قال AND الصلاة",
                service.enhanceQuery("قال (ع): الصلاة", QueryMode.SEARCH, true)
        );
    }

    @Test
    void aFieldNameWithNoValueIsText() {
        assertEquals(
                "al-Kafi\\: AND the AND book",
                service.enhanceQuery("al-Kafi: the book", QueryMode.SEARCH, true)
        );
    }

    @Test
    void aBracketedSalutationIsDropped() {
        // «[ع]» is a salutation, never a range
        assertEquals(
                "عن AND قال",
                service.enhanceQuery("عن [ع] قال", QueryMode.SEARCH, true)
        );
    }

    @Test
    void bracketsThatAreNotASalutationStayAsText() {
        assertEquals(
                "عن AND \\[الكافي\\] AND قال",
                service.enhanceQuery("عن [الكافي] قال", QueryMode.SEARCH, true)
        );
    }

    @Test
    void theSalutationGoesInEveryFormTheCorpusWritesIt() {
        for (String form : new String[] {"(ع)", "[ع]", "(عليه السلام)", "عليه السلام",
                                         "( عليه السلام )", "صلى الله عليه وآله"}) {
            assertEquals("قال AND الصلاة",
                    service.enhanceQuery("قال " + form + " الصلاة", QueryMode.SEARCH, true),
                    form);
        }
    }

    @Test
    void aQueryThatIsOnlyASalutationKeepsIt() {
        assertEquals("عليه AND السلام",
                service.enhanceQuery("عليه السلام", QueryMode.SEARCH, true));
    }

    @Test
    void aTatweelOnItsOwnLeavesNoEmptyTerm() {
        // «ـ» is all diacritic; dropping it must not leave «AND  AND»
        assertEquals(
                "الصلاة AND خير",
                service.enhanceQuery("الصلاة ـ خير", QueryMode.SEARCH, true)
        );
    }

    @Test
    void anUnclosedQuoteIsText() {
        assertEquals(
                "ما AND معنى AND \\\"الصلاة",
                service.enhanceQuery("ما معنى \"الصلاة", QueryMode.SEARCH, true)
        );
    }

    @Test
    void unbalancedParenthesesAreText() {
        assertEquals(
                "قال AND \\(ع AND الصلاة",
                service.enhanceQuery("قال (ع الصلاة", QueryMode.SEARCH, true)
        );
    }

    @Test
    void aScopedFieldKeepsItsColon() {
        assertEquals(
                "book:\"Al-Kāfi\" AND الصلاة",
                service.enhanceQuery("book:\"Al-Kāfi\" الصلاة", QueryMode.SEARCH, true)
        );
    }

    @Test
    void theIdAliasStillWorks() {
        assertEquals(
                "_id:Al-Kafi-Volume-6-Kulayni\\:2356",
                service.enhanceQuery("id:Al-Kafi-Volume-6-Kulayni:2356", QueryMode.SEARCH, true)
        );
    }

    @Test
    void balancedParenthesesThatAreNotASalutationAreLeftAlone() {
        assertEquals(
                "قال AND (الكافي) AND الصلاة",
                service.enhanceQuery("قال (الكافي) الصلاة", QueryMode.SEARCH, true)
        );
    }
}
