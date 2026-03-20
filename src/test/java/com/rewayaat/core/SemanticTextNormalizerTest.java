package com.rewayaat.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticTextNormalizerTest {

    @Test
    void stripsEnglishChainPrefixFromMatn() {
        String raw = "[3/1564] al-Kafi: Muhammad b. Yahya from Ahmad b. Muhammad from Ali b. al-Hakam "
                + "from Hisham b. Salim from Abi Abdillah عليه السلام who said: "
                + "The Qur'an that Jibril came with to Muhammad صلى الله عليه وآله was seventeen thousand verses";

        String normalized = SemanticTextNormalizer.normalizeMatn(raw, 1200);

        assertTrue(normalized.contains("seventeen thousand verses"));
        assertFalse(normalized.toLowerCase().contains("muhammad b. yahya from"));
    }

    @Test
    void stripsArabicChainPrefixFromMatn() {
        String raw = "الكافي: عن محمد بن يحيى، عن أحمد بن محمد، عن ابن محبوب، عن أبي أيوب الخزاز، "
                + "عن سليمان بن خالد، عن أبي عبدالله عليه السلام قال ما من أحد يموت من المؤمنين أحب إلى إبليس من موت فقيه";

        String normalized = SemanticTextNormalizer.normalizeMatn(raw, 1200);

        assertTrue(normalized.startsWith("ما من أحد يموت"));
        assertFalse(normalized.contains("عن محمد بن يحيى"));
    }

    @Test
    void keepsCoreContentWhenTextIsAlreadyMatn() {
        String raw = "قال أبو عبدالله: الإيمان معرفة بالقلب.";
        String normalized = SemanticTextNormalizer.normalizeMatn(raw, 1200);
        assertTrue(normalized.contains("الإيمان معرفة بالقلب"));
    }
}
