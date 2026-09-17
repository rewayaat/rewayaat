#!/usr/bin/env python3
"""Prepare the cross-reference pass: joins the sources themselves assert.

Every other pass infers identity from evidence — a shared teacher, a matching lineage, a quoted
verdict. This one asks about the cases where a source states it. Khoei and Mamaqani write
«تقدم في ...» (mentioned earlier under X), «يأتي في ...» (comes later under X), «متحد مع سابقه»
(identical with the one before him): a pointer from one entry to another entry for the same man.
2,787 entries carry such a pointer, and 731 of them still sit alone in a person of one entry,
their pointer having gone nowhere. The stage 5a audit found that splitting, not wrong joining, is
what remains, and 6 of the 9 doubles it found that no pass had ever compared share no name form —
which is exactly the case a pointer can reach and a name-form pass cannot.

Two kinds of pointer resolve:

  positional   «متحد مع سابقه», «مع من بعده» — the neighbouring entry of the same book, which is
               the entry at source_index ± 1
  by name      «تقدم في أحمد بن علي» — the people carrying that name form, longest match first

A task is a pair in the usual shape: the person holding the pointing entry, and the people its
pointer resolves to, with the pointer's own words quoted so the agent can weigh what the source
actually said. The source's statement is evidence, not a decision: it may point at a name several
men carry, the pointer may be a scribe's, and «يأتي في الكنى» points at a section rather than a
man. The agent still answers, and thin evidence still goes to review.

Reads  tmp/narrators_identity/{people,membership}.json, decisions.jsonl,
       tmp/narrators_normalized/*.json
Writes tmp/narrators_l3/runs/xref-<count>-<sha16>/{batches,outputs,manifest.json,id_map.json,
       candidates.json}

Usage:
    python3 scripts/narrators/xref_prepare.py --dry-run
    python3 scripts/narrators/xref_prepare.py
    python3 scripts/narrators/xref_prepare.py --rejudge
"""

import argparse
from collections import Counter, defaultdict
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_people import load_sources  # noqa: E402
from crossform_prepare import (holds_main_entry, judged_together, name_forms,  # noqa: E402
                               people_fingerprint, truncations)
from identity import write_json_atomic  # noqa: E402
from l3_prepare import DEFAULT_BUDGET, evidence, pack  # noqa: E402
from narrator_schema import normalize_arabic as NA  # noqa: E402

REPO = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
TMP = os.path.join(REPO, "tmp")

MAX_CANDIDATES = 6
# a form carried by more people than this is too common for a pointer to single out
MAX_OWNERS = 12

DIACRITICS = re.compile(r"[ً-ْٰـ]")
BY_NAME = re.compile(r"(تقدم في|يأتي في|تقدمت ترجمته في|تقدمت ترجمته بعنوان|هو المذكور في|"
                     r"متحد مع|هو المتقدم بعنوان)\s*(?:ترجمة\s*)?([^.،؛:)\]\"]{3,60})")
# only with an identity verb: «روى عن سابقه» is a chain, «متحد مع سابقه» is the same man
IDENTITY = r"(?:متحد مع|متحدة مع|هو|هي|عين|نفس|اتحاده مع)\s+"
BEFORE = re.compile(IDENTITY + r"(سابقه|ما قبله|المتقدم عليه|من قبله)")
AFTER = re.compile(IDENTITY + r"(من بعده|ما بعده|التالي له|الآتي بعده|لاحقه)")
# «ليس بمتّحد مع سابقه» denies the identity the same words would otherwise assert
NEGATIONS = ("ليس", "ليست", "وليس", "لم يتحد", "لا يتحد")
# «يأتي في الكنى» points at a section of the book, not at a man
SECTIONS = {NA(x) for x in ("الكنى", "الالقاب", "النساء", "باب الكنى", "الابواب")}
# «تقدم في فلان أنه ...» refers to something said under that man, not to his being this man
THAT_CLAUSE = {NA(x) for x in ("ان", "انه", "انها", "أن", "أنه", "بانه", "وانه")}
# a pointer at a relative is not a pointer at himself: «تقدم في ابنه الحسن» is his son's entry
RELATIVES = {NA(x) for x in ("ابنه", "ابيه", "أبيه", "اخيه", "أخيه", "اخوه", "عمه", "جده",
                             "ولده", "ابنته", "امه", "زوجته", "ابنيه", "بنيه")}


def flatten(text):
    """Arabic without the diacritics the sources carry, so a phrase matches however vocalized."""
    return DIACRITICS.sub("", text or "")


