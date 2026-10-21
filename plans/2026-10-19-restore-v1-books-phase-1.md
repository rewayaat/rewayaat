# Restore v1 Books — Phase 1: Source Preservation and Conversion

**Goal:** Turn the v1 snapshot's Ghurar al-Ḥikam, Mīzān al-Ḥikma and Mishkāt al-Anwār into committed import batches, a v1-id map and a translation worklist, without writing to any Elasticsearch.

**Architecture:** One offline converter (`scripts/ingest/convert_v1_books.py`) reads the v1 JSONL dump and writes `{"_id","_source"}` batches in the shape `scripts/ingest/import_hadith_batches.py:188-200` already reads. The rules are deterministic. A small committed overrides file settles the 21 titles no rule can settle. The converter exits 1 while any of them is unsettled.

**Working directory for all commands:** `/home/zir0/git/rewayaat-lost-books` (worktree, branch `feature/restore-v1-books`, based on `origin/master` `2c76714`)

**Related plans:** phase-1 (this file), [phase-1.5](2026-10-19-restore-v1-books-phase-1-5.md) text quality, [phase-2](2026-10-19-restore-v1-books-phase-2.md) app code, [phase-3](2026-10-19-restore-v1-books-phase-3.md) local import + deterministic enrichment, [phase-4](2026-10-19-restore-v1-books-phase-4.md) agent enrichment, [phase-5](2026-10-19-restore-v1-books-phase-5.md) production rollout.

**Depends on:** nothing.

**Maintainability expectations:**
- Reuse existing components, utilities and constants wherever they fit. No parallel implementations.
- Batch shape, 200 per file and the `manifest.json` style follow `batches/manifest.json`.
- Script conventions follow `scripts/README.md`: run from the repo root, large intermediates go to `/mnt/share/rewayaat-backup/tmp/`.
- No hardcoded strings or paths that belong in config. Every change leaves the codebase equally clean or cleaner.

**Discovery evidence:** [`plans/2026-10-19-restore-v1-books/discovery-receipt.md`](2026-10-19-restore-v1-books/discovery-receipt.md) R1–R5. The converter and its tests in that directory ran against the real dump (R5).

**Decision and evidence manifest:**

| ID | Decision | Files/symbols | Source evidence | Reuse target | Blast-radius query + classified siblings | Acceptance proof | Risk | Invalidation conditions |
|---|---|---|---|---|---|---|---|---|
| DEC-101 | Book names, Arabic names, slugs, id prefixes, compiler `source`/`source_ar`, `publisher`/`publisher_ar` exactly as `BOOKS` in `convert_v1_books.py` | converter `BOOKS` | R3. Existing name style `Kitāb al-Ḍuʿafāʾ` (live agg). Slugs via `Slugs.java:31-54`: `ghurar-al-hikam`, `mizan-al-hikma`, `mishkat-al-anwar`. Arabic names from `static/book_blurbs.json` headings | Thaqalayn naming in `batches/*.jsonl`, id style `Al-Khisal-Saduq:1204` | `grep -c '"book"' batches/v1-restored/batch_*.jsonl`. Names reused verbatim by phase-2 (`CorpusScope`, `book_names_mapping.json`, blurbs, summaries) | `test_ids_fields_and_v1_map` | high: public URL + MCP contract | name/slug change after phase-5 = URL break |
| DEC-102 | id = `{id_prefix}:{v1 number}`. Unique per book after the fragment exclusion | `convert()` | R4 | `bookId:hadithNumber` (`docs/data-pipeline.md` Import) | `convert()` raises on a duplicate | probe: 17,600 ids, 0 duplicates | high | v1 dump changes |
| DEC-103 | Exclude Mishkat docs whose number collides AND whose Arabic is empty (23 list fragments) | `convert()` | R4 | — | `excluded.json`: 23 rows, all `list-fragment` | `test_mishkat_fragment_…` | standard | user wants fragments kept |
| DEC-104 | v1 `section` → `part` (Mizan, Mishkat). Ghurar has no part. `volume="1"` for all. No `section` field | `convert()` | Every live doc has `volume` (R6 agg). `BookCatalog.java:517-524` composite sorts `section` as a string, so an ordinal would sort 1, 10, 100 | Kitāb al-Muʾmin (1 part) / flat-book path `BookPageController.java:109-133` | Book page level choice `BookPageController.java:111-116` | phase-3 page probe | standard | catalogue gains a reading-order key |
| DEC-105 | Title split + ALL-CAPS title case + `/`-split for Mishkat. Same English with two Arabic titles in one (book, part) → `"{en} ({ar})"` | `split_bilingual`, `title_case`, disambiguation block | R5: 25 titles, 387 docs | — | `anomalies.json.disambiguated` | `test_same_english_…` | standard | — |
| DEC-106 | 21 blocking titles settled by committed `scripts/ingest/v1_restore_title_overrides.json`. `{"inherit":"previous"}` for narration text in a title. `{"english","arabic"}` otherwise | converter `--overrides` | R5 | — | the converter exits 1 until 0 remain | Step 6 | standard | re-dump |
| DEC-107 | Drop v1 `history`, `tags`, `gradings`, `related` | `convert()` | R4: `history` carries editor e-mails, and it is public via `HadithObject.java:78-105` → API | — | `grep -c history batches/v1-restored/*.jsonl` → 0 | `test_ids_fields_…` `assertNotIn("history")` | high (privacy) | — |
| DEC-108 | Mizan v1 `source` → new field `cited_from` (user decision 2026-10-19). `source` = compiler | `convert()` | user answer. `source` semantics R6 | `source`/`source_ar` mapping shape (`scripts/search/v2_mapping.json:321-340`) | phase-2 adds mapping + card row | `test_mizan_section_…` | high: new field | user reverses |
| DEC-109 | v1-id map committed at `src/main/resources/legacy/v1_restored_ids.json` (17,600 entries) for the 301 (phase-2) | report `v1_ids.json` | user decision 2026-10-19. Memory `legacy-id-urls-are-dead` | — | 1 GSC URL known (`30611`) | Step 8 count | standard | — |
| DEC-110 | Batches committed at `batches/v1-restored/` | output dir | existing `batches/` is committed (164 files, 61 MB) | `batches/manifest.json` | `import_hadith_batches.py --batch-path` takes a dir | Step 8 | standard | repo-size objection |

