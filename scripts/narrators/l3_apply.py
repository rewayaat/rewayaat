#!/usr/bin/env python3
"""Apply Layer 3 sub-agent decisions to the merged narrator profiles.

Decisions are validated against the batches they answer before anything is applied: a
partition must cover its task exactly once, and a pair decision must name a candidate the
task actually offered. A malformed or incomplete decision file is reported and skipped
rather than partially applied.

Reads  tmp/narrators_l3/batches/*.json, tmp/narrators_l3/outputs/*.json
Writes tmp/narrators_l3/{merged_final,review_queue,apply_stats}.json

Usage:
    python3 scripts/narrators/l3_apply.py
    python3 scripts/narrators/l3_apply.py --dry-run
"""

import argparse
import glob
import json
import os
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from l3_prepare import merge_fingerprint  # noqa: E402

REPO = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
TMP = os.path.join(REPO, "tmp")

APPLIED_CONFIDENCE = {"high", "medium"}


class Union:
    """Union-find over merged_ids, so a partition and a pair decision compose."""

    def __init__(self):
        self.parent = {}

    def find(self, item):
        self.parent.setdefault(item, item)
        while self.parent[item] != item:
            self.parent[item] = self.parent[self.parent[item]]
            item = self.parent[item]
        return item

    def union(self, left, right):
        a, b = self.find(left), self.find(right)
        if a == b:
            return False
        # Lowest id wins, so the result does not depend on decision order.
        low, high = (a, b) if a < b else (b, a)
        self.parent[high] = low
        return True


def load_tasks(batch_dir):
    """Task id -> task, plus the batch it came from and that batch's merge fingerprint."""
    tasks, origin = {}, {}
    for path in sorted(glob.glob(os.path.join(batch_dir, "*.json"))):
        with open(path) as handle:
            payload = json.load(handle)
        for task in payload["tasks"]:
            tasks[task["task_id"]] = task
            origin[task["task_id"]] = (payload["batch"],
                                       payload.get("merge_fingerprint"))
    return tasks, origin


