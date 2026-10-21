#!/usr/bin/env python3
"""Bring candidate_count and top_verse_keys back in step with each doc's candidates.

The page badge reads candidate_count and hadith_for_verse searches top_verse_keys, but
earlier judge passes removed candidates without touching either field, so the badge
over-counted and hadith_for_verse answered with verses the judges had removed. This sets
both from the candidates the doc actually holds. Docs are never deleted; a doc left with
no candidates gets count 0 and no verse keys.

Usage:
    python3 scripts/quranic-insights/resync_summary_fields.py --dry-run
    python3 scripts/quranic-insights/resync_summary_fields.py --backup tmp/resync-backup.jsonl
    python3 scripts/quranic-insights/resync_summary_fields.py --es-host http://localhost:9201 --backup ...
"""

import json
import os
import sys
from elasticsearch import Elasticsearch, helpers

INDEX = os.environ.get("QURANIC_LIGHT_INDEX", "rewayaat_quranic_light_filtered")
DRY_RUN = "--dry-run" in sys.argv


def arg(name, default=None):
    if name in sys.argv:
        return sys.argv[sys.argv.index(name) + 1]
    return default


def main():
    es_host = arg("--es-host", os.environ.get("ELASTICSEARCH_URL", "http://localhost:9200"))
    backup_path = arg("--backup")
    if not DRY_RUN and not backup_path:
        sys.exit("--backup PATH is required for a live run (it holds the old values)")

    print(f"Resync summary fields")
    print(f"  ES: {es_host}  Index: {INDEX}  Dry run: {DRY_RUN}")
    es = Elasticsearch(es_host)

    scanned = stale_count = stale_keys = 0
    actions = []
    backup = open(backup_path, "w", encoding="utf-8") if backup_path else None

    for hit in helpers.scan(es, index=INDEX, query={"query": {"match_all": {}}},
                            _source=["candidates.verse_key", "candidate_count", "top_verse_keys"],
                            size=500, scroll="5m"):
        scanned += 1
        src = hit["_source"]
        keys = [c.get("verse_key", "") for c in src.get("candidates", [])]
        count_ok = src.get("candidate_count") == len(keys)
        keys_ok = sorted(src.get("top_verse_keys") or []) == sorted(keys)
        if count_ok and keys_ok:
            continue
        stale_count += not count_ok
        stale_keys += not keys_ok
        if backup:
            backup.write(json.dumps({"_id": hit["_id"],
                                     "candidate_count": src.get("candidate_count"),
                                     "top_verse_keys": src.get("top_verse_keys")},
                                    ensure_ascii=False) + "\n")
        actions.append({
            "_op_type": "update",
            "_index": hit["_index"],
            "_id": hit["_id"],
            "doc": {"candidate_count": len(keys), "top_verse_keys": keys},
        })

    if backup:
        backup.close()

    print(f"  Scanned: {scanned}")
    print(f"  Stale candidate_count: {stale_count}")
    print(f"  Stale top_verse_keys: {stale_keys}")
    print(f"  Docs to update: {len(actions)}")

    if DRY_RUN or not actions:
        return
    success, errors = helpers.bulk(es, actions, chunk_size=500, raise_on_error=False)
    print(f"  Updated: {success}, errors: {len(errors)}")
    es.indices.refresh(index=INDEX)


if __name__ == "__main__":
    main()