**Execution policy:** Work only in the worktree. Never touch `~/git/rewayaat` (R7). One commit per phase-1 slice. No push and no PR until phase-5. No sub-agents in phase-1.

---

## Phase 1.1 — Preserve the source

**Objective:** The v1 snapshot copy and its dump live on durable disk, not in the session scratchpad. Non-behavioural: a file copy. No TDD.

**Estimated budget:** 10 min, ~5 tool calls.

**Problem:** The dump and the repo copy sit in `/tmp/claude-1000/.../scratchpad/` (R2). They are lost when the session ends.

**Files changed:** none in the repo.

### Steps

- [ ] **Step 1 (Exact): Copy to the backup disk**

  ```bash
  SCR=/tmp/claude-1000/-home-zir0-git-rewayaat/acbff938-2a53-487f-89e7-c7536788645d/scratchpad
  DEST=/mnt/share/rewayaat-backup/v1-snapshot
  mkdir -p $DEST
  test -d $SCR/v1repo && cp -a $SCR/v1repo $DEST/rewayaat-data-repo || s3cmd sync --no-progress s3://rewayaat-data/ $DEST/rewayaat-data-repo/
  test -f $SCR/v1_2025-02-20.jsonl && cp $SCR/v1_2025-02-20.jsonl $DEST/
  sha256sum $DEST/v1_2025-02-20.jsonl > $DEST/v1_2025-02-20.jsonl.sha256
  ```

  If the scratchpad is gone, recreate the dump with receipt R2 steps 2–6 against `$DEST/rewayaat-data-repo`. Write it to `$DEST/v1_2025-02-20.jsonl`, then `docker rm -f v1-snapshot-probe`.

### Definition of Done
- [REQUIRED] `wc -l /mnt/share/rewayaat-backup/v1-snapshot/v1_2025-02-20.jsonl` → `36019`
- [REQUIRED] `du -sh /mnt/share/rewayaat-backup/v1-snapshot/rewayaat-data-repo` → ~200M
- [REQUIRED] `docker ps -a --format '{{.Names}}' | grep -c v1-snapshot-probe` → `0`

**Cleanliness self-check:**
- [ ] TDD skipped: non-behavioural copy (stated above)
- [ ] No repo files touched

---

## Phase 1.2 — Converter and its tests

**Objective:** `scripts/ingest/convert_v1_books.py` converts the dump by the DEC-101..108 rules, with 14 passing unit tests.

**Estimated budget:** 30 min, ~10 tool calls.

**Problem:** No path from the v1 shape (`book: "Ghurar Al-Hikam"`, bilingual `chapter`, numeric `_id`) to the v2 shape (`batches/batch_00001.jsonl`: `{"_id": "Al-Amali-Saduq:1", "_source": {...}}`).

