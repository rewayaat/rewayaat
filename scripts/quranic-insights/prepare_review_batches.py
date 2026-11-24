#!/usr/bin/env python3
"""Build the batches for the link review: every narration that has a live link or quotes
a verse, with the verses it could be linked to.

A candidate is one of:
* a live link from rewayaat_quranic_light_filtered scoring at least MIN_SCORE, or
* a verse the narration quotes (Arabic) or cites (English), from quoted_verses.py.

Live links under MIN_SCORE are dropped here and never shown to the reviewer: in the
2026-11-24 spot check every one of them was a loose word match.

    python3 scripts/quranic-insights/prepare_review_batches.py \
        --sources tmp/qlight-v2/sources --quoted tmp/qlight-v2/quoted_verses.jsonl \
        --out tmp/qlight-v2/review/inputs
"""

import argparse
import json
import os
import random
import re

MIN_SCORE = 5.0
BATCH_SIZE = 50
ENGLISH_MAX = 4000
VERSE_MAX = 360


def strip_html(text):
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", text or "")).strip()


def cut(text, n):
    return text if len(text) <= n else text[:n].rsplit(" ", 1)[0] + " …"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sources", required=True)
    ap.add_argument("--quoted", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--seed", type=int, default=20261125)
    ap.add_argument("--skip-reviewed", help="outputs dir: narrations already reviewed there are left out")
    ap.add_argument("--first-batch", type=int, default=1)
    args = ap.parse_args()

    verses = {}
    for line in open(os.path.join(args.sources, "quran.jsonl")):
        v = json.loads(line)
        verses[v["_id"]] = v
    hadith = {}
    for line in open(os.path.join(args.sources, "hadith.jsonl")):
        d = json.loads(line)
        hadith[d["_id"]] = d
    live = {}
    for line in open(os.path.join(args.sources, "light.jsonl")):
        d = json.loads(line)
        if d.get("candidates"):
            live[d["_id"]] = d["candidates"]
    quoted = {}
    for line in open(args.quoted):
        d = json.loads(line)
        quoted[d["hadith_id"]] = d["quotes"]

    reviewed = set()
    if args.skip_reviewed:
        for f in os.listdir(args.skip_reviewed):
            for line in open(os.path.join(args.skip_reviewed, f)):
                reviewed.add(json.loads(line)["hadith_id"])

    items = []
    dropped_low = 0
    for hid in sorted(set(live) | set(quoted)):
        h = hadith.get(hid)
        if not h or hid in reviewed:
            continue
        cands = {}

        def entry(vk):
            v = verses[vk]
            return cands.setdefault(vk, {
                "verse_key": vk,
                "verse_en": cut(v.get("text_english") or "", VERSE_MAX),
            })

        for q in quoted.get(hid, []):
            if q["verse_key"] not in verses:
                continue
            e = entry(q["verse_key"])
            if q["source"] == "arabic_quote":
                e["quoted_ar"] = q["text"]
                if q.get("also_matches"):
                    e["same_words_also_in"] = q["also_matches"]
            else:
                e["cited_as"] = q["text"]
        for c in live.get(hid, []):
            vk = c.get("verse_key")
            if vk not in verses:
                continue
            score = c.get("combined_score") or 0
            if score < MIN_SCORE and vk not in cands:
                dropped_low += 1
                continue
            entry(vk)["current_link"] = True
        if not cands:
            continue
        items.append({
            "hadith_id": hid,
            "book": h.get("book"),
            "chapter": strip_html(h.get("chapter")),
            "english": cut(strip_html(h.get("english")), ENGLISH_MAX),
            "candidates": list(cands.values()),
        })

    random.Random(args.seed).shuffle(items)
    os.makedirs(args.out, exist_ok=True)
    n_batches = args.first_batch - 1
    for i in range(0, len(items), BATCH_SIZE):
        n_batches += 1
        with open(os.path.join(args.out, f"batch_{n_batches:04d}.jsonl"), "w") as out:
            for item in items[i:i + BATCH_SIZE]:
                out.write(json.dumps(item, ensure_ascii=False) + "\n")
    n_cands = sum(len(it["candidates"]) for it in items)
    print(f"{len(items)} narrations, {n_cands} candidates, {n_batches} batches; "
          f"{dropped_low} live links under {MIN_SCORE} left out; {len(reviewed)} already reviewed")


if __name__ == "__main__":
    main()
