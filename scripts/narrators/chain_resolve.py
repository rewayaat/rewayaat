#!/usr/bin/env python3
"""Decides which man each name in an isnād is, and refuses to decide where the books do not.

The cutter (`chain_mentions.py`) gives spans of text. This gives them people, or says it
cannot. Four outcomes, and only the first two become a link:

- `one` — exactly one man in the rijāl books is filed under that name. Nothing to decide.
- `chosen` — several are, and one of them is attested transmitting with the name beside him in
  this very chain: «أحمد بن محمد» after «محمد بن يحيى» is the Aḥmad whom a book shows
  receiving from that Muḥammad. The reason is recorded with the link.
- `several` — several men hold the name and nothing in the books chooses between them. This is
  the honest answer for «أحمد بن محمد» standing alone: al-Bazanṭī, al-Barqī and Ibn ʿĪsā are
  all called that, and a reader is better served by the list than by our guess.
- `none` — no entry anywhere is filed under that name.

Two rules keep the candidate lists from filling with rubbish. Only men with an entry in a
classical book are targets: Khoei's Muʿjam indexes chains as well as men, so a Khoei-only
one-line record is usually the chain mention itself rather than a biography — eight of the
thirteen men holding the name «علي بن إبراهيم» are of that kind. And a name is matched in
the direction the isnād states it: for «X عن Y», Y is X's teacher, so the man before is his
student and the man after is his teacher. Matching without direction was what made the
relation gate necessary in the first place.

Usage:
    python3 scripts/narrators/chain_resolve.py --show 25
    python3 scripts/narrators/chain_resolve.py --out tmp/narrators_chains/resolved.jsonl
"""
import argparse
from collections import Counter, defaultdict
import json
import os
import re
import sys

W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
sys.path.insert(0, f'{W}/scripts/narrators')
from narrator_schema import normalize_arabic  # noqa: E402

TMP = f'{W}/tmp'
MENTIONS = f'{TMP}/narrators_chains/mentions.jsonl'
PEOPLE = f'{TMP}/narrators_chains/people_index.json'

# The six books that file men as men. Khoei and Māmaqānī index narrations too, so a record
# holding nothing but a chain clause is not a man anybody can be linked to.
CLASSICAL = {"tusi", "duafa", "najashi", "ardabili", "kashshi", "fihrist"}

_PUNCT = re.compile(r"[،؛؟.:()\[\]\"'«»/\\|]")
_PERSIAN = str.maketrans({"ی": "ي", "ک": "ك", "ۀ": "ه", "ە": "ه", "ي": "ي"})


def key(name):
    """One folded key for a name, whether it came from a chain or from a book's heading."""
    if not name:
        return ""
    return normalize_arabic(_PUNCT.sub(" ", name).translate(_PERSIAN))


def same_name(one, other):
    """Whether two keys name the same man: equal, or one the opening of the other.

    A chain writes «صفوان» where a book writes «صفوان بن يحيى»; the shorter is the opening of
    the longer, which is how Arabic names shorten. One token is allowed to stand for a longer
    name only when it is the whole of what the book itself recorded, so «محمد» never matches
    «محمد بن يحيى».
    """
    if not one or not other:
        return False
    if one == other:
        return True
    first, second = one.split(), other.split()
    short, long = (first, second) if len(first) < len(second) else (second, first)
    return len(short) >= 2 and long[:len(short)] == short


def load_people(path, books):
    """Name → the men filed under it, and each man's attested teachers and students."""
    with open(path) as handle:
        people = json.load(handle)
    holders, teachers, students, by_id = defaultdict(set), {}, {}, {}
    for person in people:
        if books and not books & set(person.get("books") or []):
            continue
        pid = person["person_id"]
        by_id[pid] = person
        names = list(person.get("headings_ar") or [])
        if person.get("display_ar"):
            names.append(person["display_ar"])
        names += list(person.get("names_ar") or [])
        for name in names:
            folded = key(name)
            if folded:
                holders[folded].add(pid)
        teachers[pid] = {key(r.get("name_ar") or r.get("name"))
                         for field in ("narrated_from", "chain_from", "unsaid_from")
                         for r in person.get(field) or []}
        students[pid] = {key(r.get("name_ar") or r.get("name"))
                         for field in ("narrated_to", "chain_to", "unsaid_to")
                         for r in person.get(field) or []}
        teachers[pid].discard("")
        students[pid].discard("")
    return holders, teachers, students, by_id


