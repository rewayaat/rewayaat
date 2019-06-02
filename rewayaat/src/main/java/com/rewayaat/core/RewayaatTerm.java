package com.rewayaat.core;

/**
 * A Rewayaat Database term.
 */
public class RewayaatTerm {

    private final String term;

    public RewayaatTerm(String term) {
        this.term = term;
    }

    public boolean isArabic() {
        for (int i = 0; i < this.term.length(); ) {
            int c = this.term.codePointAt(i);
            if (c >= 0x0600 && c <= 0x06E0) {
                return true;
            }
            i += Character.charCount(c);
        }
        return false;
    }
}
