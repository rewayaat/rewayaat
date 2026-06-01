# Rewayaat Operations Guide

Complete guide for managing hadith, Quranic verses, embeddings, tagging, and fine-tuning.
Anyone should be able to rebuild the system from scratch using this document.

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Prerequisites](#prerequisites)
3. [Hadith Pipeline](#hadith-pipeline)
4. [Quranic Verse Pipeline](#quranic-verse-pipeline)
5. [Embedding System](#embedding-system)
6. [Fine-Tuning with LoRA](#fine-tuning-with-lora)
7. [Similar Hadith Search](#similar-hadith-search)
8. [Operational Procedures](#operational-procedures)
9. [Configuration Reference](#configuration-reference)
10. [Scripts Marked for Deletion Review](#scripts-marked-for-deletion-review)

---

## Architecture Overview

```
                    ┌──────────────────────────────────────────┐
                    │           Elasticsearch 9.x              │
                    │                                          │
                    │  rewayaat_updated  (32K hadith docs)     │
                    │  rewayaat_quran    (6.2K verse docs)     │
                    │  rewayaat_tafsir   (varies)              │
                    │  rewayaat_quranic_light (hadith→verses)  │
                    └──────────────────────────────────────────┘
                                      ▲
                                      │
                    ┌─────────────────┼─────────────────────┐
                    │                 │                     │
              Ingestion          Search &              Export for
              & Tagging       Similar Hadith          Embedding/FT
                    │                 │                     │
         ┌──────┴──────┐   ┌────────┴────────┐   ┌───────┴────────┐
         │ Python      │   │ Spring Boot App  │   │ Python scripts │
         │ scripts     │   │ (port 8002)      │   │ + Colab GPU    │
         └─────────────┘   └─────────────────┘   └────────────────┘
```

**Key indices:**
- `rewayaat_updated` - Primary hadith index (configured in `application-dev.properties`)
- `rewayaat_quran` - Quran verses with Arabic text and tags
- `rewayaat_tafsir` - Extracted tafsir commentary per verse
- `rewayaat_quranic_light` - Pre-computed hadith→verse connections with scores

---

## Prerequisites

### Infrastructure
- **Elasticsearch 9.x** running on port 9200
- **Spring Boot 3.3.2** on port 8002 (app), 8003 (management)
- **Python 3.10+** with: `requests`, `sentence-transformers`, `peft`, `safetensors`
- **Java 17+** (Maven build)
- **Colab with T4 GPU** (for embedding fine-tuning)

### Thaqalayn API
- Base URL: `https://www.thaqalayn-api.net/api/v2`
- Used for hadith metadata (volume, section, part, gradings, source, related)
- No API key required

### Key config files
- `src/main/resources/application-dev.properties` - ES index name, ports
- `src/main/resources/application.yaml` - Spring config
- `src/main/resources/static/taxonomy.json` - 206 topic tags across 10 categories
- `src/main/resources/synonyms.txt` - Search synonyms

---

## Hadith Pipeline

### Step 1: Import Hadith into Elasticsearch

```bash
python3 scripts/import_hadith_batches.py
```

**What it does:** Reads JSONL batch files from the `batches/` directory and bulk-imports them into the `rewayaat_updated` index. Each hadith gets fields: `arabic`, `english`, `book`, `chapter`, `number`, `gradings`, `source`, `related`.

**Input:** `batches/*.jsonl` (one JSON object per line, one file per book)
**Output:** ES documents with `_id` format `bookId:hadithNumber` (e.g., `Al-Kafi-Volume-1-Kulayni:1048`)

### Step 2: Backfill Metadata from Thaqalayn API

```bash
python3 scripts/backfill_metadata.py
```

**What it does:** Fetches all 33 books from the Thaqalayn API, extracts `volume`, `part`, `section`, `source`, `gradings`, `related` fields that aren't in the batch data, and bulk-updates matching ES docs.

**Result:** ~32,519 docs updated with full metadata (volume, part, section visible in browse dropdowns).

### Step 3: Generate Chain-Free Arabic Text

```bash
REWAYAAT_INDEX=rewayaat_updated \
mvn -B -q -DskipTests dependency:build-classpath -Dmdep.outputFile=/tmp/rewayaat.cp

REWAYAAT_INDEX=rewayaat_updated \
java -cp "target/classes:$(cat /tmp/rewayaat.cp)" \
  com.rewayaat.tools.SemanticMatnSourceBackfillTool
```

**What it does:** For each hadith, strips narrator chains from the Arabic text to produce `semantic_matn_source` (the matn content only). Also generates `semantic_english_hint_source` (chain-free English, 120 chars).

### Step 4: Extract Significant Terms

```bash
REWAYAAT_INDEX=rewayaat_updated \
java -cp "target/classes:$(cat /tmp/rewayaat.cp)" \
  com.rewayaat.tools.SemanticSignificantTermsBackfillTool
```

**What it does:** Extracts high-signal Arabic terms from each hadith using TF-IDF against the corpus. Stored as `semantic_significant_terms_source`.

### Step 5: Apply Topic Tags

Topic tags are applied via the `TopicTagsBackfillTool` Java tool or the `import_tags_to_es.py` script:

```bash
python3 scripts/import_tags_to_es.py --tags-dir batches/ --es-url http://localhost:9200 --index rewayaat_updated
```

**What it does:** Imports pre-assigned tags from batch files. Tags are expanded with ancestors from `taxonomy.json`, but only `taggable` ancestors are included (broad categories like `prayer`, `halal` are excluded).

**Taxonomy structure:** 206 tags across 10 categories (`worship`, `ethics`, `beliefs`, `society`, `law`, `quran`, `prophets`, `history`, `spirituality`, `afterlife`). Each tag has: `slug`, `en`, `ar`, `category`, `parent` (optional), `taggable` (default true).

### Step 6: Import Tags (Alternative - Direct Tag Update)

If you have a corrected set of tags (e.g., from review batches):

```bash
python3 scripts/apply_corrected_batch_tags.py  # Apply reviewed tags
python3 scripts/overwrite_batch_tags_from_review.py  # Overwrite with reviewed tags
```

---

## Quranic Verse Pipeline

### Step 1: Build Quran Verse Index

```bash
python3 scripts/rebuild_and_retag_quran.py
```

**What it does:**
1. Downloads Quran data (Arabic text + English translation)
2. Creates `rewayaat_quran` index
3. Applies AI-powered topic tagging using the same taxonomy
4. Only primary tags (hadith use primary + secondary)

### Step 2: Tag Quran Verses (Manual/Alternative)

```bash
REWAYAAT_INDEX=rewayaat_quran \
java -cp "target/classes:$(cat /tmp/rewayaat.cp)" \
  com.rewayaat.tools.QuranVerseTaggingTool
```

### Step 3: Extract and Index Tafsir

Tafsir (Quranic commentary) is extracted from multiple sources:

```bash
# Step 3a: Extract tafsir from cached HTML files
python3 scripts/extract_and_index_tafsir.py

# Step 3b: Import split tafsir (per-verse documents)
python3 scripts/import_split_tafsir.py

# Step 3c: Alternative - import from JSON
python3 scripts/index_tafsir_json.py
```

**Tafsir sources** (extracted via `TafsirExtractionTool.java`):
- Al-Mizan (Tabatabai) - `AlMizanExtractor`
- Enlightening Commentary - `EnlighteningCommentaryExtractor`
- Pooya Yazdi - `PooyaYazdiExtractor`
- Divine Lights - `DivineLightsExtractor`
- Al-Bayan - `AlBayanExtractor`
- Hub-e-Ali - `HubEAliExtractor`
- Fatima Zahra - `FatimaZahraExtractor`
- Imam Askari - `ImamAskariExtractor`
- Khomeini Hamd - `KhomeiniHamdExtractor`
- Hoda Al-Quran - `HodaAlQuranExtractor`
- Quranic Reflections - `QuranicReflectionsExtractor`
- Al-Islam - `AlIslamHtmlExtractor`
- Single Surah - `SingleSurahExtractor`

Each extractor parses HTML from cached source pages and produces `TafsirDocument` objects with: `verse_keys`, `section_title`, `arabic_text`, `english_text`, `source_name`.

**Key classes:**
- `TafsirIndexManager` - Indexes tafsir documents into ES
- `TafsirDocument` - Data model for tafsir content
- `VerseReferenceParser` - Parses verse references like "2:255" or "البقرة:٢٥٥"
- `SurahNameResolver` - Maps Arabic surah names to numbers
- `TafsirSnippetSanitizer` - Cleans extracted text

### Step 4: Build Quranic Light Index (Hadith→Verse Matching)

```bash
python3 scripts/build_quranic_light_index.py
```

**What it does:** For each hadith, finds relevant Quran verses using 4 signals:

| Signal | Weight | Description |
|--------|--------|-------------|
| Tag overlap | Varies | Hadith topic_tags vs verse topic_tags |
| Arabic citation | High | Direct Quran quotation in hadith matn |
| Verse terms | Medium | Distinctive Quran vocabulary in hadith |
| Tafsir content | Medium | Hadith text matches tafsir commentary |

Signals are combined with weighted scoring, convergence bonuses, and threshold filtering. Output stored in `rewayaat_quranic_light` index.

**Environment variables:**
- `QURANIC_LIGHT_DRY_RUN=true` - Preview without writing
- `QURANIC_LIGHT_LIMIT=100` - Process only N hadith
- `QURANIC_LIGHT_MIN_COMBINED_SCORE` - Minimum score threshold

---

## Embedding System

### Overview

Embeddings are 1024-dim vectors from `intfloat/multilingual-e5-large` (or fine-tuned version), stored in `semantic_vector` field in ES. Used for kNN similarity search in the similar hadith feature.

### Embedding Text Format

The text fed into the embedding model follows this format (must be consistent between training and inference):

```
{arabic_matn_chain_free} || {english_hint_chain_free} || topics: {tag1}, {tag2}, ...
```

- Arabic matn: from `semantic_matn_source` (chain-free, honorifics stripped, max 3800 chars)
- English hint: from `semantic_english_hint_source` (chain-free, truncated at sentence boundary within 300 chars)
- Topics: up to 8 topic tags with hyphens replaced by spaces

**Important:** No `passage:` prefix. No `key_terms`. No narrator chains.

### Generate Embeddings Locally

```bash
python3 scripts/generate_hadith_embeddings.py \
  --model tmp/finetuned_model/finetuned_e5_large_hadith \
  --batch-size 64 \
  --force
```

**Flags:**
- `--model` - Path to model (default: `intfloat/multilingual-e5-large`)
- `--batch-size` - Encoding batch size (default: 64)
- `--force` - Regenerate all embeddings (default: skip docs that already have `semantic_vector`)
- `--eval` - Run evaluation mode only

**How it works:**
1. Scrolls through all docs in `rewayaat_updated`
2. Builds embedding text using `build_embedding_text()` (must match training format)
3. Encodes using sentence-transformers
4. Bulk-updates `semantic_vector` field in ES

**Performance:** ~1 hadith/sec on CPU with batch size 64 (~9 hours for 32K). Much faster on GPU.

**Handling LoRA models:** The script auto-detects PEFT-format weights and merges them before encoding. No manual merge step needed.

### Export for Colab (Alternative)

If generating embeddings on Colab GPU:

```bash
# Export hadith text
python3 scripts/export_hadith_for_embeddings.py

# Upload hadith_for_embeddings.json to Colab
# In Colab:
#   model = SentenceTransformer("path/to/model")
#   hadith = json.load(open("hadith_for_embeddings.json"))
#   texts = list(hadith.values())
#   embeddings = model.encode(texts, batch_size=128, normalize_embeddings=True)
#   np.savez("hadith_embeddings.npz", ids=list(hadith.keys()), embeddings=embeddings)
```

Then import back:

```bash
python3 scripts/import_embeddings_to_es.py hadith_embeddings.npz
```

---

## Fine-Tuning with LoRA

### Step 1: Build Training Dataset

```bash
python3 scripts/build_training_dataset.py --es-url http://localhost:9200 --index rewayaat_updated
```

**What it does:** Generates ~3000 training pairs from ES using 7 strategies:

**Positive pairs (label=1):**
| Strategy | Target | Description |
|----------|--------|-------------|
| `same_topic` | 1200 | Hadith sharing specific topic tags (jaccard 0.08-0.90) |
| `cross_collection` | 700 | Same narration in different books (MLT query, jaccard 0.20+) |
| `phrase_cross_collection` | 400 | Phrase matching across books (jaccard 0.10+) |
| `variant` | 800 | Variant narrations within same chapter (jaccard 0.20+) |

**Negative pairs (label=0):**
| Strategy | Target | Description |
|----------|--------|-------------|
| `hard_negative` | 1000 | Same category, different topic (jaccard <0.50) |
| `easy_negative` | 400 | Cross-category pairs (jaccard <0.30) |
| `random_negative` | 200 | Random different-book pairs (jaccard <0.25) |

**Quality controls:**
- Generic tags (broad categories) excluded from positive pairing
- Jaccard similarity bounds prevent near-duplicates and unrelated pairs
- Hard negatives verified to actually belong to the same category
- Cross-collection pairs verified to be from different books
- Variant pairs capped at 5 per chapter to prevent concentration
- Split by connected components (positive) and stratified (negative) for zero data leakage
- Output: `tmp/eval/train.json`, `tmp/eval/val.json`, `tmp/eval/test.json` (~80/10/10 split)

### Step 2: Export for Colab

```bash
python3 scripts/export_colab_data.py http://localhost:9200 rewayaat_updated
```

**Output:** `tmp/colab_training_data.json` (~5.8MB) with `text_a`, `text_b`, `label`, `pair_type` for each pair.

### Step 3: Fine-Tune in Colab

Upload to Colab:
1. `tmp/colab_training_data.json`
2. The fine-tuning notebook (see `notebooks/` for the Colab notebook)

**Colab notebook steps:**
1. Install dependencies (sentence-transformers, peft)
2. Upload training data
3. Load `intfloat/multilingual-e5-large`, apply LoRA (rank=16, Q/V attention)
4. Baseline evaluation
5. Train 10 epochs with MNR loss (batch 8x16=128 effective)
6. Final evaluation
7. Save and download model

**Key hyperparameters:**
- LoRA rank: 16, alpha: 32, dropout: 0.1
- Target modules: `query`, `value`
- Effective batch: 128 (8 physical x 16 grad accum)
- Learning rate: 5e-5 with cosine schedule
- Temperature: 20.0
- Max seq length: 256

### Step 4: Generate Embeddings with Fine-Tuned Model

```bash
# Unzip the downloaded model
unzip finetuned_e5_large_hadith.zip -d tmp/finetuned_model/

# Generate embeddings (script auto-merges LoRA weights)
python3 scripts/generate_hadith_embeddings.py \
  --model tmp/finetuned_model/finetuned_e5_large_hadith \
  --force --batch-size 64
```

**Note:** The save step in Colab may produce PEFT-format weights without `adapter_config.json`. The script detects this and auto-merges LoRA weights (base + lora_A @ lora_B * scaling).

---

## Similar Hadith Search

### How It Works

The similar hadith feature (`/v1/narrations/similar?id=...`) uses a **hybrid retrieval** system:

1. **Semantic kNN** - Fetches the source hadith's pre-computed `semantic_vector` from ES and uses it as the query vector for kNN search against all other docs' `semantic_vector`. No ES inference model needed.

2. **Lexical BM25** - `multiMatch` on `arabic` and `semantic_text` fields with significant terms boosting.

3. **RRF Fusion** - Combines semantic (85%) and lexical (15%) results using Reciprocal Rank Fusion.

4. **Scoring** - Each candidate gets a `retrievalPercent` from:
   - Semantic similarity: 50%
   - Topic tag overlap: 25%
   - Content overlap: 15%
   - Syntactic similarity: 10%

5. **Eligibility** - Semantic hits are trusted through (the embedding already learned conceptual similarity). Non-semantic candidates require shared topic tags or distinctive tokens. Near-duplicate removal at 92% syntactic threshold.

6. **LLM Reranker** - Disabled. Retrieval-ranked results are returned directly.

7. **Caching** - Results cached for 6 hours per hadith ID (Caffeine cache).

### Configuration (Environment Variables)

All constants in `SimilarHadithService` are overridable:

```bash
# Semantic search
SIMILAR_SEMANTIC_MIN_SIMILARITY=0.72    # Minimum cosine similarity
SIMILAR_SEMANTIC_POOL_SIZE=220         # kNN results to fetch
SIMILAR_SEMANTIC_NUM_CANDIDATES=500    # HNSW candidates

# Lexical search
SIMILAR_LEXICAL_POOL_SIZE=220
SIMILAR_LEXICAL_MIN_SHOULD_MATCH=55%

# Hybrid
SIMILAR_HYBRID_CANDIDATE_LIMIT=280
SIMILAR_RRF_SEMANTIC_WEIGHT=0.85
SIMILAR_RRF_LEXICAL_WEIGHT=0.15

# Display
SIMILAR_FINAL_MIN_PERCENT=40           # Minimum score to show (default)
SIMILAR_MAX_RESULT_ITEMS=40

# Ranking weights
SIMILAR_RETRIEVAL_SYNTACTIC_WEIGHT=0.10
SIMILAR_RETRIEVAL_CONTENT_WEIGHT=0.15
SIMILAR_RETRIEVAL_TOPIC_WEIGHT=0.25
# Semantic weight is 1.0 - sum of the above (0.50)

# Application properties
similar.rerank.enabled=false            # Disable LLM reranker
similar.retrieval.min-percent=40        # Min retrieval percent to display
```

---

## LLM Similar Hadith Processing

### Verdict Format Requirements

When judging hadith similarity manually or via agents, the verdict must follow this exact format:
- `SIMILAR_WORDING` - Nearly identical hadith across different sources
- `SIMILAR_CONCEPTUAL` - Same teaching with different wording
- `rejected` - No significant similarity

Any other verdict format will be counted as invalid during merging.

### Processing Batches

When processing batches with low candidate counts:
- Continue processing even if a batch has only 1-5 candidates
- The system will skip hadith that have no candidates to judge
- Each judged hadith will be marked as processed regardless of outcome
- Focus on completing batches rather than skipping them

### Optimizations for Low-Candidate Batches

1. **Process all batches** - Don't skip batches with few candidates
2. **Lower the threshold** - Consider using 30% overlap instead of 40% for conceptual matches
3. **Batch multiple hadith** - Process 2-3 hadith per agent instead of 1 (avoid timeouts)

### Manual Judging Script

Use `scripts/judge_batch.py` for manual processing:
```bash
python3 scripts/judge_batch.py <batch_file> <output_file>
```

## Operational Procedures

### Adding New Hadith

1. Prepare JSONL batch file(s) with fields: `arabic`, `english`, `book`, `chapter`, `number`
2. Import: `python3 scripts/import_hadith_batches.py`
3. Backfill metadata: `python3 scripts/backfill_metadata.py`
4. Generate semantic text: run `SemanticMatnSourceBackfillTool`
5. Generate significant terms: run `SemanticSignificantTermsBackfillTool`
6. Apply topic tags: run `TopicTagsBackfillTool` or `import_tags_to_es.py`
7. Generate embeddings: `python3 scripts/generate_hadith_embeddings.py --model <model_path>`
8. Invalidate similar hadith cache: the app auto-invalidates per-ID on edit

### Adding New Quran Verses

1. Run: `python3 scripts/rebuild_and_retag_quran.py`
2. Or manually tag: run `QuranVerseTaggingTool`

### Adding New Tafsir Source

1. Create extractor class in `src/main/java/com/rewayaat/tafsir/extractors/`
2. Register in `TafsirExtractionTool`
3. Cache source HTML pages
4. Run: `python3 scripts/extract_and_index_tafsir.py`
5. Run: `python3 scripts/import_split_tafsir.py`
6. Rebuild quranic light: `python3 scripts/build_quranic_light_index.py`

### Rebuilding Embeddings (After Model Update)

1. Place new model in `tmp/finetuned_model/<name>/`
2. Run: `python3 scripts/generate_hadith_embeddings.py --model tmp/finetuned_model/<name> --force`
3. No restart needed - kNN search reads `semantic_vector` directly from ES

### Retraining the Embedding Model

1. Build dataset: `python3 scripts/build_training_dataset.py`
2. Export: `python3 scripts/export_colab_data.py`
3. Upload `tmp/colab_training_data.json` to Colab
4. Run fine-tuning notebook
5. Download model zip
6. Generate embeddings: `python3 scripts/generate_hadith_embeddings.py --model <path> --force`

### Rebuilding Quranic Light (After Tag Changes)

```bash
python3 scripts/build_quranic_light_index.py
```

This rebuilds the entire `rewayaat_quranic_light` index from scratch.

### Tag Migration

```bash
python3 scripts/tag_migration.py
# Or for full run:
bash scripts/run_topic_tags_full.sh
```

---

## Configuration Reference

### Elasticsearch Index Mapping (Key Fields)

```
rewayaat_updated:
  semantic_vector: dense_vector (dims=1024, cosine)
  semantic_matn_source: text (chain-free Arabic)
  semantic_english_hint_source: text (chain-free English, 120 chars)
  semantic_significant_terms_source: text (TF-IDF terms)
  topic_tags: keyword (tag slugs)
  arabic: text (full Arabic with chains)
  english: text (full English with chains)
  book: keyword
  chapter: keyword
  volume: keyword
  part: keyword
  section: keyword
  number: integer
  gradings: nested
  source: keyword
  related: keyword
```

### Spring Boot Properties

Key properties in `application-dev.properties`:
```properties
spring.elasticsearch.uris=http://localhost:9200
rewayaat.elasticsearch.index=rewayaat_updated
server.port=8002
management.server.port=8003
```

---

## Scripts Marked for Deletion Review

The following scripts appear outdated, superseded, or one-shot utilities. **Do not delete without review.**

### Likely Outdated (Superseded by Newer Scripts)

| Script | Reason |
|--------|--------|
| `scripts/import_embeddings_to_es.py` | Replaced by `generate_hadith_embeddings.py` which writes directly to ES. Only needed if generating embeddings on Colab and importing via npz. |
| `scripts/apply_corrected_batch_tags.py` | One-shot utility for a specific tag correction. |
| `scripts/overwrite_batch_tags_from_review.py` | One-shot utility for review overwrite. |
| `scripts/summarize_tagging_review.py` | One-shot review summary. |
| `scripts/export_for_tagging.py` | Export for manual tagging review (may still be useful). |
| `scripts/export_tagging_review_batches.py` | Batch export for tagging review (may still be useful). |
| `scripts/export_verses_for_tagging.py` | Verse export for tagging (may still be useful). |
| `scripts/apply_quranic_light_filter.py` | One-shot filtering utility. |
| `scripts/export_quranic_light_for_filtering.py` | One-shot export for filtering. |
| `scripts/run_llm_tagging_audit.py` | One-shot LLM audit tool. |
| `scripts/extract_chains_for_narrators.py` | Research/utility script for narrator chains. |
| `scripts/export_multi_verse_tafsir.py` | One-shot tafsir export. |

### Download Scripts (Possibly Outdated)

| Script | Reason |
|--------|--------|
| `scripts/download_full_quran.py` | Quran download utility - may be redundant with `rebuild_and_retag_quran.py`. |
| `scripts/download_quran_chapters.sh` | Shell-based Quran download. |
| `scripts/download_quran_data.sh` | Another Quran download variant. |
| `scripts/download_quran_with_curl.sh` | Yet another download variant. |

### Other Scripts to Review

| Script | Reason |
|--------|--------|
| `scripts/chain-audit.sh` | Audit script for narrator chains - likely one-shot. |
| `scripts/create_synonym_search_index.py` | Synonym index creation - may be handled by ES config. |
| `scripts/export_hadith_batches.py` | Export batches (reverse of import). |
| `scripts/import_verse_tags.py` | Verse tag import - may be superseded by `rebuild_and_retag_quran.py`. |
| `scripts/index_tafsir_json.py` | Alternative tafsir import from JSON - may be superseded by `extract_and_index_tafsir.py`. |
| `scripts/finetune_hadith_embeddings.py` | Local fine-tuning script - Colab notebook is the primary path now. |
| `scripts/run_tag_migration.sh` | Wrapper around `tag_migration.py`. |
| `scripts/run_topic_tags_full.sh` | Wrapper for full tag run. |

### Temporary/Generated Files

| Path | Reason |
|------|--------|
| `tmp/` directory | Generated data (training sets, embeddings, eval results). Safe to delete and regenerate. |
| `batches.zip` | Archived batch data, likely already imported. |
| `finetuned_e5_large_hadith.zip` | Downloaded model zip, already extracted to `tmp/finetuned_model/`. |
| `llm-rerank-removal-bundle.tar.gz` | Patch bundle, already applied. |
| `remove-llm-rerank.patch` | Applied patch file. |
| `notebooks/` | Colab notebooks - keep these. |
