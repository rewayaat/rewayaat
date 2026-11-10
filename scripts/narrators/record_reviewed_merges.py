#!/usr/bin/env python3
"""Record reviewed «same man» judgements as reviewer decisions.

Input is a JSON list of judgements, each naming two current person ids and the words in the
books that make them one man:

    {"primary": "n000585", "other": "n067727", "reason": "...", "quote": "قد عرفت اتحاده مع سابقه",
     "group": "g038"}

Each becomes a reviewer «same» over the entry keys the two people hold — the entries their
pages show, read from the served narrator index — with the reason and the quote as its
evidence. Entry keys only: build_people lets a «same» join people only when every key in it
is an entry, so a decision naming a man's mention records as well joins nobody. Reviewer rank means a later rule run cannot undo it, and a
hard «distinct» already on file still blocks it (build_people reports the conflict).

The judgements recorded on 2026-11-09 (scripts/data/narrators/reviewed_merges_2026-11-09.json)
came from a review of the 190 name groups where a man linked in 20 or more served chains
shared an ism and father with another record. Sonnet agents read both records' entries
and answered same / different / disputed / unclear. Only «same» at high confidence, with a
quote verified verbatim against the entries, is kept: 64 of 578 answers. Four of those
would have overridden an earlier judgement for good reason and are held back, with the reason,
in reviewed_merges_held_2026-11-09.json; 60 are recorded.

Usage:
    python3 scripts/narrators/record_reviewed_merges.py scripts/data/narrators/reviewed_merges_2026-11-09.json --index narr_base
    python3 scripts/narrators/record_reviewed_merges.py FILE --dry-run
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from identity import append_record, make_decision  # noqa: E402

REPO = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
IDENTITY = os.path.join(REPO, "tmp", "narrators_identity")


def entry_keys(host, index, ids):
    """person id → the source keys of the entries his page shows."""
    import urllib.request
    request = urllib.request.Request(
        f"{host.rstrip('/')}/{index}/_mget", data=json.dumps({"ids": ids}).encode(),
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=120) as response:
        docs = json.load(response)["docs"]
    return {d["_id"]: [e["source_key"] for e in d["_source"].get("entries") or []
                       if e.get("source_key")]
            for d in docs if d.get("found")}


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("judgements")
    parser.add_argument("--run", default=None,
                        help="origin run label; defaults to review:<file name>")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--es-host", default="http://localhost:9200")
    parser.add_argument("--index", default="rewayaat_narrators",
                        help="the served narrator index the ids were judged on")
    args = parser.parse_args()

    with open(args.judgements) as handle:
        judgements = json.load(handle)
    ids = sorted({i for item in judgements for i in (item["primary"], item["other"])})
    keys = entry_keys(args.es_host, args.index, ids)
    run = args.run or "review:" + os.path.splitext(os.path.basename(args.judgements))[0]

    decisions, missing = [], []
    for item in judgements:
        one, two = keys.get(item["primary"]), keys.get(item["other"])
        if not one or not two:
            missing.append((item["primary"], item["other"]))
            continue
        sources = one + two
        decisions.append(make_decision(
            "same", method="reviewed_duplicate_record", actor="reviewer", confidence="high",
            sources=sources,
            evidence={"why": item["reason"], "quote": item["quote"],
                      "people": [item["primary"], item["other"]]},
            origin={"run": run, "group": item.get("group")}))
    print(f"{len(decisions)} decisions, {len(missing)} judgements naming an id no longer held")
    for pair in missing:
        print("  missing:", pair)
    if args.dry_run:
        return
    added, present = append_record(os.path.join(IDENTITY, "decisions.jsonl"), decisions)
    print(f"appended {added}, already on file {present}")


if __name__ == "__main__":
    main()
