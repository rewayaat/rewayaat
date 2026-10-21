# Restore v1 Books — Phase 5: Production Rollout

**Goal:** Production serves the 21-book corpus (50,119 narrations) on rewayaat.info in English and Arabic, through the MCP connector, with docs and counts telling the truth, and a tested rollback.

**Architecture:** Data before code (`docs/arabic-release.md`). New docs are copied from the finished local index to prod through a port-forward, along with the `llm_similar` field on old docs and the new Qurʾānic docs. Then the branch merges, CI builds the image, and Argo rolls it out. Verification goes through the public site, not the index (memory `verify-seo-against-prod`).

**Working directory for all commands:** `/home/zir0/git/rewayaat-lost-books`

**Related plans:** [phase-1](2026-10-19-restore-v1-books-phase-1.md), [phase-2](2026-10-19-restore-v1-books-phase-2.md), [phase-3](2026-10-19-restore-v1-books-phase-3.md), [phase-4](2026-10-19-restore-v1-books-phase-4.md), phase-5 (this file)

**Depends on:** phases 1–4 complete, each DoD green.

**Maintainability expectations:** Prod writes only through the existing importers. Every count in docs and code is measured, not copied from this plan.

**Discovery evidence:** [`discovery-receipt.md`](2026-10-19-restore-v1-books/discovery-receipt.md) R1 (snapshot repo), R6 (prod state). Prod disk was at 8–16% (`docs/arabic-data-migration.md`). The index grows by ~54% in docs (32,519 → 50,119).

**Decision and evidence manifest:**

| ID | Decision | Files/symbols | Source evidence | Reuse target | Blast-radius query + classified siblings | Acceptance proof | Risk | Invalidation conditions |
|---|---|---|---|---|---|---|---|---|
| DEC-501 | A manual prod snapshot first. Manual snapshots fall outside the SLM 7-day retention, which applies to policy snapshots | `es-v2-snapshots` | R1. SLM `es-v2-daily` retention | `manual-pre-alias-migration-20260909` precedent | — | `_cat/snapshots` shows it `SUCCESS` | high | — |
| DEC-502 | Prod writes: (a) the 17,600 new docs, whole; (b) the `llm_similar` field on old docs via the loader; (c) new `rewayaat_quranic_light_filtered` docs; (d) the catalogue signal. Nothing else on old docs | importers | prod is edited live (`docs/arabic-data-migration.md` "Production is being edited") | `import_hadith_batches.py`, `load_llm_similar_to_es.py` | old-doc fields touched = `llm_similar` only | the 5.3 drift check | high | prod `llm_similar` differs from local for old docs |
| DEC-503 | Merge only after 5.3 verifies the data on prod | `.github/workflows/ci-cd.yml` (a push to master deploys) | ci-cd.yml:1-4 | — | — | 5.5 checks | high | — |
| DEC-504 | Counts in docs and code: current-state statements updated, historical narrative left | list in 5.4 | `grep -rn "32,519\|eighteen\|18 books"` (classified in 5.4) | — | — | `grep` DoD | standard | — |

**Execution policy:** Outward-facing. **Ask the user before 5.2 (first prod write) and before 5.5 (push/merge).** Approval for one does not cover the other. Commits are on the branch. One PR.

---

## Phase 5.1 — Preconditions

- [ ] **Step 1 (Exact):**
  ```bash
  cd /home/zir0/git/rewayaat-lost-books
  git status --short | wc -l                                    # → 0
  mvn -B test -Dtest='!HodaAlQuranQualityCheck' -DfailIfNoSpecifiedTests=false   # → BUILD SUCCESS
  B='{"terms":{"book":["Ghurar al-Ḥikam","Mīzān al-Ḥikma","Mishkāt al-Anwār"]}}'
  for f in semantic_vector topic_tags chapter_ar; do curl -s localhost:9200/rewayaat_hadith/_count -H 'content-type: application/json' -d "{\"query\":{\"bool\":{\"filter\":[$B,{\"exists\":{\"field\":\"$f\"}}]}}}"; echo " $f"; done
  ```
  Expected: 17,564 vectors · ≥ 17,072 tags · 17,600 `chapter_ar`. Record the numbers in `tmp/v1-restore/receipts/prod-preconditions.txt`.
- [ ] **Step 2 — ASK:** "Ready to write to production: 17,600 docs + `llm_similar` on ~25K old docs + N Qurʾānic docs. Snapshot first. Proceed?"

---

## Phase 5.2 — Production data

**Estimated budget:** 60–90 min over a port-forward (the Arabic migration took 19 min for 5 dry-run scrolls, `docs/arabic-data-migration.md`).

### Steps

