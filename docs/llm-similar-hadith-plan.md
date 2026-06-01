# LLM-Powered Similar Hadith Discovery

> **Status**: Pipeline built, tested, and hardened. Ready for full-scale run.
> **Script**: `scripts/llm_similar_hadith.py` (~823 lines)
> **Last updated**: 2026-05-26

## Overview

Claude Code sub-agents judge hadith similarity using their own understanding of Arabic and Islamic concepts. The script handles ES retrieval only; agents do the filtering.

**Pivot rationale**: Fine-tuned `intfloat/multilingual-e5-large` with LoRA achieved only 83.79% accuracy (gap 0.13). LLM judgment produces higher-quality results.

## Similarity Criteria

- **SIMILAR (wording)**: Significant Arabic wording overlap (near-duplicate, variant narration)
- **SIMILAR (conceptual)**: Discusses the same core idea with different words (e.g., "reward of spending dirhams" ≈ "generosity with wealth")
- **NOT SIMILAR**: Only shares broad topic tag but different concept (tag noise)

## Architecture

```
Phase 1: Precompute → ES retrieval for all 32K hadith → JSONL
Phase 2: Build cache → Auto-accept >80% token overlap pairs
Phase 3: Prepare batches → Skip cached, order by connectivity
Phase 4: Agent batches → Spawn sub-agents, each processes 5 hadith
Phase 5: Merge results → File-locked merge into cache, mark processed
Repeat phases 3-5 until all hadith processed
```

## Retrieval Methods (4 per hadith)

1. **BM25 Arabic** — `more_like_this` on `semantic_matn_source`, top 50
2. **BM25 English** — `more_like_this` on `semantic_english_hint_source`, top 30
3. **Topic overlap** — shared ≥ 2 tags, top 50
4. **Same chapter** — same book+chapter, top 30

After merging and dedup, keep top 30 by BM25 score (opt 4).

## Optimizations

| # | Optimization | Benefit |
|---|---|---|
| 2 | Bidirectional pair caching | If (A,B) judged, skip when processing B |
| 3 | Batch hadith per agent | 5 hadith per agent reduces overhead |
| 4 | Pre-filter top 30 candidates | Agent reads less, faster judgment |
| 5 | Resume from checkpoint | Crash-resistant, skips processed IDs |
| 6 | Auto-accept >80% token overlap | Obvious wording matches skip agent |

## CLI Reference

```bash
# Phase 1: Precompute candidates for all hadith
python3 scripts/llm_similar_hadith.py --precompute 0 --output tmp/precomputed.jsonl
# Use 0 for all, or N for first N hadith. Resumes automatically.

# Phase 2: Auto-accept obvious matches
python3 scripts/llm_similar_hadith.py --build-cache tmp/precomputed.jsonl --cache tmp/pairs_cache.json

# Phase 3: Prepare agent batches
python3 scripts/llm_similar_hadith.py --prepare-batches --precomputed tmp/precomputed.jsonl --cache tmp/pairs_cache.json --processed tmp/processed_hadith.txt --batch-size 5

# Phase 5: Merge agent results
python3 scripts/llm_similar_hadith.py --merge tmp/results_batch_001.json tmp/results_batch_002.json --cache tmp/pairs_cache.json --processed tmp/processed_hadith.txt

# Stats
python3 scripts/llm_similar_hadith.py --stats --cache tmp/pairs_cache.json --processed tmp/processed_hadith.txt

# Individual hadith (debugging)
python3 scripts/llm_similar_hadith.py --id "Al-Kafi:1:1" --output tmp/test.json
python3 scripts/llm_similar_hadith.py --concepts "charity,reward" --arabic-terms "إنفاق صدقة" --output tmp/concepts.json
```

## File Layout

All files in `tmp/` (symlinked to `/mnt/share/rewayaat-backup/tmp/`):

| File | Description |
|---|---|
| `precomputed.jsonl` | Candidate lists for all hadith (one JSON per line) |
| `pairs_cache.json` | Judged pairs: `{canonical_key: {verdict, reason, match_type, source}}` |
| `pairs_cache.json.bak` | Backup created before each merge |
| `pairs_cache.json.lock` | File lock for concurrent merges |
| `processed_hadith.txt` | One hadith ID per line, fully processed |
| `batches/batch_*.json` | Batch files for agents |
| `results_batch_*.json` | Agent results before merge |

## Robustness

- **Atomic writes**: `tempfile.mkstemp` + `os.rename` prevents cache corruption
- **File locking**: `fcntl.flock` prevents concurrent merge corruption
- **Backup recovery**: Corrupted cache loads from `.bak` file
- **Malformed JSON tolerance**: Bad JSONL lines skipped with warning
- **Invalid verdict filtering**: Empty/non-standard verdicts rejected at merge
- **Per-method error handling**: One retrieval method failing doesn't kill the rest
- **Resume support**: Precompute skips already-done IDs; batches skip processed hadith

## Agent Prompt Template

Each agent receives a batch file with 5 hadith, each with ~30 uncached candidates:

```
Read the batch file at tmp/batches/batch_XXX.json.

For each hadith entry in the batch:
1. Read the source hadith (Arabic text + English hint + tags)
2. For each uncached candidate, judge similarity:
   - SIMILAR (wording): shares significant Arabic wording
   - SIMILAR (conceptual): same core idea, different words
   - NOT SIMILAR: only shares broad topic, different concept
3. Extract key concepts from the source hadith
4. Run concept search:
   python3 scripts/llm_similar_hadith.py --concepts "c1,c2" --arabic-terms "t1 t2" --output tmp/concepts_BATCH_ENTRY.json
5. Judge concept candidates too
6. Write results to tmp/results_batch_XXX.json

Output format:
{
  "results": [
    {
      "source_id": "...",
      "judgments": [
        {"id": "...", "verdict": "similar", "match_type": "wording|conceptual", "reason": "..."},
        {"id": "...", "verdict": "rejected", "reason": "..."}
      ]
    }
  ]
}
```

## Quality Assurance

- QA checkpoints every 200 hadith during full run
- Check: tag noise rejection rate, conceptual match quality, cross-book recall
- Known good test cases: Ghadir Khumm, Hadith al-Thaqalayn, spending dirhams

## Test Results (50 hadith, 38 processed)

- 1225 cached pairs: 216 similar (78 wording, 138 conceptual), 1009 rejected
- Bidirectional caching working: ~2.7% savings after 10 hadith, grows with processing
- Tag noise rejection rate: 97% (from earlier audit)
- Cross-book recall: 75% for famous hadith

## Full-Scale Run Instructions

1. Precompute all 32K: `--precompute 0` (estimated ~4-6 hours)
2. Build cache: `--build-cache` (auto-accepts obvious duplicates)
3. Loop:
   a. `--prepare-batches` (creates new batches, skips cached)
   b. Spawn 3-5 agents in parallel
   c. Wait for completion
   d. `--merge` results
   e. Check `--stats`
   f. `/compact` to manage context window
   g. Repeat until no new batches

## Resuming After Session Loss

1. Run `/compact` or start new session
2. Check stats: `python3 scripts/llm_similar_hadith.py --stats --cache tmp/pairs_cache.json --processed tmp/processed_hadith.txt`
3. If precomputed.jsonl is complete (32K lines), skip phase 1
4. If not, resume: `python3 scripts/llm_similar_hadith.py --precompute 0`
5. Continue from phase 3: `--prepare-batches` → agents → `--merge`
