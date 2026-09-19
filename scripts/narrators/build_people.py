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
import glob
import json
import os
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from identity import (
    UnionFind,  # noqa: E402
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


# --- Which people hold an entry ---
#
# A person is a man some Rijal book gives an entry of his own. Phase 1 read Khoei and Mamaqani
# page by page and made a profile of every name on a page, so most of their profiles are names
# mentioned inside other men's entries: they belong to chains, for stage 8 to resolve, and a
# component made only of them is not a person. Khoei's own entries are now in the corpus, one
# profile each, marked `printed_entry`; attach_mentions.py joins the phase 1 profiles read from
# them. The main books were read entry by entry, save the ones repair_from_openiti.py found the
# printed text does not head.

MAIN_BOOKS = {"najashi", "fihrist", "kashshi", "tusi", "duafa", "ardabili"}


def not_entries(tmp_dir):
    """Main-book keys the printed text does not head (repair_from_openiti.py)."""
    keys = set()
    for path in glob.glob(os.path.join(tmp_dir, "narrators_sources", "*_not_an_entry.json")):
        with open(path) as handle:
            keys |= {item["source_key"] for item in json.load(handle)}
    return keys


def is_entry(key, profile, excluded_keys):
    if "printed_entry" in (profile or {}).get("flags", ()):
        return True
    return key.split(":")[0] in MAIN_BOOKS and key not in excluded_keys


# Methods that tie a phase 1 mention profile to the man it stands for. A profile read from his
# own entry is that entry, so every decision on it counts (ANCHORING); a mention attached because
# its name names one man is his only on that ground, so it joins him and nothing else — its older
# joins were made on the host entry's data and would bridge him to someone else.
ANCHORING = {"mention_is_entry"}
ATTACHMENTS = ANCHORING | {"mention_names_one_entry"}


def anchored(decisions, profiles, tmp_dir):
    """The decisions that may shape people: those between keys that stand for an entry.

    A key stands for an entry when it is one (is_entry), or when it is a phase 1 profile read
    from a man's own entry and attached to it. Any other phase 1 profile is a name mentioned
    inside someone else's entry, carrying that entry's teachers and students. Decisions made on
    such profiles stay on record for stage 8, which resolves chain mentions to people. They do
    not join people here, because a mention joined to two men bridges them: the stage 6 audit
    traced its stray entries to exactly that.
    """
    headless = not_entries(tmp_dir)
    anchors = {k for k, (_, prof) in profiles.items() if is_entry(k, prof, headless)}
    for d in decisions:
        if d["kind"] == "same" and d["method"] in ANCHORING:
            anchors.update(d["sources"])
    kept = []
    for d in decisions:
        if d["kind"] == "same":
            if d["method"] in ATTACHMENTS or all(k in anchors for k in d["sources"]):
                kept.append(d)
        elif d["kind"] == "partition":
            groups = [[k for k in g if k in anchors] for g in d["groups"]]
            groups = [g for g in groups if g]
            if groups:
                kept.append({**d, "groups": groups})
        else:
            kept.append(d)
    return kept, anchors


def attach_mention_groups(uf, universe, decisions, anchors):
    """Let the mentions the decisions join to one man join him; hold back those that bridge two.

    Mentions joined to each other form groups. A group whose decisions reach exactly one
    entry-holder joins him — that is what those decisions said, and a Khoei or Mamaqani mention
    usually carries a verdict worth keeping. A group reaching two entry-holders is the bridge the
    stage 6 audit traced its stray entries to, and joins neither. A group a separation keeps from
    its man stays apart. A group reaching none stays a mention for stage 8.
    """
    groups = UnionFind(k for k in universe if k not in anchors)
    links, apart = defaultdict(set), set()

    def members(d):
        return [d["sources"]] if d["kind"] == "same" else d["groups"]

    for d in decisions:
        if d["kind"] not in ("same", "partition"):
            continue
        for group in members(d):
            keys = [k for k in group if k in universe]
            loose = [k for k in keys if k not in anchors]
            fixed = [k for k in keys if k in anchors]
            for a, b in zip(loose, loose[1:]):
                groups.union(a, b)
            for k in loose:
                for f in fixed:
                    links[k].add(f)
    for d in decisions:
        if d["kind"] in ("not_same", "distinct"):
            sides = [[k for k in g if k in universe] for g in d.get("groups") or []]
            for i, left in enumerate(sides):
                for right in sides[i + 1:]:
                    for a in left:
                        for b in right:
                            apart.add((a, b))
                            apart.add((b, a))

    reach = defaultdict(set)
    for k, fixed in links.items():
        reach[groups.find(k)] |= {uf.find(f) for f in fixed}
    by_group = defaultdict(list)
    for k in universe:
        if k not in anchors:
            by_group[groups.find(k)].append(k)
    stats = Counter()
    for root, keys in by_group.items():
        targets = reach.get(root, set())
        if not targets:
            stats["groups joined to no entry-holder"] += 1
            continue
        if len(targets) > 1:
            stats["groups bridging two entry-holders: held back"] += 1
            continue
        target = next(iter(targets))
        if any((k, other) in apart for k in keys for other in _members_of(uf, target)):
            stats["groups a separation keeps from their man"] += 1
            continue
        for k in keys:
            uf.union(target, k)
        stats["groups joined to their one entry-holder"] += 1
    return dict(stats)


_MEMBER_CACHE = {}


def _members_of(uf, root):
    """The keys whose root is `root`, computed once per build."""
    if not _MEMBER_CACHE:
        for k in list(uf.parent):
            _MEMBER_CACHE.setdefault(uf.find(k), []).append(k)
    return _MEMBER_CACHE.get(root, [])


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
    parser.add_argument("--mentions", choices=("entries", "one-man", "all"), default="entries",
                        help="which mention profiles join people: only those attached to their own "
                             "entry (entries), also those whose decisions reach one entry-holder "
                             "(one-man), or every one, as builds before stage 6 did (all)")
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

    # A person is a man some book heads (stage 6): only entries, and mention profiles attached to
    # the entry they were read from, shape people unless asked otherwise.
    if args.mentions == "all":
        uf, refused, overridden = build_components(universe, force)
    else:
        anchor_decisions, anchors = anchored(force, profiles, os.path.join(args.identity_dir, ".."))
        uf, refused, overridden = build_components(universe, anchor_decisions)
        if args.mentions == "one-man":
            print(f"mentions: {attach_mention_groups(uf, universe, force, anchors)}")
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
    headless = not_entries(os.path.join(args.identity_dir, ".."))
    for keys, person_id in zip(comps, ids):
        person = assemble(keys, profiles)
        holds = any(is_entry(k, (profiles.get(k) or (None, None))[1], headless) for k in keys)
        people.append({"person_id": person_id, "anchor": keys[0], "source_keys": keys,
                       "holds_entry": holds, **person})
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
        "people_holding_an_entry": sum(1 for p in people if p["holds_entry"]),
        "mention_only": sum(1 for p in people if not p["holds_entry"]),
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
