#!/usr/bin/env python3
"""Apply extracted relevant_excerpt fields to rewayaat_quranic_light_filtered ES index.

Non-destructive: only ADDS relevant_excerpt to snippets that don't have one yet.
Never overwrites existing relevant_excerpt values.
Matches by (hadith_id, verse_key, tafsir_slug) triplet.

Usage:
    python3 scripts/quranic-insights/apply_excerpts.py              # Run for real
    python3 scripts/quranic-insights/apply_excerpts.py --dry-run    # Preview only
    python3 scripts/quranic-insights/apply_excerpts.py --limit 100  # Only process 100 docs
"""

import json
import os
import sys
from elasticsearch import Elasticsearch, helpers

ES_HOST = os.environ.get("ELASTICSEARCH_URL", "http://localhost:9200")
LIGHT_INDEX = os.environ.get("EXCERPT_INDEX", "rewayaat_quranic_light_filtered")
OUTPUT_DIR = os.environ.get("EXCERPT_OUTPUT_DIR", "tmp/qlight-excerpt-outputs")
BATCH_SIZE = int(os.environ.get("EXCERPT_BATCH_SIZE", "200"))
DRY_RUN = "--dry-run" in sys.argv
LIMIT = int(os.environ.get("EXCERPT_LIMIT", "0"))
if "--limit" in sys.argv:
    LIMIT = int(sys.argv[sys.argv.index("--limit") + 1])


def load_excerpts():
    """Load all excerpt output files into a lookup dict keyed by (hadith_id, verse_key, tafsir_slug)."""
    lookup = {}
    file_count = 0
    entry_count = 0
    skip_count = 0

    for fn in sorted(os.listdir(OUTPUT_DIR)):
        if not fn.endswith(".jsonl"):
            continue
        fp = os.path.join(OUTPUT_DIR, fn)
        file_count += 1
        with open(fp) as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    entry = json.loads(line)
                except json.JSONDecodeError:
                    continue

                hid = entry.get("hadith_id", "")
                vk = entry.get("verse_key", "")
                slug = entry.get("tafsir_slug", "")
                excerpt = entry.get("relevant_excerpt")
                skipped = entry.get("skipped_reason")

                if not hid or not vk:
                    continue

                key = (hid, vk, slug)
                if skipped:
                    skip_count += 1
                    # Don't store skipped entries
                    continue
                if excerpt:
                    lookup[key] = excerpt
                    entry_count += 1

    print(f"Loaded {entry_count} excerpts from {file_count} files ({skip_count} skipped)")
    return lookup


def main():
    print(f"Apply Excerpts Script")
    print(f"  Index: {LIGHT_INDEX}")
    print(f"  Output dir: {OUTPUT_DIR}")
    print(f"  Dry run: {DRY_RUN}")
    print(f"  Limit: {LIMIT or 'none'}")

    es = Elasticsearch(ES_HOST)

    # Step 1: Load all excerpts
    print("\nStep 1: Loading excerpts...")
    excerpt_lookup = load_excerpts()
    print(f"  Unique (hadith_id, verse_key, tafsir_slug) entries: {len(excerpt_lookup)}")

    # Step 2: Scan ES and apply excerpts
    print("\nStep 2: Scanning ES index and applying excerpts...")
    actions = []
    scanned = 0
    updated_docs = 0
    updated_snippets = 0
    already_had_excerpt = 0
    no_match = 0

    for hit in helpers.scan(es, index=LIGHT_INDEX, query={"query": {"match_all": {}}},
                            _source=["hadith_id", "candidates"], scroll="5m", size=200):
        scanned += 1
        doc_id = hit["_id"]
        source = hit["_source"]
        hadith_id = source.get("hadith_id", doc_id)
        candidates = source.get("candidates", [])

        changed = False
        for candidate in candidates:
            vk = candidate.get("verse_key", "")
            if not vk:
                continue
            for snippet in (candidate.get("tafsir_snippets") or []):
                slug = snippet.get("tafsir_slug", "")
                if not slug:
                    continue

                # Non-destructive: skip if already has relevant_excerpt
                if snippet.get("relevant_excerpt"):
                    already_had_excerpt += 1
                    continue

                key = (hadith_id, vk, slug)
                if key in excerpt_lookup:
                    snippet["relevant_excerpt"] = excerpt_lookup[key]
                    changed = True
                    updated_snippets += 1

        if changed:
            updated_docs += 1
            actions.append({
                "_op_type": "update",
                "_index": LIGHT_INDEX,
                "_id": doc_id,
                "doc": {"candidates": candidates},
            })

        if len(actions) >= BATCH_SIZE:
            if not DRY_RUN:
                success, errors = helpers.bulk(es, actions, chunk_size=BATCH_SIZE, raise_on_error=False)
                failed = sum(1 for e in errors if e.get("update", {}).get("error"))
                print(f"  Bulk: {success} ok, {failed} errors ({scanned} scanned, {updated_snippets} snippets updated)")
            else:
                print(f"  [DRY RUN] Would update {len(actions)} docs ({scanned} scanned)")
            actions = []

        if LIMIT and scanned >= LIMIT:
            break

    # Flush remaining
    if actions:
        if not DRY_RUN:
            success, errors = helpers.bulk(es, actions, chunk_size=BATCH_SIZE, raise_on_error=False)
            failed = sum(1 for e in errors if e.get("update", {}).get("error"))
            print(f"  Final bulk: {success} ok, {failed} errors")
        else:
            print(f"  [DRY RUN] Would update {len(actions)} docs")

    print(f"\nDone!")
    print(f"  Scanned: {scanned} docs")
    print(f"  Updated docs: {updated_docs}")
    print(f"  Updated snippets: {updated_snippets}")
    print(f"  Already had excerpt: {already_had_excerpt}")
    print(f"  Excerpts not matched: {len(excerpt_lookup) - updated_snippets}")


if __name__ == "__main__":
    main()
