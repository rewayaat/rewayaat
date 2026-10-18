# Restore v1 Books — Phase 4: Agent Enrichment

**Goal:** The restored narrations carry what judgement produces: Arabic for the 220 untranslated titles and the 4,654 citations, topic tags, LLM-judged similar links (top-10 candidates), and Qurʾānic connections. Results are verified locally.

**Architecture:** Each stage reuses its existing pipeline and runbook, scoped to the three books by the phase-3 filters. Agent work is bounded, resumable from disk and **never dispatched without the user's go-ahead on a stated size** (memory `feedback-checkpoint-token-usage`). Simple judging runs on Sonnet (memory `use-sonnet-for-simple-jobs`; the similar-hadith runbook forbids Haiku, `docs/pipelines/llm-similar-hadith.md:193`).

**Working directory for all commands:** `/home/zir0/git/rewayaat-lost-books`

**Related plans:** [phase-1](2026-10-19-restore-v1-books-phase-1.md), [phase-2](2026-10-19-restore-v1-books-phase-2.md), [phase-3](2026-10-19-restore-v1-books-phase-3.md), phase-4 (this file), [phase-5](2026-10-19-restore-v1-books-phase-5.md)

**Depends on:** phase-3 (import, matn, terms, vectors; tool filters DEC-301..303).

**Maintainability expectations:** No new pipeline. Stage scripts gain scope filters only, in the DEC-301 style with a unit test. Agent prompts are committed beside their runbooks. Outputs go to `tmp/` (symlink, Step 0).

**Discovery evidence:** [`discovery-receipt.md`](2026-10-19-restore-v1-books/discovery-receipt.md) R5 (220 untranslated titles), R4 (163 cited books), R6 (previous workloads).

**Decision and evidence manifest:**

| ID | Decision | Files/symbols | Source evidence | Reuse target | Blast-radius query + classified siblings | Acceptance proof | Risk | Invalidation conditions |
|---|---|---|---|---|---|---|---|---|
| DEC-401 | Order: translations ∥ tags → similar → Qurʾānic. Tags first, because similar `topic_overlap` and Qurʾānic "tag overlap" read `topic_tags` | — | `docs/data-pipeline.md` candidate methods table; quranic signals table | — | — | stage DoDs | standard | — |
| DEC-402 | `cited_from_ar` built by rule from agent-translated book names. Unparsed citations keep the English (card fallback) | new `scripts/ingest/apply_v1_restore_translations.py` | citation shapes measured (4.1 table) | `localised` fallback `HadithCardFactory.java:178-190` | — | unit test 4.1 | standard | — |
| DEC-403 | `chapter_ar_source = "llm-translated"` for agent-made titles | 4.1 applier | provenance field exists (`chapter_ar_source = thaqalayn.com/ar` on live docs) | same field | — | count query | standard | — |
| DEC-404 | Tags: 36 batches of 500, 0–6 taggable slugs each. Output shape `{"assignments":[{"id","tags"}]}` | `import_tags_to_es.py:4-6` | R6: 67 × 500. Distribution 0:591 · 1:12,829 · 2:13,693 · 3:4,755 · 4+:652 | the importer expands ancestors | — | coverage ≥ 97% (existing 31,809/32,519 = 97.8%) | standard | — |
| DEC-405 | Similar: `LLM_SIMILAR_PREFILTER_TOP=10`, batches of 3 hadith, each file < 45 KB | `llm_similar_hadith.py` | user decision. Runbook `:196` (<45 KB) | runbook spawn pattern `:154-193` | the cache is shared with the original run → back it up first | load counts | high: cost | measured batch count > 1.5× estimate → ask again |
| DEC-406 | Qurʾānic stages 1, 2, 3, 5 for the new books. Stage 4 (excerpts) deferred: it is 51% for the existing corpus and the UI tolerates its absence | `scripts/quranic-insights/*` | `docs/pipelines/quranic-insights.md` stage table | runbook | the build script defaults to writing into the live filtered index (`build_quranic_light_index.py:19`), so set a staging index explicitly | counts | high: live-index write | — |

**Execution policy:** **Checkpoint before every dispatching step**: print the batch count and estimated agent-hours, and ask. 7–8 concurrent background Sonnet agents, as the runbook says. Resume = find the first missing output file. Local ES only.

---

## Phase 4.0 — Workspace