**Files changed:**
- Create: `scripts/ingest/convert_v1_books.py`
- Test: `scripts/ingest/test_convert_v1_books.py`
- Modify: `scripts/README.md` (`ingest/` table, one row)

**Parallel safety:** independent write set.

### Steps

- [ ] **Step 1 (TDD): Write the failing test**

  Copy verbatim: `cp plans/2026-10-19-restore-v1-books/test_convert_v1_books.py scripts/ingest/`
  (14 tests: split, title case, ids/fields/v1 map, Mizan part + `cited_from`, Mishkat fragment exclusion, disambiguation, blocking + `inherit`, untranslated non-blocking, foreign books ignored.)

- [ ] **Step 2 (TDD): Run — confirm it fails for the right reason**

  Run: `cd /home/zir0/git/rewayaat-lost-books && python3 -m unittest discover -s scripts/ingest -p 'test_convert_v1_books.py'`
  Expected: `ModuleNotFoundError: No module named 'convert_v1_books'`

- [ ] **Step 3 (Exact): Add the converter**

  Copy verbatim: `cp plans/2026-10-19-restore-v1-books/convert_v1_books.py scripts/ingest/ && chmod +x scripts/ingest/convert_v1_books.py`
  Contract pinned by that file:
  - Output `_source` keys only: `book, book_ar, number, volume, chapter, chapter_ar?, chapter_ar_source?, part?, part_ar?, arabic, english, source, source_ar, publisher?, publisher_ar?, cited_from?`
  - `chapter_ar_source = "rewayaat-v1"` whenever `chapter_ar` comes from v1.
  - `<br />` in the body becomes `\n`. The body text is otherwise untouched.
  - Exit code 1 while `anomalies.json.blocking` is non-empty.
  - Forbidden: fuzzy parent-merging of fragments, guessing chapter titles from the matn, keeping `history`.

- [ ] **Step 4 (TDD): Run — confirm it passes**

  Run: `cd /home/zir0/git/rewayaat-lost-books && python3 -m unittest discover -s scripts/ingest -p 'test_convert_v1_books.py'`
  Expected: `Ran 14 tests` … `OK`

- [ ] **Step 5 (Intent): Document it**

  Add a row to the `ingest/` table in `scripts/README.md`, after `export_hadith_batches.py`:
  `| \`convert_v1_books.py\` | Convert the three books the v2 rebuild lost from the v1 snapshot dump into \`batches/v1-restored/\` |`

- [ ] **Step 6: Commit**

  ```bash
  cd /home/zir0/git/rewayaat-lost-books && git add scripts/ingest/convert_v1_books.py scripts/ingest/test_convert_v1_books.py scripts/README.md && git commit -m "feat(ingest): convert the books the v2 rebuild lost from the v1 snapshot"
  ```

### Definition of Done
- [REQUIRED] unittest command → `OK`, 14 tests
- [REQUIRED] `grep -n "history" scripts/ingest/convert_v1_books.py` → 0 matches

**Verification pyramid:** edit loop = unittest. Slice gate = the same. Milestone gate = Phase 1.3 probe.

**Cleanliness self-check:**
- [ ] TDD followed: red on `ModuleNotFoundError`, green on 14
- [ ] No duplicated logic (no slug or title helper exists in `scripts/`: `grep -rn "def title_case\|def split_bilingual" scripts` → only the new file)
- [ ] Naming matches `scripts/ingest/*` (snake_case, argparse, `main() -> int`)
- [ ] No dead code, prints only the final summary line

---

## Phase 1.3 — Settle the 21 blocking titles

**Objective:** The converter exits 0 on the real dump. Non-behavioural: a data file. Its acceptance is the converter exit code.

**Estimated budget:** 45 min, ~10 tool calls. Judgement work on 21 strings.

**Problem:** R5: 21 blocking titles. 9 are narration text in the title field, 5 are legitimate titles over 100 characters, 7 are doubled or mixed bilingual titles.

**Files changed:**
- Create: `scripts/ingest/v1_restore_title_overrides.json`

### Steps

- [ ] **Step 1 (Exact): First run with empty overrides**

  ```bash
  cd /home/zir0/git/rewayaat-lost-books
  echo '{"chapter": {}, "part": {}}' > scripts/ingest/v1_restore_title_overrides.json
  python3 scripts/ingest/convert_v1_books.py --dump /mnt/share/rewayaat-backup/v1-snapshot/v1_2025-02-20.jsonl \
    --overrides scripts/ingest/v1_restore_title_overrides.json --out-dir batches/v1-restored \
    --report-dir /mnt/share/rewayaat-backup/tmp/v1-restore/report; echo "exit=$?"
  ```
  Expected: `{"documents": 17600, "excluded": 23, "blocking": 21, "untranslated": 219, "disambiguated": 387}` and `exit=1`

