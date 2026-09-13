#!/usr/bin/env python3
"""Record a merge run's and a Layer 3 run's identity decisions against permanent source keys.

A merge numbers its profiles afresh, and Layer 3 answers name those numbers, so until now
every merge re-run discarded every agent answer. This translates both into decisions keyed on
source keys (`book:index`; see identity.py) and appends them to the decision record, where
they outlive the runs that produced them. Re-running it is safe: a decision already on file
is not added twice.

It also writes `id_map.json` — merged id to source keys — into the Layer 3 run directory, so
the run's answers stay translatable after merged.json is overwritten by a later merge.

What gets recorded:
  rule    Layer 0 fragments (same); every Layer 1-2 absorption (same, with its matched key
          and score); quarantined disambiguation pages (exclude); deferrals kept apart for
          lack of any positive evidence (not_same).
  agent   each group task's partition; each pair answer — same when the agent merged,
          not_same against every offered candidate when it did not. Low-confidence merges are
          recorded with status `review` and do not shape people until confirmed.

Reads  tmp/narrators_merge/{merged,quarantine}.json, tmp/narrators_normalized/*.json,
       tmp/narrators_l3/runs/<fingerprint>/{manifest.json,batches,outputs,auto_separate.json}
Writes tmp/narrators_identity/decisions.jsonl, <run>/id_map.json

Usage:
    python3 scripts/narrators/record_decisions.py
    python3 scripts/narrators/record_decisions.py --no-l3      # rule decisions of a new merge only
"""

import argparse
import glob
import json
import os
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from identity import (  # noqa: E402
    append_record, fragment_members, make_decision, merged_id_map, source_key,
)
from l3_prepare import merge_fingerprint, run_directory  # noqa: E402

REPO = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
TMP = os.path.join(REPO, "tmp")


def rule_decisions(merged, quarantine, members, rules_run):
    decisions = []
    for head, keys in members.items():
        if len(keys) > 1:
            decisions.append(make_decision(
                "same", sources=keys, method="layer0_fragments", actor="rule",
                confidence="rule", origin={"run": rules_run}))
    for profile in merged:
        sources, log = profile["contributing_sources"], profile["merge_log"]
        seed = source_key(sources[0]["book"], sources[0]["source_index"])
        for source, entry in zip(sources[1:], log[1:]):
            absorbed = source_key(source["book"], source["source_index"])
            decisions.append(make_decision(
                "same", sources=[seed, absorbed], method=entry["layer"], actor="rule",
                confidence="rule",
                evidence={"matched_key": entry["matched_key"], "score": entry["score"]},
                origin={"run": rules_run}))
    for item in quarantine:
        head = source_key(item["book"], item["source_index"])
        decisions.append(make_decision(
            "exclude", sources=members.get(head, [head]), method="quarantine_index_page",
            actor="rule", confidence="rule",
            evidence={"name": item["name"], "alias_count": item["alias_count"]},
            origin={"run": rules_run}))
    return decisions


