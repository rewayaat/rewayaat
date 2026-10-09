#!/usr/bin/env python3
"""Draw the stage 5 accuracy audit: a fixed random sample of the pipeline's work, for auditors.

Three questions, each answered by fresh sub-agents from the source entries alone:

  person  Is every entry of this person the same man? People of two or more entries, drawn at
          random within size bands (PERSON_BANDS), up to MAX_UNITS entries each, always with
          the anchor entry first. The share of people with no stray entry, weighted by each
          band's share of such people, is the figure publication waits on.
  pair    Are these two entries the same man? PER_STRATUM joins drawn at random from each
          method that makes joins (STRATA), among those whose two ends are still one person.
          Join precision per method is what sets each rule's acceptance threshold.
  recall  Is any of these people the same man as this one? People holding a main-book entry,
          and others, each beside up to MAX_CANDIDATES people sharing a name form. The share
          found to have a double measures how much splitting remains.

Auditors are not told which method made a join or what any agent said of it. Hidden among the
pairs are controls: one man's entries in two main books, which must read as the same, and
same-named men whose eras lie six Imams apart, which must read as different. An auditor who
fails the controls is not trusted, whatever the figures say.

The answer key — which stratum or control each item is, which decision or person it came from —
is written outside the run directory, so an auditor reading its batch cannot see it.
The sample is drawn with a fixed seed from a fixed build, so it can be drawn again exactly.

Reads  tmp/narrators_identity/{people,membership,build_stats}.json, decisions.jsonl,
       tmp/narrators_normalized/*.json
Writes tmp/narrators_audit/runs/audit-<count>-<sha16>/{batches,outputs,manifest.json}
       tmp/narrators_audit/keys/audit-<count>-<sha16>.json

Usage:
    python3 scripts/narrators/audit_sample.py --dry-run
    python3 scripts/narrators/audit_sample.py
"""

import argparse
from collections import Counter, defaultdict
import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_people import assemble, load_sources  # noqa: E402
from crossform_prepare import (MAIN_BOOKS, marks, name_forms, people_fingerprint,  # noqa: E402
                               truncations)
from identity import anchor_rank, fragment_members, in_force, load_record, write_json_atomic  # noqa: E402
from l3_prepare import DEFAULT_BUDGET, evidence, pack  # noqa: E402
from narrator_schema import generation_distance, imam_generations  # noqa: E402
from split_prepare import eras  # noqa: E402

REPO = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
TMP = os.path.join(REPO, "tmp")

SEED = 20260914
STRATA = ["layer0_fragments", "layer1_exact", "layer1_full_name", "layer2_context", "xref_pair",
          "second_opinion_pair",
          "layer3_group", "layer3_pair", "crossform_group", "attach_pair",
          # stage 6's joins between entries: a phase 1 profile to the entry it was read from, and
          # a Khoei entry to the other books' entries he cites or shares a unique name with
          "mention_is_entry", "khoei_cites_tusi", "entry_books_tusi", "entry_books_jami",
          # and the entry-level joins of 2026-09-19: Khoei's and Jami' al-Ruwat's quotations of the
          # older books, Khoei's cross-references between his own entries, mentions tied by name
          "khoei_quotes_najashi", "khoei_quotes_fihrist", "khoei_quotes_kashshi",
          "jami_quotes_najashi", "jami_quotes_fihrist", "khoei_points_equals", "khoei_points_see",
          "khoei_points_adjacent", "mention_names_one_entry",
          # Jami' al-Ruwat's own joins: its copies of al-Tusi's listings, and its entries placed
          # among Khoei's homonyms by the words they share
          "entry_books_jami_tusi", "entry_books_jami_text"]
