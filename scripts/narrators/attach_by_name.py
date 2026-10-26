#!/usr/bin/env python3
"""Attach a mention to the one man its name names — Mamaqani's profiles above all.

People are built from entries (build_people.py): a man's entries, and the phase 1 profiles read
from his own entry (attach_mentions.py). That leaves out every profile of Mamaqani's Tanqih,
because phase 1 read it page by page like Khoei and no machine text of it has been found to
tell its entries from its mentions. Mamaqani's verdicts are worth keeping, and so are Khoei's
mentions of a man inside other entries, when it is certain whom they mean.

A name is certain when every entry carrying it belongs to one person: the books between them
head one man by that name, so a mention of it can only be him. It must give at least a name and
a father — «بشر» alone names no one — and where the mention and his entries both name Imams they
must share one. Measured blind on 100 attachments with hidden controls (both passed): 89 the same
man, 2 different, 9 too thin to tell; the two misses led to the Imam test and to attaching to the
entry that carries the name rather than to the man's anchor. Such a mention is attached to that man's anchor entry as a reviewer `same`
(`mention_names_one_entry`), which build_people.py treats as an attachment.

Widening it to names that run on from one another — a mention «خالد بن مهران الحذاء» to an entry
«خالد بن مهران البجلي الكوفي» — was measured on 2026-09-20 and rejected: 72 of 100 the same man
against 89 for names that agree throughout, and 75 of 100 when only whole-token run-ons were
taken (batch attach-by-name-s20260923, both controls 10/10). Two men share a father's name and
part at the nisbah, al-Muzani against al-Ghifari, and the corpus holds thousands of such pairs.

The rule is measured before it is used: --sample draws attachments at random, with hidden
controls, into a batch an auditor reads blind (audit_agent_prompt.md), its key kept outside the
run, and --score reads the answers back.

Usage:
    python3 scripts/narrators/attach_by_name.py                    # count, write nothing
    python3 scripts/narrators/attach_by_name.py --sample 100       # a measurement batch
    python3 scripts/narrators/attach_by_name.py --score RUN_DIR
    python3 scripts/narrators/attach_by_name.py --write
"""

import argparse
from collections import Counter, defaultdict
import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from audit_sample import load_sources, shown  # noqa: E402
from build_people import is_entry, not_entries  # noqa: E402
from identity import append_record, load_record, make_decision, write_json_atomic  # noqa: E402
from narrator_schema import generation_distance, imam_generations, name_tokens, normalize_arabic  # noqa: E402

TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "tmp")
IDENTITY = os.path.join(TMP, "narrators_identity")
NORMALIZED = os.path.join(TMP, "narrators_normalized")
RUNS = os.path.join(TMP, "narrators_audit", "runs")
KEYS = os.path.join(TMP, "narrators_audit", "keys")
METHOD = "mention_names_one_entry"
SEED = 20260921


def imams_of(profile):
    return frozenset(imam_generations(profile.get("generation")))


