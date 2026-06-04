#!/usr/bin/env python3
"""Load LLM-judged similar hadith pairs from pairs_cache.json into Elasticsearch.

Usage:
    python3 scripts/load_llm_similar_to_es.py --dry-run
    python3 scripts/load_llm_similar_to_es.py --live
    python3 scripts/load_llm_similar_to_es.py --live --resume

Safety:
    - Reads tmp/pairs_cache.json (READ-ONLY)
    - Uses ES _update API (partial update, never overwrites existing fields)
    - Only adds new 'llm_similar' field
    - Checkpoint file for resume support
"""

import argparse
import json
import os
import sys
import time
from collections import defaultdict
from pathlib import Path

# Add project root to path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from elasticsearch import Elasticsearch, helpers

# --- Config ---
CACHE_FILE = "tmp/pairs_cache.json"
CHECKPOINT_FILE = "tmp/llm_similar_load_progress.json"
ES_INDEX = "rewayaat_updated"
BATCH_SIZE = 500
DRY_RUN_VALIDATE_SAMPLE = 100

# Match type normalization
MATCH_TYPE_MAP = {
    "wording": "wording",
    "wording_similar": "wording",
    "exact": "wording",
    "variant": "wording",
    "conceptual": "conceptual",
    "conceptually_similar": "conceptual",
    "meaning": "conceptual",
    "content": "conceptual",
    "thematic": "thematic",
    "theme": "thematic",
    "event": "thematic",
    "chain": "thematic",
    "": "conceptual",  # default for entries without match_type
}


def build_adjacency(cache):
    """Build per-hadith adjacency list from cache, filtering similar only."""
    adj = defaultdict(dict)  # Use dict to deduplicate by partner ID
    for key, val in cache.items():
        if not isinstance(val, dict) or val.get("verdict") != "similar":
            continue
        parts = key.split("||")
        if len(parts) != 2:
            continue
        raw_mt = val.get("match_type", "")
        match_type = MATCH_TYPE_MAP.get(raw_mt, "conceptual")
        reason = val.get("reason", "")
        # Truncate very long reasons
        if len(reason) > 300:
            reason = reason[:297] + "..."
        entry = {"id": parts[1], "match_type": match_type, "reason": reason}
        reverse_entry = {"id": parts[0], "match_type": match_type, "reason": reason}
        # Use partner ID as key to deduplicate
        if parts[1] not in adj[parts[0]]:
            adj[parts[0]][parts[1]] = entry
        if parts[0] not in adj[parts[1]]:
            adj[parts[1]][parts[0]] = reverse_entry
    # Convert dicts to lists
    return {k: list(v.values()) for k, v in adj.items()}


def load_checkpoint():
    if os.path.exists(CHECKPOINT_FILE):
        with open(CHECKPOINT_FILE) as f:
            return json.load(f)
    return {"loaded_ids": [], "total_done": 0}


def save_checkpoint(cp):
    with open(CHECKPOINT_FILE + ".tmp", "w") as f:
        json.dump(cp, f)
    os.rename(CHECKPOINT_FILE + ".tmp", CHECKPOINT_FILE)


def ensure_mapping(es):
    """Add llm_similar field to ES mapping if it doesn't exist."""
    mapping = es.indices.get_mapping(index=ES_INDEX)
    props = mapping[ES_INDEX]["mappings"].get("properties", {})
    if "llm_similar" in props:
        print("  llm_similar field already exists in mapping.")
        return
    print("  Adding llm_similar field to mapping...")
    es.indices.put_mapping(
        index=ES_INDEX,
        body={
            "properties": {
                "llm_similar": {
                    "type": "nested",
                    "dynamic": False,
                    "properties": {
                        "id": {"type": "keyword"},
                        "match_type": {"type": "keyword"},
                        "reason": {"type": "text", "index": False},
                    },
                }
            }
        },
    )
    print("  Mapping updated.")


