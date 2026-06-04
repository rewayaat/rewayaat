package com.rewayaat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rewayaat.config.ESClientProvider;
import com.rewayaat.core.HadithDisplaySegmenter;
import com.rewayaat.core.HadithObjectCollection;
import com.rewayaat.core.data.HadithObject;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.MgetResponse;
import co.elastic.clients.elasticsearch.core.mget.MultiGetResponseItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Similar hadith retrieval using pre-computed LLM-judged pairs stored in ES.
 */
@Service
public class SimilarHadithService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SimilarHadithService.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final Cache<String, CachedResult> cache = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(Duration.ofHours(6))
            .build();

    public HadithObjectCollection findSimilar(String hadithId, int page, int pageSize) {
        if (hadithId == null || hadithId.trim().isEmpty()) {
            return new HadithObjectCollection(new ArrayList<>(), 0);
        }
        String safeId = hadithId.trim();
        int safePage = Math.max(page, 0);
        int safePageSize = Math.max(pageSize, 0);

        CachedResult cached = cache.getIfPresent(safeId);
        if (cached == null) {
            cached = loadFromES(safeId);
            if (cached != null) {
                cache.put(safeId, cached);
            }
        }
        if (cached == null || cached.items.isEmpty()) {
            return new HadithObjectCollection(new ArrayList<>(), 0);
        }

        if (safePageSize == 0) {
            return new HadithObjectCollection(new ArrayList<>(), cached.items.size());
        }

        int fromIndex = safePage * safePageSize;
        if (fromIndex >= cached.items.size()) {
            return new HadithObjectCollection(new ArrayList<>(), cached.items.size());
        }
        int toIndex = Math.min(fromIndex + safePageSize, cached.items.size());
        return new HadithObjectCollection(
                new ArrayList<>(cached.items.subList(fromIndex, toIndex)),
                cached.items.size());
    }

    private CachedResult loadFromES(String hadithId) {
        try (ESClientProvider provider = new ESClientProvider()) {
            // 1. Load source doc to get llm_similar field
            GetResponse<Map> sourceResp = provider.client().get(
                    g -> g.index(ESClientProvider.INDEX).id(hadithId),
                    Map.class);
            if (!sourceResp.found() || sourceResp.source() == null) {
                return null;
            }

            Object llmSimilarRaw = sourceResp.source().get("llm_similar");
            if (!(llmSimilarRaw instanceof List<?> llmSimilar) || llmSimilar.isEmpty()) {
                return new CachedResult(List.of());
            }

            // 2. Extract similar IDs with match metadata
            List<SimilarEntry> entries = new ArrayList<>();
            for (Object item : llmSimilar) {
                if (!(item instanceof Map<?, ?> m)) continue;
                String id = stringValue(m.get("id"));
                if (id == null || id.isBlank()) continue;
                entries.add(new SimilarEntry(id,
                        stringValue(m.get("match_type")),
                        stringValue(m.get("reason"))));
            }
            if (entries.isEmpty()) {
                return new CachedResult(List.of());
            }

            // 3. Bulk-fetch the similar hadith docs
            List<String> ids = entries.stream().map(e -> e.id).toList();
            MgetResponse<Map> mgetResponse = provider.client().mget(
                    r -> r.index(ESClientProvider.INDEX).ids(ids),
                    Map.class);

            // 4. Build HadithObject list, preserving order
            Map<String, Map<String, Object>> fetchedDocs = new HashMap<>();
            for (MultiGetResponseItem<Map> item : mgetResponse.docs()) {
                if (item.result() != null && item.result().found() && item.result().source() != null) {
                    fetchedDocs.put(item.result().id(), item.result().source());
                }
            }

            List<HadithObject> results = new ArrayList<>();
            for (SimilarEntry entry : entries) {
                Map<String, Object> doc = fetchedDocs.get(entry.id);
                if (doc == null) continue;
                Map<String, Object> mutableSource = new HashMap<>(doc);
                mutableSource.put("_id", entry.id);
                mutableSource.put("matchType", entry.matchType != null ? entry.matchType : "conceptual");
                if (entry.reason != null && !entry.reason.isBlank()) {
                    mutableSource.put("matchReason", entry.reason);
                }
                HadithDisplaySegmenter.enrich(mutableSource);
                results.add(mapper.convertValue(mutableSource, HadithObject.class));
            }

            return new CachedResult(results);
        } catch (Exception ex) {
            LOGGER.warn("Unable to load LLM similar hadith for id {}", hadithId, ex);
            return null;
        }
    }

    private static String stringValue(Object o) {
        return o == null ? null : o.toString();
    }

    private record SimilarEntry(String id, String matchType, String reason) {}
    private record CachedResult(List<HadithObject> items) {}
}
