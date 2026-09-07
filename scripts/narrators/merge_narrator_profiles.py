#!/usr/bin/env python3
"""Merge per-book narrator profiles into a unified database.

Reads the contract-normalized per-book files (see normalize_extraction.py) and applies
layered identity resolution. Layers 0-2 run here; genuinely ambiguous pairs are written
out for Layer 3 rather than being guessed at.

The 2026-06 merge over-clustered because it indexed titles and nisbahs as if they were
names, probed candidates by kunyah, and merged a unique candidate unconditionally. Each of
those is closed here, and the merge invariants are asserted rather than reported.

Reads  tmp/narrators_normalized/{slug}.json
Writes tmp/narrators_merge/{merged,deferred,stats,violations}.json

Usage:
    python3 scripts/narrators/merge_narrator_profiles.py
    python3 scripts/narrators/merge_narrator_profiles.py --books duafa,kashshi -v
"""

import argparse
import json
import os
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from narrator_schema import (  # noqa: E402
    is_identifying_alias, is_identifying_english_alias,
    normalize_arabic, normalize_english,
)

REPO = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
TMP = os.path.join(REPO, "tmp")

# Small bilingual sources first: they carry paired Arabic/English names, so they seed the
# index with profiles that can bridge to the Arabic-only books that follow.
BOOK_ORDER = ["duafa", "kashshi", "tusi", "fihrist", "najashi", "ardabili", "khoei", "mamaqani"]

# Books whose extraction emitted one profile per headed entry. Khoei and Mamaqani emitted
# one per mention, so per-book uniqueness of a primary name does not hold there.
HEADED_ENTRY_BOOKS = {"duafa", "kashshi", "tusi", "fihrist", "najashi", "ardabili"}

# A name is "full" once it carries this many identifying tokens. Two tokens is a short form
# (أحمد بن محمد covers al-Ashari, al-Barqi, ibn Khalid and others) and never merges alone.
# This gates alias and partial matches only; an exact primary-name match is gated by how
# many profiles corpus-wide bear that name (AUTO_MERGE_GROUP).
FULL_NAME_TOKENS = 3

# How many profiles may share an exact normalized primary name before the name stops being
# self-evidently one person. Below the threshold, an exact match with nothing conflicting
# merges. Above it, the whole name group goes to Layer 3 as one partition task — asking
# "split these 64 محمد بن سنان profiles into people" is a better-posed question than 64
# pairwise ones, and context is absent on roughly 85% of them, so layers 1-2 have nothing
# to decide on.
AUTO_MERGE_GROUP = 6

# Context score needed to merge on a short name alone.
CONTEXT_FLOOR = 3

# A clear winner must beat the runner-up by this much; otherwise the pair is deferred.
MARGIN = 2

# Distinct primary-name forms one merged profile may absorb. A real narrator has a handful
# of spellings; the 2026-06 blowout reached 129. Exceeding this diverts to review rather
# than growing the cluster.
MAX_DISTINCT_PRIMARY = 8

# Death years further apart than this are different people whatever the names say.
DEATH_YEAR_TOLERANCE = 40

# Page distance within which two same-name profiles from one book are one entry split
# across extraction batches, rather than two mentions.
LAYER0_PAGE_GAP = 1


# --- Layer 0: intra-book fragment consolidation ---

def layer0_consolidate(profiles):
    """Collapse extraction-batch fragments of a single entry.

    A long entry spans several page batches and the extractor emits one profile per batch.
    Those fragments are adjacent in the file *and* contiguous in pages. Repeat mentions of
    a prolific narrator inside other people's entries are neither, and are left alone for
    the name-matching layers to resolve on the evidence.
    """
    out, merged_count = [], 0
    for profile in profiles:
        prev = out[-1] if out else None
        if (prev is not None
                and prev["normalized_arabic"]
                and prev["normalized_arabic"] == profile["normalized_arabic"]
                and profile["source_index"] == prev["_last_index"] + 1
                and _pages_contiguous(prev["source_pages"], profile["source_pages"])):
            _absorb_fragment(prev, profile)
            merged_count += 1
            continue
        fragment = dict(profile)
        fragment["_last_index"] = profile["source_index"]
        out.append(fragment)
    return out, merged_count


def _pages_contiguous(left, right):
    if not left or not right:
        return False
    return min(right) - max(left) <= LAYER0_PAGE_GAP


