package com.rewayaat.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewayaatTermTest {

    @Test
    void isArabic_returnsTrueForArabicCharacters() {
        RewayaatTerm term = new RewayaatTerm("\u0633\u0644\u0627\u0645");
        assertTrue(term.isArabic());
    }

    @Test
    void isArabic_returnsFalseForLatinCharacters() {
        RewayaatTerm term = new RewayaatTerm("salaam");
        assertFalse(term.isArabic());
    }
}
