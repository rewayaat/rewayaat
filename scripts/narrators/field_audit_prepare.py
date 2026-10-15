#!/usr/bin/env python3
"""Draws narrator fields for auditors, to find out whether what a page states is in the books.

Seven audits have measured identity and the chain links. Not one has asked whether the facts
printed beside a man's name are true: where he lived, when he died, which Imam he transmitted
under. Those are stated on 15,526 pages and have never been read by anybody.

Coverage is not the question here — the sources are silent about most men and no work will
change that. The question is whether the 42.9% of cities and 51.4% of generations we do print
are supported by the passage they were read from. So each item is shown as the claim beside
its evidence: the value, the book and page it came from, and the entry's own words. The
auditor is not asked who the man was or whether the books are right. He is asked one thing —
does this passage say this?

Controls go in blind. Some items have had their value replaced with another man's, and the
auditors are expected to find those unsupported; others are the same item twice under
different ids. Nothing in a batch says which.

Writes tmp/narrators_audit/fields/runs/fields-<count>-<sha16>/{batches,outputs,manifest.json}
       tmp/narrators_audit/fields/keys/fields-<count>-<sha16>.json

Usage:
    python3 scripts/narrators/field_audit_prepare.py --dry-run
    python3 scripts/narrators/field_audit_prepare.py --per-field 130 --batches 4
"""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
import os
import random
import sys
import urllib.request

W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
sys.path.insert(0, f'{W}/scripts/narrators')
from identity import write_json_atomic  # noqa: E402

TMP = f'{W}/tmp'
SEED = 7
# Each field, and the words that would show it in a passage. A death year and a generation
# are read from different parts of an entry and are worth separate numbers.
# Every kind of claim a page prints with a book and a page behind it. The verdicts are the
# weightiest — a man is relied on or he is not, and 53,560 statements say which — and the
# relations are what the research graph will be built out of, so neither can stay unread.
FIELDS = ("city_or_tribe", "death_years", "generations", "reliability_grades",
          "narrated_from", "narrated_to", "titles", "kunyahs_arabic")
SNIPPET = 1400


def entry_key_for(pages_of, book, pages, entry_keys):
    """The entry a statement was read from — index_people's rule, kept identical on purpose."""
    same_book = [k for k in entry_keys if k.split(":")[0] == book]
    if len(same_book) == 1:
        return same_book[0]
    for key in same_book:
        held = set(pages_of.get(key) or ())
        if pages and held and set(pages) & held:
            return key
    return None


