#!/usr/bin/env python3
import json
import os
import urllib.request
from pathlib import Path


ES_BASE_URL = os.environ.get("ELASTICSEARCH_URL", "http://127.0.0.1:9200").rstrip("/")
HADITH_INDEX = os.environ.get("REWAYAAT_INDEX", "rewayaat_updated")
QURAN_INDEX = os.environ.get("QURAN_VERSES_INDEX", "rewayaat_quran")


def bulk_apply(path_str, index):
    path = Path(path_str)
    rows = json.loads(path.read_text(encoding="utf-8"))
    lines = []
    for row in rows:
        lines.append(json.dumps({"update": {"_index": index, "_id": row["id"]}}, ensure_ascii=False))
        lines.append(json.dumps({"doc": {"topic_tags": row.get("source", {}).get("topic_tags", [])}}, ensure_ascii=False))
    data = ("\n".join(lines) + "\n").encode("utf-8")
    req = urllib.request.Request(
        f"{ES_BASE_URL}/_bulk?filter_path=errors,items.*.update.error",
        data=data,
        headers={"Content-Type": "application/x-ndjson"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    if body.get("errors"):
        raise SystemExit(json.dumps(body)[:2000])
    print(f"applied {len(rows)} rows from {path} to {index}")


def main():
    import sys
    if len(sys.argv) < 3:
        raise SystemExit("usage: apply_corrected_batch_tags.py <hadith|quran> <corrected-batch.json> [more files...]")
    kind = sys.argv[1]
    index = HADITH_INDEX if kind == "hadith" else QURAN_INDEX
    for path in sys.argv[2:]:
        bulk_apply(path, index)


if __name__ == "__main__":
    main()
