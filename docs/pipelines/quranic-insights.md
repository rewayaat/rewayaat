# Quranic Insights

Connects each hadith to the Quranic verses that illuminate it, with tafsir commentary
attached. Served at runtime by `QuranicInsightsService` from the
`rewayaat_quranic_light_filtered` index — no LLM calls happen during a request.

## Stage Status

| # | Stage | Script | State |
|---|-------|--------|-------|
| 1 | Build candidate connections | `scripts/quranic-insights/build_quranic_light_index.py` | done |
| 2 | LLM judge — keep only strong links | `apply_quranic_light_filter.py` | done (651/651 batches) |
| 3 | Strict re-judge | `generate_strict_judge_batches.py`, `apply_strict_judge_filter.py` | done |
| 4 | Extract `relevant_excerpt` | `prepare_excerpt_batches.py`, `apply_excerpts.py` | **partial — 8,947 / 17,612 snippets (51%)** |
| 5 | Add `<em>` highlighting | `generate_highlight_batches.py`, `apply_highlighting.py` | done (17,612 / 17,612) |

Live index: 22,640 hadith docs carrying 17,612 tafsir snippets.

## Index Shape

`rewayaat_quranic_light_filtered`, keyed by `hadith_id`:

```
hadith_id, hadith_book, hadith_chapter, hadith_english, hadith_topic_tags, ...
candidates: nested
  verse_key, surah_number, ayah_number, surah_name_english
  text_arabic, text_english
  combined_score, rank, signal_scores, shared_tags
  tafsir_snippets: nested
    tafsir_slug, tafsir_name, section_title, source_url
    commentary_text                 # full tafsir passage
    commentary_text_highlighted     # same text with <em> around the relevant part
    relevant_excerpt                # 1-3 sentence exact substring of commentary_text
    commentary_score
```

Canonical tafsir slugs: `enlightening-commentary`, `quranic-reflections`,
`pooya-mir-ahmad-ali`, `ar-amthal`, `divine-lights`, `al-mizan`, `imam-askari`.

`relevant_excerpt` and `commentary_text_highlighted` are independent and coexist:
the excerpt is a short standalone pull-quote, the highlighted variant keeps the whole
passage and marks the important span inside it.

## Stage 1 — Build Candidates

```bash
python3 scripts/quranic-insights/build_quranic_light_index.py
```

Scores every hadith against every verse using four signals, then keeps the top-ranked
candidates above a threshold:

| Signal | Description |
|--------|-------------|
| Tag overlap | Hadith `topic_tags` vs verse `topic_tags` |
| Arabic citation | Direct Quran quotation detected in the hadith matn |
| Verse terms | Distinctive Quranic vocabulary appearing in the hadith |
| Tafsir content | Hadith text matching tafsir commentary for the verse |

Output lands in `rewayaat_quranic_light` (unfiltered).

## Stage 2 — LLM Judge

Sub-agents read each hadith with its candidate verses and rule each connection
**strong**, moderate, or weak. Only *strong* survives.

```bash
python3 scripts/quranic-insights/export_quranic_light_for_filtering.py   # -> tmp/qlight-judge-inputs/
# agents write tmp/qlight-judge-outputs/batch_XXXX.jsonl
python3 scripts/quranic-insights/apply_quranic_light_filter.py           # -> rewayaat_quranic_light_filtered
```

> **Known issue**: `export_quranic_light_for_filtering.py` truncates `commentary_text`
> at `SNIPPET_TEXT_MAX = 600` chars. The full text lives in `rewayaat_tafsir`; repair
> truncated snippets with `scripts/quranic-insights/backfill_snippet_text.py`.

## Stage 4 — Relevant Excerpts *(unfinished)*

Extracts the 1-3 sentences of a tafsir snippet that most directly explain the
verse-hadith link, so the UI can show a short preview with an expand affordance.

```bash
python3 scripts/quranic-insights/prepare_excerpt_batches.py     # -> tmp/qlight-excerpt-inputs/
# agents write tmp/qlight-excerpt-outputs/batch_XXXX.jsonl
python3 scripts/quranic-insights/apply_excerpts.py
python3 scripts/quranic-insights/import_excerpts_to_es.py
```

Input line:
```json
{"hadith_id": "Al-Kafi-Volume-7-Kulayni:778", "verse_key": "5:87",
 "tafsir_slug": "enlightening-commentary", "tafsir_name": "...",
 "hadith_english": "...", "hadith_topic_tags": ["oaths-vows"],
 "commentary_text": "Full tafsir text about verse 5:87..."}
```

Output line:
```json
{"hadith_id": "Al-Kafi-Volume-7-Kulayni:778", "verse_key": "5:87",
 "tafsir_slug": "enlightening-commentary",
 "relevant_excerpt": "The 1-3 sentences that explain the connection..."}
```

Rules the agent must follow:

- `relevant_excerpt` must be an **exact substring** of `commentary_text` — the frontend
  locates it to wrap in `<em>`. Copy, never paraphrase.
- 1-3 sentences, enough to see *why* this verse matters to this hadith.
- Commentary under 300 chars: return it whole.
- Nothing clearly relevant: pick the most informative passage about the verse's meaning.
- Skip anything whose `commentary_text` already has `<em>` tags, emitting
  `{"...": "...", "relevant_excerpt": null, "skipped_reason": "already_has_em_tags"}`.

To resume, find the first missing output batch:

```bash
for f in tmp/qlight-excerpt-inputs/batch_*.jsonl; do
  b=$(basename "$f")
  [ ! -f "tmp/qlight-excerpt-outputs/$b" ] && echo "NEXT: $b" && break
done
```

Then spawn 2-3 Sonnet sub-agents, one batch each (~100 items, ~3-5 min per batch).

## Stage 5 — `<em>` Highlighting

Keeps the full commentary and wraps the relevant span in `<em>`, writing
`commentary_text_highlighted`.

```bash
python3 scripts/quranic-insights/generate_highlight_batches.py   # -> tmp/qlight-highlight-inputs/
# agents write tmp/qlight-highlight-outputs/batch_XXXX.jsonl
python3 scripts/quranic-insights/apply_highlighting.py --dry-run
python3 scripts/quranic-insights/apply_highlighting.py
```

The agent adds `<em>` tags and must not rewrite the text. `apply_highlighting.py` is
non-destructive: it matches on the `(hadith_id, verse_key, tafsir_slug)` triplet and
only fills `commentary_text_highlighted` where it is missing.

Push to production with:

```bash
kubectl port-forward -n elastic-v2 elasticsearch-v2-0 9201:9200 &
python3 scripts/quranic-insights/apply_highlights_to_prod.py --es-host http://localhost:9201
```

## Serving

```
GET /v1/narrations/quranic_insights?id={hadithId}
GET /v1/narrations/quranic_insights?id={hadithId}&count_only=true
```

`QuranicInsightsService` reads the filtered index, enriches verses from
`rewayaat_quran`, attaches the tafsir snippets, and returns them. `count_only` serves
the lightweight badge count.
