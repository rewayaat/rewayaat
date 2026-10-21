#!/usr/bin/env python3
"""Mends the «س» that OpenITI's al-Fihrist lost, in the artifacts already built from it.

`extract_openiti.py` now repairs the source as it reads it, so anything rebuilt from here on
is clean. This is for what was built before: the book extractions, the people index the chain
resolver reads, and the narrator index Elasticsearch serves.

The damage and the reason the repair is safe are documented at MANGLED_SIN in
extract_openiti.py. In short: al-Fihrist lost almost every word-final «س» and put «أآلهتنا»
in its place, so «سليم بن قيس الهلالي» is filed as «سليم بن قيأآلهتنا الهلالي». The rule only
fires where an Arabic letter stands immediately before, which is what separates a severed word
from «أَآلِهَتُنَا», a real word the Qur'an uses — and it is pointed by hand at narrator files,
never at narration text.

Usage:
    python3 scripts/narrators/mend_severed_sin.py --files        # the artifacts on disk
    python3 scripts/narrators/mend_severed_sin.py --live         # the narrator index
"""
import argparse
import json
import os
import re
import sys

W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
sys.path.insert(0, f'{W}/scripts/narrators')
from extract_openiti import MANGLED_SIN                           # noqa: E402

TMP = f'{W}/tmp'
ES = os.environ.get("ES_HOST", "http://localhost:9200")
INDEX = "rewayaat_narrators"
CHUNK = 8 << 20          # people_index.json is one 100MB line; it is read in pieces
CARRY = 64               # kept between pieces so a severed word is never split in two

FILES = [
    f'{TMP}/narrators_chains/people_index.json',
    f'{TMP}/narrators_book_fihrist.json',
    f'{TMP}/narrators_book_fihrist_openiti.json',
    f'{TMP}/narrators_book_tusi.json',
    f'{TMP}/narrators_book_tusi_openiti.json',
    f'{TMP}/narrators_sources/narrators_book_fihrist.before_moveflags.json',
]


def mend_file(path):
    """Rewrite one file in place, reading it in pieces so a 100MB line costs 8MB of memory."""
    if not os.path.exists(path):
        return None
    out = path + ".mending"
    mended = 0
    with open(path, encoding="utf-8") as src, open(out, "w", encoding="utf-8") as dst:
        carry = ""
        while True:
            piece = src.read(CHUNK)
            if not piece:
                break
            piece = carry + piece
            # hold back the tail, in case a severed word straddles the join
            keep, piece = piece[-CARRY:], piece[:-CARRY] if len(piece) > CARRY else ("", piece)
            fixed, n = MANGLED_SIN.subn("س", piece)
            mended += n
            dst.write(fixed)
            carry = keep
        fixed, n = MANGLED_SIN.subn("س", carry)
        mended += n
        dst.write(fixed)
    os.replace(out, path)
    return mended


def post(path, body):
    from urllib.request import Request, urlopen
    call = Request(f"{ES}{path}", data=body.encode() if isinstance(body, str)
                   else json.dumps(body).encode(),
                   headers={"Content-Type": "application/x-ndjson" if isinstance(body, str)
                            else "application/json"})
    return json.load(urlopen(call, timeout=120))


def mend_value(value):
    """Mend every string inside a document, however deep it sits."""
    if isinstance(value, str):
        fixed, n = MANGLED_SIN.subn("س", value)
        return fixed, n
    if isinstance(value, list):
        out, total = [], 0
        for item in value:
            got, n = mend_value(item)
            out.append(got)
            total += n
        return out, total
    if isinstance(value, dict):
        out, total = {}, 0
        for key, item in value.items():
            got, n = mend_value(item)
            out[key] = got
            total += n
        return out, total
    return value, 0


