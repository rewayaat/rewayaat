# LLM Similar Hadith Pipeline — Resume Instructions

This document tells a new Claude Code agent exactly how to continue the LLM similar hadith pipeline from wherever it left off. **Read this entire document before doing anything.**

## What This Pipeline Does

We use Claude Code sub-agents (Sonnet) to judge whether pairs of hadith are genuinely similar. Each hadith has ~15-50 candidate pairs (found via BM25 Arabic/English + topic overlap). Agents read a batch file with source hadith + candidates, judge each pair, and write results to a JSON file.

## Key Files & Locations

| File | Purpose |
|------|---------|
| `tmp/pairs_cache.json` | Master cache of all judged pairs (~280K entries). Dict: `{"id_a\|\|id_b": {verdict, match_type, reason, source}}` |
| `tmp/processed_hadith.txt` | List of hadith IDs that have been fully processed |
| `tmp/batches/batch_XXXX.json` | Batch files (6,000 total). Format: `{"batch_id": N, "entries": [...]}` |
| `tmp/batches_new/batch_XXXX.json` | Secondary batch files (5,090 total). Same format. |
| `tmp/results_batch_XXXX.json` | Agent output files. Format: `[{"pair": "a\|\|b", "verdict": "...", ...}]` |
| `tmp/precomputed.jsonl` | All 32,516 hadith with precomputed candidates (1.28GB) |
| `docs/similar-hadith-agent-prompt.md` | The prompt to give each sub-agent |
| `docs/llm-similar-hadith-plan.md` | Full technical plan (background reading) |

**NOTE**: `tmp/` is symlinked to `/mnt/share/rewayaat-backup/tmp/` (433GB disk).

## Step 1: Check Current State

Run this to see where things stand:

```bash
python3 << 'PYEOF'
import json, os

# Cache stats
cache = json.load(open('tmp/pairs_cache.json'))
total = len(cache)
similar = sum(1 for v in cache.values() if isinstance(v, dict) and v.get('verdict') == 'similar')
print(f"Cache: {total} total, {similar} similar")

# Processed hadith count
with open('tmp/processed_hadith.txt') as f:
    processed = len(f.readlines())
print(f"Processed hadith: {processed} / 32,516 ({100*processed/32516:.1f}%)")

# Count result files (indicates agent completions)
import glob
results = glob.glob('tmp/results_batch_*.json')
print(f"Unmerged result files: {len(results)}")
PYEOF
```

## Step 2: Merge Any Unmerged Results

If there are result files from previous agents, merge them first:

```bash
python3 << 'PYEOF'
import json, os, glob, re

cache = json.load(open('tmp/pairs_cache.json'))
initial = len(cache)

for f in sorted(glob.glob('tmp/results_batch_*.json')):
    # Extract batch number for logging
    m = re.search(r'results_batch_(\d+)', f)
    batch_num = m.group(1) if m else '?'

    try:
        results = json.load(open(f))
    except json.JSONDecodeError:
        print(f"  batch_{batch_num}: BAD JSON, skipping")
        # Delete corrupted file
        os.remove(f)
        continue

    if not isinstance(results, list):
        os.remove(f)
        continue

    added = 0
    similar_count = 0
    for r in results:
        pair = r.get('pair', '')
        if not pair:
            continue
        parts = pair.split('||')
        if len(parts) != 2:
            continue
        key = '||'.join(sorted(parts))
        if key not in cache:
            cache[key] = r
            added += 1
            if isinstance(r, dict) and r.get('verdict') == 'similar':
                similar_count += 1

    if added > 0:
        print(f"  batch_{batch_num}: +{added} pairs ({similar_count} similar)")
    os.remove(f)  # Clean up after merge

final = len(cache)
new_similar = sum(1 for v in cache.values() if isinstance(v, dict) and v.get('verdict') == 'similar')
print(f"\nCache: {initial} -> {final} (+{final - initial}), Similar: {new_similar}")
json.dump(cache, open('tmp/pairs_cache.json', 'w'))
PYEOF
```

## Step 3: Find Uncached Batches

Find batches that still have pairs not yet in the cache:

```bash
python3 << 'PYEOF'
import json, os, glob

cache = json.load(open('tmp/pairs_cache.json'))
print(f"Cache has {len(cache)} entries")

# Check both batch directories
uncached = []
for batch_dir in ['tmp/batches', 'tmp/batches_new']:
    for f in sorted(glob.glob(f'{batch_dir}/batch_*.json')):
        with open(f) as fh:
            data = json.load(fh)
        entries = data.get('entries', []) if isinstance(data, dict) else data

        uncached_count = 0
        for entry in entries:
            sid = entry.get('source_id', '')
            for c in entry.get('uncached_candidates', entry.get('candidates', [])):
                cid = c.get('id', c) if isinstance(c, dict) else c
                pk = f'{sid}||{cid}'
                key = '||'.join(sorted(pk.split('||')))
                if key not in cache:
                    uncached_count += 1

        if uncached_count > 0:
            size = os.path.getsize(f)
            uncached.append((os.path.basename(f), uncached_count, size, batch_dir))

uncached.sort(key=lambda x: x[2])  # Sort by size (smallest first)
print(f"Total uncached batches: {len(uncached)}")
print(f"Top 20 smallest:")
for name, count, size, d in uncached[:20]:
    print(f"  {name}: {size}B, {count} uncached pairs [{d}]")
PYEOF
```

