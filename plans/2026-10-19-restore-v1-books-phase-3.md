# Restore v1 Books — Phase 3: Tooling, Local Import, Deterministic Enrichment

**Goal:** Local ES holds the 17,600 restored narrations with every field a script can compute (mapping, `_ar` fields, chain-free text, significant terms, embeddings). The phase-2 app is verified against them on both sites.

**Architecture:** First the offline tools learn to work on a subset (`book` filter) and to target the alias. Then the batches go into the live local alias `rewayaat_hadith` (→ `rewayaat_hadith_20260909`), so candidate retrieval in phase-4 sees the whole corpus. Existing documents are never rewritten in this phase.

**Working directory for all commands:** `/home/zir0/git/rewayaat-lost-books`

**Related plans:** [phase-1](2026-10-19-restore-v1-books-phase-1.md), [phase-2](2026-10-19-restore-v1-books-phase-2.md), phase-3 (this file), [phase-4](2026-10-19-restore-v1-books-phase-4.md), [phase-5](2026-10-19-restore-v1-books-phase-5.md)

**Depends on:** phase-1 (batches), phase-1.5 (cleaned batches — embeddings must be computed from repaired text), phase-2 (app code + `v2_mapping.json` fields).

**Maintainability expectations:**
- Extend the existing tools; no forks (memory `reuse-existing-query-paths`).
- Env-var configuration in the style already used by each tool (`readString(...)` in the Java tools, `os.environ.get` / argparse in the Python ones).
- Large outputs go to `/mnt/share/rewayaat-backup/tmp/v1-restore/`.

**Discovery evidence:** [`discovery-receipt.md`](2026-10-19-restore-v1-books/discovery-receipt.md) R5, R6.

**Decision and evidence manifest:**

| ID | Decision | Files/symbols | Source evidence | Reuse target | Blast-radius query + classified siblings | Acceptance proof | Risk | Invalidation conditions |
|---|---|---|---|---|---|---|---|---|
| DEC-301 | A `|`-separated book filter for the 2 Java backfill tools (`SEMANTIC_BACKFILL_BOOKS`) and 3 Python scripts (`--books` / `QURANIC_LIGHT_BOOKS`). Unset = today's `match_all` | `SemanticMatnSourceBackfillTool.java:82`, `SemanticSignificantTermsBackfillTool.java:169`, `export_for_tagging.py:27-31`, `llm_similar_hadith.py:375`, `build_quranic_light_index.py:259` | each scrolls `match_all` (lines cited) and would rewrite all 32,519 existing docs | env/argparse style of each file | `grep -rn '"match_all"' scripts/{tagging,similar,quranic-insights} src/main/java/com/rewayaat/tools` → classify each: only the 5 above feed this plan | unit tests 3.1 | high: an unfiltered run rewrites existing docs and drifts embeddings | — |
| DEC-302 | Script index defaults → the alias `rewayaat_hadith` | `load_llm_similar_to_es.py:31` (hardcoded `rewayaat_updated`), `llm_similar_hadith.py:35`, `generate_hadith_embeddings.py:268`, `build_quranic_light_index.py:16` (`rewayaat_thaqalayn`) | memory `prod-serves-rewayaat-hadith-alias`: writing to `rewayaat_updated` succeeds locally and is invisible. Prod has no `rewayaat_updated` (`docs/search-index-migration.md`) | `REWAYAAT_INDEX` env convention | `grep -rn "rewayaat_updated\|rewayaat_thaqalayn" scripts` → fix only the 4 used here. List the rest as NTH | 3.1 test + `--help` | high | — |
| DEC-303 | `LLM_SIMILAR_PREFILTER_TOP` env (default 30). Phase-4 sets 10 (user decision) | `llm_similar_hadith.py:41,434` | user answer 2026-10-19 | env style `:38` | the one constant | 3.1 test | standard | — |
| DEC-304 | Embeddings on CPU (`CUDA_VISIBLE_DEVICES=""`), batch 16, the existing fine-tuned model | `generate_hadith_embeddings.py` | R6: GT 1030 2 GB | `--skip-existing` default `:279` | only docs without `semantic_vector` | count DoD | standard | GPU change |
| DEC-305 | The Ghurar book page renders flat if its HTML is ≤ the largest existing book page. Otherwise stop and report (letter parts would be a new plan) | `BookPageController.java:109-133` | user decision "flat, gated on size". `BookPageController.java:104-107` (1.35 MB lesson) | — | — | 3.4 Step 4 | standard | — |
| DEC-306 | The verification app runs from the worktree on 8012/8013. `scripts/ops/restart.sh` is never used | — | `restart.sh:31-35` kills every `mvn spring-boot:run` on the machine, other sessions' too | `restart.sh:40` launch line | — | — | high (shared machine) | — |

