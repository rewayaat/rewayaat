#!/usr/bin/env python3
"""Fetch search results from the API and batch them into JSON files for sub-agent processing.

Uses multiple search queries to find hadith that reference Quranic verses.
Deduplicates by hadith ID.

Usage:
    python3 scripts/annotation/fetch_and_batch_results.py
"""
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path


API_BASE = os.environ.get("REWAYAAT_API", "https://hadith.academyofislam.com")
OUTPUT_DIR = Path("tmp/quranic-snippet-matches/batches")
PAGE_SIZE = 50
BATCH_SIZE = 10  # hadith per batch file

# Multiple search queries that indicate Quranic references in hadith
# Only use queries that strongly indicate actual Quranic verse quotation
SEARCH_QUERIES = [
    'عز وجل يقول',         # The Almighty says (most common, 4.8K results)
    'في قوله عز وجل',       # In His saying, the Almighty
    'جل جلاله يقول',        # The Majestic says
    'سبحانه وتعالى يقول',   # The Glorious and Exalted says
    'في تفسير قوله',        # In the interpretation of His saying
]


def http_json(url, timeout=30):
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def strip_html(text):
    return re.sub(r'<[^>]+>', '', text).strip()


def main():
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    all_results = {}  # keyed by hadith ID for dedup

    for qi, query in enumerate(SEARCH_QUERIES):
        print(f"\n[{qi+1}/{len(SEARCH_QUERIES)}] Fetching: {query}")
        page = 1
        query_count = 0

        while True:
            url = (f"{API_BASE}/v1/narrations?"
                   f"q={urllib.parse.quote(query)}"
                   f"&match_mode=flexible"
                   f"&per_page={PAGE_SIZE}"
                   f"&page={page}")

            try:
                data = http_json(url, timeout=30)
            except Exception as e:
                print(f"  Error on page {page}: {e}")
                break

            collection = data.get("collection", [])
            total = data.get("totalResultSetSize", 0)

            if not collection:
                break

            for h in collection:
                hid = h["_id"]
                if hid not in all_results:
                    all_results[hid] = {
                        "id": hid,
                        "book": h.get("book", ""),
                        "number": h.get("number", ""),
                        "chapter": h.get("chapter", ""),
                        "section": h.get("section", ""),
                        "arabic": strip_html(h.get("arabic", "")),
                        "english": strip_html(h.get("english", "")),
                        "matched_by": [query],
                    }
                else:
                    all_results[hid]["matched_by"].append(query)

            query_count += len(collection)
            print(f"  Page {page}: {len(collection)} results (query total: {query_count}/{total})")

            if query_count >= total:
                break
            page += 1
            time.sleep(0.3)

        print(f"  Unique after this query: {len(all_results)}")

    results_list = sorted(all_results.values(), key=lambda x: x["id"])
    print(f"\n=== Total unique hadith: {len(results_list)} ===")

    # Batch into files
    num_batches = (len(results_list) + BATCH_SIZE - 1) // BATCH_SIZE
    for i in range(num_batches):
        batch = results_list[i * BATCH_SIZE : (i + 1) * BATCH_SIZE]
        filename = OUTPUT_DIR / f"batch_{i:04d}.json"
        with open(filename, "w", encoding="utf-8") as f:
            json.dump(batch, f, ensure_ascii=False, indent=2)

    print(f"Created {num_batches} batch files in {OUTPUT_DIR}/")
    print(f"Batch size: {BATCH_SIZE} hadith each")

    # Save summary
    summary = {
        "total_unique_hadith": len(results_list),
        "total_batches": num_batches,
        "batch_size": BATCH_SIZE,
        "queries_used": SEARCH_QUERIES,
    }
    with open(OUTPUT_DIR / "_summary.json", "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    main()