def validate(decision, task, errors):
    """Check a decision answers its task. Returns True when it may be applied."""
    task_id = decision.get("task_id")
    if task["kind"] == "group":
        expected = {p["merged_id"] for p in task["profiles"]}
        clusters = decision.get("clusters")
        if not isinstance(clusters, list) or not clusters:
            errors.append((task_id, "no clusters"))
            return False
        seen = [i for cluster in clusters for i in cluster]
        if len(seen) != len(set(seen)):
            errors.append((task_id, "profile appears in more than one cluster"))
            return False
        if set(seen) != expected:
            missing, extra = expected - set(seen), set(seen) - expected
            errors.append((task_id, f"partition mismatch (missing {sorted(missing)[:5]}, "
                                    f"extra {sorted(extra)[:5]})"))
            return False
        return True

    if not decision.get("same_person"):
        return True
    offered = {c["merged_id"] for c in task["candidates"]}
    target = decision.get("merge_with")
    if target not in offered:
        errors.append((task_id, f"merge_with {target} was not offered as a candidate"))
        return False
    if decision.get("confidence") not in {"high", "medium", "low"}:
        errors.append((task_id, f"bad confidence {decision.get('confidence')!r}"))
        return False
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--merge-dir", default=os.path.join(TMP, "narrators_merge"))
    parser.add_argument("--l3-dir", default=os.path.join(TMP, "narrators_l3"))
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    tasks, origin = load_tasks(os.path.join(args.l3_dir, "batches"))
    outputs = sorted(glob.glob(os.path.join(args.l3_dir, "outputs", "*.json")))
    print(f"{len(tasks)} tasks prepared, {len(outputs)} decision files present")

    with open(os.path.join(args.merge_dir, "merged.json")) as handle:
        merged = json.load(handle)
    current = merge_fingerprint(merged)
    stale = {fp for _batch, fp in origin.values() if fp and fp != current}
    if stale:
        raise SystemExit(
            f"batches were built from a different merge ({sorted(stale)}) than the one in "
            f"{args.merge_dir} ({current}). Task ids are merge-relative — re-run "
            f"l3_prepare.py, or point --merge-dir at the merge the batches came from. "
            f"Existing decision files stay valid for their own merge; archive them first."
        )

    union = Union()
    counts = Counter()
    errors = []
    review = []
    answered = set()

    for path in outputs:
        try:
            with open(path) as handle:
                payload = json.load(handle)
        except json.JSONDecodeError as exc:
            errors.append((os.path.basename(path), f"invalid JSON: {exc}"))
            continue
        for decision in payload.get("decisions", []):
            task_id = decision.get("task_id")
            task = tasks.get(task_id)
            if task is None:
                errors.append((task_id, "no such task"))
                continue
            if task_id in answered:
                counts["duplicate_decision"] += 1
                continue
            if not validate(decision, task, errors):
                counts["invalid"] += 1
                continue
            answered.add(task_id)

            if task["kind"] == "group":
                counts["group_tasks"] += 1
                for cluster in decision["clusters"]:
                    counts["group_clusters"] += 1
                    for other in cluster[1:]:
                        if union.union(cluster[0], other):
                            counts["group_merges"] += 1
                continue

            counts["pair_tasks"] += 1
            if not decision.get("same_person"):
                counts["pair_separate"] += 1
                continue
            confidence = decision.get("confidence")
            if confidence not in APPLIED_CONFIDENCE:
                counts["pair_low_confidence"] += 1
                review.append({"task_id": task_id, "decision": decision})
                continue
            if union.union(task["subject"]["merged_id"], decision["merge_with"]):
                counts["pair_merges"] += 1

    unanswered = sorted(set(tasks) - answered)
    counts["unanswered_tasks"] = len(unanswered)

    auto_path = os.path.join(args.l3_dir, "auto_separate.json")
    if os.path.exists(auto_path):
        with open(auto_path) as handle:
            counts["auto_separate"] = len(json.load(handle))

    print("\n" + "\n".join(f"  {k:22s} {v}" for k, v in sorted(counts.items())))
    if errors:
        print(f"\n  {len(errors)} validation errors, first 10:")
        for task_id, message in errors[:10]:
            print(f"    {task_id}: {message}")

    if args.dry_run:
        print("\nDry run — nothing written.")
        return

    groups = defaultdict(list)
    for profile in merged:
        groups[union.find(profile["merged_id"])].append(profile)

    final = []
    for root, members in groups.items():
        if len(members) == 1:
            final.append(members[0])
            continue
        members.sort(key=lambda p: p["merged_id"])
        head = members[0]
        for other in members[1:]:
            for key in ("primary_names", "arabic_aliases", "english_aliases", "titles",
                        "city_or_tribe", "generations", "death_years", "narrated_from",
                        "narrated_to", "kunyahs_arabic", "doubtful_reasons", "notes"):
                known = {json.dumps(e, ensure_ascii=False, sort_keys=True) for e in head[key]}
                for entry in other[key]:
                    if json.dumps(entry, ensure_ascii=False, sort_keys=True) not in known:
                        head[key].append(entry)
            head["source_assessments"].extend(other["source_assessments"])
            head["reliability_grades"].extend(other["reliability_grades"])
            head["contributing_sources"].extend(other["contributing_sources"])
            head["merge_log"].append({"book": None, "layer": "layer3",
                                      "matched_key": None, "score": None,
                                      "name": other["primary_arabic_name"],
                                      "absorbed_merged_id": other["merged_id"]})
            head["is_doubtful"] = head["is_doubtful"] or other["is_doubtful"]
            for flag in other["sect_flags"]:
                if flag not in head["sect_flags"]:
                    head["sect_flags"].append(flag)
            if len(other["primary_arabic_name"] or "") > len(head["primary_arabic_name"] or ""):
                head["primary_arabic_name"] = other["primary_arabic_name"]
            if not head["primary_english_name"]:
                head["primary_english_name"] = other["primary_english_name"]
        final.append(head)

    for name, payload in (("merged_final", final),
                          ("review_queue", review),
                          ("apply_stats", {"counts": dict(counts),
                                           "errors": errors,
                                           "unanswered": unanswered[:200]})):
        with open(os.path.join(args.l3_dir, f"{name}.json"), "w") as handle:
            json.dump(payload, handle, ensure_ascii=False)

    print(f"\n{len(merged)} -> {len(final)} profiles")
    print(f"Output: {args.l3_dir}")


if __name__ == "__main__":
    main()