- [ ] **Step 1 (Exact):** `ln -s /mnt/share/rewayaat-backup/tmp /home/zir0/git/rewayaat-lost-books/tmp && git -C /home/zir0/git/rewayaat-lost-books check-ignore tmp`. Expected: `tmp` (ignored by `.gitignore:67`). The scripts' relative `tmp/...` defaults then resolve to the shared store, the same as in the main checkout.
- [ ] **Step 2 (Exact):** `cp tmp/pairs_cache.json tmp/v1-restore/pairs_cache.before-v1-restore.json && sha256sum tmp/pairs_cache.json > tmp/v1-restore/receipts/pairs_cache.before.sha256`

---

## Phase 4.1 — Arabic for titles and citations

**Objective:** The 220 untranslated titles get Arabic, and every parseable Mizan citation gets `cited_from_ar`.

**Estimated budget:** 60 min + **2 Sonnet agents** (one per list).

**Files changed:**
- Create: `scripts/ingest/apply_v1_restore_translations.py`, test `scripts/ingest/test_apply_v1_restore_translations.py`
- Create: `scripts/ingest/v1_restore_translations.json` (agent output, committed)
- Create: `scripts/ingest/v1_restore_cited_books.json` (the 163 distinct cited book names, generated)

### Steps

- [ ] **Step 1 (TDD): Failing test** for `citation_ar(citation, books_ar)`:
  | Input | Expected |
  |---|---|
  | `"Al-Kafi, volume:2, p. 491, number:9"` | `"الكافي، ج 2، ص 491، ح 9"` |
  | `"Nahj al-Balagha, Sermon 198"` | `"نهج البلاغة، الخطبة 198"` |
  | `"Nahj al-Balagha, Letter 31"` | `"نهج البلاغة، الكتاب 31"` |
  | `"Nahj al-Balagha, Saying 147"` | `"نهج البلاغة، الحكمة 147"` |
  | `"Ghurar al-Hikam, nos. 7921; 7922"` | `"غرر الحكم، ح 7921، 7922"` |
  | `"Bihar al-Anwar, v.94, p. 98, number:13"` | `"بحار الأنوار، ج 94، ص 98، ح 13"` |
  | `"Kanz al-Ummal number:43833"` (no comma) | `"كنز العمال، ح 43833"` |
  | `"Ibid."`, `"Mighty and Exalted"`, `"Quran 3:180"` | `None` |
  | a book missing from `books_ar` | `None` |
  `books_ar` in the test = `{"Al-Kafi": "الكافي", "Nahj al-Balagha": "نهج البلاغة", "Ghurar al-Hikam": "غرر الحكم", "Bihar al-Anwar": "بحار الأنوار", "Kanz al-Ummal": "كنز العمال"}`.
  Run: `python3 -m unittest discover -s scripts/ingest -p 'test_apply_v1_restore_translations.py'` → red (module missing).

- [ ] **Step 2 (Structured): Implement `citation_ar`**
  - Book = the longest key of `books_ar` that prefixes the citation. Remainder = the rest, with leading `,` or space stripped.
  - Remainder tokens are split on `,` (and on `.` when followed by a space and a known label). Each token matches exactly one rule; otherwise return `None`:
    `volume:N` | `v.N` | `volume:N.` → `ج N` · `p. N` → `ص N` · `number:N` | `number:N - M` → `ح N` / `ح N - M` · `nos. N[,;] M` | `nos. N - M` | `nos. N and M` → `ح N، M` / `ح N - M` · `Sermon N` → `الخطبة N` · `Letter N` → `الكتاب N` · `Saying N` → `الحكمة N` · `supplication N` (any case) → `الدعاء N` · bare `N` → `N`.
  - Join: `book_ar + "، " + "، ".join(parts)`. Digits stay Western (`LocaleDigits` converts at render, `LocaleDigits.java:12`).
  - Forbidden: emitting partial Arabic for a citation with an unparsed token.

- [ ] **Step 3 (TDD): green.** Same command → PASS.

- [ ] **Step 4 (Exact): Generate the worklists**
  ```bash
  cd /home/zir0/git/rewayaat-lost-books && python3 - <<'EOF'
  import json, glob, re
  docs = [json.loads(l)["_source"] for f in sorted(glob.glob("batches/v1-restored/batch_*.jsonl")) for l in open(f)]
  books = sorted({re.split(r",| number:", d["cited_from"], 1)[0].strip() for d in docs if d.get("cited_from")})
  json.dump(books, open("scripts/ingest/v1_restore_cited_books.json", "w"), ensure_ascii=False, indent=1)
  print(len(books))
  EOF
  ```
  Expected: ~163. Junk such as `Ibid.` is included in the list. The agent marks it untranslatable.

- [ ] **Step 5 — CHECKPOINT (ask the user):** "2 Sonnet agents: 220 titles, ~163 book names. ~10 min." Proceed only on a yes.

