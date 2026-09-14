#!/usr/bin/env python3
"""Prepare cross-form reconciliation batches: one man, headed differently by different books.

Layer 3 partitions profiles that share one exact primary name. That leaves a gap the famous
narrators fall straight into. Al-Najashi heads al-Husayn b. Sa'id as `الحسين بن سعيد`,
al-Fihrist as `الحسين بن سعيد بن حماد بن سعيد بن مهران الاهوازي`, and Jami' al-Ruwat as
`الحسين بن سعيد بن حماد` — three name groups, three Layer 3 tasks, and no task that ever put
the three entries side by side. On the post-stage-2 build the main entries of Sahl b. Ziyad,
Ibn Abi 'Umayr, al-Husayn b. Sa'id and Zurara each sat in two or three people that no agent had
compared.

This pass asks that question and only that one. Its unit is a **person** who holds an entry
in a main Rijal work (MAIN_BOOKS) — the books that give one entry per man, unlike Khoei and
Mamaqani, which were extracted per mention. Each such person carries identifying name forms
(narrator_schema.is_identifying_alias) and, for each, the lineage and nisbah truncations that
still identify. A task is one name form and every main-entry person carrying it, whole or as a
truncation, provided at least one of them carries it whole:

    الحسين بن سعيد   <-  الحسين بن سعيد                       (al-Najashi, whole)
                     <-  الحسين بن سعيد بن حماد               (Jami' al-Ruwat, truncated)
                     <-  الحسين بن سعيد بن حماد ... الاهوازي   (al-Fihrist, truncated)

Tasks are per form, not per connected component. Joined transitively, one contaminated alias
chains unrelated men into tangles of 40; per form, each task stays a question a reader can
answer. A form carried by more than MAX_OWNERS main-entry people is too common to be a question
(`احمد بن محمد`), people whom agents have already judged together are not asked again, and a
task whose members are all inside a larger task is dropped as asked.

`--attach` asks the second question, once main entries have met. Khoei and Mamaqani describe
many men well — kunyah, nisbah, teachers, students — in profiles that hold no main entry of
their own, so the per-form pass never sees them: `إبراهيم بن هاشم أبو إسحاق القمي`, whose student
is his son 'Ali, stood apart from the man whose al-Najashi and al-Fihrist entries it describes.
Each attach task is a pair: one such person, and up to MAX_ATTACH_CANDIDATES main-entry people
who carry the same name form with no contradicting kunyah and at least one agreeing kunyah or
nisbah, ranked by how many agree. Pairs rather than groups, because the answer is "which of
these, or none", and a pair carries a confidence, which thin evidence needs. A profile of a
single source with no verdict, kunyah or nisbah has nothing to decide on and is not asked.

Output is a Layer 3 run in the usual shape — `kind: "group"` tasks with
`"method": "crossform_group"`, or `kind: "pair"` tasks with `"method": "attach_pair"`, and an
id_map.json of ids to source keys and seeds — so the same brief (l3_agent_prompt.md), dispatch
(l3_dispatch.py --run) and recording (record_decisions.py --no-rules --l3-run) serve it. Ids are
the person id's number (n000515 -> 515); each person's seed is its anchor source.

Reads  tmp/narrators_identity/{people.json,decisions.jsonl}
Writes tmp/narrators_l3/runs/{xform,attach}-<count>-<sha16>/{batches,outputs,manifest.json,
       id_map.json,candidates.json}

Usage:
    python3 scripts/narrators/crossform_prepare.py
    python3 scripts/narrators/crossform_prepare.py --attach
    python3 scripts/narrators/crossform_prepare.py --attach --dry-run
"""

import argparse
from collections import Counter, defaultdict
import hashlib
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from identity import live, load_record, write_json_atomic  # noqa: E402
from l3_prepare import DEFAULT_BUDGET, evidence, pack  # noqa: E402
from narrator_schema import fold_kunyah, is_identifying_alias, normalize_arabic  # noqa: E402

REPO = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
TMP = os.path.join(REPO, "tmp")

MAIN_BOOKS = {"najashi", "fihrist", "kashshi", "tusi", "duafa", "ardabili"}
MAX_OWNERS = 12
MAX_ATTACH_CANDIDATES = 3
MIN_TOKENS = 3
CONNECTORS = {"بن", "ابن", "بنت"}


def people_fingerprint(people):
    digest = hashlib.sha256()
    for person in sorted(people, key=lambda p: p["person_id"]):
        digest.update(f"{person['person_id']}\t{person['anchor']}\n".encode())
    return f"{len(people)}:{digest.hexdigest()[:16]}"


def name_forms(person):
    forms = set()
    for entry in person["primary_names"] + person["arabic_aliases"]:
        form = normalize_arabic(entry["value"])
        if form and is_identifying_alias(form):
            forms.add(form)
    return forms


