#!/usr/bin/env python3
"""Measures how much of the chain ambiguity is the same man filed twice, and says which pairs.

Read-only. Changes no resolver rule, merges nothing, writes nothing but its own report files.

Three parts, all over the pipeline's own folding (`chain_resolve.key`) and its own link
universe (`chain_resolve.load_people` with the classical books):

1. Size the ambiguity: mention counts by outcome, and the names the `several` outcome costs most.
2. A duplicate detector. Every pair of records whose headings are equal, one the opening of the
   other, or one a word-subset of the other with the same first two words, gets separate pieces
   of evidence: death years, nasab depth (does the extension add a «بن X» generation, or only a
   nisba/laqab/kunya), shared teachers and students, Imam ordinals, books, and how many other
   records extend the same shorter name. There is deliberately no single score. Pairs fall into
   three buckets - clear double, clear distinct, undecidable - by rules stated in `bucket()`.
3. The `everywhere` conventions: what each rests on, and who else its name could reach.

What it found on 2026-10-18, so the next reader knows what to expect: of 10,484 pairs, 22
are clear doubles, 7,472 clear distinct and 2,990 undecidable. There is no large pile of
safe merges here. Most clear doubles are an Ardabilī-only copy of a classical-book record.

Read "clear distinct" as "do not merge on the name alone", not as proof of two men. The
depth veto cannot see a truncated filing: «علي بن أسباط» against «علي بن أسباط بن سالم»
lands in clear distinct and is probably one man, and so does «أحمد بن أبي عبد الله
البرقي» against «أحمد بن محمد بن خالد البرقي», because a kunya standing in for a father's
ism reads as a different ancestor. Only 351 of the 15,504 records carry a death year, so
that veto fires on about 28 pairs in 10,484.

Usage:
    nice -n 10 python3 scripts/narrators/chain_doubles.py --out-dir <dir>
"""

import argparse
from collections import Counter, defaultdict
from itertools import combinations
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import chain_resolve as cr  # noqa: E402

LINK = set(cr.LINK)
PRIMARY = {"tusi", "duafa", "najashi", "kashshi", "fihrist"}  # ardabili compiles from these
EVERYWHERE_WHY = "the books read this name as one man"
MBH = "محمد بن الحسن"


# --------------------------------------------------------------------------- names

# Headings in the index that are not names but chain clauses or editorial remarks lifted out of
# an entry («... عنه عن أبي عبد الله عليه السلام في», «هو الجاموراني وقد تقدم»). They would
# relate to everything, so a heading holding one of these words is not a name.
JUNK = {"عنه", "عن", "نسخه", "تقدم", "عليه", "عليهم", "السلام", "قال", "روي", "يروي", "حدثنا",
        "اسمه", "هو", "و", "قد", "في", "وقد", "واسم", "ويقال", "يقال", "اي", "ان"}


def clean(tokens):
    return bool(tokens) and not (set(tokens) & JUNK)


def headings_of(person):
    """The folded headings a record is filed under (display included), as token tuples."""
    out = set()
    for name in list(person.get("headings_ar") or []) + [person.get("display_ar") or ""]:
        folded = cr.key(name)
        if folded and folded not in cr.RELATIVE and clean(folded.split()):
            out.add(tuple(folded.split()))
    return out


def primary_of(person, heads):
    """The record's own full name: its display when that is a name, else its longest heading."""
    shown = tuple(cr.key(person.get("display_ar") or "").split())
    if clean(shown):
        return shown
    return max(heads, key=len) if heads else ()


def relate(x, y):
    """How two token tuples stand: (kind, shorter, longer, added generations) or None.

    «added generations» counts the «بن»/«ابن» links in what the longer name has beyond the
    shorter: zero means the extension is a nisba, laqab or kunya only.
    """
    if x == y:
        return ("equal", x, y, 0) if len(x) >= 2 else None
    short, long = (x, y) if len(x) <= len(y) else (y, x)
    if len(short) < 2 or len(short) == len(long):
        return None
    if long[:len(short)] == short:
        return ("prefix", short, long, sum(1 for t in long[len(short):] if t in LINK))
    if short[:2] == long[:2]:
        rest = Counter(long)
        rest.subtract(Counter(short))
        if all(v >= 0 for v in rest.values()):
            return ("subset", short, long, sum(1 for t in rest.elements() if t in LINK))
    return None


