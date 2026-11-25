#!/usr/bin/env python3
"""Check a review output batch against its input. Exits non-zero on any error.

    python3 scripts/quranic-insights/validate_review.py <input.jsonl> <output.jsonl>
    python3 scripts/quranic-insights/validate_review.py --all <inputs dir> <outputs dir>

Added verses are checked against the Qur'an text: check_ar must match the verse's
opening on the consonant skeleton quoted_verses.py uses.
"""

import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from quoted_verses import tokens  # noqa: E402

QURAN = os.environ.get("QURAN_JSONL", "tmp/qlight-v2/sources/quran.jsonl")
KINDS = {"quoted", "explains", "ruling", "theme"}
ARABIC = re.compile(r"[؀-ۿ]")
MAX_EN_WORDS = 40          # the instructions say 30; a little slack before it is an error
MAX_ADDED = 2

_verses = None


def verses():
    global _verses
    if _verses is None:
        _verses = {}
        for line in open(QURAN):
            v = json.loads(line)
            _verses[v["_id"]] = [t[0] for t in tokens(v["text_arabic"])]
    return _verses


def check_ar_matches(verse_key, check_ar):
    words = [t[0] for t in tokens(check_ar)]
    vw = verses().get(verse_key)
    if not vw or len(words) < 2:
        return False
    # The opening, or failing that any run in the verse (a reviewer may copy from mid-verse).
    n = len(words)
    return any(vw[i:i + n] == words for i in range(len(vw) - n + 1))


def validate(inp_path, out_path):
    errors = []
    inputs = [json.loads(l) for l in open(inp_path) if l.strip()]
    try:
        outputs = [json.loads(l) for l in open(out_path) if l.strip()]
    except (OSError, json.JSONDecodeError) as e:
        return [f"cannot read output: {e}"]
    by_id = {}
    for o in outputs:
        if o.get("hadith_id") in by_id:
            errors.append(f"{o.get('hadith_id')}: appears twice")
        by_id[o.get("hadith_id")] = o
    for item in inputs:
        hid = item["hadith_id"]
        o = by_id.pop(hid, None)
        if o is None:
            errors.append(f"{hid}: missing from output")
            continue
        links = o.get("links")
        if not isinstance(links, list):
            errors.append(f"{hid}: links is not a list")
            continue
        want = {c["verse_key"] for c in item["candidates"]}
        seen = set()
        added = 0
        for ln in links:
            vk = ln.get("verse_key")
            where = f"{hid} {vk}"
            if vk in seen:
                errors.append(f"{where}: listed twice")
            seen.add(vk)
            if vk not in verses():
                errors.append(f"{where}: not a verse key")
                continue
            if ln.get("added"):
                added += 1
                if vk in want:
                    errors.append(f"{where}: marked added but was a candidate")
                if not check_ar_matches(vk, ln.get("check_ar") or ""):
                    errors.append(f"{where}: check_ar does not match the verse text")
            elif vk not in want:
                errors.append(f"{where}: not a candidate and not marked added")
            if not isinstance(ln.get("keep"), bool):
                errors.append(f"{where}: keep must be true or false")
            if ln.get("keep"):
                if ln.get("kind") not in KINDS:
                    errors.append(f"{where}: kind must be one of {sorted(KINDS)}")
                en = (ln.get("reason_en") or "").strip()
                ar = (ln.get("reason_ar") or "").strip()
                if not en or len(en.split()) > MAX_EN_WORDS:
                    errors.append(f"{where}: reason_en missing or over {MAX_EN_WORDS} words")
                if not ar or not ARABIC.search(ar):
                    errors.append(f"{where}: reason_ar missing or not Arabic")
                if ARABIC.search(en):
                    errors.append(f"{where}: reason_en contains Arabic")
        missing = want - seen
        if missing:
            errors.append(f"{hid}: candidates not decided: {sorted(missing)}")
        if added > MAX_ADDED:
            errors.append(f"{hid}: {added} verses added, at most {MAX_ADDED}")
    for hid in by_id:
        errors.append(f"{hid}: in output but not in input")
    return errors


def main():
    if sys.argv[1] == "--all":
        inp_dir, out_dir = sys.argv[2], sys.argv[3]
        bad = 0
        names = sorted(f for f in os.listdir(inp_dir) if f.endswith(".jsonl"))
        done = [f for f in names if os.path.exists(os.path.join(out_dir, f))]
        for f in done:
            errs = validate(os.path.join(inp_dir, f), os.path.join(out_dir, f))
            if errs:
                bad += 1
                print(f"{f}: {len(errs)} error(s), first: {errs[0]}")
        print(f"{len(done)}/{len(names)} batches have output; {bad} with errors")
        sys.exit(1 if bad else 0)
    errs = validate(sys.argv[1], sys.argv[2])
    for e in errs[:60]:
        print(e)
    print("OK" if not errs else f"{len(errs)} error(s)")
    sys.exit(1 if errs else 0)


if __name__ == "__main__":
    main()