def truncations(form):
    """The form and every shorter opening of it that still identifies a man.

    A truncation may not end on a connector (`الحسين بن`), and must pass the same name-class
    test as any alias, so `ابو محمد` and bare nisbahs never become keys.
    """
    tokens = form.split()
    out = {form}
    for end in range(MIN_TOKENS, len(tokens)):
        opening = " ".join(tokens[:end])
        if tokens[end - 1] not in CONNECTORS and is_identifying_alias(opening):
            out.add(opening)
    return out


def judged_together(record_path):
    """Source key -> every source key an agent has decided alongside it."""
    seen = defaultdict(set)
    for d in live(load_record(record_path)):
        if d["actor"] != "agent" or d["kind"] not in ("partition", "same", "not_same"):
            continue
        keys = set(d.get("sources") or []) | {k for g in (d.get("groups") or []) for k in g}
        for k in keys:
            seen[k] |= keys
    return seen


def build_tasks(people, record_path, counts):
    main = [p for p in people if any(k.split(":")[0] in MAIN_BOOKS for k in p["source_keys"])]
    by_id = {p["person_id"]: p for p in main}
    forms = {p["person_id"]: name_forms(p) for p in main}
    owners = defaultdict(set)
    for pid, fs in forms.items():
        for form in fs:
            for key in truncations(form):
                owners[key].add(pid)

    seen = judged_together(record_path)

    def judged(a, b):
        other = set(by_id[b]["source_keys"])
        return any(seen.get(k, set()) & other for k in by_id[a]["source_keys"])

    questions = {}
    for key, ids in owners.items():
        if len(ids) < 2 or not any(key in forms[i] for i in ids):
            continue
        if len(ids) > MAX_OWNERS:
            counts["form_too_common"] += 1
            continue
        members = frozenset(ids)
        if all(judged(a, b) for a in members for b in members if a < b):
            counts["already_judged"] += 1
            continue
        questions.setdefault(members, set()).add(key)

    kept = []
    for members in sorted(questions, key=len, reverse=True):
        if any(members <= larger for larger in kept):
            counts["inside_a_larger_task"] += 1
            continue
        kept.append(members)

    tasks = []
    for members in kept:
        shared = sorted(questions[members], key=lambda k: (-len(k.split()), k))
        kunyahs = [{fold_kunyah(e["value"]) for e in by_id[m]["kunyahs_arabic"]} - {None, ""}
                   for m in members]
        stated = [k for k in kunyahs if k]
        if len(stated) >= 2 and not set.intersection(*stated):
            counts["tasks_with_kunyah_disagreement"] += 1
        profiles = []
        for pid in sorted(members):
            person = dict(by_id[pid], merged_id=int(pid[1:]))
            profile = evidence(person)
            profile["person_id"] = pid
            profiles.append(profile)
        tasks.append({
            "kind": "group",
            "method": "crossform_group",
            "task_id": f"xform:{shared[0]}",
            "normalized_name": shared[0],
            "shared_forms": shared,
            "profiles": profiles,
        })
    counts["main_entry_people"] = len(main)
    return tasks, by_id


def holds_main_entry(person):
    return any(k.split(":")[0] in MAIN_BOOKS for k in person["source_keys"])


def marks(person):
    """(folded kunyahs, normalized nisbahs and titles) — what narrows, never identifies."""
    kunyahs = {fold_kunyah(e["value"]) for e in person["kunyahs_arabic"]} - {None, ""}
    titles = {normalize_arabic(e["value"]) for e in person["titles"]} - {None, ""}
    return kunyahs, titles


