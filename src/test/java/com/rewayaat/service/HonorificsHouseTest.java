package com.rewayaat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Which of the Fourteen an id names.
 *
 * <p>The index files all fourteen under {@code kind: "imam"}, and every page that read that
 * one field printed «Imam The Prophet Muḥammad (a.s.)». Twelve of them are Imams; the
 * Prophet and Fāṭima are not, and the ids are the only place the three are distinguishable.
 */
class HonorificsHouseTest {

    private final Honorifics honorifics = new Honorifics();

    @Test
    @DisplayName("The Prophet is not an Imam")
    void prophetIsNotAnImam() {
        assertEquals("prophet", honorifics.houseOf("i00"));
    }

    @Test
    @DisplayName("Fāṭima is not an Imam")
    void ladyIsNotAnImam() {
        assertEquals("lady", honorifics.houseOf("i13"));
    }

    @Test
    @DisplayName("The twelve are")
    void theTwelveAre() {
        for (int ordinal = 1; ordinal <= 12; ordinal++) {
            String id = String.format("i%02d", ordinal);
            assertEquals("imam", honorifics.houseOf(id), id + " should be an Imam");
        }
    }

    @Test
    @DisplayName("A narrator belongs to none of the three, however his id is shaped")
    void narratorsAreNotOfTheHouse() {
        assertNull(honorifics.houseOf("n000515"));
        assertNull(honorifics.houseOf("n014921"));
        assertNull(honorifics.houseOf(null));
        assertNull(honorifics.houseOf(""));
        // Ids past the roster are nobody, rather than quietly reading as an Imam.
        assertNull(honorifics.houseOf("i14"));
        assertNull(honorifics.houseOf("i99"));
        assertNull(honorifics.houseOf("i0"));
        assertNull(honorifics.houseOf("iXY"));
    }

    @Test
    @DisplayName("The salutations stay as they were: the Prophet's is not the Imams'")
    void salutationsAreUnchanged() {
        assertEquals("the Prophet (ṣ)", honorifics.of("the Prophet", false));
        assertEquals("al-Ṣādiq (ʿa)", honorifics.of("al-Ṣādiq", false));
        assertEquals("فاطمة عليها السلام", honorifics.of("فاطمة", true));
    }

    @Test
    @DisplayName("The Prophet's own heading takes his salutation; nobody else's does")
    void theProphetsHeadingIsSaluted() {
        assertEquals("The Prophet Muḥammad (ṣ)", honorifics.titled("The Prophet Muḥammad", "i00", false));
        assertEquals("The Prophet Muḥammad (ṣ)", honorifics.titled("The Prophet Muḥammad (ṣ)", "i00", false));
        assertEquals("Zurarah ibn A'yan", honorifics.titled("Zurarah ibn A'yan", "n000001", false));
        assertEquals("", honorifics.titled(null, "i00", false));
    }
}
