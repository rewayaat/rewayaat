#!/usr/bin/env python3
"""Permanent narrator identity: source keys, the decision record, and people.

Strategy and rationale: docs/proposals/narrator-system.md, Part III. Identity is kept as a
record of decisions over sources that never change; people are derived from the record, and
every product is derived from the people. This module holds what every stage shares.

Source keys
    `book:index` — a Rijal entry's position in its Phase 1 extraction file
    (tmp/narrators_book_{book}.json). That file is never rewritten, so the key never
    changes. Decisions are keyed on source keys, never on the ids a merge run assigns, which
    is what lets a decision outlive the run that produced it.

The decision record
    An append-only JSONL file, one decision per line, identified by a hash of its content,
    so recording the same decision twice is a no-op.

      kind       meaning                                         effect on people
      same       `sources` are one person                        enforced
      partition  each of `groups` is one person (a Layer 3       enforced within groups;
                 group task); groups are NOT asserted distinct   nothing across them
      not_same   `groups` were judged not shown to be the same   review signal only
      distinct   `groups` are positively different people        enforced against `same`
      exclude    `sources` are not narrators                     removed
      retract    `targets` (decision ids) are withdrawn          they no longer count

    `not_same` is deliberately weak. The agent brief says to default to separate, so an
    agent's "not the same" often means "not shown to be the same", and a profile an agent
    left on its own may be one it could not place. Enforcing either would block legitimate
    merges later. Only `distinct` — from reviewers and the split pass, with evidence of
    difference — can stop a `same`, and only one made by an actor of lower or equal rank.

    Agent decisions bind each profile's *seed* source — the source the profile was built
    from, whose quotation the agent was always shown — not every source in it. An agent
    judged a merged profile as a whole; which sources the rules had merged into it was the
    rules' claim, not the agent's. Binding only the seed leaves those internal merges to the
    rule decisions of whichever merge run is in force, so a later run can undo a bad one.

People
    The connected components of the `same` and `partition` decisions in force, each with a
    permanent identifier from an append-only registry. An identifier is never reused or
    dropped: when people merge, the absorbed identifiers redirect; when a person splits,
    the part holding the identifier's anchor keeps it and the rest are minted anew.
"""

import datetime
import hashlib
import json
import os
from collections import defaultdict

ACTOR_RANK = {"reviewer": 3, "agent": 2, "rule": 1}
KINDS = {"same", "partition", "not_same", "distinct", "exclude", "retract"}

# Books extracted one profile per headed entry come first, so an anchor is an entry heading
# wherever the person has one — the source least likely ever to be split away from him.
BOOK_PRIORITY = ["najashi", "fihrist", "kashshi", "tusi", "duafa", "ardabili", "khoei", "mamaqani"]


def source_key(book, index):
    return f"{book}:{int(index)}"


def split_key(key):
    book, index = key.rsplit(":", 1)
    return book, int(index)


def anchor_rank(key):
    book, index = split_key(key)
    rank = BOOK_PRIORITY.index(book) if book in BOOK_PRIORITY else len(BOOK_PRIORITY)
    return rank, index


# --- Layer 0 membership and merge-id translation ---

def fragment_members(normalized_dir):
    """Map each Layer 0 head key to every source key its profile covers.

    Replays the merge's own layer0_consolidate over the normalized files, so the grouping is
    exactly the one the merge worked from.
    """
    from merge_narrator_profiles import BOOK_ORDER, layer0_consolidate
    members = {}
    for book in BOOK_ORDER:
        path = os.path.join(normalized_dir, f"{book}.json")
        if not os.path.exists(path):
            continue
        with open(path) as handle:
            profiles = json.load(handle)
        consolidated, _ = layer0_consolidate(profiles)
        for profile in consolidated:
            indices = profile.get("fragment_indices") or [profile["source_index"]]
            members[source_key(book, profile["source_index"])] = [
                source_key(book, i) for i in indices]
    return members


def merged_id_map(merged, normalized_dir):
    """merged_id -> the source keys that merged profile covers.

    Newer merges record each source's Layer 0 fragments in `contributing_sources`. Older ones
    are expanded by replaying Layer 0, which is only valid while the normalized files are
    unchanged since the merge ran — a head the replay cannot find means they have changed,
    and translating would be unsafe.
    """
    replay = any("fragment_indices" not in s
                 for p in merged for s in p["contributing_sources"])
    members = fragment_members(normalized_dir) if replay else {}
    mapping, missing = {}, []
    for profile in merged:
        keys = []
        for source in profile["contributing_sources"]:
            if "fragment_indices" in source:
                keys += [source_key(source["book"], i) for i in source["fragment_indices"]]
                continue
            head = source_key(source["book"], source["source_index"])
            if head in members:
                keys += members[head]
            else:
                missing.append(head)
        mapping[profile["merged_id"]] = sorted(set(keys), key=anchor_rank)
    if missing:
        raise SystemExit(
            f"{len(missing)} merge sources are not Layer 0 heads of the current normalized "
            f"files (e.g. {missing[:3]}): they have changed since this merge ran, so its ids "
            f"cannot be translated to source keys safely.")
    return mapping, ("layer0_replay" if replay else "contributing_sources")


