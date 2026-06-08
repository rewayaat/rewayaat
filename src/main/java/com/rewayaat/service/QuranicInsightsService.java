package com.rewayaat.service;

import co.elastic.clients.elasticsearch.core.GetResponse;
import com.rewayaat.config.ESClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads precomputed hadith-to-Quran candidates from the quranic light index.
 */
@Service
public class QuranicInsightsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuranicInsightsService.class);

    @Value("${quranic.insights.enabled:true}")
    private boolean enabled;

    @Value("${quranic.insights.index:rewayaat_quranic_light_filtered}")
    private String indexName;

    @Value("${quranic.insights.max-candidates:10}")
    private int maxCandidates;

    public Map<String, Object> insightOverview(String hadithId, boolean countOnly, boolean all) {
        LightDocument document = loadLightDocument(hadithId);
        int count = document == null ? 0 : document.count();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("count", count);
        if (!countOnly && document != null) {
            java.util.stream.Stream<Candidate> candidates = document.candidates().stream();
            if (!all) {
                candidates = candidates.limit(Math.max(1, maxCandidates));
            }
            payload.put("candidates", candidates
                    .map(this::toOverviewMap)
                    .toList());
        } else if (!countOnly) {
            payload.put("candidates", List.of());
        }
        return payload;
    }

    private LightDocument loadLightDocument(String hadithId) {
        String safeId = safeText(hadithId);
        if (!enabled || safeId.isBlank()) {
            return null;
        }
        try (ESClientProvider provider = new ESClientProvider()) {
            GetResponse<Map> response = provider.client().get(g -> g.index(indexName).id(safeId), Map.class);
            if (!response.found() || response.source() == null) {
                return null;
            }
            return parseLightDocument(response.source(), safeId);
        } catch (Exception ex) {
            LOGGER.warn("Unable to load Quranic light document for hadith {}", hadithId, ex);
            return null;
        }
    }

    private LightDocument parseLightDocument(Map source, String hadithId) {
        List<Candidate> candidates = new ArrayList<>();
        for (Map<String, Object> rawCandidate : readMapList(source.get("candidates"))) {
            String verseKey = safeText(rawCandidate.get("verse_key"));
            if (verseKey.isBlank()) {
                continue;
            }
            List<TafsirSnippet> snippets = readMapList(rawCandidate.get("tafsir_snippets")).stream()
                    .map(this::parseSnippet)
                    .filter(snippet -> !snippet.commentaryText().isBlank())
                    .sorted(Comparator.comparingDouble(TafsirSnippet::commentaryScore).reversed())
                    .toList();
            Candidate candidate = new Candidate(
                    verseKey,
                    safeText(rawCandidate.get("surah_name_english")),
                    intValue(rawCandidate.get("surah_number")),
                    intValue(rawCandidate.get("ayah_number")),
                    safeText(rawCandidate.get("text_english")),
                    safeText(rawCandidate.get("text_arabic")),
                    doubleValue(rawCandidate.get("combined_score")),
                    readStringList(rawCandidate.get("shared_tags")),
                    snippets
            );
            candidates.add(candidate);
        }
        candidates.sort(Comparator
                .comparingDouble(Candidate::combinedScore).reversed()
                .thenComparing(Candidate::verseKey));
        int count = intValue(source.get("candidate_count"));
        if (count <= 0) {
            count = candidates.size();
        }
        return new LightDocument(
                hadithId,
                safeText(source.get("hadith_book")),
                safeText(source.get("hadith_number")),
                safeText(source.get("hadith_chapter")),
                safeText(source.get("hadith_section")),
                safeText(source.get("hadith_english")),
                safeText(source.get("hadith_semantic_matn_source")),
                safeText(source.get("hadith_semantic_english_hint_source")),
                count,
                candidates
        );
    }

    private TafsirSnippet parseSnippet(Map<String, Object> source) {
        return new TafsirSnippet(
                safeText(source.get("tafsir_slug")),
                safeText(source.get("tafsir_name")),
                safeText(source.get("commentary_text")),
                safeText(source.get("commentary_text_highlighted")),
                safeText(source.get("source_url")),
                safeText(source.get("section_title")),
                doubleValue(source.get("commentary_score"))
        );
    }

    private Map<String, Object> toOverviewMap(Candidate candidate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("verse_key", candidate.verseKey());
        payload.put("reference", candidate.referenceLabel());
        payload.put("surah_name_english", candidate.surahNameEnglish());
        payload.put("surah_number", candidate.surahNumber());
        payload.put("ayah_number", candidate.ayahNumber());
        payload.put("text_english", candidate.textEnglish());
        payload.put("text_arabic", candidate.textArabic());
        payload.put("combined_score", candidate.combinedScore());
        payload.put("shared_tags", candidate.sharedTags());
        payload.put("tafsir_snippet_count", candidate.snippets().size());
        payload.put("sources", candidate.sourceNames());
        List<Map<String, Object>> snippets = new ArrayList<>();
        for (TafsirSnippet snippet : candidate.snippets()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("tafsir_slug", snippet.tafsirSlug());
            s.put("tafsir_name", snippet.tafsirName());
            s.put("commentary_text", snippet.commentaryText());
            s.put("commentary_text_highlighted", snippet.commentaryTextHighlighted());
            s.put("source_url", snippet.sourceUrl());
            s.put("section_title", snippet.sectionTitle());
            s.put("commentary_score", snippet.commentaryScore());
            snippets.add(s);
        }
        payload.put("tafsir_snippets", snippets);
        return payload;
    }

    private List<Map<String, Object>> readMapList(Object raw) {
        if (!(raw instanceof List<?> items)) {
            return List.of();
        }
        List<Map<String, Object>> values = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> value = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() != null) {
                        value.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                values.add(value);
            }
        }
        return values;
    }

    private List<String> readStringList(Object raw) {
        if (!(raw instanceof List<?> items)) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (Object item : items) {
            String value = safeText(item);
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return new ArrayList<>(values);
    }

    private String safeText(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    private int intValue(Object raw) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(safeText(raw));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private double doubleValue(Object raw) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(safeText(raw));
        } catch (NumberFormatException ex) {
            return 0d;
        }
    }

    private String compactWhitespace(String value) {
        return safeText(value).replaceAll("\\s+", " ").trim();
    }

    private record LightDocument(
            String hadithId,
            String hadithBook,
            String hadithNumber,
            String hadithChapter,
            String hadithSection,
            String hadithEnglish,
            String hadithArabic,
            String hadithEnglishHint,
            int count,
            List<Candidate> candidates) {

        private Candidate findCandidate(String verseKey) {
            String safeVerseKey = verseKey == null ? "" : verseKey.trim();
            if (safeVerseKey.isBlank()) {
                return null;
            }
            for (Candidate candidate : candidates) {
                if (safeVerseKey.equals(candidate.verseKey())) {
                    return candidate;
                }
            }
            return null;
        }
    }

    private record Candidate(
            String verseKey,
            String surahNameEnglish,
            int surahNumber,
            int ayahNumber,
            String textEnglish,
            String textArabic,
            double combinedScore,
            List<String> sharedTags,
            List<TafsirSnippet> snippets) {

        private String referenceLabel() {
            if (!surahNameEnglish.isBlank() && ayahNumber > 0) {
                return surahNameEnglish + " " + ayahNumber;
            }
            if (surahNumber > 0 && ayahNumber > 0) {
                return surahNumber + ":" + ayahNumber;
            }
            return verseKey;
        }

        private List<String> sourceNames() {
            LinkedHashSet<String> names = new LinkedHashSet<>();
            for (TafsirSnippet snippet : snippets) {
                if (!snippet.tafsirName().isBlank()) {
                    names.add(snippet.tafsirName());
                }
            }
            return new ArrayList<>(names);
        }
    }

    private record TafsirSnippet(
            String tafsirSlug,
            String tafsirName,
            String commentaryText,
            String commentaryTextHighlighted,
            String sourceUrl,
            String sectionTitle,
            double commentaryScore) {

        private String sourceLabel() {
            if (!tafsirName.isBlank()) {
                return tafsirName;
            }
            if (!tafsirSlug.isBlank()) {
                return tafsirSlug.replace('-', ' ').toUpperCase(Locale.ROOT);
            }
            return "Unknown Source";
        }
    }
}
