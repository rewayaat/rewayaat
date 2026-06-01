#!/usr/bin/env python3
"""Export hadith + candidates from ES into JSONL batches for LLM judging.
Each line = one hadith with its candidates and tafsir snippets."""

import json
import os
import argparse
from elasticsearch import Elasticsearch

ES_HOST = "http://localhost:9200"
INDEX = "rewayaat_quranic_light_filtered"
OUTPUT_DIR = "tmp/qlight-judge-inputs"
BATCH_SIZE = 50  # hadith per batch


def export_batches(limit=0):
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    es = Elasticsearch(ES_HOST)
    total = es.count(index=INDEX)["count"]
    if limit:
        total = min(total, limit)

    batch_num = 0
    offset = 0
    exported = 0

    # Use scroll API to iterate all docs
    scroll_id = None
    hits = es.search(index=INDEX, body={
        "query": {"match_all": {}},
        "size": BATCH_SIZE,
    }, scroll="5m")
    scroll_id = hits["_scroll_id"]

    while hits["hits"]["hits"]:
        batch_num += 1
        out_file = f"{OUTPUT_DIR}/batch_{batch_num:04d}.jsonl"

        with open(out_file, "w") as f:
            for hit in hits["hits"]["hits"]:
                src = hit["_source"]
                candidates = []
                for c in src.get("candidates", []):
                    snippets = []
                    for s in c.get("tafsir_snippets", []):
                        ct = s.get("commentary_text", "")
                        # Strip em tags for the judge
                        clean = ct.replace("<em>", "").replace("</em>", "")
                        snippets.append({
                            "tafsir_name": s.get("tafsir_name", ""),
                            "commentary_text": clean[:800],  # truncate long snippets
                        })
                    candidates.append({
                        "verse_key": c.get("verse_key", ""),
                        "surah_name": c.get("surah_name_english", ""),
                        "verse_text": c.get("text_english", ""),
                        "snippets": snippets,
                    })

                entry = {
                    "hadith_id": hit["_id"],
                    "hadith_english": (src.get("hadith_english") or "")[:800],
                    "hadith_arabic": (src.get("hadith_semantic_matn_source") or "")[:200],
                    "candidates": candidates,
                }
                f.write(json.dumps(entry, ensure_ascii=False) + "\n")
                exported += 1

        offset += BATCH_SIZE
        if offset % 1000 < BATCH_SIZE:
            print(f"  Exported {exported} hadith in {batch_num} batches...")

        hits = es.scroll(scroll_id=scroll_id, scroll="5m")

    print(f"Done: {exported} hadith in {batch_num} batches -> {OUTPUT_DIR}/")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--limit", type=int, default=0)
    args = parser.parse_args()
    export_batches(args.limit)
