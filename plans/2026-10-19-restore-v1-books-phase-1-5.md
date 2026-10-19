# Restore v1 Books — Phase 1.5: Text Quality

**Goal:** Repair the mechanical damage in the 17,600 restored narrations before anything is computed from them. Every repair is provably unable to change what a narration says. Records that cannot be repaired that way are flagged for a human decision.

**Architecture:** Two layers, both inside the phase-1 converter so the result can be rebuilt from the repo.
1. Deterministic cleaners for the defect classes that have an exact rule.
2. A committed fixes file holding proposals from Sonnet agents. The converter re-validates every fix against hard invariants on every run, then applies it.

Agents report and propose. They never write the data. Records whose problem is semantic (misaligned, truncated, merged) are never auto-fixed. They go to a review list where the user decides: keep, exclude, or hand-fix from the printed book.

**Working directory for all commands:** `/home/zir0/git/rewayaat-lost-books`

**Related plans:** [phase-1](2026-10-19-restore-v1-books-phase-1.md), phase-1.5 (this file), [phase-2](2026-10-19-restore-v1-books-phase-2.md), [phase-3](2026-10-19-restore-v1-books-phase-3.md), [phase-4](2026-10-19-restore-v1-books-phase-4.md), [phase-5](2026-10-19-restore-v1-books-phase-5.md)

**Depends on:** phase-1 (converter, overrides, batches). **Phase-3 now depends on phase-1.5.** Embeddings, tags and similar-hadith must be computed from the cleaned text.

**Maintainability expectations:** No second converter. The cleaners and the fix validator live in `scripts/ingest/convert_v1_books.py` beside the split rules, with unit tests in `test_convert_v1_books.py`. The agent prompts are committed in `docs/pipelines/v1-restore-quality.md`.

**Discovery evidence:** the deterministic audit (2026-10-19, below) and the sample pass (§ Sample results).

| Detector (script) | Ghurar | Mizan | Mishkat |
|---|---|---|---|
| Arabic: diacritic floating after a space | 30 | 10 | 981 |
| Arabic: >8% single-letter tokens (split words) | 26 | 172 | 4 |
| Arabic: Latin letters | 0 | 0 | 37 |
| Arabic: empty | 0 | 0 | 36 |
| English: double space / space before punctuation | 33 | 85 | 65 |
| English: no terminal punctuation (truncation or heading bleed) | 46 | 16 | 58 |
| EN/AR length ratio outside 0.6–6 | 0 | 8 | 9 |
| Citation without a locator | — | 16 | — |

**Decision and evidence manifest:**

| ID | Decision | Files/symbols | Source evidence | Reuse target | Blast-radius query + classified siblings | Acceptance proof | Risk | Invalidation conditions |
|---|---|---|---|---|---|---|---|---|
| DEC-151 | **Arabic skeleton invariant:** `skeleton(t)` = the sequence of letters U+0621–U+064A after removing diacritics U+064B–U+0652 / U+0670, tatweel U+0640, whitespace, Latin, digits and punctuation. Every Arabic change, by cleaner or fix, must leave the skeleton equal to the original's. The one exception: a whitelisted honorific inserted where a Latin or symbol glyph is removed | `skeleton()`, `validate_fix()` | user requirement: an agent "can repair broken words but cannot change what a narration says" | — | applies to `arabic` only. Titles go through the overrides file (phase-1.3 rules) | unit tests | high: matn integrity | — |
| DEC-152 | **English word invariant:** the English token sequence (split on whitespace, punctuation detached) must be equal before and after a change, except a trailing or leading span equal to a known part or chapter heading of the same book, which may be removed | `validate_fix()` | translations are the publisher's (Ansariyan, ICAS); typos are kept | — | — | unit tests | high | — |
| DEC-153 | Mishkāt Arabic: strip all diacritics | `clean_arabic()` | the source is unvocalized and its marks are OCR noise (`إّني`, `عبٍد`, `علّي`): audit 981 / 1,915 | — | Mishkāt only. Ghurar and Mizan are fully vocalized editions, so theirs are kept | skeleton test + count | standard | the sample shows meaningful shadda loss |
| DEC-154 | Deterministic joins: detached hamza `شَيْ ء` → `شَيْء`; isolated `أ` + space + `ن…` → joined (`أ نّه` → `أنّه`) | `clean_arabic()` | audit (split words: Ghurar 26, Mizan 172) | — | all three books | skeleton test | standard | — |
| DEC-155 | Agent fixes are auto-accepted only when valid (DEC-151/152), `old` occurs exactly once in the field, and confidence is `high` or `medium`. `low` → review list | `validate_fix()` | — | — | — | unit tests | standard | — |
| DEC-156 | Full agent pass per book only if the sample's rate of **agent-only** issues (`ocr-arabic` with a proposal, `latin-in-arabic`, `heading-bleed`, `misaligned`, `truncated-*`, `merged`, `title-mismatch`) is ≥ 2% (≥ 4 of 200). Below that, the deterministic cleaners + the sample's own findings suffice | Step 1.5.3 | the threshold balances cost (Ghurar is 111 batches) against an expected residue under 220 docs | — | — | § Sample results | standard | user overrides |
| DEC-157 | Chapter/part title pairs: a separate agent pass over the distinct `(book, part, chapter, chapter_ar)` pairs (~2,200). Mismatches → candidate entries in `v1_restore_title_overrides.json`, reviewed by the main model before commit | Step 1.5.4 | probe: `Speech and Speaking` ↔ `وَ فَضيلَةِ الإحْسانِ` | phase-1.3 overrides | — | review | standard | — |