def build_attach_tasks(people, record_path, counts):
    by_id = {p["person_id"]: p for p in people}
    main = [p for p in people if holds_main_entry(p)]
    owners = defaultdict(set)
    for person in main:
        for form in name_forms(person):
            for key in truncations(form):
                owners[key].add(person["person_id"])
    seen = judged_together(record_path)

    def judged(a, b):
        other = set(by_id[b]["source_keys"])
        return any(seen.get(k, set()) & other for k in by_id[a]["source_keys"])

    tasks = []
    for person in people:
        if holds_main_entry(person):
            continue
        kunyahs, titles = marks(person)
        if (len(person["source_keys"]) == 1 and not person["reliability_grades"]
                and not kunyahs and not titles):
            counts["thin_not_asked"] += 1
            continue
        scored = {}
        for form in name_forms(person):
            # The per-form pass's cap applies here too: `احمد بن محمد` is carried by over a
            # hundred main-entry people, and a shared kunyah among them is chance.
            if len(owners.get(form, ())) > MAX_OWNERS:
                continue
            for mid in owners.get(form, ()):
                their_kunyahs, their_titles = marks(by_id[mid])
                if kunyahs and their_kunyahs and not kunyahs & their_kunyahs:
                    continue
                agree = 2 * len(kunyahs & their_kunyahs) + len(titles & their_titles)
                if agree:
                    scored[mid] = max(scored.get(mid, 0), agree)
        if not scored:
            continue
        fresh = {mid: s for mid, s in scored.items() if not judged(person["person_id"], mid)}
        if not fresh:
            counts["already_judged"] += 1
            continue
        if len(fresh) > MAX_ATTACH_CANDIDATES:
            counts["candidates_trimmed_to_top"] += 1
        chosen = sorted(fresh, key=lambda m: (-fresh[m], m))[:MAX_ATTACH_CANDIDATES]

        def shown(p):
            profile = evidence(dict(p, merged_id=int(p["person_id"][1:])))
            profile["person_id"] = p["person_id"]
            return profile

        tasks.append({
            "kind": "pair",
            "method": "attach_pair",
            "task_id": f"attach:{person['person_id']}",
            "reason": "holds no main-book entry; shares a name form and a kunyah or nisbah "
                      "with people who do",
            "subject": shown(person),
            "candidates": [shown(by_id[m]) for m in chosen],
        })
    counts["main_entry_people"] = len(main)
    return tasks, by_id


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--identity-dir", default=os.path.join(TMP, "narrators_identity"))
    parser.add_argument("--out-dir", default=os.path.join(TMP, "narrators_l3"))
    parser.add_argument("--budget", type=int, default=DEFAULT_BUDGET)
    parser.add_argument("--attach", action="store_true",
                        help="pair people holding no main entry with main-entry people they may be")
    parser.add_argument("--dry-run", action="store_true", help="count tasks, write nothing")
    args = parser.parse_args()

    with open(os.path.join(args.identity_dir, "people.json")) as handle:
        people = json.load(handle)
    fingerprint = people_fingerprint(people)
    counts = Counter()
    record_path = os.path.join(args.identity_dir, "decisions.jsonl")
    kind, prefix = ("pair", "attach") if args.attach else ("group", "xform")
    if args.attach:
        tasks, by_id = build_attach_tasks(people, record_path, counts)
        members_of = lambda t: [t["subject"]] + t["candidates"]
        sizes = Counter(len(t["candidates"]) for t in tasks)
        shape = "candidates per subject"
    else:
        tasks, by_id = build_tasks(people, record_path, counts)
        members_of = lambda t: t["profiles"]
        sizes = Counter(len(t["profiles"]) for t in tasks)
        shape = "sizes"
    print(f"people fingerprint: {fingerprint}")
    print(f"{counts['main_entry_people']} main-entry people -> {len(tasks)} {kind} tasks, "
          f"{sum(len(members_of(t)) for t in tasks)} person-slots; {shape} {sorted(sizes.items())}")
    print("  " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items()) if k != "main_entry_people"))
    batches = pack(tasks, args.budget)
    print(f"  -> {len(batches)} batches at a budget of {args.budget} chars")
    if args.dry_run:
        return

    run_dir = os.path.join(args.out_dir, "runs", f"{prefix}-" + fingerprint.replace(":", "-"))
    batch_dir = os.path.join(run_dir, "batches")
    if os.path.isdir(batch_dir) and os.listdir(batch_dir):
        raise SystemExit(f"{batch_dir} already holds batches — runs are immutable")
    os.makedirs(batch_dir)
    os.makedirs(os.path.join(run_dir, "outputs"), exist_ok=True)

    manifest = {"budget": args.budget, "merge_fingerprint": f"people:{fingerprint}",
                "kind": prefix, "batches": []}
    for number, batch in enumerate(batches):
        name = f"{kind}_{number:04d}.json"
        path = os.path.join(batch_dir, name)
        write_json_atomic(path, {"kind": kind, "batch": name,
                                 "merge_fingerprint": f"people:{fingerprint}", "tasks": batch})
        manifest["batches"].append({"batch": name, "kind": kind, "tasks": len(batch),
                                    "profiles": sum(len(members_of(t)) for t in batch),
                                    "chars": os.path.getsize(path)})
    write_json_atomic(os.path.join(run_dir, "manifest.json"), manifest)

    members = sorted({p["person_id"] for t in tasks for p in members_of(t)})
    write_json_atomic(os.path.join(run_dir, "id_map.json"), {
        "merge_fingerprint": f"people:{fingerprint}", "method": "person_ids",
        "map": {str(int(pid[1:])): by_id[pid]["source_keys"] for pid in members},
        "seeds": {str(int(pid[1:])): by_id[pid]["anchor"] for pid in members}})
    write_json_atomic(os.path.join(run_dir, "candidates.json"), [
        {"task_id": t["task_id"], "shared_forms": t.get("shared_forms"),
         "people": [p["person_id"] for p in members_of(t)]} for t in tasks])
    print(f"\nwrote {len(batches)} batches -> {batch_dir}")


if __name__ == "__main__":
    main()
