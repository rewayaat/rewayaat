# Scripts

Offline tooling, grouped by the pipeline it belongs to. Everything here is run from the
**repository root**, not from inside `scripts/`:

```bash
python3 scripts/<group>/<script>.py
```

Most scripts take `--es-host` (default `http://localhost:9200`) and default to a dry run
or a small sample; pass `--live` to write. Large intermediate files go to `tmp/`, which
is a symlink to `/mnt/share/rewayaat-backup/tmp/` and is never committed.

See [../docs/data-pipeline.md](../docs/data-pipeline.md) for how these fit together, and
[../docs/pipelines/](../docs/pipelines/) for the agent-driven ones.

## `ingest/` — get the corpus in

| Script | Purpose |
|--------|---------|
| `thaqalayn_import.py` | Pull the corpus from the Thaqalayn API |
| `import_hadith_batches.py` | Bulk-import `batches/*.jsonl` into `rewayaat_updated` |
| `export_hadith_batches.py` | Export hadith back out to JSONL |
| `backfill_metadata.py` | Enrich docs with volume, part, section, gradings, source |
| `download_full_quran.py` | Quran text + Sahih International translation from quran.com |
| `download_quran_*.sh` | Older shell variants of the same download |
| `extract_and_index_tafsir.py` | Run the tafsir extractors over cached HTML |
| `index_tafsir_json.py` | Index pre-extracted tafsir JSON |
| `import_split_tafsir.py` | Import per-verse tafsir documents |
| `export_multi_verse_tafsir.py` | Export tafsir spanning several verses |

## `embeddings/` — semantic vectors

| Script | Purpose |
|--------|---------|
| `generate_hadith_embeddings.py` | Generate `semantic_vector`; skips existing unless `--force` |
| `import_embeddings_to_es.py` | Load a `.npz` of vectors into ES |
| `build_training_dataset.py` | Build fine-tuning pairs, split by hadith ID to prevent leakage |
| `enrich_training_data_from_audit.py` | Harvest validated pairs from the live API |
| `setup_semantic_similarity.sh` | Configure the ES mapping for kNN |

## `similar/` — LLM similar hadith

Runbook: [../docs/pipelines/llm-similar-hadith.md](../docs/pipelines/llm-similar-hadith.md)

| Script | Purpose |
|--------|---------|
| `llm_similar_hadith.py` | All-in-one: `--precompute`, `--build-cache`, `--prepare-batches`, `--merge` |
| `load_llm_similar_to_es.py` | Load the judged cache into the `llm_similar` nested field |
| `quality_audit_similar.py` | Sample and score the judged pairs |
| `manual_hadith_judge.py` | Judge pairs by hand |
| `prepare_judge_batches.py` | Build judge batches |
| `process_remaining_pairs.py` | Sweep up pairs the main loop missed |
| `auto_llm_similar_loop.sh` | Driver for the unattended agent loop |

## `quranic-insights/` — hadith → Quran verse connections

Runbook: [../docs/pipelines/quranic-insights.md](../docs/pipelines/quranic-insights.md)

| Script | Purpose |
|--------|---------|
| `build_quranic_light_index.py` | Score hadith against verses on four signals |
| `export_quranic_light_for_filtering.py` | Export candidates for agent judging |
| `apply_quranic_light_filter.py` | Keep only strong connections → `_filtered` index |
| `generate_strict_judge_batches.py` / `apply_strict_judge_filter.py` | Stricter second judging pass |
| `backfill_snippet_text.py` | Repair `commentary_text` truncated at 600 chars |
| `prepare_excerpt_batches.py` / `apply_excerpts.py` / `import_excerpts_to_es.py` | `relevant_excerpt` extraction |
| `generate_highlight_batches.py` / `prepare_snippet_highlighting.py` | Build highlighting batches |
| `highlight_tafsir_snippets.py` / `apply_highlighting.py` | Add and apply `<em>` highlighting |
| `apply_highlights_to_prod.py` | Push highlighting to production ES |

## `tagging/` — topic taxonomy

| Script | Purpose |
|--------|---------|
| `export_for_tagging.py` | Export hadith for agent tagging |
| `import_tags_to_es.py` | Load tags, expanding `taggable` ancestors from `taxonomy.json` |
| `export_verses_for_tagging.py` / `import_verse_tags.py` | The same loop for Quran verses |
| `export_tagging_review_batches.py` | Export tagged output for review |
| `apply_corrected_batch_tags.py` / `overwrite_batch_tags_from_review.py` | Apply review corrections |
| `tag_migration.py` / `run_tag_migration.sh` | Historical remap of 48 tags on the retired `syn_v1` index |

## `annotation/` — inline hadith text annotation

Runbook: [../docs/pipelines/hadith-annotation.md](../docs/pipelines/hadith-annotation.md)

| Script | Purpose |
|--------|---------|
| `prepare_annotation_batches.py` | Export batches, skipping already-annotated hadith |
| `run_annotation_agents.py` | Status, prompt preparation, progress tracking |
| `run_full_annotation.py` | Print the commands for the next wave |
| `load_annotations_to_es.py` | Bulk-load annotation results into ES |
| `extract_quranic_quotes.py` / `run_quranic_ref_agents.py` | Detect Quranic quotations in hadith |
| `fetch_and_batch_results.py` | Pull API search results into agent batches |

## `narrators/` — narrator biographies

The pipeline itself was removed; the extracted data survives under `tmp/`. See
[../docs/proposals/narrator-system.md](../docs/proposals/narrator-system.md).

| Script | Purpose |
|--------|---------|
| `audit_narrator_quality.py` | Sample and score profiles in `tmp/narrators_merged.json` |

## `ops/` — running the thing

| Script | Purpose |
|--------|---------|
| `restart.sh` | Kill and restart the local app on port 8002 |
| `create_snapshot.sh` | Take an ES snapshot before a destructive migration |
| `create_synonym_search_index.py` | Clone an index with a query-time synonym analyzer |
| `check_translation_progress.py` | Progress of the Arabic translation batches |

## `data/`

Batch inputs and outputs for translation and tagging work. Gitignored — local only.
