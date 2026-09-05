#!/usr/bin/env python3
"""
Import split tafsir documents back into Elasticsearch.

Reads split JSON files from tmp/tafsir-split/output/, indexes per-verse
documents to ES, and deletes the original multi-verse documents.

Usage:
    python3 scripts/ingest/import_split_tafsir.py
    TAFSIR_INDEX=rewayaat_tafsir python3 scripts/ingest/import_split_tafsir.py
"""

import json
import os
import sys
import urllib.request
import urllib.error

ES_BASE_URL = os.environ.get("ELASTICSEARCH_URL", "http://localhost:9200").rstrip("/")
TAFSIR_INDEX = os.environ.get("TAFSIR_INDEX", "rewayaat_tafsir")
INPUT_DIR = os.environ.get("INPUT_DIR", "tmp/tafsir-split/output")
BULK_BATCH_SIZE = int(os.environ.get("BULK_BATCH_SIZE", "50"))
DRY_RUN = os.environ.get("DRY_RUN", "false").lower() == "true"


def es_request(method, path, payload=None):
    url = f"{ES_BASE_URL}{path}"
    body = json.dumps(payload).encode("utf-8") if payload else None
    headers = {"Content-Type": "application/json"} if body else {}
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=30) as resp:
        raw = resp.read().decode("utf-8")
        return resp.getcode(), json.loads(raw) if raw else {}


def es_ndjson(path, lines):
    url = f"{ES_BASE_URL}{path}"
    body = ("\n".join(lines) + "\n").encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        headers={"Content-Type": "application/x-ndjson"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        raw = resp.read().decode("utf-8")
        return resp.getcode(), json.loads(raw) if raw else {}


def build_split_doc(split_doc, original_id):
    """Build an ES-ready document from a split result."""
    doc = {
        "tafsir_slug": split_doc.get("tafsirSlug", ""),
        "tafsir_name": split_doc.get("tafsirName", ""),
        "surah_number": split_doc.get("surahNumber"),
        "ayah_start": split_doc.get("ayahStart"),
        "ayah_end": split_doc.get("ayahEnd"),
        "verse_key": split_doc.get("verseKey", ""),
        "verse_keys": split_doc.get("verseKeys", []),
        "commentary_text": split_doc.get("commentaryText", ""),
        "section_title": split_doc.get("sectionTitle", ""),
        "source_url": split_doc.get("sourceUrl", ""),
        "language": split_doc.get("language", ""),
    }

    # Compute word count
    text = doc["commentary_text"]
    doc["commentary_word_count"] = len(text.split()) if text else 0

    # Include split metadata
    if split_doc.get("splitConfidence") is not None:
        doc["split_confidence"] = split_doc["splitConfidence"]
    if split_doc.get("isGeneral") is not None:
        doc["split_is_general"] = split_doc["isGeneral"]

    # ES document ID: tafsirSlug + "_" + verseKey
    doc_id = split_doc.get("tafsirSlug", "") + "_" + split_doc.get("verseKey", "")

    return doc_id, doc


def delete_original_docs(original_ids):
    """Delete original multi-verse documents from ES."""
    if not original_ids:
        return 0

    lines = []
    for doc_id in original_ids:
        lines.append(json.dumps({"delete": {"_index": TAFSIR_INDEX, "_id": doc_id}}))

    if DRY_RUN:
        print(f"  DRY RUN: Would delete {len(original_ids)} original docs")
        return len(original_ids)

    try:
        _, body = es_ndjson("/_bulk", lines)
        deleted = 0
        for item in body.get("items", []):
            delete_result = item.get("delete", {})
            if delete_result.get("result") == "deleted":
                deleted += 1
        return deleted
    except urllib.error.HTTPError as e:
        print(f"  Warning: bulk delete failed: {e}")
        return 0


def import_file(filepath):
    """Import a single split output file. Returns (split_count, original_id)."""
    with open(filepath, "r", encoding="utf-8") as f:
        data = json.load(f)

    original_id = data.get("originalId", "")
    split_docs = data.get("splitDocuments", [])

    if not split_docs:
        return 0, original_id

    # Build bulk index lines (using index op type = overwrite)
    lines = []
    doc_ids = []
    for split_doc in split_docs:
        doc_id, doc = build_split_doc(split_doc, original_id)
        lines.append(json.dumps({"index": {"_index": TAFSIR_INDEX, "_id": doc_id}}))
        lines.append(json.dumps(doc, ensure_ascii=False))
        doc_ids.append(doc_id)

    if DRY_RUN:
        print(f"  DRY RUN: {original_id} -> {len(split_docs)} split docs ({', '.join(doc_ids)})")
        return len(split_docs), original_id

    _, body = es_ndjson("/_bulk", lines)

    indexed = 0
    errors = 0
    for item in body.get("items", []):
        index_result = item.get("index", {})
        if index_result.get("error"):
            errors += 1
            print(f"    Error indexing {index_result.get('_id')}: {index_result['error'].get('reason', '')}")
        else:
            indexed += 1

    return indexed, original_id


def main():
    if not os.path.isdir(INPUT_DIR):
        print(f"Error: input directory not found: {INPUT_DIR}")
        print("Run the export script first, then run the verse splitting.")
        sys.exit(1)

    files = sorted(f for f in os.listdir(INPUT_DIR) if f.endswith(".json"))
    if not files:
        print(f"No split JSON files found in {INPUT_DIR}/")
        sys.exit(0)

    print(f"Found {len(files)} split files in {INPUT_DIR}/")
    print(f"Target index: {TAFSIR_INDEX}")
    print(f"Dry run: {DRY_RUN}")
    print()

    total_indexed = 0
    total_errors = 0
    original_ids = []

    for i, filename in enumerate(files, 1):
        filepath = os.path.join(INPUT_DIR, filename)
        try:
            count, original_id = import_file(filepath)
            total_indexed += count
            if original_id:
                original_ids.append(original_id)

            if i % 25 == 0:
                print(f"  Progress: {i}/{len(files)} files, {total_indexed} docs indexed")
        except Exception as e:
            print(f"  Error processing {filename}: {e}")
            total_errors += 1

    print()
    print(f"Import summary: {total_indexed} docs indexed, {total_errors} file errors")

    # Delete original multi-verse documents
    if original_ids and not DRY_RUN:
        unique_ids = list(set(original_ids))
        print(f"Deleting {len(unique_ids)} original multi-verse documents...")
        deleted = delete_original_docs(unique_ids)
        print(f"Deleted {deleted} original documents")
    elif original_ids and DRY_RUN:
        print(f"DRY RUN: Would delete {len(set(original_ids))} original multi-verse documents")

    print("Done.")


if __name__ == "__main__":
    main()
