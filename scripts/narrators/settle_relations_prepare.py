#!/usr/bin/env python3
"""Draws the relation rows no name match can place, for a judge to settle.

A narrator page lists the men the rijāl books name as his teachers, his pupils, and the men
the chain indexes stand him beside. Each of those rows is a name as some book printed it,
and the row becomes a link only when that name reaches exactly one man in this index.
13,305 of the 36,002 rows in the isnād lists reach more than one, and `relations_join`
gives such a row to nobody: «أحمد بن محمد بن يحيى» is four men, and a row placed on the
wrong one is worse than a row left bare.

A reader does not resolve those names by matching strings either. He reads the page: who
this man is, when he lived, which Imams he kept company with, which of the candidates
could have stood beside him at all — and most of the time the answer is not close. That is
the judgement this asks for, one row at a time, with everything the index holds about the
page's man and about each candidate laid beside it, including two pieces of evidence the
strings cannot see:

* how many narrations in the corpus this database serves actually stand the two men side
  by side, which is a fact about the chains and not about any book's claim; and
* whether the candidate's own entry names the page's man back, which is the books
  attesting the pairing from the other end.

Those two agree on 2,312 rows and disagree on 145, so neither is a rule to apply blind.
They go in as evidence and the judge weighs them against the dating, which outranks
everything: a book cannot name a man who outlived its compiler, and a man two generations
out is not the man meant however well the name matches.

**Controls go in blind.** A fifth of every batch is a row the two kinds of evidence already
agree on, planted unmarked, and the key is written outside the run. A judge who cannot
recover those is not to be trusted with the rest.

Writes tmp/narrators_audit/relations/runs/relations-<rows>-<sha16>/{batches,outputs,manifest.json}
       tmp/narrators_audit/relations/keys/relations-<rows>-<sha16>.json   (the key, outside the run)

Usage:
    python3 scripts/narrators/settle_relations_prepare.py --rows 240 --dry-run
    python3 scripts/narrators/settle_relations_prepare.py --rows 240
    python3 scripts/narrators/settle_relations_prepare.py --rows all --fields chain_from,chain_to
"""
import argparse
import hashlib
import json
import os
import random
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import isnad_neighbours  # noqa: E402
import relations_join as rj  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
RUNS = os.path.join(ROOT, "tmp", "narrators_audit", "relations", "runs")
KEYS = os.path.join(ROOT, "tmp", "narrators_audit", "relations", "keys")

#: The name fields the row lookup consults, which is what makes a name ambiguous or not.
NAMES_AR = ("headings_ar", "names_ar", "display_ar")
NAMES_EN = ("names_en", "display_en")

#: What a judge is shown about a man, his own and every candidate's.
PROFILE = ("person_id", "slug", "display_ar", "display_en", "nasab_ar", "headings_ar",
           "life", "death_years", "generations", "standing", "books", "mention_count",
           "narrated_from", "narrated_to")

#: More than this many men holding one name is not a judgement, it is a lottery. The row is
#: still drawn — a judge who can rule out all but one of nine has told us something — but
#: the list is cut to the candidates the evidence or the books give any weight to.
MOST_CANDIDATES = 8

#: A fifth of each batch, planted blind.
CONTROL_SHARE = 0.2

DIRECTION = {
    "narrated_from": "a book names him as one the page's man narrated FROM (his teacher)",
    "narrated_to": "a book names him as one who narrated FROM the page's man (his pupil)",
    "chain_from": "a chain index stands him BEFORE the page's man in an isnād",
    "chain_to": "a chain index stands him AFTER the page's man in an isnād",
    "unsaid_from": "our records tie him to the page's man as a teacher, but the entry we "
                   "hold does not name him",
    "unsaid_to": "our records tie him to the page's man as a pupil, but the entry we hold "
                 "does not name him",
}


def _scan(host, index, fields):
    return rj._scan(host, index, list(fields))


