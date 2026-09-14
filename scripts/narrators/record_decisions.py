#!/usr/bin/env python3
"""Record a merge run's and a Layer 3 run's identity decisions against permanent source keys.

A merge numbers its profiles afresh and Layer 3 answers name those numbers, so every merge
re-run used to discard every agent answer. This translates both into decisions keyed on
source keys (`book:index`; see identity.py) and appends them to the decision record, where
they outlive the runs that produced them. Re-running it is safe: a decision already on file
is not added twice.

What gets recorded:
  rule    Layer 0 fragments (same); every Layer 1-2 absorption (same, with its matched key
          and score); quarantined disambiguation pages (exclude); deferrals kept apart for
          lack of any positive evidence (not_same).
  agent   each group task's partition; each pair answer — same when the agent merged,
          not_same against every offered candidate when it did not. Low-confidence merges are
          recorded with status `review` and do not shape people until confirmed.

Agent decisions bind each profile's *seed* source, not every source in it (see identity.py):
an agent judged a merged profile as a whole, and which sources the rules had merged into it
was the rules' claim, not the agent's.

A run's decisions on file always equal the current translation of its answers: decisions
recorded from an earlier translation of the same run that no longer appear are retracted.

Each Layer 3 run carries `id_map.json` — merged id to source keys and seed — so its answers can
be recorded after merged.json has been overwritten by a later merge.

Reads  tmp/narrators_merge/{merged,quarantine}.json, tmp/narrators_normalized/*.json,
       tmp/narrators_l3/runs/<fingerprint>/{manifest.json,id_map.json,batches,outputs,
       auto_separate.json}
Writes tmp/narrators_identity/decisions.jsonl, and <run>/id_map.json when it is missing

Usage:
    python3 scripts/narrators/record_decisions.py              # current merge + its Layer 3 run
    python3 scripts/narrators/record_decisions.py --no-l3      # a new merge's rule decisions only
    python3 scripts/narrators/record_decisions.py --no-rules --l3-run tmp/narrators_l3/runs/<fp>
"""

import argparse
import glob
import json
import os
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from identity import (  # noqa: E402
    append_record, fragment_members, live, load_record, make_decision, merged_id_map,
    merged_seeds, source_key, write_json_atomic,
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


def run_translation(run_dir, merged, fingerprint, normalized_dir):
    """The run's merged ids as ({id: source keys}, {id: seed key}).

    Read from the run's id_map.json when it carries seeds. Otherwise rebuilt from the merge on
    hand — which is only valid if that is the merge the run was built from — and saved.
    """
    path = os.path.join(run_dir, "id_map.json")
    stored = None
    if os.path.exists(path):
        with open(path) as handle:
            stored = json.load(handle)
        if "seeds" in stored:
            return ({int(k): v for k, v in stored["map"].items()},
                    {int(k): v for k, v in stored["seeds"].items()})
    with open(os.path.join(run_dir, "manifest.json")) as handle:
        run_fingerprint = json.load(handle)["merge_fingerprint"]
    if merged is None or run_fingerprint != fingerprint:
        raise SystemExit(
            f"{run_dir} has no id_map.json with seeds and was built from merge "
            f"{run_fingerprint}, not the merge on hand ({fingerprint}); its answers cannot be "
            f"translated")
    mapping, method = merged_id_map(merged, normalized_dir)
    seeds = merged_seeds(merged)
    if stored is not None and stored["map"] != {str(k): v for k, v in mapping.items()}:
        raise SystemExit(f"{path} disagrees with the current translation")
    write_json_atomic(path, {"merge_fingerprint": run_fingerprint, "method": method,
                             "map": {str(k): v for k, v in mapping.items()},
                             "seeds": {str(k): v for k, v in seeds.items()}})
    print(f"  wrote {path} (with seeds)")
    return mapping, seeds


def l3_decisions(run_dir, seeds, merge_run, counts):
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
            origin = {"run": f"l3:{run_name}", "merge": merge_run,
                      "batch": batch_of[task["task_id"]], "task_id": task["task_id"]}
            if task["kind"] == "group":
                offered = {p["merged_id"] for p in task["profiles"]}
                placed = [m for cluster in answer.get("clusters", []) for m in cluster]
                if sorted(placed) != sorted(offered):
                    counts["skipped_invalid_partition"] += 1
                    continue
                # Cross-form tasks (crossform_prepare.py) are group tasks over people rather
                # than merged profiles, and say so in their own method.
                decisions.append(make_decision(
                    "partition", groups=[[seeds[m] for m in cluster]
                                         for cluster in answer["clusters"]],
                    method=task.get("method", "layer3_group"), actor="agent",
                    evidence={"notes": answer.get("notes", "")}, origin=origin))
                continue

            subject = seeds[task["subject"]["merged_id"]]
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
                    "same", sources=[subject, seeds[target]], method="layer3_pair",
                    actor="agent", confidence=confidence, status=status,
                    evidence=evidence, origin=origin))
            else:
                for candidate in offered:
                    decisions.append(make_decision(
                        "not_same", groups=[[subject], [seeds[candidate]]],
                        method="layer3_pair", actor="agent", confidence=confidence,
                        evidence=evidence, origin=origin))

    auto_path = os.path.join(run_dir, "auto_separate.json")
    if os.path.exists(auto_path):
        with open(auto_path) as handle:
            for item in json.load(handle):
                subject = seeds.get(item["merged_id"])
                for candidate in item["candidates"]:
                    if subject and candidate in seeds and candidate != item["merged_id"]:
                        decisions.append(make_decision(
                            "not_same", groups=[[subject], [seeds[candidate]]],
                            method="auto_separate", actor="rule", confidence="rule",
                            evidence={"basis": item.get("basis"), "reason": item.get("reason")},
                            origin={"run": merge_run, "l3_run": run_name}))
    return decisions


