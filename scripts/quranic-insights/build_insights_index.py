#!/usr/bin/env python3
"""Build the v2 Quranic-insights documents from the link review, offline, as JSONL.

One document per narration that kept at least one link. Each link carries the reviewer's
kind and reason (English and Arabic), the verse, and up to three cleaned English tafsir
passages plus one Arabic one, chosen by snippets.py. The document also carries `quotes`:
every place the narration's own text quotes or cites a kept verse, with offsets, so a
page can turn that text into a link to the verse.

    python3 scripts/quranic-insights/build_insights_index.py --generated-at 2026-11-25T00:00:00Z
        # reads tmp/qlight-v2/{sources,review/inputs,review/outputs,quoted_verses.jsonl}
        # writes tmp/qlight-v2/insights_v2.jsonl and tmp/qlight-v2/insights_v2_mapping.json

Load with load_insights_index.py.
"""

import argparse
import json
import os
import re
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import snippets as S  # noqa: E402

KIND_ORDER = {"quoted": 0, "explains": 1, "ruling": 2, "theme": 3}

MAPPING = {
    "dynamic": False,
    "properties": {
        "hadith_id": {"type": "keyword"},
        "hadith_book": {"type": "keyword"},
        "top_verse_keys": {"type": "keyword"},
        "candidate_count": {"type": "integer"},
        "generated_at": {"type": "date"},
        "version": {"type": "keyword"},
    },
}


def jsonl(path):
    with open(path) as f:
        for line in f:
            if line.strip():
                yield json.loads(line)


def tafsir_by_verse(path):
    by = defaultdict(list)
    for d in jsonl(path):
        keys = d.get("verse_keys") or ([d["verse_key"]] if d.get("verse_key") else [])
        if not keys and d.get("surah_number") and d.get("ayah_start"):
            end = d.get("ayah_end") or d["ayah_start"]
            keys = [f"{d['surah_number']}:{a}" for a in range(d["ayah_start"], end + 1)]
        for k in keys:
            by[k].append(d)
    return by


