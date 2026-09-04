# Quranic Light Excerpt Extraction Pipeline — Resume Instructions

This document tells a new Claude Code agent exactly how to continue the excerpt extraction pipeline from wherever it left off. **Read this entire document before doing anything.**

## What This Pipeline Does

We use Claude Code sub-agents (Sonnet) to extract the most relevant portion of each tafsir snippet for a given hadith. The goal is to show users a concise highlighted excerpt that quickly explains **how the Quranic verse relates to the hadith**. If the user wants to research further, they can expand to see the full tafsir text.

**UI concept**: The `relevant_excerpt` is shown inline with `<em>` highlighting. An "expand" button reveals the full `commentary_text` for deeper study.

## Input Data

- **39,319 hadith-snippet pairs** across **394 batches** (100 items each, last batch may be smaller)
- Input batches: `tmp/qlight-excerpt-inputs/batch_XXXX.jsonl`
- Output batches: `tmp/qlight-excerpt-outputs/batch_XXXX.jsonl`

### Input format (one JSON per line):
```json
{
  "hadith_id": "Al-Kafi-Volume-7-Kulayni:778",
  "verse_key": "5:87",
  "tafsir_slug": "enlightening-commentary",
  "tafsir_name": "An Enlightening Commentary into the Light of the Holy Quran",
  "hadith_english": "Abu Abd Allah said the Messenger of Allah...",
  "hadith_topic_tags": ["oaths-vows", "parents"],
  "commentary_text": "Full tafsir text about verse 5:87..."
}
```

### Output format (one JSON per line):
```json
{
  "hadith_id": "Al-Kafi-Volume-7-Kulayni:778",
  "verse_key": "5:87",
  "tafsir_slug": "enlightening-commentary",
  "relevant_excerpt": "The specific 1-3 sentences from commentary_text that explain how verse 5:87 relates to this hadith about oaths and prohibitions..."
}
```

**Rules for `relevant_excerpt`:**
- Must be an exact substring of `commentary_text` (so the frontend can locate and `<em>`-wrap it)
- Should be 1-3 sentences, enough for the user to quickly understand the verse-hadith connection
- Prioritize clarity: a user reading the excerpt should immediately see "oh, this verse is about X, which is what the hadith is about"
- If the entire commentary is short (< 300 chars), just return it as-is
- If no specific part is clearly relevant, pick the most informative passage about the verse's meaning
- **SKIP entries where `commentary_text` already contains `<em>` tags** — these were processed by a previous excerpt pipeline. Output `{"hadith_id": "...", "verse_key": "...", "tafsir_slug": "...", "relevant_excerpt": null, "skipped_reason": "already_has_em_tags"}`

## Agent Prompt Template

```
You are an Islamic studies expert. For each hadith-snippet pair below, extract the portion of the tafsir commentary that is most relevant to understanding how the Quranic verse connects to this specific hadith.

The excerpt should be:
- An exact substring of the commentary_text (copy-paste, do not paraphrase)
- 1-3 sentences that help the reader quickly grasp the verse-hadith connection
- Focused on the thematic overlap between the verse's meaning and the hadith's subject

Read the file /home/zir0/git/rewayaat/tmp/qlight-excerpt-inputs/batch_XXXX.jsonl

For each entry, output a JSON object with:
- hadith_id (string)
- verse_key (string)
- tafsir_slug (string)
- relevant_excerpt (exact substring of commentary_text)

IMPORTANT: If the commentary_text already contains <em> tags, skip it — output: {"hadith_id": "...", "verse_key": "...", "tafsir_slug": "...", "relevant_excerpt": null, "skipped_reason": "already_has_em_tags"}

Write one JSON object per line to /home/zir0/git/rewayaat/tmp/qlight-excerpt-outputs/batch_XXXX.jsonl
```

## How to Run

1. Check which batches are complete:
```bash
ls tmp/qlight-excerpt-outputs/ 2>/dev/null | wc -l
```

2. Find next batch to process:
```bash
for i in $(seq -w 0 393); do
  [ ! -f "tmp/qlight-excerpt-outputs/batch_0${i}.jsonl" ] && echo "NEXT: batch_0${i}" && break
done
```

3. Spawn 2-3 agents at a time (user preference: max 2-3 parallel):
```
Agent tool → prompt with the template above, batch number filled in
```

4. Each agent processes one batch (~100 items, takes ~3-5 minutes)

5. Repeat until all 394 batches are done

## After All Batches Complete

Run the apply script to update ES:

```python
# For each output batch, load relevant_excerpt and update the corresponding
# hadith doc in rewayaat_quranic_light_filtered
# Match by (hadith_id, verse_key, tafsir_slug) → update snippet.relevant_excerpt
```

Then the frontend can:
1. Show `relevant_excerpt` with `<em>` wrapping (the highlighted preview)
2. On "expand" click, show the full `commentary_text`

## Progress Tracking

| Date | Batches Done | Notes |
|------|-------------|-------|
| June 3 | 0/394 | Pipeline created, batches exported, ready to start |

## Key Files

| File | Purpose |
|------|---------|
| `tmp/qlight-excerpt-inputs/batch_XXXX.jsonl` | Input batches (394 total, 100 items each) |
| `tmp/qlight-excerpt-outputs/batch_XXXX.jsonl` | Agent output (same batch numbering) |
| `docs/qlight-excerpt-resume.md` | This file |
| `scripts/backfill_snippet_text.py` | Reference for ES bulk update pattern |
