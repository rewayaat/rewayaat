#!/usr/bin/env python3
"""
Data preparation for LLM-generated field translation (no direct API calls).

Prepares batch files for Claude Code sub-agents to translate, then builds
ES update actions from sub-agent outputs.

Target fields:
  notes              → notes_ar (flat field)
  llm_similar.reason → reason_ar (nested array)
  gradings.rationale → rationale_ar (nested array)
  related.title      → title_ar (nested array)
  related.description → description_ar (nested array)

Usage:
    # Step 1: Extract unique texts → batch files for sub-agents
    python scripts/translate_llm_field.py --extract --field notes

    # Step 2: Sub-agents translate batch files (done externally)

    # Step 3: Build ES update actions from translated batches
    python scripts/translate_llm_field.py --build --field notes

    # Step 4: Preview
    python scripts/translate_llm_field.py --apply --field notes --dry-run

    # Step 5: Apply to ES
    python scripts/translate_llm_field.py --apply --field notes

Non-destructive: Only adds _ar fields, never overwrites existing fields.
"""

import argparse
import hashlib
import json
import os
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from scripts.translate_utils import (
    get_es_client, scroll_all, atomic_write_json, load_json_cache,
    ensure_field_mapping, ensure_nested_field, bulk_update_with_checkpoint,
    DATA_DIR,
)

ES_INDEX = "rewayaat_updated"
BATCH_SIZE = 30  # Items per batch file for sub-agents
ES_BATCH_SIZE = 500  # Docs per ES bulk request

# Field configuration: (field_path, ar_field, parent_for_nested, es_field_type)
FIELD_CONFIG = {
    "notes": {
        "flat": True,
        "es_field": "notes",
        "ar_field": "notes_ar",
        "es_type": "text",
    },
    "llm_similar.reason": {
        "flat": False,
        "parent": "llm_similar",
        "sub_field": "reason",
        "ar_field": "reason_ar",
    },
    "gradings.rationale": {
        "flat": False,
        "parent": "gradings",
        "sub_field": "rationale",
        "ar_field": "rationale_ar",
    },
    "related.title": {
        "flat": False,
        "parent": "related",
        "sub_field": "title",
        "ar_field": "title_ar",
    },
    "related.description": {
        "flat": False,
        "parent": "related",
        "sub_field": "description",
        "ar_field": "description_ar",
    },
}


def text_hash(text):
    """Hash text for deduplication."""
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


def extract_field(es, field_name):
    """Extract unique text values from ES for a field.

    Writes:
      - {field}_batches/batch_NNN.json: batch files for sub-agents
      - {field}_hash_to_docs.json: maps text hash → list of doc IDs
      - {field}_unique.json: all unique texts with hashes
    """
    config = FIELD_CONFIG[field_name]
    batch_dir = DATA_DIR / f"{field_name.replace('.', '_')}_batches"
    batch_dir.mkdir(parents=True, exist_ok=True)

    # Unique texts: hash → text
    unique_texts = {}
    # Hash → list of (doc_id, index_in_array) for nested fields
    hash_to_docs = defaultdict(list)

    source_fields = _get_source_fields(field_name)
    total_docs = 0
    total_values = 0

    print(f"Extracting unique texts for '{field_name}'...")
    for doc_id, source in scroll_all(es, ES_INDEX, source_fields=source_fields):
        total_docs += 1

        if config.get("flat"):
            val = source.get(config["es_field"])
            if val and str(val).strip():
                text = str(val).strip()
                h = text_hash(text)
                if h not in unique_texts:
                    unique_texts[h] = text
                hash_to_docs[h].append({"_id": doc_id})
                total_values += 1
        else:
            # Nested field
            parent = source.get(config["parent"], [])
            if not isinstance(parent, list):
                continue
            for idx, item in enumerate(parent):
                if not isinstance(item, dict):
                    continue
                val = item.get(config["sub_field"])
                if val and str(val).strip():
                    text = str(val).strip()
                    h = text_hash(text)
                    if h not in unique_texts:
                        unique_texts[h] = text
                    hash_to_docs[h].append({"_id": doc_id, "idx": idx})
                    total_values += 1

    print(f"  Scanned {total_docs} docs")
    print(f"  Total values: {total_values}")
    print(f"  Unique texts: {len(unique_texts)}")

    # Save mappings
    field_slug = field_name.replace(".", "_")
    atomic_write_json(DATA_DIR / f"{field_slug}_unique.json", {
        h: t for h, t in unique_texts.items()
    })
    atomic_write_json(DATA_DIR / f"{field_slug}_hash_to_docs.json", {
        h: docs for h, docs in hash_to_docs.items()
    })

    # Write batch files for sub-agents
    texts = list(unique_texts.items())  # [(hash, text), ...]
    num_batches = (len(texts) + BATCH_SIZE - 1) // BATCH_SIZE

    for i in range(0, len(texts), BATCH_SIZE):
        batch = texts[i:i + BATCH_SIZE]
        batch_num = i // BATCH_SIZE
        batch_data = [
            {"id": h, "text": t} for h, t in batch
        ]
        batch_file = batch_dir / f"batch_{batch_num:04d}.json"
        atomic_write_json(batch_file, batch_data)

    print(f"  Wrote {num_batches} batch files to {batch_dir}")
    print(f"  Hash→docs mapping: {field_slug}_hash_to_docs.json")
    return num_batches