PER_STRATUM = 100
PERSON_BANDS = {"2-3": (2, 3), "4-10": (4, 10), "11+": (11, 10 ** 6)}
PER_BAND = 40
MAX_UNITS = 10
RECALL_GROUPS = {"main-entry": 60, "other": 60}
MAX_CANDIDATES = 6
MAX_FORM_OWNERS = 30
CONTROLS = {"positive": 30, "negative": 30}
# One man's entries in two main books: the twelve most-cited narrators, by permanent id.
POSITIVE_PEOPLE = ["n000515", "n000488", "n000907", "n000912", "n001232", "n000151",
                   "n000220", "n000034", "n000866", "n000549", "n000349"]


def generations_apart(a, b):
    """What the two sides say about when each man lived, and how far apart that is.

    Auditors read a citation such as «رجال الشيخ (24)» as proof of identity and miss the chapter
    it stands in: three of audit 3's negative controls were called the same man although one side
    is a Companion of the Prophet and the other a companion of al-Sadiq. The sources' own words
    are still what settles it — this only says plainly what they already say.
    """
    first = frozenset().union(*(imam_generations(g) for g in a.get("generation") or [])) \
        if a.get("generation") else frozenset()
    second = frozenset().union(*(imam_generations(g) for g in b.get("generation") or [])) \
        if b.get("generation") else frozenset()
    apart = generation_distance(first, second)
    if not first or not second or apart is None:
        return None
    return {"A": sorted(first), "B": sorted(second), "imams_between": apart,
            "note": "the ordinal of each Imam the entry names (0 the Prophet, 6 al-Sadiq, "
                    "12 the Mahdi). Several Imams apart with nothing else in common is two men."}


