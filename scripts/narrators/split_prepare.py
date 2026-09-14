#!/usr/bin/env python3
"""Prepare the split pass: people the pipeline may have assembled from several men.

Every earlier layer can join; none can take apart. A rule merge on a thin profile, an agent
answer that trusted a contaminated alias, an entry whose heading names one man and whose
quotation describes another: each leaves a person carrying a second man's sources, and the
reliability verdicts that come with them. On the post-stage-3 build, 1008's fusion of
al-Najashi's thiqa 'Amr b. Hurayth with the Companion of that name is still one person, and
the stage 3 agents named hundreds more in their answers.

A candidate is a person of two or more source entries that at least one signal points at:

  agent flag        an agent answer in any Layer 3 run said a profile mixes men — "fused",
                    "contaminated", "carries another man's entry" — naming an id the task
                    offered; the id is followed to the person its seed belongs to now
  review signal     an agent's separation now lies inside one person (build_people.py)
  verdict clash     a positive and a negative verdict and two different kunyahs together
  generation clash  the Imams a person is said to be a companion of lie more than
                    MAX_ERA_SPAN apart — a Companion of the Prophet who is also a companion
                    of al-Sadiq — or his death years more than MAX_DEATH_SPREAD years apart

Each task shows one person as his source **entries** — a Layer 0 fragment group, never a
single page, so a split cannot cut an entry in half — each with its own name, lineage,
teachers, students, verdict and quotation, and asks an agent to partition them into the men
they describe. A person whose every entry an earlier split already placed is not asked again.

Output is a Layer 3 run of `kind: "split"` tasks. Ids are entry numbers; id_map.json maps each
to every source key of its entry, because a split binds whole entries (record_decisions.py).

Reads  tmp/narrators_identity/{people,membership,review_signals}.json, decisions.jsonl,
       tmp/narrators_normalized/*.json, tmp/narrators_l3/runs/*/{batches,outputs,id_map.json}
Writes tmp/narrators_l3/runs/split-<count>-<sha16>/{batches,outputs,manifest.json,
       id_map.json,candidates.json,single_source_flags.json}

Usage:
    python3 scripts/narrators/split_prepare.py --dry-run
    python3 scripts/narrators/split_prepare.py
"""

import argparse
from collections import Counter, defaultdict
import glob
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_people import assemble, load_sources  # noqa: E402
from crossform_prepare import people_fingerprint  # noqa: E402
from identity import anchor_rank, fragment_members, live, load_record, write_json_atomic  # noqa: E402
from l3_prepare import DEFAULT_BUDGET, evidence, pack  # noqa: E402
from narrator_schema import fold_kunyah  # noqa: E402

REPO = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
TMP = os.path.join(REPO, "tmp")

POSITIVE = {"thiqa", "saduq", "hasan", "qawi"}
NEGATIVE = {"daif", "very_weak", "kadhdhab"}
FUSION = re.compile(r"(fus|mix|conflat|composite|contaminat|another man|two men|several men|"
                    r"bled|split)", re.I)

# Imams by era, as the extraction's `generations` text names them. Only unambiguous names:
# "Abu Ja'far" is al-Baqir or al-Jawad and "Abu al-Hasan" three Imams, so neither counts.
ERAS = [
    (0, r"prophet|messenger of (god|allah)|النبي|رسول الله"),
    (1, r"amir al|commander of the faithful|أمير المؤمنين"),
    (2, r"mujtaba|المجتبى"),
    (4, r"sajjad|zayn al|السجاد|زين العابدين"),
    (5, r"baqir|الباقر"),
    (6, r"sadiq|الصادق"),
    (7, r"kazim|kadhim|الكاظم"),
    (8, r"\bridh?a\b|الرضا"),
    (9, r"jawad|al-thani|الجواد"),
    (10, r"\bhadi\b|al-thalith|الهادي"),
    (11, r"askari|العسكري"),
    (12, r"occultation|ghayba|الغيبة"),
]
# Long-lived narrators span four or five Imams from al-Sadiq to al-'Askari, and the extracted
# text adds guesses ("implied by era"); more than five apart is a different man, not a long life.
MAX_ERA_SPAN = 5
MAX_DEATH_SPREAD = 60