def _get_source_fields(field_name):
    """Get ES source fields needed for extraction."""
    config = FIELD_CONFIG[field_name]
    if config.get("flat"):
        ar_field = config["ar_field"]
        return [config["es_field"], ar_field]
    else:
        return [config["parent"]]


def build_updates(field_name):
    """Build ES update actions from sub-agent translation outputs.

    Reads all batch_NNN_out.json files from sub-agents, merges into a
    hash→translation cache, then maps to doc IDs.

    Writes:
      - {field}_ar_cache.json: hash → Arabic translation
      - {field}_ar_updates.json: list of update actions for ES
    """
    config = FIELD_CONFIG[field_name]
    field_slug = field_name.replace(".", "_")
    batch_dir = DATA_DIR / f"{field_slug}_batches"

    # Load hash→docs mapping
    hash_to_docs = load_json_cache(DATA_DIR / f"{field_slug}_hash_to_docs.json")
    if not hash_to_docs:
        print(f"  No hash→docs mapping found. Run --extract first.")
        return

    # Load all sub-agent outputs
    cache = load_json_cache(DATA_DIR / f"{field_slug}_ar_cache.json")
    new_translations = 0

    batch_files = sorted(batch_dir.glob("batch_*_out.json"))
    if not batch_files:
        print(f"  No batch output files found in {batch_dir}")
        print(f"  Expected files matching batch_*_out.json")
        return

    for batch_file in batch_files:
        with open(batch_file, "r", encoding="utf-8") as f:
            translations = json.load(f)
        for item in translations:
            h = item.get("id")
            ar = item.get("ar")
            if h and ar:
                if h not in cache:
                    new_translations += 1
                cache[h] = ar

    print(f"  Loaded {len(cache)} translations ({new_translations} new)")

    # Save updated cache
    atomic_write_json(DATA_DIR / f"{field_slug}_ar_cache.json", cache)

    # Build ES update actions
    # For nested fields, we need to group by doc_id and rebuild the full array
    if config.get("flat"):
        updates = _build_flat_updates(field_name, cache, hash_to_docs)
    else:
        updates = _build_nested_updates(field_name, cache, hash_to_docs)

    # Save updates
    atomic_write_json(DATA_DIR / f"{field_slug}_ar_updates.json", updates)
    print(f"  Built {len(updates)} update actions")
    print(f"  Updates file: {field_slug}_ar_updates.json")


def _build_flat_updates(field_name, cache, hash_to_docs):
    """Build update actions for flat fields (e.g., notes)."""
    config = FIELD_CONFIG[field_name]
    ar_field = config["ar_field"]
    updates = []

    for h, doc_list in hash_to_docs.items():
        if h not in cache:
            continue
        ar_val = cache[h]
        for entry in doc_list:
            updates.append({
                "_id": entry["_id"],
                "doc": {ar_field: ar_val},
            })

    return updates


def _build_nested_updates(field_name, cache, hash_to_docs):
    """Build update actions for nested fields.

    For nested fields, we need to read the full parent array from ES,
    add _ar fields to each element, and write back the full array.
    This requires a separate ES read pass.
    """
    config = FIELD_CONFIG[field_name]
    parent = config["parent"]
    sub_field = config["sub_field"]
    ar_field = config["ar_field"]

    # Collect doc IDs that need updating
    docs_needing_update = set()
    for h, doc_list in hash_to_docs.items():
        if h in cache:
            for entry in doc_list:
                docs_needing_update.add(entry["_id"])

    print(f"  Docs needing update: {len(docs_needing_update)}")

    # We return a special format that the apply step handles
    # by reading the full doc, modifying the array, and writing back
    updates = []
    for doc_id in sorted(docs_needing_update):
        # Collect which hash→ar mappings apply to this doc
        doc_translations = {}
        for h, doc_list in hash_to_docs.items():
            if h in cache:
                for entry in doc_list:
                    if entry["_id"] == doc_id:
                        doc_translations[h] = cache[h]
        if doc_translations:
            updates.append({
                "_id": doc_id,
                "_type": "nested",
                "parent_field": parent,
                "sub_field": sub_field,
                "ar_field": ar_field,
                "translations": doc_translations,
            })

    return updates


