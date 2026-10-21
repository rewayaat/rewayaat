package com.rewayaat.controllers;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyIdRedirectsTest {

    private final LegacyIdRedirects redirects =
            new LegacyIdRedirects(Map.of("30611", "Ghurar-al-Hikam-Amidi:417"));

    @Test
    void aRestoredV1IdMovesToItsNarrationPage() {
        assertEquals(Optional.of("/hadith/Ghurar-al-Hikam-Amidi:417"), redirects.target("_id:30611"));
    }

    /** An id with no exact entry is never guessed at: a wrong target is worse than a dead link. */
    @Test
    void anUnmappedV1IdIsLeftAlone() {
        assertEquals(Optional.empty(), redirects.target("_id:AXMd1nBSoW8eiOSs8d1H"));
    }

    @Test
    void anOrdinarySearchIsNotARedirect() {
        assertEquals(Optional.empty(), redirects.target("patience _id:30611"));
        assertEquals(Optional.empty(), redirects.target(""));
        assertEquals(Optional.empty(), redirects.target(null));
    }

    @Test
    void theShippedMapCarriesTheSearchConsoleUrl() {
        assertTrue(new LegacyIdRedirects().target("_id:30611").orElse("")
                .startsWith("/hadith/Ghurar-al-Hikam-Amidi:"));
    }
}