- [ ] **Step 6 (Exact): Agent prompts** (two `Agent` calls, `model: "sonnet"`, background)
  Titles agent:
  > You translate chapter and part titles of *Mīzān al-Ḥikma* (Rayshahrī; English: *The Scale of Wisdom*, ICAS Press) and one part of *Mishkāt al-Anwār* into the Arabic titles those books use. Read `/home/zir0/git/rewayaat-lost-books/scripts/ingest/v1_restore_untranslated.json` (a list of `[book, field, english]`). For context, read the Arabic titles of neighbouring chapters in `batches/v1-restored/*.jsonl` (same `book` and `part`); Rayshahrī's titles are short nominal phrases with full vowels, e.g. «عَدَمُ خُلُوِّ الدُّعاءِ مِنَ التَّأثيرِ». Write `/home/zir0/git/rewayaat-lost-books/scripts/ingest/v1_restore_translations.json` as `{"chapter": {"<english>": "<arabic>"}, "part": {"<english>": "<arabic>"}}`, with every english key present exactly as given. Do not translate word for word when Classical Arabic has a set term (Aashura → عاشوراء). Output only that file.

  Citations agent:
  > Read `/home/zir0/git/rewayaat-lost-books/scripts/ingest/v1_restore_cited_books.json`, the English names of hadith and reference books cited in *Mīzān al-Ḥikma*. Write `/home/zir0/git/rewayaat-lost-books/scripts/ingest/v1_restore_cited_books_ar.json` as `{"<english>": "<arabic title as the book is known in Arabic>"}` (Bihar al-Anwar → بحار الأنوار, Amali al-Saduq → أمالي الصدوق, Kanz al-Ummal → كنز العمال). For an entry that is not a book title (`Ibid.`, a sentence fragment, `Quran 3:180`), write `null`. Output only that file.

- [ ] **Step 7 (Structured): Review + apply**
  - Spot-check 20 titles and 20 book names by hand (the main model, not an agent). Fix the file in place.
  - Applier `apply_v1_restore_translations.py --es-url … --index rewayaat_hadith [--live]`:
    - Scroll the three books (filter as DEC-301).
    - Per doc, build a partial update with only the fields it lacks:
      - `chapter_ar` + `chapter_ar_source: "llm-translated"` if `chapter` ∈ translations.chapter
      - `part_ar` if `part` ∈ translations.part
      - `cited_from_ar` if `citation_ar(...)` is not None
    - Write with bulk `update` (`doc`), 500 per request, following `import_hadith_batches.py:203-226` for the bulk call.
    - Dry run by default, printing counts per field.
  - Run the dry run, then `--live`.
- [ ] **Step 8: Commit** the applier, its test and the three JSON files.

### Definition of Done
- [REQUIRED] `chapter_ar` present on 17,600 of the new docs. Query: count with `terms book` + `must_not exists chapter_ar` → `0`
- [REQUIRED] `cited_from_ar` on ≥ 4,560 of 4,654 Mizan docs (≥ 98%). The unparsed remainder is listed in `tmp/v1-restore/receipts/citations-unparsed.txt`
- [REQUIRED] unit test green

**Cleanliness self-check:** [ ] TDD red→green · [ ] dry run before `--live` · [ ] the bulk helper follows `import_hadith_batches.py`, not a new HTTP client

---

## Phase 4.2 — Topic tags

**Objective:** ≥ 97% of the new docs carry `topic_tags` from the taxonomy.

**Estimated budget:** 36 Sonnet batches. Measured: previous batches of 500.

### Steps

- [ ] **Step 1 (Exact): Export**
  `python3 scripts/tagging/export_for_tagging.py --es-url http://localhost:9200 --index rewayaat_hadith --books 'Ghurar al-Ḥikam|Mīzān al-Ḥikma|Mishkāt al-Anwār' --batch-size 500 --outdir tmp/v1-restore/tagging/input`
  Expected: 36 files.
- [ ] **Step 2 (Exact): Commit the prompt** — `docs/pipelines/topic-tagging.md`, containing the prompt below verbatim. The previous run's prompt is not in the repo (`grep -rli prompt scripts/tagging` → none).
  > Read `tmp/v1-restore/tagging/input/<batch>.json` and `src/main/resources/static/taxonomy.json`. For each hadith, choose the taggable slugs (`"taggable": true`) that name what the narration is *about*, judged by its English and Arabic text — usually 1 to 3, at most 6, and none when nothing fits. Never use a slug whose `taggable` is false, and never invent one. Write `tmp/v1-restore/tagging/output/tags_batch_<NNNN>.json` as `{"assignments": [{"id": "<id>", "tags": ["<slug>", ...]}]}`, with one entry per input hadith in input order. Output only that file.