def tables(people):
    """Name → the one man who answers to it, and the names more than one man answers to."""
    arabic, english = defaultdict(set), defaultdict(set)
    for doc in people:
        pid = doc.get("person_id")
        if not pid:
            continue
        for name in _values(doc, NAMES_AR):
            key = rj.arabic_key(name)
            if key and len(key.split()) >= rj._LEAST_WORDS:
                arabic[key].add(pid)
        for name in _values(doc, NAMES_EN):
            key = rj.english_key(name)
            if key and len(key.split()) >= rj._LEAST_WORDS:
                english[key].add(pid)
    one_ar = {k: next(iter(v)) for k, v in arabic.items() if len(v) == 1}
    one_en = {k: next(iter(v)) for k, v in english.items() if len(v) == 1}
    many = {k: sorted(v) for k, v in arabic.items() if len(v) > 1}
    many_en = {k: sorted(v) for k, v in english.items() if len(v) > 1}
    return (one_ar, one_en), many, many_en


def _values(doc, fields):
    out = []
    for field in fields:
        value = doc.get(field)
        if isinstance(value, list):
            out += [v for v in value if isinstance(v, str)]
        elif isinstance(value, str):
            out.append(value)
    return out


def row_keys(row):
    """The Arabic and Latin keys a row is looked up under."""
    written = row.get("name") or ""
    arabic = rj.arabic_key(row.get("name_ar") or (written if rj._has_arabic(written) else ""))
    return arabic, rj.english_key(written)


def undecided(row, one, many, many_en):
    """The men a row could name, when it names more than one and nothing else places it."""
    arabic, latin = row_keys(row)
    one_ar, one_en = one
    if arabic in one_ar or latin in one_en:
        return None                      # placed already
    if arabic and arabic in many:
        return many[arabic]
    if not arabic and latin in many_en:
        return many_en[latin]
    return None                          # a name nobody answers to is a different problem


def profile(doc):
    """What a judge is told about a man: who he is, when, and whom the books put him with.

    Written once per batch and referred to by id. A page with twenty undecided rows was
    carrying twenty copies of its own profile and of every candidate's, which is most of
    what a batch weighed.

    Empty fields are left out rather than sent as nulls, and the headings are cut to the
    few that say something the name and the lineage do not. A judge reads less of the same
    thing, which is also the only way the whole index fits through this at a sane cost.
    """
    life = doc.get("life") or {}
    name, lineage = doc.get("display_ar"), doc.get("nasab_ar")
    filed = [h for h in (doc.get("headings_ar") or []) if h not in (name, lineage)][:4]
    imams = life.get("imams") or []
    out = {
        "person_id": doc.get("person_id"),
        "name_ar": name,
        "name_en": doc.get("display_en"),
        "lineage_ar": lineage if lineage != name else None,
        "also_filed_as": filed,
        "kept_company_with_imams": imams,
        "city": life.get("city"),
        "died": life.get("died") or ([d.get("value") for d in doc.get("death_years") or []]
                                     or [None])[0],
        # Ṭūsī files a man under the Imams he kept company with, which is the same dating
        # said at greater length. Only worth sending where nothing shorter carries it.
        "generation_said_by_tusi": ([g.get("value") for g in (doc.get("generations") or [])][:2]
                                    if not imams else []),
        "standing": doc.get("standing"),
        "entries_in": doc.get("books") or [],
        "named_in_narrations": doc.get("mention_count"),
        "his_teachers": [r.get("name_ar") or r.get("name")
                         for r in (doc.get("narrated_from") or [])[:4]],
        "his_pupils": [r.get("name_ar") or r.get("name")
                       for r in (doc.get("narrated_to") or [])[:4]],
    }
    return {k: v for k, v in out.items() if v not in (None, [], "")}


def evidence(subject_id, candidate_id, beside, names_back):
    """The two things the strings cannot see, said plainly and without a verdict."""
    chains = ((beside.get(subject_id) or {}).get("beside") or {}).get(candidate_id, 0)
    back = names_back.get(candidate_id, {}).get(subject_id)
    return {
        "narrations_standing_them_together": chains,
        "his_own_entry_names_the_pages_man": bool(back),
        "where_it_names_him": back or None,
    }


