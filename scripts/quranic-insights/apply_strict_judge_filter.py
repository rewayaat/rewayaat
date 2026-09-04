#!/usr/bin/env python3
"""Apply strict judge filter to rewayaat_quranic_light_filtered ES index.

Removes candidates not in the judge's kept set. Deletes docs with 0 candidates.

Usage:
    python3 scripts/apply_strict_judge_filter.py
    python3 scripts/apply_strict_judge_filter.py --dry-run
"""

import json
import os
import sys
from elasticsearch import Elasticsearch, helpers

ES_HOST = os.environ.get("ELASTICSEARCH_URL", "http://localhost:9200")
INDEX = "rewayaat_quranic_light_filtered"
INPUT_DIR = "tmp/qlight-strict-judge-outputs"
DRY_RUN = "--dry-run" in sys.argv


def load_verdicts():
    """Load judge outputs into lookup: hadith_id -> set of kept verse_keys."""
    kept = {}
    file_count = 0
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
                    d = json.loads(line)
                except json.JSONDecodeError:
                    continue
                hid = d.get("hadith_id", "")
                kept_vks = set()
                for k in d.get("kept", []):
                    if isinstance(k, str):
                        kept_vks.add(k)
                    elif isinstance(k, dict):
                        kept_vks.add(k.get("verse_key", ""))
                if hid:
                    kept[hid] = kept_vks
    print(f"Loaded verdicts for {len(kept)} hadith from {file_count} files")
    return kept


def main():
    print(f"Apply Strict Judge Filter")
    print(f"  Index: {INDEX}")
    print(f"  Input dir: {INPUT_DIR}")
    print(f"  Dry run: {DRY_RUN}")

    es = Elasticsearch(ES_HOST)

    # Step 1: Load verdicts
    print("\nStep 1: Loading verdicts...")
    verdicts = load_verdicts()

    # Step 2: Scan ES and remove weak connections
    print("\nStep 2: Scanning ES and removing weak connections...")
    actions = []
    delete_ids = []
    scanned = 0
    docs_updated = 0
    candidates_removed = 0
    snippets_removed = 0
    no_verdict = 0

    for hit in helpers.scan(es, index=INDEX, query={"query": {"match_all": {}}},
                            _source=["hadith_id", "candidates"], size=200, scroll="5m"):
        scanned += 1
        doc_id = hit["_id"]
        src = hit["_source"]
        hid = src.get("hadith_id", "")
        candidates = src.get("candidates", [])

        kept = verdicts.get(hid)
        if kept is None:
            no_verdict += 1
            continue

        new_candidates = []
        changed = False
        for c in candidates:
            vk = c.get("verse_key", "")
            if vk in kept:
                new_candidates.append(c)
            else:
                snippets_removed += len(c.get("tafsir_snippets", []))
                candidates_removed += 1
                changed = True

        if changed:
            if new_candidates:
                src["candidates"] = new_candidates
                docs_updated += 1
                actions.append({
                    "_op_type": "update",
                    "_index": INDEX,
                    "_id": doc_id,
                    "doc": {"candidates": new_candidates},
                })
            else:
                # Doc has no candidates left — delete it
                delete_ids.append(doc_id)
                docs_updated += 1

        if len(actions) >= 200:
            if not DRY_RUN:
                success, errors = helpers.bulk(es, actions, chunk_size=200, raise_on_error=False)
                failed = sum(1 for e in errors if e.get("update", {}).get("error"))
                print(f"  Bulk: {success} ok, {failed} errors ({scanned} scanned)")
            else:
                print(f"  [DRY RUN] Would update {len(actions)} docs")
            actions = []

        # Delete empty docs in batches
        if len(delete_ids) >= 200:
            if not DRY_RUN:
                for did in delete_ids:
                    es.delete(index=INDEX, id=did)
                print(f"  Deleted {len(delete_ids)} empty docs ({scanned} scanned)")
            else:
                print(f"  [DRY RUN] Would delete {len(delete_ids)} empty docs")
            delete_ids = []

    # Flush remaining
    if actions:
        if not DRY_RUN:
            success, errors = helpers.bulk(es, actions, chunk_size=200, raise_on_error=False)
            failed = sum(1 for e in errors if e.get("update", {}).get("error"))
            print(f"  Final bulk: {success} ok, {failed} errors")
        else:
            print(f"  [DRY RUN] Would update {len(actions)} docs")

    if delete_ids:
        if not DRY_RUN:
            for did in delete_ids:
                es.delete(index=INDEX, id=did)
            print(f"  Final delete: {len(delete_ids)} empty docs")
        else:
            print(f"  [DRY RUN] Would delete {len(delete_ids)} empty docs")

    print(f"\nDone!")
    print(f"  Scanned: {scanned} docs")
    print(f"  No verdict (kept as-is): {no_verdict}")
    print(f"  Docs updated: {docs_updated}")
    print(f"  Candidates removed: {candidates_removed}")
    print(f"  Snippets removed: {snippets_removed}")


if __name__ == "__main__":
    main()
