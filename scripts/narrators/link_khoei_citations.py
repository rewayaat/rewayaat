#!/usr/bin/env python3
"""Join each Khoei entry to the Rijal al-Tusi entry it cites.

Khoei's Mu'jam heads nearly every man al-Tusi does, and says so: «من أصحاب الصادق عليه السلام،
رجال الشيخ (189)». Joined by name, the two books meet badly: al-Tusi heads a man in two or three
words, and a bare two-token name is not enough to join on, so thousands of men stood twice, once
in each book. A citation is better evidence than a name. It is the book pointing at one entry.

The number is not the running number of the edition our al-Tusi comes from. Khoei used the Najaf
edition, which numbers within each Imam's chapter and each letter within it: in al-Sadiq's
chapter آدم runs 16 to 19 and أبان 179 to 185. The two editions differ by an entry here and
there, so a citation is resolved within two places either side, and taken only where exactly one
entry in that window carries the heading's name and father's name, and where no third name both
give differs (a nisbah may differ; a grandfather may not).

Each link is recorded as a reviewer `same` between the Khoei entry and the al-Tusi entry. It is
not taken where the two people already differ by three generations or more.

Usage:
    python3 scripts/narrators/link_khoei_citations.py            # count, write nothing
    python3 scripts/narrators/link_khoei_citations.py --write
"""

import argparse
from collections import Counter, defaultdict
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from identity import append_record, load_record, make_decision  # noqa: E402
from narrator_schema import (  # noqa: E402
    generation_distance, imam_generations, name_tokens, normalize_arabic,
)

TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "tmp")
RECORD = os.path.join(TMP, "narrators_identity", "decisions.jsonl")
KHOEI_ENTRIES_FROM = 21938      # phase 1's per-mention profiles come first
TUSI_ENTRIES_FROM = 123
WINDOW = 2
# the honorific, bare or in brackets: «أصحاب الهادي (عليه السلام)، رجال الشيخ (5)»
HONORIFIC = (r"(?:\s*\(?\s*(?:(?:عليه|عليهم|عليهما)\s+السلام|صلى الله عليه وآله(?:\s*وسلم)?)"
             r"\s*\)?)?")
# «من أصحاب الصادق عليه السلام، رجال الشيخ (189)» and «ذكره الشيخ في أصحاب الصادق (159)»
CITATIONS = [
    re.compile(r"أصحاب\s+([^،.()]{2,40}?)" + HONORIFIC + r"[،.]?\s*رجال الشيخ\s*\((\d+)\)"),
    re.compile(r"(?:ذكره|عده)\s+الشيخ\s+(?:في رجاله\s+)?(?:تارة\s+)?في\s+أصحاب\s+([^،.()]{2,40}?)"
               + HONORIFIC + r"[،.]?\s*\((\d+)\)"),
    # a man listed under several Imams: «من أصحاب الجواد عليه السلام (1)، قائلا: (…)، (وأخرى) من
    # أصحاب الهادي عليه السلام (4)»
    re.compile(r"أصحاب\s+([^،.()]{2,40}?)" + HONORIFIC + r"\s*[،,]?\s*\((\d+)\)"),
]
LETTER_SECTION = re.compile(r"; (باب [^;]+)")


