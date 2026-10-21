# Discovery receipt — restoring the v1 books

Measured 2026-10-19. Every number in the phase plans traces here or to a cited `file:line`.

## R1 — Where the books survive

- Command: `s3cmd ls s3://` · `kubectl -n elastic-v2 exec elasticsearch-v2-0 -c elasticsearch -- curl -s localhost:9200/_cat/snapshots/es-v2-snapshots`
- `s3://rewayaat-data`: an fs-style ES snapshot repo, 197 MB, 331 snapshots, 2024-03-25 → 2025-02-20, ES 7.10.2, a single index `rewayaat` (`index-330`, `index.latest`).
- `es-v2-snapshots` (bucket `k8s-c1-es-v2-backups`): the oldest snapshot is `manual-pre-alias-migration-20260909`, the rest are the last 7 days (SLM `es-v2-daily`, `expire_after: 7d`). Nothing from before Ghadeer.
- `s3://k8s-c1-es-backups`: 0 objects.
- Proven: the snapshot from 2025-02-20 and the earliest one with an index (2024-04-09) hold the same 11 books, with the same counts.

## R2 — Restore procedure (worked end to end)

1. `s3cmd sync s3://rewayaat-data/ <dir>/`, then `chmod -R a+rX <dir>`.
2. `docker run -d --name v1-snapshot-probe -p 127.0.0.1:9207:9200 -e discovery.type=single-node -e path.repo=/repo -e ES_JAVA_OPTS="-Xms512m -Xmx512m" -v <dir>:/repo:ro docker.elastic.co/elasticsearch/elasticsearch:7.10.2`
3. `PUT _snapshot/v1 {"type":"fs","settings":{"location":"/repo","readonly":true}}`
4. **`docker exec v1-snapshot-probe touch /usr/share/elasticsearch/config/synonyms.txt`.** Without it the shard fails to allocate with `NoSuchFileException[.../synonyms.txt]`.
5. `POST _snapshot/v1/2025-02-20/_restore?wait_for_completion=true {"indices":"rewayaat","include_global_state":false,"index_settings":{"index.number_of_replicas":0}}`. Result: 36,019 docs, green.
6. Scroll dump → JSONL (48.4 MB). ES 9.0.2 cannot restore a 7.x snapshot directly.

## R3 — What the current corpus lacks

- v1 books (2025-02-20): Al-Kafi 13,402 · **Ghurar Al-Hikam 11,031** · **Mizan Al-Hikmah 4,654** · **Mishkat Al-Anwar 1,937 + 1** (two spellings of the book name) · Al-Khisal 1,280 · Uyun 951 · Kamil 762 · Tawhid 577 · Mu'jam 550 · Nahj 487 · Al-Amali 387.
- A sample of 40 per book, phrase-matched by 6 words from the middle of the Arabic against `rewayaat_hadith`: Ghurar 0/40 found · Mizan 7/40 (all of them quotations from other books) · Mishkat 8/40 · the other 8 books mostly found. v1's Al-Amali is al-Mufid's, and it is inside today's `Al-Amālī` (`source` = al-Mufīd, 387 docs).
- `batches/*.jsonl` (the Thaqalayn-derived import, 164 files) contains none of the three books.

## R4 — Shape of the v1 data

| | Ghurar | Mizan | Mishkat |
|---|---|---|---|
| docs | 11,031 | 4,654 | 1,938 |
| `number` unique | yes, 1–11036 | yes, 1–4670 | no, 33 docs collide |
| v1 `section` | null | 284 topics | 12 sections |
| v1 `chapter` | 727, `ENGLISH ARABIC` | 1,367, mixed | 105, `English / Arabic` |
| `publisher` | Ansariyan Publications | ICAS Press | — |
| v1 `source` | — | 4,234 citations, 163 cited books (Bihar 1,011 · Ghurar 923 · Kafi 512 · Kanz al-Ummal 399 · Nahj 356) | — |
| empty Arabic | 0 | 0 | 59 |
| `history` | v1 edit log **containing editor e-mail addresses** | 22 | 22 |
| `gradings` / `related` / `tags` | empty | empty | empty |

- Mishkat collisions: 23 of the 33 have empty Arabic and are numbered-list fragments split off a parent narration (e.g. `"anger, 5- sleeping, 6- waking up.”"`). No deterministic parent can be found (checked against the `1-` list openers in the same chapter). With those 23 removed, every number is unique (1,915 docs).
- Legacy GSC soft 404 `/?q=_id:30611` is the Ghurar narration v1 `_id` 30611.

## R5 — Converter probe

Ran the files in this directory (`convert_v1_books.py`, `test_convert_v1_books.py`) against the dump.

- `python3 -m unittest test_convert_v1_books`: 14 tests, OK.
- With empty overrides: `{"documents": 17600, "excluded": 23, "blocking": 21, "untranslated": 219, "disambiguated": 387}`, exit 1. Full report: `probe-anomalies.json`.
- 88 batch files of 200. Ghurar: 11,031 docs, 724 chapters, no part. Mizan: 4,654 docs, 1,386 chapters, 282 parts, 3,853 with `chapter_ar`. Mishkat: 1,915 docs, 107 chapters, 12 parts, 36 with empty Arabic.
- 21 blocking titles: 9 are narration text pasted into the title field, 5 are legitimate titles over 100 characters, 7 are doubled or mixed bilingual titles. The 219 untranslated titles are 217 Mizan chapters and 2 parts. 25 English titles carry two different Arabic titles; together they cover 387 docs.
- Chapter length: p99 69 characters, max 202.

## R6 — Live index facts

- Local and prod alias `rewayaat_hadith` → `rewayaat_hadith_20260909`. Both hold 32,519 docs, 18 books.
- Prod: `chapter_ar`, `book_ar` and `part_ar` on 32,519 · `semantic_vector` 32,516 · `topic_tags` 31,809 · `chain_people` 0 (narrators are not on prod) · `rewayaat_quranic_light_filtered` 22,640.
- `source` holds the compiler (11 values). `publisher` is on 0 docs today. `history` and `notes` on 0 local docs.
- Local GPU: GT 1030, 2 GB, too small for e5-large in fp32. Embeddings run on CPU.
- Previous agent workloads: tags were 67 batches of 500 (`/mnt/share/rewayaat-backup/tmp/results/hadith/`). The quranic judge was 651 batches of 50. Similar-hadith was 374,461 pairs, about 25–30 h of Sonnet agents (`docs/data-pipeline.md`).

## R7 — Out-of-band state to respect

- The shared checkout `~/git/rewayaat` is on local `master` at `81d5f65`, behind `origin/master` `2c76714`, with **116 staged changes from another session** (deletions of `ArabicNames.java`, `ArabicSiteConfig.java`, `scripts/i18n/*`, the Arabic docs). These are not on `origin/master`. All work happens in the worktree `~/git/rewayaat-lost-books`, branch `feature/restore-v1-books`, based on `origin/master`. The shared checkout is not touched.
