#!/usr/bin/env python3
"""Prepare Layer 3 batches for Claude sub-agents.

Layers 0-2 resolve what rules can. What is left needs a reading of the biographical text,
so each batch carries the evidence with it — names, aliases, kunyah, nisbahs, city,
generation, death year, teachers, students, and a snippet of the source quotation. Without
the quotation an agent is guessing, and guessing is the failure mode this whole rebuild
exists to prevent.

Two kinds of task:

  group  — one exact name held by several merged profiles. Partition them into people.
           Better posed than pairwise questions: kunyah is absent on 82-92% of same-name
           profiles, so most pairs carry no evidence either way, while the group as a whole
           usually does.
  pair   — a profile matched a candidate on an alias or partial name and context was
           inconclusive. Same person as one of the candidates, or nobody?

Each merge gets its own immutable run directory, named for its fingerprint:

    tmp/narrators_l3/runs/<fingerprint>/{batches,outputs}/

Batches are never rewritten in place. A sub-agent reading a batch file cannot have it
change under it because the merge was re-run, and answers to an older merge stay where
they were rather than needing to be archived by hand.

Reads  tmp/narrators_merge/{merged,name_group_tasks,deferred}.json
Writes tmp/narrators_l3/runs/<fingerprint>/batches/{group,pair}_NNNN.json

Usage:
    python3 scripts/narrators/l3_prepare.py
    python3 scripts/narrators/l3_prepare.py --kinds group --budget 40000
"""

import argparse
import glob
import hashlib
import json
import os

REPO = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
TMP = os.path.join(REPO, "tmp")

ASSESSMENT_AR_CHARS = 320
ASSESSMENT_EN_CHARS = 220
MAX_CHAIN_NAMES = 6

# Characters of task payload per batch. Keeps a sub-agent's reading within one sitting; a
# group larger than this is still emitted alone rather than split, because splitting a
# partition task destroys the comparison it exists to make.
DEFAULT_BUDGET = 120000


def run_directory(out_dir, fingerprint):
    """Immutable per-merge run directory.

    The fingerprint carries a colon, which is legal on this filesystem but awkward in
    shell paths, so it becomes a hyphen in the directory name.
    """
    return os.path.join(out_dir, "runs", fingerprint.replace(":", "-"))


def merge_fingerprint(merged):
    """Stable digest of the merge these batches were built from.

    Task ids are merge-relative, so a decision file is only valid against the merge that
    produced its batch. Re-running the merge invalidates outstanding answers, and apply
    refuses to silently mix them.
    """
    digest = hashlib.sha256()
    for profile in sorted(merged, key=lambda p: p["merged_id"]):
        digest.update(f"{profile['merged_id']}\t{profile['primary_arabic_name']}\n".encode())
    return f"{len(merged)}:{digest.hexdigest()[:16]}"


def snippet(text, limit):
    if not text:
        return None
    text = " ".join(str(text).split())
    return text if len(text) <= limit else text[:limit] + "…"


def evidence(merged):
    """The profile as a sub-agent needs to see it: identity plus the source's own words."""
    assessments = []
    for item in merged["source_assessments"][:3]:
        ar = snippet(item.get("assessment_ar"), ASSESSMENT_AR_CHARS)
        en = snippet(item.get("assessment_en"), ASSESSMENT_EN_CHARS)
        if ar or en:
            assessments.append({
                "source": item.get("source_name"),
                "arabic": ar,
                "english": en,
            })
    values = lambda key: [e["value"] for e in merged.get(key, [])]
    out = {
        "merged_id": merged["merged_id"],
        "name_ar": merged["primary_arabic_name"],
        "name_en": merged["primary_english_name"],
        "books": sorted({s["book"] for s in merged["contributing_sources"]}),
        "assessments": assessments,
    }
    optional = {
        "aliases_ar": values("arabic_aliases")[:8],
        "kunyah": merged.get("kunyah_arabic"),
        "titles": values("titles")[:6],
        "city_or_tribe": values("city_or_tribe")[:4],
        "generation": values("generations")[:3],
        "death_year": values("death_years")[:3],
        "narrated_from": values("narrated_from")[:MAX_CHAIN_NAMES],
        "narrated_to": values("narrated_to")[:MAX_CHAIN_NAMES],
        "grades": sorted({g["grade"] for g in merged["reliability_grades"] if g["grade"]}),
    }
    # Absent fields are omitted rather than sent as null: the agent should see what the
    # sources actually say, not a wall of empties that reads like conflicting evidence.
    out.update({k: v for k, v in optional.items() if v})
    return out


