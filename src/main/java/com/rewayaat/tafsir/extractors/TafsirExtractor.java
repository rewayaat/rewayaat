package com.rewayaat.tafsir.extractors;

import com.rewayaat.tafsir.TafsirDocument;

import java.util.List;

/**
 * Interface for tafsir content extractors.
 * Each extractor is responsible for extracting documents from a specific source.
 */
public interface TafsirExtractor {

    /**
     * Extracts all tafsir documents from this source.
     * Returns a list of documents ready for indexing.
     */
    List<TafsirDocument> extract() throws ExtractionException;

    /**
     * Returns the name/slug of this tafsir source.
     */
    String getTafsirSlug();

    /**
     * Returns the display name of this tafsir source.
     */
    String getTafsirName();

    /**
     * Exception thrown when extraction fails.
     */
    class ExtractionException extends Exception {
        public ExtractionException(String message) {
            super(message);
        }

        public ExtractionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
