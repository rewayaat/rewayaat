#!/usr/bin/env python3
"""Export hadith or quran documents from Elasticsearch into numbered batch JSON files."""
import argparse
import json
import os
import subprocess
import sys
from pathlib import Path


def curl_json(url, method="GET", payload=None, timeout=120):
    """Use curl for HTTP requests to avoid urllib issues."""
    cmd = ["curl", "-sS", "-X", method, "--max-time", str(timeout)]
    if payload is not None:
        cmd += ["-H", "Content-Type: application/json",
                "-d", json.dumps(payload)]
    cmd.append(url)
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout + 30)
    if result.returncode != 0:
        raise RuntimeError(f"curl failed ({result.returncode}): {result.stderr}")
    return json.loads(result.stdout) if result.stdout.strip() else {}


def scroll_all(es_url, index, batch_size, fields):
    body = {
        "size": batch_size,
        "_source": fields,
        "query": {"match_all": {}},
        "sort": ["_doc"],
    }
    url = f"{es_url}/{index}/_search?scroll=6h"
    resp = curl_json(url, "POST", body)
    scroll_id = resp.get("_scroll_id", "")

    while True:
        hits = resp.get("hits", {}).get("hits", [])
        if not hits:
            break
        yield hits
        resp = curl_json(f"{es_url}/_search/scroll", "POST", {
            "scroll": "6h",
            "scroll_id": scroll_id,
        })

    try:
        curl_json(f"{es_url}/_search/scroll", "DELETE", {"scroll_id": scroll_id})
    except Exception:
        pass


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--es-url", default="http://localhost:9200")
    parser.add_argument("--index", required=True)
    parser.add_argument("--batch-size", type=int, default=500)
    parser.add_argument("--outdir", required=True)
    args = parser.parse_args()

    Path(args.outdir).mkdir(parents=True, exist_ok=True)

    if "quran" in args.index:
        fields = ["surah_number", "ayah_number", "text_arabic", "text_english",
                   "surah_name_english", "topic_tags"]
    else:
        fields = ["book", "chapter", "section", "english", "arabic",
                   "semantic_matn_source", "semantic_english_hint_source", "topic_tags"]

    total = 0
    batch_num = 0
    for hits in scroll_all(args.es_url, args.index, args.batch_size, fields):
        batch_num += 1
        docs = []
        for hit in hits:
            src = hit["_source"]
            doc = {"_id": hit["_id"]}
            if "quran" in args.index:
                doc["english"] = (src.get("text_english") or "")[:2200]
                doc["arabic"] = (src.get("text_arabic") or "")[:2200]
                doc["reference"] = f"{src.get('surah_name_english', '')} {src.get('ayah_number', '')}"
            else:
                doc["english"] = (src.get("semantic_english_hint_source")
                                  or src.get("english") or "")[:2200]
                doc["arabic"] = (src.get("semantic_matn_source") or "")[:2200]
                doc["book"] = src.get("book", "")
                doc["chapter"] = src.get("chapter", "")
                doc["section"] = src.get("section", "")
            doc["current_tags"] = src.get("topic_tags") or []
            docs.append(doc)

        out_path = Path(args.outdir) / f"batch_{batch_num:04d}.json"
        out_path.write_text(json.dumps(docs, ensure_ascii=False, indent=1), encoding="utf-8")
        total += len(docs)
        print(f"  batch_{batch_num:04d}.json  ({len(docs)} docs, total={total})")

    manifest = {"index": args.index, "batch_size": args.batch_size,
                "total_docs": total, "total_batches": batch_num}
    (Path(args.outdir) / "manifest.json").write_text(
        json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"\nExported {total} documents in {batch_num} batches to {args.outdir}")


if __name__ == "__main__":
    main()
