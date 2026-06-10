#!/usr/bin/env python3
"""
Extract unique values for Tier 1 fields from ES, and apply Arabic mappings.

Tier 1 fields have a finite number of unique values (keyword type):
  chapter, section, part, publisher, edition, source

Usage:
    # Step 1: Extract unique values from ES → mapping template files
    python scripts/translate_tier1.py --extract

    # Step 2: (Manual) Fill Arabic translations in mapping files
    #   Or use Claude Code sub-agents to translate

    # Step 3: Preview what would be applied
    python scripts/translate_tier1.py --apply --dry-run

    # Step 4: Apply to ES
    python scripts/translate_tier1.py --apply

    # Stats only
    python scripts/translate_tier1.py --stats

Non-destructive: Only adds _ar fields, never overwrites existing fields.
"""

import argparse
import json
import os
import sys
from collections import Counter
from pathlib import Path

# Add project root to path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from scripts.translate_utils import (
    get_es_client, scroll_all, atomic_write_json, load_json_cache,
    ensure_field_mapping, bulk_update_with_checkpoint,
    DATA_DIR, SCRIPT_DIR,
)

ES_INDEX = "rewayaat_updated"

TIER1_FIELDS = ["chapter", "section", "part", "publisher", "edition", "source"]

BATCH_SIZE = 500


def extract_unique_values(es, index, fields=None):
    """Extract unique values for each field from ES."""
    fields = fields or TIER1_FIELDS
    source_fields = fields + [f"{f}_ar" for f in fields]

    # Collect unique values and count occurrences
    unique_values = {f: Counter() for f in fields}
    docs_with_field = {f: 0 for f in fields}
    docs_with_ar = {f: 0 for f in fields}
    total_docs = 0

    print(f"Scrolling {index} for unique values...")
    for doc_id, source in scroll_all(es, index, source_fields=source_fields):
        total_docs += 1
        for field in fields:
            val = source.get(field)
            if val and str(val).strip():
                unique_values[field][str(val).strip()] += 1
                docs_with_field[field] += 1
            ar_val = source.get(f"{field}_ar")
            if ar_val and str(ar_val).strip():
                docs_with_ar[field] += 1

    print(f"Scanned {total_docs} documents\n")

    # Write mapping template files and print stats
    for field in fields:
        counts = unique_values[field]
        mapping_file = DATA_DIR / f"{field}_ar_mapping.json"

        if not counts:
            print(f"  {field}: no values found")
            continue

        # Check if mapping file already has translations
        existing = load_json_cache(mapping_file)

        # Build mapping template: English -> Arabic (existing or empty)
        mapping = {}
        for val, count in counts.most_common():
            mapping[val] = existing.get(val, "")

        # Write template
        atomic_write_json(mapping_file, mapping)

        filled = sum(1 for v in mapping.values() if v)
        print(f"  {field}:")
        print(f"    Unique values: {len(counts)}")
        print(f"    Docs with field: {docs_with_field[field]}")
        print(f"    Docs already with {field}_ar: {docs_with_ar[field]}")
        print(f"    Mapping file: {mapping_file}")
        print(f"    Translations filled: {filled}/{len(mapping)}")
        print()

    return unique_values


def print_stats(es, index, fields=None):
    """Print unique value counts without writing files."""
    fields = fields or TIER1_FIELDS
    source_fields = fields + [f"{f}_ar" for f in fields]

    unique_values = {f: Counter() for f in fields}
    docs_with_ar = {f: 0 for f in fields}
    total_docs = 0

    for doc_id, source in scroll_all(es, index, source_fields=source_fields):
        total_docs += 1
        for field in fields:
            val = source.get(field)
            if val and str(val).strip():
                unique_values[field][str(val).strip()] += 1
            ar_val = source.get(f"{field}_ar")
            if ar_val and str(ar_val).strip():
                docs_with_ar[field] += 1

    print(f"Index: {index}, Total docs: {total_docs}\n")
    for field in fields:
        counts = unique_values[field]
        if not counts:
            print(f"  {field}: no values")
            continue
        print(f"  {field}: {len(counts)} unique values, {sum(counts.values())} docs, {docs_with_ar[field]} already have {field}_ar")
        # Show top 5
        for val, count in counts.most_common(5):
            print(f"    \"{val[:60]}\" ({count})")
        if len(counts) > 5:
            print(f"    ... and {len(counts) - 5} more")
        print()