- [ ] **Step 2 (Structured): Write one override per blocking row**

  Source: `/mnt/share/rewayaat-backup/tmp/v1-restore/report/anomalies.json` → `blocking[]`. Key = `raw` exactly as printed, under `field` (`chapter` or `part`).
  Rules, applied in this order:
  1. `raw` is narration text (starts with a speaker such as `الإمامُ`, `فقالَ`, `وهُوَ`, `فلَمّا`, `71ـ`, contains `(عَلَيهِ الّسَلامُ)`, or is an English narration sentence) → `{"inherit": "previous"}`.
  2. `raw` repeats one bilingual pair (`Experience بَرَكاتُ التَّجرِبَةِ EXPERIENCE التَّجرِبَة`, `Intimacy الاُنسِ INTIMACY الاُنس`, `Aid - The Festival العيدُ THE FESTIVAL العيدُ AID`) → the first pair: `{"english": "Experience", "arabic": "بَرَكاتُ التَّجرِبَةِ"}`.
  3. `raw` joins two titles (`Innate Knowledge المَعرِفَةُ الفِطرَيةُ Affirmation of …`, `Spiritual Purity الطَّهارَةُ المَعنَوِيَّةُ Purifying Agents: Fire`, `Things that Expiate Sins: Punishment … مُكَفِّراتُ …`, `Wronging The Worker ظُلمُ الأجيرِ محمّدَ…`) → the first English and its Arabic only. When the Arabic runs into a narration, cut it before the narration starts.
  4. A legitimate title over 100 characters (`too-long` only) → `{"english": <split English>, "arabic": <split Arabic>}` unchanged. This accepts it.
  5. Mishkat `SECTION 9 - ON SOME ADVICE 1906- …` (part) → `{"english": "Section 9 - On Some Advice", "arabic": ""}`. Its Arabic is filled in phase-4 with the other untranslated titles.
  6. Mishkat `CHAPTER 13 - On Enjoining the Right…` → `{"english": "CHAPTER 13 - On Enjoining the Right and Forbidding the Wrong", "arabic": <split Arabic with the Latin characters removed>}`.
  Forbidden: inventing an Arabic title that is not in the raw string.

- [ ] **Step 3 (Exact): Re-run until clean**

  Same command as Step 1. Expected: `"blocking": 0`, `exit=0`, `"documents": 17600`. `untranslated` is 219 + 1 = 220: the Mishkat Section 9 Arabic is now deferred.

- [ ] **Step 4: Commit**

  ```bash
  cd /home/zir0/git/rewayaat-lost-books && git add scripts/ingest/v1_restore_title_overrides.json && git commit -m "data(ingest): settle the v1 titles the split rules cannot"
  ```
  Kept in `scripts/ingest/`, not `scripts/data/`: `.gitignore:12` (`*/data/*`) ignores `scripts/data/`, and the conversion must be reproducible from the repo. Check: `git check-ignore -v scripts/ingest/v1_restore_title_overrides.json` → exit 1.

### Definition of Done
- [REQUIRED] converter exit 0, `blocking: 0`, `documents: 17600`, `excluded: 23`
- [REQUIRED] `git ls-files scripts/ingest/v1_restore_title_overrides.json` → 1 line

**Cleanliness self-check:**
- [ ] TDD skipped: data-only slice. Acceptance = converter exit code
- [ ] Every override traces to a `blocking[]` row (`python3 -c` count of keys = 21)

---

## Phase 1.4 — Commit batches, id map, worklist

**Objective:** The batches, the legacy id map and the phase-4 translation worklist are committed and validated.

**Estimated budget:** 20 min, ~8 tool calls.

**Files changed:**
- Create: `batches/v1-restored/batch_00001.jsonl … batch_00088.jsonl`, `batches/v1-restored/manifest.json`
- Create: `src/main/resources/legacy/v1_restored_ids.json`
- Create: `scripts/ingest/v1_restore_untranslated.json` (copy of `anomalies.json.untranslated`, the phase-4 input)

### Steps

