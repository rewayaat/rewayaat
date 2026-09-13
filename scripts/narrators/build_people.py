#!/usr/bin/env python3
"""Derive people, with permanent identifiers, from the decision record.

Takes the rule decisions of one merge run plus every agent and reviewer decision (see
identity.py for what each kind means), computes people as the connected components of the
`same` and `partition` decisions in force, gives each a permanent identifier from the
registry, and assembles each person's profile from his source entries. Nothing here is
edited by hand: run it again and the people follow from the record.

`not_same` decisions do not shape people. Where one now falls inside a single person it is
reported as a review signal — two sources an agent or rule judged not shown to be the same
man, that other decisions have joined.

Reads  tmp/narrators_identity/{decisions.jsonl,person_ids.jsonl,membership.json},
       tmp/narrators_normalized/*.json, tmp/narrators_merge/merged.json (for its fingerprint)
Writes tmp/narrators_identity/{people.json,membership.json,review_signals.json,build_stats.json}
       and appends to person_ids.jsonl

Usage:
    python3 scripts/narrators/build_people.py
    python3 scripts/narrators/build_people.py --verify-against \\
        tmp/narrators_l3/runs/28687-a993d061519aaa64/merged_final.json
"""

import argparse
import datetime
import json
import os
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from identity import (  # noqa: E402
    append_lines, append_record, assign_ids, write_json_atomic, build_components, components, considered, fragment_members,
    in_force, load_record, load_registry, registry_state, source_key,
)
from l3_prepare import merge_fingerprint  # noqa: E402
from merge_narrator_profiles import BOOK_ORDER, MergeState  # noqa: E402

REPO = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
TMP = os.path.join(REPO, "tmp")


def load_sources(normalized_dir):
    profiles = {}
    for book in BOOK_ORDER:
        path = os.path.join(normalized_dir, f"{book}.json")
        if not os.path.exists(path):
            continue
        with open(path) as handle:
            for profile in json.load(handle):
                profiles[source_key(book, profile["source_index"])] = (book, profile)
    return profiles


def assemble(keys, profiles):
    """A person's profile, built from his source entries in anchor order."""
    state = MergeState()
    book, profile = profiles[keys[0]]
    mid = state.add(profile, book)
    for key in keys[1:]:
        book, profile = profiles[key]
        state.merge(mid, profile, book, "derived", None, 0)
    person = state.profiles[mid]
    person.pop("merge_log", None)
    person.pop("merged_id", None)
    return person


