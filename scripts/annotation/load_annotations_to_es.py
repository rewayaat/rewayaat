#!/usr/bin/env python3
"""Load annotation results from agent pipeline into Elasticsearch.

Reads result files from tmp/hadith-annotation/results/ and bulk updates
the rewayaat_updated index with english_annotated, arabic_annotated,
footnotes, and translation_suggestions fields.

Usage:
    python3 scripts/load_annotations_to_es.py --dry-run
    python3 scripts/load_annotations_to_es.py --live
    python3 scripts/load_annotations_to_es.py --live --resume
    python3 scripts/load_annotations_to_es.py --live --es-host http://PROD_HOST:9200
"""

import argparse
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from elasticsearch import Elasticsearch, helpers

RESULTS_DIR = Path("tmp/hadith-annotation/results")
CHECKPOINT_FILE = "tmp/hadith-annotation/_load_progress.json"
ES_INDEX = "rewayaat_updated"
BATCH_SIZE = 200


def ensure_mapping(es):
    """Add annotation fields to ES mapping if they don't exist."""
    mapping = es.indices.get_mapping(index=ES_INDEX)
    props = mapping[ES_INDEX]["mappings"].get("properties", {})

    new_fields = {}

    if "english_annotated" not in props:
        new_fields["english_annotated"] = {"type": "text", "index": False}

    if "arabic_annotated" not in props:
        new_fields["arabic_annotated"] = {"type": "text", "index": False}

    if "footnotes" not in props:
        new_fields["footnotes"] = {
            "type": "nested",
            "dynamic": False,
            "properties": {
                "id": {"type": "integer"},
                "term": {"type": "keyword"},
                "note": {"type": "text", "index": False},
            },
        }

    if "translation_suggestions" not in props:
        new_fields["translation_suggestions"] = {
            "type": "nested",
            "dynamic": False,
            "properties": {
                "original": {"type": "text", "index": False},
                "suggested": {"type": "text", "index": False},
                "reason": {"type": "text", "index": False},
            },
        }

    if new_fields:
        print(f"  Adding fields to mapping: {list(new_fields.keys())}")
        es.indices.put_mapping(index=ES_INDEX, body={"properties": new_fields})
        print("  Mapping updated.")
    else:
        print("  All annotation fields already exist in mapping.")


def load_checkpoint():
    if os.path.exists(CHECKPOINT_FILE):
        with open(CHECKPOINT_FILE) as f:
            return json.load(f)
    return {"loaded_ids": [], "total_done": 0}


def save_checkpoint(cp):
    tmp = CHECKPOINT_FILE + ".tmp"
    with open(tmp, "w") as f:
        json.dump(cp, f)
    os.rename(tmp, CHECKPOINT_FILE)


def collect_results():
    """Read all result files and return dict of hadith_id -> annotation data."""
    annotations = {}
    result_files = sorted(RESULTS_DIR.glob("batch_*_results.json"))

    for rf in result_files:
        try:
            with open(rf) as f:
                data = json.load(f)
        except Exception as e:
            print(f"  WARNING: Could not read {rf.name}: {e}")
            continue

        if not isinstance(data, list):
            data = [data]

        for item in data:
            hid = item.get("hadith_id") or item.get("id")
            if not hid:
                continue
            annotations[hid] = {
                "english_annotated": item.get("english_annotated", ""),
                "arabic_annotated": item.get("arabic_annotated", ""),
                "footnotes": item.get("footnotes", []),
                "translation_suggestions": item.get("translation_suggestions", []),
            }

    return annotations