def relations(heads_a, heads_b):
    found = []
    for x in heads_a:
        for y in heads_b:
            rel = relate(x, y)
            if rel:
                found.append(rel)
    return found


# --------------------------------------------------------------------------- evidence

def death_years(person):
    return {d["value"] for d in person.get("death_years") or []
            if isinstance(d, dict) and isinstance(d.get("value"), int)}


def imam_ordinals(person):
    return {o for o in person.get("imam_ordinals") or [] if isinstance(o, int)}


def nasab_depth(pa, pb):
    """What one record's own full name has beyond the other's, judged on the full names.

    equal            one is the other, or extends it by a nisba/laqab/kunya only
    adds_generation  one extends the other by a «بن X»: a different father or grandfather
    different_ancestor  neither contains the other and a generation they both state differs
    divergent_nisba  neither contains the other, but no generation they both state differs
    """
    rel = relate(pa, pb)
    if rel:
        return ("equal" if rel[3] == 0 else "adds_generation"), rel[3]
    ga, gb = cr.generations(list(pa)), cr.generations(list(pb))
    for x, y in zip(ga, gb):
        if not (x & y):
            return "different_ancestor", None
    return "divergent_nisba", None


def evidence(pid_a, pid_b, rels, data, heads, prefix_idx):
    """All the independent evidence for one pair, reported field by field."""
    by_id = data["by_id"]
    a, b = by_id[pid_a], by_id[pid_b]
    pa, pb = primary_of(a, heads[pid_a]), primary_of(b, heads[pid_b])
    depth, added = nasab_depth(pa, pb)
    adds = [r[3] for r in rels] or [0]
    da, db = death_years(a), death_years(b)
    death = ("same" if da & db else "conflict") if (da and db) else "unknown"
    oa, ob = imam_ordinals(a), imam_ordinals(b)
    if oa and ob:
        gap = min(abs(x - y) for x in oa for y in ob)
        imam = "overlap" if oa & ob else ("adjacent" if gap <= 1 else "disjoint_far")
    else:
        gap, imam = None, "unknown"
    own = {" ".join(h) for h in headings_of(a) | headings_of(b)}
    t = (data["teachers"][pid_a] & data["teachers"][pid_b]) - own - cr.RELATIVE
    s = (data["students"][pid_a] & data["students"][pid_b]) - own - cr.RELATIVE
    ba, bb = set(a.get("books") or []), set(b.get("books") or [])
    shared_primary = sorted((ba & bb) & PRIMARY)
    if shared_primary:
        books = "same_book"
    elif (ba & PRIMARY) and (bb & PRIMARY):
        books = "different_books"
    else:
        books = "not_comparable"   # one is filed only in a compilation (ardabili)
    shorter = min(pa, pb, key=len)
    competing = sorted(prefix_idx.get(shorter, set()) - {pid_a, pid_b})
    return {
        "a": pid_a, "b": pid_b,
        "a_display_ar": a.get("display_ar"), "b_display_ar": b.get("display_ar"),
        "a_display_en": a.get("display_en"), "b_display_en": b.get("display_en"),
        "a_name": " ".join(pa), "b_name": " ".join(pb),
        "relation_via": sorted({r[0] for r in rels}),
        "nasab_depth": depth, "added_generations": added,
        "alias_added_generations_min_max": [min(adds), max(adds)],
        "a_death": sorted(da), "b_death": sorted(db), "death": death,
        "shared_teachers": len(t), "shared_students": len(s),
        "shared_teacher_names": sorted(t)[:5], "shared_student_names": sorted(s)[:5],
        "a_teachers": len(data["teachers"][pid_a]), "b_teachers": len(data["teachers"][pid_b]),
        "a_students": len(data["students"][pid_a]), "b_students": len(data["students"][pid_b]),
        "a_ordinals": sorted(oa), "b_ordinals": sorted(ob), "imam": imam, "imam_gap": gap,
        "a_books": sorted(ba & cr.CLASSICAL), "b_books": sorted(bb & cr.CLASSICAL),
        "shared_primary_books": shared_primary, "books": books,
        "a_entries": a.get("entry_count"), "b_entries": b.get("entry_count"),
        "shorter_name": " ".join(shorter),
        "competing_others": len(competing), "competing_ids": competing[:6],
    }


