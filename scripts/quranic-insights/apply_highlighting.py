#!/usr/bin/env python3
"""Apply <em> tag highlighting to rewayaat_quranic_light_filtered ES index.

Non-destructive: only ADDS commentary_text_highlighted to snippets that don't have one yet.
Never overwrites existing commentary_text_highlighted values.
Matches by (hadith_id, verse_key, tafsir_slug) triplet.

Usage:
    python3 scripts/quranic-insights/apply_highlighting.py              # Run for real
    python3 scripts/quranic-insights/apply_highlighting.py --dry-run    # Preview only
"""

import json
import os
import sys
from elasticsearch import Elasticsearch, helpers

ES_HOST = os.environ.get("ELASTICSEARCH_URL", "http://localhost:9200")
LIGHT_INDEX = "rewayaat_quranic_light_filtered"
INPUT_DIR = "tmp/qlight-highlight-outputs"
BATCH_SIZE = 200
DRY_RUN = "--dry-run" in sys.argv


def load_highlights():
    """Load all highlight output files into a lookup dict keyed by (hadith_id, verse_key, tafsir_slug)."""
    lookup = {}
    file_count = 0
    entry_count = 0

    for fn in sorted(os.listdir(INPUT_DIR)):
        if not fn.endswith(".jsonl"):
            continue
        fp = os.path.join(INPUT_DIR, fn)
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
                highlighted = entry.get("commentary_text_highlighted", "")

                if not hid or not vk or not highlighted:
                    continue

                key = (hid, vk, slug)
                if key not in lookup:
                    lookup[key] = highlighted
                    entry_count += 1

    print(f"Loaded {entry_count} highlights from {file_count} files")
    return lookup


def main():
    print(f"Apply Highlighting Script")
    print(f"  Index: {LIGHT_INDEX}")
    print(f"  Input dir: {INPUT_DIR}")
    print(f"  Dry run: {DRY_RUN}")

    es = Elasticsearch(ES_HOST)

    # Step 1: Load highlights
    print("\nStep 1: Loading highlights...")
    highlight_lookup = load_highlights()
    print(f"  Unique entries: {len(highlight_lookup)}")

    # Step 2: Scan ES and apply
    print("\nStep 2: Scanning ES index and applying highlights...")
    actions = []
    scanned = 0
    updated_docs = 0
    updated_snippets = 0
    already_had = 0

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

                # Non-destructive: skip if already has commentary_text_highlighted with <em>
                existing = snippet.get("commentary_text_highlighted", "")
                if existing and "<em>" in existing:
                    already_had += 1
                    continue

                key = (hadith_id, vk, slug)
                if key in highlight_lookup:
                    snippet["commentary_text_highlighted"] = highlight_lookup[key]
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
                print(f"  Bulk: {success} ok, {failed} errors ({scanned} scanned, {updated_snippets} updated)")
            else:
                print(f"  [DRY RUN] Would update {len(actions)} docs ({scanned} scanned)")
            actions = []

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
    print(f"  Already highlighted: {already_had}")
    print(f"  Not matched: {len(highlight_lookup) - updated_snippets}")


if __name__ == "__main__":
    main()
