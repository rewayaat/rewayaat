#!/usr/bin/env python3
"""
Index tafsir JSON dump files into Elasticsearch.

Reads JSON files produced by TafsirExtractionTool's dry-run dump
and indexes them via HTTP, bypassing the broken Rest5Client.

Usage:
    python3 scripts/ingest/index_tafsir_json.py
    TAFSIR_INDEX=rewayaat_tafsir JSON_DIR=tmp/tafsir-json python3 scripts/ingest/index_tafsir_json.py
"""

import json
import os
import sys
import urllib.request
import urllib.error

ES_BASE_URL = os.environ.get("ELASTICSEARCH_URL", "http://localhost:9200").rstrip("/")
TAFSIR_INDEX = os.environ.get("TAFSIR_INDEX", "rewayaat_tafsir")
JSON_DIR = os.environ.get("JSON_DIR", "tmp/tafsir-json")
BULK_BATCH_SIZE = int(os.environ.get("BULK_BATCH_SIZE", "100"))


def es_ndjson(lines):
    url = f"{ES_BASE_URL}/_bulk"
    body = ("\n".join(lines) + "\n").encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        headers={"Content-Type": "application/x-ndjson"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        raw = resp.read().decode("utf-8")
        return json.loads(raw) if raw else {}


def ensure_index():
    try:
        req = urllib.request.Request(
            f"{ES_BASE_URL}/{TAFSIR_INDEX}",
            method="HEAD",
        )
        urllib.request.urlopen(req, timeout=10)
        print(f"Index {TAFSIR_INDEX} exists")
    except urllib.error.HTTPError as e:
        if e.code == 404:
            mapping = {
                "mappings": {
                    "properties": {
                        "tafsir_slug": {"type": "keyword"},
                        "tafsir_name": {"type": "text"},
                        "surah_number": {"type": "integer"},
                        "ayah_start": {"type": "integer"},
                        "ayah_end": {"type": "integer"},
                        "verse_key": {"type": "keyword"},
                        "verse_keys": {"type": "keyword"},
                        "verse_text_english": {"type": "text"},
                        "commentary_text": {"type": "text"},
                        "commentary_text_arabic": {"type": "text"},
                        "commentary_text_english": {"type": "text"},
                        "section_title": {"type": "text"},
                        "commentary_word_count": {"type": "integer"},
                        "volume": {"type": "keyword"},
                        "source_url": {"type": "keyword"},
                        "language": {"type": "keyword"},
                    }
                }
            }
            data = json.dumps(mapping).encode("utf-8")
            req = urllib.request.Request(
                f"{ES_BASE_URL}/{TAFSIR_INDEX}",
                data=data,
                headers={"Content-Type": "application/json"},
                method="PUT",
            )
            urllib.request.urlopen(req, timeout=10)
            print(f"Created index {TAFSIR_INDEX}")
        else:
            raise


def main():
    ensure_index()

    if not os.path.isdir(JSON_DIR):
        print(f"JSON directory not found: {JSON_DIR}")
        print("Run TafsirExtractionTool with TAFSIR_DRY_RUN=true first.")
        sys.exit(1)

    json_files = sorted(f for f in os.listdir(JSON_DIR) if f.endswith(".json"))
    if not json_files:
        print(f"No JSON files found in {JSON_DIR}")
        sys.exit(1)

    print(f"Found {len(json_files)} JSON files in {JSON_DIR}")
    print(f"Target index: {TAFSIR_INDEX}")
    print()

    total_indexed = 0
    total_errors = 0
    total_docs = 0

    for filename in json_files:
        filepath = os.path.join(JSON_DIR, filename)
        slug = filename.replace(".json", "")

        with open(filepath, "r", encoding="utf-8") as f:
            docs = json.load(f)

        print(f"{slug}: {len(docs)} documents")
        total_docs += len(docs)

        # Index in batches
        lines = []
        for doc in docs:
            doc_id = doc.pop("_id", "")
            if not doc_id:
                continue
            lines.append(json.dumps({"index": {"_index": TAFSIR_INDEX, "_id": doc_id}}, ensure_ascii=False))
            lines.append(json.dumps(doc, ensure_ascii=False))

            if len(lines) >= BULK_BATCH_SIZE * 2:
                count, errors = flush_batch(lines)
                total_indexed += count
                total_errors += errors
                lines.clear()

        if lines:
            count, errors = flush_batch(lines)
            total_indexed += count
            total_errors += errors
            lines.clear()

    print()
    print(f"Done: {total_indexed} indexed, {total_errors} errors out of {total_docs} total documents")

    # Verify
    try:
        req = urllib.request.Request(f"{ES_BASE_URL}/{TAFSIR_INDEX}/_count")
        with urllib.request.urlopen(req, timeout=10) as resp:
            result = json.loads(resp.read().decode("utf-8"))
            print(f"ES doc count: {result.get('count', '?')}")
    except Exception:
        pass


def flush_batch(lines):
    if not lines:
        return 0, 0
    try:
        body = es_ndjson(lines)
        indexed = 0
        errors = 0
        for item in body.get("items", []):
            idx = item.get("index", {})
            if idx.get("error"):
                errors += 1
            else:
                indexed += 1
        if errors > 0:
            print(f"  Batch: {indexed} indexed, {errors} errors")
        return indexed, errors
    except urllib.error.HTTPError as e:
        print(f"  Bulk request failed: {e}")
        return 0, len(lines) // 2


if __name__ == "__main__":
    main()