def apply_updates(es, field_name, dry_run=False):
    """Apply translations to ES."""
    config = FIELD_CONFIG[field_name]
    field_slug = field_name.replace(".", "_")
    updates_file = DATA_DIR / f"{field_slug}_ar_updates.json"

    if not updates_file.exists():
        print(f"  No updates file found. Run --build first.")
        return

    updates = load_json_cache(updates_file)
    if not updates:
        print(f"  No updates to apply.")
        return

    print(f"  Loaded {len(updates)} updates")

    if dry_run:
        print(f"  Dry run — no ES changes.")
        # Show samples
        for u in updates[:3]:
            print(f"    {u['_id']}: {json.dumps(u['doc'] if 'doc' in u else u.get('translations', {}), ensure_ascii=False)[:100]}...")
        return

    # Separate flat and nested updates
    flat_updates = [u for u in updates if u.get("_type") != "nested"]
    nested_updates = [u for u in updates if u.get("_type") == "nested"]

    if flat_updates:
        _apply_flat_updates(es, field_name, flat_updates)

    if nested_updates:
        _apply_nested_updates(es, field_name, nested_updates)


def _apply_flat_updates(es, field_name, updates):
    """Apply flat field updates to ES."""
    config = FIELD_CONFIG[field_name]
    ar_field = config["ar_field"]

    # Ensure mapping
    ensure_field_mapping(es, ES_INDEX, ar_field, config.get("es_type", "text"))

    checkpoint_path = DATA_DIR / f"{field_name.replace('.', '_')}_ar_checkpoint.json"
    success, errors = bulk_update_with_checkpoint(
        es, ES_INDEX, updates, str(checkpoint_path),
        batch_size=ES_BATCH_SIZE,
    )
    print(f"  Flat updates: {success} ok, {errors} errors")


def _apply_nested_updates(es, field_name, updates):
    """Apply nested field updates to ES.

    For each doc, reads the full parent array, adds _ar to each element,
    writes back the entire array.
    """
    from elasticsearch import helpers

    config = FIELD_CONFIG[field_name]
    parent = config["parent"]
    sub_field = config["sub_field"]
    ar_field = config["ar_field"]

    # Ensure nested mapping
    ensure_nested_field(es, ES_INDEX, parent, ar_field)

    checkpoint_path = DATA_DIR / f"{field_name.replace('.', '_')}_ar_checkpoint.json"
    cp = load_json_cache(str(checkpoint_path))
    done_ids = set(cp.get("done_ids", []))

    remaining = [u for u in updates if u["_id"] not in done_ids]
    print(f"  Nested updates: {len(remaining)} remaining ({len(done_ids)} done)")

    success_total = 0
    error_total = 0

    for i in range(0, len(remaining), ES_BATCH_SIZE):
        batch = remaining[i:i + ES_BATCH_SIZE]
        actions = []

        for u in batch:
            doc_id = u["_id"]
            translations = u["translations"]

            # Read full parent array from ES
            try:
                doc = es.get(index=ES_INDEX, id=doc_id, source_includes=[parent])
                parent_arr = doc["_source"].get(parent, [])
            except Exception as e:
                print(f"  ERROR reading {doc_id}: {e}")
                continue

            if not isinstance(parent_arr, list):
                continue

            # Add _ar to matching elements
            modified = False
            for item in parent_arr:
                if not isinstance(item, dict):
                    continue
                val = item.get(sub_field)
                if val and str(val).strip():
                    h = text_hash(str(val).strip())
                    if h in translations:
                        item[ar_field] = translations[h]
                        modified = True

            if modified:
                actions.append({
                    "_op_type": "update",
                    "_index": ES_INDEX,
                    "_id": doc_id,
                    "doc": {parent: parent_arr},
                })

        if actions:
            try:
                success, errors = helpers.bulk(es, actions, raise_on_error=False)
                success_total += success
                err_count = len([e for e in errors if isinstance(e, dict) and
                               e.get("update", {}).get("status", 200) != 200])
                error_total += err_count
            except Exception as e:
                print(f"  Batch FAILED: {e}")

        done_ids.update(u["_id"] for u in batch)
        # Checkpoint every batch
        atomic_write_json(str(checkpoint_path), {
            "done_ids": list(done_ids),
            "total_done": success_total,
        })
        print(f"  Batch {i // ES_BATCH_SIZE + 1}: {success_total} updated")

    # Cleanup checkpoint
    if os.path.exists(str(checkpoint_path)):
        os.remove(str(checkpoint_path))

    print(f"  Nested updates done: {success_total} ok, {error_total} errors")


def main():
    parser = argparse.ArgumentParser(description="LLM field translation data prep")
    parser.add_argument("--es-host", default="http://localhost:9200")
    parser.add_argument("--index", default=ES_INDEX)
    parser.add_argument("--field", required=True,
                       choices=list(FIELD_CONFIG.keys()),
                       help="Field to translate")
    parser.add_argument("--extract", action="store_true",
                       help="Extract unique texts → batch files")
    parser.add_argument("--build", action="store_true",
                       help="Build ES updates from sub-agent outputs")
    parser.add_argument("--apply", action="store_true",
                       help="Apply updates to ES")
    parser.add_argument("--dry-run", action="store_true",
                       help="Preview only, no ES writes")
    args = parser.parse_args()

    global ES_INDEX
    ES_INDEX = args.index

    es = get_es_client(args.es_host)

    if args.extract:
        extract_field(es, args.field)
    elif args.build:
        build_updates(args.field)
    elif args.apply:
        apply_updates(es, args.field, dry_run=args.dry_run)
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
