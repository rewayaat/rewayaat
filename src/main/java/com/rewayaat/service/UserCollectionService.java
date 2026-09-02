package com.rewayaat.service;

import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.config.ESClientProvider;
import com.rewayaat.core.HadithObjectCollection;
import com.rewayaat.core.HadithSourceFilter;
import com.rewayaat.core.data.HadithObject;
import com.rewayaat.core.data.UserCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service for managing user hadith collections.
 */
@Service
public class UserCollectionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserCollectionService.class);

    public static final String COLLECTIONS_INDEX = firstNonEmpty(
            System.getProperty("REWAYAAT_COLLECTIONS_INDEX"),
            System.getenv("REWAYAAT_COLLECTIONS_INDEX"),
            "rewayaat_user_collections");
    public static final int MAX_HADITHS_PER_COLLECTION = 50;

    private final ObjectMapper mapper = new ObjectMapper();

    public List<UserCollection> listCollections(String ownerEmail) throws Exception {
        List<UserCollection> result = new ArrayList<>();
        try (ESClientProvider provider = new ESClientProvider()) {
            SearchResponse<Map> response = provider.client().search(s -> s
                    .index(COLLECTIONS_INDEX)
                    .size(200)
                    .query(q -> q.term(t -> t.field("owner_email.keyword").value(ownerEmail)))
                    .sort(st -> st.field(f -> f.field("updated_at").order(co.elastic.clients.elasticsearch._types.SortOrder.Desc))), Map.class);
            for (Hit<Map> hit : response.hits().hits()) {
                if (hit.source() == null) {
                    continue;
                }
                Map<String, Object> map = new HashMap<>(hit.source());
                map.put("id", hit.id());
                result.add(mapper.convertValue(map, UserCollection.class));
            }
        } catch (Exception ex) {
            if (isIndexMissing(ex)) {
                return result;
            }
            throw ex;
        }
        return result;
    }

    public UserCollection getCollection(String ownerEmail, String collectionId) throws Exception {
        UserCollection collection = findCollectionById(collectionId);
        if (collection == null || !ownerEmail.equals(collection.getOwnerEmail())) {
            return null;
        }
        return collection;
    }

    public UserCollection createCollection(String ownerEmail, String name) throws Exception {
        String cleanName = sanitizeName(name);
        long now = System.currentTimeMillis();
        UserCollection collection = new UserCollection();
        collection.setId(UUID.randomUUID().toString());
        collection.setOwnerEmail(ownerEmail);
        collection.setName(cleanName);
        collection.setHadithIds(new ArrayList<>());
        collection.setCreatedAt(now);
        collection.setUpdatedAt(now);
        saveCollection(collection);
        return collection;
    }

    public UserCollection quickSaveHadith(String ownerEmail, String collectionName, String hadithId) throws Exception {
        if (hadithId == null || hadithId.trim().isEmpty()) {
            throw new IllegalArgumentException("Hadith id is required.");
        }
        String cleanName = sanitizeName(collectionName);
        UserCollection collection = findCollectionByName(ownerEmail, cleanName);
        if (collection == null) {
            collection = createCollection(ownerEmail, cleanName);
        }
        List<String> hadithIds = collection.getHadithIds();
        if (hadithIds == null) {
            hadithIds = new ArrayList<>();
            collection.setHadithIds(hadithIds);
        }
        String trimmedId = hadithId.trim();
        if (!hadithIds.contains(trimmedId)) {
            ensureCollectionCapacity(hadithIds.size() + 1);
            hadithIds.add(trimmedId);
            collection.setUpdatedAt(System.currentTimeMillis());
            saveCollection(collection);
        }
        return collection;
    }

    public UserCollection quickSaveHadithBatch(String ownerEmail, String collectionName, List<String> hadithIds) throws Exception {
        if (hadithIds == null || hadithIds.isEmpty()) {
            throw new IllegalArgumentException("At least one hadith id is required.");
        }
        String cleanName = sanitizeName(collectionName);
        UserCollection collection = findCollectionByName(ownerEmail, cleanName);
        if (collection == null) {
            collection = createCollection(ownerEmail, cleanName);
        }

        LinkedHashSet<String> merged = new LinkedHashSet<>();
        List<String> existing = collection.getHadithIds();
        if (existing != null) {
            for (String id : existing) {
                if (id == null) {
                    continue;
                }
                String trimmed = id.trim();
                if (!trimmed.isEmpty()) {
                    merged.add(trimmed);
                }
            }
        }
        int before = merged.size();
        for (String id : hadithIds) {
            if (id == null) {
                continue;
            }
            String trimmed = id.trim();
            if (!trimmed.isEmpty()) {
                merged.add(trimmed);
            }
        }
        if (merged.isEmpty()) {
            throw new IllegalArgumentException("At least one valid hadith id is required.");
        }
        ensureCollectionCapacity(merged.size());
        if (merged.size() != before) {
            collection.setHadithIds(new ArrayList<>(merged));
            collection.setUpdatedAt(System.currentTimeMillis());
            saveCollection(collection);
        }
        return collection;
    }

    public UserCollection removeHadith(String ownerEmail, String collectionId, String hadithId) throws Exception {
        if (hadithId == null || hadithId.trim().isEmpty()) {
            throw new IllegalArgumentException("Hadith id is required.");
        }
        UserCollection collection = findCollectionById(collectionId);
        if (collection == null || !ownerEmail.equals(collection.getOwnerEmail())) {
            return null;
        }
        List<String> hadithIds = collection.getHadithIds();
        if (hadithIds == null || hadithIds.isEmpty()) {
            return collection;
        }
        String trimmedId = hadithId.trim();
        if (hadithIds.removeIf(trimmedId::equals)) {
            collection.setUpdatedAt(System.currentTimeMillis());
            saveCollection(collection);
        }
        return collection;
    }

    public boolean deleteCollection(String ownerEmail, String collectionId) throws Exception {
        UserCollection collection = findCollectionById(collectionId);
        if (collection == null || !ownerEmail.equals(collection.getOwnerEmail())) {
            return false;
        }
        try (ESClientProvider provider = new ESClientProvider()) {
            DeleteResponse response = provider.client().delete(d -> d
                    .index(COLLECTIONS_INDEX)
                    .id(collectionId)
                    .refresh(Refresh.True));
            return response.result() != null;
        }
    }

    public UserCollection updateCollectionName(String ownerEmail, String collectionId, String newName) throws Exception {
        UserCollection collection = findCollectionById(collectionId);
        if (collection == null || !ownerEmail.equals(collection.getOwnerEmail())) {
            return null;
        }
        String cleanName = sanitizeName(newName);
        collection.setName(cleanName);
        collection.setUpdatedAt(System.currentTimeMillis());
        saveCollection(collection);
        return collection;
    }

    public HadithObjectCollection collectionHadith(String ownerEmail, String collectionId, int page, int perPage,
            List<String> topicTags) throws Exception {
        UserCollection collection = findCollectionById(collectionId);
        if (collection == null || !ownerEmail.equals(collection.getOwnerEmail())) {
            return new HadithObjectCollection(new ArrayList<>(), 0);
        }

        List<String> hadithIds = collection.getHadithIds() == null ? new ArrayList<>() : collection.getHadithIds();
        if (hadithIds.isEmpty() || perPage <= 0) {
            return new HadithObjectCollection(new ArrayList<>(), hadithIds.size());
        }

        List<String> normalizedTags = sanitizeTags(topicTags);
        Map<String, HadithObject> byId = new LinkedHashMap<>();

        try (ESClientProvider provider = new ESClientProvider()) {
            SearchResponse<Map> response = provider.client().search(s -> s
                    .index(ESClientProvider.INDEX)
                    .size(hadithIds.size())
                    .source(HadithSourceFilter.searchSource())
                    .query(q -> q.ids(ids -> ids.values(hadithIds))), Map.class);
            for (Hit<Map> hit : response.hits().hits()) {
                if (hit.source() == null) {
                    continue;
                }
                Map<String, Object> map = new HashMap<>(hit.source());
                map.put("_id", hit.id());
                byId.put(hit.id(), mapper.convertValue(map, HadithObject.class));
            }
        }

        List<HadithObject> ordered = new ArrayList<>();
        for (String id : hadithIds) {
            HadithObject hadith = byId.get(id);
            if (hadith != null && matchesAllTopicTags(hadith, normalizedTags)) {
                ordered.add(hadith);
            }
        }
        if (ordered.isEmpty()) {
            return new HadithObjectCollection(new ArrayList<>(), 0);
        }
        int from = page * perPage;
        if (from >= ordered.size()) {
            return new HadithObjectCollection(new ArrayList<>(), ordered.size());
        }
        int to = Math.min(from + perPage, ordered.size());
        return new HadithObjectCollection(new ArrayList<>(ordered.subList(from, to)), ordered.size());
    }

    private UserCollection findCollectionById(String collectionId) throws Exception {
        if (collectionId == null || collectionId.trim().isEmpty()) {
            return null;
        }
        try (ESClientProvider provider = new ESClientProvider()) {
            GetResponse<Map> response = provider.client().get(g -> g
                    .index(COLLECTIONS_INDEX)
                    .id(collectionId), Map.class);
            if (!response.found() || response.source() == null) {
                return null;
            }
            Map<String, Object> map = new HashMap<>(response.source());
            map.put("id", collectionId);
            return mapper.convertValue(map, UserCollection.class);
        } catch (Exception ex) {
            if (isIndexMissing(ex)) {
                return null;
            }
            throw ex;
        }
    }

    private UserCollection findCollectionByName(String ownerEmail, String name) throws Exception {
        try (ESClientProvider provider = new ESClientProvider()) {
            SearchResponse<Map> response = provider.client().search(s -> s
                    .index(COLLECTIONS_INDEX)
                    .size(1)
                    .query(q -> q.bool(b -> b
                            .must(m -> m.term(t -> t.field("owner_email.keyword").value(ownerEmail)))
                            .must(m -> m.term(t -> t.field("name.keyword").value(name))))), Map.class);
            List<Hit<Map>> hits = response.hits().hits();
            if (hits == null || hits.isEmpty()) {
                return null;
            }
            Hit<Map> hit = hits.get(0);
            if (hit.source() == null) {
                return null;
            }
            Map<String, Object> map = new HashMap<>(hit.source());
            map.put("id", hit.id());
            return mapper.convertValue(map, UserCollection.class);
        } catch (Exception ex) {
            if (isIndexMissing(ex)) {
                return null;
            }
            throw ex;
        }
    }

    private void saveCollection(UserCollection collection) throws Exception {
        try (ESClientProvider provider = new ESClientProvider()) {
            provider.client().index(i -> i
                    .index(COLLECTIONS_INDEX)
                    .id(collection.getId())
                    .document(collection)
                    .refresh(Refresh.True));
        }
    }

    private String sanitizeName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "Saved Hadith";
        }
        String value = name.trim();
        if (value.length() > 80) {
            return value.substring(0, 80);
        }
        return value;
    }

    private List<String> sanitizeTags(List<String> topicTags) {
        List<String> cleaned = new ArrayList<>();
        if (topicTags == null) {
            return cleaned;
        }
        for (String tag : topicTags) {
            if (tag == null) {
                continue;
            }
            String value = tag.trim();
            if (!value.isEmpty() && !cleaned.contains(value)) {
                cleaned.add(value);
            }
        }
        return cleaned;
    }

    private boolean matchesAllTopicTags(HadithObject hadith, List<String> topicTags) {
        if (topicTags == null || topicTags.isEmpty()) {
            return true;
        }
        List<String> hadithTags = hadith.getTopicTags() == null ? new ArrayList<>() : hadith.getTopicTags();
        for (String tag : topicTags) {
            if (!hadithTags.contains(tag)) {
                return false;
            }
        }
        return true;
    }

    private void ensureCollectionCapacity(int sizeAfterSave) {
        if (sizeAfterSave > MAX_HADITHS_PER_COLLECTION) {
            throw new IllegalArgumentException("Collections can contain at most "
                    + MAX_HADITHS_PER_COLLECTION + " hadith.");
        }
    }

    private boolean isIndexMissing(Exception ex) {
        String message = ex.getMessage();
        if (message == null) {
            return false;
        }
        return message.contains("index_not_found_exception") || message.contains("no such index");
    }

    private static String firstNonEmpty(String first, String second, String defaultValue) {
        if (first != null && !first.trim().isEmpty()) {
            return first.trim();
        }
        if (second != null && !second.trim().isEmpty()) {
            return second.trim();
        }
        return defaultValue;
    }
}
