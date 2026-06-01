package com.rewayaat.core.data;

/**
 * Lightweight representation of a narrator parsed from a hadith chain.
 */
public record ExtractedNarrator(
        String rawArabicName,
        String rawEnglishName,
        String normalizedArabic,
        String normalizedEnglish,
        String narratorId,
        boolean isImamOrProphet,
        boolean isDoubtful,
        String doubtfulReason
) {}