- [ ] **Step 1 (Exact): Port-forward** (background): `kubectl port-forward -n elastic-v2 svc/elasticsearch-v2 9201:9200`. Then `curl -s localhost:9201/_cat/aliases/rewayaat_hadith` → `rewayaat_hadith rewayaat_hadith_20260909`. Everything below targets **9201** and the **alias**.

- [ ] **Step 2 (Exact): Snapshot**
  ```bash
  S=manual-pre-v1-restore-$(date -u +%Y%m%d-%H%M)
  curl -s -XPUT "localhost:9201/_snapshot/es-v2-snapshots/$S?wait_for_completion=true" -H 'content-type: application/json' \
    -d '{"indices":"rewayaat_hadith_20260909,rewayaat_quranic_light_filtered,rewayaat_meta","include_global_state":false}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["snapshot"]["state"])'
  echo $S > tmp/v1-restore/receipts/prod-snapshot.txt
  ```
  Expected: `SUCCESS`.

- [ ] **Step 2b (Exact): Drift baseline** — 50 random old docs, without the field this phase rewrites:
  ```bash
  curl -s 'localhost:9201/rewayaat_hadith/_search?size=50' -H 'content-type: application/json' \
    -d '{"query":{"function_score":{"random_score":{"seed":20261019,"field":"_seq_no"}}},"_source":{"excludes":["llm_similar"]}}' \
    | python3 -c 'import json,sys;h=json.load(sys.stdin)["hits"]["hits"];json.dump({x["_id"]:x["_source"] for x in h},open(sys.argv[1],"w"),sort_keys=True,ensure_ascii=False)' tmp/v1-restore/receipts/prod-drift-before.json
  ```

- [ ] **Step 3 (Exact): Mapping** — the phase-3 3.3 Step 1 command with `localhost:9201`. Expected: `{"acknowledged":true}`.

- [ ] **Step 4 (Exact): Export the finished new docs from local, then import to prod**
  ```bash
  python3 scripts/ingest/export_hadith_batches.py --es-url http://localhost:9200 --index rewayaat_hadith \
    --query "{\"terms\":{\"book\":[\"Ghurar al-Ḥikam\",\"Mīzān al-Ḥikma\",\"Mishkāt al-Anwār\"]}}" --out-dir tmp/v1-restore/prod-export --force
  cat tmp/v1-restore/prod-export/*.jsonl | wc -l                       # → 17600
  python3 scripts/ingest/import_hadith_batches.py --es-url http://localhost:9201 --dest-index rewayaat_hadith --batch-path tmp/v1-restore/prod-export
  ```
  `--fields` is omitted, so the export carries every field, including `semantic_vector`, `topic_tags`, `llm_similar` and the `_ar` fields.

- [ ] **Step 5 (Exact): Similar links on old docs**
  ```bash
  REWAYAAT_INDEX=rewayaat_hadith python3 scripts/similar/load_llm_similar_to_es.py --dry-run --es-host http://localhost:9201
  REWAYAAT_INDEX=rewayaat_hadith python3 scripts/similar/load_llm_similar_to_es.py --live --es-host http://localhost:9201
  ```
  This is the same cache that phase-4 loaded locally, so old docs get exactly the local adjacency.

- [ ] **Step 6 (Exact): Qurʾānic docs**
  ```bash
  python3 scripts/ingest/export_hadith_batches.py --es-url http://localhost:9200 --index rewayaat_quranic_light_filtered \
    --query "{\"terms\":{\"hadith_book\":[\"Ghurar al-Ḥikam\",\"Mīzān al-Ḥikma\",\"Mishkāt al-Anwār\"]}}" --out-dir tmp/v1-restore/prod-qlight --force
  python3 scripts/ingest/import_hadith_batches.py --es-url http://localhost:9201 --dest-index rewayaat_quranic_light_filtered --batch-path tmp/v1-restore/prod-qlight
  ```
  `import_hadith_batches.py` is a generic `{_id,_source}` bulk index (`:203-226`). Without `--create-index` it does not touch the mapping. If `hadith_book` is not a keyword in that index (`GET rewayaat_quranic_light_filtered/_mapping`), filter on `hadith_book.keyword`.

- [ ] **Step 7 (Exact): Catalogue signal** — the phase-3 3.3 Step 3 command with `localhost:9201`.

### Definition of Done
- [REQUIRED] prod `_count` on the alias = 32,519 + 17,600 + (prod-only docs added since 2026-10-19, normally 0) → record it
- [REQUIRED] the prod `book` aggregation = 21 buckets with the three counts from phase-1
- [REQUIRED] prod `rewayaat_quranic_light_filtered/_count` = 22,640 + N_new (phase-4)

---

## Phase 5.3 — Verify the data on the live site (old code)