# «أبيه» as a teacher's name identifies nobody: every man who narrates from his father
# carries it, so it would hand the name to whichever candidate happened to be listed first.
RELATIVE = {"ابيه", "ابيها", "عمه", "جده", "اخيه", "ابنه", "امه", "ابي", "جدي", "ابايه"}


def support(pid, before, after, teachers, students):
    """Why this candidate and not the others: a book shows him beside the name next to him."""
    if before in RELATIVE:
        before = ""
    if after in RELATIVE:
        after = ""
    for name in teachers.get(pid, ()):  # the man after him in the chain is his teacher
        if name not in RELATIVE and same_name(name, after):
            return f"narrated from {name}"
    for name in students.get(pid, ()):  # the man before him received from him
        if name not in RELATIVE and same_name(name, before):
            return f"narrated to {name}"
    return ""


def resolve_chain(mentions, holders, teachers, students):
    """Give each name mention in one chain an outcome, reading its neighbours for evidence."""
    keys = [key(m["text"]) for m in mentions]
    out = []
    for position, mention in enumerate(mentions):
        row = dict(mention)
        if mention["kind"] != "name":
            row["outcome"] = mention["kind"]
            out.append(row)
            continue
        candidates = sorted(holders.get(keys[position], ()))
        before = keys[position - 1] if position else ""
        after = keys[position + 1] if position + 1 < len(keys) else ""
        if not candidates:
            row["outcome"] = "none"
        elif len(candidates) == 1:
            row["outcome"], row["person_id"] = "one", candidates[0]
        else:
            backed = [(pid, support(pid, before, after, teachers, students)) for pid in candidates]
            backed = [(pid, why) for pid, why in backed if why]
            if len(backed) == 1:
                row["outcome"], row["person_id"], row["because"] = "chosen", *backed[0]
            else:
                row["outcome"], row["candidates"] = "several", candidates
                if backed:
                    row["backed"] = [pid for pid, _ in backed]
        out.append(row)
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--mentions", default=MENTIONS)
    parser.add_argument("--people", default=PEOPLE)
    parser.add_argument("--out", default=f'{TMP}/narrators_chains/resolved.jsonl')
    parser.add_argument("--all-books", action="store_true",
                        help="let Khoei-only and Māmaqānī-only records be link targets too")
    parser.add_argument("--show", type=int, default=0, help="print N of each outcome")
    parser.add_argument("--dry-run", action="store_true", help="measure, write nothing")
    args = parser.parse_args()

    books = set() if args.all_books else CLASSICAL
    holders, teachers, students, by_id = load_people(args.people, books)
    print(f"  {len(by_id):7,}  men can be linked to"
          f"{'' if args.all_books else ' (an entry in a classical book)'}")
    print(f"  {len(holders):7,}  names lead to them")

    outcomes, examples, chains = Counter(), defaultdict(list), 0
    rows = []
    with open(args.mentions) as handle:
        for line in handle:
            row = json.loads(line)
            chains += 1
            resolved = resolve_chain(row["mentions"], holders, teachers, students)
            for mention in resolved:
                outcomes[mention["outcome"]] += 1
                pool = examples[mention["outcome"]]
                if len(pool) < max(args.show, 0):
                    pool.append((row["book"], mention))
            rows.append({"id": row["id"], "book": row["book"], "mentions": resolved})

    names = sum(outcomes[k] for k in ("one", "chosen", "several", "none"))
    print(f"\n  {chains:7,}  chains, {sum(outcomes.values()):,} mentions, {names:,} of them names")
    for outcome in ("one", "chosen", "several", "none"):
        count = outcomes[outcome]
        print(f"      {count:7,}  {100 * count / names:5.1f}%  {outcome}")
    linked = outcomes["one"] + outcomes["chosen"]
    print(f"      {linked:7,}  {100 * linked / names:5.1f}%  become a link")
    for outcome in ("imam", "relative", "collective", "text"):
        print(f"      {outcomes[outcome]:7,}         {outcome} (not a link)")

    for outcome in ("one", "chosen", "several", "none"):
        if not examples[outcome]:
            continue
        print(f"\n  {outcome}:")
        for book, mention in examples[outcome]:
            who = mention.get("person_id", "")
            name = by_id[who]["display_ar"] if who in by_id else ""
            extra = mention.get("because", "") or f"{len(mention.get('candidates', []))} men"
            print(f"      «{mention['text'][:40]}» {book[:18]:18s} {who:8s} {name[:28]:28s} {extra}")

    if args.dry_run:
        return
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"\nwrote {len(rows):,} chains -> {args.out}")


if __name__ == "__main__":
    main()