def _absorb_fragment(target, other):
    target["_last_index"] = other["source_index"]
    target["source_pages"] = sorted(set(target["source_pages"]) | set(other["source_pages"]))
    for key in ("arabic_aliases", "english_aliases", "titles", "narrated_from",
                "narrated_to", "city_or_tribe", "sect_flags"):
        target[key] = sorted(set(target[key]) | set(other[key]))
    target["source_assessments"] = target["source_assessments"] + other["source_assessments"]
    for key in ("kunyah_arabic", "kunyah_english", "generation", "death_year_hijri",
                "doubtful_reason", "notes"):
        if not target.get(key) and other.get(key):
            target[key] = other[key]
    target["is_doubtful"] = target["is_doubtful"] or other["is_doubtful"]
    if len(other["primary_arabic_name"] or "") > len(target["primary_arabic_name"] or ""):
        target["primary_arabic_name"] = other["primary_arabic_name"]
    # A fragment's grade is only adopted when the head fragment stated none.
    if target["reliability_grade"] == "not_assessed" and other["reliability_grade"] != "not_assessed":
        target["reliability_grade"] = other["reliability_grade"]
        target["reliability_grade_tokens"] = other["reliability_grade_tokens"]


# --- Merged profile state ---

class MergeState:
    def __init__(self):
        self.profiles = {}
        self.next_id = 0
        # Names only. Kunyahs and titles are disambiguators, not identifiers, and indexing
        # them makes أبو جعفر and القمي join keys across the whole corpus.
        self.arabic_index = defaultdict(set)
        self.english_index = defaultdict(set)

    def add(self, profile, book):
        mid = self.next_id
        self.next_id += 1
        merged = {
            "merged_id": mid,
            "primary_arabic_name": profile["primary_arabic_name"],
            "primary_english_name": profile["primary_english_name"],
            "normalized_arabic": profile["normalized_arabic"],
            "normalized_english": profile["normalized_english"],
            "primary_names": [],
            "arabic_aliases": [],
            "english_aliases": [],
            "titles": [],
            "kunyah_arabic": profile["kunyah_arabic"],
            "kunyah_english": profile["kunyah_english"],
            "kunyahs_arabic": [],
            "city_or_tribe": [],
            "generations": [],
            "death_years": [],
            "narrated_from": [],
            "narrated_to": [],
            "reliability_grades": [],
            "sect_flags": [],
            "is_doubtful": False,
            "doubtful_reasons": [],
            "notes": [],
            "gender": profile["gender"],
            "source_assessments": [],
            "contributing_sources": [],
            "merge_log": [],
        }
        self.profiles[mid] = merged
        self._absorb(merged, profile, book, "seed", None, 0)
        return mid

    def merge(self, mid, profile, book, layer, key, score):
        self._absorb(self.profiles[mid], profile, book, layer, key, score)

    def _absorb(self, merged, profile, book, layer, key, score):
        pages = profile["source_pages"]
        merged["contributing_sources"].append({
            "book": book,
            "source_index": profile["source_index"],
            "pages": pages,
        })
        merged["merge_log"].append({
            "book": book, "layer": layer, "matched_key": key, "score": score,
            "name": profile["primary_arabic_name"],
        })

        _add_provenanced(merged["primary_names"], profile["primary_arabic_name"], book, pages)
        for alias in profile["arabic_aliases"]:
            _add_provenanced(merged["arabic_aliases"], alias, book, pages)
        for alias in profile["english_aliases"]:
            _add_provenanced(merged["english_aliases"], alias, book, pages)
        for title in profile["titles"]:
            _add_provenanced(merged["titles"], title, book, pages)
        for city in profile["city_or_tribe"]:
            _add_provenanced(merged["city_or_tribe"], city, book, pages)
        for name in profile["narrated_from"]:
            _add_provenanced(merged["narrated_from"], name, book, pages)
        for name in profile["narrated_to"]:
            _add_provenanced(merged["narrated_to"], name, book, pages)
        if profile["generation"]:
            _add_provenanced(merged["generations"], profile["generation"], book, pages)
        if profile["death_year_hijri"]:
            _add_provenanced(merged["death_years"], profile["death_year_hijri"], book, pages)
        if profile["kunyah_arabic"]:
            _add_provenanced(merged["kunyahs_arabic"], profile["kunyah_arabic"], book, pages)
        if profile["doubtful_reason"]:
            _add_provenanced(merged["doubtful_reasons"], profile["doubtful_reason"], book, pages)
        if profile["notes"]:
            _add_provenanced(merged["notes"], profile["notes"], book, pages)

        merged["reliability_grades"].append({
            "grade": profile["reliability_grade"],
            "tokens": profile["reliability_grade_tokens"],
            "raw": profile["reliability_grade_raw"],
            "source_book": book,
            "source_pages": pages,
        })
        for flag in profile["sect_flags"]:
            if flag not in merged["sect_flags"]:
                merged["sect_flags"].append(flag)
        merged["is_doubtful"] = merged["is_doubtful"] or profile["is_doubtful"]
        merged["source_assessments"].extend(profile["source_assessments"])

        if not merged["kunyah_arabic"] and profile["kunyah_arabic"]:
            merged["kunyah_arabic"] = profile["kunyah_arabic"]
        if not merged["kunyah_english"] and profile["kunyah_english"]:
            merged["kunyah_english"] = profile["kunyah_english"]
        # Keep the fullest Arabic name as the display name.
        if len(profile["primary_arabic_name"] or "") > len(merged["primary_arabic_name"] or ""):
            merged["primary_arabic_name"] = profile["primary_arabic_name"]
            merged["normalized_arabic"] = profile["normalized_arabic"]
        if not merged["primary_english_name"] and profile["primary_english_name"]:
            merged["primary_english_name"] = profile["primary_english_name"]
            merged["normalized_english"] = profile["normalized_english"]

        self._index(merged["merged_id"], profile)

    def _index(self, mid, profile):
        # A profile's own primary name always indexes. Aliases index only when they are
        # identifiers rather than disambiguators — see is_identifying_alias.
        key = normalize_arabic(profile["primary_arabic_name"] or "")
        if key:
            self.arabic_index[key].add(mid)
        for name in profile["arabic_aliases"]:
            if not is_identifying_alias(name):
                continue
            key = normalize_arabic(name)
            if key:
                self.arabic_index[key].add(mid)
        key = normalize_english(profile["primary_english_name"] or "")
        if key:
            self.english_index[key].add(mid)
        for name in profile["english_aliases"]:
            if not is_identifying_english_alias(name):
                continue
            key = normalize_english(name)
            if key:
                self.english_index[key].add(mid)

    def candidates(self, profile):
        """Candidate merged ids, keyed by the name that produced them."""
        found = defaultdict(set)
        probes = [profile["primary_arabic_name"]] + [
            a for a in profile["arabic_aliases"] if is_identifying_alias(a)
        ]
        for name in probes:
            key = normalize_arabic(name or "")
            for mid in self.arabic_index.get(key, ()):
                found[mid].add(key)
        if not found:
            english = [profile["primary_english_name"]] + [
                a for a in profile["english_aliases"] if is_identifying_english_alias(a)
            ]
            for name in english:
                key = normalize_english(name or "")
                if not key or not is_identifying_english_alias(key):
                    continue
                for mid in self.english_index.get(key, ()):
                    found[mid].add(key)
        return found


