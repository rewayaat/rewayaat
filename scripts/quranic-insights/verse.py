#!/usr/bin/env python3
"""Look up verses, for the link reviewers. Offline, from the quran dump.

    python3 scripts/quranic-insights/verse.py 2:234 65:1
    python3 scripts/quranic-insights/verse.py --find "four months and ten"
"""

import argparse
import json
import os

QURAN = os.environ.get("QURAN_JSONL", "tmp/qlight-v2/sources/quran.jsonl")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("keys", nargs="*")
    ap.add_argument("--find", help="case-insensitive phrase to look for in the English")
    args = ap.parse_args()
    verses = [json.loads(l) for l in open(QURAN)]
    by_key = {v["_id"]: v for v in verses}
    for k in args.keys:
        v = by_key.get(k)
        print(f"{k}: NOT A VERSE" if not v else f"{k} | {v['text_arabic']}\n      {v['text_english']}")
    if args.find:
        needle = args.find.lower()
        hits = [v for v in verses if needle in (v.get("text_english") or "").lower()]
        for v in hits[:25]:
            print(f"{v['_id']} | {v['text_english'][:220]}")
        print(f"{len(hits)} verse(s) contain it")


if __name__ == "__main__":
    main()
