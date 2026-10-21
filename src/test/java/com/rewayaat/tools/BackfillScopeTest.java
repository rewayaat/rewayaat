package com.rewayaat.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackfillScopeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void unsetMeansTheWholeIndex() {
        assertEquals("{\"match_all\":{}}", BackfillScope.query(MAPPER, null).toString());
        assertEquals("{\"match_all\":{}}", BackfillScope.query(MAPPER, " ").toString());
    }

    @Test
    void aListOfBooksLimitsTheScroll() {
        assertEquals("{\"terms\":{\"book\":[\"Ghurar al-Ḥikam\",\"Mīzān al-Ḥikma\"]}}",
                BackfillScope.query(MAPPER, "Ghurar al-Ḥikam | Mīzān al-Ḥikma").toString());
    }
}
