package com.rewayaat.service;

import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rewayaat.config.ESClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads narrators out of the narrator index.
 *
 * A narrator document is one man as the eight Rijal books have him: his names, an `entries`
 * record for each entry a book heads him under — the book, its page, its own words — and the
 * verdicts, each pointing back at the entry it was read from. The index is built by
 * scripts/narrators/index_people.py from the decision record, so the service only reads.
 *
 * The document id is the permanent person id, which survives a rebuild.
 */
@Service
public class NarratorService {

    private static final Logger LOGGER = LoggerFactory.getLogger(NarratorService.class);
    private static final int MAX_PAGE_SIZE = 50;

    @Value("${narrator.index:rewayaat_narrators}")
    private String indexName;

    @Value("${narrator.enabled:true}")
    private boolean enabled;

    private final Cache<String, Map<String, Object>> cache = Caffeine.newBuilder()
            .maximumSize(5000)
            .expireAfterWrite(Duration.ofHours(6))
            .build();

    /** One narrator by his permanent id, or null where the index does not hold him. */
    public Map<String, Object> getNarrator(String personId) {
        if (!enabled || personId == null || personId.trim().isEmpty()) {
            return null;
        }
        String id = personId.trim();
        Map<String, Object> cached = cache.getIfPresent(id);
        if (cached != null) {
            return cached;
        }
        try (ESClientProvider provider = new ESClientProvider()) {
            @SuppressWarnings("unchecked")
            GetResponse<Map> response = provider.client()
                    .get(g -> g.index(indexName).id(id), Map.class);
            if (!response.found() || response.source() == null) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> source = (Map<String, Object>) response.source();
            cache.put(id, source);
            return source;
        } catch (Exception e) {
            LOGGER.warn("Could not read narrator {}: {}", id, e.getMessage());
            return null;
        }
    }

    /**
     * Narrators whose names answer to a query, the fullest entries first.
     *
     * A man is searched for by every form the books head him under, in Arabic or in
     * transliteration, so «الآدمي» and «Sahl b. Ziyad» both reach the same person.
     */
    public Map<String, Object> search(String query, int page, int pageSize) {
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("total", 0);
        empty.put("narrators", new ArrayList<>());
        if (!enabled || query == null || query.trim().isEmpty()) {
            return empty;
        }
        String text = query.trim();
        int size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        int from = Math.max(page - 1, 0) * size;
        try (ESClientProvider provider = new ESClientProvider()) {
            SearchResponse<Map> response = provider.client().search(s -> s
                    .index(indexName)
                    .from(from)
                    .size(size)
                    // Transliteration is never settled — Zurara, Zurarah, Zurāra — and Arabic
                    // headings differ by a letter as often as by a word, so a name is matched
                    // with one edit allowed.
                    //
                    // The match alone ranks badly: «عمير بن زرارة», a man named once in a chain,
                    // holds the word Zurara in a shorter field than Zurara b. A'yan's own heading
                    // and so scores above him. How much the books write about a man is the best
                    // guide to which one a reader means, so the score is weighted by how many
                    // entries he holds — by its logarithm, so that eleven entries count for more
                    // than one without a famous man swallowing every query near his name.
                    .query(q -> q.functionScore(fs -> fs
                            .query(inner -> inner.multiMatch(m -> m
                                    .query(text)
                                    .fuzziness("AUTO")
                                    .fields("display_ar^4", "display_en^4", "names_ar^2",
                                            "names_en^2", "normalized_ar", "normalized_en",
                                            "kunyahs_ar", "titles")))
                            .functions(f -> f.fieldValueFactor(v -> v
                                    .field("entry_count")
                                    .modifier(FieldValueFactorModifier.Log1p)
                                    .missing(1.0)))
                            .boostMode(FunctionBoostMode.Multiply)))
                    // a result row needs the names and the counts, not every entry's full text
                    .source(src -> src.filter(f -> f.excludes("entries", "narrated_from",
                            "narrated_to"))), Map.class);

            List<Map<String, Object>> narrators = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                if (hit.source() == null) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> source = (Map<String, Object>) hit.source();
                narrators.add(source);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total", response.hits().total() == null ? narrators.size()
                    : response.hits().total().value());
            result.put("page", Math.max(page, 1));
            result.put("page_size", size);
            result.put("narrators", narrators);
            return result;
        } catch (Exception e) {
            LOGGER.warn("Narrator search failed for «{}»: {}", text, e.getMessage());
            return empty;
        }
    }
}
