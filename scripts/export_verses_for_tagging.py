#!/usr/bin/env python3
"""Export Quran verses + associated tafsir snippets into batch JSON files for Claude tagging."""
import json
import os
import subprocess
import sys
from pathlib import Path


ES_URL = os.environ.get("ES_URL", "http://localhost:9200")
QURAN_INDEX = os.environ.get("QURAN_INDEX", "rewayaat_quran")
TAFSIR_INDEX = os.environ.get("TAFSIR_INDEX", "rewayaat_tafsir")
OUTPUT_DIR = os.environ.get("OUTPUT_DIR", "tmp/verse-tagging/input")
BATCH_SIZE = int(os.environ.get("BATCH_SIZE", "25"))
EN_SNIPPET_MAX = int(os.environ.get("EN_SNIPPET_MAX", "1000"))
AR_SNIPPET_MAX = int(os.environ.get("AR_SNIPPET_MAX", "300"))
MAX_SNIPPETS_PER_VERSE = int(os.environ.get("MAX_SNIPPETS_PER_VERSE", "5"))


def curl_json(url, method="GET", payload=None, timeout=120):
    cmd = ["curl", "-sS", "-X", method, "--max-time", str(timeout)]
    if payload is not None:
        cmd += ["-H", "Content-Type: application/json", "-d", json.dumps(payload)]
    cmd.append(url)
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout + 30)
    if result.returncode != 0:
        raise RuntimeError(f"curl failed ({result.returncode}): {result.stderr}")
    return json.loads(result.stdout) if result.stdout.strip() else {}


def scroll_all(index, batch_size=500, fields=None):
    body = {"size": batch_size, "query": {"match_all": {}}, "sort": ["_doc"]}
    if fields:
        body["_source"] = fields
    url = f"{ES_URL}/{index}/_search?scroll=6h"
    resp = curl_json(url, "POST", body)
    scroll_id = resp.get("_scroll_id", "")
    while True:
        hits = resp.get("hits", {}).get("hits", [])
        if not hits:
            break
        yield hits
        resp = curl_json(f"{ES_URL}/_search/scroll", "POST", {"scroll": "6h", "scroll_id": scroll_id})
    try:
        curl_json(f"{ES_URL}/_search/scroll", "DELETE", {"scroll_id": scroll_id})
    except Exception:
        pass


def fetch_tafsir_snippets(verse_key):
    """Fetch tafsir snippets for a given verse_key from ES."""
    body = {
        "size": 20,
        "query": {"term": {"verse_key": verse_key}},
        "_source": ["tafsir_name", "tafsir_slug", "language", "commentary_text",
                     "commentary_text_english", "commentary_text_arabic"],
        "sort": [{"commentary_word_count": {"order": "desc"}}]
    }
    resp = curl_json(f"{ES_URL}/{TAFSIR_INDEX}/_search", "POST", body)
    snippets = []
    for hit in resp.get("hits", {}).get("hits", []):
        src = hit["_source"]
        lang = src.get("language", "en")
        # Get the main commentary text
        text = src.get("commentary_text") or src.get("commentary_text_english") or ""
        ar_text = src.get("commentary_text_arabic") or ""
        if not text and not ar_text:
            continue
        snippet = {
            "tafsir_name": src.get("tafsir_name", ""),
            "tafsir_slug": src.get("tafsir_slug", ""),
            "language": lang,
        }
        # Truncate based on language
        if text:
            snippet["commentary_text"] = text[:EN_SNIPPET_MAX]
        if ar_text:
            snippet["commentary_text_arabic"] = ar_text[:AR_SNIPPET_MAX]
        snippets.append(snippet)
        if len(snippets) >= MAX_SNIPPETS_PER_VERSE:
            break
    return snippets


def main():
    Path(OUTPUT_DIR).mkdir(parents=True, exist_ok=True)

    fields = ["surah_number", "ayah_number", "text_arabic", "text_english",
              "surah_name_english", "topic_tags"]

    all_verses = []
    total = 0
    for hits in scroll_all(QURAN_INDEX, 500, fields):
        for hit in hits:
            src = hit["_source"]
            verse_key = hit["_id"]
            all_verses.append({
                "verse_key": verse_key,
                "surah_number": src.get("surah_number"),
                "ayah_number": src.get("ayah_number"),
                "surah_name_english": src.get("surah_name_english", ""),
                "text_arabic": src.get("text_arabic", ""),
                "text_english": src.get("text_english", ""),
                "existing_tags": src.get("topic_tags") or [],
            })
            total += 1
    print(f"Loaded {total} verses from {QURAN_INDEX}")

    # Fetch tafsir snippets for each verse
    verses_with_snippets = []
    no_snippets = 0
    for i, v in enumerate(all_verses):
        snippets = fetch_tafsir_snippets(v["verse_key"])
        v["tafsir_snippets"] = snippets
        if not snippets:
            no_snippets += 1
        if (i + 1) % 500 == 0:
            print(f"  Fetched tafsir for {i+1}/{total} verses...")
        verses_with_snippets.append(v)
    print(f"Tafsir coverage: {total - no_snippets}/{total} verses have snippets")

    # Write batch files
    batch_num = 0
    for i in range(0, len(verses_with_snippets), BATCH_SIZE):
        batch = verses_with_snippets[i:i + BATCH_SIZE]
        batch_num += 1
        fname = f"batch-{batch_num:04d}.json"
        path = Path(OUTPUT_DIR) / fname
        path.write_text(json.dumps(batch, ensure_ascii=False, indent=1), encoding="utf-8")

    manifest = {
        "total_verses": total,
        "verses_with_tafsir": total - no_snippets,
        "verses_without_tafsir": no_snippets,
        "batch_size": BATCH_SIZE,
        "total_batches": batch_num,
        "en_snippet_max": EN_SNIPPET_MAX,
        "ar_snippet_max": AR_SNIPPET_MAX,
    }
    (Path(OUTPUT_DIR) / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"\nExported {total} verses in {batch_num} batches to {OUTPUT_DIR}/")


if __name__ == "__main__":
    main()
