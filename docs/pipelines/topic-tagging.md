# Topic tagging (agent pass)

Narrations carry `topic_tags` from the taxonomy in `src/main/resources/static/taxonomy.json`. A slug is taggable unless its entry says `"taggable": false` (the 70 broad category slugs); `import_tags_to_es.py` adds a tag's taggable ancestors on import, so an agent picks the most specific slugs only.

```bash
python3 scripts/tagging/export_for_tagging.py --es-url http://localhost:9200 --index rewayaat_hadith \
    [--books 'A|B'] --batch-size 500 --outdir <work>/input           # batch_NNNN.json, a JSON array
# one Sonnet agent per batch, 7-8 at a time; resume = the first input without an output
python3 scripts/tagging/import_tags_to_es.py --es-url http://localhost:9200 --index rewayaat_hadith --indir <work>/output
```

Validate before importing: `scripts/tagging/collect_tag_outputs.py --work-dir <work>` checks that every output holds exactly its input's ids in order and only taggable slugs, and writes the `tags_batch_NNNN.json` files the importer reads. A failing batch is listed and left out, to be rerun.

## Batches

The export's 500-narration JSON arrays are too large for an agent to read: the first attempt (2026-10-19) came back in 25 seconds with fewer tokens spent than the input holds, and 45% of one batch untagged — the agents had matched keywords with a script. The pass therefore runs on 250-narration JSON-lines batches (`id`, `chapter`, `english` ≤ 700 chars, `arabic` ≤ 450 chars) and a compact list of the 206 taggable slugs (`taggable_slugs.tsv`: slug, English, Arabic, parent), and the prompt forbids code. An output whose agent spent fewer tokens than its input holds is discarded and rerun.

## Prompt

`{INPUT}` and `{OUTPUT}` are filled per batch.

> You assign topic tags to hadith narrations, by reading them. Read the tag list `{SLUGS}` (tab-separated: slug, English name, Arabic name, parent topic) and then `{INPUT}` (one JSON object per line: `id`, `chapter`, `english`, `arabic`), in chunks of 50 lines (Read with offset/limit).
>
> For each narration, choose the slugs from the list that name what the narration is *about*, judged by its English and Arabic text — usually 1 to 3, at most 6. Leave it empty only when no topic in the list fits at all; most narrations fit one. Prefer the most specific slug (its parent is added automatically). The chapter title is a hint, not the answer: tag what the narration itself says. Use only slugs from the list, exactly as written.
>
> **Do not write or run any code, script or search to assign tags** — no python, no grep, no keyword matching. Read every narration and decide yourself. You may use Bash only to append your result lines to the output file.
>
> Write `{OUTPUT}` as JSON lines, one per input line in the same order: `{"id": "<id>", "tags": ["<slug>", ...]}`. After each chunk of 50, append that chunk's 50 lines (e.g. `cat >> {OUTPUT} <<'EOF'`). Do not use the web. Do not modify the input. Final reply: one line of JSON `{"narrations": N, "tagged": N, "untagged": N}`.
