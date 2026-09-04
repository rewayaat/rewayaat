#!/usr/bin/env python3
"""Backfill commentary_text in rewayaat_quranic_light_filtered from full rewayaat_tafsir source.

The commentary_text in the light index was truncated to ~600 chars during the original
export/build pipeline. This script reads the full text from rewayaat_tafsir and updates
the light index.

Usage:
    python3 scripts/quranic-insights/backfill_snippet_text.py              # Run for real
    python3 scripts/quranic-insights/backfill_snippet_text.py --dry-run    # Preview only
    python3 scripts/quranic-insights/backfill_snippet_text.py --limit 100  # Only process 100 docs
"""

import json
import os
import sys
from collections import defaultdict
from elasticsearch import Elasticsearch, helpers

ES_HOST = os.environ.get("ELASTICSEARCH_URL", "http://localhost:9200")
LIGHT_INDEX = os.environ.get("BACKFILL_LIGHT_INDEX", "rewayaat_quranic_light_filtered")
TAFSIR_INDEX = os.environ.get("BACKFILL_TAFSIR_INDEX", "rewayaat_tafsir")
BATCH_SIZE = int(os.environ.get("BACKFILL_BATCH_SIZE", "200"))
DRY_RUN = "--dry-run" in sys.argv
LIMIT = int(os.environ.get("BACKFILL_LIMIT", "0"))
if "--limit" in sys.argv:
    LIMIT = int(sys.argv[sys.argv.index("--limit") + 1])


def main():
    print(f"Backfill script")
    print(f"  Light index: {LIGHT_INDEX}")
    print(f"  Tafsir index: {TAFSIR_INDEX}")
    print(f"  Dry run: {DRY_RUN}")
    print(f"  Limit: {LIMIT or 'none'}")

    es = Elasticsearch(ES_HOST)

    # Step 1: Scan light index to collect all unique (verse_key, tafsir_slug) pairs
    print("\nStep 1: Scanning light index for snippet pairs...")
    snippet_pairs = {}  # (verse_key, tafsir_slug) -> max current length
    scanned = 0

    for hit in helpers.scan(es, index=LIGHT_INDEX, query={"query": {"match_all": {}}},
                            _source=["candidates"], scroll="5m", size=200):
        scanned += 1
        source = hit["_source"]
        for candidate in (source.get("candidates") or []):
            vk = candidate.get("verse_key", "")
            if not vk:
                continue
            for snippet in (candidate.get("tafsir_snippets") or []):
                slug = snippet.get("tafsir_slug", "")
                name = snippet.get("tafsir_name", "")
                identifier = slug if slug else name
                if not identifier:
                    continue
                key = (vk, identifier)
                current_len = len(snippet.get("commentary_text", ""))
                if key not in snippet_pairs or current_len > snippet_pairs[key]:
                    snippet_pairs[key] = current_len

        if LIMIT and scanned >= LIMIT:
            break

    print(f"  Scanned: {scanned} docs")
    print(f"  Unique (verse_key, tafsir) pairs: {len(snippet_pairs)}")

    # Step 2: Fetch full tafsir text for all verse keys
    all_verse_keys = list(set(vk for vk, _ in snippet_pairs.keys()))
    print(f"\nStep 2: Fetching tafsir for {len(all_verse_keys)} unique verse keys...")

    tafsir_lookup = {}  # (verse_key, tafsir_slug) -> full commentary_text
    fetched = 0

    for i in range(0, len(all_verse_keys), 100):
        chunk = all_verse_keys[i:i + 100]
        result = es.search(index=TAFSIR_INDEX, size=500, body={
            "query": {
                "bool": {
                    "filter": [
                        {
                            "bool": {
                                "should": [
                                    {"terms": {"verse_key": chunk}},
                                    {"terms": {"verse_keys": chunk}},
                                ],
                                "minimum_should_match": 1,
                            }
                        }
                    ]
                }
            },
            "_source": [
                "verse_key", "verse_keys",
                "tafsir_slug", "tafsir_name",
                "commentary_text", "commentary_text_english",
            ],
        })

        for hit in result["hits"]["hits"]:
            source = hit["_source"]
            verse_keys = source.get("verse_keys") or [source.get("verse_key", "")]
            slug = source.get("tafsir_slug", "")
            name = source.get("tafsir_name", "")
            identifier = slug if slug else name
            if not identifier:
                continue

            # Pick the longest text available
            ct = source.get("commentary_text", "")
            ct_en = source.get("commentary_text_english", "")
            full_text = ct_en if len(ct_en) > len(ct) else ct

            for vk in verse_keys:
                if not vk:
                    continue
                key = (vk, identifier)
                if len(full_text) > len(tafsir_lookup.get(key, "")):
                    tafsir_lookup[key] = full_text

        fetched += len(chunk)
        if fetched % 1000 == 0 or fetched >= len(all_verse_keys):
            print(f"  Fetched {fetched}/{len(all_verse_keys)} verse keys, {len(tafsir_lookup)} tafsir entries")

    print(f"  Tafsir lookup size: {len(tafsir_lookup)} entries")

    # Stats on how many snippets will be extended
    extendable = sum(1 for key, current_len in snippet_pairs.items()
                     if key in tafsir_lookup and len(tafsir_lookup[key]) > current_len)
    print(f"  Snippets to extend: {extendable} out of {len(snippet_pairs)}")

    # Step 3: Scan light index again and build updates
    print("\nStep 3: Building updates...")
    actions = []
    updated_docs = 0
    extended_snippets = 0
    total_extended_chars = 0
    scanned = 0

    for hit in helpers.scan(es, index=LIGHT_INDEX, query={"query": {"match_all": {}}},
                            _source=["candidates"], scroll="5m", size=100):
        scanned += 1
        hid = hit["_id"]
        source = hit["_source"]
        candidates = source.get("candidates", [])

        changed = False
        for candidate in candidates:
            vk = candidate.get("verse_key", "")
            if not vk:
                continue
            for snippet in (candidate.get("tafsir_snippets") or []):
                slug = snippet.get("tafsir_slug", "")
                name = snippet.get("tafsir_name", "")
                identifier = slug if slug else name
                if not identifier:
                    continue
                key = (vk, identifier)
                if key in tafsir_lookup:
                    full_text = tafsir_lookup[key]
                    current_text = snippet.get("commentary_text", "")
                    if len(full_text) > len(current_text):
                        total_extended_chars += (len(full_text) - len(current_text))
                        snippet["commentary_text"] = full_text
                        changed = True
                        extended_snippets += 1

        if changed:
            updated_docs += 1
            actions.append({
                "_op_type": "update",
                "_index": LIGHT_INDEX,
                "_id": hid,
                "doc": {"candidates": candidates},
            })

        if len(actions) >= BATCH_SIZE:
            if not DRY_RUN:
                success, errors = helpers.bulk(es, actions, chunk_size=BATCH_SIZE, raise_on_error=False)
                failed = sum(1 for e in errors if e.get("update", {}).get("error"))
                print(f"  Bulk: {success} ok, {failed} errors ({scanned} scanned, {extended_snippets} snippets extended)")
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
    print(f"  Snippets extended: {extended_snippets}")
    print(f"  Total chars added: {total_extended_chars:,}")


if __name__ == "__main__":
    main()
