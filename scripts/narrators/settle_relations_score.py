#!/usr/bin/env python3
"""Scores a judging run, and writes the rows it settled into a table the build reads.

The judges answered the rows no name match could place. A fifth of what they read were
controls — rows where the served chains and the books already agree on which candidate is
meant, planted unmarked — and this reads those first. A judge who cannot recover the
controls has not earned the rest, so the table is written only when the run clears the
gate, and `--force` is the only way past it.

What the gate measures is **precision on the controls**: of the controls a judge settled,
how many landed on the man both kinds of evidence point at. A judge is free to call a
control unclear — that is cautious, not wrong — so refusals are reported beside the gate
rather than counted against it.

The table is keyed by the row, not by the name: «أحمد بن محمد بن يحيى» on one man's page
is not the same question as the same name on another's, and the whole point is that the
page decides it.

Writes scripts/data/narrators/settled_relations.json

Usage:
    python3 scripts/narrators/settle_relations_score.py --run relations-240-<sha>
    python3 scripts/narrators/settle_relations_score.py --run ... --write
"""
import argparse
import glob
import json
import os
import sys
from collections import Counter

W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
sys.path.insert(0, f'{W}/scripts/narrators')

from identity import write_json_atomic  # noqa: E402

RUNS = f'{W}/tmp/narrators_audit/relations/runs'
KEYS = f'{W}/tmp/narrators_audit/relations/keys'
TABLE = f'{W}/scripts/data/narrators/settled_relations.json'

#: Of the controls a judge chose to settle, this share must be the man the evidence names.
GATE = 0.90

#: A judgement resting on nothing the judge could name is not kept, however sure it sounds.
KEEP_CONFIDENCE = ("high", "medium")


def _row_key(row):
    """The row id, without the place in the list the first run carried.

    A row's position moves whenever an extraction changes; its folded name does not, and
    after the join a list holds one row per folded name. Runs made before that was fixed
    are read here rather than thrown away.
    """
    parts = (row or "").split("|")
    if len(parts) == 4 and parts[3].isdigit():
        return "|".join(parts[:3])
    return row


def answers(run_dir):
    """Every judge's answers, by row. A row answered twice keeps the first answer."""
    out, duplicates, malformed = {}, 0, 0
    for path in sorted(glob.glob(f'{run_dir}/outputs/*.jsonl')):
        for line in open(path, encoding="utf-8"):
            line = line.strip().strip(",")
            if not line or line.startswith("```"):
                continue
            try:
                said = json.loads(line)
            except json.JSONDecodeError:
                malformed += 1
                continue
            row = _row_key(said.get("row"))
            if not row:
                malformed += 1
            elif row in out:
                duplicates += 1
            else:
                out[row] = said
    return out, duplicates, malformed


def tasks(run_dir):
    """What was asked, by row, so an answer can be checked against its own candidates."""
    asked = {}
    for path in sorted(glob.glob(f'{run_dir}/batches/*.json')):
        batch = json.load(open(path, encoding="utf-8"))
        for task in batch["tasks"]:
            asked[_row_key(task["row"])] = task
    return asked


def score(asked, said, key):
    """Controls first, then what the run settled."""
    tally = Counter()
    wrong = []
    for row, control in key.items():
        answer = said.get(row)
        if not answer:
            tally["control unanswered"] += 1
            continue
        if answer.get("verdict") != "settled":
            tally["control the judge would not settle"] += 1
            continue
        if answer.get("person_id") == control:
            tally["control settled on the man the evidence names"] += 1
        else:
            tally["control settled on a different man"] += 1
            wrong.append((row, control, answer.get("person_id"), answer.get("why")))
    for row, answer in said.items():
        if row in key:
            continue
        task = asked.get(row)
        if not task:
            tally["an answer to a row nobody asked about"] += 1
            continue
        if answer.get("verdict") == "settled":
            allowed = {c["person_id"] for c in task["candidates"]}
            if answer.get("person_id") in allowed:
                tally["settled"] += 1
                tally[f"  confidence {answer.get('confidence')}"] += 1
            else:
                tally["settled on a man who was not a candidate"] += 1
        else:
            tally["left unclear"] += 1
    for row in asked:
        if row not in said:
            tally["unanswered"] += 1
    return tally, wrong


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", required=True, help="the run directory's name")
    parser.add_argument("--write", action="store_true",
                        help="write the settled rows into the build's table")
    parser.add_argument("--force", action="store_true",
                        help="write even when the controls fail the gate")
    parser.add_argument("--show-wrong", type=int, default=5)
    args = parser.parse_args()

    run_dir = os.path.join(RUNS, args.run)
    key = {_row_key(row): who for row, who in
           json.load(open(os.path.join(KEYS, f"{args.run}.json"), encoding="utf-8")).items()}
    asked = tasks(run_dir)
    said, duplicates, malformed = answers(run_dir)
    print(f"{len(asked):,} rows asked, {len(said):,} answered "
          f"({len(key):,} of them controls)")
    if duplicates or malformed:
        print(f"  {duplicates} answered twice, {malformed} lines unreadable")

    tally, wrong = score(asked, said, key)
    for label, n in tally.most_common():
        print(f"  {n:6,}  {label}")

    right = tally["control settled on the man the evidence names"]
    missed = tally["control settled on a different man"]
    judged = right + missed
    precision = right / judged if judged else 0.0
    print(f"\ncontrols: {right}/{judged} settled on the right man "
          f"({precision:.1%}); gate is {GATE:.0%}")
    for row, control, chosen, why in wrong[:args.show_wrong]:
        print(f"  {row}\n    evidence says {control}, judge said {chosen}: {why}")

    if not args.write:
        print("\nnothing written — pass --write")
        return
    if precision < GATE and not args.force:
        print(f"\nthe controls did not clear the gate; nothing written. "
              f"--force writes anyway")
        sys.exit(1)

    settled = {}
    for row, answer in said.items():
        task = asked.get(row)
        if not task or answer.get("verdict") != "settled":
            continue
        if answer.get("confidence") not in KEEP_CONFIDENCE:
            continue
        if answer.get("person_id") not in {c["person_id"] for c in task["candidates"]}:
            continue
        settled[row] = {"person_id": answer["person_id"],
                        "why": (answer.get("why") or "")[:300],
                        "confidence": answer.get("confidence"),
                        "run": args.run}
    held = {}
    if os.path.exists(TABLE):
        held = json.load(open(TABLE, encoding="utf-8"))
    held.update(settled)
    write_json_atomic(TABLE, held)
    print(f"\n{len(settled):,} rows written ({len(held):,} in the table) -> {TABLE}")


if __name__ == "__main__":
    main()