- [ ] **Step 3 — CHECKPOINT (ask):** "36 Sonnet batches of 500, 7 concurrent."
- [ ] **Step 4:** Dispatch. Resume = the first input batch without an output file.
- [ ] **Step 5 (Exact): Validate + import**
  - Every output has the input's ids, and every slug is taggable. Write a 15-line check: load the taxonomy, compare id sets.
  - `python3 scripts/tagging/import_tags_to_es.py --es-url http://localhost:9200 --index rewayaat_hadith --indir tmp/v1-restore/tagging/output`

### Definition of Done
- [REQUIRED] `topic_tags` exists on ≥ 17,072 of 17,600 (97%)
- [ADVISORY] a hand review of 30 random tagged docs: tags plausible in ≥ 27

**Cleanliness self-check:** [ ] prompt committed · [ ] outputs validated before import

---

## Phase 4.3 — LLM similar hadith (top-10)

**Objective:** New docs carry `llm_similar` links (both directions) judged by the runbook's process.

**Estimated budget:** precompute ~20 min. Agents: measured at Step 3. Estimate 80–100K pairs, ~3,000 batches, ~6–8 h at the original run's pair rate (374,461 pairs / ~27 h).

### Steps

- [ ] **Step 1 (Exact): Precompute (new sources, whole-corpus candidates)**
  `LLM_SIMILAR_PREFILTER_TOP=10 python3 scripts/similar/llm_similar_hadith.py --books 'Ghurar al-Ḥikam|Mīzān al-Ḥikma|Mishkāt al-Anwār' --precompute 0 --output tmp/v1-restore/precomputed.jsonl`
  Expected: 17,600 lines. It is resumable (`llm_similar_hadith.py:409-420`).
- [ ] **Step 2 (Exact): Auto-accept wording matches into the shared cache**
  `python3 scripts/similar/llm_similar_hadith.py --build-cache tmp/v1-restore/precomputed.jsonl --cache tmp/pairs_cache.json`
- [ ] **Step 3 (Exact): Batches**
  `python3 scripts/similar/llm_similar_hadith.py --prepare-batches --precomputed tmp/v1-restore/precomputed.jsonl --cache tmp/pairs_cache.json --output-dir tmp/v1-restore/similar-batches --batch-size 3`
  Then `find tmp/v1-restore/similar-batches -size +45k | wc -l` → `0`. Otherwise re-run with `--batch-size 2`.
  Record the count of files and uncached pairs.
- [ ] **Step 4 — CHECKPOINT (ask):** "N batches, M pairs, ~H agent-hours at 7–8 concurrent." If N > 4,500 (1.5 × the estimate), offer the user fewer candidates (`PREFILTER_TOP=5`) first.
- [ ] **Step 5:** Run per [`docs/pipelines/llm-similar-hadith.md`](../docs/pipelines/llm-similar-hadith.md) "Steps 4-5" (prompt, spawn pattern, merge, resume), with `tmp/v1-restore/similar-batches` as the batch dir. Keep the main context small, per its "Context Window Conservation" section.
- [ ] **Step 6 (Exact): Load locally**
  `REWAYAAT_INDEX=rewayaat_hadith python3 scripts/similar/load_llm_similar_to_es.py --dry-run && REWAYAAT_INDEX=rewayaat_hadith python3 scripts/similar/load_llm_similar_to_es.py --live`
  The loader rebuilds every doc's adjacency from the cache (`docs/data-pipeline.md` Step 6), so old docs gain links to new ones.

### Definition of Done
- [REQUIRED] nested count of `llm_similar.id` over the three books > 0, recorded. Over all docs ≥ 25,273 (the pre-restore value, R6)
- [REQUIRED] `GET /v1/narrations/{id}/similar` on 3 random new ids with links returns them on the 8012 app (phase-3 launch command)
- [REQUIRED] `tmp/pairs_cache.json` key count ≥ the `.before` copy (nothing lost)

**Checkpoint contract:** batch files are the probe ids. Output presence = done. Merge is idempotent (`--merge` per file). The fingerprint is the cache sha256 before and after.

---

## Phase 4.4 — Qurʾānic connections (stages 1, 2, 3, 5)

**Objective:** New docs that have strong verse connections appear in `rewayaat_quranic_light_filtered`, highlighted. The 22,640 existing docs are untouched.

**Estimated budget:** stage 1 ~30–60 min. Stage 2 ~250–350 batches of 50. Stage 3 ≤ stage 2. Stage 5 ~60–90 batches of 100. Measured at each export.