**Execution policy:** One commit per slice that changes code. Data steps (3.3–3.4) produce receipts in `/mnt/share/rewayaat-backup/tmp/v1-restore/receipts/` and no commits. Local ES only. No production access in phase-3.

---

## Phase 3.1 — Subset and alias support in the tools

**Objective:** Every tool phase-3/4 runs can be limited to the three books and targets the alias by default. Unset filters behave exactly as today.

**Estimated budget:** 75 min.

**Files changed:**
- Create: `src/main/java/com/rewayaat/tools/BackfillScope.java`, test `src/test/java/com/rewayaat/tools/BackfillScopeTest.java`
- Modify: `src/main/java/com/rewayaat/tools/SemanticMatnSourceBackfillTool.java:82`, `SemanticSignificantTermsBackfillTool.java:169`
- Modify: `scripts/tagging/export_for_tagging.py`, `scripts/similar/llm_similar_hadith.py`, `scripts/similar/load_llm_similar_to_es.py:31`, `scripts/embeddings/generate_hadith_embeddings.py:268`, `scripts/quranic-insights/build_quranic_light_index.py:16,259`
- Test: `scripts/similar/test_book_scope.py` (create)

**Parallel safety:** independent of phase-2 files.

### Steps

- [ ] **Step 1 (TDD): Java failing test (Exact)**
  ```java
  package com.rewayaat.tools;

  import com.fasterxml.jackson.databind.ObjectMapper;
  import org.junit.jupiter.api.Test;
  import static org.junit.jupiter.api.Assertions.assertEquals;

  class BackfillScopeTest {

      private static final ObjectMapper MAPPER = new ObjectMapper();

      @Test
      void unsetMeansTheWholeIndex() {
          assertEquals("{\"match_all\":{}}", BackfillScope.query(MAPPER, null).toString());
          assertEquals("{\"match_all\":{}}", BackfillScope.query(MAPPER, " ").toString());
      }

      @Test
      void aListOfBooksLimitsTheScroll() {
          assertEquals("{\"terms\":{\"book\":[\"Ghurar al-Ḥikam\",\"Mīzān al-Ḥikma\"]}}",
                  BackfillScope.query(MAPPER, "Ghurar al-Ḥikam | Mīzān al-Ḥikma").toString());
      }
  }
  ```
  Run: `mvn -B -q test -Dtest=BackfillScopeTest` → red: `cannot find symbol BackfillScope`.