def build_group_tasks(merged_by_id, groups):
    tasks = []
    for group in groups:
        profiles = [merged_by_id[p["merged_id"]] for p in group["profiles"]
                    if p["merged_id"] in merged_by_id]
        if len(profiles) < 2:
            continue
        tasks.append({
            "kind": "group",
            "task_id": f"group:{group['normalized_name']}",
            "normalized_name": group["normalized_name"],
            "profiles": [evidence(p) for p in profiles],
        })
    return tasks


def build_pair_tasks(merged_by_id, deferred, source_to_merged):
    """Pairwise tasks, minus the ones an agent cannot help with.

    Where no candidate scores any positive context, there is nothing to read: the names
    collided and the sources say nothing that bears on identity. The design's default is to
    keep such profiles separate, and asking an agent to decide on absent evidence invites
    exactly the confident wrong merge this rebuild exists to prevent. Those are resolved
    here and recorded, not sent out.
    """
    tasks, auto_separate = [], []
    for index, item in enumerate(deferred):
        mid = source_to_merged.get((item["book"], item["source_index"]))
        if mid is None or mid not in merged_by_id:
            continue
        if all(c["score"] <= 0 for c in item["candidates"]):
            auto_separate.append({
                "merged_id": mid,
                "reason": item["reason"],
                "resolution": "keep_separate",
                "basis": "no candidate carries positive context evidence",
                "candidates": [c["merged_id"] for c in item["candidates"]],
            })
            continue
        candidates = [merged_by_id[c["merged_id"]] for c in item["candidates"]
                      if c["merged_id"] in merged_by_id and c["merged_id"] != mid]
        if not candidates:
            continue
        tasks.append({
            "kind": "pair",
            "task_id": f"pair:{index}",
            "reason": item["reason"],
            "subject": evidence(merged_by_id[mid]),
            "candidates": [evidence(c) for c in candidates[:3]],
        })
    return tasks, auto_separate