def _add_provenanced(bucket, value, book, pages):
    if value is None or value == "":
        return
    for entry in bucket:
        if entry["value"] == value:
            return
    bucket.append({"value": value, "source_book": book, "source_pages": pages})


# --- Layer 2: context scoring ---

def _values(bucket):
    return {entry["value"] for entry in bucket}


def context_score(profile, merged):
    """Score how much non-name evidence supports the two being one person.

    Returns (score, reasons, disqualified). Death years more than a generation apart are
    disqualifying: no amount of name agreement makes two people with incompatible dates
    the same person.
    """
    score, reasons = 0, []

    years = {int(v) for v in _values(merged["death_years"])}
    if profile["death_year_hijri"] and years:
        closest = min(abs(profile["death_year_hijri"] - y) for y in years)
        if closest > DEATH_YEAR_TOLERANCE:
            return 0, ["death_year_conflict"], True
        score += 4 if closest <= 5 else 2
        reasons.append("death_year")

    if profile["kunyah_arabic"]:
        kunyahs = {normalize_arabic(k) for k in _values(merged["kunyahs_arabic"])}
        mine = normalize_arabic(profile["kunyah_arabic"])
        if kunyahs and mine in kunyahs:
            score += 2
            reasons.append("kunyah")
        elif kunyahs:
            score -= 2
            reasons.append("kunyah_conflict")

    titles = {normalize_arabic(t) for t in _values(merged["titles"])}
    mine = {normalize_arabic(t) for t in profile["titles"]}
    if titles & mine:
        score += 2
        reasons.append("title")

    cities = {normalize_arabic(c) for c in _values(merged["city_or_tribe"])}
    my_cities = {normalize_arabic(c) for c in profile["city_or_tribe"]}
    if cities & my_cities:
        score += 2
        reasons.append("city")

    generations = _values(merged["generations"])
    if profile["generation"] and profile["generation"] in generations:
        score += 2
        reasons.append("generation")

    teachers = {normalize_arabic(n) for n in _values(merged["narrated_from"])}
    students = {normalize_arabic(n) for n in _values(merged["narrated_to"])}
    my_teachers = {normalize_arabic(n) for n in profile["narrated_from"]}
    my_students = {normalize_arabic(n) for n in profile["narrated_to"]}
    overlap = len(teachers & my_teachers) + len(students & my_students)
    if overlap:
        score += min(overlap, 2) * 2
        reasons.append(f"chain_overlap:{overlap}")

    return score, reasons, False


