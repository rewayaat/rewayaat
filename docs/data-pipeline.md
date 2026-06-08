# Data Pipeline

How hadith, Quran, tafsir, and derived data get into the system.

## Hadith Pipeline

### 1. Import

```bash
python3 scripts/import_hadith_batches.py
```

Reads JSONL batch files from `batches/` and bulk-imports into `rewayaat_updated`. Document IDs use format `bookId:hadithNumber` (e.g., `Al-Kafi-Volume-1-Kulayni:1048`).

### 2. Backfill Metadata

```bash
python3 scripts/backfill_metadata.py
```

Fetches 33 books from the Thaqalayn API (`https://www.thaqalayn-api.net/api/v2`), enriches ES docs with `volume`, `part`, `section`, `gradings`, `source`, `related`. No API key required.

### 3. Generate Chain-Free Text

```bash
REWAYAAT_INDEX=rewayaat_updated \
mvn -B -q -DskipTests dependency:build-classpath -Dmdep.outputFile=/tmp/rewayaat.cp

REWAYAAT_INDEX=rewayaat_updated \
java -cp "target/classes:$(cat /tmp/rewayaat.cp)" \
  com.rewayaat.tools.SemanticMatnSourceBackfillTool
```

Strips narrator chains from Arabic text to produce `semantic_matn_source` (matn only). Also generates `semantic_english_hint_source` (chain-free English, 120 chars).

### 4. Extract Significant Terms

```bash
REWAYAAT_INDEX=rewayaat_updated \
java -cp "target/classes:$(cat /tmp/rewayaat.cp)" \
  com.rewayaat.tools.SemanticSignificantTermsBackfillTool
```

Extracts high-signal Arabic terms using TF-IDF against the corpus. Stored as `semantic_significant_terms_source`.

### 5. Apply Topic Tags

```bash
python3 scripts/import_tags_to_es.py --tags-dir batches/ --es-url http://localhost:9200 --index rewayaat_updated
```

Imports pre-assigned tags from batch files. Tags are expanded with ancestors from `taxonomy.json` (only `taggable` ancestors included). See architecture.md for taxonomy structure.

## Quran Pipeline

### Build Verse Index

```bash
python3 scripts/rebuild_and_retag_quran.py
```

Downloads Quran data (Arabic + English), creates `rewayaat_quran` index, applies AI-powered topic tagging using the same taxonomy.

### Alternative: Java Tagging Tool

```bash
REWAYAAT_INDEX=rewayaat_quran \
java -cp "target/classes:$(cat /tmp/rewayaat.cp)" \
  com.rewayaat.tools.QuranVerseTaggingTool
```

## Tafsir Pipeline

### Extract and Index

Tafsir is extracted from cached HTML pages using 13+ extractors:

| Extractor | Source |
|-----------|--------|
| `AlMizanExtractor` | Al-Mizan (Tabatabai) |
| `EnlighteningCommentaryExtractor` | An Enlightening Commentary into the Light of the Holy Quran |
| `PooyaYazdiExtractor` | Pooya Yazdi commentary |
| `DivineLightsExtractor` | Divine Lights |
| `AlBayanExtractor` | Al-Bayan |
| `HubEAliExtractor` | Hub-e-Ali |
| `FatimaZahraExtractor` | Fatima Zahra |
| `ImamAskariExtractor` | Imam Askari |
| `KhomeiniHamdExtractor` | Khomeini Hamd |
| `HodaAlQuranExtractor` | Hoda Al-Quran |
| `QuranicReflectionsExtractor` | Quranic Reflections |
| `AlIslamHtmlExtractor` | Al-Islam |
| `SingleSurahExtractor` | Single Surah |

Each extractor parses HTML and produces `TafsirDocument` objects with `verse_keys`, `section_title`, `arabic_text`, `english_text`, `source_name`.

```bash
# Extract from cached HTML
python3 scripts/extract_and_index_tafsir.py

# Import split tafsir (per-verse documents)
python3 scripts/import_split_tafsir.py

# Alternative: import from JSON
python3 scripts/index_tafsir_json.py
```

**Key classes**: `TafsirIndexManager`, `TafsirDocument`, `VerseReferenceParser`, `SurahNameResolver`, `TafsirSnippetSanitizer`.

## Quranic Light Pipeline

Builds hadith-to-Quran connections using 4 signals:

| Signal | Weight | Description |
|--------|--------|-------------|
| Tag overlap | Varies | Hadith topic_tags vs verse topic_tags |
| Arabic citation | High | Direct Quran quotation in hadith matn |
| Verse terms | Medium | Distinctive Quran vocabulary in hadith |
| Tafsir content | Medium | Hadith text matches tafsir commentary |