def mend_live(dry):
    after, seen, touched, mended = None, 0, [], 0
    while True:
        body = {"size": 500, "sort": ["person_id"]}
        if after:
            body["search_after"] = after
        hits = post(f"/{INDEX}/_search", body)["hits"]["hits"]
        if not hits:
            break
        for hit in hits:
            seen += 1
            fixed, n = mend_value(hit["_source"])
            if n:
                mended += n
                touched.append((hit["_id"], fixed, n))
        after = hits[-1]["sort"]
    print(f"  {seen:,} records read, {len(touched):,} carry a severed «س» "
          f"({mended:,} occurrences)")
    for person, _, n in touched[:6]:
        print(f"     {person}  ×{n}")
    if dry:
        print("  dry run: nothing written")
        return
    for at in range(0, len(touched), 200):
        lines = []
        for person, fixed, _ in touched[at:at + 200]:
            lines.append(json.dumps({"index": {"_index": INDEX, "_id": person}}))
            lines.append(json.dumps(fixed, ensure_ascii=False))
        got = post("/_bulk?refresh=true", "\n".join(lines) + "\n")
        if got.get("errors"):
            bad = [i for i in got["items"] if i.get("index", {}).get("error")]
            print(f"  ! {len(bad)} failed; first: {bad[0]['index']['error']}")
            return
    print(f"  wrote {len(touched):,} records")


# What the transliterator made of «أآلهتنا» before the Arabic was mended: «Idris» was written
# «Idriaalhatna», and the slug followed it into the URL.
SEVERED_EN = re.compile(r"a?alhatn|alihatun|lhatna", re.I)


def mend_english(dry):
    """Remake the English name from the mended Arabic, for the records that were built from it.

    Only names this pipeline made itself are touched: where a rijālī book gives a man an
    English name of its own, `display_en_made` is false and the book's name stands.
    """
    sys.path.insert(0, f"{W}/scripts/narrators")
    import index_people
    import transliterate
    learned = transliterate.load(transliterate.MAP)
    after, touched = None, []
    while True:
        body = {"size": 500, "sort": ["person_id"]}
        if after:
            body["search_after"] = after
        hits = post(f"/{INDEX}/_search", body)["hits"]["hits"]
        if not hits:
            break
        for hit in hits:
            person = hit["_source"]
            english = person.get("display_en") or ""
            if not SEVERED_EN.search(english):
                continue
            if not person.get("display_en_made"):
                print(f"  ! {hit['_id']} carries a book's own English name; left alone")
                continue
            made = transliterate.english_for(person.get("display_ar", ""), learned)
            if not made:
                print(f"  ! {hit['_id']} could not be transliterated; left alone")
                continue
            person["display_en"] = made
            person["slug"] = index_people.slugify(made)
            touched.append((hit["_id"], english, made, person))
        after = hits[-1]["sort"]
    print(f"  {len(touched):,} English names made from a severed Arabic name")
    for person, was, now, _ in touched[:8]:
        print(f"     {person}  {was[:40]:42s} -> {now[:40]}")
    if dry:
        print("  dry run: nothing written")
        return
    for at in range(0, len(touched), 200):
        lines = []
        for person, _, _, fixed in touched[at:at + 200]:
            lines.append(json.dumps({"index": {"_index": INDEX, "_id": person}}))
            lines.append(json.dumps(fixed, ensure_ascii=False))
        got = post("/_bulk?refresh=true", "\n".join(lines) + "\n")
        if got.get("errors"):
            bad = [i for i in got["items"] if i.get("index", {}).get("error")]
            print(f"  ! {len(bad)} failed; first: {bad[0]['index']['error']}")
            return
    print(f"  wrote {len(touched):,} records")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--files", action="store_true", help="mend the artifacts on disk")
    parser.add_argument("--live", action="store_true", help="mend the narrator index")
    parser.add_argument("--english", action="store_true",
                        help="remake the English name and slug of records transliterated "
                             "from a severed name")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if args.files:
        for path in FILES:
            mended = mend_file(path) if not args.dry_run else None
            name = os.path.relpath(path, W)
            if mended is None:
                print(f"  {name}: {'skipped (dry run)' if args.dry_run else 'not here'}")
            else:
                print(f"  {name}: mended {mended:,}")
    if args.live:
        mend_live(args.dry_run)
    if args.english:
        mend_english(args.dry_run)
    if not args.files and not args.live and not args.english:
        parser.print_help()


if __name__ == "__main__":
    main()