def l3_decisions(run_dir, id_map, rules_run, counts):
    run_name = os.path.basename(os.path.normpath(run_dir))
    tasks, batch_of = {}, {}
    for path in sorted(glob.glob(os.path.join(run_dir, "batches", "*.json"))):
        with open(path) as handle:
            payload = json.load(handle)
        for task in payload["tasks"]:
            tasks[task["task_id"]] = task
            batch_of[task["task_id"]] = payload["batch"]

    decisions = []
    for path in sorted(glob.glob(os.path.join(run_dir, "outputs", "*.json"))):
        with open(path) as handle:
            payload = json.load(handle)
        for answer in payload.get("decisions", []):
            task = tasks.get(answer.get("task_id"))
            if task is None:
                counts["skipped_unknown_task"] += 1
                continue
            origin = {"run": f"l3:{run_name}", "merge": rules_run,
                      "batch": batch_of[task["task_id"]], "task_id": task["task_id"]}
            if task["kind"] == "group":
                offered = {p["merged_id"] for p in task["profiles"]}
                placed = [m for cluster in answer.get("clusters", []) for m in cluster]
                if sorted(placed) != sorted(offered):
                    counts["skipped_invalid_partition"] += 1
                    continue
                groups = [[k for mid in cluster for k in id_map[mid]]
                          for cluster in answer["clusters"]]
                decisions.append(make_decision(
                    "partition", groups=groups, method="layer3_group", actor="agent",
                    evidence={"notes": answer.get("notes", "")}, origin=origin))
                continue

            subject = id_map[task["subject"]["merged_id"]]
            offered = [c["merged_id"] for c in task["candidates"]]
            confidence = answer.get("confidence")
            evidence = {"reason": answer.get("reason", "")}
            if answer.get("same_person"):
                target = answer.get("merge_with")
                if target not in offered:
                    counts["skipped_invalid_pair"] += 1
                    continue
                status = "applied" if confidence in ("high", "medium") else "review"
                decisions.append(make_decision(
                    "same", sources=subject + id_map[target], method="layer3_pair",
                    actor="agent", confidence=confidence, status=status,
                    evidence=evidence, origin=origin))
            else:
                for candidate in offered:
                    decisions.append(make_decision(
                        "not_same", groups=[subject, id_map[candidate]], method="layer3_pair",
                        actor="agent", confidence=confidence, evidence=evidence, origin=origin))

    auto_path = os.path.join(run_dir, "auto_separate.json")
    if os.path.exists(auto_path):
        with open(auto_path) as handle:
            for item in json.load(handle):
                subject = id_map.get(item["merged_id"])
                for candidate in item["candidates"]:
                    if subject and candidate in id_map and candidate != item["merged_id"]:
                        decisions.append(make_decision(
                            "not_same", groups=[subject, id_map[candidate]],
                            method="auto_separate", actor="rule", confidence="rule",
                            evidence={"basis": item.get("basis"), "reason": item.get("reason")},
                            origin={"run": rules_run, "l3_run": run_name}))
    return decisions


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--merge-dir", default=os.path.join(TMP, "narrators_merge"))
    parser.add_argument("--normalized-dir", default=os.path.join(TMP, "narrators_normalized"))
    parser.add_argument("--l3-dir", default=os.path.join(TMP, "narrators_l3"))
    parser.add_argument("--l3-run", help="run directory (default: the one matching the merge)")
    parser.add_argument("--no-l3", action="store_true", help="record rule decisions only")
    parser.add_argument("--record", default=os.path.join(TMP, "narrators_identity", "decisions.jsonl"))
    args = parser.parse_args()

    with open(os.path.join(args.merge_dir, "merged.json")) as handle:
        merged = json.load(handle)
    quarantine_path = os.path.join(args.merge_dir, "quarantine.json")
    quarantine = json.load(open(quarantine_path)) if os.path.exists(quarantine_path) else []
    fingerprint = merge_fingerprint(merged)
    rules_run = f"merge:{fingerprint}"
    print(f"merge {fingerprint}")

    members = fragment_members(args.normalized_dir)
    id_map, method = merged_id_map(merged, args.normalized_dir)
    covered = sum(len(v) for v in id_map.values())
    print(f"  {len(id_map)} merged profiles cover {covered} source keys (translated by {method})")

    counts = Counter()
    decisions = rule_decisions(merged, quarantine, members, rules_run)

    if not args.no_l3:
        run_dir = args.l3_run or run_directory(args.l3_dir, fingerprint)
        with open(os.path.join(run_dir, "manifest.json")) as handle:
            run_fingerprint = json.load(handle)["merge_fingerprint"]
        if run_fingerprint != fingerprint:
            raise SystemExit(f"{run_dir} was built from merge {run_fingerprint}, not {fingerprint}; "
                             f"its answers cannot be translated through this merge")
        map_path = os.path.join(run_dir, "id_map.json")
        payload = {"merge_fingerprint": fingerprint, "method": method,
                   "map": {str(k): v for k, v in id_map.items()}}
        if os.path.exists(map_path):
            with open(map_path) as handle:
                if json.load(handle)["map"] != payload["map"]:
                    raise SystemExit(f"{map_path} disagrees with the current translation")
        else:
            with open(map_path, "w") as handle:
                json.dump(payload, handle, ensure_ascii=False)
            print(f"  wrote {map_path}")
        decisions += l3_decisions(run_dir, id_map, rules_run, counts)

    tally = Counter((d["actor"], d["kind"], d["method"], d["status"]) for d in decisions)
    added, present = append_record(args.record, decisions)
    print(f"\n{len(decisions)} decisions: {added} added, {present} already on file -> {args.record}")
    for (actor, kind, method, status), n in sorted(tally.items()):
        print(f"  {actor:6s} {kind:9s} {method:22s} {status:8s} {n:6d}")
    for key, n in counts.items():
        print(f"  {key}: {n}")


if __name__ == "__main__":
    main()