## Step 4: Spawn Agents

Spawn 7-8 agents (background, Sonnet model) to judge batches. **Always pick smallest batches first** to avoid context limit failures. Here is the exact Agent tool call pattern:

```
Agent(
  description: "Judge hadith batch XXXX",
  prompt: """You are an expert in Islamic hadith studies, fluent in Arabic and English. Your task is to judge whether pairs of hadith are genuinely similar or not.

Read the batch file at tmp/batches/batch_XXXX.json. For each entry in the batch:

1. Read the source hadith (source_id, source_arabic, source_english, source_tags)
2. For each candidate in uncached_candidates, compare it to the source hadith
3. Judge each pair as "similar" or "rejected"

Two hadith are SIMILAR if they share the same core narrative, teaching, or saying — not just a general theme. Specifically:
- Same or near-identical matn (wording) in Arabic — even with different chains or books
- Different narrations of the same event or statement by the same Imam/Prophet
- Same specific teaching with substantially overlapping content
- Partial overlap where one hadith is a shorter version of the same narration

They are NOT similar if they merely share:
- A general topic (e.g., both about "prayer" but different teachings)
- A keyword overlap without shared substance
- Related but distinct teachings on the same broad subject

Write results as a JSON array to tmp/results_batch_XXXX.json. Each element:
{"pair": "source_id||candidate_id", "verdict": "similar" or "rejected", "reason": "brief explanation", "match_type": "wording" or "meaning" or "thematic"}

- match_type "wording": Same or near-identical Arabic text
- match_type "meaning": Different wording but same specific teaching/narrative
- match_type "thematic": Same specific event/narrative from different angles

Be precise. Only mark as "similar" if a knowledgeable reader would recognize these as the "same" hadith or a clear variant of it.""",
  model: "sonnet",
  run_in_background: true
)
```

**Important notes for spawning:**
- Spawn all 7-8 agents in a **single message** (parallel tool calls)
- Use `model: "sonnet"` — Haiku over-matches
- Use `run_in_background: true` — you'll get notified when they complete
- Adjust batch path: `tmp/batches/` or `tmp/batches_new/` depending on which directory has the batch
- **Batches >50KB often hit context limits** — stick to <45KB batches
- If an agent fails, reassign it to a smaller batch

## Step 5: The Main Loop

This is the core operating pattern. Repeat until all batches are cached:

```
1. Wait for agent completion notifications (background agents notify automatically)
2. When agents complete, check for result files
3. Merge all result files into cache (Step 2 script)
4. Find next uncached batches (Step 3 script)
5. Spawn replacement agents for completed ones (Step 4)
6. Report status: cache size, similar count, remaining batches, agents running
7. Go to step 1
```

**Key metrics to report each wave:**
- Cache total / similar count
- Batches processed this session
- Remaining uncached batches
- Agents currently running

## Known Timeout/Skip Batches

These batches have historically timed out. Skip them if they appear:
```
829, 887, 1397, 1459, 1335, 1040, 910, 873, 1839, 2080, 1202, 2444, 2334,
2535, 2541, 1084, 2579, 687, 2588, 598, 781, 1265, 2644, 1258, 2641, 2731,
780, 1247, 2629, 2631, 1065, 2515, 691, 2618, 2627, 3061, 3425, 3962, 4804,
4118, 1945, 698, 699, 700, 4832, 2511, 2067
```

## Troubleshooting

| Problem | Solution |
|---------|----------|
| "context window limit" errors | Pick smaller batches (<30KB). Spawn fewer agents. |
| Bad JSON in result file | Delete the file (script handles this). Agent will need to re-run that batch. |
| No progress between checks | Agents may still be running. Wait longer (~2-3 min per batch). |
| Cache not growing after merge | All pairs from those batches may have already been cached. Normal. |
| `processed_hadith.txt` count != cache count | Normal — processed count tracks which hadith had batches prepared, not which pairs are cached. |

## Expected Performance

- **Throughput**: ~7 agents × ~2 min/batch = ~3.5 batches/min
- **Similar rate**: ~15% of pairs judged similar (Sonnet)
- **Success rate**: ~100% with batches <45KB
- **Time to completion**: At ~5,000 remaining batches, ~20-25 hours

## When Done

When all batches are cached:
1. Run the merge script one final time
2. Report final stats: total pairs, similar pairs, coverage %
3. Update `docs/operations.md` and MEMORY.md with final numbers
