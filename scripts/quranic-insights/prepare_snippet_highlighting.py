#!/usr/bin/env python3
"""Phase 2: Fetch tafsir snippets for identified Quranic verse references.

Reads all batch_*_results.json files, and for each hadith-verse match:
1. Fetches tafsir snippets from rewayaat_tafsir for the matched verse
2. Checks if the verse already exists in the hadith's Quranic insights
3. Outputs per-batch JSON files ready for sub-agent highlighting

Usage:
    python3 scripts/quranic-insights/prepare_snippet_highlighting.py
    python3 scripts/quranic-insights/prepare_snippet_highlighting.py --limit 50
"""
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path


ES_BASE = os.environ.get("ELASTICSEARCH_URL", "http://localhost:9200")
API_BASE = os.environ.get("REWAYAAT_API", "https://hadith.academyofislam.com")
RESULTS_DIR = Path("tmp/quranic-snippet-matches")
SNIPPET_DIR = RESULTS_DIR / "snippets"
TAFSIR_INDEX = "rewayaat_tafsir"
LIGHT_INDEX = "rewayaat_quranic_light_filtered"
TIMEOUT = 30


def http_json(url, payload=None, method="GET", timeout=TIMEOUT):
    body = None
    headers = {}
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
        method = "POST"
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read().decode("utf-8")
        return json.loads(raw) if raw else {}


def es_json(path, payload=None, method="GET"):
    return http_json(f"{ES_BASE}{path}", payload=payload, method=method)


def get_tafsir_for_verse(verse_key, limit=5):
    """Get tafsir snippets for a specific verse from the tafsir index."""
    try:
        data = es_json(f"/{TAFSIR_INDEX}/_search", payload={
            "query": {
                "bool": {
                    "should": [
                        {"term": {"verse_key": verse_key}},
                        {"term": {"verse_keys": verse_key}}
                    ],
                    "minimum_should_match": 1
                }
            },
            "size": limit,
            "_source": ["tafsir_slug", "tafsir_name", "commentary_text",
                        "commentary_text_english", "source_url", "section_title"]
        })
        snippets = []
        for hit in data.get("hits", {}).get("hits", []):
            s = hit["_source"]
            ct = s.get("commentary_text_english", "") or s.get("commentary_text", "")
            if ct and len(ct) > 50:
                snippets.append({
                    "tafsir_slug": s.get("tafsir_slug", ""),
                    "tafsir_name": s.get("tafsir_name", ""),
                    "commentary_text": ct[:3000],  # Cap for agent processing
                    "source_url": s.get("source_url", ""),
                    "section_title": s.get("section_title", ""),
                })
        return snippets
    except Exception as e:
        print(f"    Error fetching tafsir for {verse_key}: {e}")
        return []


def get_quranic_insights(hadith_id):
    """Fetch existing Quranic insights for a hadith from the light index."""
    try:
        data = es_json(f"/{LIGHT_INDEX}/_doc/{hadith_id}")
        if data.get("found"):
            candidates = data["_source"].get("candidates", [])
            return {
                "verse_keys": [c.get("verse_key", "") for c in candidates],
                "candidates": candidates,
            }
    except urllib.error.HTTPError:
        pass
    except Exception:
        pass
    return {"verse_keys": [], "candidates": []}


def get_verse_text(verse_key):
    """Get Quran verse Arabic and English text."""
    try:
        parts = verse_key.split(":")
        surah, ayah = int(parts[0]), int(parts[1])
        data = es_json("/rewayaat_quran/_search", payload={
            "query": {"bool": {"must": [
                {"term": {"surah_number": surah}},
                {"term": {"ayah_number": ayah}}
            ]}},
            "size": 1,
            "_source": ["text_arabic", "text_english", "surah_name_english"]
        })
        if data["hits"]["hits"]:
            s = data["hits"]["hits"][0]["_source"]
            return {
                "text_arabic": s.get("text_arabic", ""),
                "text_english": s.get("text_english", ""),
                "surah_name": s.get("surah_name_english", ""),
            }
    except Exception:
        pass
    return None


def main():
    SNIPPET_DIR.mkdir(parents=True, exist_ok=True)

    limit = 0
    if "--limit" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--limit") + 1])

    # Find all result files
    result_files = sorted(RESULTS_DIR.glob("batch_*_results.json"))
    print(f"Found {len(result_files)} result files to process")

    total_matches = 0
    total_snippets = 0
    total_new_connections = 0
    processed = 0

    for rf in result_files:
        with open(rf, encoding="utf-8") as f:
            results = json.load(f)

        # Collect all hadith-verse matches from this batch
        matches = []
        for h in results:
            if not h.get("has_quranic_reference"):
                continue
            for ref in h.get("references", []):
                matches.append({
                    "hadith_id": h["hadith_id"],
                    "verse_key": ref["verse_key"],
                    "extracted_arabic": ref["extracted_arabic"],
                    "context": ref.get("context", ""),
                    "confidence": ref.get("confidence", "medium"),
                })

        if not matches:
            # Write empty file
            out_file = SNIPPET_DIR / rf.name
            with open(out_file, "w", encoding="utf-8") as f:
                json.dump([], f, ensure_ascii=False)
            continue

        # For each match, get verse text and tafsir snippets
        enriched = []
        for m in matches:
            hid = m["hadith_id"]
            vk = m["verse_key"]

            # Get verse text
            verse_info = get_verse_text(vk)

            # Check if already in insights
            insights = get_quranic_insights(hid)
            already_in_insights = vk in insights["verse_keys"]

            # Get tafsir snippets
            snippets = get_tafsir_for_verse(vk, limit=5)

            entry = {
                "hadith_id": hid,
                "verse_key": vk,
                "extracted_arabic": m["extracted_arabic"],
                "context": m["context"],
                "confidence": m["confidence"],
                "verse_text_arabic": verse_info["text_arabic"] if verse_info else "",
                "verse_text_english": verse_info["text_english"] if verse_info else "",
                "surah_name": verse_info["surah_name"] if verse_info else "",
                "already_in_insights": already_in_insights,
                "tafsir_snippets": snippets,
                "snippet_count": len(snippets),
            }

            enriched.append(entry)
            total_matches += 1
            total_snippets += len(snippets)
            if not already_in_insights:
                total_new_connections += 1

        # Write enriched file
        out_file = SNIPPET_DIR / rf.name
        with open(out_file, "w", encoding="utf-8") as f:
            json.dump(enriched, f, ensure_ascii=False, indent=2)

        processed += 1
        if processed % 20 == 0:
            print(f"  Processed {processed}/{len(result_files)} files, "
                  f"{total_matches} matches, {total_snippets} snippets, "
                  f"{total_new_connections} new connections")

        if limit and processed >= limit:
            break

    # Save summary
    summary = {
        "result_files_processed": processed,
        "total_hadith_verse_matches": total_matches,
        "total_tafsir_snippets": total_snippets,
        "new_connections": total_new_connections,
    }
    with open(SNIPPET_DIR / "_summary.json", "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)

    print(f"\n=== Done ===")
    print(f"  Files processed: {processed}")
    print(f"  Hadith-verse matches: {total_matches}")
    print(f"  Tafsir snippets fetched: {total_snippets}")
    print(f"  New connections (not in insights): {total_new_connections}")


if __name__ == "__main__":
    main()
