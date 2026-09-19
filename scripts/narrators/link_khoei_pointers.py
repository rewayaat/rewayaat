#!/usr/bin/env python3
"""Join Khoei's entries that Khoei himself says are one man.

The Mu'jam heads a man under every form his name takes in the chains, and says which headings
are his. Three ways of saying it are read here:

  see       an entry that is only a pointer: «الحسين بن عمرو بن يزيد: تقدم في الحسين بن عمر بن
            يزيد.» — a variant heading sending the reader to the man's entry
  adjacent  «أقول: هو متحد مع ما بعده» (or «ما قبله»): the entry next to it
  equals    the «= X» lines under a heading, the other titles of the same man. A bare heading
            such as «أحمد بن محمد بن الحسن» can be pointed at from two men — Ibn al-Sakan and
            Ibn al-Walid both carry it — so a pointer joins only where the two point at each
            other, or where neither end is pointed at from anywhere else

A pointer is taken only where its target names exactly one Khoei entry. Each join is a reviewer
`same` (`khoei_points_<kind>`), not taken where the two people stand three generations apart.

Usage:
    python3 scripts/narrators/link_khoei_pointers.py            # count, write nothing
    python3 scripts/narrators/link_khoei_pointers.py --write
"""

import argparse
from collections import Counter, defaultdict
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from identity import append_record, load_record, make_decision  # noqa: E402
from narrator_schema import generation_distance, imam_generations, normalize_arabic  # noqa: E402

TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "tmp")
RECORD = os.path.join(TMP, "narrators_identity", "decisions.jsonl")
EQUALS = re.compile(r"^\s*=\s*([^=.]{3,80}?)\s*\.")
SEE = re.compile(r"^\s*:?\s*(?:تقدم|يأتي|سيأتي)(?:\s+(?:في|بعنوان|ترجمة|ترجمته في))+"
                 r"\s+([^.،(]{3,80}?)\s*[.،]?\s*$")
ADJACENT = re.compile(r"(?:هو\s+)?متحد\s+مع\s+ما\s+(بعده|قبله)")


def body(profile):
    return profile["source_assessments"][0]["assessment_ar"]


def after_heading(profile):
    text, heading = body(profile), profile["primary_arabic_name"]
    return text[len(heading) + 1:].strip() if text.startswith(heading) else text


def pointers(raw):
    """(entry, entry, kind) for each join Khoei states between two of his entries."""
    entries = [i for i, p in enumerate(raw) if p.get("printed_entry")]
    by_name = defaultdict(list)
    for i in entries:
        by_name[normalize_arabic(raw[i]["primary_arabic_name"])].append(i)

    def one(name):
        found = by_name.get(normalize_arabic(name).strip(), [])
        return found[0] if len(found) == 1 else None

    counts, links, equals = Counter(), [], defaultdict(set)
    for i in entries:
        rest = after_heading(raw[i])
        seen = SEE.match(rest)
        if seen:
            target = one(seen.group(1))
            if target is not None and target != i:
                links.append((i, target, "see"))
            else:
                counts["see: the name heads no single entry"] += 1
        while (said := EQUALS.match(rest)):
            target = one(said.group(1))
            if target is not None and target != i:
                equals[i].add(target)
            rest = rest[said.end():]
        near = ADJACENT.search(body(raw[i]))
        if near:
            step = 1 if near.group(1) == "بعده" else -1
            j = i + step
            while 0 <= j < len(raw) and not raw[j].get("printed_entry"):
                j += step
            if 0 <= j < len(raw):
                links.append((i, j, "adjacent"))

    pointed_at = Counter(t for targets in equals.values() for t in targets)
    for a, targets in equals.items():
        for b in targets:
            if a in equals.get(b, ()):
                if a < b:
                    links.append((a, b, "equals"))
            elif pointed_at[b] == 1 and pointed_at[a] == 0:
                links.append((a, b, "equals"))
            else:
                counts["equals: an end other pointers reach too"] += 1
    return links, counts


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()

    with open(os.path.join(TMP, "narrators_book_khoei.json")) as handle:
        raw = json.load(handle)
    with open(os.path.join(TMP, "narrators_identity", "membership.json")) as handle:
        membership = json.load(handle)
    links, counts = pointers(raw)
    kept = []
    for a, b, kind in links:
        apart = generation_distance(frozenset(imam_generations(raw[a].get("generation"))),
                                    frozenset(imam_generations(raw[b].get("generation"))))
        if apart is not None and apart >= 3:
            counts[f"{kind}: generations apart"] += 1
            continue
        ka, kb = f"khoei:{a}", f"khoei:{b}"
        counts[f"{kind}: linked"] += 1
        if not (membership.get(ka) and membership.get(ka) == membership.get(kb)):
            counts[f"{kind}: linked, joining two people"] += 1
        kept.append((ka, kb, kind))
    for label, n in sorted(counts.items()):
        print(f"  {n:6d}  {label}")
    if not args.write:
        return
    on_file = {d["decision_id"] for d in load_record(RECORD)}
    decisions = [d for d in (
        make_decision("same", sources=[a, b], method=f"khoei_points_{kind}", actor="reviewer",
                      confidence="high",
                      evidence={"why": "Khoei's own cross-reference between his two entries"},
                      origin={"run": "stage6:link_khoei_pointers"})
        for a, b, kind in kept) if d["decision_id"] not in on_file]
    append_record(RECORD, decisions)
    print(f"\nrecorded {len(decisions)} links")


if __name__ == "__main__":
    main()
