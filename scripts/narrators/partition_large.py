#!/usr/bin/env python3
"""Put every entry of a large person in front of one agent, and have him partition the lot.

The accuracy audit cannot measure the people who matter most. It shows an auditor at most
ten entries of a person who may hold a hundred, and asks which of the ten do not belong. On
the 11+ band that produced 83.9% with a third of the people carrying an entry the auditor
could place neither way — he was being asked about an entry whose context sat in the ninety
he could not see. The number is a floor with no known ceiling, and the band is already
enumerated, so drawing the same sample again answers nothing.

This asks the other question. One person, every entry he holds, one agent: *how many men are
these, and which entry belongs to which?* It is the question the merge itself answered, asked
again of the whole evidence rather than a tenth of it, and it yields a repair and not only a
measurement — a partition can be recorded, where "U5 looks wrong" could only be flagged.

A person's entries are rendered as fragments, as the audit renders them, so an agent judges
the same units the pipeline joined. One person is one task and never split across batches:
the comparison is the task, and half of it is worth nothing.

Reads  tmp/narrators_identity/{people,membership}.json, tmp/narrators_normalized/*.json
Writes tmp/narrators_partition/runs/<fingerprint>/batches/person_NNNN.json

Usage:
    python3 scripts/narrators/partition_large.py --min-units 11 --dry-run
    python3 scripts/narrators/partition_large.py --min-units 11
"""
import argparse
from collections import Counter
import glob
import hashlib
import json
import os
import sys

W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
sys.path.insert(0, f'{W}/scripts/narrators')
from build_people import assemble, load_sources  # noqa: E402
from identity import anchor_rank, fragment_members, write_json_atomic  # noqa: E402
from l3_prepare import DEFAULT_BUDGET, evidence, pack  # noqa: E402

TMP = f'{W}/tmp'
MIN_UNITS = 11


def load(path):
    with open(path) as handle:
        return json.load(handle)


def fragments(person, head_of):
    """The person's entries as the units the pipeline joined, anchor first.

    A book read into several profiles is one unit, not three: asking an agent whether
    Khoei's page 431 and his page 432 are the same man is asking about our own parsing.
    """
    seen, units = {}, []
    for key in person["source_keys"]:
        seen.setdefault(head_of.get(key, key), []).append(key)
    anchor_head = head_of.get(person["anchor"], person["anchor"])
    for head in sorted(seen, key=lambda h: (h != anchor_head, anchor_rank(h))):
        units.append(seen[head])
    return units


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--min-units", type=int, default=MIN_UNITS,
                        help=f"only people holding this many entries or more (default {MIN_UNITS})")
    parser.add_argument("--max-units", type=int, default=0,
                        help="skip people larger than this, if one person will not fit an agent")
    parser.add_argument("--people", default=os.path.join(TMP, "narrators_identity"))
    parser.add_argument("--normalized-dir", default=os.path.join(TMP, "narrators_normalized"))
    parser.add_argument("--out-dir", default=os.path.join(TMP, "narrators_partition"))
    parser.add_argument("--budget", type=int, default=DEFAULT_BUDGET)
    parser.add_argument("--only", help="one person id, to look at a single task")
    parser.add_argument("--dry-run", action="store_true", help="count, write nothing")
    args = parser.parse_args()

    people = load(os.path.join(args.people, "people.json"))
    membership = load(os.path.join(args.people, "membership.json"))
    profiles = load_sources(args.normalized_dir)
    # A book read into several profiles is one fragment with one head; the audit counts
    # units the same way, so the two measurements are of the same thing.
    members = fragment_members(args.normalized_dir)
    head_of = {k: h for h, keys in members.items() for k in keys}

    counts = Counter()
    tasks = []
    for person in people:
        if person.get("holds_entry") is False:
            continue
        if args.only and person["person_id"] != args.only:
            continue
        units = fragments(person, head_of)
        if len(units) < args.min_units:
            continue
        if args.max_units and len(units) > args.max_units:
            counts["too large for one agent, left out"] += 1
            continue
        shown = []
        for n, keys in enumerate(units):
            built = evidence(dict(assemble(sorted(keys, key=anchor_rank), profiles),
                                  merged_id=0))
            built.pop("merged_id", None)
            shown.append({"id": f"U{n + 1}", "source_keys": sorted(keys, key=anchor_rank),
                          **built})
        tasks.append({
            "task_id": f"partition:{person['person_id']}",
            "person_id": person["person_id"],
            "entries_in_person": len(units),
            "question": "These entries were assembled as one man. How many men are they? "
                        "Put every entry in exactly one cluster; one cluster means the "
                        "assembly was right.",
            "entries": shown,
        })
        counts["people to partition"] += 1
        counts["entries shown"] += len(units)

    for label, n in counts.most_common():
        print(f"  {n:7d}  {label}")
    if not tasks:
        print("nothing to do")
        return

    batches = pack(tasks, args.budget)
    print(f"  {len(tasks)} tasks -> {len(batches)} batches")
    if args.dry_run:
        biggest = max(tasks, key=lambda t: t["entries_in_person"])
        print(f"  largest: {biggest['person_id']} with {biggest['entries_in_person']} entries, "
              f"{len(json.dumps(biggest, ensure_ascii=False)):,} characters")
        return

    fingerprint = hashlib.sha256(
        json.dumps([t["task_id"] for t in tasks], sort_keys=True).encode()).hexdigest()[:16]
    run = os.path.join(args.out_dir, "runs", f"large-{len(tasks)}-{fingerprint}")
    os.makedirs(os.path.join(run, "batches"), exist_ok=True)
    os.makedirs(os.path.join(run, "outputs"), exist_ok=True)
    for n, batch in enumerate(batches):
        write_json_atomic(os.path.join(run, "batches", f"person_{n:04d}.json"),
                          {"batch": f"person_{n:04d}.json", "tasks": batch}, indent=1)
    print(f"\nwrote {len(batches)} batches -> {os.path.join(run, 'batches')}")


if __name__ == "__main__":
    main()
