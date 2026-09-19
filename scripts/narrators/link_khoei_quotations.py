#!/usr/bin/env python3
"""Join each Khoei entry to the al-Najashi, al-Fihrist and al-Kashshi entries he quotes.

Khoei's Mu'jam opens most entries by quoting what the older books say of the man, word for word:
«قال النجاشي: "سهل بن زياد أبو سعيد الآدمي الرازي، كان ضعيفا في الحديث…"». The books' own text is
now in the corpus, one profile per printed entry, so the quotation can be found. Among the entries
of a book whose name and father's name agree with Khoei's heading, the one whose opening words
stand in Khoei's entry is the one he quotes — which settles homonyms by what the entries say
rather than by what they are called.

An entry is joined when at least MIN_SHARE of its opening words stand in Khoei's entry and no
other candidate of that book comes within MARGIN of it; and a printed entry joins only the one
Khoei entry that quotes it most fully. Each join is a reviewer `same`
(`khoei_quotes_<book>`), not taken where the two people already stand three generations apart.

link_khoei_citations.py does the same for Rijal al-Tusi by the number Khoei cites, and
link_entry_books.py for Jami' al-Ruwat by name and Imam.

Usage:
    python3 scripts/narrators/link_khoei_quotations.py            # count, write nothing
    python3 scripts/narrators/link_khoei_quotations.py --write
"""

import argparse
from collections import Counter, defaultdict
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from identity import append_record, load_record, make_decision  # noqa: E402
from narrator_schema import name_tokens, normalize_arabic  # noqa: E402

TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "tmp")
RECORD = os.path.join(TMP, "narrators_identity", "decisions.jsonl")
BOOKS = ("najashi", "fihrist", "kashshi")
OPENING = 25            # words of a quoted entry to look for
MIN_SHARE = 0.6
MARGIN = 0.15


def entries(book):
    with open(os.path.join(TMP, "narrators_normalized", f"{book}.json")) as handle:
        return [p for p in json.load(handle) if "printed_entry" in p.get("flags", ())]


def body(profile):
    return (profile.get("source_assessments") or [{}])[-1].get("assessment_ar") or ""


def key(profile):
    return tuple(name_tokens(normalize_arabic(profile["primary_arabic_name"]))[:2])


def words(text):
    return normalize_arabic(text).split()


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()

    with open(os.path.join(TMP, "narrators_identity", "membership.json")) as handle:
        membership = json.load(handle)
    khoei = entries("khoei")
    targets = {book: defaultdict(list) for book in BOOKS}
    for book in BOOKS:
        for p in entries(book):
            opening = words(body(p))[:OPENING]
            if len(opening) >= 5 and len(key(p)) == 2:
                targets[book][key(p)].append((p, opening))

    counts, links = Counter(), []
    for k in khoei:
        heading = key(k)
        if len(heading) < 2:
            continue
        text = set(words(body(k)))
        for book in BOOKS:
            candidates = targets[book].get(heading, [])
            if not candidates:
                continue
            scored = sorted(((sum(1 for w in opening if w in text) / len(opening), p)
                             for p, opening in candidates), key=lambda x: -x[0])
            best, runner = scored[0][0], (scored[1][0] if len(scored) > 1 else 0.0)
            if best < MIN_SHARE:
                counts[f"{book}: Khoei does not quote it"] += 1
                continue
            if best - runner < MARGIN:
                counts[f"{book}: two entries he may be quoting"] += 1
                continue
            links.append((f"khoei:{k['source_index']}", f"{book}:{scored[0][1]['source_index']}",
                          book, round(best, 2)))
            counts[f"{book}: linked"] += 1

    # One printed entry is one man: where two Khoei entries both quote it, only the one that
    # quotes it most fully is joined. Khoei heads one man under two forms at times, and his own
    # cross-references join those; he also quotes al-Najashi to say a namesake is someone else,
    # and a second link would bridge the two.
    best_for = {}
    for l in links:
        prev = best_for.get(l[1])
        if prev is None or l[3] > prev[3]:
            best_for[l[1]] = l
        elif l[3] == prev[3]:
            best_for[l[1]] = (None,) + l[1:]
    kept = {id(l) for l in best_for.values() if l[0] is not None}
    dropped = [l for l in links if id(l) not in kept]
    counts["links dropped: a printed entry another Khoei entry quotes as fully or more"] += len(dropped)
    links = [l for l in links if id(l) in kept]
    fresh = [l for l in links if not (membership.get(l[0]) and membership.get(l[0]) == membership.get(l[1]))]
    for l in fresh:
        counts[f"{l[2]}: linked, joining two people"] += 1
    for label, n in sorted(counts.items()):
        print(f"  {n:6d}  {label}")
    if not args.write:
        return
    on_file = {d["decision_id"] for d in load_record(RECORD)}
    decisions = [d for d in (
        make_decision("same", sources=[a, b], method=f"khoei_quotes_{book}", actor="reviewer",
                      confidence="high",
                      evidence={"why": f"Khoei's entry reproduces this {book} entry's opening words",
                                "share": share},
                      origin={"run": "stage6:link_khoei_quotations"})
        for a, b, book, share in links) if d["decision_id"] not in on_file]
    append_record(RECORD, decisions)
    print(f"\nrecorded {len(decisions)} links")


if __name__ == "__main__":
    main()