**Execution policy:** Ask before every agent dispatch, giving the batch count (memory `feedback-checkpoint-token-usage`). All agents run on Sonnet (memory `use-sonnet-for-simple-jobs`). One commit per slice.

---

## Phase 1.5.1 — Deterministic cleaners and the fix validator

**Objective:** The converter cleans English spacing and heading bleed, Mishkāt diacritics and the known split words. It validates and applies a fixes file, refusing any fix that breaks DEC-151/152.

**Estimated budget:** 75 min.

**Files changed:** Modify `scripts/ingest/convert_v1_books.py`, `scripts/ingest/test_convert_v1_books.py`. Create `scripts/ingest/v1_restore_text_fixes.json` (`{}`) and `scripts/ingest/v1_restore_exclusions.json` (`{}`).

### Steps

- [ ] **Step 1 (TDD): Failing tests** (add to `test_convert_v1_books.py`):
  | Test | Input → expected |
  |---|---|
  | `test_skeleton_ignores_marks_spaces_latin` | `skeleton("قالَ  عليٌّ H")` == `skeleton("قال علي")` |
  | `test_mishkat_diacritics_stripped_skeleton_kept` | `clean_arabic("إّني عبٍد", "Mishkāt al-Anwār")` == `"إني عبد"` |
  | `test_vocalised_books_keep_their_marks` | `clean_arabic("بِرُّ الوالِدَيْنِ", "Ghurar al-Ḥikam")` unchanged |
  | `test_detached_hamza_joined` | `"أصْدَقُ شَيْ ء اَلأجَلُ"` → `"أصْدَقُ شَيْء اَلأجَلُ"` |
  | `test_split_anna_joined` | `"يَتَمَنّى المؤمنُ أ نّه لم"` → `"يَتَمَنّى المؤمنُ أنّه لم"` |
  | `test_english_spacing` | `"deeds , and  association"` → `"deeds, and association"` |
  | `test_heading_bleed_removed` | english `"…in the truth.” On Faith, Islam, and Related Issues"` with part `"Section 1 - On Faith, Islam and Related Issues"` → `"…in the truth.”"` |
  | `test_valid_arabic_fix_applies` | fix `{"field":"arabic","old":"أ  م ن","new":"أمَن"}` applied |
  | `test_fix_changing_a_letter_is_refused` | `{"old":"الجرب","new":"الحرب"}` → `ValueError` mentioning `skeleton` |
  | `test_honorific_for_glyph_is_allowed` | `{"old":"الحسين H","new":"الحسين عليه السلام"}` accepted |
  | `test_english_reword_is_refused` | `{"field":"english","old":"crippled","new":"disabled"}` → `ValueError` |
  | `test_fix_with_ambiguous_old_is_refused` | `old` occurring twice → `ValueError` |
  | `test_excluded_id_is_left_out_with_reason` | exclusions `{"<v1 id>": "misaligned"}` → absent from docs, present in `excluded` with that reason |
  Run: `python3 -m unittest discover -s scripts/ingest -p 'test_convert_v1_books.py'` → red (`AttributeError: … 'skeleton'`).