def dry_run(adj, es):
    """Validate and print stats without touching ES."""
    print(f"\n=== DRY RUN ===")
    print(f"Total hadith with similar pairs: {len(adj)}")
    total_entries = sum(len(v) for v in adj.values())
    print(f"Total similar entries: {total_entries}")

    # Match type distribution
    mt_counts = defaultdict(int)
    for entries in adj.values():
        for e in entries:
            mt_counts[e["match_type"]] += 1
    print(f"Match type distribution: {dict(mt_counts)}")

    # Per-hadith stats
    counts = [len(v) for v in adj.values()]
    print(f"Avg similar per hadith: {sum(counts)/len(counts):.1f}")
    print(f"Median: {sorted(counts)[len(counts)//2]}, Max: {max(counts)}")

    # Validate IDs exist in ES (spot check)
    all_ids = list(adj.keys())
    import random
    sample_ids = random.sample(all_ids, min(DRY_RUN_VALIDATE_SAMPLE, len(all_ids)))
    missing = 0
    for hid in sample_ids:
        try:
            if not es.exists(index=ES_INDEX, id=hid):
                missing += 1
                print(f"  WARNING: ID not in ES: {hid}")
        except Exception as e:
            print(f"  ERROR checking {hid}: {e}")

    print(f"\nID validation: {len(sample_ids)} checked, {missing} missing")

    # Show 3 sample docs
    print(f"\nSample documents to be loaded:")
    for hid in all_ids[:3]:
        entries = adj[hid]
        print(f"  {hid}: {len(entries)} similar")
        for e in entries[:3]:
            print(f"    -> {e['id']} ({e['match_type']})")

    # Payload estimate
    sample_payload = json.dumps({hid: adj[hid] for hid in all_ids[:100]})
    bytes_per_doc = len(sample_payload.encode()) / 100
    total_bytes = bytes_per_doc * len(adj)
    print(f"\nEstimated total payload: {total_bytes / 1024 / 1024:.1f} MB")
    print(f"Batches: {(len(adj) + BATCH_SIZE - 1) // BATCH_SIZE}")


def live_load(adj, es, resume=False):
    """Load similar hadith data into ES."""
    cp = load_checkpoint() if resume else {"loaded_ids": [], "total_done": 0}
    already_loaded = set(cp.get("loaded_ids", []))

    todo = {k: v for k, v in adj.items() if k not in already_loaded}
    print(f"\n=== LIVE LOAD ===")
    print(f"Already loaded: {len(already_loaded)}, To load: {len(todo)}, Total: {len(adj)}")

    if not todo:
        print("Nothing to load!")
        return

    ids_to_load = list(todo.keys())
    batch_num = 0
    total_updated = len(already_loaded)

    for i in range(0, len(ids_to_load), BATCH_SIZE):
        batch = ids_to_load[i : i + BATCH_SIZE]
        batch_num += 1

        # Build bulk actions
        actions = []
        for hid in batch:
            entries = adj[hid]
            # Validate ID exists
            if not es.exists(index=ES_INDEX, id=hid):
                print(f"  SKIP {hid}: not in ES")
                continue
            actions.append(
                {
                    "_op_type": "update",
                    "_index": ES_INDEX,
                    "_id": hid,
                    "doc": {"llm_similar": entries},
                }
            )

        if not actions:
            continue

        # Execute bulk update
        try:
            success, errors = helpers.bulk(es, actions, raise_on_error=False)
            if errors:
                err_count = len([e for e in errors if e.get("update", {}).get("status", 200) != 200])
                if err_count:
                    print(f"  Batch {batch_num}: {err_count} errors")
                    for e in errors[:3]:
                        print(f"    {e}")
            total_updated += success
        except Exception as e:
            print(f"  Batch {batch_num} FAILED: {e}")
            # Save checkpoint and continue
            cp["loaded_ids"] = list(already_loaded)
            cp["total_done"] = total_updated
            save_checkpoint(cp)
            print(f"  Checkpoint saved at {total_updated} docs. Re-run with --resume.")
            return

        already_loaded.update(batch)
        if batch_num % 5 == 0 or i + BATCH_SIZE >= len(ids_to_load):
            print(f"  Batch {batch_num}: {success} updated, total {total_updated}/{len(adj)}")
            cp["loaded_ids"] = list(already_loaded)
            cp["total_done"] = total_updated
            save_checkpoint(cp)

    print(f"\nDONE: {total_updated} documents updated with llm_similar field.")

    # Cleanup checkpoint
    if os.path.exists(CHECKPOINT_FILE):
        os.remove(CHECKPOINT_FILE)