```bash
python3 scripts/build_quranic_light_index.py
```

Signals are combined with weighted scoring and threshold filtering. Output stored in `rewayaat_quranic_light` index.

**LLM Filtering**: The judge pipeline (`docs/qlight-excerpt-resume.md`) further filters these connections using Claude agents to keep only strong matches. Output goes to `rewayaat_quranic_light_filtered`.

```bash
python3 scripts/apply_quranic_light_filter.py  # Apply filtered results to ES
```

## Embedding Generation

### Text Format

The text fed to the embedding model:

```
{arabic_matn_chain_free} || {english_hint_chain_free}
```

- Arabic matn: from `semantic_matn_source` (chains stripped, honorifics removed, max 3800 chars)
- English hint: from `semantic_english_hint_source` (truncated at sentence boundary within 300 chars)
- No `passage:` prefix, no topics, no key terms

### Generate Embeddings

```bash
python3 scripts/generate_hadith_embeddings.py \
  --model tmp/finetuned_model/finetuned_e5_large_hadith \
  --batch-size 64
```

- `--model` — Path to model (default: `intfloat/multilingual-e5-large`)
- `--force` — Regenerate all (default: skip docs with existing `semantic_vector`)
- Auto-detects and merges PEFT/LoRA weights

### Fine-Tuning the Model

1. Build training dataset: `python3 scripts/build_training_dataset.py`
2. Export for Colab: `python3 scripts/export_colab_data.py`
3. Upload to Colab and run fine-tuning notebook (`notebooks/rewayaat_embedding_pipeline.ipynb`)
4. Download model, generate embeddings with `--force`

**Model config**: LoRA rank 16, alpha 32, target modules: query+value, 1024-dim vectors, mean pooling.

## LLM Similar Hadith Pipeline

Uses Claude Code sub-agents (Sonnet) to judge whether pairs of hadith are genuinely similar. Candidate pairs are retrieved from ES via multiple retrieval methods, then agents judge each pair as "similar" or "rejected".

**Final results (June 2026)**: 374,461 pairs judged, 47,522 similar (12.7%). Loaded into ES as `llm_similar` nested field on 25,273 docs.

### Architecture

```
ES (rewayaat_updated)
  ↓ candidate retrieval (4 methods)
tmp/precomputed.jsonl  (all 32,516 hadith × top-30 candidates each)
  ↓ prepare-batches (skip already-cached pairs)
tmp/batches/ + tmp/batches_new/  (10,988 batch files)
  ↓ Claude Code sub-agents (Sonnet, 7 concurrent)
tmp/results_batch_XXXX.json  (agent outputs)
  ↓ merge into cache
tmp/pairs_cache.json  (master cache: 374K pairs)
  ↓ load_llm_similar_to_es.py
ES llm_similar nested field  (25,273 docs updated)
```

### Key Files

| File | Purpose |
|------|---------|
| `scripts/llm_similar_hadith.py` | All-in-one tool: precompute, build-cache, prepare-batches, merge |
| `scripts/load_llm_similar_to_es.py` | Load pairs_cache.json into ES `llm_similar` field |
| `tmp/precomputed.jsonl` | All hadith with top-30 candidate pairs (~1.3GB, 32,516 entries) |
| `tmp/pairs_cache.json` | Master cache of judged pairs. Format: `{"id_a\|\|id_b": {verdict, match_type, reason}}` |
| `tmp/batches/batch_XXX.json` | Agent input batch files (6,000 files) |
| `tmp/batches_new/batch_XXX.json` | Secondary batch files (5,090 files) |

### Step 1: Precompute Candidates

Retrieve candidate pairs for every hadith using 4 ES-based methods:

| Method | ES Query | Description |
|--------|----------|-------------|
| `bm25_arabic` | `more_like_this` on `semantic_matn_source` | Arabic text similarity (min_doc_freq=3, 30% match) |
| `bm25_english` | `more_like_this` on `semantic_english_hint_source` | English hint similarity (min_doc_freq=2, 30% match) |
| `topic_overlap` | `bool` with `term` on `topic_tags` (min 2 overlap) | Shared topic tags |
| `same_chapter` | `bool` with `term` on `book` + `chapter` | Same book/chapter neighbors |

Each method returns up to 25 candidates. Results are merged, deduplicated, and sorted by score. Top 30 candidates per hadith are kept.