The book pages are built from the index (`BookCatalog`), so they exist before the deploy. Their blurbs, Arabic names and the cited-from row arrive with the code.

- [ ] **Step 1 (Exact):**
  ```bash
  P=https://rewayaat.info
  curl -s -o /dev/null -w '%{http_code}\n' $P/books/ghurar-al-hikam               # → 200 (within 30 s of 5.2 Step 7)
  curl -s -o /dev/null -w '%{http_code}\n' "$P/hadith/Mizan-al-Hikma-Rayshahri:4115"  # → 200
  curl -s "$P/v1/narrations?q=ummal" | head -c 300                                  # no hits yet: cited_from.text is not searched before the deploy
  ```
- [ ] **Step 2 (Exact): Drift check on old docs** — fetch the same 50 ids (`_mget` with the ids from `prod-drift-before.json`, `_source_excludes=llm_similar`) and compare: `python3 -c` diff of the two maps → `{}`. A difference is a prod edit made in the window (shown by the editor's `history`) or a bug in this rollout. Classify each before going on.
- [ ] **Step 3 — ASK:** "Prod data verified. Push the branch and open the PR?"

---

## Phase 5.4 — Counts, copy and docs on the branch

**Objective:** Every current-state count is true. The release is announced in both languages.

**Files changed (current-state statements only; historical narrative is left):**
- `src/main/java/com/rewayaat/mcp/tools/FindSimilarTool.java:21,65` — `25,273 of 32,519` → measured (nested count of docs with `llm_similar` on prod / 50,119). Also `89,225` judgements → measured.
- `src/main/java/com/rewayaat/mcp/tools/VersesForHadithTool.java:20,66` — `22,635 of 32,519` → measured.
- `src/main/java/com/rewayaat/mcp/tools/HadithForVerseTool.java:20` — "eighteen books" → "twenty-one books".
- `src/main/java/com/rewayaat/mcp/NarrationRepository.java:65` — `32,516 of 32,519` → `50,080 of 50,119`.
- `src/main/java/com/rewayaat/service/BookCatalog.java:40,95`, `src/main/java/com/rewayaat/controllers/SitemapController.java:160` — book, part and chapter counts, measured from the startup log `Book catalog built: N books, M chapters` and a parts count.
- `README.md:3`, `docs/architecture.md:3,12,127` (+ the index sizes in the diagram), `docs/data-pipeline.md:12-14` (state table) + a new section "Restoring the v1 books" (receipt R2 steps, the converter, the overrides), `docs/mcp-connector.md:16,105,222`, `docs/architecture/adr-001-mcp-connector.md:87`, `docs/seo.md:211,278-279` (6 hadith sitemaps), `docs/i18n.md:70,85` and the counts in its content table, `docs/pipelines/llm-similar-hadith.md:9`, `docs/pipelines/quranic-insights.md` (stage table: the new books' row).
- `src/main/resources/static/announcement.json`: new `id` `restored-books-2026-10`, with `text`/`text_ar` announcing the three books. The connector banner is replaced.
- `src/main/resources/static/recent_updates.json`: a new first entry, same keys as the shortest existing entry, `id` `restored-books`, with `title`/`summary` and `title_ar`/`summary_ar`. The summary says what each book is (from the phase-2 book summaries), never "Rewayaat" (memory `platform-name-hadith-database`). Run the `humanizer` skill over the English.
- Left as history: `recent_updates.json` older entries, `docs/arabic-*.md`, `docs/search-index-migration.md`, the javadoc that narrates past states (`ShareCardController.java:40,45`, `HadithPageController.java:134`, `BookPageController.java:42`, `PageLocale.java:163`).

### Steps
- [ ] **Step 1 (Structured):** Measure on prod, edit the files above, then run `mvn -B -q test -Dtest='TranslatedDataTest,UpdateAnchorsTest,MessageCatalogueTest,McpProtocolIntegrationTest'` → PASS.
- [ ] **Step 2 (Exact) DoD grep:** `grep -rn "32,519\|32519" src/main/java src/main/resources/static/announcement.json README.md docs/architecture.md docs/mcp-connector.md docs/data-pipeline.md` → only lines classified as history above (list them in the commit body).
- [ ] **Step 3: Commit** — `git commit -am "docs: the corpus is twenty-one books now"`

---

## Phase 5.5 — Merge, deploy, verify

- [ ] **Step 1:** `git push -u origin feature/restore-v1-books`, then `gh pr create`. The body summarises phases 1–5, the receipts and the rollback, and ends with the attribution line from the session's system reminder. **The user approves the merge.**
- [ ] **Step 2:** CI green on the PR. After merge, CI pushes the image, commits the tag (`ci: update rewayaat-v2 image tag to …`) and Argo rolls out. Wait for `kubectl -n <app ns> rollout status deploy/rewayaat-v2` (namespace from `k8s/kustomization.yaml`) and for the MCP deployment (`k8s/deployment-mcp.yaml`).
- [ ] **Step 3 (Exact): Public verification** (both hosts, memory `canonical-domain`):
  | Check | Expected |
  |---|---|
  | `curl -s https://rewayaat.info/books \| grep -c '/books/mizan-al-hikma'` | ≥1 |
  | `curl -s https://rewayaat.info/ar/books/mishkat-al-anwar \| grep -o '<h1[^>]*>[^<]*</h1>'` | `مشكاة الأنوار في غرر الأخبار` |
  | `curl -s "https://rewayaat.info/hadith/Mizan-al-Hikma-Rayshahri:4115" \| grep -c 'Cited from'` | ≥1 |
  | `curl -s "https://rewayaat.info/ar/hadith/Mizan-al-Hikma-Rayshahri:4115" \| grep -c 'الكافي، ج 2'` | ≥1 |
  | `curl -s -o /dev/null -w '%{http_code} %{redirect_url}' 'https://rewayaat.info/?q=_id:30611'` | `301 https://rewayaat.info/hadith/Ghurar-al-Hikam-Amidi:…` |
  | `curl -s https://rewayaat.info/sitemap.xml \| grep -c sitemap-hadith-` | `6` |
  | `curl -s "https://rewayaat.info/v1/narrations?q=ummal" \| python3 -c 'import json,sys;print(json.load(sys.stdin)["totalResultSetSize"])'` (adapt to the payload) | > 0 |
  | the same book page on the academyofislam mirror | 200, same content |
  | MCP: `search_hadith` with `query: "Ghurar"` through the Hadith connector | results from Ghurar al-Ḥikam. The server instructions name 21 books |
  | screenshots (headless Chrome) of `/books`, `/ar/books`, one card per book EN/AR | read and correct |
- [ ] **Step 4 (user actions, list them to the user):** resubmit `https://rewayaat.info/sitemap.xml` in Search Console. URL-inspect `/?q=_id:30611`. Update the connector directory descriptions that say 18 books or 32,519 (see `docs/mcp-connector.md` listing copy).

---

## Phase 5.6 — Cleanup and memory

- [ ] `docker image rm docker.elastic.co/elasticsearch/elasticsearch:7.10.2` (1.3 GB on the 86% root disk). Keep `/mnt/share/rewayaat-backup/v1-snapshot/`.
- [ ] Delete the local staging index `rewayaat_quranic_light_v1restore` once prod verification passes.
- [ ] Memory: update `MEMORY.md` architecture counts (index 50,119, 21 books) and `v1-snapshots-missing-books.md` → "restored 2026-MM-DD". Add the narrator handoff (phase-4 NTH) to the narrator memory.
- [ ] After merge: `git worktree remove ~/git/rewayaat-lost-books` (after `git status` is clean) and delete the local branch.

## Rollback

Before the merge, data only:
1. `POST localhost:9201/rewayaat_hadith/_delete_by_query {"query":{"terms":{"book":[…three…]}}}` and the same on `rewayaat_quranic_light_filtered` with `hadith_book`.
2. Restore `llm_similar` on old docs: `LLM_SIMILAR_CACHE=tmp/v1-restore/pairs_cache.before-v1-restore.json REWAYAAT_INDEX=rewayaat_hadith python3 scripts/similar/load_llm_similar_to_es.py --live --es-host http://localhost:9201` (env added in phase-3 3.1). That cache holds no pair touching a new id, so the old adjacency comes back exactly.
3. Touch the catalogue signal.

After the merge: revert the merge commit (CI redeploys), then steps 1–3. The redirect, `CorpusScope` and the blurbs revert with the code.

Last resort: restore `$S` (`tmp/v1-restore/receipts/prod-snapshot.txt`) into new index names and move the alias with `scripts/search/migrate_index.py`. It loses edits made after the snapshot (`docs/search-index-migration.md`).

## Risks
- Prod edits during the window are untouched by design (DEC-502). Only `llm_similar` on old docs is rewritten.
- The index grows ~54% in docs, plus ~17.6K × 4 KB vectors ≈ 70 MB. Check node disk after Step 5.2 (`_cat/allocation?v`). The 1 GiB heap (`k8s/deployment.yaml:60-67`) also holds the larger `BookCatalog` (~1,000 more chapters, ~300 more parts). Watch `RewayaatV2HeapPressure` for 24 h (memory `prod-capacity-findings-2026-09-25`).
- Search Console sees ~17,600 new narration URLs at once. This is expected crawl load, not a problem.