def head_tokens(name, n=2):
    """A man's name and his father's: the first two identifying words, «بن» and «ابن» skipped.

    Comparing the first two words as written would let «إبراهيم بن» stand for every Ibrahim.
    """
    return name_tokens(normalize_arabic(name))[:n]


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()

    with open(os.path.join(TMP, "narrators_book_khoei.json")) as handle:
        khoei = json.load(handle)[KHOEI_ENTRIES_FROM:]
    with open(os.path.join(TMP, "narrators_book_tusi.json")) as handle:
        tusi = json.load(handle)[TUSI_ENTRIES_FROM:]
    with open(os.path.join(TMP, "narrators_identity", "membership.json")) as handle:
        membership = json.load(handle)
    with open(os.path.join(TMP, "narrators_identity", "people.json")) as handle:
        people = {p["person_id"]: p for p in json.load(handle)}

    sections = defaultdict(list)          # (Imams, letter section) -> al-Tusi entries in order
    for i, entry in enumerate(tusi):
        imams = frozenset(imam_generations(entry.get("generation")))
        found = LETTER_SECTION.search(entry.get("notes") or "")
        sections[(imams, found.group(1).strip() if found else None)].append(i)
    by_imams = defaultdict(list)
    for key in sections:
        by_imams[key[0]].append(key)

    def generations(key):
        person = people.get(membership.get(key))
        found = set()
        for statement in (person or {}).get("generations") or []:
            found |= imam_generations(statement.get("value") if isinstance(statement, dict)
                                      else statement)
        return frozenset(found)

    counts, links = Counter(), set()
    for k, entry in enumerate(khoei):
        body = entry["source_assessments"][0]["assessment_ar"]
        name = head_tokens(entry["primary_arabic_name"])
        for pattern in CITATIONS:
            for cited in pattern.finditer(body):
                imams = frozenset(imam_generations("أصحاب " + cited.group(1)))
                if not imams:
                    counts["citation names no Imam we can read"] += 1
                    continue
                number = int(cited.group(2))
                matches = set()
                for section in by_imams.get(imams, []):
                    run = sections[section]
                    for at in range(number - WINDOW, number + WINDOW + 1):
                        if 0 < at <= len(run) and head_tokens(tusi[run[at - 1]]
                                                              ["primary_arabic_name"]) == name:
                            matches.add(run[at - 1])
                if len(matches) != 1:
                    counts["no single entry there carries the name" if not matches
                           else "several entries there carry the name"] += 1
                    continue
                t = matches.pop()
                # a grandfather both names give, and give differently, is two men — or a copyist's
                # variant of one, which the citation alone cannot tell apart
                mine, theirs = head_tokens(entry["primary_arabic_name"], 3), \
                    head_tokens(tusi[t]["primary_arabic_name"], 3)
                if len(mine) == 3 and len(theirs) == 3 and mine[2] != theirs[2] \
                        and not (mine[2].startswith("ال") or theirs[2].startswith("ال")):
                    counts["a third name both give differs"] += 1
                    continue
                a = f"khoei:{KHOEI_ENTRIES_FROM + k}"
                b = f"tusi:{TUSI_ENTRIES_FROM + t}"
                apart = generation_distance(generations(a), generations(b))
                if apart is not None and apart >= 3:
                    counts["the two people are generations apart"] += 1
                    continue
                links.add((a, b))
                counts["linked"] += 1

    # One printed entry is one man: an al-Tusi entry cited from two Khoei entries joins the one
    # whose heading shares most of its name, and neither where they tie — Khoei heads homonyms
    # apart and joins his own double headings by cross-reference.
    def overlap(a, b):
        x = set(head_tokens(khoei[int(a.split(":")[1]) - KHOEI_ENTRIES_FROM]["primary_arabic_name"], 6))
        y = set(head_tokens(tusi[int(b.split(":")[1]) - TUSI_ENTRIES_FROM]["primary_arabic_name"], 6))
        return len(x & y)
    by_target = defaultdict(list)
    for a, b in links:
        by_target[b].append(a)
    chosen = set()
    for b, sources in by_target.items():
        ranked = sorted(sources, key=lambda a: -overlap(a, b))
        if len(ranked) == 1 or overlap(ranked[0], b) > overlap(ranked[1], b):
            chosen.add((ranked[0], b))
        else:
            counts["an al-Tusi entry two Khoei entries cite alike: left"] += 1
    counts["links dropped: another Khoei entry cites the same al-Tusi entry more closely"] += \
        len(links) - len(chosen) - counts["an al-Tusi entry two Khoei entries cite alike: left"]
    links = chosen
    print(f"{len(khoei)} Khoei entries against {len(tusi)} al-Tusi entries")
    for key, n in counts.most_common():
        print(f"  {n:6d}  {key}")
    already = sum(1 for a, b in links if membership.get(a) and membership.get(a) == membership.get(b))
    print(f"  distinct links {len(links)}, of which already one person: {already}")
    if not args.write:
        return
    on_file = {d["decision_id"] for d in load_record(RECORD)}
    decisions = [d for d in (
        make_decision("same", sources=[a, b], method="khoei_cites_tusi", actor="reviewer",
                      confidence="high",
                      evidence={"why": "Khoei's entry cites this al-Tusi entry by Imam and number, "
                                       "and its heading carries the same name"},
                      origin={"run": "stage6:link_khoei_citations"})
        for a, b in sorted(links)) if d["decision_id"] not in on_file]
    append_record(RECORD, decisions)
    print(f"\nrecorded {len(decisions)} links")


if __name__ == "__main__":
    main()
