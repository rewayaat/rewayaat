#!/usr/bin/env python3
"""
Export multi-verse tafsir documents from Elasticsearch for verse splitting.

Queries the tafsir index for documents where ayahEnd > ayahStart
and commentaryWordCount >= MIN_WORD_COUNT, scrolls through results,
and writes each document to a JSON file.

Usage:
    python3 scripts/ingest/export_multi_verse_tafsir.py
    TAFSIR_INDEX=rewayaat_tafsir MIN_WORD_COUNT=100 python3 scripts/ingest/export_multi_verse_tafsir.py
"""

import json
import os
import sys
import urllib.request
import urllib.error

ES_BASE_URL = os.environ.get("ELASTICSEARCH_URL", "http://localhost:9200").rstrip("/")
TAFSIR_INDEX = os.environ.get("TAFSIR_INDEX", "rewayaat_tafsir")
MIN_WORD_COUNT = int(os.environ.get("MIN_WORD_COUNT", "100"))
OUTPUT_DIR = os.environ.get("OUTPUT_DIR", "tmp/tafsir-split/input")
SCROLL_KEEPALIVE = os.environ.get("SCROLL_KEEPALIVE", "5m")
SCROLL_BATCH_SIZE = int(os.environ.get("SCROLL_BATCH_SIZE", "100"))


def es_request(method, path, payload=None):
    url = f"{ES_BASE_URL}{path}"
    body = json.dumps(payload).encode("utf-8") if payload else None
    headers = {"Content-Type": "application/json"} if body else {}
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=30) as resp:
        raw = resp.read().decode("utf-8")
        return json.loads(raw) if raw else {}


def start_scroll():
    payload = {
        "size": SCROLL_BATCH_SIZE,
        "sort": ["_doc"],
        "_source": [
            "tafsir_slug", "tafsirSlug",
            "tafsir_name", "tafsirName",
            "surah_number", "surahNumber",
            "ayah_start", "ayahStart",
            "ayah_end", "ayahEnd",
            "verse_key", "verseKey",
            "verse_keys", "verseKeys",
            "commentary_text", "commentaryText",
            "commentary_text_english", "commentaryTextEnglish",
            "commentary_text_arabic", "commentaryTextArabic",
            "section_title", "sectionTitle",
            "commentary_word_count", "commentaryWordCount",
            "volume",
            "source_url", "sourceUrl",
            "language",
        ],
        "query": {
            "bool": {
                "filter": [
                    {"range": {"ayah_end": {"gt": {"var": "ayah_start"}}}},
                    {"range": {"commentary_word_count": {"gte": MIN_WORD_COUNT}}},
                ]
            }
        },
    }
    # Also try camelCase field names since the index mapping has both
    payload_fallback = {
        "size": SCROLL_BATCH_SIZE,
        "sort": ["_doc"],
        "_source": [
            "tafsir_slug", "tafsirSlug",
            "tafsir_name", "tafsirName",
            "surah_number", "surahNumber",
            "ayah_start", "ayahStart",
            "ayah_end", "ayahEnd",
            "verse_key", "verseKey",
            "verse_keys", "verseKeys",
            "commentary_text", "commentaryText",
            "commentary_text_english", "commentaryTextEnglish",
            "commentary_text_arabic", "commentaryTextArabic",
            "section_title", "sectionTitle",
            "commentary_word_count", "commentaryWordCount",
            "volume",
            "source_url", "sourceUrl",
            "language",
        ],
        "query": {
            "bool": {
                "filter": [
                    {"script": {
                        "script": "doc['ayah_end'].size() > 0 && doc['ayah_start'].size() > 0 && doc['ayah_end'].value > doc['ayah_start'].value"
                    }},
                    {"range": {"commentary_word_count": {"gte": MIN_WORD_COUNT}}},
                ]
            }
        },
    }

    # Try the range query first; fall back to script query if field names differ
    try:
        return es_request("POST", f"/{TAFSIR_INDEX}/_search?scroll={SCROLL_KEEPALIVE}", payload)
    except urllib.error.HTTPError:
        return es_request("POST", f"/{TAFSIR_INDEX}/_search?scroll={SCROLL_KEEPALIVE}", payload_fallback)