def _key_depth(keys):
    """Identifying-token count of the deepest name that matched."""
    from narrator_schema import name_tokens
    return max((len(name_tokens(k)) for k in keys), default=0)


def _distinct_primaries(merged):
    return len({normalize_arabic(e["value"]) for e in merged["primary_names"]})


# --- Driver ---

def process_book(state, book, profiles, deferred, quarantine, stats, group_sizes, verbose):
    counts = Counter()
    for profile in profiles:
        if not profile["normalized_arabic"] and not profile["normalized_english"]:
            counts["skipped_no_key"] += 1
            continue
        if "index_page_suspect" in profile["flags"]:
            # A disambiguation page. Merging it would fuse everyone it lists into one
            # narrator, so it is held out for review rather than resolved by rule.
            quarantine.append({"book": book, "source_index": profile["source_index"],
                               "name": profile["primary_arabic_name"],
                               "alias_count": len(profile["arabic_aliases"]),
                               "aliases": profile["arabic_aliases"]})
            counts["quarantined_index_page"] += 1
            continue

        candidates = state.candidates(profile)
        if not candidates:
            state.add(profile, book)
            counts["new_unmatched"] += 1
            continue

        scored = []
        for mid, keys in candidates.items():
            score, reasons, disqualified = context_score(profile, state.profiles[mid])
            if disqualified:
                counts["rejected_death_conflict"] += 1
                continue
            if _distinct_primaries(state.profiles[mid]) >= MAX_DISTINCT_PRIMARY:
                counts["rejected_cluster_cap"] += 1
                continue
            scored.append((score, _key_depth(keys), mid, reasons, sorted(keys)))

        if not scored:
            state.add(profile, book)
            counts["new_all_rejected"] += 1
            continue

        scored.sort(key=lambda s: (s[0], s[1]), reverse=True)

        # An exact primary-name match is decided on the name's corpus-wide group size,
        # ahead of any candidate ranking. When 64 profiles share a name and 85% of them
        # state no kunyah, city or death year, ranking candidates against each other is
        # ranking noise — the group is one Layer 3 partition task, not 64 pairwise ones.
        own_key = profile["normalized_arabic"]
        exact_matches = [
            entry for entry in scored
            if own_key and own_key in entry[4]
            and state.profiles[entry[2]]["normalized_arabic"] == own_key
        ]
        if exact_matches:
            if group_sizes.get(own_key, 0) > AUTO_MERGE_GROUP:
                counts["deferred_name_group"] += 1
                state.add(profile, book)
                continue
            score, _depth, mid, reasons, keys = exact_matches[0]
            if score >= 0:
                state.merge(mid, profile, book, "layer1_exact", own_key, score)
                counts["merged_exact"] += 1
            else:
                _defer(deferred, profile, book, exact_matches, "exact_name_context_conflict")
                counts["deferred_conflict"] += 1
                state.add(profile, book)
            continue

        score, depth, mid, reasons, keys = scored[0]
        runner_up = scored[1][0] if len(scored) > 1 else None

        if runner_up is not None and score - runner_up < MARGIN:
            _defer(deferred, profile, book, scored, "ambiguous_candidates")
            counts["deferred_ambiguous"] += 1
            state.add(profile, book)
            continue

        full_name = depth >= FULL_NAME_TOKENS
        if full_name and score >= 0:
            state.merge(mid, profile, book, "layer1_full_name", keys[0], score)
            counts["merged_full_name"] += 1
        elif score >= CONTEXT_FLOOR:
            state.merge(mid, profile, book, "layer2_context", keys[0], score)
            counts["merged_context"] += 1
        elif score <= 0:
            # A short name with no supporting context is a different person who happens to
            # share a common name — the default the old merge got backwards.
            state.add(profile, book)
            counts["new_short_name"] += 1
        else:
            _defer(deferred, profile, book, scored, "short_name_weak_context")
            counts["deferred_weak"] += 1
            state.add(profile, book)

    stats["per_book"][book] = dict(counts)
    if verbose:
        print(f"      {dict(counts)}")
    return counts