def apply_mappings(es, index, fields=None, dry_run=False):
    """Read mapping files and apply _ar fields to ES."""
    fields = fields or TIER1_FIELDS

    for field in fields:
        mapping_file = DATA_DIR / f"{field}_ar_mapping.json"
        if not mapping_file.exists():
            print(f"  {field}: mapping file not found ({mapping_file}), skipping")
            continue

        mapping = load_json_cache(mapping_file)

        # Check how many are actually translated
        filled = {k: v for k, v in mapping.items() if v}
        if not filled:
            print(f"  {field}: no translations in mapping file, skipping")
            continue

        print(f"\n{field}: {len(filled)} translations in mapping")

        # Scroll ES and build update actions
        source_fields = [field, f"{field}_ar"]
        actions = []
        skipped_ar = 0
        skipped_no_match = 0

        for doc_id, source in scroll_all(es, index, source_fields=source_fields):
            val = source.get(field)
            if not val or not str(val).strip():
                continue
            existing_ar = source.get(f"{field}_ar")
            if existing_ar and str(existing_ar).strip():
                skipped_ar += 1
                continue

            ar_val = filled.get(str(val).strip())
            if not ar_val:
                skipped_no_match += 1
                continue

            actions.append({"_id": doc_id, "doc": {f"{field}_ar": ar_val}})

        print(f"  Updates: {len(actions)}, Already has {field}_ar: {skipped_ar}, No mapping: {skipped_no_match}")

        # Write local preview
        preview_file = DATA_DIR / f"{field}_ar_updates.json"
        atomic_write_json(preview_file, [{"_id": a["_id"], field: a["doc"][f"{field}_ar"]} for a in actions])
        print(f"  Preview: {preview_file}")

        if dry_run:
            print(f"  Dry run — no ES changes.")
            continue

        if not actions:
            print(f"  No updates to apply.")
            continue

        # Ensure mapping exists
        field_type = "keyword" if field in TIER1_FIELDS else "text"
        added = ensure_field_mapping(es, index, f"{field}_ar", field_type)
        if added:
            print(f"  Added {field}_ar to ES mapping ({field_type})")

        # Apply with checkpoint
        checkpoint_path = DATA_DIR / f"{field}_ar_checkpoint.json"
        success, errors = bulk_update_with_checkpoint(
            es, index, actions, str(checkpoint_path),
            batch_size=BATCH_SIZE,
        )
        print(f"  Applied: {success} updated, {errors} errors")


def main():
    parser = argparse.ArgumentParser(description="Tier 1 field translation (static mappings)")
    parser.add_argument("--es-host", default="http://localhost:9200")
    parser.add_argument("--index", default=ES_INDEX)
    parser.add_argument("--fields", default=None, help="Comma-separated fields (default: all Tier 1)")
    parser.add_argument("--extract", action="store_true", help="Extract unique values to mapping templates")
    parser.add_argument("--stats", action="store_true", help="Print unique value stats")
    parser.add_argument("--apply", action="store_true", help="Apply mappings to ES")
    parser.add_argument("--dry-run", action="store_true", help="Preview only, no ES writes")
    args = parser.parse_args()

    index_name = args.index

    fields = args.fields.split(",") if args.fields else TIER1_FIELDS

    es = get_es_client(args.es_host)

    if args.extract:
        extract_unique_values(es, index_name, fields)
    elif args.stats:
        print_stats(es, index_name, fields)
    elif args.apply:
        apply_mappings(es, index_name, fields, dry_run=args.dry_run)
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