def continue_scroll(scroll_id):
    return es_request("POST", "/_search/scroll", {
        "scroll": SCROLL_KEEPALIVE,
        "scroll_id": scroll_id,
    })


def clear_scroll(scroll_id):
    if not scroll_id:
        return
    try:
        es_request("DELETE", "/_search/scroll", {"scroll_id": [scroll_id]})
    except Exception:
        pass


def normalize_doc(raw_source, doc_id):
    """Normalize field names to snake_case, handling both naming conventions in ES."""
    s = {}
    for key in raw_source:
        s[key] = raw_source[key]

    def get(*keys):
        for k in keys:
            if k in s and s[k] is not None:
                return s[k]
        return None

    return {
        "id": doc_id,
        "tafsirSlug": get("tafsir_slug", "tafsirSlug") or "",
        "tafsirName": get("tafsir_name", "tafsirName") or "",
        "surahNumber": get("surah_number", "surahNumber"),
        "ayahStart": get("ayah_start", "ayahStart"),
        "ayahEnd": get("ayah_end", "ayahEnd"),
        "verseKey": get("verse_key", "verseKey") or "",
        "verseKeys": get("verse_keys", "verseKeys") or [],
        "commentaryText": get("commentary_text", "commentaryText") or "",
        "commentaryTextEnglish": get("commentary_text_english", "commentaryTextEnglish") or "",
        "commentaryTextArabic": get("commentary_text_arabic", "commentaryTextArabic") or "",
        "sectionTitle": get("section_title", "sectionTitle") or "",
        "commentaryWordCount": get("commentary_word_count", "commentaryWordCount"),
        "volume": get("volume"),
        "sourceUrl": get("source_url", "sourceUrl") or "",
        "language": get("language") or "",
    }


def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    total_exported = 0
    by_slug = {}
    scroll_id = None

    try:
        page = start_scroll()
        scroll_id = page.get("_scroll_id", "")

        while True:
            hits = page.get("hits", {}).get("hits", [])
            if not hits:
                break

            for hit in hits:
                doc_id = hit.get("_id", "")
                source = hit.get("_source", {})

                doc = normalize_doc(source, doc_id)

                # Double-check the multi-verse condition (in case query used script)
                if doc["ayahEnd"] is None or doc["ayahStart"] is None:
                    continue
                if doc["ayahEnd"] <= doc["ayahStart"]:
                    continue
                word_count = doc.get("commentaryWordCount") or 0
                if word_count < MIN_WORD_COUNT:
                    continue

                # Write to file
                slug = doc["tafsirSlug"]
                verse_key = doc["verseKey"]
                filename = f"{slug}_{verse_key.replace(':', '_')}.json"
                filepath = os.path.join(OUTPUT_DIR, filename)

                with open(filepath, "w", encoding="utf-8") as f:
                    json.dump(doc, f, indent=2, ensure_ascii=False)

                by_slug[slug] = by_slug.get(slug, 0) + 1
                total_exported += 1

                if total_exported % 50 == 0:
                    print(f"  Exported {total_exported} documents...")

            page = continue_scroll(scroll_id)
            scroll_id = page.get("_scroll_id", scroll_id)

    finally:
        clear_scroll(scroll_id)

    # Write manifest
    manifest = {
        "totalDocs": total_exported,
        "totalSlugs": len(by_slug),
        "bySlug": dict(sorted(by_slug.items(), key=lambda x: x[1], reverse=True)),
        "minWordCount": MIN_WORD_COUNT,
        "index": TAFSIR_INDEX,
    }
    manifest_path = os.path.join(os.path.dirname(OUTPUT_DIR), "manifest.json")
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)

    print(f"Export complete: {total_exported} multi-verse documents to {OUTPUT_DIR}/")
    print(f"Manifest written to {manifest_path}")
    for slug, count in sorted(by_slug.items(), key=lambda x: x[1], reverse=True):
        print(f"  {slug}: {count} documents")


if __name__ == "__main__":
    main()