def gather(host, index, fields, verbose=True):
    """Every undecided row, with its candidates and the evidence for each."""
    if verbose:
        print("reading the names every narrator answers to", file=sys.stderr)
    people = list(_scan(host, index, ("person_id",) + NAMES_AR + NAMES_EN))
    one, many, many_en = tables(people)
    if verbose:
        print(f"  {len(people):,} people; {len(many):,} Arabic names more than one man "
              f"answers to", file=sys.stderr)
    del people

    # Which man each row reaches, from the other end: the candidate's own entry naming the
    # page's man back is half the evidence, and it has to be read before the rows are drawn.
    if verbose:
        print("reading every relation row, to see who names whom back", file=sys.stderr)
    names_back = defaultdict(dict)
    wanted = ["person_id"] + [f"{f}.{k}" for f in rj.RELATION_FIELDS
                              for k in ("name", "name_ar", "books")]
    rows_by_man = {}
    for doc in _scan(host, index, wanted):
        pid = doc.get("person_id")
        if not pid:
            continue
        rows_by_man[pid] = {f: doc.get(f) or [] for f in rj.RELATION_FIELDS}
        for field in rj.RELATION_FIELDS:
            for row in doc.get(field) or []:
                who = rj.whose(row, one)
                if who:
                    names_back[pid][who] = (row.get("books") or [None])[0] or "a book"

    if verbose:
        print("reading the chains this database serves", file=sys.stderr)
    try:
        beside, steps = isnad_neighbours.build(host, least=1)
    except Exception as unreachable:  # noqa: BLE001
        beside, steps = {}, 0
        print(f"  could not read the chains ({unreachable}); that evidence will be blank",
              file=sys.stderr)

    tasks, needed = [], set()
    for pid, lists in rows_by_man.items():
        for field in fields:
            for row in lists.get(field) or []:
                candidates = undecided(row, one, many, many_en)
                if not candidates:
                    continue
                arabic, latin = row_keys(row)
                # The man, the list, and the name as folded — and not the row's place in
                # that list, which moves whenever an extraction changes. After the join a
                # list holds one row per folded name, so this names the row exactly.
                tasks.append({
                    "row": f"{pid}|{field}|{arabic or latin}",
                    "page_man": pid,
                    "field": field,
                    "what_the_book_says": DIRECTION[field],
                    "name_as_printed": row.get("name_ar") or row.get("name"),
                    "named_by": row.get("books") or [],
                    "candidate_ids": candidates,
                })
                needed.add(pid)
                needed.update(candidates)
    if verbose:
        print(f"  {len(tasks):,} rows no name match can place, over "
              f"{len({t['page_man'] for t in tasks}):,} pages", file=sys.stderr)
    return tasks, needed, beside, names_back, steps


def profiles(host, index, ids, verbose=True):
    """Fetch the men a batch talks about, in chunks, carrying only what a judge reads."""
    out, ids = {}, sorted(ids)
    for start in range(0, len(ids), 400):
        chunk = ids[start:start + 400]
        body = json.dumps({"ids": chunk}).encode()
        import urllib.request
        request = urllib.request.Request(
            f"{host}/{index}/_mget?_source={','.join(PROFILE)}", data=body,
            headers={"Content-Type": "application/json"})
        for doc in json.load(urllib.request.urlopen(request)).get("docs", []):
            if doc.get("found"):
                out[doc["_id"]] = doc["_source"]
        if verbose:
            print(f"  profiles {len(out):,}/{len(ids):,}", end="\r", file=sys.stderr)
    if verbose:
        print(file=sys.stderr)
    return out


