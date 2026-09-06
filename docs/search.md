# Search and Retrieval

How search, similar hadith, and Quranic insights work.

## Primary Search (HadithQueryService)

The main search endpoint (`/v1/narrations`) supports two modes:

### Flexible Mode (default)

Broad, forgiving search combining multiple signals:

- **Multi-field matching** across `arabic`, `english`, `semantic_text`, `book`, `chapter`
- **Exact match boosting** (6x) for terms that match exactly
- **Synonym expansion** from `static/synonyms.txt`
- **Fuzzy matching** for typo tolerance

### Precise Mode

Strict matching requiring more terms to match:

- Higher `minimum_should_match` threshold
- Less fuzziness
- Better for targeted queries where precision matters

### Result Processing

- Pagination with configurable `per_page`
- Faceted counts by book, chapter, topic
- Highlighting of matched terms
- Topic tag enrichment from taxonomy

## Reading Mode

The search interface doubles as the reader. It switches into reading mode when the query
scopes the corpus but asks nothing of it — a `book:`/`volume:`/`section:`/`chapter:` query
with no keyword terms — or when the URL carries `mode=read`. `isReadingMode()` in
`rewayaat.js` decides; reading mode pages larger and sorts by the book's own numbering
rather than by score.

Nothing routes to it any more. The browse panel used to submit into reading mode; it now
asks `/v1/browse/page` which page a selection corresponds to and goes there, because the
book, volume and chapter pages carry the same cards, the same tag facet and the same
actions, and are real URLs that can be shared and indexed. Reading mode and its URLs
still work, and the chapter pages link to it for what the app adds on top — PDF export
and the cross-corpus panels. The link is built as:

```
/?q=book:"Al-Kāfi" volume:"2" section:"20" chapter:"Degrees of Belief"
  &page=1
  &sort_fields=volume:asc,part:asc,section:asc,chapter:asc,number:asc
  &mode=read&match_mode=flexible&entry=browse
```

`BookCatalog.readingModeUrl` builds it server-side, and a `Chapter` hands out its own via
`readingUrl()`. The two implementations have to agree and nothing fails loudly if they
drift — see [seo.md](seo.md#the-one-fragile-coupling).

The division of labour has since shifted. The chapter pages render the full card —
Arabic, English, metadata, tags, Related and Tafsir — so reading mode is no longer where
a narration is read; it is one more view over the same corpus, kept because its URLs are
in the wild and because export and tag filtering across a whole result set live there.

## Similar Hadith (SimilarHadithService)

### Architecture: Pre-Computed LLM Pairs

Similar hadith uses **pre-computed LLM-judged pairs** stored in the `llm_similar` nested field in each hadith document. No real-time LLM calls during search.

**How it works:**

1. Source hadith's `llm_similar` field is loaded from ES
2. Referenced hadith IDs are bulk-fetched
3. Each match includes `matchType` (wording/conceptual/thematic) and `matchReason`

**Match types:**
- **wording** (~26%) — Near-identical Arabic text, variant narrations
- **conceptual** (~73%) — Same teaching with different words
- **thematic** (~1%) — Same event from different angles

**Caching**: Results cached 6 hours per hadith ID (Caffeine cache).

### LLM Similar Pipeline

The offline pipeline that generates these pairs:

1. **Precompute candidates** — For each of 32K hadith, find ~30 candidates via BM25 Arabic/English, topic overlap, and same-chapter queries
2. **Auto-accept obvious matches** — Pairs with >80% token overlap bypass agent judgment
3. **Agent judgment** — Claude Sonnet sub-agents judge batches of 5 hadith, classifying each candidate pair as similar or rejected
4. **Merge results** — File-locked merge into central cache (`tmp/pairs_cache.json`)
5. **Load to ES** — `scripts/similar/load_llm_similar_to_es.py` bulk-updates `llm_similar` field

**Runbook and agent prompt**: [pipelines/llm-similar-hadith.md](pipelines/llm-similar-hadith.md)
**Candidate generation and ES loading**: [data-pipeline.md](data-pipeline.md#llm-similar-hadith-pipeline)

### Legacy: Hybrid Retrieval (SimilarHadithRanking)

`SimilarHadithRanking.java` still exists and is used by:
- `HadithSemanticText` — Semantic text generation
- `HadithSignificantTerms` — TF-IDF term extraction
- `TopicTaxonomyAuditTool` — Tag quality audit

It uses semantic kNN + BM25 + RRF fusion but is **not** used by the current similar hadith API endpoint.

## Quranic Insights (QuranicInsightsService)

Shows Quranic verses connected to a hadith, with tafsir commentary.

### Data Source

Pre-computed connections from `rewayaat_quranic_light_filtered` index. These are LLM-filtered — only strong hadith-verse connections are kept.

### How It Works

1. Fetch hadith's connections from `rewayaat_quranic_light_filtered`
2. Enrich with verse data from `rewayaat_quran`
3. Attach tafsir snippets from `rewayaat_tafsir`
4. Return verse text, translation, and commentary

### Endpoint

```
GET /v1/narrations/quranic_insights?id={hadithId}
```

Returns connected verses with tafsir excerpts. Supports `count_only=true` for lightweight badge counts.

Each snippet can carry two enriched forms of the commentary: `relevant_excerpt`, a short
exact substring for the preview, and `commentary_text_highlighted`, the full passage with
`<em>` around the relevant span. How both are produced is in
[pipelines/quranic-insights.md](pipelines/quranic-insights.md).

## Embedding-Based kNN Search

Used internally by the legacy similar hadith system and available for semantic search:

- **Model**: `intfloat/multilingual-e5-large` (fine-tuned with LoRA)
- **Dimensions**: 1024
- **Distance**: Cosine similarity
- **Index**: HNSW in ES `semantic_vector` field
- **Pooling**: Mean pooling (training and inference)

No ES inference model is required for *similar hadith*, where both sides of the comparison
are documents that already carry a vector. Free-text search is the different case: an
arbitrary query has no stored vector, so making search semantic would require embedding it
at request time — an ES inference endpoint or an external embedder. See
[mcp-connector.md](mcp-connector.md) for why that is not currently worth doing.

## Configuration

Key environment variables (in `SimilarHadithService` and `application-dev.properties`):

```properties
# Core
spring.elasticsearch.uris=http://localhost:9200
rewayaat.elasticsearch.index=rewayaat_updated
server.port=8002
management.server.port=8003

# Quranic insights
quranic.insights.enabled=true
```