### Steps

- [ ] **Step 1 (Structured): Scope audit** — read `export_quranic_light_for_filtering.py`, `apply_quranic_light_filter.py`, `generate_strict_judge_batches.py`, `apply_strict_judge_filter.py`, `generate_highlight_batches.py` and `apply_highlighting.py` in full. For each, record which index it reads and writes and whether it can delete or rebuild. A script that scrolls `rewayaat_quranic_light_filtered` without a hadith filter gets `QLFILTER_BOOKS` (terms on `hadith_book`, DEC-301 style + a unit test in `scripts/quranic-insights/test_book_scope.py`). Commit before Step 2.
- [ ] **Step 2 (Exact): Stage 1 into a staging index**
  ```bash
  REWAYAAT_INDEX=rewayaat_hadith QURANIC_LIGHT_INDEX=rewayaat_quranic_light_v1restore QURANIC_LIGHT_REBUILD_INDEX=true \
  QURANIC_LIGHT_BOOKS='Ghurar al-Ḥikam|Mīzān al-Ḥikma|Mishkāt al-Anwār' QURANIC_LIGHT_CHECKPOINT_FILE=tmp/v1-restore/qlight-build.checkpoint \
  python3 scripts/quranic-insights/build_quranic_light_index.py
  ```
  `QURANIC_LIGHT_INDEX` is mandatory. The default is the live filtered index (`:19`).
- [ ] **Step 3:** Export for judging: `QLFILTER_SOURCE_INDEX=rewayaat_quranic_light_v1restore QLFILTER_EXPORT_DIR=tmp/v1-restore/qlight-judge-inputs QLFILTER_EXPORT_BATCH_SIZE=50 python3 scripts/quranic-insights/export_quranic_light_for_filtering.py`. Count the files. **CHECKPOINT (ask).** Then run agents per `docs/pipelines/quranic-insights.md` stage 2, with outputs in `tmp/v1-restore/qlight-verdicts`.
- [ ] **Step 4:** Apply with `QLFILTER_SOURCE_INDEX=rewayaat_quranic_light_v1restore QLFILTER_TARGET_INDEX=rewayaat_quranic_light_filtered QLFILTER_REBUILD_INDEX=false QLFILTER_VERDICTS_DIR=tmp/v1-restore/qlight-verdicts`, dry run first (`QLFILTER_DRY_RUN=true`).
- [ ] **Step 5:** Stage 3 (strict re-judge) and stage 5 (highlighting) per the runbook, scoped by Step 1's filter. **CHECKPOINT (ask)** before each dispatch, with the measured batch count.
- [ ] **Step 6 (Exact): Verify**
  - `curl -s localhost:9200/rewayaat_quranic_light_filtered/_count` → 22,640 + N_new
  - a count with `terms hadith_book` [three books] → N_new
  - `curl -s localhost:9200/_cat/indices/rewayaat_quranic_light_v1restore` exists (keep it until phase-5 is done)
  - On the 8012 app, `GET /v1/narrations/{id}/quranic_insights` for 3 new ids returns verses

### Definition of Done
- [REQUIRED] existing docs untouched: the count of non-new `hadith_id` = 22,640 · N_new recorded
- [ADVISORY] N_new / 17,600 near the existing ratio 22,640 / 32,519 (70%). Ghurar's short sayings will land lower. Record the ratio.

**Cleanliness self-check:** [ ] staging index used · [ ] every dispatch was preceded by a checkpoint · [ ] scope filters unit-tested

---

## Maintainability summary
- Runbooks reused: `docs/pipelines/llm-similar-hadith.md`, `docs/pipelines/quranic-insights.md`. New: `docs/pipelines/topic-tagging.md` (the prompt that was missing).
- Importers reused: `import_tags_to_es.py`, `load_llm_similar_to_es.py`, `apply_quranic_light_filter.py`.

## NTH notes
- Qurʾānic stage 4 (excerpts) for the new books joins the existing 49% backlog.
- `chain_people` / narrator linking: Mishkāt has partial isnāds (`عن أبي عبد الله`). Ghurar and Mīzān have none. That belongs to the narrator pipeline on `feature/narrators` (`~/git/rewayaat-narrators`), which must not treat Ghurar or Mīzān as chain-bearing.
- Annotation pipeline (`english_annotated`): 0 loaded for the existing corpus, so nothing to do here.

## Risks
- Agent cost: similar-hadith dominates. The Step 3 measurement gates it.
- Shared `tmp/pairs_cache.json` corruption. Mitigation: backup in 4.0, the merge is per file.
- Tag quality without the original prompt. Mitigation: the advisory 30-doc review.
