package com.rewayaat.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.rewayaat.core.data.HadithObject;
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

    private final Cache<String, Map<String, Object>> nameCache = Caffeine.newBuilder()
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
     * The names and page slugs of several narrators at once, without their entries.
     *
     * <p>A chain of twelve steps needs twelve names, and a narrator document is mostly
     * entries - the books' own words, page after page - none of which a chain wants. One
     * multi-get that includes only the name fields costs the same round trip as fetching a
     * single man and a fraction of the bytes. An id the index does not hold is left out of
     * the answer rather than mapped to null, so a caller can tell "unknown" from "unnamed".
     */
    public Map<String, Map<String, Object>> names(java.util.Collection<String> personIds) {
        Map<String, Map<String, Object>> found = new LinkedHashMap<>();
        if (!enabled || personIds == null || personIds.isEmpty()) {
            return found;
        }
        List<String> missing = new ArrayList<>();
        for (String id : new java.util.LinkedHashSet<>(personIds)) {
            if (id == null || id.isBlank()) {
                continue;
            }
            Map<String, Object> known = nameCache.getIfPresent(id);
            if (known != null) {
                found.put(id, known);
            } else {
                missing.add(id);
            }
        }
        if (missing.isEmpty()) {
            return found;
        }
        try (ESClientProvider provider = new ESClientProvider()) {
            co.elastic.clients.elasticsearch.core.MgetResponse<Map> response = provider.client()
                    .mget(m -> m.index(indexName).ids(missing).sourceIncludes(
                            List.of("person_id", "display_ar", "display_en", "slug", "moved_to")),
                            Map.class);
            for (co.elastic.clients.elasticsearch.core.mget.MultiGetResponseItem<Map> item
                    : response.docs()) {
                if (item.isFailure() || item.result() == null || !item.result().found()
                        || item.result().source() == null) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> source = (Map<String, Object>) item.result().source();
                nameCache.put(item.result().id(), source);
                found.put(item.result().id(), source);
            }
        } catch (Exception e) {
            LOGGER.warn("Could not read narrator names {}: {}", missing, e.getMessage());
        }
        return found;
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
                    // A document carrying «moved_to» is somebody else's — one of the fourteen
                    // under a narrator's identifier. It stays in the index so its links keep
                    // working and the page can send the reader to the roster, but a search
                    // must not answer «zurara» or «ali» with three cards for the same man.
                    .query(q -> q.bool(b -> b
                            .mustNot(n -> n.exists(e -> e.field("moved_to")))
                            .must(top -> top.functionScore(fs -> fs
                            .query(inner -> inner.multiMatch(m -> m
                                    .query(text)
                                    // «AUTO» allows two edits past five letters, and at two
                                    // edits «zurara» reaches «hurasa» and «zuraqan»: a search
                                    // for Zurara returned Abu Hurasa and Qurazah b. Ka'b.
                                    // One edit up to eight letters, and the first letter
                                    // fixed, keeps the spellings that vary and drops the men
                                    // who merely rhyme.
                                    .fuzziness("AUTO:4,8")
                                    .prefixLength(1)
                                    // Every word, not any word. «ibn abi umayr» matched
                                    // 10,000 men because «ibn» is in nearly every name.
                                    .operator(Operator.And)
                                    .fields("display_ar^4", "display_en^4", "names_ar^2",
                                            "names_en^2", "normalized_ar", "normalized_en",
                                            "kunyahs_ar", "titles")))
                            .functions(f -> f.fieldValueFactor(v -> v
                                    .field("entry_count")
                                    .modifier(FieldValueFactorModifier.Log1p)
                                    .missing(1.0)))
                            .boostMode(FunctionBoostMode.Multiply)))))
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

    /**
     * The narrations whose chain names this man.
     *
     * <p>This is a search by name, not a resolved chain: the chains are not yet linked to
     * people, so a narration is here because its text carries one of the forms the Rijal books
     * head him under. For a man with a distinctive name that is exactly right; for «Muhammad b.
     * Isa» it will gather more than one man, and the page says so rather than implying the
     * identity work is done. Only forms of three words or more are searched — a bare «Zurara»
     * would drag in every Zurara there is.
     *
     * @return the hits, the total, and the topic tags they carry, for the filter bar
     */
    public Map<String, Object> narrationsNaming(List<String> nameForms, String tag, String book,
                                                int page, int pageSize) {
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("total", 0L);
        empty.put("hits", new ArrayList<>());
        empty.put("tags", new ArrayList<>());
        List<String> forms = distinctive(nameForms);
        if (forms.isEmpty()) {
            return empty;
        }
        int size = Math.min(Math.max(pageSize, 1), 50);
        int from = Math.max(page - 1, 0) * size;
        String wanted = tag == null ? "" : tag.trim();
        String wantedBook = book == null ? "" : book.trim();
        try (ESClientProvider provider = new ESClientProvider()) {
            ElasticsearchClient client = provider.client();
            SearchResponse<Map> response = client.search(s -> s
                    .index(ESClientProvider.INDEX)
                    .from(from)
                    .size(size)
                    // «*_ar» as well, or the card asks for the Arabic of a book title and
                    // finds nothing: an Arabic page was labelling «الكتاب» and then printing
                    // «Man Lā Yaḥḍuruh al-Faqīh», «الباب» and «The Prayer of the Two Eids».
                    // The translations are in the index; this query simply did not fetch them.
                    .source(src -> src.filter(f -> f.includes(
                            "book", "number", "english", "arabic", "notes", "volume", "part",
                            "section", "chapter", "source", "edition", "publisher",
                            "topic_tags", "llm_similar", "*_ar", "chain_links",
                            "chain_links_en", "chain_people")))
                    .query(q -> q.bool(b -> {
                        for (String form : forms) {
                            b.should(sh -> sh.matchPhrase(m -> m.field("arabic").query(form)));
                        }
                        b.minimumShouldMatch("1");
                        if (!wanted.isEmpty()) {
                            b.filter(f -> f.term(t -> t.field("topic_tags").value(wanted)));
                        }
                        if (!wantedBook.isEmpty()) {
                            b.filter(f -> f.term(t -> t.field("book").value(wantedBook)));
                        }
                        return b;
                    }))
                    .aggregations("tags", a -> a.terms(t -> t.field("topic_tags").size(18)))
                    .aggregations("books", a -> a.terms(t -> t.field("book").size(8))),
                    Map.class);

            List<Map<String, Object>> hits = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                if (hit.source() != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> source = (Map<String, Object>) hit.source();
                    hits.add(Map.of("id", hit.id(), "source", source));
                }
            }
            List<Map<String, Object>> tags = new ArrayList<>();
            if (response.aggregations().get("tags") != null) {
                for (StringTermsBucket bucket : response.aggregations().get("tags")
                        .sterms().buckets().array()) {
                    tags.add(Map.of("label", bucket.key().stringValue(),
                            "count", bucket.docCount()));
                }
            }
            long found = response.hits().total() == null ? hits.size()
                    : response.hits().total().value();
            List<Map<String, Object>> books = new ArrayList<>();
            if (response.aggregations().get("books") != null) {
                for (StringTermsBucket bucket : response.aggregations().get("books")
                        .sterms().buckets().array()) {
                    // The share was hard-wired to nought here while the overview worked it
                    // out, so the same man's ledger read «61% in Al-Kāfi» on one tab and
                    // «0% in Al-Kāfi» on the next.
                    books.add(Map.of("book", bucket.key().stringValue(),
                            "count", bucket.docCount(),
                            "share", found == 0 ? 0
                                    : Math.round(bucket.docCount() * 100.0 / found)));
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("total", found);
            out.put("hits", hits);
            out.put("tags", tags);
            out.put("books", books);
            out.put("searched_for", forms);
            return out;
        } catch (Exception e) {
            LOGGER.warn("Could not read narrations naming {}: {}", nameForms, e.getMessage());
            return empty;
        }
    }

    /**
     * How many narrations name him, and which books they sit in — without fetching any.
     *
     * <p>Where a man's narrations concentrate is a rijali fact in itself: 1,396 of Sahl b.
     * Ziyad's 1,504 are in al-Kafi, which is why a judgment on him moves so much of that book.
     */
    public Map<String, Object> narrationSummary(List<String> nameForms) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", 0L);
        summary.put("books", new ArrayList<>());
        List<String> forms = distinctive(nameForms);
        if (forms.isEmpty()) {
            return summary;
        }
        try (ESClientProvider provider = new ESClientProvider()) {
            SearchResponse<Map> response = provider.client().search(s -> s
                    .index(ESClientProvider.INDEX)
                    .size(0)
                    .query(q -> q.bool(b -> {
                        for (String form : forms) {
                            b.should(sh -> sh.matchPhrase(m -> m.field("arabic").query(form)));
                        }
                        return b.minimumShouldMatch("1");
                    }))
                    .aggregations("books", a -> a.terms(t -> t.field("book").size(4))), Map.class);

            long total = response.hits().total() == null ? 0L : response.hits().total().value();
            List<Map<String, Object>> books = new ArrayList<>();
            if (response.aggregations().get("books") != null) {
                for (StringTermsBucket bucket : response.aggregations().get("books")
                        .sterms().buckets().array()) {
                    books.add(Map.of("book", bucket.key().stringValue(),
                            "count", bucket.docCount(),
                            "share", total == 0 ? 0 : Math.round(bucket.docCount() * 100.0 / total)));
                }
            }
            summary.put("total", total);
            summary.put("books", books);
            return summary;
        } catch (Exception e) {
            LOGGER.warn("Could not summarise narrations naming {}: {}", nameForms, e.getMessage());
            return summary;
        }
    }

    /**
     * The name forms worth searching a chain for.
     *
     * <p>Shortest first, and «X b. Y» at the least. A chain names a man the short way —
     * «سهل بن زياد» stands in 1,500 of them — while the Rijal books head him with his kunyah,
     * his nisbah and his city, and searching for those full headings found eight.
     *
     * <p>Which forms are safe to search is settled when the index is built, by how many
     * narrators answer to each ({@code search_ar}, index_people.searchable), not here by
     * counting words. A three-word rule looked safe and was not: a chain says «عن زرارة»,
     * so the rule found Zurara 84 narrations out of well over a thousand, while admitting
     * any three-word name that had strayed onto his record. One man holding the name is
     * what makes it his; length never was.
     */
    private List<String> distinctive(List<String> nameForms) {
        List<String> forms = new ArrayList<>();
        for (String form : nameForms == null ? List.<String>of() : nameForms) {
            if (form != null && !form.isBlank()) {
                forms.add(form.trim());
            }
        }
        forms.sort((a, b) -> a.length() - b.length());
        return forms.size() > 6 ? forms.subList(0, 6) : forms;
    }
}