- [ ] **Step 2 (Structured): Implement**
  - `skeleton(text) -> str` per DEC-151.
  - `clean_arabic(text, book) -> str`, in order: strip diacritics if `book == "Mishkāt al-Anwār"`; join the detached hamza with `re.sub(r"(\S) ء(?=[\s،.:؛]|$)", r"\1ء", t)`; join `re.sub(r"(^|\s)أ (ن)", r"\1أ\2", t)`; collapse whitespace runs to one space (keep `\n`). Assert `skeleton(out) == skeleton(text)`, or raise.
  - `clean_english(text, headings) -> str`: collapse spaces; remove the space before `, . ; : ! ?`; strip a trailing or leading heading. A heading matches when the text's tail, lowercased with punctuation removed, equals any heading's text after its `Section N - ` / `CHAPTER N - ` prefix, normalised the same way. `headings` = the book's distinct part and chapter English titles.
  - `HONORIFICS = ["عليه السلام", "عليهم السلام", "عليها السلام", "صلى الله عليه وآله", "صلى الله عليه وآله وسلم"]`.
  - `validate_fix(field, original, old, new)`: `original.count(old) == 1`. For `arabic`: `skeleton(new)` equals `skeleton(old)` once removed Latin/symbols are accounted for, or `skeleton(new) == skeleton(old) + skeleton(h)` / honorific substitution for an `h` in `HONORIFICS` when `old` contains Latin or symbol characters. For `english`: the DEC-152 token test, or `new` equals `old` minus a heading. Raise `ValueError` naming the rule.
  - `convert(rows, overrides, fixes=None, exclusions=None)`: exclusions run before the fragment rule, with `reason` from the file. Cleaners run on every doc. Fixes run after the cleaners, keyed by the **new** id (`{"Mishkat-al-Anwar-Tabrisi:427": [{"field","old","new"}]}`). An invalid fix makes `convert` raise, so a bad fixes file cannot ship.
  - `main()`: new args `--fixes`, `--exclusions` (default: the two committed files).

- [ ] **Step 3 (TDD): green** — the same command → all tests pass (14 old + 13 new).
- [ ] **Step 4 (Exact): Re-run on the dump** — the phase-1.3 Step 1 command with the two new arguments. Expected: exit 0, `documents: 17600`. Then run the phase-1.4 Step 2 validator → `ok`. Re-run the audit detectors (§ Discovery) and record the new counts in `/mnt/share/rewayaat-backup/tmp/v1-restore/receipts/audit-after-cleaners.txt`. Expected: Mishkāt orphan diacritics → 0, English spacing → 0.
- [ ] **Step 5: Commit** — `git commit -m "feat(ingest): repair the restored text where a rule can, and refuse any fix that changes it"`

**Cleanliness self-check:** [ ] TDD red→green · [ ] the skeleton is asserted inside `clean_arabic`, not only in tests · [ ] no new module

---

## Phase 1.5.2 — Sample pass (done 2026-10-19)

3 Sonnet agents, 200 narrations per book, seed `20261019`. Input and output: `/mnt/share/rewayaat-backup/tmp/v1-restore/quality-sample/{ghurar,mizan,mishkat}.jsonl` → `out/`. The prompts are copied verbatim into `docs/pipelines/v1-restore-quality.md` in Step 1.5.3.

### Sample results

| | Ghurar | Mizan | Mishkāt |
|---|---|---|---|
| records with ≥ 1 issue | 29 / 200 (15%) | 62 / 200 (31%) | **176 / 200 (88%)** |
| mechanical (spacing, marks, split words, RLM, tatweel) | 22 | 50 | 23 |
| content: misaligned / truncated / merged | 3 (1.5%): 378 lacks «لا», 4374 starts mid-clause | 6 (3%): 2080, 2486, 3523 English from another narration; 809, 1768, 2179 English missing a clause | **28 (14%)**: 15 merged across page breaks, 12 truncated, 1 empty |
| OCR letter damage (not fixable under DEC-151) | 0 | 0 | **111 (56%)**: `فد` for `قد`, `فليئق` for `فليتق`, words interleaved out of order |
| headings pasted into text | 0 | 1 | 53 (Arabic 31, `chapter_ar` 18, English 4) |
| titles: `(Arabic)` disambiguation suffixes flagged; real mismatches | 6 suffixes; 1 mismatch (8540) | 1 (3743: narration text in `chapter`) | 3, plus truncated `part_ar` for sections 3, 4, 7 |
| citations | — | 6 garbled (`Ghurar al-Hikam,. number:2142`, `volume:23, p. 30, p. 46`); Arabic carries `(غرر الحکم: N)` pasted in (317, 342, 344) | — |

Validator replay over the 153 proposals: every pure spacing, mark or join edit kept the skeleton and was accepted. Every proposal that removed or replaced letters was refused: 31 Arabic heading removals, 17 glyph → honorific swaps, 8 English footnote-digit removals (`Qibla1`). The validator works. Four of those refused classes have an exact rule instead → DEC-158..161.

