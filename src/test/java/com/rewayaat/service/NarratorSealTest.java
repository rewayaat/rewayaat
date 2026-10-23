package com.rewayaat.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seal and the generations strip: the two marks a narrator is recognised by, and the
 * things about them a reader would notice if they broke.
 */
class NarratorSealTest {

    private final NarratorSeal seals = new NarratorSeal();

    @Test
    void theStripInksOnlyTheImamsHeTransmittedFrom() {
        String strip = seals.generations(List.of(7, 8), "of the generation of al-Kāẓim and al-Riḍā");

        assertEquals(13, strip.split("<i ", -1).length - 1, "the Prophet and the twelve Imams");
        assertEquals(2, strip.split("is-lit", -1).length - 1, "two of them inked");
        assertTrue(strip.contains("aria-label=\"of the generation of al-Kāẓim and al-Riḍā\""),
                "a screen reader is given the sentence, not thirteen unlabelled marks");
    }

    @Test
    void aManThePlacedUnderNoImamGetsNoStripAtAll() {
        // Thirteen blank marks would say «we looked and found none», which the record does
        // not claim: 14,330 of 15,518 men are simply never placed.
        assertEquals("", seals.generations(List.of(), "anything"));
        assertEquals("", seals.generations(null, "anything"));
    }

    @Test
    void anOrdinalOutsideTheTwelveIsIgnoredRatherThanDrawn() {
        assertEquals("", seals.generations(List.of(13, -1), ""));
        String strip = seals.generations(List.of(13, 4), "");
        assertEquals(1, strip.split("is-lit", -1).length - 1);
        assertTrue(strip.contains("aria-hidden=\"true\""), "no label, so no empty one is printed");
    }

    @Test
    void theTintAndTheSealReadTheSamePigment() {
        // Both take the earliest Imam he transmitted from, so a page's wash and its seal
        // cannot come out of two different generations.
        String tint = seals.tint(List.of(9, 7, 11));
        assertTrue(tint.startsWith("hsl(104 "), "the pigment of ordinal 7, the lowest given");
        assertTrue(tint.endsWith("/ 0.10)"), "a wash, not a fill");
        assertTrue(seals.of("n1", "الحسن", List.of(9, 7, 11), 100, 84).contains("hsl(104 32% 34%)"));
    }

    @Test
    void aManWithNoImamOnRecordGetsNoWash() {
        assertEquals("transparent", seals.tint(null));
        assertEquals("transparent", seals.tint(List.of()));
    }

    @Test
    void theRingIsDroppedBelowTheSizeItCouldBeReadAt() {
        // At 24 or 32px a 5-unit stroke is under two device pixels and reads as dirt.
        assertFalse(seals.of("n1", "الحسن", List.of(7), 4056, 32).contains("nseal__ring"));
        assertTrue(seals.of("n1", "الحسن", List.of(7), 4056, 44).contains("nseal__ring"));
    }

    @Test
    void theInitialDropsTheArticleAndSurvivesAnEmptyName() {
        assertEquals("ح", NarratorSeal.initial("الحسن بن محبوب"));
        assertEquals("س", NarratorSeal.initial("سهل بن زياد"));
        assertEquals("؟", NarratorSeal.initial(""));
        assertEquals("؟", NarratorSeal.initial(null));
    }

    @Test
    void aLabelWithAQuoteInItCannotBreakOutOfTheAttribute() {
        String strip = seals.generations(List.of(3), "al-\"Sajjād\" & co");
        assertTrue(strip.contains("aria-label=\"al-&quot;Sajjād&quot; &amp; co\""));
    }
}