def bucket(row):
    """Clear distinct if depth differs, death years conflict or Imams are far apart. Clear
    double only if depth is equal, no death conflict, teachers or students are shared, and
    no other record extends the same shorter name. Everything else is undecidable."""
    if (row["nasab_depth"] in ("adds_generation", "different_ancestor")
            or row["death"] == "conflict"
            or row["imam"] == "disjoint_far"):
        return "clear distinct"
    related = row["shared_teachers"] + row["shared_students"] > 0
    if row["nasab_depth"] == "equal" and related and row["competing_others"] == 0:
        return "clear double"
    return "undecidable"


# --------------------------------------------------------------------------- the pass

def scan_resolved(path, targets):
    """One pass over resolved.jsonl: outcome counts, `several` costs, links per man."""
    outcomes, name_outcomes = Counter(), Counter()
    several = defaultdict(lambda: {"n": 0, "cands": None, "backed": 0, "books": Counter()})
    pair_cost, either_cost, links = Counter(), Counter(), Counter()
    conv_links = Counter()
    conv_pid = defaultdict(Counter)
    per_book = defaultdict(lambda: defaultdict(Counter))
    neighbours = defaultdict(lambda: defaultdict(Counter))
    ctx = []
    with open(path) as handle:
        for line in handle:
            row = json.loads(line)
            ms = row["mentions"]
            keys = [cr.key(m.get("text", "")) for m in ms]
            for i, m in enumerate(ms):
                out = m["outcome"]
                outcomes[out] += 1
                if m.get("kind") in ("name", "imam"):
                    name_outcomes[out] += 1
                pid = m.get("person_id") or m.get("imam_id") or ""
                if out in ("one", "chosen", "convention") and pid:
                    links[pid] += 1
                if out == "several" and m.get("kind") == "name":
                    rec = several[keys[i]]
                    rec["n"] += 1
                    rec["books"][row["book"]] += 1
                    if m.get("backed"):
                        rec["backed"] += 1
                    cands = sorted(m.get("candidates") or [])
                    rec["cands"] = rec["cands"] or cands
                    if len(cands) <= 12:
                        for p, q in combinations(cands, 2):
                            pair_cost[(p, q)] += 1
                    for p in cands:
                        either_cost[p] += 1
                if out == "convention" and m.get("because") == EVERYWHERE_WHY:
                    conv_links[keys[i]] += 1
                    conv_pid[keys[i]][pid] += 1
                if keys[i] in targets and m.get("kind") == "name":
                    per_book[keys[i]][row["book"]][(out, pid)] += 1
                    if keys[i] == MBH:
                        b4 = keys[i - 1] if i else ""
                        af = keys[i + 1] if i + 1 < len(keys) else ""
                        neighbours[row["book"]]["before"][b4] += 1
                        neighbours[row["book"]]["after"][af] += 1
                        ctx.append((row["book"], b4, af, pid, out))
    return dict(outcomes=outcomes, name_outcomes=name_outcomes, several=several,
                pair_cost=pair_cost, either_cost=either_cost, links=links,
                conv_links=conv_links, conv_pid=conv_pid, per_book=per_book,
                neighbours=neighbours, ctx=ctx)