def eras(person):
    found = set()
    for entry in person["generations"]:
        text = entry["value"].lower().replace("'", "").replace("’", "")
        for era, pattern in ERAS:
            if re.search(pattern, text):
                found.add(era)
    return found


def death_years(person):
    years = []
    for entry in person["death_years"]:
        match = re.search(r"\b(\d{2,3})\b", str(entry["value"]))
        if match and 10 <= int(match.group(1)) <= 500:
            years.append(int(match.group(1)))
    return years


def intrinsic_signals(person):
    signals = []
    grades = {g["grade"] for g in person["reliability_grades"]}
    kunyahs = {fold_kunyah(e["value"]) for e in person["kunyahs_arabic"]} - {None, ""}
    if grades & POSITIVE and grades & NEGATIVE and len(kunyahs) >= 2:
        signals.append("verdict clash")
    found, years = eras(person), death_years(person)
    if (found and max(found) - min(found) > MAX_ERA_SPAN) or (
            years and max(years) - min(years) > MAX_DEATH_SPREAD):
        signals.append("generation clash")
    return signals


def agent_flags(runs_dir, membership):
    """Person id -> runs whose agents said a profile of his mixes men."""
    flagged = defaultdict(set)
    for run in sorted(glob.glob(os.path.join(runs_dir, "*"))):
        name = os.path.basename(run)
        id_map_path = os.path.join(run, "id_map.json")
        if name.startswith("split-") or not os.path.exists(id_map_path):
            continue
        with open(id_map_path) as handle:
            seeds = json.load(handle).get("seeds", {})
        tasks = {}
        for path in glob.glob(os.path.join(run, "batches", "*.json")):
            with open(path) as handle:
                for task in json.load(handle)["tasks"]:
                    tasks[task["task_id"]] = task
        for path in glob.glob(os.path.join(run, "outputs", "*.json")):
            with open(path) as handle:
                answers = json.load(handle).get("decisions", [])
            for answer in answers:
                task = tasks.get(answer.get("task_id"))
                if task is None:
                    continue
                offered = {p["merged_id"] for p in task.get("profiles", [])}
                offered |= {c["merged_id"] for c in task.get("candidates", [])}
                if "subject" in task:
                    offered.add(task["subject"]["merged_id"])
                text = answer.get("notes") or answer.get("reason") or ""
                for sentence in re.split(r"(?<=[.;])\s+", text):
                    if not FUSION.search(sentence):
                        continue
                    for number in re.findall(r"\b\d{1,5}\b", sentence):
                        seed = seeds.get(number) if int(number) in offered else None
                        person = membership.get(seed) if seed else None
                        if person:
                            flagged[person].add(name)
    return flagged


