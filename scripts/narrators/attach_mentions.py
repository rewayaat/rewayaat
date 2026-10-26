#!/usr/bin/env python3
"""Attach each per-mention Khoei profile that is really an entry to the entry it was read from.

Phase 1 read Khoei's Mu'jam page by page and made a profile of every name on a page, so its
21,938 profiles are a mix of two things. Some were read from a man's own entry: they are that
entry, extracted once more. The rest are names mentioned inside other men's entries, and they
carry that other entry's teachers and students, which is why they are not people at all but
names in chains, for stage 8 to resolve.

extract_openiti.py has since appended the Mu'jam's 15,618 entries, one profile each. This pass
tells the two kinds apart by where a profile was read. Phase 1 read an edition paginated
differently, 10,924 pages against OpenITI's 8,236 over 24 volumes, but within a volume the two
run in step: fitting one line per volume to the profiles whose name matches exactly one entry
puts 71% of them within twelve pages of it. So a profile is its entry when the entry's name is
its name and the entry's page, carried onto phase 1's pagination, falls inside the pages the
profile was read from. The page also separates Khoei's homonyms, which he heads one after
another on different pages.

A profile attached is recorded as a reviewer `same` with its entry: it stays a source inside
that man's person, so every decision resting on its key stays true. A profile left unattached
is untouched: it is a mention, and it counts as a person only if something joins it to an
entry-holder (build_people.py's `holds_entry`).

Usage:
    python3 scripts/narrators/attach_mentions.py            # count, write nothing
    python3 scripts/narrators/attach_mentions.py --write
"""

import argparse
from collections import Counter, defaultdict
import json
import os
import re
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from identity import append_record, load_record, make_decision  # noqa: E402
from narrator_schema import normalize_arabic  # noqa: E402

TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "tmp")
BOOK = os.path.join(TMP, "narrators_book_khoei.json")
RECORD = os.path.join(TMP, "narrators_identity", "decisions.jsonl")
PHASE1 = 21938          # profiles phase 1 left in the file; the entries follow them
TOLERANCE = 12          # pages either side of the batch a profile was read from
VOLUME = re.compile(r"الجزء (\d+)")
MIN_OVERLAP = 0.3       # share of the quoted words that must stand in the entry's body


def words(text):
    return set(normalize_arabic(text or "").split())


def fit_volumes(mentions, entries, volumes, by_name):
    """One line per volume, from the profiles whose name only one entry carries."""
    points = defaultdict(list)
    for mention in mentions:
        hits = by_name.get(normalize_arabic(mention["primary_arabic_name"]), [])
        if len(hits) == 1 and mention.get("source_pages") and volumes[hits[0]] \
                and entries[hits[0]]["source_pages"]:
            points[volumes[hits[0]]].append((entries[hits[0]]["source_pages"][0],
                                             mention["source_pages"][0]))
    lines = {}
    for volume, xs in points.items():
        xs.sort()
        step = max(1, min(25, len(xs) // 4))
        slopes = [(xs[i + step][1] - xs[i][1]) / (xs[i + step][0] - xs[i][0])
                  for i in range(len(xs) - step) if xs[i + step][0] != xs[i][0]]
        slope = statistics.median(slopes) if slopes else 1.0
        intercept = statistics.median(y - slope * x for x, y in xs)
        lines[volume] = (slope, intercept)
    return lines


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()

    with open(BOOK) as handle:
        book = json.load(handle)
    mentions, entries = book[:PHASE1], book[PHASE1:]
    volumes = [int(m.group(1)) if (m := VOLUME.search(e.get("notes") or "")) else None
               for e in entries]
    by_name = defaultdict(list)
    for j, entry in enumerate(entries):
        by_name[normalize_arabic(entry["primary_arabic_name"])].append(j)
    lines = fit_volumes(mentions, entries, volumes, by_name)

    def mapped(j):
        entry = entries[j]
        if not volumes[j] or volumes[j] not in lines or not entry["source_pages"]:
            return None
        slope, intercept = lines[volumes[j]]
        return intercept + slope * entry["source_pages"][0]

    counts, attach = Counter(), []
    for i, mention in enumerate(mentions):
        name = normalize_arabic(mention["primary_arabic_name"])
        hits = by_name.get(name, [])
        if not hits:
            # a heading the agent shortened: one entry whose name extends it, or it extends
            extends = [k for k in by_name if k.startswith(name + " ") or name.startswith(k + " ")]
            hits = by_name[extends[0]] if len(extends) == 1 else []
        pages = mention.get("source_pages") or []
        if not hits:
            counts["no entry carries the name: a mention"] += 1
            continue
        if not pages:
            counts["no page to place it by"] += 1
            continue
        low, high = min(pages) - TOLERANCE, max(pages) + TOLERANCE
        near = [j for j in hits if (p := mapped(j)) is not None and low <= p <= high]
        if len(near) == 1:
            # The words phase 1 quoted should be the entry's own: for the median attachment all of
            # them are. Where fewer than a third are, the profile may have been read from a
            # neighbouring homonym's entry, so it is left rather than attached.
            quoted = words((mention.get("source_assessments") or [{}])[0].get("assessment_ar"))
            body = words(entries[near[0]]["source_assessments"][0]["assessment_ar"])
            if len(quoted) >= 4 and len(quoted & body) / len(quoted) < MIN_OVERLAP:
                counts["quotation not in the entry's text: left"] += 1
                continue
            attach.append((i, near[0]))
            counts["read from the entry itself: attached"] += 1
        elif len(near) > 1:
            counts["several namesake entries on those pages: left"] += 1
        else:
            counts["named elsewhere than its entry: a mention"] += 1

    print(f"{len(mentions)} phase 1 profiles against {len(entries)} entries, "
          f"{len(lines)} volumes fitted")
    for key, n in counts.most_common():
        print(f"  {n:6d}  {key}")
    per_entry = Counter(j for _, j in attach)
    print(f"  entries reached: {len(per_entry)}; most profiles on one entry: "
          f"{per_entry.most_common(1)[0][1] if per_entry else 0}")
    if not args.write:
        return
    on_file = {d["decision_id"] for d in load_record(RECORD)}
    decisions = [d for d in (
        make_decision("same", sources=[f"khoei:{i}", f"khoei:{PHASE1 + j}"],
                      method="mention_is_entry", actor="reviewer", confidence="high",
                      evidence={"why": "read from this entry's own pages under its own name",
                                "pages_read": mentions[i].get("source_pages"),
                                "entry": entries[j]["notes"]},
                      origin={"run": "stage6:attach_mentions"})
        for i, j in attach) if d["decision_id"] not in on_file]
    append_record(RECORD, decisions)
    print(f"\nrecorded {len(decisions)} attachments")


if __name__ == "__main__":
    main()