def pack(tasks, budget):
    """Group tasks into batches under a character budget, largest first.

    A single task over budget goes in a batch of its own — a partition task cannot be split
    without destroying the comparison it exists to make.
    """
    sized = sorted(((len(json.dumps(t, ensure_ascii=False)), t) for t in tasks),
                   key=lambda pair: -pair[0])
    batches, current, current_size = [], [], 0
    for size, task in sized:
        if size >= budget:
            batches.append([task])
            continue
        if current and current_size + size > budget:
            batches.append(current)
            current, current_size = [], 0
        current.append(task)
        current_size += size
    if current:
        batches.append(current)
    return batches


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--merge-dir", default=os.path.join(TMP, "narrators_merge"))
    parser.add_argument("--out-dir", default=os.path.join(TMP, "narrators_l3"))
    parser.add_argument("--kinds", default="group,pair")
    parser.add_argument("--budget", type=int, default=DEFAULT_BUDGET)
    args = parser.parse_args()

    kinds = [k.strip() for k in args.kinds.split(",") if k.strip()]
    with open(os.path.join(args.merge_dir, "merged.json")) as handle:
        merged = json.load(handle)
    merged_by_id = {p["merged_id"]: p for p in merged}
    source_to_merged = {}
    for profile in merged:
        for source in profile["contributing_sources"]:
            source_to_merged[(source["book"], source["source_index"])] = profile["merged_id"]

    run_dir = run_directory(args.out_dir, merge_fingerprint(merged))
    batch_dir = os.path.join(run_dir, "batches")
    os.makedirs(batch_dir, exist_ok=True)
    os.makedirs(os.path.join(run_dir, "outputs"), exist_ok=True)

    fingerprint = merge_fingerprint(merged)
    print(f"merge fingerprint: {fingerprint}")
    print(f"run directory:     {run_dir}\n")

    # Preserve entries for kinds this run is not regenerating. Running with --kinds group
    # must not drop the pair batches from the manifest and strand their answers.
    manifest_path = os.path.join(run_dir, "manifest.json")
    kept = []
    if os.path.exists(manifest_path):
        with open(manifest_path) as handle:
            previous = json.load(handle)
        if previous.get("merge_fingerprint") in (None, fingerprint):
            kept = [b for b in previous.get("batches", []) if b["kind"] not in kinds]
            if kept:
                print(f"keeping {len(kept)} existing batch entries for "
                      f"{sorted({b['kind'] for b in kept})}")
        else:
            print("existing manifest is from a different merge — replacing it")
    manifest = {"budget": args.budget,
                "merge_fingerprint": fingerprint,
                "batches": list(kept)}
    for kind in kinds:
        if kind == "group":
            with open(os.path.join(args.merge_dir, "name_group_tasks.json")) as handle:
                tasks = build_group_tasks(merged_by_id, json.load(handle))
        elif kind == "pair":
            with open(os.path.join(args.merge_dir, "deferred.json")) as handle:
                tasks, auto_separate = build_pair_tasks(
                    merged_by_id, json.load(handle), source_to_merged)
            with open(os.path.join(run_dir, "auto_separate.json"), "w") as handle:
                json.dump(auto_separate, handle, ensure_ascii=False, indent=1)
            print(f"pair: {len(auto_separate)} resolved as keep-separate without an agent "
                  f"(no positive evidence)")
        else:
            raise SystemExit(f"unknown kind: {kind}")

        batches = pack(tasks, args.budget)

        # Remove this kind's batch files from any previous run. A run that produces fewer
        # batches than the last one would otherwise leave the tail behind, and those stale
        # files carry the old merge's fingerprint and task ids.
        stale = sorted(glob.glob(os.path.join(batch_dir, f"{kind}_*.json")))
        keep = {f"{kind}_{n:04d}.json" for n in range(len(batches))}
        removed = 0
        for path in stale:
            if os.path.basename(path) not in keep:
                os.remove(path)
                removed += 1
        if removed:
            print(f"{kind}: removed {removed} stale batch file(s) from a previous run")

        print(f"{kind}: {len(tasks)} tasks -> {len(batches)} batches")
        for number, batch in enumerate(batches):
            name = f"{kind}_{number:04d}.json"
            path = os.path.join(batch_dir, name)
            with open(path, "w") as handle:
                json.dump({"kind": kind, "batch": name,
                           "merge_fingerprint": fingerprint, "tasks": batch},
                          handle, ensure_ascii=False, indent=1)
            manifest["batches"].append({
                "batch": name,
                "kind": kind,
                "tasks": len(batch),
                "profiles": sum(len(t.get("profiles", [])) or (1 + len(t.get("candidates", [])))
                                for t in batch),
                "chars": os.path.getsize(path),
            })

    manifest["batches"].sort(key=lambda b: (b["kind"], b["batch"]))
    with open(manifest_path, "w") as handle:
        json.dump(manifest, handle, ensure_ascii=False, indent=1)
    total = sum(b["chars"] for b in manifest["batches"])
    print(f"\n{len(manifest['batches'])} batches, {total // 1000}K chars -> {batch_dir}")


if __name__ == "__main__":
    main()