def already_split(record_path):
    """Source keys an earlier split has placed."""
    placed = set()
    for d in live(load_record(record_path)):
        if d["actor"] == "agent" and d["method"] == "split_partition":
            placed |= {k for g in d["groups"] for k in g}
    return placed


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--identity-dir", default=os.path.join(TMP, "narrators_identity"))
    parser.add_argument("--normalized-dir", default=os.path.join(TMP, "narrators_normalized"))
    parser.add_argument("--out-dir", default=os.path.join(TMP, "narrators_l3"))
    parser.add_argument("--budget", type=int, default=DEFAULT_BUDGET)
    parser.add_argument("--dry-run", action="store_true", help="count tasks, write nothing")
    args = parser.parse_args()

    def load(name):
        with open(os.path.join(args.identity_dir, name)) as handle:
            return json.load(handle)

    people, membership = load("people.json"), load("membership.json")
    by_id = {p["person_id"]: p for p in people}
    fingerprint = people_fingerprint(people)

    signals = defaultdict(set)
    for person in people:
        for signal in intrinsic_signals(person):
            signals[person["person_id"]].add(signal)
    for signal in load("review_signals.json"):
        if signal.get("person_id") in by_id:
            signals[signal["person_id"]].add("review signal")
    for person_id in agent_flags(os.path.join(args.out_dir, "runs"), membership):
        if person_id in by_id:
            signals[person_id].add("agent flag")

    placed = already_split(os.path.join(args.identity_dir, "decisions.jsonl"))
    members = fragment_members(args.normalized_dir)
    head_of = {k: head for head, keys in members.items() for k in keys}
    profiles = load_sources(args.normalized_dir)

    counts, tasks, id_map, seeds, candidates, single = Counter(), [], {}, {}, [], []
    next_id = 1
    for person_id in sorted(signals, key=lambda p: int(p[1:])):
        person = by_id[person_id]
        why = sorted(signals[person_id])
        for signal in why:
            counts[signal] += 1
        if len(person["source_keys"]) < 2:
            single.append({"person_id": person_id, "name": person["primary_arabic_name"],
                           "signals": why, "source_keys": person["source_keys"]})
            counts["single_source"] += 1
            continue
        if all(k in placed for k in person["source_keys"]):
            counts["already_split"] += 1
            continue
        entries = defaultdict(list)
        for key in person["source_keys"]:
            entries[head_of.get(key, key)].append(key)
        if len(entries) < 2:
            counts["one_entry"] += 1
            continue
        shown = []
        for head in sorted(entries, key=anchor_rank):
            keys = sorted(entries[head], key=anchor_rank)
            profile = evidence(dict(assemble(keys, profiles), merged_id=next_id))
            profile["source_keys"] = keys
            shown.append(profile)
            id_map[str(next_id)], seeds[str(next_id)] = keys, head
            next_id += 1
        tasks.append({"kind": "split", "method": "split_partition",
                      "task_id": f"split:{person_id}", "person_id": person_id,
                      "name_ar": person["primary_arabic_name"], "signals": why,
                      "profiles": shown})
        candidates.append({"person_id": person_id, "name": person["primary_arabic_name"],
                           "signals": why, "entries": len(shown),
                           "sources": len(person["source_keys"])})

    sizes = Counter(min(len(t["profiles"]) // 10 * 10, 50) for t in tasks)
    print(f"people fingerprint: {fingerprint}")
    print(f"{len(signals)} people signalled -> {len(tasks)} split tasks, "
          f"{sum(len(t['profiles']) for t in tasks)} entries; entries per task (by tens) "
          f"{sorted(sizes.items())}")
    print("  " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items())))
    batches = pack(tasks, args.budget)
    print(f"  -> {len(batches)} batches at a budget of {args.budget} chars")
    if args.dry_run:
        return

    run_dir = os.path.join(args.out_dir, "runs", "split-" + fingerprint.replace(":", "-"))
    batch_dir = os.path.join(run_dir, "batches")
    if os.path.isdir(batch_dir) and os.listdir(batch_dir):
        raise SystemExit(f"{batch_dir} already holds batches — runs are immutable")
    os.makedirs(batch_dir)
    os.makedirs(os.path.join(run_dir, "outputs"), exist_ok=True)
    manifest = {"budget": args.budget, "merge_fingerprint": f"people:{fingerprint}",
                "kind": "split", "batches": []}
    for number, batch in enumerate(batches):
        name = f"split_{number:04d}.json"
        path = os.path.join(batch_dir, name)
        write_json_atomic(path, {"kind": "split", "batch": name,
                                 "merge_fingerprint": f"people:{fingerprint}", "tasks": batch})
        manifest["batches"].append({"batch": name, "kind": "split", "tasks": len(batch),
                                    "profiles": sum(len(t["profiles"]) for t in batch),
                                    "chars": os.path.getsize(path)})
    write_json_atomic(os.path.join(run_dir, "manifest.json"), manifest)
    write_json_atomic(os.path.join(run_dir, "id_map.json"), {
        "merge_fingerprint": f"people:{fingerprint}", "method": "entries",
        "map": id_map, "seeds": seeds})
    write_json_atomic(os.path.join(run_dir, "candidates.json"), candidates)
    write_json_atomic(os.path.join(run_dir, "single_source_flags.json"), single)
    print(f"\nwrote {len(batches)} batches -> {batch_dir}")


if __name__ == "__main__":
    main()