```bash
# Precompute candidates for all hadith (takes ~30 min, resume-safe)
python3 scripts/llm_similar_hadith.py --precompute 0 --output tmp/precomputed.jsonl

# Precompute for N hadith only
python3 scripts/llm_similar_hadith.py --precompute 100 --output tmp/precomputed.jsonl
```

**Output format** (`tmp/precomputed.jsonl`, one JSON object per line):
```json
{
  "source_id": "Al-Kafi-Volume-1-Kulayni:1048",
  "source_arabic": "...",
  "source_english": "...",
  "source_tags": ["prayer", "worship"],
  "candidates": [
    {"id": "...", "arabic": "...", "english": "...", "tags": [...], "score": 12.5, "method": "bm25_arabic"},
    ...
  ],
  "total_original": 45,
  "method_counts": {"bm25_arabic": 15, "bm25_english": 10, "topic_overlap": 12, "same_chapter": 8}
}
```

### Step 2: Auto-Accept Obvious Matches

Automatically accept pairs with >80% Arabic token overlap (Jaccard similarity) as "wording" matches:

```bash
python3 scripts/llm_similar_hadith.py --build-cache tmp/precomputed.jsonl
```

### Step 3: Prepare Agent Batches

Create batch files from the precomputed candidates, skipping pairs already in cache. Batches are sorted by connectivity (hadith that appear most as candidates go first). Each batch contains 2 hadith with their uncached candidates.

```bash
# First round of batches
python3 scripts/llm_similar_hadith.py --prepare-batches --output-dir tmp/batches --batch-size 2

# Second round (run after cache has grown from first round)
python3 scripts/llm_similar_hadith.py --prepare-batches --output-dir tmp/batches_new --batch-size 2
```

**Batch file format** (`tmp/batches/batch_XXX.json`):
```json
{
  "batch_id": 1,
  "total_hadith": 2,
  "entries": [
    {
      "source_id": "Al-Kafi-Volume-1-Kulayni:1048",
      "source_arabic": "...(truncated 800 chars)...",
      "source_english": "...(truncated 400 chars)...",
      "source_tags": ["prayer"],
      "pre_cached_similar": [{"id": "...", "match_type": "wording", "reason": "..."}],
      "uncached_candidates": [
        {"id": "...", "arabic": "...(600 chars)", "english": "...(300 chars)", "tags": [...], "score": 12.5}
      ],
      "stats": {"original": 45, "pre_cached": 5, "uncached": 22}
    }
  ]
}
```

### Step 4: Run LLM Agents

Spawn Claude Code sub-agents to judge the uncached pairs. Each agent reads a batch file, compares each source hadith to its uncached candidates, and writes a results file.

**Agent prompt template:**
```
You are an expert in Islamic hadith studies, fluent in Arabic and English.
Your task is to judge whether pairs of hadith are genuinely similar or not.

Read the batch file at tmp/batches/batch_XXXX.json. For each entry in the batch:

1. Read the source hadith (source_id, source_arabic, source_english, source_tags)
2. For each candidate in uncached_candidates, compare it to the source hadith
3. Judge each pair as "similar" or "rejected"

Two hadith are SIMILAR if they share the same core narrative, teaching, or saying.
They are NOT similar if they merely share a general topic or keyword overlap.

Write results as a JSON array to tmp/results_batch_XXXX.json. Each element:
{"pair": "source_id||candidate_id", "verdict": "similar" or "rejected",
 "reason": "brief explanation", "match_type": "wording" or "conceptual" or "thematic"}

- match_type "wording": Same or near-identical Arabic text
- match_type "conceptual": Different wording but same specific teaching/narrative
- match_type "thematic": Same specific event/narrative from different angles
```

**Agent spawn pattern (Claude Code):**
```
Agent(
  description: "Judge hadith batch XXXX",
  prompt: "<above prompt>",
  model: "sonnet",
  run_in_background: true
)
```

**Operating parameters:**
- 7 agents concurrent (spawned in parallel via single message)
- Always pick smallest uncached batches first (sort by file size)
- Batches >50KB often hit context limits — stick to <45KB
- Similar rate: ~12.7% of pairs judged similar
- Success rate: ~95% with batches <45KB

### Step 5: Merge Results and Loop

After agents complete, merge their results into the master cache:

```bash
python3 scripts/llm_similar_hadith.py --merge tmp/results_batch_XXXX.json
```

Or use the inline merge script for bulk merging (used during the production run):

