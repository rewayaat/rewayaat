# Quranic Insights

Connects each hadith to the Quranic verses that illuminate it, with tafsir commentary
attached. Served at runtime by `QuranicInsightsService` from the
`rewayaat_quranic_light_filtered` index — no LLM calls happen during a request.

## v2 — reviewed links (2026-11)

The index the site serves is now built by the v2 pipeline below. Stages 1–5 further down
are the v1 history that produced `rewayaat_quranic_light_filtered`; their outputs feed v2
only as candidates and as reusable `<em>` highlights.

Why it was rebuilt: a 50-narration spot check on 2026-11-24 found about 38% of links
strong, 36% on-topic but generic (any divorce hadith got 65:1), and 26% weak or wrong.
Narrations that quote a verse usually did not get that verse, because the v1 judges
only ever chose among three retrieved candidates. Readers saw a verse and a long
commentary on it with nothing saying why it was there.

| # | Step | Script | Agents |
|---|------|--------|--------|
| 0 | Dump the pipeline's indices to JSONL (read-only) | `dump_sources.py` | — |
| 1 | Find the verses each narration quotes (Arabic) or cites (English), with spans | `quoted_verses.py` | — |
| 2 | Review batches: live links scoring ≥ 5 plus quoted verses | `prepare_review_batches.py` | — |
| 3 | Review: keep/drop, add the precise verse, reason in English and Arabic | `REVIEW_INSTRUCTIONS.md`, `validate_review.py`, `verse.py` | Sonnet, 50 narrations a batch |
| 4 | Build documents: clean and choose tafsir passages, previews, quote spans | `build_insights_index.py`, `snippets.py` | — |
| 5 | Load a dated index and point the `rewayaat_quranic_insights` alias at it | `load_insights_index.py` | — |

Result of the 2026-11-25 run: 13,280 narrations reviewed in 266 batches (Sonnet, about
22.5M tokens); 7,526 narrations keep 10,287 links (4,886 quoted, 1,587 explains, 1,752
ruling, 2,062 theme); 553 verses were added that no retrieval had proposed; 820 links
have no English tafsir passage. A random 50 graded the way the November spot check was:
40 strong, 10 on topic but generic, none weak (it was 19 / 18 / 13).

All data lives in `tmp/qlight-v2/`. Run order:

```bash
kubectl port-forward -n elastic-v2 elasticsearch-v2-0 9201:9200 &
python3 scripts/quranic-insights/dump_sources.py --es-host http://localhost:9201 --out tmp/qlight-v2/sources
python3 scripts/quranic-insights/quoted_verses.py --hadith tmp/qlight-v2/sources/hadith.jsonl \
    --quran tmp/qlight-v2/sources/quran.jsonl --out tmp/qlight-v2/quoted_verses.jsonl
python3 scripts/quranic-insights/prepare_review_batches.py --sources tmp/qlight-v2/sources \
    --quoted tmp/qlight-v2/quoted_verses.jsonl --out tmp/qlight-v2/review/inputs
# one agent per batch, writing tmp/qlight-v2/review/outputs/batch_NNNN.jsonl
python3 scripts/quranic-insights/validate_review.py --all tmp/qlight-v2/review/inputs tmp/qlight-v2/review/outputs
python3 scripts/quranic-insights/build_insights_index.py --generated-at <ISO time>
python3 scripts/quranic-insights/load_insights_index.py --es-host <host> --index rewayaat_quranic_insights_<date>
python3 scripts/quranic-insights/load_insights_index.py --es-host <host> --alias-to rewayaat_quranic_insights_<date>
```