**Conclusion:** Ghurar and Mizan are repairable by rule plus agent proposals. **Mishkāt's Arabic is not.** Its damage is letter-level OCR substitution, word-order scrambling and text shifted across page breaks. DEC-151 forbids repairing that from inside the text. → DEC-162.

| ID | Decision | Evidence | Acceptance proof |
|---|---|---|---|
| DEC-158 | Cleaner: U+200F between two Arabic letters → a space; elsewhere it is removed. Tatweel inside a word is removed (a spaced `ـ` used as a dash stays) | Mizan: 36 of 42 agent fixes are exactly this (`الخَلائقِ‏إلَى`) | skeleton test |
| DEC-159 | Arabic heading removal is allowed when the removed span's skeleton equals the skeleton of one of the book's own `part_ar` / `chapter_ar` values, or a prefix of one ≥ 3 words | Mishkāt 31 refusals, e.g. `في الإيمان والإسلام وما يتعلق بهما` (= section 1 `part_ar`) | validator unit test |
| DEC-160 | Cleaners: Mishkāt English footnote digits glued to a word (`([A-Za-z”’)])\d{1,2}\b` → `\1`) and trailing footnote text after the closing quote (`\s\d\s+\w.*$` once the narration's closing `”` is found); Mizan Arabic citation parenthetical `\(غرر الح[كک]م: ?\d+\)` removed. It is a citation, the English omits it, and `cited_from` carries it | 8 + `410` + 317/342/344 | unit tests |
| DEC-161 | Glyph → honorific (`و:`, `H`, `I`, `0`, `4`, `1/`) is never auto-applied. It goes to review, batched for the main model: the right honorific depends on who is named | 17 proposals, some on `«` | review list |
| DEC-162 | **Mishkāt Arabic needs a clean source text.** Phase 1.6 (to be planned) does discovery for a digitised edition (OpenITI first, the corpus phase-6 narrator work re-extracted from), aligned to our English by narration order. Until then Mishkāt is either (a) imported with Arabic marked low-quality and excluded from embeddings and similar-hadith candidates, or (b) held back from the restore. **User decision** | the sample above | — |

---

## Phase 1.5.3 — Full agent pass (books meeting DEC-156)

**Objective:** Every narration of each qualifying book is reviewed. Valid fixes go into `v1_restore_text_fixes.json`. Semantic issues go to the review list.

**Estimated budget:** `ceil(N/100)` batches per qualifying book. By the sample, Ghurar (111) and Mizan (47) qualify under DEC-156. Mishkāt (20) waits on DEC-162. Run it only for whatever remains after the clean-source decision. 7 concurrent.

### Steps
- [ ] **Step 1 (Exact): Batches** — a 20-line script cuts the **cleaned** converter output (`batches/v1-restored/`, after 1.5.1) into `/mnt/share/rewayaat-backup/tmp/v1-restore/quality-full/<book>/batch_NNN.jsonl`, 100 lines each, with the sample's fields (incl. `prev_arabic_tail`, `next_arabic_head`).
- [ ] **Step 2 (Exact): Commit the prompts** — `docs/pipelines/v1-restore-quality.md`: the three sample prompts verbatim, with the input/output paths templated per batch and "4 chunks of 50" → "2 chunks of 50", plus the resume rule (first batch without an output file).
- [ ] **Step 3 — CHECKPOINT (ask):** "N batches across <books>, Sonnet, 7 concurrent."
- [ ] **Step 4:** Dispatch. Resume = the first batch without `out/batch_NNN.jsonl`.
- [ ] **Step 5 (Structured): Merge** — `scripts/ingest/merge_v1_quality.py` reads all outputs, then:
  - every issue with a `proposed` edit → `validate_fix`. Valid + `high`/`medium` → `v1_restore_text_fixes.json`. Invalid or `low` → `review.md` § Proposed but refused, with the reason.
  - `misaligned`, `truncated-*`, `merged`, `other`, `citation-defect` → `review.md` § Needs a decision, one row each: id, type, evidence, the EN and AR first 120 chars, the site link `/hadith/<id>` (local app).
  Write `review.md` to `/mnt/share/rewayaat-backup/tmp/v1-restore/quality-full/review.md`.

### Definition of Done
- [REQUIRED] every batch has an output (or a recorded failure retried once)
- [REQUIRED] the converter with the merged fixes exits 0 (all fixes valid by construction)

---

## Phase 1.5.4 — Title pairs

**Objective:** Every distinct chapter and part title pair is checked for EN↔AR agreement.

### Steps
- [ ] **Step 1 (Exact):** Extract the distinct `{book, field, english, arabic}` tuples where both sides are non-empty from the cleaned batches → `quality-titles/titles_NNN.jsonl`, 300 per file (~8 files).
- [ ] **Step 2 — CHECKPOINT (ask):** "~8 Sonnet batches."
- [ ] **Step 3:** The prompt (committed in the same runbook): for each tuple, write `{"english","arabic","verdict":"match|mismatch|partial","suggested_arabic": "<only when mismatch, taken from the Arabic of a neighbouring title or the narrations' own wording, else null>","note"}`. Write only non-`match` lines.
- [ ] **Step 4 (Structured):** The main model reviews every non-`match` line. Accepted ones become entries in `scripts/ingest/v1_restore_title_overrides.json` (keyed by the raw v1 title, per phase-1.3) or a `disambiguated` correction.

---

## Phase 1.5.5 — Decisions and rebuild

- [ ] **Step 1 — ASK the user** with `review.md` § Needs a decision (counts by type + the table). Per row: `keep` (no change) · `exclude` (→ `v1_restore_exclusions.json` with the reason) · `hand-fix` (the user supplies the text; it goes into the fixes file and must still pass the validator, or into the exclusions with `pending-hand-fix`).
- [ ] **Step 2 (Exact):** Re-run the converter, then the phase-1.4 Step 2 validator. The `17600` in its asserts becomes `17600 - len(exclusions)`. Regenerate `src/main/resources/legacy/v1_restored_ids.json` (excluded ids drop out of the redirect map) and `scripts/ingest/v1_restore_untranslated.json`.
- [ ] **Step 3: Commit** — the fixes, exclusions, overrides, regenerated batches and map, in one commit: `data: repaired text for the restored books, and the records left out`.

### Definition of Done
- [REQUIRED] converter exit 0 · validator `ok` · `review.md` has no undecided row
- [REQUIRED] the audit detectors re-run, before/after counts in the commit body
- [ADVISORY] a random sample of 30 applied fixes read by the main model: 30/30 are pure spacing, mark or glyph repairs

**Cleanliness self-check:** [ ] no fix bypasses `validate_fix` · [ ] every exclusion has a reason · [ ] phase-3 is started only after this commit

## Risks
- Mishkāt diacritic stripping also removes the few legitimate marks. Accepted (DEC-153). The book is unvocalized in print.
- The heading-bleed rule can strip a sentence that happens to equal a heading. Mitigated by matching only the book's own headings and only at the start or end of the text.
- Misaligned records found in the full pass change the document count. Phase-2's `CorpusScope.NARRATION_COUNT`, phase-3's counts and phase-5's counts all follow from the final converter output and must be re-derived, not copied from the plans.

---

## Outcome (2026-10-19) — later phases read their counts from here

Phases 1.1–1.4 and 1.5 are done for Ghurar and Mizan. **Mishkāt is held back** (`--hold-back "Mishkāt al-Anwār"`) until its Arabic has a clean source. OpenITI `0700AbuFadlCaliTabarsi.MishkatAnwar.Shia001383` aligns to 88% of v1 narrations at ≥ 0.6 4-gram containment: phase 1.6, to be planned.

| | Ghurar | Mizan | Total |
|---|---|---|---|
| imported | 11,012 | 4,612 | **15,624** |
| excluded (`v1_restore_exclusions.json`) | 19 | 42 | 61 |

- Exclusions: 28 English that belongs to no record · 15 cut mid-sentence · 6 meaning inverted · 11 repeated within their chapter · 1 fragment.
- Repairs: 700 agent text fixes (letter- and word-safe, re-validated each build) · 13 encoding/glyph repairs approved · 16 title spacing fixes · 20 English moves · 18 relabels · 24 speaker corrections (user-approved; the citation is Ghurar or Nahj, so Imam Ali) · 300 reader retitles (`chapter_ar_source: rewayaat-review`) · 234 particle-spacing proposals dropped as the edition's convention.
- Kept as is, by decision: 84 letter-level Arabic errors, 136 English wording slips, 45 one-sided omissions.
- 221 titles still without Arabic (220 chapters, 1 part): phase-4 4.1 input `scripts/ingest/v1_restore_untranslated.json`.

**Counts for phases 2–5:** `CorpusScope.BOOK_COUNT = 20`, `NARRATION_COUNT = 48143` (32,519 + 15,624), 20 books in the scope sentence (no Mishkāt). Every "17,600", "21 books" and "50,119" in phases 2–5 reads as these until Mishkāt lands. Vectors expected: 32,516 + 15,624 (no new empty-Arabic docs).