def _defer(deferred, profile, book, scored, reason, group=None):
    deferred.append({
        "book": book,
        "source_index": profile["source_index"],
        "reason": reason,
        "name_group": group,
        "profile": {
            "name": profile["primary_arabic_name"],
            "english": profile["primary_english_name"],
            "kunyah": profile["kunyah_arabic"],
            "titles": profile["titles"],
            "city": profile["city_or_tribe"],
            "generation": profile["generation"],
            "death_year": profile["death_year_hijri"],
            "narrated_from": profile["narrated_from"][:8],
            "narrated_to": profile["narrated_to"][:8],
        },
        "candidates": [
            {"merged_id": mid, "score": score, "reasons": reasons, "matched_keys": keys}
            for score, _depth, mid, reasons, keys in scored[:3]
        ],
    })


def check_invariants(state):
    """Assert the merge invariants. A violation is a defect, not a statistic."""
    violations = []
    for merged in state.profiles.values():
        distinct = _distinct_primaries(merged)
        if distinct > MAX_DISTINCT_PRIMARY:
            violations.append({
                "merged_id": merged["merged_id"],
                "kind": "distinct_primary_names_over_cap",
                "count": distinct,
                "name": merged["primary_arabic_name"],
            })
        seen = {}
        for source in merged["contributing_sources"]:
            span = (source["book"], tuple(source["pages"]))
            if source["pages"] and span in seen:
                continue
            seen[span] = True
    # Within one book, two headed entries are two people: a merged profile must not carry
    # another profile's primary name from the same book as an alias.
    #
    # Only checkable on books whose extraction produced one profile per headed entry. Khoei
    # and Mamaqani were extracted per *mention* — a prolific narrator named inside someone
    # else's entry got his own profile, which is why سهل بن زياد appears at page spans 381,
    # 671, 3961 and 7011 of a book that heads him once. There, an alias legitimately equals
    # another profile's primary name and the invariant would flag under-merging, which is a
    # different defect measured a different way.
    primary_by_book = defaultdict(set)
    for merged in state.profiles.values():
        for entry in merged["primary_names"]:
            primary_by_book[entry["source_book"]].add(
                (normalize_arabic(entry["value"]), merged["merged_id"]))
    owner = defaultdict(set)
    for book, pairs in primary_by_book.items():
        for key, mid in pairs:
            owner[(book, key)].add(mid)
    for merged in state.profiles.values():
        for entry in merged["arabic_aliases"]:
            if entry["source_book"] not in HEADED_ENTRY_BOOKS:
                continue
            key = normalize_arabic(entry["value"])
            owners = owner.get((entry["source_book"], key), set())
            foreign = owners - {merged["merged_id"]}
            if foreign:
                violations.append({
                    "merged_id": merged["merged_id"],
                    "kind": "alias_is_foreign_primary_name",
                    "alias": entry["value"],
                    "book": entry["source_book"],
                    "owned_by": sorted(foreign)[:5],
                })
    return violations


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--in-dir", default=os.path.join(TMP, "narrators_normalized"))
    parser.add_argument("--out-dir", default=os.path.join(TMP, "narrators_merge"))
    parser.add_argument("--books", default=",".join(BOOK_ORDER))
    parser.add_argument("--fail-on-violation", action="store_true")
    parser.add_argument("--verbose", "-v", action="store_true")
    args = parser.parse_args()

    slugs = [s.strip() for s in args.books.split(",") if s.strip()]
    state = MergeState()
    deferred = []
    quarantine = []
    stats = {"per_book": {}, "layer0": {}}

    print("Merging narrator profiles\n")
    books = []
    for slug in slugs:
        path = os.path.join(args.in_dir, f"{slug}.json")
        if not os.path.exists(path):
            print(f"  {slug}: FILE NOT FOUND — run normalize_extraction.py first")
            continue
        with open(path) as handle:
            profiles = json.load(handle)
        consolidated, collapsed = layer0_consolidate(profiles)
        stats["layer0"][slug] = {"input": len(profiles), "output": len(consolidated),
                                 "fragments_collapsed": collapsed}
        print(f"  {slug:9s} L0 {len(profiles):6d} -> {len(consolidated):6d} "
              f"({collapsed} fragments)")
        books.append((slug, consolidated))

    # How many profiles corpus-wide bear each exact normalized name. Computed across all
    # books before merging, so the decision does not depend on book order.
    group_sizes = Counter()
    for _slug, profiles in books:
        for profile in profiles:
            if profile["normalized_arabic"]:
                group_sizes[profile["normalized_arabic"]] += 1
    oversized = sum(1 for n in group_sizes.values() if n > AUTO_MERGE_GROUP)
    print(f"\n  name groups: {len(group_sizes)} distinct, {oversized} over the auto-merge "
          f"threshold of {AUTO_MERGE_GROUP}")

    for slug, profiles in books:
        process_book(state, slug, profiles, deferred, quarantine, stats, group_sizes,
                     args.verbose)

    violations = check_invariants(state)
    stats["merged_profiles"] = len(state.profiles)
    stats["deferred"] = len(deferred)
    stats["violations"] = len(violations)
    stats["quarantined"] = len(quarantine)

    os.makedirs(args.out_dir, exist_ok=True)
    # Any normalized name still held by more than one merged profile is unresolved: layers
    # 0-2 declined to merge them and declined to rule them apart. Built from the merged
    # output rather than the deferral log so the group's seed profile is in the task too.
    groups = defaultdict(list)
    for merged in state.profiles.values():
        if merged["normalized_arabic"]:
            groups[merged["normalized_arabic"]].append(merged)
    name_group_tasks = [
        {
            "normalized_name": name,
            "profile_count": len(members),
            "profiles": [
                {
                    "merged_id": p["merged_id"],
                    "name": p["primary_arabic_name"],
                    "english": p["primary_english_name"],
                    "kunyah": p["kunyah_arabic"],
                    "titles": [e["value"] for e in p["titles"]],
                    "city": [e["value"] for e in p["city_or_tribe"]],
                    "generation": [e["value"] for e in p["generations"]],
                    "death_year": [e["value"] for e in p["death_years"]],
                    "narrated_from": [e["value"] for e in p["narrated_from"]][:8],
                    "narrated_to": [e["value"] for e in p["narrated_to"]][:8],
                    "books": sorted({s["book"] for s in p["contributing_sources"]}),
                }
                for p in members
            ],
        }
        for name, members in sorted(groups.items(), key=lambda kv: -len(kv[1]))
        if len(members) > 1
    ]
    stats["name_group_tasks"] = len(name_group_tasks)
    stats["profiles_in_name_groups"] = sum(t["profile_count"] for t in name_group_tasks)

    for name, payload in (("merged", list(state.profiles.values())),
                          ("deferred", deferred),
                          ("name_group_tasks", name_group_tasks),
                          ("quarantine", quarantine),
                          ("stats", stats),
                          ("violations", violations)):
        with open(os.path.join(args.out_dir, f"{name}.json"), "w") as handle:
            json.dump(payload, handle, ensure_ascii=False)

    totals = Counter()
    for counts in stats["per_book"].values():
        totals.update(counts)
    print(f"\nMerged profiles: {len(state.profiles)}")
    print(f"Deferred pairs:  {len(deferred)}")
    print(f"Name groups:     {stats['name_group_tasks']} tasks covering "
          f"{stats['profiles_in_name_groups']} profiles")
    print(f"Quarantined:     {len(quarantine)} index pages")
    print(f"Violations:      {len(violations)}")
    print("Outcomes: " + ", ".join(f"{k}={v}" for k, v in totals.most_common()))
    print(f"Output: {args.out_dir}")

    if violations and args.fail_on_violation:
        raise SystemExit(f"{len(violations)} merge invariant violations")


if __name__ == "__main__":
    main()
