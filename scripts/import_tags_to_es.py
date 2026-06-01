#!/usr/bin/env python3
"""Import tag assignments from JSON result files back into Elasticsearch.

Expects files named tags_batch_XXXX.json in the input directory, each containing:
{"assignments": [{"id": "...", "tags": ["slug-1", "slug-2"]}, ...]}

Also reads the taxonomy to expand ancestor tags.

Usage:
  python3 import_tags_to_es.py --indir tmp/results/hadith --index rewayaat_updated
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TAXONOMY_JSON = ROOT / "src/main/resources/static/taxonomy.json"
PROPOSALS_JSON = ROOT / "src/main/resources/static/taxonomy.proposals.json"


def es_request(es_url, method, path, data=None, content_type="application/json", timeout=300):
    import tempfile, os
    url = f"{es_url.rstrip('/')}/{path.lstrip('/')}"
    cmd = ["curl", "-sS", "-X", method, "--max-time", str(timeout),
           "-H", f"Content-Type: {content_type}"]
    tmp_path = None
    if data is not None:
        fd, tmp_path = tempfile.mkstemp(suffix=".ndjson")
        with os.fdopen(fd, "wb") as f:
            f.write(data)
        cmd += ["--data-binary", f"@{tmp_path}"]
    cmd.append(url)
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout + 30)
    finally:
        if tmp_path:
            os.unlink(tmp_path)
    if result.returncode != 0:
        raise RuntimeError(f"curl failed ({result.returncode}): {result.stderr}")
    return json.loads(result.stdout) if result.stdout.strip() else {}


def load_taxonomy():
    """Load taxonomy and build parent lookup for ancestor expansion."""
    entries = []
    for path in [TAXONOMY_JSON, PROPOSALS_JSON]:
        if path.exists():
            data = json.loads(path.read_text(encoding="utf-8"))
            if isinstance(data, list):
                entries.extend(data)

    parent_map = {}
    allowed = set()
    for entry in entries:
        slug = entry.get("slug", "").strip()
        if not slug:
            continue
        slug = slug.lower().replace(" ", "-")
        parent = entry.get("parent", "").strip().lower().replace(" ", "-")
        parent_map[slug] = parent
        if entry.get("taggable", True):
            allowed.add(slug)
    return parent_map, allowed


def expand_with_ancestors(tags, parent_map, allowed=None):
    """Add taggable ancestor tags to the list.

    Only includes ancestors that are marked as taggable in the taxonomy
    (i.e. present in the *allowed* set).  Top-level grouping categories
    like ``halal`` or ``good-character`` (taggable=False) are skipped.
    """
    expanded = []
    seen = set()
    for tag in tags:
        tag = tag.strip().lower().replace(" ", "-")
        if not tag or tag in seen:
            continue
        expanded.append(tag)
        seen.add(tag)
        # Walk up the parent chain
        current = tag
        for _ in range(10):  # safety limit
            parent = parent_map.get(current, "")
            if not parent or parent in seen:
                break
            # Only include taggable ancestors
            if allowed is not None and parent not in allowed:
                seen.add(parent)
                current = parent
                continue
            expanded.append(parent)
            seen.add(parent)
            current = parent
    return expanded


def bulk_update_tags(es_url, index, assignments, parent_map, allowed):
    """Bulk update topic_tags in ES."""
    lines = []
    for entry in assignments:
        doc_id = entry["id"]
        raw_tags = entry.get("tags", [])
        expanded = expand_with_ancestors(raw_tags, parent_map, allowed)
        lines.append(json.dumps({"update": {"_index": index, "_id": doc_id}}))
        lines.append(json.dumps({"doc": {"topic_tags": expanded}}))

    if not lines:
        return 0

    data = ("\n".join(lines) + "\n").encode("utf-8")
    resp = es_request(es_url, "/_bulk", "POST", data, content_type="application/x-ndjson")
    errors = []
    for item in resp.get("items", []):
        result = item.get("update", {})
        if result.get("error"):
            errors.append(f"{result.get('_id')}: {result['error']}")

    if errors:
        print(f"  WARN: {len(errors)} errors in bulk update")
        for e in errors[:5]:
            print(f"    {e}")
    success = len(assignments) - len(errors)
    return success


def main():
    parser = argparse.ArgumentParser(description="Import tag results into ES")
    parser.add_argument("--es-url", default="http://localhost:9200")
    parser.add_argument("--index", required=True)
    parser.add_argument("--indir", required=True, help="Directory containing tags_batch_*.json files")
    parser.add_argument("--chunk-size", type=int, default=500, help="Bulk update chunk size")
    args = parser.parse_args()

    parent_map, allowed = load_taxonomy()
    print(f"Loaded taxonomy: {len(parent_map)} slugs")

    result_files = sorted(Path(args.indir).glob("tags_batch_*.json"))
    if not result_files:
        print(f"No tags_batch_*.json files found in {args.indir}", file=sys.stderr)
        sys.exit(1)

    total_updated = 0
    total_docs = 0
    for result_file in result_files:
        data = json.loads(result_file.read_text(encoding="utf-8"))
        assignments = data.get("assignments", [])
        if not assignments:
            print(f"  {result_file.name}: empty, skipping")
            continue

        # Filter to only allowed slugs
        for entry in assignments:
            entry["tags"] = [t for t in entry.get("tags", []) if t in allowed]

        total_docs += len(assignments)

        # Chunk and bulk update
        for i in range(0, len(assignments), args.chunk_size):
            chunk = assignments[i:i + args.chunk_size]
            success = bulk_update_tags(args.es_url, args.index, chunk, parent_map, allowed)
            total_updated += success

        print(f"  {result_file.name}: {len(assignments)} docs updated (total: {total_updated})")

    print(f"\nDone. Updated {total_updated}/{total_docs} documents in {args.index}")


if __name__ == "__main__":
    main()
