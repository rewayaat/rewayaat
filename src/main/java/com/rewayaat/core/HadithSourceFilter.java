package com.rewayaat.core;

import co.elastic.clients.elasticsearch.core.search.SourceConfig;

import java.util.List;

/**
 * Central definition of hadith document fields that must never be fetched from
 * Elasticsearch when the document is on its way to a client.
 *
 * <p>{@code semantic_vector} is a 1024-dimension dense vector used only for kNN
 * scoring. A kNN query references the field by name in the query itself, which does
 * not require the field to be present in {@code _source}, so excluding it here is
 * safe for similarity search.</p>
 *
 * <p>Measured on the live index: a document fetched from Elasticsearch shrinks about
 * 5x (26.5 KB to 4.8 KB for a single hit), and {@code /v1/narrations?q=prayer&per_page=5}
 * drops from 149 KB to 41 KB -- roughly 3.6x, the gap between the two being response
 * scaffolding and the fields the vector was dwarfing.</p>
 *
 * <p>Note that {@link com.rewayaat.core.data.HadithObject} round-trips unknown fields
 * through {@code @JsonAnySetter}/{@code @JsonAnyGetter}, so anything left in
 * {@code _source} is serialised straight back out to the client. Filtering at the
 * Elasticsearch level also saves the Elasticsearch-to-application transfer.</p>
 *
 * <p><strong>Do not</strong> use this filter on read paths that feed a write back into
 * Elasticsearch (for example the narration edit endpoint, which re-indexes the whole
 * document): dropping the vector there would delete it from the index.</p>
 */
public final class HadithSourceFilter {

    /** Fields stripped from every hadith {@code _source} fetched for client consumption. */
    public static final List<String> EXCLUDED_FIELDS = List.of("semantic_vector");

    private static final SourceConfig SEARCH_SOURCE = SourceConfig.of(
            s -> s.filter(f -> f.excludes(EXCLUDED_FIELDS)));

    private HadithSourceFilter() {
    }

    /** Source configuration for search requests ({@code SearchRequest.Builder#source}). */
    public static SourceConfig searchSource() {
        return SEARCH_SOURCE;
    }

    /** Excluded field names for get/mget requests ({@code sourceExcludes}). */
    public static List<String> excludes() {
        return EXCLUDED_FIELDS;
    }
}