def shown(keys, profiles, label):
    """An entry (or a person) as an auditor sees it: the sources' own words, nothing else."""
    profile = evidence(dict(assemble(sorted(keys, key=anchor_rank), profiles), merged_id=0))
    profile.pop("merged_id", None)
    return {"id": label, "source_keys": sorted(keys, key=anchor_rank), **profile}


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--identity-dir", default=os.path.join(TMP, "narrators_identity"))
    parser.add_argument("--normalized-dir", default=os.path.join(TMP, "narrators_normalized"))
    parser.add_argument("--out-dir", default=os.path.join(TMP, "narrators_audit"))
    parser.add_argument("--budget", type=int, default=DEFAULT_BUDGET)
    parser.add_argument("--seed", type=int, default=SEED,
                        help="a fresh draw for a re-audit (the first audit used the default)")
    parser.add_argument("--strata", default="",
                        help="draw pair items from these methods only (comma list); the controls "
                             "are drawn whatever it says, so the auditors are still checked")
    parser.add_argument("--kinds", default="pair,person,recall",
                        help="which questions to draw: pair, person, recall")
    parser.add_argument("--cohort", action="append", default=[], metavar="NAME=PATH",
                        help="a named list of person ids to judge as person items, beside the "
                             "random bands: a file of one id per line. A cohort is a census of "
                             "a chosen group, not a sample of a population, so it is scored on "
                             "its own and left out of the weighted gate.")
    parser.add_argument("--dry-run", action="store_true", help="count items, write nothing")
    args = parser.parse_args()
    kinds = {k.strip() for k in args.kinds.split(",") if k.strip()}

    def load(name):
        with open(os.path.join(args.identity_dir, name)) as handle:
            return json.load(handle)

    people, membership, stats = load("people.json"), load("membership.json"), load("build_stats.json")
    by_id = {p["person_id"]: p for p in people}
    fingerprint = people_fingerprint(people)
    rng = random.Random(args.seed)
    profiles = load_sources(args.normalized_dir)
    members = fragment_members(args.normalized_dir)
    head_of = {k: h for h, keys in members.items() for k in keys}

    def entry(key):
        """The key's entry as it stands inside its person: its fragment's pages there."""
        person = by_id[membership[key]]
        return [k for k in person["source_keys"] if head_of.get(k, k) == head_of.get(key, key)]

    def units(person):
        grouped = defaultdict(list)
        for k in person["source_keys"]:
            grouped[head_of.get(k, k)].append(k)
        anchor_head = head_of.get(person["anchor"], person["anchor"])
        return [grouped[anchor_head]] + [grouped[h] for h in sorted(grouped, key=anchor_rank)
                                         if h != anchor_head]

    items, key = [], {}
    counts = Counter()

    # --- pair items: joins, per method -------------------------------------------------------
    force = in_force(load_record(os.path.join(args.identity_dir, "decisions.jsonl")),
                     stats["rules_run"])
    pool = defaultdict(list)
    for d in force:
        if d["method"] not in STRATA:
            continue
        if d["kind"] == "same":
            candidates = [tuple(d["sources"][:2])]
        elif d["kind"] == "partition":
            candidates = []
            for group in d["groups"]:
                inside = [k for k in group if k in membership]
                for i in range(len(inside)):
                    for j in range(i + 1, len(inside)):
                        candidates.append((inside[i], inside[j]))
        else:
            continue
        candidates = [(a, b) for a, b in candidates
                      if a in membership and membership.get(a) == membership.get(b)]
        if candidates:
            pool[d["method"]].append((d["decision_id"], candidates))

    pairs = []
    # Two decisions can join the same two entries, and a control pair can also be drawn as an
    # ordinary sample. Such an item is shown twice, answered twice and counted twice, so each
    # pair of entries is drawn once however many decisions or strata reach it.
    seen_pairs = set()

    def once(ua, ub):
        key = frozenset((tuple(sorted(ua)), tuple(sorted(ub))))
        if key in seen_pairs:
            return False
        seen_pairs.add(key)
        return True

    wanted = [s.strip() for s in args.strata.split(",") if s.strip()] or STRATA
    for stratum in (wanted if "pair" in kinds else []):
        drawn = 0
        for decision_id, candidates in rng.sample(pool[stratum], len(pool[stratum])):
            a, b = rng.choice(candidates)
            if stratum == "layer0_fragments":
                ua, ub = [a], [b]
            else:
                ua, ub = entry(a), entry(b)
                if set(ua) == set(ub):
                    continue
            if not once(ua, ub):
                counts["pair already drawn"] += 1
                continue
            pairs.append(({"stratum": stratum, "decision_id": decision_id,
                           "person_id": membership[a]}, ua, ub))
            drawn += 1
            if drawn == PER_STRATUM:
                break
        counts[f"pair {stratum}"] = drawn

    # positive controls: one famous man's entries in two different main books
    positive = []
    for pid in POSITIVE_PEOPLE:
        person = by_id.get(pid)
        if not person:
            continue
        main_units = [u for u in units(person) if u[0].split(":")[0] in MAIN_BOOKS]
        for i in range(len(main_units)):
            for j in range(i + 1, len(main_units)):
                if main_units[i][0].split(":")[0] != main_units[j][0].split(":")[0]:
                    positive.append((pid, main_units[i], main_units[j]))
    if "pair" not in kinds:
        positive = []
    drawn = 0
    for pid, ua, ub in rng.sample(positive, len(positive)):
        if drawn == CONTROLS["positive"]:
            break
        if not once(ua, ub):
            continue
        pairs.append(({"control": "positive", "person_id": pid}, ua, ub))
        drawn += 1
    counts["control positive"] = drawn

    # negative controls: same primary name, different people, eras six Imams apart
    by_name = defaultdict(list)
    for person in people:
        found = eras(person)
        if found:
            by_name[person["normalized_arabic"]].append((person, min(found), max(found)))
    negative = []
    for group in by_name.values():
        for i in range(len(group)):
            for j in range(i + 1, len(group)):
                (p, lo_p, hi_p), (q, lo_q, hi_q) = group[i], group[j]
                if hi_p + 6 <= lo_q or hi_q + 6 <= lo_p:
                    negative.append((p, q))
    if "pair" not in kinds:
        negative = []
    drawn = 0
    for p, q in rng.sample(negative, len(negative)):
        if drawn == CONTROLS["negative"]:
            break
        ua, ub = units(p)[0], units(q)[0]
        if not once(ua, ub):
            continue
        pairs.append(({"control": "negative", "person_ids": [p["person_id"], q["person_id"]]},
                      ua, ub))
        drawn += 1
    counts["control negative"] = drawn

    rng.shuffle(pairs)
    for n, (meta, ua, ub) in enumerate(pairs):
        item_id = f"p{n:04d}"
        a, b = shown(ua, profiles, "A"), shown(ub, profiles, "B")
        gap = generations_apart(a, b)
        items.append({"kind": "pair", "item_id": item_id,
                      **({"imams_apart": gap} if gap else {}),
                      "entries": [a, b]})
        key[item_id] = {"kind": "pair", "A": ua, "B": ub, **meta}

    # --- person items: is every entry one man? ----------------------------------------------
    banded = defaultdict(list)
    for person in people:
        # A component of mentions alone is not a person and is not published: people precision
        # is measured over those who hold an entry (build_people.py's holds_entry).
        if person.get("holds_entry") is False:
            continue
        n = len(units(person))
        for band, (lo, hi) in PERSON_BANDS.items():
            if lo <= n <= hi:
                banded[band].append(person)
    population = {band: len(ps) for band, ps in banded.items()}
    person_items = []
    for band in (PERSON_BANDS if "person" in kinds else []):
        for person in rng.sample(banded[band], min(PER_BAND, len(banded[band]))):
            all_units = units(person)
            chosen = [all_units[0]] + rng.sample(all_units[1:], min(MAX_UNITS - 1,
                                                                     len(all_units) - 1))
            person_items.append((band, person, chosen, len(all_units)))
        counts[f"person {band}"] = min(PER_BAND, len(banded[band]))

    # The named cohorts, judged whole rather than sampled. Every member is asked about, in
    # the same shape and shuffled in among the random items, so an auditor cannot tell he is
    # being given the hard ones.
    cohorts = []
    for spec in args.cohort:
        name, _, path = spec.partition("=")
        with open(path) as handle:
            wanted = [line.strip() for line in handle if line.strip()]
        drawn = missing = 0
        for pid in wanted:
            person = by_id.get(pid)
            if person is None or person.get("holds_entry") is False:
                missing += 1
                continue
            all_units = units(person)
            if len(all_units) < 2:
                # one entry cannot contain a stray entry; there is nothing to judge
                missing += 1
                continue
            chosen = [all_units[0]] + rng.sample(all_units[1:], min(MAX_UNITS - 1,
                                                                   len(all_units) - 1))
            person_items.append((name, person, chosen, len(all_units)))
            drawn += 1
        cohorts.append(name)
        population[name] = drawn
        counts[f"person {name}"] = drawn
        if missing:
            counts[f"person {name} (no second entry, left out)"] = missing
    rng.shuffle(person_items)
    for n, (band, person, chosen, total) in enumerate(person_items):
        item_id = f"u{n:04d}"
        labels = [f"U{i + 1}" for i in range(len(chosen))]
        items.append({"kind": "person", "item_id": item_id, "entries_in_person": total,
                      "entries": [shown(u, profiles, lab) for u, lab in zip(chosen, labels)]})
        key[item_id] = {"kind": "person", "band": band, "person_id": person["person_id"],
                        "cohort": band in cohorts,
                        "units": dict(zip(labels, chosen)), "entries_in_person": total}

    # --- recall items: does this man have a double? -----------------------------------------
    owners = defaultdict(set)
    forms = {}
    for person in people:
        forms[person["person_id"]] = name_forms(person)
        for form in forms[person["person_id"]]:
            for t in truncations(form):
                owners[t].add(person["person_id"])

    def thin(person):
        kunyahs, titles = marks(person)
        return len(person["source_keys"]) == 1 and not person["reliability_grades"] \
            and not kunyahs and not titles

    groups = {"main-entry": [p for p in people if any(k.split(":")[0] in MAIN_BOOKS
                                                       for k in p["source_keys"])],
              "other": [p for p in people if not any(k.split(":")[0] in MAIN_BOOKS
                                                     for k in p["source_keys"]) and not thin(p)]}
    recall_items = []
    for group, n in (RECALL_GROUPS.items() if "recall" in kinds else []):
        for person in rng.sample(groups[group], n):
            kunyahs, titles = marks(person)
            scored = Counter()
            for form in forms[person["person_id"]]:
                holders = owners.get(form, set())
                if len(holders) > MAX_FORM_OWNERS:
                    continue
                for other in holders - {person["person_id"]}:
                    their_kunyahs, their_titles = marks(by_id[other])
                    scored[other] += 1 + 2 * len(kunyahs & their_kunyahs) + len(titles & their_titles)
            candidates = [pid for pid, _ in scored.most_common(MAX_CANDIDATES)]
            recall_items.append((group, person, candidates))
        counts[f"recall {group}"] = n
    rng.shuffle(recall_items)
    for n, (group, person, candidates) in enumerate(recall_items):
        item_id = f"r{n:04d}"
        labels = [f"C{i + 1}" for i in range(len(candidates))]
        key[item_id] = {"kind": "recall", "group": group, "person_id": person["person_id"],
                        "candidates": dict(zip(labels, candidates))}
        if not candidates:
            counts["recall without candidates"] += 1
            key[item_id]["no_candidates"] = True
            continue
        items.append({"kind": "recall", "item_id": item_id,
                      "subject": shown(person["source_keys"], profiles, "S"),
                      "candidates": [shown(by_id[c]["source_keys"], profiles, lab)
                                     for c, lab in zip(candidates, labels)]})

    by_kind = defaultdict(list)
    for item in items:
        by_kind[item["kind"]].append(item)
    batches = [(kind, batch) for kind in ("pair", "person", "recall")
               for batch in pack(by_kind[kind], args.budget)]
    print(f"people fingerprint: {fingerprint}")
    print("  " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items())))
    print(f"  {len(items)} items -> {len(batches)} batches "
          f"({', '.join(f'{k} {sum(1 for kk, _ in batches if kk == k)}' for k in by_kind)})")
    if args.dry_run:
        return

    name = "audit-" + fingerprint.replace(":", "-") + ("" if args.seed == SEED else f"-s{args.seed}")
    run_dir = os.path.join(args.out_dir, "runs", name)
    batch_dir = os.path.join(run_dir, "batches")
    if os.path.isdir(batch_dir) and os.listdir(batch_dir):
        raise SystemExit(f"{batch_dir} already holds batches — runs are immutable")
    os.makedirs(batch_dir)
    os.makedirs(os.path.join(run_dir, "outputs"), exist_ok=True)
    os.makedirs(os.path.join(args.out_dir, "keys"), exist_ok=True)
    manifest = {"people_fingerprint": fingerprint, "seed": args.seed, "kinds": sorted(kinds), "rules_run": stats["rules_run"],
                "person_band_population": population, "cohorts": cohorts, "batches": []}
    numbering = Counter()
    for kind, batch in batches:
        batch_name = f"{kind}_{numbering[kind]:04d}.json"
        numbering[kind] += 1
        path = os.path.join(batch_dir, batch_name)
        write_json_atomic(path, {"kind": kind, "batch": batch_name, "items": batch})
        manifest["batches"].append({"batch": batch_name, "kind": kind, "items": len(batch),
                                    "chars": os.path.getsize(path)})
    write_json_atomic(os.path.join(run_dir, "manifest.json"), manifest)
    write_json_atomic(os.path.join(args.out_dir, "keys", f"{name}.json"),
                      {"people_fingerprint": fingerprint, "seed": args.seed, "kinds": sorted(kinds),
                       "person_band_population": population, "cohorts": cohorts, "items": key})
    print(f"\nwrote {len(batches)} batches -> {batch_dir}")


if __name__ == "__main__":
    main()