- [ ] **Step 2 (Exact): Implement**
  ```java
  package com.rewayaat.tools;

  import com.fasterxml.jackson.databind.ObjectMapper;
  import com.fasterxml.jackson.databind.node.ArrayNode;
  import com.fasterxml.jackson.databind.node.ObjectNode;

  /**
   * Which narrations a backfill tool scrolls.
   *
   * <p>The tools were written for a whole-corpus pass and scroll {@code match_all}. Adding
   * books to a finished corpus needs the same pass over the new books only: rerunning it
   * over everything rewrites fields the embeddings were computed from. Set
   * {@code SEMANTIC_BACKFILL_BOOKS} to a {@code |}-separated list of {@code book} values.
   */
  final class BackfillScope {

      static final String ENV = "SEMANTIC_BACKFILL_BOOKS";

      private BackfillScope() {
      }

      static ObjectNode query(ObjectMapper mapper, String books) {
          ObjectNode query = mapper.createObjectNode();
          if (books == null || books.isBlank()) {
              query.set("match_all", mapper.createObjectNode());
              return query;
          }
          ArrayNode values = mapper.createArrayNode();
          for (String book : books.split("\\|")) {
              if (!book.isBlank()) {
                  values.add(book.trim());
              }
          }
          query.set("terms", mapper.createObjectNode().set("book", values));
          return query;
      }
  }
  ```
  In both tools, replace `body.set("query", MAPPER.createObjectNode().set("match_all", MAPPER.createObjectNode()));` with `body.set("query", BackfillScope.query(MAPPER, readString(BackfillScope.ENV, "")));`. Each tool's own `readString` resolves property, then env, as at `:246`/`:276`.

- [ ] **Step 3 (TDD): Python failing test** — `scripts/similar/test_book_scope.py`: for each of the three modules, load it with `importlib.util.spec_from_file_location` and assert:
  - `books_query(None) == {"match_all": {}}`
  - `books_query("Ghurar al-Ḥikam|Mīzān al-Ḥikma") == {"terms": {"book": ["Ghurar al-Ḥikam", "Mīzān al-Ḥikma"]}}`
  Also assert `llm_similar_hadith.PREFILTER_TOP == 30` with the env var unset, and `load_llm_similar_to_es.ES_INDEX == "rewayaat_hadith"`.
  Run: `python3 -m unittest discover -s scripts/similar -p 'test_book_scope.py'` → red: `AttributeError: … has no attribute 'books_query'`.

- [ ] **Step 4 (Structured): Python changes**
  - Each of `export_for_tagging.py`, `llm_similar_hadith.py`, `build_quranic_light_index.py` gets the same 6-line module function:
    ```python
    def books_query(books):
        """match_all, or a terms filter on `book` for a |-separated list."""
        names = [b.strip() for b in (books or "").split("|") if b.strip()]
        return {"terms": {"book": names}} if names else {"match_all": {}}
    ```
    It is a copy per script because the scripts are self-contained (each defines its own ES helpers, e.g. `export_for_tagging.py:11`) and `scripts/` has no shared import path.
  - `export_for_tagging.py`: `--books` argument. `scroll_all(..., books)` uses `books_query(books)` at `:27-31`.
  - `llm_similar_hadith.py`: `--books` argument. `scroll_all_ids(limit, books)` (`:375`) uses it. `PREFILTER_TOP = int(os.environ.get("LLM_SIMILAR_PREFILTER_TOP", "30"))` (`:41`). Default `ES_INDEX` `"rewayaat_hadith"` (`:35`). **Only** the source-id scroll is filtered. Candidate retrieval (`:100-170`) keeps searching the whole index.
  - `build_quranic_light_index.py`: `HADITH_BOOKS = os.environ.get("QURANIC_LIGHT_BOOKS", "")`, used at `:259`. Default `HADITH_INDEX` → `"rewayaat_hadith"` (`:16`).
  - `load_llm_similar_to_es.py:31`: `ES_INDEX = os.environ.get("REWAYAAT_INDEX", "rewayaat_hadith")`. `:29`: `CACHE_FILE = os.environ.get("LLM_SIMILAR_CACHE", "tmp/pairs_cache.json")`. The phase-5 rollback loads a backup cache through this.
  - `generate_hadith_embeddings.py:268`: default `"rewayaat_hadith"`.

- [ ] **Step 5 (TDD): green** — both test commands pass. `python3 scripts/similar/llm_similar_hadith.py --help | grep -c -- --books` → 1.

- [ ] **Step 6: Commit** — `git commit -am "feat(tools): run a backfill over chosen books, against the alias by default"` (`git add` the two new files first).

