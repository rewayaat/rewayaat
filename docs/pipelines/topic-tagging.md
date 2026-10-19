# Topic tagging (agent pass)

Narrations carry `topic_tags` from the taxonomy in `src/main/resources/static/taxonomy.json`. A slug is taggable unless its entry says `"taggable": false` (the 70 broad category slugs); `import_tags_to_es.py` adds a tag's taggable ancestors on import, so an agent picks the most specific slugs only.

```bash
python3 scripts/tagging/export_for_tagging.py --es-url http://localhost:9200 --index rewayaat_hadith \
    [--books 'A|B'] --batch-size 500 --outdir <work>/input           # batch_NNNN.json, a JSON array
# one Sonnet agent per batch, 7-8 at a time; resume = the first input without an output
python3 scripts/tagging/import_tags_to_es.py --es-url http://localhost:9200 --index rewayaat_hadith --indir <work>/output
```

Validate before importing: every output holds exactly its input's ids, and every slug exists and is taggable.

## Prompt

`{INPUT}` and `{OUTPUT}` are filled per batch.

> You assign topic tags to hadith narrations. Read `{INPUT}` (a JSON array; each item has `_id`, `english`, `arabic`, `book`, `chapter`, `section`) and the taxonomy `/home/zir0/git/rewayaat-lost-books/src/main/resources/static/taxonomy.json` (a list of `{slug, en, ar, category, parent?, taggable?}`). A slug is taggable unless its entry has `"taggable": false`.
>
> For each narration, choose the taggable slugs that name what the narration is *about*, judged by its English and Arabic text — usually 1 to 3, at most 6, and none when nothing fits. Prefer the most specific slug (a child over its parent: the parent is added automatically). The chapter title is a hint, not the answer: tag what the narration itself says. Never use a slug whose `taggable` is false, and never invent one.
>
> Write `{OUTPUT}` as `{"assignments": [{"id": "<_id>", "tags": ["<slug>", ...]}]}`, one entry per input narration, in input order, every `_id` exactly once. Read the input in chunks (Read with offset/limit, about 800 lines at a time) and build the output as you go, writing it with Write at the end. Do not use the web. Do not modify the input. Final reply: one line of JSON `{"narrations": N, "tagged": N, "untagged": N}`.
