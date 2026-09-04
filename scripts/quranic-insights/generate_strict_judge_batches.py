#!/usr/bin/env python3
"""Generate input batches for strict re-judging of Quranic connections.

Scans rewayaat_quranic_light_filtered, groups hadith into batches of ~50,
writes JSONL files for sub-agent processing.

Usage:
    python3 scripts/quranic-insights/generate_strict_judge_batches.py
    python3 scripts/quranic-insights/generate_strict_judge_batches.py --batch-size 100
"""

import json
import os
import sys
from elasticsearch import Elasticsearch, helpers

ES_HOST = os.environ.get("ELASTICSEARCH_URL", "http://localhost:9200")
INDEX = "rewayaat_quranic_light_filtered"
OUTPUT_DIR = os.environ.get("STRICT_JUDGE_INPUT_DIR", "tmp/qlight-strict-judge-inputs")


def main():
    batch_size = 50
    if "--batch-size" in sys.argv:
        idx = sys.argv.index("--batch-size")
        batch_size = int(sys.argv[idx + 1])

    es = Elasticsearch(ES_HOST)
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    print(f"Scanning {INDEX} for hadith-candidate pairs to re-judge...")

    batch = []
    batch_num = 0
    total_hadith = 0
    total_connections = 0

    for hit in helpers.scan(es, index=INDEX, query={"query": {"match_all": {}}},
                            _source=["hadith_id", "hadith_english", "hadith_arabic",
                                     "candidates.verse_key", "candidates.verse_text",
                                     "candidates.surah_name_english",
                                     "candidates.tafsir_snippets.commentary_text"]):
        src = hit["_source"]
        hid = src["hadith_id"]

        # Build candidates list with snippet context
        candidates_out = []
        for c in src.get("candidates", []):
            vk = c.get("verse_key", "")
            vt = c.get("verse_text", "")
            surah = c.get("surah_name_english", "")

            # Get first snippet's commentary as context (truncated)
            snippets = c.get("tafsir_snippets", [])
            ctx = ""
            if snippets:
                ctx = (snippets[0].get("commentary_text") or "")[:400]

            candidates_out.append({
                "verse_key": vk,
                "verse_text": vt,
                "surah_name_english": surah,
                "snippet_context": ctx,
            })

        if not candidates_out:
            continue

        batch.append({
            "hadith_id": hid,
            "hadith_english": (src.get("hadith_english") or "")[:600],
            "hadith_arabic": (src.get("hadith_arabic") or "")[:300],
            "candidates": candidates_out,
        })
        total_connections += len(candidates_out)

        if len(batch) >= batch_size:
            fn = os.path.join(OUTPUT_DIR, f"batch_{batch_num:04d}.jsonl")
            with open(fn, "w", encoding="utf-8") as f:
                for entry in batch:
                    f.write(json.dumps(entry, ensure_ascii=False) + "\n")
            total_hadith += len(batch)
            print(f"  Wrote {fn} ({len(batch)} hadith, {total_connections} connections)")
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
    print(f"  Connections to judge: {total_connections}")


if __name__ == "__main__":
    main()