### Definition of Done
- [REQUIRED] both tests green. [REQUIRED] `grep -n "rewayaat_updated\|rewayaat_thaqalayn" scripts/similar/load_llm_similar_to_es.py scripts/similar/llm_similar_hadith.py scripts/embeddings/generate_hadith_embeddings.py scripts/quranic-insights/build_quranic_light_index.py` → 0

**Cleanliness self-check:** [ ] TDD red→green · [ ] unset filter = old behaviour (test) · [ ] no unrelated script touched

---

## Phase 3.2 — Baselines before import

**Objective:** Record the pre-import state that later checks compare against. Non-behavioural: measurement.

**Estimated budget:** 30 min.

### Steps

- [ ] **Step 1 (Exact): Start the worktree app on 8012 (DEC-306)**
  ```bash
  cd /home/zir0/git/rewayaat-lost-books && mvn -B -q -DskipTests package -Dcheckstyle.skip=true -Dfindbugs.skip=true
  setsid /bin/bash -lc "cd /home/zir0/git/rewayaat-lost-books && export APP_BASE_URL=http://localhost:8012; exec mvn -DskipMinify=true spring-boot:run -Dspring-boot.run.profiles=dev -Dspring-boot.run.arguments='--server.port=8012 --management.server.port=8013'" </dev/null > /tmp/rewayaat-8012.log 2>&1 &
  ```
  Ready when `curl -s -o /dev/null -w '%{http_code}' localhost:8012/books` → `200`.

- [ ] **Step 2 (Exact): Baselines**
  ```bash
  R=/mnt/share/rewayaat-backup/tmp/v1-restore/receipts; mkdir -p $R
  cd /home/zir0/git/rewayaat-lost-books
  python3 scripts/search/evaluate_search.py --base http://localhost:8012 > $R/eval-before.txt 2>&1
  for b in $(curl -s localhost:8012/v1/browse/books | python3 -c 'import json,sys; print(" ".join(x["slug"] for x in json.load(sys.stdin)))'); do
    printf '%s %s\n' "$(curl -s localhost:8012/books/$b | wc -c)" "$b"; done | sort -n > $R/book-page-bytes-before.txt
  curl -s localhost:9200/rewayaat_hadith/_count > $R/count-before.json
  ```
  If the `/v1/browse/books` payload shape differs (`BrowseFacets.java:38-51` returns a `JSONArray` of `{name, slug, nameAr}`), adapt the one-liner. The goal is unchanged: every book slug.

### Definition of Done
- [REQUIRED] `tail -1 $R/book-page-bytes-before.txt` gives the largest page (the DEC-305 budget). `count-before.json` shows `32519`.

**Cleanliness self-check:** [ ] TDD skipped: measurement only · [ ] app on 8012, nothing on 8002 touched

---

## Phase 3.3 — Import into local ES

**Objective:** The 17,600 narrations are searchable locally under the alias, the catalogue sees 21 books, and existing docs are untouched.

**Estimated budget:** 20 min.

### Steps

- [ ] **Step 1 (Exact): Add the new fields to the live mapping**
  ```bash
  curl -s -XPUT localhost:9200/rewayaat_hadith/_mapping -H 'content-type: application/json' -d '{"properties":{
    "cited_from":{"type":"keyword","fields":{"text":{"type":"text","analyzer":"english_fold","search_analyzer":"english_fold_search"}}},
    "cited_from_ar":{"type":"keyword","fields":{"text":{"type":"text","analyzer":"arabic_norm"}}},
    "publisher_ar":{"type":"keyword","fields":{"text":{"type":"text","analyzer":"arabic_norm"}}}}}'
  ```
  Expected: `{"acknowledged":true}`. Adding fields is allowed in place, so no reindex is needed (`docs/search-index-migration.md`).

- [ ] **Step 2 (Exact): Import**
  ```bash
  cd /home/zir0/git/rewayaat-lost-books && python3 scripts/ingest/import_hadith_batches.py --es-url http://localhost:9200 --dest-index rewayaat_hadith --batch-path batches/v1-restored
  ```
  No `--create-index`. `import_hadith_batches.py` indexes by `_id` (`:212`), so a rerun is idempotent.

