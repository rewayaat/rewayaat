#!/usr/bin/env python3
"""
Add book_ar field to Elasticsearch documents based on book name mapping.

Usage:
    # Step 1: Preview (dry run) — shows what would be updated
    python scripts/add_book_ar_to_es.py --es-host http://localhost:9200 --dry-run

    # Step 2: Apply to ES
    python scripts/add_book_ar_to_es.py --es-host http://localhost:9200

    # Step 3: Apply to production
    python scripts/add_book_ar_to_es.py --es-host http://PROD_HOST:9200 --index rewayaat_updated

Non-destructive: Only adds book_ar field, never overwrites existing fields.
"""

import argparse
import json
import sys
from pathlib import Path

try:
    from elasticsearch import Elasticsearch
except ImportError:
    print("Install elasticsearch-py: pip install elasticsearch")
    sys.exit(1)

SCRIPT_DIR = Path(__file__).resolve().parent
DATA_DIR = SCRIPT_DIR / "data"
MAPPING_FILE = DATA_DIR / "book_names_mapping.json"
OUTPUT_FILE = DATA_DIR / "book_ar_updates.json"


def load_mapping():
    with open(MAPPING_FILE, "r", encoding="utf-8") as f:
        return json.load(f)


def build_reverse_mapping(mapping):
    """Build case-insensitive lookup: normalized_book_name -> ar value"""
    reverse = {}
    for eng, val in mapping.items():
        key = eng.strip().upper()
        reverse[key] = val["ar"]
    return reverse


def main():
    parser = argparse.ArgumentParser(description="Add book_ar field to ES docs")
    parser.add_argument("--es-host", default="http://localhost:9200")
    parser.add_argument("--index", default="rewayaat_updated")
    parser.add_argument("--dry-run", action="store_true", help="Preview only, write to local file")
    args = parser.parse_args()

    mapping = load_mapping()
    reverse = build_reverse_mapping(mapping)

    es = Elasticsearch(args.es_host)

    # Scroll through all docs that have a book field
    updates = []
    query = {"query": {"exists": {"field": "book"}}, "_source": ["book", "book_ar"]}

    resp = es.search(index=args.index, body=query, scroll="5m", size=500)
    scroll_id = resp["_scroll_id"]
    hits = resp["hits"]["hits"]

    matched = 0
    skipped_already = 0
    skipped_unknown = 0

    while hits:
        for hit in hits:
            doc_id = hit["_id"]
            source = hit.get("_source", {})
            book = source.get("book", "")
            existing_ar = source.get("book_ar")

            if existing_ar:
                skipped_already += 1
                continue

            # Try exact match first, then case-insensitive
            ar_name = reverse.get(book.strip().upper())

            if ar_name:
                updates.append({"_id": doc_id, "book": book, "book_ar": ar_name})
                matched += 1
            else:
                skipped_unknown += 1

        resp = es.scroll(scroll_id=scroll_id, scroll="5m")
        scroll_id = resp["_scroll_id"]
        hits = resp["hits"]["hits"]

    # Clear scroll
    try:
        es.clear_scroll(scroll_id=scroll_id)
    except Exception:
        pass

    print(f"Matched: {matched}")
    print(f"Skipped (already has book_ar): {skipped_already}")
    print(f"Skipped (unknown book name): {skipped_unknown}")

    # Write to local file
    with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
        json.dump(updates, f, ensure_ascii=False, indent=2)
    print(f"\nWritten {len(updates)} updates to {OUTPUT_FILE}")

    if args.dry_run:
        print("\nDry run — no ES changes made. Review the output file and re-run without --dry-run.")
        return

    # Apply to ES
    if not updates:
        print("No updates to apply.")
        return

    print(f"\nApplying {len(updates)} updates to ES index '{args.index}'...")
    from elasticsearch.helpers import bulk

    actions = []
    for u in updates:
        actions.append({
            "_op_type": "update",
            "_index": args.index,
            "_id": u["_id"],
            "doc": {"book_ar": u["book_ar"]}
        })

    success, errors = bulk(es, actions, raise_on_error=False)
    print(f"Updated: {success}")
    if errors:
        print(f"Errors: {len(errors)}")
        for err in errors[:5]:
            print(f"  {err}")


if __name__ == "__main__":
    main()