def retractions(record_path, run_name, fresh):
    """Withdraw this run's decisions on file that its current translation no longer produces."""
    label, fresh_ids = f"l3:{run_name}", {d["decision_id"] for d in fresh}
    stale = [d["decision_id"] for d in live(load_record(record_path))
             if (d.get("origin", {}).get("run") == label
                 or d.get("origin", {}).get("l3_run") == run_name)
             and d["decision_id"] not in fresh_ids]
    if not stale:
        return []
    return [make_decision(
        "retract", targets=stale, method="rerecord_run", actor="rule", confidence="rule",
        evidence={"reason": "superseded by the current translation of this run's answers — "
                            "agent decisions bind each profile's seed source (identity.py)"},
        origin={"run": label})]


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--merge-dir", default=os.path.join(TMP, "narrators_merge"))
    parser.add_argument("--normalized-dir", default=os.path.join(TMP, "narrators_normalized"))
    parser.add_argument("--l3-dir", default=os.path.join(TMP, "narrators_l3"))
    parser.add_argument("--l3-run", help="run directory (default: the one matching the merge)")
    parser.add_argument("--no-l3", action="store_true", help="skip Layer 3 answers")
    parser.add_argument("--no-rules", action="store_true", help="skip the merge's rule decisions")
    parser.add_argument("--record",
                        default=os.path.join(TMP, "narrators_identity", "decisions.jsonl"))
    args = parser.parse_args()

    merged_path = os.path.join(args.merge_dir, "merged.json")
    merged, fingerprint = None, None
    if os.path.exists(merged_path):
        with open(merged_path) as handle:
            merged = json.load(handle)
        fingerprint = merge_fingerprint(merged)
        print(f"merge on hand: {fingerprint}")

    counts, decisions = Counter(), []
    if not args.no_rules:
        if merged is None:
            raise SystemExit(f"no merge at {merged_path}")
        quarantine_path = os.path.join(args.merge_dir, "quarantine.json")
        quarantine = []
        if os.path.exists(quarantine_path):
            with open(quarantine_path) as handle:
                quarantine = json.load(handle)
        members = fragment_members(args.normalized_dir)
        decisions += rule_decisions(merged, quarantine, members, f"merge:{fingerprint}")

    if not args.no_l3:
        run_dir = args.l3_run or run_directory(args.l3_dir, fingerprint)
        with open(os.path.join(run_dir, "manifest.json")) as handle:
            run_fingerprint = json.load(handle)["merge_fingerprint"]
        _, seeds = run_translation(run_dir, merged, fingerprint, args.normalized_dir)
        fresh = l3_decisions(run_dir, seeds, f"merge:{run_fingerprint}", counts)
        decisions += fresh + retractions(args.record, os.path.basename(os.path.normpath(run_dir)),
                                         fresh)

    tally = Counter((d["actor"], d["kind"], d["method"], d["status"]) for d in decisions)
    added, present = append_record(args.record, decisions)
    print(f"\n{len(decisions)} decisions: {added} added, {present} already on file -> {args.record}")
    for (actor, kind, method, status), n in sorted(tally.items()):
        extra = ""
        if kind == "retract":
            extra = f"  (withdrawing {sum(len(d['targets']) for d in decisions if d['kind'] == 'retract')})"
        print(f"  {actor:6s} {kind:9s} {method:22s} {status:8s} {n:6d}{extra}")
    for key, n in counts.items():
        print(f"  {key}: {n}")


if __name__ == "__main__":
    main()