- [ ] **Step 3 (Exact): Tell the catalogue**
  ```bash
  curl -s -XPUT 'localhost:9200/rewayaat_meta/_doc/catalog?refresh=true' -H 'content-type: application/json' -d "{\"updatedAt\":\"$(date -u +%Y-%m-%dT%H:%M:%S.%6NZ)\"}"
  ```
  This matches `CatalogSignal.java` (`INDEX rewayaat_meta`, `DOC_ID catalog`, `FIELD updatedAt`). `BookCatalog.java:394` rebuilds within 30 s.

- [ ] **Step 4 (Exact): Verify**
  ```bash
  curl -s 'localhost:9200/rewayaat_hadith/_count' # → 50119
  curl -s 'localhost:9200/rewayaat_hadith/_search?size=0' -H 'content-type: application/json' -d '{"aggs":{"b":{"terms":{"field":"book","size":30}}}}' | python3 -c 'import json,sys;[print(b["doc_count"],b["key"]) for b in json.load(sys.stdin)["aggregations"]["b"]["buckets"]]'
  ```
  Expected: 21 books. Ghurar al-Ḥikam 11031 · Mīzān al-Ḥikma 4654 · Mishkāt al-Anwār 1915. The 18 existing counts are unchanged from R6.

### Definition of Done
- [REQUIRED] count 50,119 · 21 buckets · `curl -s localhost:8012/v1/browse/books` lists 21 entries, each new one with `nameAr`

**Cleanliness self-check:** [ ] TDD skipped: data load. DoD = counts · [ ] the existing 32,519 are unchanged (an aggregation on the 18 old books equals R6)

---

## Phase 3.4 — Deterministic enrichment

**Objective:** The new docs carry `semantic_matn_source`, `semantic_english_hint_source`, `semantic_significant_terms_source` and `semantic_vector`. Old docs are byte-identical in those fields.

**Estimated budget:** 60–120 min wall time (embeddings on CPU).

### Steps

- [ ] **Step 1 (Exact): Fingerprint 200 existing docs** (the drift check)
  ```bash
  R=/mnt/share/rewayaat-backup/tmp/v1-restore/receipts
  curl -s 'localhost:9200/rewayaat_hadith/_search?size=200&sort=_id' -H 'content-type: application/json' -d '{"query":{"bool":{"must_not":{"terms":{"book":["Ghurar al-Ḥikam","Mīzān al-Ḥikma","Mishkāt al-Anwār"]}}}},"_source":["semantic_matn_source","semantic_significant_terms_source","semantic_english_hint_source"]}' | sha256sum > $R/old-docs-fingerprint.txt
  ```

- [ ] **Step 2 (Exact): Chain-free text, then terms, on the three books**
  ```bash
  cd /home/zir0/git/rewayaat-lost-books
  mvn -B -q -DskipTests dependency:build-classpath -Dmdep.outputFile=/tmp/rewayaat-lostbooks.cp
  export REWAYAAT_INDEX=rewayaat_hadith SEMANTIC_BACKFILL_BOOKS='Ghurar al-Ḥikam|Mīzān al-Ḥikma|Mishkāt al-Anwār'
  java -cp "target/classes:$(cat /tmp/rewayaat-lostbooks.cp)" com.rewayaat.tools.SemanticMatnSourceBackfillTool
  java -cp "target/classes:$(cat /tmp/rewayaat-lostbooks.cp)" com.rewayaat.tools.SemanticSignificantTermsBackfillTool
  ```
  Probe before the full run: inspect 10 Ghurar `semantic_matn_source` values. The isnād stripper was written for chains. Ghurar matn begin with `الإمامُ عليٌّ (عَلَيهِ الّسَلامُ) :`. **Required:** that attribution is either removed or kept, consistently across all three books. **If the stripper removes saying text** (a matn shorter than the Arabic minus the attribution), stop and report with 10 examples. Do not patch the stripper in this plan.