def documents(host, index, ids):
    """The published pages for these men, which carry each entry's own words."""
    found = {}
    ids = list(ids)
    for start in range(0, len(ids), 200):
        body = {"size": 200, "query": {"terms": {"person_id": ids[start:start + 200]}},
                "_source": ["person_id", "display_ar", "display_en", "entries", "life",
                            "generations", "books", "where_books_stand"]}
        request = urllib.request.Request(f'{host}/{index}/_search',
                                         data=json.dumps(body).encode(),
                                         headers={"Content-Type": "application/json"})
        for hit in json.load(urllib.request.urlopen(request))["hits"]["hits"]:
            found[hit["_source"]["person_id"]] = hit["_source"]
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--people", default=f'{TMP}/narrators_identity/people.json')
    parser.add_argument("--out-dir", default=f'{TMP}/narrators_audit/fields')
    parser.add_argument("--es-host", default="http://localhost:9200")
    parser.add_argument("--index", default="rewayaat_narrators")
    parser.add_argument("--per-field", type=int, default=130)
    parser.add_argument("--batches", type=int, default=4)
    parser.add_argument("--controls", type=int, default=10)
    parser.add_argument("--seed", type=int, default=SEED)
    parser.add_argument("--fields", default=",".join(FIELDS),
                        help="only these fields, for re-measuring one of them closely")
    parser.add_argument("--stated-only", action="store_true",
                        help="only claims the page prints as a book's own position. The whole "
                             "record holds 53,552 grade rows and a page states 2,584 of them; "
                             "measuring the record measures what nobody is shown.")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    wanted = tuple(f.strip() for f in args.fields.split(",") if f.strip())
    rng = random.Random(args.seed)
    with open(args.people) as handle:
        people = {p["person_id"]: p for p in json.load(handle) if p.get("holds_entry")}

    # Every statement that reached a page, with the entry it was read from.
    claims = defaultdict(list)
    for person in people.values():
        entry_keys = [k for k in person.get("source_keys") or []]
        pages_of = person.get("pages_of") or {}
        for field in wanted:
            for item in person.get(field) or []:
                value = item.get("value") or item.get("grade")
                if not value:
                    continue
                if field == "reliability_grades" and value == "not_assessed":
                    continue   # «no grade» is the absence of a claim, not a claim
                key = entry_key_for(pages_of, item.get("source_book"),
                                    item.get("source_pages") or [], entry_keys)
                if key:
                    claims[field].append((person["person_id"], value, key,
                                          item.get("source_pages") or []))
    for field in wanted:
        print(f"  {len(claims[field]):7,}  statements of {field} that reached a page")

    drawn = []
    for field in wanted:
        pool = claims[field]
        drawn += [(field,) + row for row in rng.sample(pool, min(args.per_field, len(pool)))]
    rng.shuffle(drawn)

    pages = documents(args.es_host, args.index, {row[1] for row in drawn})
    tasks, key_rows = [], []
    for position, (field, person_id, value, key, pages_said) in enumerate(drawn):
        page = pages.get(person_id)
        if not page:
            continue
        if args.stated_only and field == "reliability_grades":
            stated = {position["grade"]
                      for book in page.get("where_books_stand") or []
                      for position in book["positions"]}
            if value not in stated:
                continue
        entry = next((e for e in page.get("entries") or [] if e.get("source_key") == key), None)
        if not entry:
            continue
        if not (entry.get("text_ar") or "").strip() and not (
                entry.get("under_imams_ar") or entry.get("generation")):
            continue
        task_id = f"field:{position:04d}"
        tasks.append({"task_id": task_id, "field": field, "value": value,
                      "narrator_ar": page.get("display_ar"),
                      "narrator_en": page.get("display_en"),
                      "book": entry.get("book_title"), "book_ar": entry.get("book_title_ar"),
                      "pages": entry.get("pages") or pages_said,
                      "heading_ar": entry.get("heading_ar"),
                      # The chapter the entry stands in. A generation in Rijāl al-Ṭūsī is not
                      # written in the entry — «سليم بن قيس الهلالي.» is the whole of one —
                      # it is the chapter it is filed under. The first run left this out and
                      # four auditors independently said so: they were shown a bare name and
                      # asked whether it supported «Companion of al-Sadiq». It could not.
                      "filed_under": entry.get("under_imams_ar") or entry.get("under_imams"),
                      "entry_generation": entry.get("generation"),
                      "passage_ar": (entry.get("text_ar") or "")[:SNIPPET]})
        key_rows.append({"task_id": task_id, "field": field, "person_id": person_id,
                         "value": value, "source_key": key})

    planted = 0
    for task, key_row in zip(tasks, key_rows):
        if planted >= args.controls:
            break
        others = [t["value"] for t in tasks if t["field"] == task["field"]
                  and t["value"] != task["value"]]
        if not others:
            continue
        task["value"] = rng.choice(others)
        key_row["planted"] = True
        planted += 1

    copies = rng.sample(range(len(tasks)), min(args.controls, len(tasks)))
    for number, index in enumerate(copies):
        twin = json.loads(json.dumps(tasks[index]))
        twin["task_id"] = f"field:twin{number:02d}"
        tasks.append(twin)
        key_rows.append({"task_id": twin["task_id"], "twin_of": tasks[index]["task_id"],
                         "field": twin["field"]})

    rng.shuffle(tasks)
    counts = Counter(t["field"] for t in tasks)
    print(f"\n  {len(tasks):5,}  statements to judge "
          f"({planted} with the value replaced, {len(copies)} drawn twice)")
    for field in FIELDS:
        print(f"      {counts[field]:5,}  {field}")
    batches = [tasks[i::args.batches] for i in range(args.batches)]
    print(f"  {len(batches):5,}  batches, largest "
          f"{max(len(json.dumps(b, ensure_ascii=False)) for b in batches):,} chars")
    if args.dry_run:
        return

    fingerprint = hashlib.sha256(
        json.dumps(sorted(t["task_id"] for t in tasks)).encode()).hexdigest()[:16]
    name = f"fields-{len(key_rows)}-{fingerprint}"
    run = f'{args.out_dir}/runs/{name}'
    os.makedirs(f'{run}/batches', exist_ok=True)
    os.makedirs(f'{run}/outputs', exist_ok=True)
    os.makedirs(f'{args.out_dir}/keys', exist_ok=True)
    for number, batch in enumerate(batches):
        write_json_atomic(f'{run}/batches/fields_{number:02d}.json',
                          {"batch": f"fields_{number:02d}.json", "tasks": batch}, indent=1)
    write_json_atomic(f'{run}/manifest.json',
                      {"run": name, "seed": args.seed, "statements": len(key_rows),
                       "batches": len(batches), "planted": planted, "twins": len(copies),
                       "by_field": dict(counts)}, indent=1)
    write_json_atomic(f'{args.out_dir}/keys/{name}.json', key_rows, indent=1)
    print(f"\nwrote {len(batches)} batches -> {run}/batches")
    print(f"key (not in the run) -> {args.out_dir}/keys/{name}.json")


if __name__ == "__main__":
    main()