def verify(es, adj):
    """Spot-check loaded data."""
    print(f"\n=== VERIFICATION ===")
    import random

    sample_ids = random.sample(list(adj.keys()), min(20, len(adj)))
    mismatches = 0

    for hid in sample_ids:
        try:
            doc = es.get(index=ES_INDEX, id=hid, source_includes=["llm_similar"])
            loaded = doc["_source"].get("llm_similar", [])
            expected = adj[hid]
            if len(loaded) != len(expected):
                print(f"  MISMATCH {hid}: expected {len(expected)}, got {len(loaded)}")
                mismatches += 1
        except Exception as e:
            print(f"  ERROR {hid}: {e}")
            mismatches += 1

    # Count total docs with llm_similar
    result = es.count(index=ES_INDEX, body={"query": {"exists": {"field": "llm_similar"}}})
    print(f"Docs with llm_similar field: {result['count']}")
    print(f"Expected: {len(adj)}")
    print(f"Spot check: {len(sample_ids)} docs, {mismatches} mismatches")

    # Verify existing fields not corrupted on 3 random docs
    print("\nField integrity check (3 random docs):")
    for hid in random.sample(list(adj.keys()), min(3, len(adj))):
        doc = es.get(index=ES_INDEX, id=hid)["_source"]
        has_arabic = "arabic" in doc and len(doc["arabic"]) > 0
        has_book = "book" in doc and len(doc["book"]) > 0
        has_llm = "llm_similar" in doc and len(doc["llm_similar"]) > 0
        print(
            f"  {hid}: arabic={'OK' if has_arabic else 'MISSING'}, "
            f"book={'OK' if has_book else 'MISSING'}, "
            f"llm_similar={'OK' if has_llm else 'MISSING'} ({len(doc.get('llm_similar', []))} entries)"
        )


def main():
    parser = argparse.ArgumentParser(description="Load LLM similar hadith to ES")
    parser.add_argument("--dry-run", action="store_true", help="Validate only, no ES writes")
    parser.add_argument("--live", action="store_true", help="Load data into ES")
    parser.add_argument("--resume", action="store_true", help="Resume from checkpoint")
    parser.add_argument("--verify", action="store_true", help="Verify loaded data")
    parser.add_argument("--es-host", default="http://localhost:9200", help="ES host")
    args = parser.parse_args()

    if not any([args.dry_run, args.live, args.verify]):
        parser.print_help()
        return

    print(f"Loading cache from {CACHE_FILE}...")
    cache = json.load(open(CACHE_FILE))
    total = len(cache)
    similar = sum(1 for v in cache.values() if isinstance(v, dict) and v.get("verdict") == "similar")
    print(f"Cache: {total} total, {similar} similar")

    print("Building adjacency list...")
    adj = build_adjacency(cache)
    print(f"Built adjacency for {len(adj)} hadith")

    es = Elasticsearch([args.es_host], request_timeout=60)

    if args.dry_run:
        dry_run(adj, es)

    if args.live:
        ensure_mapping(es)
        live_load(adj, es, resume=args.resume)
        verify(es, adj)

    if args.verify:
        verify(es, adj)


if __name__ == "__main__":
    main()