- [ ] **Step 3 (Exact): Embeddings**
  ```bash
  cd /home/zir0/git/rewayaat-lost-books
  CUDA_VISIBLE_DEVICES="" python3 scripts/embeddings/generate_hadith_embeddings.py --es-url http://localhost:9200 --index rewayaat_hadith \
    --model /mnt/share/rewayaat-backup/tmp/finetuned_model/finetuned_e5_large_hadith --batch-size 16 \
    2>&1 | tee /mnt/share/rewayaat-backup/tmp/v1-restore/receipts/embeddings.log
  ```
  `--skip-existing` is the default (`:279`), so only the new docs are encoded. Run it in the background and wait for the exit notification. Rate check after the first 500 docs, in the log. Expected order: tens of docs per second on CPU. Below 2 docs/s, stop and report.

- [ ] **Step 4 (Exact): Verify**
  ```bash
  q(){ curl -s localhost:9200/rewayaat_hadith/_count -H 'content-type: application/json' -d "$1"; echo; }
  q '{"query":{"exists":{"field":"semantic_vector"}}}'                    # → 50080 (32516 + 17564; the 36 Mishkat empty-Arabic docs have no matn)
  q '{"query":{"bool":{"filter":[{"terms":{"book":["Ghurar al-Ḥikam","Mīzān al-Ḥikma","Mishkāt al-Anwār"]}}],"must_not":{"exists":{"field":"semantic_matn_source"}}}}}'  # → 36
  ```
  Re-run the Step 1 command and `diff` it against `old-docs-fingerprint.txt` → identical. If the vector count differs from 50,080, list the new ids without vectors and classify them (empty Arabic versus failure).

### Definition of Done
- [REQUIRED] vectors 50,080 · fingerprint identical · `embeddings.log` with no `ERROR`

**Checkpoint contract:** each tool is resumable. The matn and terms tools are idempotent over the filtered scroll. Embeddings skip existing vectors. Receipt fingerprint = commit SHA + `batches/v1-restored/manifest.json` sha256 + model path.

**Cleanliness self-check:** [ ] TDD skipped: data enrichment. DoD = counts · [ ] old-doc fingerprint unchanged

---

## Phase 3.5 — Local verification of both sites

**Objective:** Every reader-facing surface works for the new books in English and Arabic. The checks are observable HTTP and screenshots, not greps.

**Estimated budget:** 60 min.

### Steps