- [ ] **Step 1 (Exact): Copy the map and the worklist into the repo**

  ```bash
  cd /home/zir0/git/rewayaat-lost-books
  mkdir -p src/main/resources/legacy
  python3 -c 'import json,sys; m=json.load(open(sys.argv[1])); json.dump(dict(sorted(m.items())), open(sys.argv[2],"w"), ensure_ascii=False, indent=0, sort_keys=True)' \
    /mnt/share/rewayaat-backup/tmp/v1-restore/report/v1_ids.json src/main/resources/legacy/v1_restored_ids.json
  python3 -c 'import json,sys; json.dump(json.load(open(sys.argv[1]))["untranslated"], open(sys.argv[2],"w"), ensure_ascii=False, indent=1)' \
    /mnt/share/rewayaat-backup/tmp/v1-restore/report/anomalies.json scripts/ingest/v1_restore_untranslated.json
  ```

- [ ] **Step 2 (Exact): Validate the batches**

  ```bash
  cd /home/zir0/git/rewayaat-lost-books && python3 - <<'EOF'
  import json, glob, collections
  docs = [json.loads(l) for f in sorted(glob.glob("batches/v1-restored/batch_*.jsonl")) for l in open(f)]
  ids = [d["_id"] for d in docs]
  assert len(ids) == 17600 == len(set(ids)), len(ids)
  books = collections.Counter(d["_source"]["book"] for d in docs)
  assert books == {"Ghurar al-Ḥikam": 11031, "Mīzān al-Ḥikma": 4654, "Mishkāt al-Anwār": 1915}, books
  allowed = {"book","book_ar","number","volume","chapter","chapter_ar","chapter_ar_source","part","part_ar",
             "arabic","english","source","source_ar","publisher","publisher_ar","cited_from"}
  assert all(set(d["_source"]) <= allowed for d in docs)
  assert all(d["_source"]["chapter"] for d in docs)
  assert sum("cited_from" in d["_source"] for d in docs) == 4654
  live = {"Al-Kafi","Man-La","Nahj","Al-Amali","Al-Khisal"}  # prefixes in use today
  assert not any(i.split(":")[0].startswith(tuple(live)) for i in ids)
  m = json.load(open("src/main/resources/legacy/v1_restored_ids.json"))
  assert len(m) == 17600 and m["30611"].startswith("Ghurar-al-Hikam-Amidi:")
  print("ok", books)
  EOF
  ```
  Expected: `ok Counter({...})`

- [ ] **Step 3: Commit**

  ```bash
  cd /home/zir0/git/rewayaat-lost-books && git add batches/v1-restored src/main/resources/legacy/v1_restored_ids.json scripts/ingest/v1_restore_untranslated.json && git commit -m "data: the three v1 books as import batches, with their legacy id map"
  ```

### Definition of Done
- [REQUIRED] the Step 2 script prints `ok`
- [REQUIRED] `ls batches/v1-restored/batch_*.jsonl | wc -l` → `88`
- [REQUIRED] `grep -l '@gmail.com' batches/v1-restored/*.jsonl | wc -l` → `0`

**Checkpoint contract:** the converter is idempotent. It deletes stale `batch_*.jsonl` (`write_outputs`) and is re-runnable after any override change. Fingerprint = sha256 of the dump (Phase 1.1) + the overrides file commit.

**Cleanliness self-check:**
- [ ] TDD skipped: generated artifacts. Validation script = acceptance
- [ ] No hand edits to generated batch files

---

## Maintainability summary
- Batch shape and loader: `scripts/ingest/import_hadith_batches.py:188-212` (unchanged).
- Manifest style: `batches/manifest.json`.
- Id scheme: `docs/data-pipeline.md` "Import" (`bookId:hadithNumber`).

## NTH notes
- `BookCatalog` orders chapters by the composite key (`BookCatalog.java:517-524`), so parts and chapters sort as strings in every book (Mishkat `Section 10` before `Section 2`, Nahj parts alphabetical). This defect is older than this plan. Fixing it needs a reading-order key.
- The Mishkat Arabic has OCR damage (`َشَلٍل`, broken spacing, R4 sample). The converter does not repair it. Arabic search recall on Mishkat will be lower.
- v1 `_id`s of the other 8 books have no exact map. They stay dead (memory `legacy-id-urls-are-dead`).

## Risks
- Mishkat's 36 empty-Arabic narrations get no embedding (`generate_hadith_embeddings.py` skips empty matn, the same as the existing 3) and no Arabic search.
- The 25 disambiguated titles show Arabic inside the English title on the English site, e.g. `Generosity (الجود)`.
- Name and slug choices (DEC-101) become permanent URLs at phase-5.
