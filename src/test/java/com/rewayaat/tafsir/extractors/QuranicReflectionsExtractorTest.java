package com.rewayaat.tafsir.extractors;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuranicReflectionsExtractorTest {

    private final QuranicReflectionsExtractor extractor = new QuranicReflectionsExtractor();

    private static Document page(String body) {
        return Jsoup.parseBodyFragment(body);
    }

    @Test
    void theSeasonalHadithAboveTheVerseIsNotPartOfTheReflection() {
        // The shape of the 2:184 issue: a Laylat al-Qadr hadith, the verse in translation,
        // its citation in brackets, then the reflection.
        Document html = page("<p>Bismillah. Prophet Muhammad (s): And when it is night of Qadr, Allah commands"
                + " Jibra'il to descend to earth. (Al-Mufid, Al-Amali, 27th Assembly, H 3)</p>"
                + "<p>And those who find it straining shall be liable to atonement by feeding a needy person.</p>"
                + "<p>(Sūrat al-Baqarah No. 2, Āyat 184)</p>"
                + "<p>The verse above is the second of three discussing the obligation of fasting.</p>"
                + "<p>It addresses fidyah, a compensatory payment.</p>");

        String text = extractor.extractCommentaryText(html, null);

        assertTrue(text.startsWith("The verse above is the second of three"), text);
        assertFalse(text.contains("night of Qadr"));
        assertFalse(text.contains("feeding a needy person"));
    }

    @Test
    void anAdvertAtTheTopIsDropped() {
        Document html = page("<p>Check our new online courses here; or see the details below.</p>"
                + "<p>Sūrat al-Nisā, No. 4, Āyat 86</p>"
                + "<p>The greeting of salām is a du'a for the person greeted.</p>");

        assertEquals("The greeting of salām is a du'a for the person greeted.",
                extractor.extractCommentaryText(html, null));
    }

    @Test
    void aCitationLateInTheReflectionDoesNotEraseIt() {
        StringBuilder body = new StringBuilder();
        for (int i = 1; i <= 7; i++) {
            body.append("<p>Paragraph ").append(i).append(" of the reflection.</p>");
        }
        body.append("<p>(Sūrat al-Baqarah No. 2, Āyat 185)</p><p>Closing paragraph.</p>");

        String text = extractor.extractCommentaryText(page(body.toString()), null);

        assertTrue(text.startsWith("Paragraph 1 of the reflection."), text);
        assertTrue(text.endsWith("Closing paragraph."));
    }
}