def attachments(profiles, people, membership, decisions):
    """(mention key, anchor entry key, person id) for every mention its name ties to one man."""
    headless = not_entries(TMP)
    entry_keys = {k for k, (_, p) in profiles.items() if is_entry(k, p, headless)}
    attached = {k for d in decisions if d["kind"] == "same"
                and d["method"] in ("mention_is_entry", METHOD) for k in d["sources"]}
    by_name, entry_of = defaultdict(set), defaultdict(list)
    for k in entry_keys:
        name = normalize_arabic(profiles[k][1]["primary_arabic_name"])
        if k in membership:
            by_name[name].add(membership[k])
            entry_of[name].append(k)
    person = {p["person_id"]: p for p in people}
    person_imams = defaultdict(set)
    for k in entry_keys:
        if k in membership:
            person_imams[membership[k]] |= imams_of(profiles[k][1])

    counts, out = Counter(), []
    for k, (book, prof) in profiles.items():
        if k in entry_keys or k in attached or book not in ("khoei", "mamaqani"):
            continue
        name = normalize_arabic(prof["primary_arabic_name"])
        if len(name_tokens(name)) < 2:
            counts["a name without a father's name"] += 1
            continue
        owners = by_name.get(name, set())
        if not owners:
            counts["no entry carries the name"] += 1
            continue
        if len(owners) > 1:
            counts["the name's entries are several men"] += 1
            continue
        pid = next(iter(owners))
        # Where the mention and the man's entries both name Imams, they must share one: «علي بن
        # أبي حمزة», al-Rida's companion, is not al-Bata'ini of al-Sadiq's and al-Kazim's.
        apart = generation_distance(imams_of(prof), frozenset(person_imams[pid]))
        if apart is not None and apart >= 1:
            counts["placed in another generation"] += 1
            continue
        # the entry that carries the name, not the man's anchor, which may head him differently
        target = sorted(entry_of[name])[0]
        out.append((k, target, pid))
        counts[f"attached ({book})"] += 1
    return out, counts, entry_keys


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--sample", type=int, default=0)
    parser.add_argument("--seed", type=int, default=SEED, help="a fresh draw, for a changed rule")
    parser.add_argument("--score", default=None)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()

    if args.score:
        return score(args.score)
    profiles = load_sources(NORMALIZED)
    with open(os.path.join(IDENTITY, "people.json")) as handle:
        people = json.load(handle)
    with open(os.path.join(IDENTITY, "membership.json")) as handle:
        membership = json.load(handle)
    decisions = load_record(os.path.join(IDENTITY, "decisions.jsonl"))
    links, counts, entry_keys = attachments(profiles, people, membership, decisions)
    for label, n in sorted(counts.items()):
        print(f"  {n:6d}  {label}")

    if args.sample:
        rng = random.Random(args.seed)
        drawn = rng.sample(links, min(args.sample, len(links)))
        items, key = [], {}
        for n, (m, anchor, _) in enumerate(drawn):
            iid = f"a{n:04d}"
            items.append({"kind": "pair", "item_id": iid,
                          "entries": [shown([m], profiles, "A"), shown([anchor], profiles, "B")]})
            key[iid] = {"kind": "pair", "stratum": METHOD, "A": [m], "B": [anchor]}
        # controls: positive, a phase 1 profile beside the entry it was read from (measured 100%);
        # negative, a mention beside an entry-holder of the same first name and another father
        proven = [d["sources"] for d in decisions if d["method"] == "mention_is_entry"]
        for n, (m, e) in enumerate(rng.sample(proven, 10)):
            iid = f"c{n:04d}"
            items.append({"kind": "pair", "item_id": iid,
                          "entries": [shown([m], profiles, "A"), shown([e], profiles, "B")]})
            key[iid] = {"kind": "pair", "control": "positive", "A": [m], "B": [e]}
        by_first = defaultdict(list)
        for k in entry_keys:
            toks = name_tokens(normalize_arabic(profiles[k][1]["primary_arabic_name"]))
            if len(toks) >= 2:
                by_first[toks[0]].append((toks[1], k))
        made = 0
        for m, _, _ in rng.sample(links, len(links)):
            toks = name_tokens(normalize_arabic(profiles[m][1]["primary_arabic_name"]))
            others = [k for father, k in by_first.get(toks[0], []) if father != toks[1]]
            if not others:
                continue
            iid = f"c{10 + made:04d}"
            other = rng.choice(others)
            items.append({"kind": "pair", "item_id": iid,
                          "entries": [shown([m], profiles, "A"), shown([other], profiles, "B")]})
            key[iid] = {"kind": "pair", "control": "negative", "A": [m], "B": [other]}
            made += 1
            if made == 10:
                break
        rng.shuffle(items)
        name = f"attach-by-name-s{args.seed}"
        os.makedirs(os.path.join(RUNS, name, "batches"), exist_ok=True)
        os.makedirs(os.path.join(RUNS, name, "outputs"), exist_ok=True)
        os.makedirs(KEYS, exist_ok=True)
        write_json_atomic(os.path.join(RUNS, name, "batches", "pair_0000.json"),
                          {"kind": "pair", "batch": "pair_0000.json", "items": items}, indent=1)
        write_json_atomic(os.path.join(KEYS, f"{name}.json"), {"items": key}, indent=1)
        print(f"\nmeasurement batch: {len(items)} items -> {os.path.join(RUNS, name)}")

    if args.write:
        on_file = {d["decision_id"] for d in decisions}
        new = [d for d in (
            make_decision("same", sources=[m, anchor], method=METHOD, actor="reviewer",
                          confidence="high",
                          evidence={"why": "every entry carrying this name belongs to one man"},
                          origin={"run": "stage6:attach_by_name"})
            for m, anchor, _ in links) if d["decision_id"] not in on_file]
        append_record(os.path.join(IDENTITY, "decisions.jsonl"), new)
        print(f"\nrecorded {len(new)} attachments")


def score(run_dir):
    name = os.path.basename(os.path.normpath(run_dir))
    with open(os.path.join(KEYS, f"{name}.json")) as handle:
        key = json.load(handle)["items"]
    with open(os.path.join(run_dir, "outputs", "pair_0000.json")) as handle:
        answers = {a["item_id"]: a for a in json.load(handle)["answers"]}
    tally = defaultdict(Counter)
    for iid, meta in key.items():
        verdict = answers.get(iid, {}).get("verdict", "unanswered")
        tally[meta.get("control") or "attachment"][verdict] += 1
    for kind, c in tally.items():
        n = sum(c.values())
        right = c["same"] if kind in ("attachment", "positive") else c["different"]
        print(f"  {kind:10s} {right}/{n} = {right / n:.1%}   {dict(c)}")


if __name__ == "__main__":
    main()
