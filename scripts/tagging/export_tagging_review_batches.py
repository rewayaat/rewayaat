#!/usr/bin/env python3
import json
import os
import random
import urllib.parse
import urllib.request
from pathlib import Path


ES_BASE_URL = os.environ.get("ELASTICSEARCH_URL", "http://127.0.0.1:9200").rstrip("/")
HADITH_INDEX = os.environ.get("REWAYAAT_INDEX", "rewayaat_updated")
QURAN_INDEX = os.environ.get("QURAN_VERSES_INDEX", "rewayaat_quran")
OUT_DIR = Path(os.environ.get("TAGGING_REVIEW_BATCH_DIR", "/mnt/share/rewayaat-tagging-batches"))
HADITH_BATCH_SIZE = int(os.environ.get("HADITH_REVIEW_BATCH_SIZE", "500"))
HADITH_BATCH_COUNT = int(os.environ.get("HADITH_REVIEW_BATCH_COUNT", "2"))
QURAN_BATCH_SIZE = int(os.environ.get("QURAN_REVIEW_BATCH_SIZE", "500"))
QURAN_BATCH_COUNT = int(os.environ.get("QURAN_REVIEW_BATCH_COUNT", "1"))
RANDOM_SEED = int(os.environ.get("TAGGING_REVIEW_BATCH_SEED", "20260517"))
SCROLL = "5m"


def es_json(method, path, payload=None):
    data = None
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        f"{ES_BASE_URL}{path}",
        data=data,
        headers={"Content-Type": "application/json"},
        method=method,
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        raw = resp.read().decode("utf-8")
    return json.loads(raw) if raw else {}


def scroll_all(index, source_fields):
    query = {
        "size": 500,
        "sort": ["_doc"],
        "_source": source_fields,
        "query": {"match_all": {}},
    }
    root = es_json("POST", f"/{urllib.parse.quote(index)}/_search?scroll={SCROLL}", query)
    scroll_id = root.get("_scroll_id")
    items = []
    try:
        while True:
            hits = root.get("hits", {}).get("hits", [])
            if not hits:
                break
            for hit in hits:
                items.append({"id": hit["_id"], "source": hit.get("_source", {})})
            root = es_json("POST", "/_search/scroll", {"scroll": SCROLL, "scroll_id": scroll_id})
            scroll_id = root.get("_scroll_id", scroll_id)
    finally:
        if scroll_id:
            try:
                es_json("DELETE", "/_search/scroll", {"scroll_id": [scroll_id]})
            except Exception:
                pass
    return items


def write_batches(items, batch_size, batch_count, prefix):
    random.shuffle(items)
    manifest = []
    total = min(len(items), batch_size * batch_count)
    for batch_num in range(batch_count):
        start = batch_num * batch_size
        end = min(start + batch_size, total)
        if start >= end:
            break
        batch_items = items[start:end]
        filename = f"{prefix}-batch-{batch_num + 1:02d}.json"
        path = OUT_DIR / filename
        path.write_text(json.dumps(batch_items, ensure_ascii=False, indent=2), encoding="utf-8")
        manifest.append(
            {
                "file": filename,
                "count": len(batch_items),
                "first_id": batch_items[0]["id"] if batch_items else "",
                "last_id": batch_items[-1]["id"] if batch_items else "",
            }
        )
    return manifest


def main():
    random.seed(RANDOM_SEED)
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    hadith = scroll_all(
        HADITH_INDEX,
        [
            "book",
            "chapter",
            "section",
            "semantic_matn_source",
            "semantic_english_hint_source",
            "topic_tags",
        ],
    )
    quran = scroll_all(
        QURAN_INDEX,
        [
            "surah_number",
            "ayah_number",
            "surah_name_english",
            "text_arabic",
            "text_english",
            "topic_tags",
        ],
    )

    manifest = {
        "hadith": write_batches(hadith, HADITH_BATCH_SIZE, HADITH_BATCH_COUNT, "hadith"),
        "quran": write_batches(quran, QURAN_BATCH_SIZE, QURAN_BATCH_COUNT, "quran"),
    }
    (OUT_DIR / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
