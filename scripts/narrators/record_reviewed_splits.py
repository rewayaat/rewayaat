#!/usr/bin/env python3
"""Record reviewed «two men in one record» judgements as reviewer decisions.

Input is a JSON list, each naming the entries that stay with the record and the entries that
are another man, with the words in the books that make them two:

    {"record": "n012308", "keep": ["tusi:6407", ...], "apart": ["khoei:32384", ...],
     "why": "...", "quote": "فالظهر أنه غير محمد بن الحسن البراني الآتي"}

Each becomes a reviewer «partition» and a reviewer «distinct» over the two groups. The
distinct is hard at reviewer rank, so no rule, agent or name match joins them again; the
partition is what build_people splits on. The man's other records follow whichever side
their own decisions reach. The «apart» side is minted a new id (a split, `split_from`).

Usage:
    python3 scripts/narrators/record_reviewed_splits.py scripts/data/narrators/reviewed_splits_2026-11-12.json
    python3 scripts/narrators/record_reviewed_splits.py FILE --dry-run
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from identity import append_record, make_decision  # noqa: E402

REPO = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
IDENTITY = os.path.join(REPO, "tmp", "narrators_identity")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("judgements")
    parser.add_argument("--identity-dir", default=IDENTITY)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    with open(args.judgements) as handle:
        judgements = json.load(handle)
    with open(os.path.join(args.identity_dir, "membership.json")) as handle:
        membership = json.load(handle)
    run = "review:" + os.path.splitext(os.path.basename(args.judgements))[0]
    decisions = []
    for item in judgements:
        groups = [item["keep"], item["apart"]]
        held = {membership.get(k) for g in groups for k in g}
        if held != {item["record"]}:
            raise SystemExit(f"{item['record']}: the keys are held by {sorted(map(str, held))}, "
                             "not by this record alone — check the judgement against the build")
        for kind in ("partition", "distinct"):
            decisions.append(make_decision(
                kind, method="reviewed_mixed_record", actor="reviewer", confidence="high",
                groups=groups, evidence={"why": item["why"], "quote": item["quote"],
                                         "record": item["record"]},
                origin={"run": run}))
    print(f"{len(decisions)} decisions for {len(judgements)} records")
    if args.dry_run:
        return
    added, present = append_record(os.path.join(args.identity_dir, "decisions.jsonl"), decisions)
    print(f"appended {added}, already on file {present}")


if __name__ == "__main__":
    main()