**Quote detection.** The books write the Qur'an in ordinary spelling and `rewayaat_quran`
is Uthmani, so both sides are reduced to a consonant skeleton (no diacritics, long
vowels or hamza seats; ة→ه). A run of 4+ matching words is a quote. A run of 4–5 words
must also match with و and ي kept (قال/قيل and إن/إني collide otherwise), have no
punctuation inside it, avoid prayer formulae (tahlil, in sha' Allah, …) and carry two
words the Qur'an uses at most 60 times. English citations are read from "(2:196)",
"(Surah An-Nisa 4:93)", "[Qur'an 21:69]". Each hit keeps its exact text and offsets.

**Review rules** are in `scripts/quranic-insights/REVIEW_INSTRUCTIONS.md`: a link is kept
only if a reader would say "that is the verse behind this narration"; kinds are quoted,
explains, ruling and theme; fiqh prefers the precise verse (2:234 for a widow's waiting
period, not 2:228); additions must quote the verse's opening, which the validator checks
against the Qur'an text. The two pilot batches were audited before the rest ran; the
audit added the attribution, phrasing and mis-citation rules.

**Tafsir passages** (`snippets.py`): the Quranic Reflections page header and adverts
are cut (the seasonal hadith, then verse and citation, that open many issues); the verse
repeated at the top of Enlightening Commentary and al-Mizan is cut; Divine Lights
passages that only restate verses are dropped; a passage split out of a multi-verse
section that never names the verse is shown at most once, after those that do;
identical text is shown once; Pooya only when no other English tafsir covers the verse.
Up to three English passages and one Arabic, each with a `preview` (the `<em>` span
chosen in v1 when the same passage survives, else the opening sentences).

### v2 document

```
hadith_id, hadith_book, hadith_chapter, candidate_count, top_verse_keys, version: "v2"
quotes: [{verse_key, source: arabic_quote|reference|reference_quote, field: arabic|english,
          start, end, text}]        # where the narration's own text quotes a kept verse
candidates: [{verse_key, surah_number, ayah_number, surah_name_english, surah_name_arabic,
              text_arabic, text_english, kind, reason, reason_ar, quoted, rank,
              combined_score,          # len - rank, so old readers keep the order
              tafsir_snippets: [{tafsir_slug, tafsir_name, section_title, source_url,
                                 language, commentary_text, commentary_text_highlighted,
                                 preview, commentary_score}]}]
```

Only `hadith_id`, `hadith_book`, `top_verse_keys`, `candidate_count`, `generated_at` and
`version` are mapped; the rest is `_source` only, since everything is read by id except
`hadith_for_verse`, which filters on `top_verse_keys`.

**Rollback** is one alias action: `load_insights_index.py --alias-to
rewayaat_quranic_light_filtered` points the app back at the v1 index, which the v2
code still reads (no reasons or previews; everything else as before).

## Stage Status

| # | Stage | Script | State |
|---|-------|--------|-------|
| 1 | Build candidate connections | `scripts/quranic-insights/build_quranic_light_index.py` | done |
| 2 | LLM judge — keep only strong links | `apply_quranic_light_filter.py` | done (651/651 batches) |
| 3 | Strict re-judge | `generate_strict_judge_batches.py`, `apply_strict_judge_filter.py` | done |
| 4 | Extract `relevant_excerpt` | `prepare_excerpt_batches.py`, `apply_excerpts.py` | **partial — 8,947 / 17,612 snippets (51%)** |
| 5 | Add `<em>` highlighting | `generate_highlight_batches.py`, `apply_highlighting.py` | done (17,612 / 17,612) |

v1 index (`rewayaat_quranic_light_filtered`) as served until the v2 rebuild: 11,426 narrations, 14,325 links (this line used to say 22,640, which was the count before the strict re-judge).

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

## Stage 3 — Strict Re-judge

A second, stricter judge reads only the connections stage 2 kept as strong and drops
any that are general rather than specific. Its output is
`{"hadith_id", "kept": [verse_key], "removed": [verse_key]}`.

```bash
python3 scripts/quranic-insights/generate_strict_judge_batches.py   # -> tmp/qlight-strict-judge-inputs/
# agents write tmp/qlight-strict-judge-outputs/batch_XXXX.jsonl
python3 scripts/quranic-insights/apply_strict_judge_filter.py --dry-run
python3 scripts/quranic-insights/apply_strict_judge_filter.py
```

`apply_strict_judge_filter.py` also applies stage-2 outputs (only `strength: strong`
survives) and keeps `candidate_count` and `top_verse_keys` in step with the remaining
candidates; it deletes a doc left with none.

> **Known issue (live index)**: the original stage 3 judges received empty verse text
> (the exporter read fields that do not exist, fixed in f92ddc7), and the original apply
> left `candidate_count` and `top_verse_keys` stale. On the live index 1,153 docs
> over-count and 9,869 docs list removed verses in `top_verse_keys`, which
> `hadith_for_verse` searches.

### Restored books (Ghurar, Mīzān, Mishkāt), October 2026

The original judge prompts were not preserved; the rebuilt ones are
`quranic-insights-v1-restore-judge-prompt.md` (stage 2) and
`quranic-insights-v1-restore-strict-prompt.md` (stage 3). Built in the staging index
`rewayaat_quranic_light_v1restore` (stage-1 copy `_stage1`), then copied create-only
into the live index:

| | hadith | verses |
|---|---|---|
| Stage 1 candidates | 17,518 | 59,071 |
| Stage 2 strong | 3,565 | 4,499 (12,721 moderate, 41,851 removed) |
| Stage 3 kept | 2,870 | 3,394 (1,105 removed) |

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

`QuranicInsightsService` reads the `rewayaat_quranic_insights` alias
(`quranic.insights.index`) by narration id and returns its links in order, each with
`kind`, `reason`, `reason_ar`, `quoted` and its tafsir passages (`preview`, `language`),
plus the narration's `quotes` spans. `count_only` serves the badge count. The Tafsir
panel shows the reason with a kind badge, the reader's language first, and each
passage as its preview with the whole passage behind Show more.
