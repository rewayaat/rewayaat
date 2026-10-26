#!/usr/bin/env python3
"""Record what the auditors found, so a fault an audit catches is actually mended.

The audit measures and writes `errors.json` "for review". Nothing read it. Two audits running
three days apart both found the same join — al-Najashi's Ali b. al-Hasan b. Faddal tied to the
al-Qanawi entry, a different grandfather and a different nisbah — and both times it stayed. An
audit that changes nothing measures the same faults for ever.

This closes that loop for the faults an auditor can settle on the evidence shown:

  join judged different  the two entries are different men
  outlier entry          this entry describes someone other than the person's anchor

A high answer is recorded as `distinct`, a medium one as `not_same`. The difference decides
whether the finding can mend anything: `not_same` binds only the layers below the actor who made
it, so against a join an agent made it does nothing at all, and many of these joins were an
agent's. `distinct` binds equal rank too, which is how the split pass already undoes an agent's
fusion. It is the stronger claim, so only a high-confidence answer earns it.

`double found` is not applied. Those are missed merges, and merging is the one thing this corpus
cannot yet judge fairly: 82% of its unattached profiles have no main-book entry to attach to,
because Rijal al-Tusi is 123 entries of about 8,000. An agent asked to place them picks from what
happens to be present. They are written to `deferred_doubles.json` and wait for stage 6.

Auditors are agents: blind to how a thing was joined, given the entries alone, and checked by
hidden controls in the same run. Their answers are recorded at agent rank, high and medium
applied, low held for review — the same standing every other agent answer has. The audit that
found a fault is not the audit that measures the fix: each run draws a fresh sample at a new seed.

Reads  tmp/narrators_audit/runs/<run>/errors.json and tmp/narrators_audit/keys/<run>.json
Writes tmp/narrators_identity/decisions.jsonl, <run>/deferred_doubles.json

Usage:
    python3 scripts/narrators/audit_apply.py tmp/narrators_audit/runs/audit-... [more runs]
    python3 scripts/narrators/audit_apply.py --all --dry-run
"""

import argparse
from collections import Counter
import glob
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from identity import append_record, make_decision, write_json_atomic  # noqa: E402

REPO = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
TMP = os.path.join(REPO, "tmp")


def decisions_for(run_dir, counts):
    """The decisions one audit's findings call for, and the doubles it leaves to stage 6."""
    name = os.path.basename(os.path.normpath(run_dir))
    errors_path = os.path.join(run_dir, "errors.json")
    if not os.path.exists(errors_path):
        counts["run without errors.json"] += 1
        return [], []
    with open(errors_path) as handle:
        errors = json.load(handle)
    keys_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.normpath(run_dir))), "keys")
    with open(os.path.join(keys_dir, f"{name}.json")) as handle:
        key = json.load(handle)["items"]

    made, deferred = [], []
    for error in errors:
        confidence = error.get("confidence")
        status = "applied" if confidence in ("high", "medium") else "review"
        kind = "distinct" if confidence == "high" else "not_same"
        origin = {"run": f"audit:{name}", "item_id": error.get("item_id")}
        evidence = {"reason": error.get("reason", ""), "found_by": "audit"}
        if error["kind"] == "join judged different":
            a, b = error.get("A"), error.get("B")
            if not a or not b:
                counts["join without both sides"] += 1
                continue
            evidence["stratum"] = error.get("stratum")
            made.append(make_decision(kind, groups=[a, b], method="audit_error",
                                      actor="agent", confidence=confidence, status=status,
                                      evidence=evidence, origin=origin))
            counts[f"join judged different -> {kind} {status}"] += 1
        elif error["kind"] == "outlier entry":
            units = key.get(error.get("item_id"), {}).get("units") or {}
            anchor = units.get("U1")
            entry = error.get("entry")
            if not anchor or not entry or set(anchor) == set(entry):
                counts["outlier without a distinct anchor"] += 1
                continue
            made.append(make_decision(kind, groups=[entry, anchor], method="audit_outlier",
                                      actor="agent", confidence=confidence, status=status,
                                      evidence=evidence, origin=origin))
            counts[f"outlier entry -> {kind} {status}"] += 1
        elif error["kind"] == "double found":
            deferred.append({"run": name, "item_id": error.get("item_id"),
                             "person_id": error.get("person_id"), "double": error.get("double"),
                             "confidence": confidence, "reason": error.get("reason", "")})
            counts["double found -> deferred to stage 6"] += 1
    return made, deferred


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("runs", nargs="*", help="audit run directories")
    parser.add_argument("--all", action="store_true", help="every run under narrators_audit/runs")
    parser.add_argument("--identity-dir", default=os.path.join(TMP, "narrators_identity"))
    parser.add_argument("--dry-run", action="store_true", help="count, write nothing")
    args = parser.parse_args()

    runs = args.runs or (sorted(glob.glob(os.path.join(TMP, "narrators_audit", "runs", "audit-*")))
                         if args.all else [])
    if not runs:
        raise SystemExit("name an audit run, or pass --all")

    counts, decisions = Counter(), []
    for run_dir in runs:
        made, deferred = decisions_for(run_dir, counts)
        decisions.extend(made)
        if deferred and not args.dry_run:
            write_json_atomic(os.path.join(run_dir, "deferred_doubles.json"), deferred, indent=1)
        print(f"{os.path.basename(os.path.normpath(run_dir))}: "
              f"{len(made)} decisions, {len(deferred)} doubles deferred")
    print("  " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items())))
    if args.dry_run:
        print(f"\nwould record {len(decisions)} decisions")
        return
    added, already = append_record(os.path.join(args.identity_dir, "decisions.jsonl"), decisions)
    print(f"\n{len(decisions)} decisions: {added} added, {already} already on file")


if __name__ == "__main__":
    main()
