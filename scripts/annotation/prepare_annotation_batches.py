#!/usr/bin/env python3
"""Export hadith from ES into batches for the annotation agent pipeline.

Creates JSONL batch files in tmp/hadith-annotation/batches/.
Skips hadith that already have english_annotated field (resume support).

Usage:
    python3 scripts/prepare_annotation_batches.py
    python3 scripts/prepare_annotation_batches.py --batch-size 15
    python3 scripts/prepare_annotation_batches.py --force  # don't skip already annotated
    python3 scripts/prepare_annotation_batches.py --es-host http://localhost:9200
"""

import argparse
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from elasticsearch import Elasticsearch

ES_INDEX = "rewayaat_updated"
BATCH_DIR = Path("tmp/hadith-annotation/batches")
RESULTS_DIR = Path("tmp/hadith-annotation/results")
DEFAULT_BATCH_SIZE = 15


def scroll_hadith(es, batch_size=1000, fields=None, skip_annotated=True):
    """Scroll through all hadith, yielding documents."""
    query = {"match_all": {}}
    if skip_annotated:
        query = {"bool": {"must_not": {"exists": {"field": "english_annotated"}}}}

    body = {
        "size": batch_size,
        "_source": fields or ["english", "arabic", "book", "chapter", "topic_tags"],
        "query": query,
        "sort": ["_doc"],
    }

    resp = es.search(index=ES_INDEX, body=body, scroll="5m")
    scroll_id = resp["_scroll_id"]

    while True:
        hits = resp["hits"]["hits"]
        if not hits:
            break
        yield hits
        resp = es.scroll(scroll_id=scroll_id, scroll="5m")
        scroll_id = resp.get("_scroll_id", scroll_id)

    try:
        es.clear_scroll(scroll_id=scroll_id)
    except Exception:
        pass


def main():
    parser = argparse.ArgumentParser(description="Prepare annotation batches")
    parser.add_argument("--batch-size", type=int, default=DEFAULT_BATCH_SIZE)
    parser.add_argument("--force", action="store_true", help="Don't skip already annotated")
    parser.add_argument("--max-chars", type=int, default=20000,
                        help="Max total chars per batch (flushes early if exceeded)")
    parser.add_argument("--es-host", default="http://localhost:9200")
    args = parser.parse_args()

    BATCH_DIR.mkdir(parents=True, exist_ok=True)
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)

    es = Elasticsearch([args.es_host], request_timeout=60)

    # Count total and already annotated
    total = es.count(index=ES_INDEX, body={"query": {"match_all": {}}})["count"]
    annotated = 0
    if not args.force:
        annotated = es.count(
            index=ES_INDEX, body={"query": {"exists": {"field": "english_annotated"}}}
        )["count"]

    to_process = total - annotated
    print(f"Total hadith: {total}")
    print(f"Already annotated: {annotated}")
    print(f"To process: {to_process}")
    print(f"Batch size: {args.batch_size}, max chars: {args.max_chars}")

    if to_process == 0:
        print("Nothing to do!")
        return

    batch_num = 0
    total_exported = 0
    current_batch = []
    current_chars = 0

    for hits in scroll_hadith(es, batch_size=1000, skip_annotated=not args.force):
        for hit in hits:
            source = hit["_source"]
            doc = {
                "id": hit["_id"],
                "book": source.get("book", ""),
                "english": source.get("english", ""),
                "arabic": source.get("arabic", ""),
                "topic_tags": source.get("topic_tags", []),
            }
            # Skip hadith with empty text
            if not doc["english"] and not doc["arabic"]:
                continue

            doc_chars = len(doc["english"]) + len(doc["arabic"])
            current_batch.append(doc)
            current_chars += doc_chars

            if len(current_batch) >= args.batch_size or current_chars >= args.max_chars:
                batch_num += 1
                batch_file = BATCH_DIR / f"batch_{batch_num:04d}.jsonl"
                with open(batch_file, "w") as f:
                    for item in current_batch:
                        f.write(json.dumps(item, ensure_ascii=False) + "\n")
                total_exported += len(current_batch)
                current_batch = []
                current_chars = 0

    # Flush remaining
    if current_batch:
        batch_num += 1
        batch_num += 1
        batch_file = BATCH_DIR / f"batch_{batch_num:04d}.jsonl"
        with open(batch_file, "w") as f:
            for item in current_batch:
                f.write(json.dumps(item, ensure_ascii=False) + "\n")
        total_exported += len(current_batch)

    print(f"\nDone: {total_exported} hadith exported into {batch_num} batches")
    print(f"Output: {BATCH_DIR}/")


if __name__ == "__main__":
    main()
