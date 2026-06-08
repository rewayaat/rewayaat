# Hadith Text Annotation Pipeline — Resume Instructions

This document tells a new Claude Code agent exactly how to continue the annotation pipeline from wherever it left off. **Read this entire document before doing anything.**

## What This Pipeline Does

We annotate all 32,519 hadith in ES with four types of enrichment:
1. **God references** — Highlight names/titles/pronouns referring to God
2. **Quranic verse references** — Detect and link Quranic quotes/allusions with surah+ayah
3. **Translation verification** — Flag major translation errors for manual review
4. **Footnotes** — Explain terms, historical references, concepts for learners (100% certainty rule)

## Key Files & Locations

| File | Purpose |
|------|---------|
| `tmp/hadith-annotation/batches/batch_XXXX.jsonl` | Input batches (~15 hadith each) |
| `tmp/hadith-annotation/results/batch_XXXX_results.json` | Agent output files |
| `tmp/hadith-annotation/_progress.json` | Tracks processed/failed batches |
| `scripts/prepare_annotation_batches.py` | Create batches from ES |
| `scripts/run_annotation_agents.py` | Orchestration (status, prepare prompts) |
| `scripts/load_annotations_to_es.py` | Load results into ES |
| `docs/hadith-annotation-pipeline.md` | Full technical documentation |

## Annotation Format

Each hadith gets four new ES fields:
- `english_annotated` — English text with inline `<span>` tags
- `arabic_annotated` — Arabic text with inline `<span>` tags
- `footnotes` — `[{id, term, note}]` array
- `translation_suggestions` — `[{original, suggested, reason}]` array

HTML tags used in text:
- `<span class="god-ref" data-type="name|pronoun">...</span>` — God references
- `<span class="quran-ref" data-surah="N" data-ayah="N" data-ref-type="quote|mention|allusion">...</span>` — Quranic refs
- `<span class="fn-word" data-id="N">term</span>` — Footnote anchors

## Step 1: Check Current State

```bash
python3 scripts/run_annotation_agents.py --status
```

This shows: total batches, processed, failed, remaining, annotation counts.

## Step 2: Prepare Batches (if not done)

```bash
python3 scripts/prepare_annotation_batches.py
```

This exports hadith from ES, skipping those already annotated. Only needed once or after adding new hadith.

## Step 3: Spawn Agents

Spawn **6 agents** at a time using the Agent tool. Each agent processes one batch. Here's the pattern:

```
Agent(
  description: "Annotate hadith batch XXXX",
  prompt: """<paste the prompt from the batch prompt file>""",
  model: "sonnet",
  run_in_background: true
)
```

To get the prompts for the next 6 batches:
```bash
python3 scripts/run_annotation_agents.py --prepare-next 6
```

This saves prompt files to `tmp/hadith-annotation/results/batch_XXXX_prompt.txt`. Read each prompt file and pass it to an agent.

**Important:**
- Spawn all 6 agents in a **single message** (parallel tool calls)
- Use `model: "sonnet"`
- Use `run_in_background: true`
- The agent must write results to `tmp/hadith-annotation/results/batch_XXXX_results.json`

### Agent Result Format

Each agent writes a JSON file containing an array:
```json
[
  {
    "hadith_id": "id",
    "english_annotated": "annotated english text",
    "arabic_annotated": "annotated arabic text",
    "footnotes": [{"id": 1, "term": "word", "note": "explanation"}],
    "translation_suggestions": [{"original": "...", "suggested": "...", "reason": "..."}]
  }
]
```

## Step 4: The Main Loop

```
1. Spawn 6 agents (Step 3)
2. Wait for agent completion notifications (background agents notify automatically)
3. Mark completed batches: python3 scripts/run_annotation_agents.py --mark-done batch_XXXX.jsonl
4. Check status: python3 scripts/run_annotation_agents.py --status
5. Repeat from step 1
```

## Step 5: Load to ES (when all done or at milestones)

```bash
# Test first
python3 scripts/load_annotations_to_es.py --dry-run

# Load to local ES
python3 scripts/load_annotations_to_es.py --live

# Load to production
python3 scripts/load_annotations_to_es.py --live --es-host http://PROD_HOST:9200
```

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Agent returns malformed JSON | Mark as failed, re-prepare that batch |
| Context window limit | Batch size too large — reduce in prepare script |
| Missing annotations for a hadith | Agent may have skipped it — re-run that batch |
| ES mapping error | Run `load_annotations_to_es.py --live` which auto-creates mapping |

## Partial Loads

You can load results incrementally — the loader reads all result files and uses `_update` API. Already-loaded docs are skipped with `--resume` flag. This means you can:
1. Run 100 batches
2. Load to ES for testing
3. Continue running more batches
4. Load again (only new ones get updated)
