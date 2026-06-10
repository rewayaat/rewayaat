#!/usr/bin/env python3
"""
Shared utilities for ES field translation scripts.

Provides: ES connection, scroll helper, atomic JSON writes,
bulk update with checkpoint/resume, and field mapping helpers.

Follows patterns from add_book_ar_to_es.py and load_llm_similar_to_es.py.
"""

import json
import os
import sys
from pathlib import Path

try:
    from elasticsearch import Elasticsearch, helpers
except ImportError:
    print("Install elasticsearch-py: pip install elasticsearch")
    sys.exit(1)


SCRIPT_DIR = Path(__file__).resolve().parent
DATA_DIR = SCRIPT_DIR / "data"


def get_es_client(host="http://localhost:9200"):
    """Create ES client with timeout."""
    return Elasticsearch([host], request_timeout=60)


def scroll_all(es, index, query=None, source_fields=None, page_size=500):
    """Yield all matching docs via scroll API.

    Args:
        es: Elasticsearch client
        index: Index name
        query: Optional query dict. Defaults to match_all.
        source_fields: Optional list of fields to return.
        page_size: Documents per scroll page.

    Yields:
        (doc_id, source) tuples
    """
    body = {"query": query or {"match_all": {}}}
    if source_fields:
        body["_source"] = source_fields

    resp = es.search(index=index, body=body, scroll="5m", size=page_size)
    scroll_id = resp["_scroll_id"]
    hits = resp["hits"]["hits"]

    while hits:
        for hit in hits:
            yield hit["_id"], hit.get("_source", {})
        resp = es.scroll(scroll_id=scroll_id, scroll="5m")
        scroll_id = resp["_scroll_id"]
        hits = resp["hits"]["hits"]

    try:
        es.clear_scroll(scroll_id=scroll_id)
    except Exception:
        pass


def atomic_write_json(path, data):
    """Write JSON to temp file, then atomically rename."""
    path = Path(path)
    tmp = str(path) + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    os.rename(tmp, str(path))


def load_json_cache(path):
    """Load JSON cache with backup recovery."""
    path = Path(path)
    if path.exists():
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    # Try backup
    backup = Path(str(path) + ".bak")
    if backup.exists():
        with open(backup, "r", encoding="utf-8") as f:
            return json.load(f)
    return {}


def save_checkpoint(path, data):
    """Atomic checkpoint save."""
    atomic_write_json(path, data)


def load_checkpoint(path):
    """Load checkpoint or return empty."""
    return load_json_cache(path)


def ensure_field_mapping(es, index, field_name, field_type="keyword"):
    """Add a field to ES mapping if it doesn't exist."""
    mapping = es.indices.get_mapping(index=index)
    props = mapping[index]["mappings"].get("properties", {})
    if field_name in props:
        return False
    es.indices.put_mapping(
        index=index,
        body={"properties": {field_name: {"type": field_type}}},
    )
    return True


def ensure_nested_field(es, index, parent_field, sub_field, sub_type="text"):
    """Add a sub-field to an existing nested type."""
    mapping = es.indices.get_mapping(index=index)
    props = mapping[index]["mappings"].get("properties", {})
    parent_props = props.get(parent_field, {}).get("properties", {})
    if sub_field in parent_props:
        return False
    # For nested types, we need to update the parent's properties
    es.indices.put_mapping(
        index=index,
        body={
            "properties": {
                parent_field: {
                    "type": "nested",
                    "dynamic": False,
                    "properties": {
                        sub_field: {"type": sub_type, "index": False},
                    },
                }
            }
        },
    )
    return True


def bulk_update_with_checkpoint(es, index, actions, checkpoint_path,
                                batch_size=500, checkpoint_every=5):
    """Bulk update ES with checkpoint/resume support.

    Args:
        es: Elasticsearch client
        index: Index name
        actions: List of {"_id": ..., "doc": {...}} dicts
        checkpoint_path: Path to checkpoint file
        batch_size: Actions per bulk request
        checkpoint_every: Save checkpoint every N batches

    Returns:
        (success_count, error_count)
    """
    cp = load_checkpoint(checkpoint_path)
    done_ids = set(cp.get("done_ids", []))
    total_done = cp.get("total_done", 0)

    # Filter out already-done actions
    remaining = [a for a in actions if a["_id"] not in done_ids]

    if not remaining:
        print(f"  All {len(actions)} actions already done.")
        return total_done, 0

    print(f"  Remaining: {len(remaining)} actions ({len(done_ids)} already done)")

    success_total = 0
    error_total = 0
    batch_num = 0

    for i in range(0, len(remaining), batch_size):
        batch = remaining[i:i + batch_size]
        batch_num += 1

        bulk_actions = [
            {"_op_type": "update", "_index": index, "_id": a["_id"], "doc": a["doc"]}
            for a in batch
        ]

        try:
            success, errors = helpers.bulk(es, bulk_actions, raise_on_error=False)
            success_total += success
            total_done += success
            err_count = len([e for e in errors if isinstance(e, dict) and
                           e.get("update", {}).get("status", 200) != 200])
            error_total += err_count
            if err_count:
                print(f"  Batch {batch_num}: {err_count} errors")
                for e in errors[:3]:
                    print(f"    {e}")
        except Exception as e:
            print(f"  Batch {batch_num} FAILED: {e}")
            # Save checkpoint and bail
            done_ids.update(a["_id"] for a in batch[:i])
            save_checkpoint(checkpoint_path, {
                "done_ids": list(done_ids),
                "total_done": total_done,
            })
            print(f"  Checkpoint saved. Re-run to resume.")
            return success_total, error_total

        done_ids.update(a["_id"] for a in batch)
        if batch_num % checkpoint_every == 0 or i + batch_size >= len(remaining):
            save_checkpoint(checkpoint_path, {
                "done_ids": list(done_ids),
                "total_done": total_done,
            })
            print(f"  Batch {batch_num}: {success} ok, total {total_done}/{len(actions)}")

    # Clean up checkpoint on completion
    if os.path.exists(checkpoint_path):
        os.remove(checkpoint_path)

    return success_total, error_total