def verify(comps, reference_path, normalized_dir):
    with open(reference_path) as handle:
        reference = json.load(handle)
    members = fragment_members(normalized_dir)
    expected = set()
    for profile in reference:
        keys = set()
        for source in profile["contributing_sources"]:
            head = source_key(source["book"], source["source_index"])
            keys.update(members.get(head, [head]))
        expected.add(frozenset(keys))
    built = {frozenset(comp) for comp in comps}
    result = {"reference": reference_path, "reference_people": len(expected),
              "built_people": len(built), "identical": len(built & expected),
              "built_only": len(built - expected), "reference_only": len(expected - built)}
    result["match"] = result["built_only"] == 0 and result["reference_only"] == 0
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--identity-dir", default=os.path.join(TMP, "narrators_identity"))
    parser.add_argument("--normalized-dir", default=os.path.join(TMP, "narrators_normalized"))
    parser.add_argument("--merge-dir", default=os.path.join(TMP, "narrators_merge"))
    parser.add_argument("--rules-run", help="merge whose rule decisions to use "
                        "(default: the merge in --merge-dir)")
    parser.add_argument("--verify-against", help="merged_final.json whose people to reproduce")
    args = parser.parse_args()

    record_path = os.path.join(args.identity_dir, "decisions.jsonl")
    registry_path = os.path.join(args.identity_dir, "person_ids.jsonl")
    membership_path = os.path.join(args.identity_dir, "membership.json")

    if args.rules_run:
        rules_run = args.rules_run
    else:
        with open(os.path.join(args.merge_dir, "merged.json")) as handle:
            rules_run = f"merge:{merge_fingerprint(json.load(handle))}"

    record = load_record(record_path)
    force = in_force(record, rules_run)
    if not any(d["actor"] == "rule" for d in force):
        raise SystemExit(f"no rule decisions on file for {rules_run} — run record_decisions.py first")
    print(f"record: {len(record)} decisions, {len(force)} in force (rules from {rules_run})")

    profiles = load_sources(args.normalized_dir)
    excluded = {k for d in force if d["kind"] == "exclude" for k in d["sources"]}
    universe = set(profiles) - excluded

    uf, refused, overridden = build_components(universe, force)
    comps = components(uf, universe)

    signals = []
    for d in considered(record, rules_run):
        if d["kind"] != "not_same":
            continue
        roots = [{uf.find(k) for k in group if k in universe} for group in d["groups"]]
        if len(roots) == 2 and roots[0] & roots[1]:
            signals.append(d)

    events = load_registry(registry_path)
    previous = {}
    if os.path.exists(membership_path):
        with open(membership_path) as handle:
            previous = json.load(handle)
    today = datetime.date.today().isoformat()
    ids, new_events = assign_ids(comps, events, previous, today)
    if new_events:
        append_lines(registry_path, new_events)
    state = registry_state(events + new_events)

    print(f"assembling {len(comps)} people...")
    people = []
    membership = {}
    for keys, person_id in zip(comps, ids):
        person = assemble(keys, profiles)
        people.append({"person_id": person_id, "anchor": keys[0], "source_keys": keys, **person})
        for key in keys:
            membership[key] = person_id
    people.sort(key=lambda p: p["person_id"])

    review = [{"decision_id": d["decision_id"], "method": d["method"], "actor": d["actor"],
               "confidence": d.get("confidence"), "person_id": membership[d["groups"][0][0]]
               if d["groups"][0][0] in membership else None,
               "evidence": d.get("evidence", {}), "origin": d.get("origin", {})}
              for d in signals]

    event_counts = Counter(e["event"] for e in new_events)
    stats = {
        "built": today,
        "rules_run": rules_run,
        "decisions_on_file": len(record),
        "decisions_retracted": len({t for d in record if d["kind"] == "retract"
                                    for t in d.get("targets", [])}),
        "decisions_in_force": dict(Counter(f"{d['actor']}/{d['kind']}/{d['method']}" for d in force)),
        "sources": len(profiles),
        "excluded": len(excluded),
        "people": len(comps),
        "people_on_more_than_one_book": sum(
            1 for comp in comps if len({k.split(':')[0] for k in comp}) > 1),
        "refused_unions": dict(Counter(f"{r['method']} blocked by {b['kind']}"
                                       for r in refused for b in r["blocked_by"][:1])),
        "overridden_constraints": dict(Counter(f"{o['method']} over {c['kind']}"
                                               for o in overridden for c in o["overrides"][:1])),
        "review_signals": len(review),
        "registry": {"minted": event_counts.get("mint", 0),
                     "redirected": event_counts.get("redirect", 0),
                     "retired": event_counts.get("retire", 0),
                     "active_total": sum(1 for s in state.values() if s["status"] == "active")},
    }
    if args.verify_against:
        stats["verification"] = verify(comps, args.verify_against, args.normalized_dir)

    os.makedirs(args.identity_dir, exist_ok=True)
    for name, payload in (("people", people), ("membership", membership),
                          ("review_signals", review), ("build_stats", stats)):
        write_json_atomic(os.path.join(args.identity_dir, f"{name}.json"), payload,
                          indent=1 if name == "build_stats" else None)
    if refused or overridden:
        write_json_atomic(os.path.join(args.identity_dir, "distinct_conflicts.json"),
                          {"refused": refused, "overridden": overridden})

    print(json.dumps(stats, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