def dry_run(annotations):
    print(f"\n=== DRY RUN ===")
    print(f"Total annotations to load: {len(annotations)}")

    # Stats
    with_eng = sum(1 for v in annotations.values() if v["english_annotated"])
    with_ara = sum(1 for v in annotations.values() if v["arabic_annotated"])
    with_fn = sum(1 for v in annotations.values() if v["footnotes"])
    with_ts = sum(1 for v in annotations.values() if v["translation_suggestions"])
    total_fns = sum(len(v["footnotes"]) for v in annotations.values())
    total_ts = sum(len(v["translation_suggestions"]) for v in annotations.values())

    print(f"  With english_annotated: {with_eng}")
    print(f"  With arabic_annotated: {with_ara}")
    print(f"  With footnotes: {with_fn} ({total_fns} total notes)")
    print(f"  With translation_suggestions: {with_ts} ({total_ts} total)")

    # Show 2 samples
    for hid in list(annotations.keys())[:2]:
        ann = annotations[hid]
        print(f"\n  Sample: {hid}")
        if ann["footnotes"]:
            print(f"    Footnotes: {[f['term'] for f in ann['footnotes'][:3]]}")
        if ann["translation_suggestions"]:
            print(f"    Translation suggestions: {len(ann['translation_suggestions'])}")
        if ann["english_annotated"]:
            # Show first annotation tags found
            tags = []
            for tag in ["god-ref", "quran-ref", "fn-word"]:
                count = ann["english_annotated"].count(f'class="{tag}')
                if count:
                    tags.append(f"{tag}:{count}")
            print(f"    Annotations: {', '.join(tags)}")


def live_load(annotations, es, resume=False):
    cp = load_checkpoint() if resume else {"loaded_ids": [], "total_done": 0}
    already_loaded = set(cp.get("loaded_ids", []))

    todo = {k: v for k, v in annotations.items() if k not in already_loaded}
    print(f"\n=== LIVE LOAD ===")
    print(f"Already loaded: {len(already_loaded)}, To load: {len(todo)}")

    if not todo:
        print("Nothing to load!")
        return

    ids_to_load = list(todo.keys())
    batch_num = 0
    total_updated = len(already_loaded)

    for i in range(0, len(ids_to_load), BATCH_SIZE):
        batch = ids_to_load[i : i + BATCH_SIZE]
        batch_num += 1

        actions = []
        for hid in batch:
            ann = todo[hid]
            if not es.exists(index=ES_INDEX, id=hid):
                continue
            doc = {}
            if ann["english_annotated"]:
                doc["english_annotated"] = ann["english_annotated"]
            if ann["arabic_annotated"]:
                doc["arabic_annotated"] = ann["arabic_annotated"]
            if ann["footnotes"]:
                doc["footnotes"] = ann["footnotes"]
            if ann["translation_suggestions"]:
                doc["translation_suggestions"] = ann["translation_suggestions"]

            if not doc:
                continue

            actions.append(
                {
                    "_op_type": "update",
                    "_index": ES_INDEX,
                    "_id": hid,
                    "doc": doc,
                }
            )

        if not actions:
            continue

        try:
            success, errors = helpers.bulk(es, actions, raise_on_error=False)
            if errors:
                err_count = len(
                    [e for e in errors if e.get("update", {}).get("status", 200) != 200]
                )
                if err_count:
                    print(f"  Batch {batch_num}: {err_count} errors")
            total_updated += success
        except Exception as e:
            print(f"  Batch {batch_num} FAILED: {e}")
            cp["loaded_ids"] = list(already_loaded)
            cp["total_done"] = total_updated
            save_checkpoint(cp)
            print(f"  Checkpoint saved. Re-run with --resume.")
            return

        already_loaded.update(batch)
        if batch_num % 10 == 0 or i + BATCH_SIZE >= len(ids_to_load):
            print(f"  Batch {batch_num}: {success} updated, total {total_updated}/{len(annotations)}")
            cp["loaded_ids"] = list(already_loaded)
            cp["total_done"] = total_updated
            save_checkpoint(cp)

    print(f"\nDONE: {total_updated} documents updated.")
    if os.path.exists(CHECKPOINT_FILE):
        os.remove(CHECKPOINT_FILE)


def main():
    parser = argparse.ArgumentParser(description="Load annotations to ES")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--live", action="store_true")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--es-host", default="http://localhost:9200")
    args = parser.parse_args()

    if not args.dry_run and not args.live:
        parser.print_help()
        return

    print("Collecting results...")
    annotations = collect_results()
    print(f"Found {len(annotations)} annotated hadith from {len(list(RESULTS_DIR.glob('batch_*_results.json')))} result files")

    es = Elasticsearch([args.es_host], request_timeout=60)

    if args.dry_run:
        dry_run(annotations)

    if args.live:
        ensure_mapping(es)
        live_load(annotations, es, resume=args.resume)


if __name__ == "__main__":
    main()
