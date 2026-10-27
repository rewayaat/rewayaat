#!/usr/bin/env python3
"""Records whose heading is a name lifted out of a chain clause about somebody else.

Khoei's Mu'jam indexes narrations as well as men. Among its profiles are entries whose whole
text is one relation — «وروى عنه إسماعيل بن مرار», «روى عن ابن بكير، وروى عنه القاسم بن
يحيى» — about a subject the entry never names. The extraction took the only name present,
the one inside the clause, and made it the heading. The record then looks like a biography of
that man and is not one: it is a note that he transmitted to or from somebody else.

They are not entries, and `is_entry` already keeps them off the page. They are still attached
to people as mentions, so they bring their teachers and students into the chains tab and the
ledger's counts, and the accuracy audit judges them as if they were entries — fourteen of the
fifteen strays the audit found in its hardest band were records of this kind, which is why
that band read 81.8% against 98.2% for the entries a reader actually sees.

The test is deliberately narrow, because two looser ones failed on inspection: the whole body
is a single short relation clause, and this record's own heading stands inside it. A normal
rijal sentence like Ardabili's «روى عنه التلعكبري وسمع منه سنة ٣٦٥» names the transmitter and
not the subject, so it does not match; an entry that mentions its own man later in a long
passage does not match either. Twenty drawn at random were all of the intended shape.

Al-Ṭūsī has the same thing in another shape. His chapter on al-Ṣādiq's companions closes with
«باب من لم يسم», the companions whose names are not given, and each is entered by the man who
narrates from him: «محمد بن سنان، عن الغلام الذي أعتقه أبو عبد الله عليه السلام» is the freed
slave, not Ibn Sinān — al-Khoei cites it under «الغلام السندي». Read as entries, the section made
one-entry men of Muḥammad b. Sinān, ʿAlī b. Asbāṭ, ʿAmr b. ʿUthmān and others, each standing
beside the real man in every chain that names him: 948 chain steps were left undecided by them.
The section is the test here, not the shape — «زرعة بن محمد، عن سماعة» elsewhere in the book is an
entry for Zurʿa — and the extraction recorded the section in each profile's notes.

These are kept apart from the relation stubs because they must be treated differently. A
relation stub never joins anybody; but the man such an entry names really is the narrator it
says, and where the books have joined it to his own entries — Thaʿlaba b. Maymūn, Isḥāq b.
ʿAmmār, Abū Bakr al-Ḥaḍramī — the join is right and other records hang on it. Treated as stubs,
those six men each lost the record to a new person and one lost nine more with it. All the
entry must not do is make somebody an entry-holder.

Writes tmp/narrators_sources/<book>_relation_stub.json, read by build_people.relation_stubs,
and <book>_unnamed_subject.json, read by build_people.unnamed_subjects.

Usage:
    python3 scripts/narrators/find_relation_stubs.py --dry-run
    python3 scripts/narrators/find_relation_stubs.py
"""
import argparse
from collections import Counter, defaultdict
import glob
import json
import os
import random
import re
import sys

W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
sys.path.insert(0, f'{W}/scripts/narrators')
from identity import write_json_atomic  # noqa: E402
from narrator_schema import normalize_arabic  # noqa: E402

TMP = f'{W}/tmp'
# «روى عنه», «وروى عن», «يروي عنه» — the clause opens on the relation and nothing else
RELATION = re.compile(r"^\s*(?:و?روى\s+عنه?\b|و?يروي\s+عنه?\b)")
MAX_BODY = 80


def body_of(profile):
    for item in profile.get("source_assessments") or []:
        text = (item.get("assessment_ar") or "").strip()
        if text:
            return text
    return ""


def is_relation_stub(profile):
    """Its whole text is one relation clause, and the heading it was given sits inside it."""
    heading = profile.get("primary_arabic_name") or ""
    if len(heading.split()) < 2:
        # a one-word heading is too weak a signal: it matches inside almost any clause
        return False
    body = body_of(profile)
    if not body or len(body) > MAX_BODY or not RELATION.match(body):
        return False
    return normalize_arabic(heading) in normalize_arabic(body)


UNNAMED_SECTION = "باب من لم يسم"


def is_unnamed_subject(profile):
    """An entry of al-Ṭūsī's «باب من لم يسم»: about the unnamed man its heading narrates from."""
    return (profile.get("book") == "tusi" and UNNAMED_SECTION in (profile.get("notes") or "")
            and re.search(r"\bعم?ن\b", body_of(profile)) is not None)


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--normalized-dir", default=os.path.join(TMP, "narrators_normalized"))
    parser.add_argument("--out-dir", default=os.path.join(TMP, "narrators_sources"))
    parser.add_argument("--sample", type=int, default=0, help="print N at random, to read them")
    parser.add_argument("--dry-run", action="store_true", help="count, write nothing")
    args = parser.parse_args()

    found, unnamed, counts = defaultdict(list), defaultdict(list), Counter()
    for path in sorted(glob.glob(os.path.join(args.normalized_dir, "*.json"))):
        book = os.path.basename(path)[:-5]
        with open(path) as handle:
            profiles = json.load(handle)
        if not isinstance(profiles, list):
            continue
        for profile in profiles:
            if not isinstance(profile, dict) or "source_index" not in profile:
                continue
            counts["profiles read"] += 1
            row = {"source_key": f"{book}:{profile['source_index']}",
                   "heading": profile.get("primary_arabic_name"), "text": body_of(profile)}
            if is_relation_stub(profile):
                found[book].append(row)
            elif is_unnamed_subject(profile):
                unnamed[book].append(row)
    total = sum(len(v) for v in found.values())
    print(f"  {counts['profiles read']:7,}  profiles read")
    print(f"  {total:7,}  are a relation clause naming their own heading")
    for book, rows in sorted(found.items(), key=lambda kv: -len(kv[1])):
        print(f"      {len(rows):6,}  {book}")
    for book, rows in sorted(unnamed.items()):
        print(f"  {len(rows):7,}  {book} entries about the unnamed man their heading narrates from")

    if args.sample:
        random.seed(7)
        pool = [r for rows in found.values() for r in rows]
        print(f"\n{min(args.sample, len(pool))} at random, to read:")
        for row in random.sample(pool, min(args.sample, len(pool))):
            print(f"  {row['source_key']:15s} «{row['heading']}»")
            print(f"      «{row['text'][:95]}»")

    if args.dry_run:
        return
    os.makedirs(args.out_dir, exist_ok=True)
    for book, rows in found.items():
        path = os.path.join(args.out_dir, f"{book}_relation_stub.json")
        write_json_atomic(path, sorted(rows, key=lambda r: r["source_key"]), indent=1)
        print(f"wrote {len(rows):6,} -> {path}")
    for book, rows in unnamed.items():
        path = os.path.join(args.out_dir, f"{book}_unnamed_subject.json")
        write_json_atomic(path, sorted(rows, key=lambda r: r["source_key"]), indent=1)
        print(f"wrote {len(rows):6,} -> {path}")


if __name__ == "__main__":
    main()