def merged_seeds(merged):
    """merged_id -> the source the merged profile was built from (its first contributor)."""
    return {p["merged_id"]: source_key(p["contributing_sources"][0]["book"],
                                       p["contributing_sources"][0]["source_index"])
            for p in merged}


# --- The decision record ---

def decision_id(decision):
    """A hash of the decision's content, so the same decision recorded twice is one entry."""
    core = {k: v for k, v in decision.items() if k not in ("decision_id", "recorded")}
    blob = json.dumps(core, ensure_ascii=False, sort_keys=True)
    return "d-" + hashlib.sha256(blob.encode("utf-8")).hexdigest()[:16]


def make_decision(kind, *, method, actor, sources=None, groups=None, targets=None,
                  confidence=None, status="applied", evidence=None, origin=None):
    if kind not in KINDS:
        raise ValueError(f"unknown decision kind {kind!r}")
    if actor not in ACTOR_RANK:
        raise ValueError(f"unknown actor {actor!r}")
    decision = {"kind": kind, "method": method, "actor": actor, "confidence": confidence,
                "status": status, "evidence": evidence or {}, "origin": origin or {}}
    if sources is not None:
        decision["sources"] = sorted(set(sources), key=anchor_rank)
    if groups is not None:
        cleaned = [sorted(set(g), key=anchor_rank) for g in groups if g]
        decision["groups"] = sorted(cleaned, key=lambda g: anchor_rank(g[0]))
    if targets is not None:
        decision["targets"] = sorted(set(targets))
    decision["decision_id"] = decision_id(decision)
    return decision


def load_record(path):
    decisions = []
    if os.path.exists(path):
        with open(path) as handle:
            for line in handle:
                if line.strip():
                    decisions.append(json.loads(line))
    return decisions


def append_record(path, decisions, recorded=None):
    """Append the decisions not already on file. Returns (added, already_present)."""
    on_file = {d["decision_id"] for d in load_record(path)}
    stamp = recorded or datetime.date.today().isoformat()
    added = present = 0
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "a") as handle:
        for decision in decisions:
            if decision["decision_id"] in on_file:
                present += 1
                continue
            handle.write(json.dumps(dict(decision, recorded=stamp), ensure_ascii=False) + "\n")
            on_file.add(decision["decision_id"])
            added += 1
    return added, present


def _counts(decision, rules_run):
    return decision["actor"] != "rule" or decision.get("origin", {}).get("run") == rules_run


def live(record):
    """Every decision not withdrawn by a `retract`, excluding the retractions themselves."""
    withdrawn = {t for d in record if d["kind"] == "retract" for t in d.get("targets", [])}
    return [d for d in record if d["kind"] != "retract" and d["decision_id"] not in withdrawn]


def in_force(record, rules_run):
    """Decisions that shape people: applied, and — for rules — from the chosen merge run.

    Rule decisions are artefacts of one merge run and are replaced wholesale when the merge
    is re-run. Agent and reviewer decisions are judgments about sources and stand across runs.
    """
    return [d for d in live(record) if d.get("status") == "applied" and _counts(d, rules_run)]


def considered(record, rules_run):
    """As in_force, but regardless of status — for review signals, a low-confidence call counts."""
    return [d for d in live(record) if _counts(d, rules_run)]


# --- People ---

class UnionFind:
    def __init__(self, items=()):
        self.parent = {item: item for item in items}

    def find(self, item):
        parent = self.parent
        parent.setdefault(item, item)
        while parent[item] != item:
            parent[item] = parent[parent[item]]
            item = parent[item]
        return item

    def union(self, a, b):
        ra, rb = self.find(a), self.find(b)
        if ra == rb:
            return ra
        keep, drop = (ra, rb) if anchor_rank(ra) <= anchor_rank(rb) else (rb, ra)
        self.parent[drop] = keep
        return keep


