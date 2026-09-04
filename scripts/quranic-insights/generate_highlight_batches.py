#!/usr/bin/env python3
"""Generate input batches for <em> tag highlighting from ES.

Scans rewayaat_quranic_light_filtered, groups hadith into batches of ~50,
writes JSONL files for sub-agent processing.

Skips snippets that already have <em> tags in commentary_text_highlighted.

Usage:
    python3 scripts/generate_highlight_batches.py
    python3 scripts/generate_highlight_batches.py --force    # Re-generate all
    python3 scripts/generate_highlight_batches.py --batch-size 100
"""

import json
import os
import sys
from elasticsearch import Elasticsearch, helpers

ES_HOST = os.environ.get("ELASTICSEARCH_URL", "http://localhost:9200")
INDEX = "rewayaat_quranic_light_filtered"
OUTPUT_DIR = os.environ.get("HL_INPUT_DIR", "tmp/qlight-highlight-inputs")
FORCE = "--force" in sys.argv


def main():
    batch_size = int(os.environ.get("HL_BATCH_SIZE", "50"))
    if "--batch-size" in sys.argv:
        idx = sys.argv.index("--batch-size")
        batch_size = int(sys.argv[idx + 1])

    es = Elasticsearch(ES_HOST)
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    print(f"Scanning {INDEX} for snippets needing highlighting...")
    if FORCE:
        print("  --force: will include already-highlighted snippets")

    # Collect hadith with snippets that need highlighting
    batch = []
    batch_num = 0
    total_hadith = 0
    total_snippets = 0
    skipped_snippets = 0

    for hit in helpers.scan(es, index=INDEX, query={"query": {"match_all": {}}},
                            _source=["hadith_id", "hadith_english", "hadith_arabic",
                                     "candidates.verse_key", "candidates.verse_text",
                                     "candidates.surah_name_english",
                                     "candidates.tafsir_snippets"]):
        src = hit["_source"]
        hid = src["hadith_id"]

        # Build candidates with only snippets needing highlighting
        candidates_out = []
        doc_snippets = 0
        doc_skipped = 0

        for c in src.get("candidates", []):
            vk = c.get("verse_key", "")
            vt = c.get("verse_text", "")
            snips_out = []

            for s in c.get("tafsir_snippets", []):
                ct = s.get("commentary_text", "")
                if not ct or len(ct) < 30:
                    doc_skipped += 1
                    continue

                # Check if already highlighted
                highlighted = s.get("commentary_text_highlighted", "")
                if not FORCE and highlighted and "<em>" in highlighted:
                    doc_skipped += 1
                    continue

                snips_out.append({
                    "tafsir_slug": s.get("tafsir_slug", ""),
                    "tafsir_name": s.get("tafsir_name", ""),
                    "commentary_text": ct,
                })
                doc_snippets += 1

            if snips_out:
                candidates_out.append({
                    "verse_key": vk,
                    "verse_text": vt,
                    "snippets": snips_out,
                })

        if not candidates_out:
            skipped_snippets += doc_skipped
            continue

        batch.append({
            "hadith_id": hid,
            "hadith_english": (src.get("hadith_english") or "")[:500],
            "candidates": candidates_out,
        })
        total_snippets += doc_snippets
        skipped_snippets += doc_skipped

        if len(batch) >= batch_size:
            fn = os.path.join(OUTPUT_DIR, f"batch_{batch_num:04d}.jsonl")
            with open(fn, "w", encoding="utf-8") as f:
                for entry in batch:
                    f.write(json.dumps(entry, ensure_ascii=False) + "\n")
            total_hadith += len(batch)
            print(f"  Wrote {fn} ({len(batch)} hadith, running total: {total_hadith})")
            batch = []
            batch_num += 1

    # Write remaining
    if batch:
        fn = os.path.join(OUTPUT_DIR, f"batch_{batch_num:04d}.jsonl")
        with open(fn, "w", encoding="utf-8") as f:
            for entry in batch:
                f.write(json.dumps(entry, ensure_ascii=False) + "\n")
        total_hadith += len(batch)
        batch_num += 1
        print(f"  Wrote {fn} ({len(batch)} hadith)")

    print(f"\nDone!")
    print(f"  Batches: {batch_num}")
    print(f"  Hadith: {total_hadith}")
    print(f"  Snippets to highlight: {total_snippets}")
    print(f"  Snippets skipped (already done or too short): {skipped_snippets}")


if __name__ == "__main__":
    main()