```python
import json, os, glob, re

cache = json.load(open('tmp/pairs_cache.json'))
for f in sorted(glob.glob('tmp/results_batch_*.json')):
    results = json.load(open(f))
    for r in results:
        pair = r.get('pair', '')
        parts = pair.split('||')
        if len(parts) != 2: continue
        key = '||'.join(sorted(parts))
        if key not in cache:
            cache[key] = r
    os.remove(f)
json.dump(cache, open('tmp/pairs_cache.json', 'w'))
```

**Main loop** (repeat until all batches cached):
1. Merge completed agent results into cache
2. Find next uncached batches (smallest first)
3. Spawn 7 replacement agents
4. Report: cache total, similar count, remaining batches

### Step 6: Load into Elasticsearch

Load the judged pairs from the cache into ES. This builds a bidirectional adjacency list and writes it as the `llm_similar` nested field on each hadith document.

```bash
# Dry run first (validates without writing)
python3 scripts/load_llm_similar_to_es.py --dry-run

# Load to local ES
python3 scripts/load_llm_similar_to_es.py --live

# Load to specific ES host (e.g., production via port-forward)
python3 scripts/load_llm_similar_to_es.py --live --es-host http://localhost:9201

# Resume interrupted load
python3 scripts/load_llm_similar_to_es.py --live --resume
```

**Production deployment:**
```bash
# Port-forward to production ES pod
kubectl port-forward -n elastic-v2 elasticsearch-v2-0 9201:9200 &

# Load to production
python3 scripts/load_llm_similar_to_es.py --live --es-host http://localhost:9201

# Verify: count docs with llm_similar (nested query required)
curl -s localhost:9201/rewayaat_updated/_count -H 'Content-Type: application/json' \
  -d '{"query":{"nested":{"path":"llm_similar","query":{"exists":{"field":"llm_similar.id"}}}}}'
```

**ES field mapping** (auto-created by the loader):
```json
"llm_similar": {
  "type": "nested",
  "dynamic": false,
  "properties": {
    "id": {"type": "keyword"},
    "match_type": {"type": "keyword"},
    "reason": {"type": "text", "index": false}
  }
}
```

**Match type normalization** (agent output → ES):
| Agent output | Normalized to | Description |
|-------------|---------------|-------------|
| `wording`, `wording_similar`, `exact`, `variant` | `wording` | Near-identical Arabic (~26%) |
| `conceptual`, `conceptually_similar`, `meaning`, `content`, `""` | `conceptual` | Same teaching, different words (~73%) |
| `thematic`, `theme`, `event`, `chain` | `thematic` | Same event from different angles (~1%) |

### Redoing the Pipeline from Scratch

If you need to rebuild the similar hadith data from scratch (e.g., after adding new hadith):

```bash
# 1. Precompute candidates (requires ES running with hadith data)
python3 scripts/llm_similar_hadith.py --precompute 0 --output tmp/precomputed.jsonl

# 2. Auto-accept obvious wording matches
python3 scripts/llm_similar_hadith.py --build-cache tmp/precomputed.jsonl

# 3. Prepare batches (creates tmp/batches/)
python3 scripts/llm_similar_hadith.py --prepare-batches --output-dir tmp/batches

# 4. Run agents (see Step 4 above) — this is the long step (~25+ hours)
#    Use the resume doc (docs/llm-similar-hadith-resume.md) for agent loop instructions

# 5. After all batches are judged, prepare a second round with remaining uncached
python3 scripts/llm_similar_hadith.py --prepare-batches --output-dir tmp/batches_new

# 6. Run agents again for second round

# 7. Load into ES
python3 scripts/load_llm_similar_to_es.py --live
```

**Note on incremental updates**: For a few new hadith, you can precompute just those, prepare a small batch set, run a few agents, and reload. The loader is idempotent — it overwrites the `llm_similar` field per doc.

## Adding New Hadith (Checklist)

1. Prepare JSONL batch files (`arabic`, `english`, `book`, `chapter`, `number`)
2. Import: `python3 scripts/import_hadith_batches.py`
3. Backfill metadata: `python3 scripts/backfill_metadata.py`
4. Generate chain-free text: `SemanticMatnSourceBackfillTool`
5. Generate significant terms: `SemanticSignificantTermsBackfillTool`
6. Apply tags: `import_tags_to_es.py` or `TopicTagsBackfillTool`
7. Generate embeddings: `generate_hadith_embeddings.py`
8. Re-run LLM similar hadith pipeline for new docs (see above)

## Adding New Tafsir Source (Checklist)

1. Create extractor class in `com.rewayaat.tafsir.extractors/`
2. Register in `TafsirExtractionTool`
3. Cache source HTML pages
4. Run extraction scripts
5. Rebuild Quranic Light: `build_quranic_light_index.py`
