# Hadith Text Annotation

Annotates every hadith in `rewayaat_updated` with four kinds of enrichment, written to
new ES fields so the original `arabic` and `english` are never touched.

1. **God references** — names, titles and pronouns referring to God
2. **Quranic verse references** — quotes and allusions, linked to surah + ayah
3. **Translation verification** — major translation errors flagged for review
4. **Footnotes** — terms and concepts explained for learners

> **Status: in progress, nothing loaded to ES yet.**
> 306 of 2,420 batches judged (~13%). `english_annotated` exists on 0 of 32,519 docs —
> the results sit in `tmp/hadith-annotation/results/` and have never been loaded.
> Resume by running the loop below, or load what already exists with
> `scripts/annotation/load_annotations_to_es.py --live`.

All four types are applied in a single agent pass per hadith.

## Architecture

```
ES (rewayaat_updated)
  ↓ scroll export (skip annotated)
tmp/hadith-annotation/batches/batch_XXXX.jsonl  (~15 hadith each)
  ↓ Claude Code sub-agents (Sonnet, 6 concurrent)
tmp/hadith-annotation/results/batch_XXXX_results.json
  ↓ bulk update
ES (rewayaat_updated) — new fields added
```

## Scripts

| Script | Purpose |
|--------|---------|
| `scripts/annotation/prepare_annotation_batches.py` | Export hadith from ES into JSONL batches. Skips already-annotated. |
| `scripts/annotation/run_annotation_agents.py` | Orchestration: status, prepare prompts, track progress. |
| `scripts/annotation/load_annotations_to_es.py` | Bulk update ES with annotated fields. |

## ES Schema

New fields on `rewayaat_updated`:

```
english_annotated      text (not indexed) — annotated English text
arabic_annotated       text (not indexed) — annotated Arabic text
footnotes              nested [{id: int, term: keyword, note: text}]
translation_suggestions nested [{original: text, suggested: text, reason: text}]
```

Original `english` and `arabic` fields are never modified.

## Annotation Types

### 1. God References
- Tag: `<span class="god-ref" data-type="name|pronoun">text</span>`
- All names, titles, pronouns referring to God
- Arabic: detached pronouns only (not attached)
- Agent decides contextually what refers to God

### 2. Quranic Verse References
- Tag: `<span class="quran-ref" data-surah="N" data-ayah="N" data-ref-type="quote|mention|allusion">text</span>`
- Types: direct quotes, explicit mentions, strong allusions
- Must identify surah + ayah. If uncertain, don't annotate.

### 3. Translation Verification
- Stored in `translation_suggestions` array
- Major errors only: missing clauses, wrong meaning, significant omissions
- Not for stylistic preferences

### 4. Footnotes
- Tag: `<span class="fn-word" data-id="N">term</span>`
- Stored in `footnotes` array: `{id, term, note}`
- Scope: anything a hadith learner would benefit from
- 100% certainty rule — if not absolutely sure, don't add
- 1-3 sentences per note

## Frontend

### CSS (manuscript.css)
- `.god-ref` — bold, gold color for names
- `.quran-ref` — purple background, dashed underline, clickable
- `.fn-word` — dotted underline, gold accent, hover highlight

### JavaScript (rewayaat.js)
- Quranic hover: fetch verse from `rewayaat_quran` on hover, show in tooltip
- Footnote click: show footnote note in tooltip/popover

### Templates
- Prefer `english_annotated` / `arabic_annotated` over raw fields when available

---

# Running the Pipeline

## Step 1: Check Current State

```bash
python3 scripts/annotation/run_annotation_agents.py --status
```

This shows: total batches, processed, failed, remaining, annotation counts.

## Step 2: Prepare Batches (if not done)

```bash
python3 scripts/annotation/prepare_annotation_batches.py
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
python3 scripts/annotation/run_annotation_agents.py --prepare-next 6
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
3. Mark completed batches: python3 scripts/annotation/run_annotation_agents.py --mark-done batch_XXXX.jsonl
4. Check status: python3 scripts/annotation/run_annotation_agents.py --status
5. Repeat from step 1
```

## Step 5: Load to ES (when all done or at milestones)

```bash
# Test first
python3 scripts/annotation/load_annotations_to_es.py --dry-run

# Load to local ES
python3 scripts/annotation/load_annotations_to_es.py --live

# Load to production
python3 scripts/annotation/load_annotations_to_es.py --live --es-host http://PROD_HOST:9200
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