def snippet_record(s, i):
    d = s["doc"]
    return {
        "tafsir_slug": s["slug"],
        "tafsir_name": d.get("tafsir_name") or s["slug"],
        "section_title": d.get("section_title") or "",
        "source_url": d.get("source_url") or "",
        "language": s["lang"],
        "commentary_text": s["text"],
        "commentary_text_highlighted": s["highlighted"] or "",
        "preview": S.preview(s["text"], s["highlighted"]),
        "commentary_score": float(100 - i),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default="tmp/qlight-v2")
    ap.add_argument("--generated-at", required=True)
    args = ap.parse_args()
    root = args.root

    verses = {v["_id"]: v for v in jsonl(f"{root}/sources/quran.jsonl")}
    tafsir = tafsir_by_verse(f"{root}/sources/tafsir.jsonl")
    hadith = {d["_id"]: d for d in jsonl(f"{root}/sources/hadith.jsonl")}
    live = {}
    for d in jsonl(f"{root}/sources/light.jsonl"):
        for c in d.get("candidates") or []:
            live[(d["_id"], c.get("verse_key"))] = c
    quoted = {d["hadith_id"]: d["quotes"] for d in jsonl(f"{root}/quoted_verses.jsonl")}
    inputs = {}
    for f in sorted(os.listdir(f"{root}/review/inputs")):
        for d in jsonl(f"{root}/review/inputs/{f}"):
            inputs[d["hadith_id"]] = d

    out_path = f"{root}/insights_v2.jsonl"
    stats = defaultdict(int)
    with open(out_path, "w") as out:
        for f in sorted(os.listdir(f"{root}/review/outputs")):
            for o in jsonl(f"{root}/review/outputs/{f}"):
                hid = o["hadith_id"]
                stats["reviewed"] += 1
                inp = {c["verse_key"]: c for c in inputs.get(hid, {}).get("candidates", [])}
                kept = [ln for ln in o["links"] if ln.get("keep") and ln["verse_key"] in verses]
                if not kept:
                    stats["no_links"] += 1
                    continue
                # Where each verse is first quoted in the narration, so quoted verses read
                # in the order the narration recites them.
                first_at = {}
                for q in quoted.get(hid, []):
                    pos = q["start"] if q["field"] == "arabic" else 10**6 + q["start"]
                    for k in [q["verse_key"]] + q.get("also_matches", []):
                        first_at[k] = min(first_at.get(k, 10**9), pos)

                def verse_number(vk):
                    s_, a_ = vk.split(":")
                    return int(s_) * 1000 + int(a_)

                kept.sort(key=lambda ln: (
                    KIND_ORDER.get(ln.get("kind"), 9),
                    first_at.get(ln["verse_key"], 10**9),
                    -((live.get((hid, ln["verse_key"])) or {}).get("combined_score") or 0),
                    verse_number(ln["verse_key"])))
                kept_keys = [ln["verse_key"] for ln in kept]

                # Where the narration's own text quotes or cites a kept verse.
                spans = []
                for q in quoted.get(hid, []):
                    target = q["verse_key"] if q["verse_key"] in kept_keys else next(
                        (k for k in q.get("also_matches", []) if k in kept_keys), None)
                    if not target:
                        continue
                    span = {"verse_key": target, "source": q["source"], "field": q["field"],
                            "start": q["start"], "end": q["end"], "text": q["text"]}
                    spans.append(span)
                    if q.get("quoted_english"):
                        qe = q["quoted_english"]
                        spans.append({"verse_key": target, "source": "reference_quote", "field": "english",
                                      "start": qe["start"], "end": qe["end"], "text": qe["text"]})

                candidates = []
                for rank, ln in enumerate(kept):
                    vk = ln["verse_key"]
                    v = verses[vk]
                    lv = live.get((hid, vk)) or {}
                    english, arabic = S.choose(vk, v.get("text_arabic") or "", tafsir.get(vk, []),
                                               lv.get("tafsir_snippets"))
                    chosen = english + ([arabic] if arabic else [])
                    snips = [snippet_record(s, i) for i, s in enumerate(chosen)]
                    stats["links"] += 1
                    stats[f"kind_{ln.get('kind')}"] += 1
                    stats["added" if ln.get("added") else "kept_candidate"] += 1
                    if not english:
                        stats["links_without_english_tafsir"] += 1
                    stats["snippets"] += len(snips)
                    candidates.append({
                        "verse_key": vk,
                        "surah_number": v.get("surah_number"),
                        "ayah_number": v.get("ayah_number"),
                        "surah_name_english": v.get("surah_name_english") or "",
                        "surah_name_arabic": v.get("surah_name_arabic") or "",
                        "text_arabic": v.get("text_arabic") or "",
                        "text_english": v.get("text_english") or "",
                        "kind": ln.get("kind"),
                        "reason": ln.get("reason_en", "").strip(),
                        "reason_ar": ln.get("reason_ar", "").strip(),
                        "quoted": any(sp["verse_key"] == vk for sp in spans),
                        "rank": rank,
                        "combined_score": float(len(kept) - rank),
                        "tafsir_snippets": snips,
                    })
                h = hadith.get(hid, {})
                out.write(json.dumps({
                    "hadith_id": hid,
                    "hadith_book": h.get("book") or "",
                    "hadith_chapter": re.sub(r"<[^>]+>", "", h.get("chapter") or ""),
                    "candidate_count": len(candidates),
                    "top_verse_keys": kept_keys,
                    "quotes": spans,
                    "candidates": candidates,
                    "generated_at": args.generated_at,
                    "version": "v2",
                }, ensure_ascii=False) + "\n")
                stats["docs"] += 1
    with open(f"{root}/insights_v2_mapping.json", "w") as m:
        json.dump(MAPPING, m, indent=1)
    for k in sorted(stats):
        print(f"{k}: {stats[k]}")
    print(f"wrote {out_path}")


if __name__ == "__main__":
    main()
