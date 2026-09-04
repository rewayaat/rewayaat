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

No ES inference model required at query time — vectors are pre-computed and stored directly in ES.

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