def build_components(universe, decisions):
    """Apply the `same` and `partition` decisions over `universe`, strongest actor first.

    A union that would put both sides of a `distinct` decision into one person is refused if
    the distinct decision's actor ranks at least as high as the union's; a stronger actor's
    union goes through and is reported as an override. Returns (uf, refused, overridden).
    """
    uf = UnionFind(universe)
    sides = defaultdict(dict)          # root -> {distinct decision id: set of sides present}
    distinct_rank = {}
    for d in decisions:
        if d["kind"] != "distinct":
            continue
        distinct_rank[d["decision_id"]] = ACTOR_RANK[d["actor"]]
        for i, group in enumerate(d["groups"]):
            for key in group:
                if key in universe:
                    sides[uf.find(key)].setdefault(d["decision_id"], set()).add(i)

    proposals = [(ACTOR_RANK[d["actor"]], d) for d in decisions
                 if d["kind"] in ("same", "partition")]
    proposals.sort(key=lambda p: -p[0])
    refused, overridden = [], []
    for rank, d in proposals:
        groups = [d["sources"]] if d["kind"] == "same" else d["groups"]
        for group in groups:
            keys = [k for k in group if k in universe]
            for key in keys[1:]:
                a, b = uf.find(keys[0]), uf.find(key)
                if a == b:
                    continue
                clash = [cid for cid in sides[a].keys() & sides[b].keys()
                         if len(sides[a][cid]) == 1 and len(sides[b][cid]) == 1
                         and sides[a][cid] != sides[b][cid]]
                blocking = [cid for cid in clash if distinct_rank[cid] >= rank]
                if blocking:
                    refused.append({"decision_id": d["decision_id"], "blocked_by": blocking,
                                    "between": [keys[0], key]})
                    continue
                if clash:
                    overridden.append({"decision_id": d["decision_id"], "overrides": clash})
                root = uf.union(a, b)
                other = b if root == a else a
                for cid, present in sides.pop(other, {}).items():
                    sides[root].setdefault(cid, set()).update(present)
    return uf, refused, overridden


def components(uf, universe):
    grouped = defaultdict(list)
    for key in universe:
        grouped[uf.find(key)].append(key)
    return [sorted(keys, key=anchor_rank) for keys in grouped.values()]


# --- Permanent identifiers ---

def load_registry(path):
    return load_record(path)


def registry_state(events):
    state = {}
    for event in events:
        if event["event"] == "mint":
            state[event["id"]] = {"anchor": event["anchor"], "status": "active"}
        elif event["event"] == "redirect":
            state[event["id"]].update(status="redirect", to=event["to"])
        elif event["event"] == "retire":
            state[event["id"]].update(status="retired")
    return state


def resolve(state, person_id):
    """Follow redirects to the identifier now in use."""
    seen = set()
    while state.get(person_id, {}).get("status") == "redirect" and person_id not in seen:
        seen.add(person_id)
        person_id = state[person_id]["to"]
    return person_id


def assign_ids(comps, events, previous_membership, today=None):
    """Give each component a permanent identifier. Returns (ids, new registry events).

    A component keeps the identifier whose anchor it holds; holding several means people
    merged, and the younger identifiers redirect to the oldest. A component holding none is
    minted a new identifier anchored on its first source, noting any identifiers its sources
    were under before — a split. Identifiers whose anchor has left the narrator sources
    altogether are retired, never reused.
    """
    today = today or datetime.date.today().isoformat()
    state = registry_state(events)
    by_anchor = {s["anchor"]: pid for pid, s in state.items() if s["status"] == "active"}
    next_number = max((int(pid[1:]) for pid in state), default=0) + 1
    ids, new_events = [None] * len(comps), []
    for i in sorted(range(len(comps)), key=lambda i: anchor_rank(comps[i][0])):
        keys = comps[i]
        held = sorted({by_anchor[k] for k in keys if k in by_anchor}, key=lambda p: int(p[1:]))
        if held:
            ids[i] = held[0]
            for other in held[1:]:
                new_events.append({"event": "redirect", "id": other, "to": held[0],
                                   "reason": "merged", "date": today})
            continue
        person_id = f"n{next_number:06d}"
        next_number += 1
        event = {"event": "mint", "id": person_id, "anchor": keys[0], "date": today}
        earlier = sorted({previous_membership[k] for k in keys if k in previous_membership})
        if earlier:
            event["split_from"] = earlier
        new_events.append(event)
        ids[i] = person_id
    present = {k for comp in comps for k in comp}
    for person_id, s in state.items():
        if s["status"] == "active" and s["anchor"] not in present:
            new_events.append({"event": "retire", "id": person_id,
                               "reason": "anchor is no longer a narrator source", "date": today})
    return ids, new_events
