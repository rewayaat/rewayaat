package com.rewayaat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NarratorSealTest {

    @Test
    @DisplayName("a kunya takes the following word, because أبو alone identifies nobody")
    void kunyaCarriesTheNextWord() {
        assertEquals("أبو رافع", NarratorSeal.monogram(
                List.of("أبو رافع مولى رسول الله", "أبو رافع"), null));
        assertEquals("عبد الله", NarratorSeal.monogram(List.of("عبد الله بن سنان"), null));
    }

    @Test
    @DisplayName("an ordinary name uses its first word")
    void plainNameUsesItsHead() {
        assertEquals("علي", NarratorSeal.monogram(List.of("علي بن أبي رافع"), null));
        assertEquals("إسماعيل", NarratorSeal.monogram(List.of("إسماعيل بن الحكم الرافعي"), null));
    }

    @Test
    @DisplayName("the shortest recorded name wins, since the list runs up to whole biographies")
    void shortestNameWins() {
        assertEquals("محمد", NarratorSeal.monogram(
                List.of("محمد بن يعقوب الكليني صاحب الكافي وهو ثقة", "محمد بن يعقوب"), null));
    }

    @Test
    @DisplayName("falls back to the display name, then to nothing")
    void fallsBack() {
        assertEquals("زرارة", NarratorSeal.monogram(List.of(), "زرارة بن أعين"));
        assertNull(NarratorSeal.monogram(List.of(), null));
        assertNull(NarratorSeal.monogram(null, "  "));
    }

    @Test
    @DisplayName("the ring treatment is stable per person and spreads across people")
    void variantIsStableAndSpread() {
        assertEquals(NarratorSeal.variant("n000001"), NarratorSeal.variant("n000001"));
        assertTrue(NarratorSeal.variant("n000001") >= 0 && NarratorSeal.variant("n000001") < 6);
        // Not a guarantee for every pair, but consecutive ids must not all collapse to one.
        assertNotEquals(NarratorSeal.variant("n000001"), NarratorSeal.variant("n000002"));
    }
}
