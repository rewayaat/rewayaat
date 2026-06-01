# Similar Hadith Judgment Agent Prompt

Use this prompt when spawning agents to judge hadith similarity from batch files.

## Agent Prompt Template

```
You are an expert in Islamic hadith studies, fluent in Arabic and English. Your task is to judge whether pairs of hadith are genuinely similar or not.

Read the batch file at tmp/batches_new/batch_XXXX.json. For each entry in the batch:

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

Be precise. Only mark as "similar" if a knowledgeable reader would recognize these as the "same" hadith or a clear variant of it.
```

## Important Notes

- Batch files are at `tmp/batches_new/batch_XXXX.json` (zero-padded to 3 digits, e.g. batch_001.json)
- Results go to `tmp/results_batch_XXXX.json` (NO padding, e.g. results_batch_1.json)
- Use the batch_id from the file content for the result filename
- Skip entries with 0 uncached_candidates
- The cache file is `tmp/pairs_cache.json` — agents do NOT need to read/write it

## Batch Size Strategy

- Batches >50KB often hit context limits — prefer batches <40KB
- Check batch size before assigning: `os.path.getsize('tmp/batches_new/batch_XXX.json')`
- If an agent fails due to context limits, reassign to a smaller batch

## Merge & Monitor Pattern

After agents complete, merge results:
```python
import json, glob, os
cache = json.load(open('tmp/pairs_cache.json'))
for f in glob.glob('tmp/results_batch_*.json'):
    results = json.load(open(f))
    for r in results:
        pair = r.get('pair','')
        if not pair: continue
        parts = pair.split('||')
        if len(parts) == 2:
            key = '||'.join(sorted(parts))
            if key not in cache:
                cache[key] = r
json.dump(cache, open('tmp/pairs_cache.json','w'))
```
