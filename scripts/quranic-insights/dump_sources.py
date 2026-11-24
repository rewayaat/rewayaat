#!/usr/bin/env python3
"""Dump the four indices the Quranic-insight pipeline reads, as JSONL, for offline work.

Read-only. Each line is {"_id": ..., **_source}. The hadith dump drops the fields the
pipeline never reads (vectors, chain links, similar lists) to keep it small.

    python3 scripts/quranic-insights/dump_sources.py --es-host http://localhost:9201 \
        --out tmp/qlight-v2/sources
"""

import argparse
import json
import os
import urllib.request

HADITH_FIELDS = ["book", "number", "volume", "part", "section", "chapter",
                 "english", "arabic", "topic_tags", "tags"]

DUMPS = [
    ("hadith", "rewayaat_hadith", HADITH_FIELDS),
    ("quran", "rewayaat_quran", None),
    ("tafsir", "rewayaat_tafsir", None),
    ("light", "rewayaat_quranic_light_filtered", None),
]


def post(url, body):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as resp:
        return json.load(resp)


def dump(host, index, fields, path):
    body = {"size": 500, "sort": ["_doc"], "query": {"match_all": {}}}
    if fields:
        body["_source"] = fields
    page = post(f"{host}/{index}/_search?scroll=5m", body)
    scroll_id, n = page["_scroll_id"], 0
    with open(path + ".part", "w") as out:
        while page["hits"]["hits"]:
            for hit in page["hits"]["hits"]:
                out.write(json.dumps({"_id": hit["_id"], **hit["_source"]}, ensure_ascii=False) + "\n")
                n += 1
            page = post(f"{host}/_search/scroll", {"scroll": "5m", "scroll_id": scroll_id})
            scroll_id = page["_scroll_id"]
    req = urllib.request.Request(f"{host}/_search/scroll", method="DELETE",
                                 data=json.dumps({"scroll_id": scroll_id}).encode(),
                                 headers={"Content-Type": "application/json"})
    urllib.request.urlopen(req, timeout=60).read()
    os.replace(path + ".part", path)
    return n


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--es-host", default="http://localhost:9200")
    ap.add_argument("--out", required=True)
    ap.add_argument("--only", nargs="*", help="names to dump: hadith quran tafsir light")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    for name, index, fields in DUMPS:
        if args.only and name not in args.only:
            continue
        n = dump(args.es_host.rstrip("/"), index, fields, os.path.join(args.out, f"{name}.jsonl"))
        print(f"{name}: {n} docs from {index}")


if __name__ == "__main__":
    main()
