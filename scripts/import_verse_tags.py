#!/usr/bin/env python3
"""Import tagged verse results from Claude sub-agents back into Elasticsearch."""
import json
import os
import subprocess
import sys
from pathlib import Path

ES_URL = os.environ.get("ES_URL", "http://localhost:9200")
QURAN_INDEX = os.environ.get("QURAN_INDEX", "rewayaat_quran")
INPUT_DIR = os.environ.get("INPUT_DIR", "tmp/verse-tagging/output")
DRY_RUN = os.environ.get("DRY_RUN", "").lower() in ("true", "1", "yes")


def curl_json(url, method="GET", payload=None, timeout=120):
    cmd = ["curl", "-sS", "-X", method, "--max-time", str(timeout)]
    if payload is not None:
        cmd += ["-H", "Content-Type: application/json", "-d", json.dumps(payload)]
    cmd.append(url)
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout + 30)
    if result.returncode != 0:
        raise RuntimeError(f"curl failed ({result.returncode}): {result.stderr}")
    return json.loads(result.stdout) if result.stdout.strip() else {}


def bulk_update_tags(docs):
    """Bulk update topic_tags for a list of {verse_key, tags} dicts."""
    bulk_lines = []
    for doc in docs:
        vk = doc["verse_key"]
        tags = doc["tags"]
        bulk_lines.append(json.dumps({"update": {"_id": vk}}))
        bulk_lines.append(json.dumps({"doc": {"topic_tags": tags}}))

    if DRY_RUN:
        print(f"  [DRY RUN] Would update {len(docs)} verses")
        return 0

    bulk_body = "\n".join(bulk_lines) + "\n"
    url = f"{ES_URL}/{QURAN_INDEX}/_bulk"
    cmd = ["curl", "-sS", "-X", "POST", "--max-time", "120",
           "-H", "Content-Type: application/x-ndjson",
           "--data-binary", bulk_body, url]
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=150)
    if result.returncode != 0:
        print(f"  ERROR: bulk update failed: {result.stderr}")
        return len(docs)

    resp = json.loads(result.stdout) if result.stdout.strip() else {}
    errors = sum(1 for item in resp.get("items", []) if item.get("update", {}).get("error"))
    return errors


def main():
    input_path = Path(INPUT_DIR)
    if not input_path.exists():
        print(f"Input directory not found: {INPUT_DIR}")
        sys.exit(1)

    batch_files = sorted(input_path.glob("batch-*.json"))
    if not batch_files:
        print("No batch files found")
        sys.exit(1)

    print(f"Found {len(batch_files)} batch files in {INPUT_DIR}")
    if DRY_RUN:
        print("DRY RUN mode - no changes will be made")

    total_tagged = 0
    total_errors = 0

    for bf in batch_files:
        with open(bf, encoding="utf-8") as f:
            docs = json.load(f)

        # Validate format
        valid = []
        for doc in docs:
            if "verse_key" not in doc or "tags" not in doc:
                print(f"  WARNING: skipping invalid doc in {bf.name}: {list(doc.keys())}")
                continue
            if not isinstance(doc["tags"], list):
                print(f"  WARNING: tags not a list for {doc.get('verse_key', '?')}: {doc['tags']}")
                continue
            valid.append(doc)

        if valid:
            errors = bulk_update_tags(valid)
            total_tagged += len(valid)
            total_errors += errors
            print(f"  {bf.name}: {len(valid)} verses tagged ({errors} errors), total={total_tagged}")

    print(f"\nDone. Tagged {total_tagged} verses with {total_errors} errors")


if __name__ == "__main__":
    main()