def settled_by_both(task, beside, names_back):
    """The control set: rows the chains and the books already agree on.

    A control is not an answer to copy — it is a row where two independent kinds of
    evidence point at the same one of the candidates, and a judge reading the page should
    land there too.
    """
    pid = task["page_man"]
    standing = {c for c in task["candidate_ids"]
                if ((beside.get(pid) or {}).get("beside") or {}).get(c)}
    naming = {c for c in task["candidate_ids"] if names_back.get(c, {}).get(pid)}
    if len(standing) == 1 and standing == naming:
        return next(iter(standing))
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--es-host", default="http://localhost:9200")
    parser.add_argument("--index", default="rewayaat_narrators")
    parser.add_argument("--rows", default="240",
                        help="how many rows to draw, or «all»")
    parser.add_argument("--batch-rows", type=int, default=40)
    parser.add_argument("--fields", default=",".join(rj.RELATION_FIELDS),
                        help="which relation lists to draw from")
    parser.add_argument("--seed", type=int, default=20261026)
    parser.add_argument("--dry-run", action="store_true",
                        help="count what there is to do and write nothing")
    args = parser.parse_args()

    fields = [f.strip() for f in args.fields.split(",") if f.strip()]
    unknown = [f for f in fields if f not in rj.RELATION_FIELDS]
    if unknown:
        parser.error(f"not a relation list: {', '.join(unknown)}")

    tasks, needed, beside, names_back, steps = gather(args.es_host, args.index, fields)
    by_field = Counter(t["field"] for t in tasks)
    for field in fields:
        print(f"  {by_field.get(field, 0):7,}  {field}")
    controls = {t["row"]: settled_by_both(t, beside, names_back) for t in tasks}
    controls = {row: who for row, who in controls.items() if who}
    print(f"  {len(controls):7,}  of them the chains and the books already agree on")
    if args.dry_run:
        return

    rng = random.Random(args.seed)
    plain = [t for t in tasks if t["row"] not in controls]
    rng.shuffle(plain)
    control_tasks = [t for t in tasks if t["row"] in controls]
    rng.shuffle(control_tasks)

    if args.rows == "all":
        drawn = plain
        wanted_controls = int(len(drawn) * CONTROL_SHARE / (1 - CONTROL_SHARE))
    else:
        total = int(args.rows)
        wanted_controls = int(round(total * CONTROL_SHARE))
        drawn = plain[:total - wanted_controls]
    drawn = drawn + control_tasks[:wanted_controls]
    rng.shuffle(drawn)

    ids = {t["page_man"] for t in drawn} | {c for t in drawn for c in t["candidate_ids"]}
    print(f"drawing {len(drawn):,} rows ({min(wanted_controls, len(control_tasks)):,} of "
          f"them controls); reading {len(ids):,} profiles", file=sys.stderr)
    known = profiles(args.es_host, args.index, ids)

    for task in drawn:
        pid = task["page_man"]
        shown = []
        for cid in task["candidate_ids"]:
            if cid not in known:
                continue
            found = evidence(pid, cid, beside, names_back)
            found["person_id"] = cid
            shown.append(found)
        # Where a name is held by more men than anyone can weigh, the ones with evidence
        # are kept and the rest cut by how often the corpus names them at all.
        if len(shown) > MOST_CANDIDATES:
            shown.sort(key=lambda c: (-(c["narrations_standing_them_together"] or 0),
                                      -c["his_own_entry_names_the_pages_man"],
                                      -(known[c["person_id"]].get("mention_count") or 0)))
            task["candidates_left_out"] = len(shown) - MOST_CANDIDATES
            shown = shown[:MOST_CANDIDATES]
        task["candidates"] = shown
        task["the_page"] = pid
        task.pop("candidate_ids", None)
        task.pop("page_man", None)

    # One page's rows travel together, so its profile is written once and the candidates
    # its rows share are written once.
    drawn.sort(key=lambda t: (t["the_page"], t["row"]))

    digest = hashlib.sha256(
        json.dumps([t["row"] for t in drawn], ensure_ascii=False).encode()).hexdigest()[:16]
    name = f"relations-{len(drawn)}-{digest}"
    run = os.path.join(RUNS, name)
    os.makedirs(os.path.join(run, "batches"), exist_ok=True)
    os.makedirs(os.path.join(run, "outputs"), exist_ok=True)
    os.makedirs(KEYS, exist_ok=True)

    batches = []
    for n, start in enumerate(range(0, len(drawn), args.batch_rows), 1):
        chunk = drawn[start:start + args.batch_rows]
        wanted = {t["the_page"] for t in chunk}
        wanted |= {c["person_id"] for t in chunk for c in t["candidates"]}
        people = {pid: profile(known[pid]) for pid in sorted(wanted) if pid in known}
        path = os.path.join(run, "batches", f"batch_{n:03d}.json")
        with open(path, "w") as handle:
            json.dump({"batch": n, "people": people, "tasks": chunk},
                      handle, ensure_ascii=False, indent=1)
        batches.append(os.path.basename(path))

    with open(os.path.join(run, "manifest.json"), "w") as handle:
        json.dump({"run": name, "rows": len(drawn), "batches": batches,
                   "fields": fields, "seed": args.seed,
                   "controls": sum(1 for t in drawn if t["row"] in controls),
                   "chain_steps": steps,
                   "prompt": "scripts/narrators/settle_relations_prompt.md"},
                  handle, indent=1)
    with open(os.path.join(KEYS, f"{name}.json"), "w") as handle:
        json.dump({row: who for row, who in controls.items()
                   if row in {t["row"] for t in drawn}}, handle, indent=1)

    print(f"{len(batches)} batches -> {run}/batches")
    print(f"key -> {os.path.join(KEYS, name + '.json')}")


if __name__ == "__main__":
    main()