def candidate_pairs(by_id):
    """Pairs of records that might be related, bucketed on the first two words of a heading."""
    buckets = defaultdict(lambda: defaultdict(set))
    heads = {pid: headings_of(p) for pid, p in by_id.items()}
    for pid, hs in heads.items():
        for h in hs:
            if len(h) >= 2:
                buckets[h[:2]][pid].add(h)
    pairs = set()
    for members in buckets.values():
        for p, q in combinations(sorted(members), 2):
            pairs.add((p, q))
    return pairs, heads


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--resolved", default=f"{cr.TMP}/narrators_chains/resolved.jsonl")
    args = ap.parse_args()
    os.makedirs(args.out_dir, exist_ok=True)

    table = cr.conventions(cr.CONVENTIONS)
    everywhere = table.get("everywhere") or {}
    settles = table.get("settles") or {}
    data = cr.load_people(cr.PEOPLE, cr.CLASSICAL)
    by_id = data["by_id"]
    with open(cr.PEOPLE) as handle:
        everyone = {p["person_id"]: p for p in json.load(handle)}
    print(f"universe {len(by_id):,} of {len(everyone):,} records", file=sys.stderr)

    scan = scan_resolved(args.resolved, set(everywhere) | {MBH})
    report = {"outcomes": dict(scan["outcomes"]), "name_outcomes": dict(scan["name_outcomes"])}

    def card(pid):
        p = by_id.get(pid) or everyone.get(pid) or {}
        return {"id": pid, "display_ar": p.get("display_ar"), "display_en": p.get("display_en"),
                "death_years": sorted(death_years(p)), "entry_count": p.get("entry_count")}

    # ---------------- part 1
    top = []
    for name, rec in sorted(scan["several"].items(), key=lambda kv: -kv[1]["n"]):
        held = cr.held_by(name, data)
        top.append({"name": name, "mentions": rec["n"], "held_by": len(held),
                    "backed_but_still_several": rec["backed"],
                    "books": dict(rec["books"].most_common(4)),
                    "candidates": [card(p) for p in held]})
    report["several_total_name_mentions"] = sum(r["n"] for r in scan["several"].values())
    report["several_distinct_names"] = len(scan["several"])
    report["several_top40"] = top[:40]

    # ---------------- part 2
    pairs, heads = candidate_pairs(by_id)
    print(f"{len(pairs):,} bucket pairs", file=sys.stderr)
    # who is filed under a name, or under a longer name that opens with it
    prefix_idx = defaultdict(set)
    for pid, hs in heads.items():
        for h in hs:
            for n in range(2, len(h) + 1):
                prefix_idx[h[:n]].add(pid)
    rel_by_pair = {}
    for p, q in pairs:
        rels = relations(heads[p], heads[q])
        if not rels:
            continue
        rel_by_pair[(p, q)] = rels
    print(f"{len(rel_by_pair):,} related pairs", file=sys.stderr)
    rows = []
    for (p, q), rels in rel_by_pair.items():
        pp, pq = primary_of(by_id[p], heads[p]), primary_of(by_id[q], heads[q])
        a, b = (p, q) if (len(pp), p) <= (len(pq), q) else (q, p)   # A has the shorter name
        row = evidence(a, b, rels, data, heads, prefix_idx)
        row["mentions_both_several"] = scan["pair_cost"].get((p, q), 0)
        row["mentions_either_several"] = scan["either_cost"][a] + scan["either_cost"][b]
        row["links_a"], row["links_b"] = scan["links"][a], scan["links"][b]
        row["bucket"] = bucket(row)
        rows.append(row)
    rows.sort(key=lambda r: (-r["mentions_both_several"], -r["mentions_either_several"],
                             -(r["links_a"] + r["links_b"]), r["a"], r["b"]))
    counts = Counter(r["bucket"] for r in rows)
    report["pairs"] = len(rows)
    report["buckets"] = dict(counts)
    report["buckets_of_pairs_costing_mentions"] = dict(
        Counter(r["bucket"] for r in rows if r["mentions_both_several"]))
    report["mentions_both_several_by_bucket"] = {
        k: sum(r["mentions_both_several"] for r in rows if r["bucket"] == k) for k in counts}
    in_pair = {r[k] for r in rows for k in ("a", "b")}
    clear_ids = {r[k] for r in rows if r["bucket"] == "clear double" for k in ("a", "b")}
    old = set(open(f"{cr.TMP}/narrators_chains/doubles_one_candidate.txt").read().split())
    report["stale_doubles_file"] = {
        "ids": len(old), "in_link_universe": len(old & set(by_id)),
        "in_any_detector_pair": len(old & in_pair),
        "in_clear_double_pair": len(old & clear_ids)}
    with open(os.path.join(args.out_dir, "chain_doubles_pairs.json"), "w") as handle:
        json.dump(rows, handle, ensure_ascii=False, indent=1)
    with open(os.path.join(args.out_dir, "chain_doubles_pairs.txt"), "w") as handle:
        handle.write("\t".join(("bucket", "both", "either", "a", "b", "via", "depth",
                                "death", "shT", "shS", "imam", "books", "comp",
                                "shorter -> longer | en")) + "\n")
        for r in rows:
            handle.write("\t".join(str(x) for x in (
                r["bucket"], r["mentions_both_several"], r["mentions_either_several"],
                r["a"], r["b"], "+".join(r["relation_via"]),
                f"{r['nasab_depth']}({r['added_generations']})",
                f"{r['death']}{r['a_death']}{r['b_death']}", r["shared_teachers"],
                r["shared_students"], r["imam"], r["books"], r["competing_others"],
                f"{r['a_name']} -> {r['b_name']} | "
                f"{r['a_display_en']} / {r['b_display_en']}")) + "\n")

    # ---------------- part 3
    conv = []
    for name, pid in everywhere.items():
        toks = tuple(name.split())
        mapped = by_id.get(pid)
        held = cr.held_by(name, data)
        reach = {}
        for other in by_id:
            if other == pid:
                continue
            if other in held:
                reach[other] = "held_by"
                continue
            for h in heads[other]:
                if len(h) > len(toks) and h[:len(toks)] == toks:
                    reach[other] = "prefix"
                    break
        others = []
        for other, via in reach.items():
            entry = {"via": via, **card(other), "mention_count": by_id[other].get("mention_count"),
                     "links": scan["links"][other]}
            rels = relations(heads[pid], heads[other]) if mapped else []
            if mapped and rels:
                ev = evidence(pid, other, rels, data, heads, prefix_idx)
                entry.update({k: ev[k] for k in (
                    "nasab_depth", "death", "shared_teachers", "shared_students", "imam",
                    "books", "competing_others")})
                entry["bucket"] = bucket(ev)
            else:
                entry["bucket"] = "no heading relation to the mapped record"
            others.append(entry)
        others.sort(key=lambda e: (e["via"] != "held_by", -(e["links"] or 0)))
        conv.append({"name": name, "maps_to": card(pid), "in_universe": pid in by_id,
                     "links_resting_on_it": scan["conv_links"][name],
                     "links_by_target": dict(scan["conv_pid"][name]),
                     "reachable_others": len(others), "held_by_count": len(held),
                     "others": others[:12],
                     "by_book_outcomes": {b: {f"{o}:{p}": n for (o, p), n in c.most_common()}
                                          for b, c in scan["per_book"][name].items()},
                     "convention_links_any_reason": sum(
                         n for c in scan["per_book"][name].values()
                         for (o, _), n in c.items() if o == "convention")})
    report["conventions"] = conv
    report["conventions_total_links"] = sum(scan["conv_links"].values())

    # ---------------- محمد بن الحسن per book
    held = cr.held_by(MBH, data)
    support = defaultdict(lambda: defaultdict(Counter))
    for book, b4, af, pid, out in scan["ctx"]:
        for cand in held:
            why = cr.support(cand, b4, af, data["teachers"], data["students"])
            if why:
                support[book][cand][why.split(" ", 2)[0] + " " + why.split(" ", 2)[1]] += 1
    mb = tuple(MBH.split())
    report["mbh"] = {
        "in_everywhere": MBH in everywhere, "everywhere_target": everywhere.get(MBH),
        "settles": {k: v["person_id"] for k, v in settles.items() if k.endswith("\t" + MBH)},
        "held_by": [card(p) for p in held],
        "per_book_outcomes": {b: {f"{o}:{p}": n for (o, p), n in c.most_common()}
                              for b, c in scan["per_book"][MBH].items()},
        "neighbours_after": {b: n["after"].most_common(8) for b, n in scan["neighbours"].items()},
        "neighbours_before": {b: n["before"].most_common(6) for b, n in scan["neighbours"].items()},
        "support_by_candidate": {b: {c: dict(v) for c, v in d.items()}
                                 for b, d in support.items()},
        "extensions": [card(p) | {"links": scan["links"][p]} for p in by_id
                       if p not in held and any(len(h) > 3 and h[:3] == mb for h in heads[p])],
    }
    with open(os.path.join(args.out_dir, "chain_doubles_report.json"), "w") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=1)
    print(json.dumps({k: report[k] for k in (
        "outcomes", "name_outcomes", "several_total_name_mentions", "several_distinct_names",
        "pairs", "buckets", "buckets_of_pairs_costing_mentions",
        "mentions_both_several_by_bucket", "stale_doubles_file", "conventions_total_links")},
        ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
