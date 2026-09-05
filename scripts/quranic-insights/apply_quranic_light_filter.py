#!/usr/bin/env python3
"""Apply filtering verdicts from Claude sub-agents and build the filtered quranic light index."""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


ES_BASE_URL = os.environ.get("ELASTICSEARCH_URL", "http://localhost:9200").rstrip("/")
SOURCE_INDEX = os.environ.get("QLFILTER_SOURCE_INDEX", "rewayaat_quranic_light")
TARGET_INDEX = os.environ.get("QLFILTER_TARGET_INDEX", "rewayaat_quranic_light_filtered")
VERDICTS_DIR = Path(os.environ.get("QLFILTER_VERDICTS_DIR", "tmp/qlight-verdicts"))
REBUILD_INDEX = os.environ.get("QLFILTER_REBUILD_INDEX", "false").lower() == "true"
DRY_RUN = os.environ.get("QLFILTER_DRY_RUN", "false").lower() == "true"
BULK_BATCH_SIZE = int(os.environ.get("QLFILTER_BULK_BATCH_SIZE", "50"))
REQUEST_TIMEOUT = int(os.environ.get("QLFILTER_TIMEOUT_SECS", "180"))


def http_json(method, url, payload=None, timeout=REQUEST_TIMEOUT):
    body = None
    headers = {"Content-Type": "application/json"}
    if payload is not None:
        body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read().decode("utf-8")
        return json.loads(raw) if raw else {}


def http_ndjson(url, lines, timeout=REQUEST_TIMEOUT):
    body = ("\n".join(lines) + "\n").encode("utf-8")
    req = urllib.request.Request(
        url, data=body,
        headers={"Content-Type": "application/x-ndjson"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read().decode("utf-8")
        return json.loads(raw) if raw else {}


def es_request(method, path, payload=None):
    return http_json(method, f"{ES_BASE_URL}{path}", payload=payload)


def load_verdicts():
    """Load all verdict files from the verdicts directory into a dict keyed by hadith_id."""
    verdicts = {}
    if not VERDICTS_DIR.exists():
        print(f"Verdicts directory {VERDICTS_DIR} not found")
        return verdicts

    for path in sorted(VERDICTS_DIR.glob("*.jsonl")):
        with open(path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                doc = json.loads(line)
                hadith_id = doc.get("hadith_id", "")
                if not hadith_id:
                    continue
                keep = {}
                for verdict in doc.get("candidates_to_keep", []):
                    vk = verdict.get("verse_key", "")
                    snippet_indices = verdict.get("snippet_indices_to_keep", None)
                    keep[vk] = snippet_indices
                verdicts[hadith_id] = keep
    print(f"Loaded verdicts for {len(verdicts)} hadiths")
    return verdicts


def ensure_target_index():
    """Create the target index with the same mapping as the source."""
    if DRY_RUN:
        print("DRY RUN: would create target index")
        return

    if REBUILD_INDEX:
        try:
            es_request("DELETE", f"/{TARGET_INDEX}")
            print(f"Deleted existing index {TARGET_INDEX}")
        except urllib.error.HTTPError as exc:
            if exc.code != 404:
                raise

    try:
        es_request("HEAD", f"/{TARGET_INDEX}")
        print(f"Index {TARGET_INDEX} already exists")
        return
    except urllib.error.HTTPError as exc:
        if exc.code != 404:
            raise

    # Copy mapping from source index
    _, source_mapping = es_request("GET", f"/{SOURCE_INDEX}/_mapping")
    # Extract the actual mapping (may be nested under index name)
    mappings = source_mapping
    for key in list(source_mapping.keys()):
        if "mappings" in source_mapping[key]:
            mappings = source_mapping[key]["mappings"]
            break

    es_request("PUT", f"/{TARGET_INDEX}", {"mappings": mappings})
    print(f"Created index {TARGET_INDEX}")


def apply_filter(source, verdicts_for_hadith):
    """Remove candidates/snippets not in the verdicts. Returns modified source."""
    if not verdicts_for_hadith:
        return source

    filtered_candidates = []
    for candidate in (source.get("candidates") or []):
        verse_key = candidate.get("verse_key", "")
        if verse_key not in verdicts_for_hadith:
            continue  # Remove this candidate entirely

        snippet_indices = verdicts_for_hadith[verse_key]
        if snippet_indices is not None:
            # Keep only specified snippets
            snippets = candidate.get("tafsir_snippets", [])
            candidate["tafsir_snippets"] = [s for i, s in enumerate(snippets) if i in snippet_indices]

        filtered_candidates.append(candidate)

    source["candidates"] = filtered_candidates
    source["candidate_count"] = len(filtered_candidates)
    source["top_verse_keys"] = [c.get("verse_key", "") for c in filtered_candidates]
    return source


def bulk_index(documents):
    if not documents or DRY_RUN:
        return 0
    lines = []
    for doc in documents:
        hadith_id = doc.get("hadith_id", "")
        lines.append(json.dumps({"index": {"_index": TARGET_INDEX, "_id": hadith_id}}, ensure_ascii=False))
        lines.append(json.dumps(doc, ensure_ascii=False))
    _, body = http_ndjson(f"{ES_BASE_URL}/_bulk", lines)
    if body.get("errors"):
        errors = [item for item in body.get("items", []) if item.get("index", {}).get("status", 200) >= 400]
        print(f"Bulk indexing had {len(errors)} errors")
    return len(documents)


def main():
    verdicts = load_verdicts()
    if not verdicts:
        print("No verdicts found. Nothing to do.")
        sys.exit(1)

    ensure_target_index()

    scroll_id = None
    pending = []
    total_seen = 0
    total_indexed = 0
    total_filtered = 0

    try:
        page = es_request("POST", f"/{SOURCE_INDEX}/_search?scroll=5m", {
            "size": 100,
            "sort": ["_doc"],
            "_source": True,
            "query": {"match_all": {}},
        })
        scroll_id = page.get("_scroll_id", "")

        while True:
            hits = page.get("hits", {}).get("hits", [])
            if not hits:
                break

            for hit in hits:
                hadith_id = hit.get("_id", "")
                source = hit.get("_source", {})
                total_seen += 1

                if hadith_id not in verdicts:
                    continue  # Skip hadiths not in verdicts

                original_count = len(source.get("candidates", []))
                source = apply_filter(source, verdicts[hadith_id])
                filtered_count = len(source.get("candidates", []))

                if filtered_count < original_count:
                    total_filtered += (original_count - filtered_count)

                # Re-rank
                source["candidates"].sort(key=lambda c: c.get("combined_score", 0), reverse=True)
                for i, c in enumerate(source["candidates"], 1):
                    c["rank"] = i

                pending.append(source)

                if len(pending) >= BULK_BATCH_SIZE:
                    total_indexed += bulk_index(pending)
                    pending.clear()
                    print(f"Progress: seen={total_seen} indexed={total_indexed} filtered={total_filtered}")

            page = es_request("POST", "/_search/scroll", {"scroll": "5m", "scroll_id": scroll_id})
            scroll_id = page.get("_scroll_id", scroll_id)

        if pending:
            total_indexed += bulk_index(pending)

    finally:
        if scroll_id:
            try:
                es_request("DELETE", "/_search/scroll", {"scroll_id": [scroll_id]})
            except Exception:
                pass

    print(f"Done: seen={total_seen} indexed={total_indexed} candidates_filtered={total_filtered}")


if __name__ == "__main__":
    main()