- [ ] **Step 1: Restart the 8012 app** (stop with `kill` of the PID from Step 3.2's `setsid` launch, then relaunch with the same command), so `ArabicNames` and the blurbs reload.

- [ ] **Step 2 (Exact): HTTP checks**, `B=http://localhost:8012`:
  | Check | Command | Expected |
  |---|---|---|
  | book list | `curl -s $B/books \| grep -c 'href="/books/ghurar-al-hikam"'` | ≥1 |
  | Arabic book page | `curl -s $B/ar/books/mizan-al-hikma \| grep -o '<h1[^>]*>[^<]*</h1>'` | `ميزان الحكمة` |
  | blurb + summary render | `curl -s $B/books/mishkat-al-anwar \| grep -c 'Mishkāt al-anwār fī ghurar al-akhbār'` | ≥1 |
  | Mizan part page Arabic | `curl -s $B/ar/books/mizan-al-hikma \| grep -c 'href="/ar/books/mizan-al-hikma/part/'` | ≥282 |
  | Ghurar chapter, Arabic title from index | `curl -s $B/ar/books/ghurar-al-hikam/greed \| grep -c 'الحِرص'` | ≥1 |
  | cited-from row EN/AR | `curl -s $B/hadith/Mizan-al-Hikma-Rayshahri:4115 \| grep -c 'Al-Kafi, volume:2'` · `/ar/…` `grep -c 'نقلاً عن'` | ≥1 · ≥1 (`cited_from_ar` is filled in phase-4. Until then the row shows the English, by `localised` fallback) |
  | legacy redirect | `curl -s -o /dev/null -w '%{http_code} %{redirect_url}' "$B/?q=_id:30611"` | `301 …/hadith/Ghurar-al-Hikam-Amidi:…` |
  | unmapped legacy id | `curl -s -o /dev/null -w '%{http_code}' "$B/?q=_id:AXMd1nBSoW8eiOSs8d1H"` | `200` |
  | sitemap pages | `curl -s $B/sitemap.xml \| grep -c sitemap-hadith-` | `6` (`SitemapController.java:43` PAGE_SIZE 10000 → ⌈50119/10000⌉) |
  | books sitemap | `curl -s $B/sitemap-books.xml \| grep -c '/books/ghurar-al-hikam'` | ≥1 |
  | share card | `curl -s -o /dev/null -w '%{http_code} %{content_type}' $B/books/ghurar-al-hikam/card.png` | `200 image/png` |
  | MCP scope | `mvn -B -q test -Dtest=McpProtocolIntegrationTest` | PASS (local ES now has 21 books) |

- [ ] **Step 3 (Exact): Page-size gate (DEC-305)**
  `curl -s $B/books/ghurar-al-hikam | wc -c` ≤ the largest value in `book-page-bytes-before.txt`. Over budget → stop and report both numbers. Letter parts would be a separate plan.

- [ ] **Step 4 (Exact): Search evaluation**
  `python3 scripts/search/evaluate_search.py --base $B > $R/eval-after.txt 2>&1; diff $R/eval-before.txt $R/eval-after.txt`
  Every newly failing property is listed in the receipt. Ghurar's 11,031 short sayings raise BM25 scores for short matn. Expected effect: single-word English queries surface Ghurar near the top. That is reported, not tuned here (NTH).

- [ ] **Step 5 (Exact): Screenshots** (memory: always verify UI with headless Chrome)
  ```bash
  for u in books books/ghurar-al-hikam ar/books/mizan-al-hikma ar/books/mishkat-al-anwar "hadith/Mizan-al-Hikma-Rayshahri:4115" "ar/hadith/Mizan-al-Hikma-Rayshahri:4115" "?q=patience"; do
    f=$(echo "$u" | tr '/?:=' '____'); google-chrome --headless --disable-gpu --window-size=1280,2200 --screenshot=$R/shot-$f.png "http://localhost:8012/$u"; done
  ```
  Read each PNG. Required: Arabic titles RTL, the "Cited from" row present, book cards for the 3 books on `/books` with Arabic names on `/ar/books`.

- [ ] **Step 6:** Stop the 8012 app (`kill` its PID). Commit nothing; receipts stay in `$R`.

### Definition of Done
- [REQUIRED] every Step 2 row as expected · Step 3 within budget · screenshots read
- [ADVISORY] eval diff: new failures listed with an explanation

**Cleanliness self-check:** [ ] TDD n/a (verification slice) · [ ] the 8012 process is stopped · [ ] nothing on 8002 was touched

---

## Maintainability summary
- Import: `import_hadith_batches.py` unchanged. Catalogue refresh: `CatalogSignal` contract. Embeddings: the existing skip-existing behaviour.
- One new Java class (`BackfillScope`) shared by both backfill tools. No new Python module.

## NTH notes
- `grep -rn "rewayaat_updated" scripts` lists more stale defaults: `scripts/tagging/import_tags_to_es.py:10` (docstring), `docs/data-pipeline.md` commands, `docs/pipelines/llm-similar-hadith.md` verify commands. Phase-5 fixes the docs this plan's commands depend on. The rest are outside this plan.
- `scripts/ops/restart.sh:31-35` kills every `spring-boot:run` on the machine, which is unsafe on a shared box.
- Search relevance once Ghurar's short sayings are in the corpus (Step 4) is outside this plan.

## Risks
- The isnād stripper on Ghurar/Mizan attribution prefixes (Step 2 probe).
- Embedding throughput on CPU (Step 3 rate gate).
- Prod edits continue during phases 3–4. They do not affect new ids. Phase-5 writes only new docs plus the `llm_similar` field on old ones.
