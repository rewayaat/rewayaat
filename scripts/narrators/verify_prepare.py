#!/usr/bin/env python3
"""Put the rule joins the stage 5 audit could not trust to agents, one pair at a time.

The audit measured each rule layer against blind auditors. Two fall short, and neither can be
mended by a threshold alone:

  layer1_full_name  89% precise; its ten wrong joins in a hundred spread over every context
                    score and key depth, and a conflict check catches three while blocking four
                    correct joins
  layer2_context    89% precise, its errors at the floor: 8 of 42 decided joins at score 4
                    wrong, 2 of 49 from score 6 up (CONTEXT_FLOOR)

Every such join still in force and still inside one person, and not already decided by an
agent, becomes a pair: the two entries it joined, shown as they stand, and the question the
audit asked — same man, different men, or cannot tell. An agent's `different` is recorded as a
not_same over both entries, which outranks the rule and breaks the join; `same` confirms it at
agent rank; `cannot_tell` changes nothing (record_decisions.py).

Reads  tmp/narrators_identity/{people,membership,build_stats}.json, decisions.jsonl,
       tmp/narrators_normalized/*.json
Writes tmp/narrators_l3/runs/verify-<count>-<sha16>/{batches,outputs,manifest.json,id_map.json}

Usage:
    python3 scripts/narrators/verify_prepare.py --dry-run
    python3 scripts/narrators/verify_prepare.py
"""

import argparse
from collections import Counter
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_people import assemble, load_sources  # noqa: E402
from crossform_prepare import judged_together, people_fingerprint  # noqa: E402
from identity import anchor_rank, fragment_members, in_force, load_record, write_json_atomic  # noqa: E402
from l3_prepare import evidence, pack  # noqa: E402

REPO = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
TMP = os.path.join(REPO, "tmp")

CONTEXT_FLOOR = 6
BUDGET = 100000


def untrusted(decision):
    """Whether the audit found this kind of rule join too imprecise to stand unchecked."""
    if decision["actor"] != "rule" or decision["kind"] != "same":
        return None
    if decision["method"] == "layer1_full_name":
        return "layer1_full_name"
    score = decision.get("evidence", {}).get("score")
    if decision["method"] == "layer2_context" and score is not None and score < CONTEXT_FLOOR:
        return "layer2_context below the floor"
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--identity-dir", default=os.path.join(TMP, "narrators_identity"))
    parser.add_argument("--normalized-dir", default=os.path.join(TMP, "narrators_normalized"))
    parser.add_argument("--out-dir", default=os.path.join(TMP, "narrators_l3"))
    parser.add_argument("--budget", type=int, default=BUDGET)
    parser.add_argument("--dry-run", action="store_true", help="count tasks, write nothing")
    args = parser.parse_args()

    def load(name):
        with open(os.path.join(args.identity_dir, name)) as handle:
            return json.load(handle)

    people, membership, stats = load("people.json"), load("membership.json"), load("build_stats.json")
    by_id = {p["person_id"]: p for p in people}
    fingerprint = people_fingerprint(people)
    record_path = os.path.join(args.identity_dir, "decisions.jsonl")
    force = in_force(load_record(record_path), stats["rules_run"])
    seen = judged_together(record_path)
    members = fragment_members(args.normalized_dir)
    head_of = {k: h for h, keys in members.items() for k in keys}
    profiles = load_sources(args.normalized_dir)

    def entry(key):
        person = by_id[membership[key]]
        return sorted((k for k in person["source_keys"]
                       if head_of.get(k, k) == head_of.get(key, key)), key=anchor_rank)

    counts, tasks, id_map, seeds, asked = Counter(), [], {}, {}, set()
    next_id = 1
    for decision in force:
        why = untrusted(decision)
        if not why:
            continue
        a, b = decision["sources"][:2]
        if not membership.get(a) or membership.get(a) != membership.get(b):
            counts["no longer one person"] += 1
            continue
        ua, ub = entry(a), entry(b)
        if set(ua) == set(ub):
            counts["same entry"] += 1
            continue
        if seen.get(a, set()) & set(ub) or any(seen.get(k, set()) & set(ub) for k in ua):
            counts["already decided by an agent"] += 1
            continue
        pair = frozenset((ua[0], ub[0]))
        if pair in asked:
            counts["pair already asked"] += 1
            continue
        asked.add(pair)
        shown = []
        for keys, label in ((ua, "A"), (ub, "B")):
            profile = evidence(dict(assemble(keys, profiles), merged_id=next_id))
            profile["id"], profile["source_keys"] = label, keys
            shown.append(profile)
            id_map[str(next_id)], seeds[str(next_id)] = keys, keys[0]
            next_id += 1
        tasks.append({"kind": "verify", "method": "verify_pair",
                      "task_id": f"verify:{decision['decision_id']}", "entries": shown})
        counts[why] += 1

    batches = pack(tasks, args.budget)
    print(f"people fingerprint: {fingerprint}")
    print(f"{len(tasks)} verify tasks -> {len(batches)} batches at {args.budget} chars")
    print("  " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items())))
    if args.dry_run:
        return

    run_dir = os.path.join(args.out_dir, "runs", "verify-" + fingerprint.replace(":", "-"))
    batch_dir = os.path.join(run_dir, "batches")
    if os.path.isdir(batch_dir) and os.listdir(batch_dir):
        raise SystemExit(f"{batch_dir} already holds batches — runs are immutable")
    os.makedirs(batch_dir)
    os.makedirs(os.path.join(run_dir, "outputs"), exist_ok=True)
    manifest = {"budget": args.budget, "merge_fingerprint": f"people:{fingerprint}",
                "kind": "verify", "batches": []}
    for number, batch in enumerate(batches):
        name = f"verify_{number:04d}.json"
        path = os.path.join(batch_dir, name)
        write_json_atomic(path, {"kind": "verify", "batch": name,
                                 "merge_fingerprint": f"people:{fingerprint}", "tasks": batch})
        manifest["batches"].append({"batch": name, "kind": "verify", "tasks": len(batch),
                                    "chars": os.path.getsize(path)})
    write_json_atomic(os.path.join(run_dir, "manifest.json"), manifest)
    write_json_atomic(os.path.join(run_dir, "id_map.json"), {
        "merge_fingerprint": f"people:{fingerprint}", "method": "verify_entries",
        "map": id_map, "seeds": seeds})
    print(f"\nwrote {len(batches)} batches -> {batch_dir}")


if __name__ == "__main__":
    main()
