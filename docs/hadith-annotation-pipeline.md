# Hadith Text Annotation Pipeline — Technical Documentation

## Overview

Comprehensive annotation pass over all 32,519 hadith in `rewayaat_updated` ES index. Four annotation types applied in a single agent pass per hadith, stored in new ES fields alongside originals.

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
| `scripts/prepare_annotation_batches.py` | Export hadith from ES into JSONL batches. Skips already-annotated. |
| `scripts/run_annotation_agents.py` | Orchestration: status, prepare prompts, track progress. |
| `scripts/load_annotations_to_es.py` | Bulk update ES with annotated fields. |

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
