#!/usr/bin/env python3
"""Record what the partition pass found, so a conflation it catches is actually undone.

Each answer is one assembled person read whole: the entries that are his, the entries that
belong to somebody else, and the entries too bare to call. Three things follow:

  clusters of two or more   a `partition` decision over the source keys. This is the
                            strongest claim the pass makes and the only one that can undo
                            a join, so it is recorded as the agent's reading of the whole
                            evidence rather than as a note for review.
  one cluster, no others    the assembly was right. Nothing is recorded: a decision that
                            says «leave it as it is» adds a row to a 140MB log and changes
                            nothing, and the absence of a finding is already the finding.
  unplaced                  written to the run's report and to review_signals, never
                            recorded as a decision. An entry nobody could place is not
                            evidence that it belongs, nor that it does not, and inventing
                            either reading from silence is the failure this pass exists to
                            avoid. They are the queue for stage 8, where the chains name
                            who these men narrate from.

A partition binds by rank like any other decision (identity.py): it refuses a union from an
actor of equal or lower rank. These are the only reading of a man's whole file that anyone
has made, so they stand against the rules, and against the earlier agent passes that saw a
tenth of what this one saw.

Reads  tmp/narrators_partition/runs/<run>/outputs/*.json and batches/*.json
Writes tmp/narrators_identity/decisions.jsonl, <run>/report.json

Usage:
    python3 scripts/narrators/partition_apply.py tmp/narrators_partition/runs/<run> --dry-run
    python3 scripts/narrators/partition_apply.py tmp/narrators_partition/runs/<run>
"""
import argparse
from collections import Counter
import glob
import json
import os
import sys

W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
sys.path.insert(0, f'{W}/scripts/narrators')
from identity import append_record, make_decision, write_json_atomic  # noqa: E402

TMP = f'{W}/tmp'


def load(path):
    with open(path) as handle:
        return json.load(handle)


def tasks_of(run_dir):
    """Every task posed, by id, so an answer's labels can be read back to source keys."""
    posed = {}
    for path in sorted(glob.glob(os.path.join(run_dir, "batches", "*.json"))):
        for task in load(path).get("tasks", []):
            posed[task["task_id"]] = task
    return posed


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("run_dir")
    parser.add_argument("--identity-dir", default=os.path.join(TMP, "narrators_identity"))
    parser.add_argument("--min-confidence", default="medium",
                        choices=["low", "medium", "high"],
                        help="the weakest answer that may split a person (default medium)")
    parser.add_argument("--dry-run", action="store_true", help="count, write nothing")
    args = parser.parse_args()

    ranks = {"low": 0, "medium": 1, "high": 2}
    floor = ranks[args.min_confidence]
    posed = tasks_of(args.run_dir)
    counts = Counter()
    made, unplaced_report, problems = [], [], []

    for path in sorted(glob.glob(os.path.join(args.run_dir, "outputs", "*.json"))):
        for answer in load(path).get("partitions", []):
            # One agent answered with the bare person id. The id is unambiguous either
            # way, and dropping eleven people over a missing prefix would be the wrong
            # kind of strictness.
            asked = answer.get("task_id")
            task = posed.get(asked) or posed.get(f"partition:{asked}")
            if task is None:
                problems.append({"answer": answer.get("task_id"), "why": "no such task"})
                counts["answer for a task not posed"] += 1
                continue
            keys_of = {e["id"]: e["source_keys"] for e in task["entries"]}
            clusters = answer.get("clusters") or []
            unplaced = answer.get("unplaced") or []

            seen = [label for cluster in clusters for label in cluster] + list(unplaced)
            unknown = [label for label in seen if label not in keys_of]
            if unknown or len(seen) != len(set(seen)):
                problems.append({"task": task["task_id"], "why": "labels invented or repeated",
                                 "unknown": unknown})
                counts["answer with bad labels, skipped"] += 1
                continue
            if unplaced:
                unplaced_report.append({"person_id": task["person_id"],
                                        "entries": [keys_of[l] for l in unplaced],
                                        "of": task["entries_in_person"]})
                counts["entries left unplaced"] += len(unplaced)

            real = [c for c in clusters if c]
            if len(real) < 2:
                counts["assembly confirmed, nothing to record"] += 1
                continue
            if ranks.get(answer.get("confidence"), 0) < floor:
                counts[f"split below {args.min_confidence} confidence, left for review"] += 1
                problems.append({"task": task["task_id"], "why": "confidence too low to split",
                                 "confidence": answer.get("confidence")})
                continue

            groups = [sorted({k for label in cluster for k in keys_of[label]})
                      for cluster in real]
            # `partition` from an agent is a soft constraint: it refuses a union only from a
            # strictly lower-ranked actor, so it cannot undo a join another agent made — and
            # most of these men were assembled by agents. Recording 76 partitions moved 17
            # people. A high-confidence answer therefore goes in as `distinct`, which binds
            # equal rank too, and that is the honest weight for it: every other pass judged
            # these men from a sample, and this one read the whole file.
            # Medium binds too. These answers are medium mostly because the rijal books
            # disagree about the man — «Khoei makes them one, Mamaqani makes them father and
            # son» — which is a finding about the corpus, not a hedge about the reading. A
            # soft constraint leaves such a man silently fused, which states one book's view
            # as fact; splitting him and showing the disagreement is the lesser error, and
            # the page already says where each book stands. Only `low` is left for review.
            kind = "partition" if answer.get("confidence") == "low" else "distinct"
            made.append(make_decision(
                kind, groups=groups, method="large_partition", actor="agent",
                confidence=answer.get("confidence", "medium"), status="applied",
                evidence={"reason": answer.get("reason", ""),
                          "read": "every entry of this person, not a sample",
                          "run": os.path.basename(args.run_dir.rstrip("/"))},
                origin={"run": f"partition:{os.path.basename(args.run_dir.rstrip('/'))}"}))
            counts[f"people split ({kind})"] += 1
            counts["men where there was one"] += len(real)

    for label, n in counts.most_common():
        print(f"  {n:7d}  {label}")
    print(f"\nwould record {len(made)} partitions" if args.dry_run
          else f"\nrecording {len(made)} partitions")
    if args.dry_run:
        return

    write_json_atomic(os.path.join(args.run_dir, "report.json"),
                      {"counts": dict(counts), "unplaced": unplaced_report,
                       "problems": problems}, indent=1)
    if made:
        added, already = append_record(
            os.path.join(args.identity_dir, "decisions.jsonl"), made)
        print(f"{len(made)} decisions: {added} added, {already} already on file")
    print(f"report -> {os.path.join(args.run_dir, 'report.json')}")


if __name__ == "__main__":
    main()