def assessment_text(profile):
    return " ".join(flatten(a.get("assessment_ar") or "")
                    for a in profile.get("source_assessments", []) if isinstance(a, dict))


def pointers(profile):
    """The cross-references an entry's own text makes: ('name', target) or ('before'/'after', None)."""
    text = assessment_text(profile)
    negated = lambda at: any(word in text[max(0, at - 25):at] for word in NEGATIONS)
    found = []
    for match in BY_NAME.finditer(text):
        if negated(match.start()):
            continue
        target = match.group(2).strip()
        if target and NA(target) not in SECTIONS:
            found.append(("name", match.group(1), target))
    match = BEFORE.search(text)
    if match and not negated(match.start()):
        found.append(("before", match.group(0).strip(), None))
    match = AFTER.search(text)
    if match and not negated(match.start()):
        found.append(("after", match.group(0).strip(), None))
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--identity-dir", default=os.path.join(TMP, "narrators_identity"))
    parser.add_argument("--normalized-dir", default=os.path.join(TMP, "narrators_normalized"))
    parser.add_argument("--out-dir", default=os.path.join(TMP, "narrators_l3"))
    parser.add_argument("--budget", type=int, default=DEFAULT_BUDGET)
    parser.add_argument("--rejudge", action="store_true",
                        help="ask pairs an agent has already judged apart; the pointer is evidence "
                             "they may not have weighed")
    parser.add_argument("--dry-run", action="store_true", help="count tasks, write nothing")
    args = parser.parse_args()

    def load(name):
        with open(os.path.join(args.identity_dir, name)) as handle:
            return json.load(handle)

    people, membership = load("people.json"), load("membership.json")
    by_id = {p["person_id"]: p for p in people}
    fingerprint = people_fingerprint(people)
    profiles = load_sources(args.normalized_dir)
    record_path = os.path.join(args.identity_dir, "decisions.jsonl")
    seen = judged_together(record_path)

    owners = defaultdict(set)
    for person in people:
        for form in name_forms(person):
            for key in truncations(form):
                owners[key].add(person["person_id"])

    def judged(a, b):
        other = set(by_id[b]["source_keys"])
        return any(seen.get(k, set()) & other for k in by_id[a]["source_keys"])

    def by_name(target):
        """The people a pointer's name resolves to — or why the pointer is not about identity.

        The phrase runs into the sentence after it, so the name is the longest opening that is
        a name form somebody carries. What follows that name decides what the pointer meant:
        «تقدم في الحسن بن سعيد أنه المتفرد بالرواية عن فضالة» tells you where a fact was said,
        not that this man is al-Hasan b. Sa'id.
        """
        tokens = NA(target).split()
        if not tokens:
            return None, set(), "empty"
        if tokens[0] in RELATIVES:
            return None, set(), "points at a relative"
        for size in range(len(tokens), 1, -1):
            form = " ".join(tokens[:size])
            if form in owners and len(owners[form]) <= MAX_OWNERS:
                rest = tokens[size:]
                if rest and rest[0] in THAT_CLAUSE:
                    return None, set(), "refers to what was said there"
                return form, set(owners[form]), None
        return None, set(), "name not found"

    def neighbour(key, step):
        """The entry beside this one in the same book, when it is plainly the entry meant.

        Index order tracks the book's order for 94% of these pointers, but not across a page
        boundary or where a mention was extracted out of turn — «اتحاده مع سابقه» under
        عبد الله بن المغيرة lands on موسى بن بكر. A neighbour who is not even a namesake is not
        the man the pointer meant.
        """
        book, index = key.split(":")
        other = f"{book}:{int(index) + step}"
        if other not in profiles:
            return None
        head = lambda k: (NA(profiles[k][1].get("primary_arabic_name") or "").split() or [""])[0]
        if not head(key) or head(key) != head(other):
            return None
        return membership.get(other)

    counts, tasks, quoted = Counter(), [], {}
    for key, (book, profile) in sorted(profiles.items()):
        found = pointers(profile)
        if not found:
            continue
        counts["entries with a pointer"] += 1
        subject = membership.get(key)
        if subject is None:
            counts["entry excluded from identity"] += 1
            continue
        candidates, why = set(), []
        for kind, phrase, target in found:
            if kind == "name":
                form, hits, why_not = by_name(target)
                if not hits:
                    counts[f"pointer skipped: {why_not}"] += 1
                    continue
                candidates |= hits
                why.append(f"«{phrase} {target.strip()}» → {form}")
            else:
                other = neighbour(key, -1 if kind == "before" else 1)
                if other is None:
                    counts["neighbour not found"] += 1
                    continue
                candidates.add(other)
                why.append(f"«{phrase}» → the {'previous' if kind == 'before' else 'next'} entry "
                           f"of {book}")
        candidates.discard(subject)
        if not candidates:
            counts["pointer resolved only to himself"] += 1
            continue
        fresh = {c for c in candidates if args.rejudge or not judged(subject, c)}
        if not fresh:
            counts["already judged with every candidate"] += 1
            continue
        chosen = sorted(fresh, key=lambda c: (not holds_main_entry(by_id[c]),
                                              -len(by_id[c]["source_keys"]), c))[:MAX_CANDIDATES]
        if len(fresh) > MAX_CANDIDATES:
            counts["candidates trimmed"] += 1

        def shown(person):
            out = evidence(dict(person, merged_id=int(person["person_id"][1:])))
            out["person_id"] = person["person_id"]
            return out

        tasks.append({
            "kind": "pair", "method": "xref_pair", "task_id": f"xref:{key}",
            "reason": "the subject's own entry points at another entry for the same man",
            "pointer": "; ".join(why), "pointing_entry": key,
            "subject": shown(by_id[subject]),
            "candidates": [shown(by_id[c]) for c in chosen]})
        quoted[f"xref:{key}"] = why
        counts["tasks"] += 1
        counts["task from a positional pointer" if any(k != "name" for k, _, _ in found)
               else "task from a named pointer"] += 1
        counts["subject is alone in a one-entry person"] += len(by_id[subject]["source_keys"]) == 1

    members_of = lambda t: [t["subject"]] + t["candidates"]
    print(f"people fingerprint: {fingerprint}")
    print(f"{counts['tasks']} xref tasks, "
          f"{sum(len(t['candidates']) for t in tasks)} candidate slots; "
          f"candidates per subject {sorted(Counter(len(t['candidates']) for t in tasks).items())}")
    print("  " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items())))
    batches = pack(tasks, args.budget)
    print(f"  -> {len(batches)} batches at a budget of {args.budget} chars")
    if args.dry_run:
        for t in tasks[:5]:
            print(f"    {t['task_id']} «{(t['subject'].get('name_ar') or '')[:28]}» "
                  f"{t['pointer'][:60]} -> {[(c.get('name_ar') or '')[:24] for c in t['candidates']]}")
        return

    run_dir = os.path.join(args.out_dir, "runs", "xref-" + fingerprint.replace(":", "-"))
    batch_dir = os.path.join(run_dir, "batches")
    if os.path.isdir(batch_dir) and os.listdir(batch_dir):
        raise SystemExit(f"{batch_dir} already holds batches — runs are immutable")
    os.makedirs(batch_dir)
    os.makedirs(os.path.join(run_dir, "outputs"), exist_ok=True)
    manifest = {"budget": args.budget, "merge_fingerprint": f"people:{fingerprint}",
                "kind": "xref", "batches": []}
    for number, batch in enumerate(batches):
        name = f"pair_{number:04d}.json"
        path = os.path.join(batch_dir, name)
        write_json_atomic(path, {"kind": "pair", "batch": name,
                                 "merge_fingerprint": f"people:{fingerprint}", "tasks": batch})
        manifest["batches"].append({"batch": name, "kind": "pair", "tasks": len(batch),
                                    "profiles": sum(len(members_of(t)) for t in batch),
                                    "chars": os.path.getsize(path)})
    write_json_atomic(os.path.join(run_dir, "manifest.json"), manifest)
    members = sorted({p["person_id"] for t in tasks for p in members_of(t)})
    write_json_atomic(os.path.join(run_dir, "id_map.json"), {
        "merge_fingerprint": f"people:{fingerprint}", "method": "person_ids",
        "map": {str(int(pid[1:])): by_id[pid]["source_keys"] for pid in members},
        "seeds": {str(int(pid[1:])): by_id[pid]["anchor"] for pid in members}})
    write_json_atomic(os.path.join(run_dir, "candidates.json"), [
        {"task_id": t["task_id"], "pointer": t["pointer"], "pointing_entry": t["pointing_entry"],
         "people": [p["person_id"] for p in members_of(t)]} for t in tasks])
    print(f"\nwrote {len(batches)} batches -> {batch_dir}")


if __name__ == "__main__":
    main()
