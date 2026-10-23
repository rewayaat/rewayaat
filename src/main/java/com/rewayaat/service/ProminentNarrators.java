package com.rewayaat.service;

import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rewayaat.config.ESClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The men these books lean on most.
 *
 * <p>«Prominent» could mean several things, and this picks the one the corpus can prove:
 * how many of its narrations a man's name actually stands in, counted from the chain links
 * the resolver stored on every narration. It is a fact with a number beside it, not a
 * judgement of ours — which matters, because the page it feeds is the first thing a reader
 * sees and the site's whole discipline is to report what the books say rather than rank men
 * itself. The classical answer to the same question, al-Kashshī's eighteen Aṣḥāb al-Ijmāʿ,
 * is a judgement to be quoted and attributed, and belongs in a section of its own.
 *
 * <p>The Imams are left out. al-Ṣādiq stands in 8,444 chains and would head any such list,
 * but he is the source the chains run to, not a narrator among narrators.
 *
 * <p>One terms aggregation, cached: the home page is the most-requested page on the site
 * and the answer changes only when the corpus is rebuilt.
 */
@Component
public class ProminentNarrators {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProminentNarrators.class);
    private static final Duration CACHE_TTL = Duration.ofHours(6);

    /** Asked for twice as many as are shown, because Imams and strays are dropped after. */
    private static final int ASK_FOR = 40;

    private final NarratorService narrators;

    private final Cache<Integer, List<Map<String, Object>>> cache = Caffeine.newBuilder()
            .maximumSize(4)
            .expireAfterWrite(CACHE_TTL)
            .build();

    @Value("${narrator.enabled:true}")
    private boolean enabled;

    public ProminentNarrators(NarratorService narrators) {
        this.narrators = narrators;
    }

    /**
     * The most-carrying narrators, heaviest first, each with what one book calls him.
     *
     * <p>Never throws and never returns null: the home page renders with this section
     * missing rather than not at all.
     */
    public List<Map<String, Object>> top(int howMany, PageLocale locale) {
        if (!enabled || howMany <= 0) {
            return List.of();
        }
        List<Map<String, Object>> ranked = cache.get(howMany, size -> rank(size));
        if (ranked == null) {
            return List.of();
        }
        List<Map<String, Object>> shown = new ArrayList<>(ranked.size());
        for (Map<String, Object> man : ranked) {
            shown.add(forLocale(man, locale));
        }
        return shown;
    }

    private final Cache<String, Long> chains = Caffeine.newBuilder()
            .maximumSize(5000)
            .expireAfterWrite(CACHE_TTL)
            .build();

    /**
     * How many chains one man stands in — the same number the ranking above counts, for
     * the seal's ring and the card's footer. It is counted from the resolved chain index,
     * so it includes the chains that reach him as «أبيه» or «عنه» without writing his
     * name; that is why it runs well above the narration search's total, and why the two
     * carry different words in the interface. Zero on any failure, so the card simply
     * draws no ring.
     */
    public long chainsOf(String personId) {
        if (!enabled || personId == null || personId.isBlank()) {
            return 0;
        }
        Long cached = chains.getIfPresent(personId);
        if (cached != null) {
            return cached;
        }
        try (ESClientProvider provider = new ESClientProvider()) {
            long found = provider.client().count(c -> c
                    .index(ESClientProvider.INDEX)
                    .query(q -> q.term(t -> t.field("chain_links.p.keyword").value(personId))))
                    .count();
            chains.put(personId, found);
            return found;
        } catch (Exception e) {
            LOGGER.warn("Could not count the chains standing on {}: {}", personId, e.getMessage());
            return 0;
        }
    }

    private List<Map<String, Object>> rank(int howMany) {
        List<Map<String, Object>> found = new ArrayList<>();
        try (ESClientProvider provider = new ESClientProvider()) {
            SearchResponse<Void> response = provider.client().search(s -> s
                    .index(ESClientProvider.INDEX)
                    .size(0)
                    .aggregations("men", a -> a
                            .terms(t -> t.field("chain_links.p.keyword").size(ASK_FOR))),
                    Void.class);
            List<StringTermsBucket> buckets =
                    response.aggregations().get("men").sterms().buckets().array();
            for (StringTermsBucket bucket : buckets) {
                if (found.size() >= howMany) {
                    break;
                }
                Map<String, Object> man = narrators.getNarrator(bucket.key().stringValue());
                if (man == null || "imam".equals(man.get("kind")) || man.get("moved_to") != null) {
                    continue;
                }
                found.add(card(man, bucket.docCount()));
            }
        } catch (Exception e) {
            LOGGER.warn("Could not rank the narrators by weight: {}", e.getMessage());
            return List.of();
        }
        return found;
    }

    /**
     * As much of a name as a card can hold.
     *
     * <p>al-Najāshī heads Aḥmad b. Muḥammad b. ʿĪsā with eleven generations of lineage,
     * and the full heading is the right thing on his own page and far too much on a tile
     * among eleven others. Two «ibn» steps is how the books themselves refer to these men
     * in a chain — «أحمد بن محمد بن عيسى» — so that is where this cuts. The whole name
     * stays on the link's title, for anyone who hovers.
     */
    private static String shortened(Object value, Set<String> connectors) {
        if (value == null) {
            return null;
        }
        String[] words = String.valueOf(value).trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        int steps = 0;
        int kept = 0;
        for (String word : words) {
            if (connectors.contains(word)) {
                steps++;
                if (steps > 2) {
                    break;
                }
            }
            // Some headings carry two names joined by «و» and never reach a third «ibn»,
            // so a word count stops those too.
            if (kept >= 6) {
                break;
            }
            out.append(out.length() == 0 ? "" : " ").append(word);
            kept++;
        }
        // A cut that lands on «ibn» leaves the name hanging — «محمد بن أبي عمير زياد بن» —
        // so the connector goes with it.
        String shortened = out.toString().trim();
        for (String connector : connectors) {
            if (shortened.endsWith(" " + connector)) {
                shortened = shortened.substring(0, shortened.length() - connector.length() - 1);
            }
        }
        return shortened.isEmpty() ? String.valueOf(value) : shortened;
    }

    private static final Set<String> AR_CONNECTORS = Set.of("بن", "ابن");
    private static final Set<String> EN_CONNECTORS = Set.of("ibn", "bin", "b.");

    /** What a card needs, and nothing of the 91 KB document behind it. */
    private static Map<String, Object> card(Map<String, Object> man, long chains) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("person_id", man.get("person_id"));
        row.put("slug", man.get("slug"));
        row.put("display_ar", man.get("display_ar"));
        row.put("display_en", man.get("display_en"));
        row.put("short_ar", shortened(man.get("display_ar"), AR_CONNECTORS));
        row.put("short_en", shortened(man.get("display_en"), EN_CONNECTORS));
        row.put("chains", chains);
        // The seal's colour: the lowest Imam ordinal he transmitted from (see NarratorSeal).
        row.put("ordinals", man.get("imam_ordinals") instanceof List<?> ords ? ords : List.of());
        row.put("book_count", man.get("books") instanceof List<?> books ? books.size() : 0);
        // Every row the books give, not only the first: Sahl b. Ziyad has three words from
        // four books, and a card that kept one would be choosing a side for the reader.
        row.put("stands", man.get("said_by") instanceof List<?> said ? said : List.of());
        if (man.get("said_by") instanceof List<?> said && !said.isEmpty()
                && said.get(0) instanceof Map<?, ?> first) {
            // Sahl b. Ziyad stands in 1,524 chains and the books do not agree about him:
            // al-Tusi calls him thiqa and al-Najashi weak. Showing the first word alone
            // would make this page take one side, so a card whose rows do not all lean the
            // same way says that instead.
            long leanings = said.stream()
                    .filter(entry -> entry instanceof Map)
                    .map(entry -> String.valueOf(((Map<?, ?>) entry).get("leaning")))
                    .distinct().count();
            row.put("divided", leanings > 1);
            row.put("said", first.get("said"));
            row.put("said_ar", first.get("said_ar"));
            row.put("leaning", first.get("leaning"));
            if (first.get("books") instanceof List<?> books && !books.isEmpty()) {
                row.put("book", books.get(0));
            }
            if (first.get("books_ar") instanceof List<?> arabic && !arabic.isEmpty()) {
                row.put("book_ar", arabic.get(0));
            }
        }
        return row;
    }

    /**
     * The same card in one language.
     *
     * <p>The Arabic site shows the Arabic name, the Arabic word and the Arabic title of the
     * book that says it, and no transliteration at all; the English site shows the Arabic
     * name with its Latin reading beneath. Choosing here rather than in the template keeps
     * the rule in one place, where a missing Arabic form can fall back without the page
     * printing Latin on an Arabic screen by accident.
     */
    private static List<Map<String, Object>> wordsOf(Object stands, boolean arabic) {
        List<Map<String, Object>> words = new ArrayList<>();
        if (!(stands instanceof List<?> rows) || rows.isEmpty()) {
            return words;
        }
        Map<?, ?> pro = null;
        Map<?, ?> con = null;
        for (Object o : rows) {
            if (o instanceof Map<?, ?> m) {
                if (pro == null && "for".equals(m.get("leaning"))) {
                    pro = m;
                } else if (con == null && "against".equals(m.get("leaning"))) {
                    con = m;
                }
            }
        }
        // Where the books take both sides the card shows one word from each side, strongest
        // first in the record's own order; otherwise just the first row.
        List<Map<?, ?>> picked = new ArrayList<>();
        if (pro != null && con != null) {
            picked.add(pro);
            picked.add(con);
        } else if (rows.get(0) instanceof Map<?, ?> first) {
            picked.add(first);
        }
        for (Map<?, ?> m : picked) {
            Map<String, Object> w = new java.util.LinkedHashMap<>();
            w.put("word", arabic && m.get("said_ar") != null ? m.get("said_ar") : m.get("said"));
            w.put("arabic", m.get("said_ar"));
            // «thiqa — trustworthy» → «trustworthy»: the Arabic term already leads.
            String said = String.valueOf(m.get("said"));
            w.put("gloss", arabic || m.get("said") == null ? null
                    : said.substring(said.lastIndexOf('—') + 1).trim());
            w.put("leaning", m.get("leaning"));
            Object books = arabic && m.get("books_ar") != null ? m.get("books_ar") : m.get("books");
            if (books instanceof List<?> b && !b.isEmpty()) {
                w.put("book", b.get(0));
                w.put("more", b.size() - 1);
            }
            words.add(w);
        }
        return words;
    }

    private static Map<String, Object> forLocale(Map<String, Object> man, PageLocale locale) {
        boolean arabic = locale != null && locale.isArabic();
        Map<String, Object> row = new java.util.LinkedHashMap<>(man);
        row.put("name", man.get("short_ar"));
        row.put("full", arabic ? man.get("display_ar") : man.get("display_en"));
        row.put("latin", arabic ? null : man.get("short_en"));
        row.put("word", arabic ? man.get("said_ar") : man.get("said"));
        row.put("words", wordsOf(man.get("stands"), arabic));
        Object book = arabic ? man.getOrDefault("book_ar", man.get("book")) : man.get("book");
        row.put("whose", book);
        return row;
    }
}
