#!/usr/bin/env python3
"""Draws the links that exist only because a nisba was trimmed, for auditors to judge.

When no man holds a chain's name as written, `widen` tries it again with its trailing nisbas
taken off, and links whoever holds what is left. That finds «مسعود العياشي» under his bare name,
and it also sent «محمد بن جعفر الرزاز» to Ibn Baṭṭa about thirty times: the trimmed word was
the only thing that said who this was, and the man it reached does not carry it. A blanket
rule cannot tell the two apart. Demanding that the man carry a trimmed word threw away 1,378
links, many of them right, so this asks readers which ones are right and fits the rule to
their answers.

The links are found by difference: the chains are resolved once with trimming switched off
(`chain_resolve.py --recall article,ibn,relative,short,kunya --out NOTRIM`), and every link the
served resolution makes that the other does not make, or makes to a different man, rests on a
trim. One link is drawn per distinct (name, man) pair, so a name repeated across hundreds of
chains is judged once and the rule is fitted to pairs rather than to how often a book repeats
one of them.

Controls are the chain audit's: links quietly swapped for another man, and tasks set twice.

Writes tmp/narrators_audit/trim/runs/trim-<count>-<sha16>/{batches,outputs,manifest.json}
       tmp/narrators_audit/trim/keys/trim-<count>-<sha16>.json   (the key, outside the run)

Usage:
    python3 scripts/narrators/trim_audit_prepare.py --notrim NOTRIM --dry-run
    python3 scripts/narrators/trim_audit_prepare.py --notrim NOTRIM --links 300 --batches 6
"""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
import os
import random
import sys

W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
sys.path.insert(0, f'{W}/scripts/narrators')
from chain_audit_prepare import load_people, plant, shown  # noqa: E402
from chain_resolve import key  # noqa: E402
from identity import write_json_atomic  # noqa: E402

TMP = f'{W}/tmp'
SEED = 20261028
LINKED = ("one", "chosen", "convention")
OPENING = 300   # characters of the man's first entry, enough to place him in time


def trimmed_links(served, notrim):
    """Every link of «served» that «notrim» does not make to the same man."""
    other = {}
    with open(notrim) as handle:
        for line in handle:
            row = json.loads(line)
            other[row["id"]] = {(m["start"], m["end"]): m for m in row["mentions"]}
    found = []
    with open(served) as handle:
        for line in handle:
            row = json.loads(line)
            theirs = other.get(row["id"], {})
            for mention in row["mentions"]:
                if mention.get("outcome") not in LINKED or not mention.get("person_id"):
                    continue
                twin = theirs.get((mention["start"], mention["end"]), {})
                if twin.get("person_id") == mention["person_id"] and twin.get("outcome") in LINKED:
                    continue
                found.append((row, mention, twin.get("person_id")))
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--resolved", default=f'{TMP}/narrators_chains/resolved.jsonl')
    parser.add_argument("--notrim", required=True,
                        help="the same chains resolved with trimming switched off")
    parser.add_argument("--chains-file", default=f'{TMP}/narrators_chains/chains_served.jsonl')
    parser.add_argument("--people", default=f'{TMP}/narrators_chains/people_index.json')
    parser.add_argument("--out-dir", default=f'{TMP}/narrators_audit/trim')
    parser.add_argument("--links", type=int, default=300, help="(name, man) pairs to draw")
    parser.add_argument("--batches", type=int, default=6)
    parser.add_argument("--controls", type=int, default=15,
                        help="planted controls, and the same number of verbatim ones")
    parser.add_argument("--seed", type=int, default=SEED)
    parser.add_argument("--dry-run", action="store_true", help="count, write nothing")
    args = parser.parse_args()

    rng = random.Random(args.seed)
    people = load_people(args.people)
    pool = sorted(pid for pid, p in people.items() if p.get("entry_count", 0) > 1)
    text_of = {}
    with open(args.chains_file) as handle:
        for line in handle:
            row = json.loads(line)
            text_of[row["id"]] = row["chain_ar"]

    found = trimmed_links(args.resolved, args.notrim)
    pairs = defaultdict(list)
    for row, mention, before in found:
        pairs[(key(mention["text"]), mention["person_id"])].append((row, mention, before))
    print(f"  {len(found):6,}  links rest on a trim, {len(pairs):,} distinct (name, man) pairs")

    drawn = rng.sample(sorted(pairs), min(args.links, len(pairs)))
    tasks, key_rows = [], []
    for position, pair in enumerate(drawn):
        row, mention, before = rng.choice(pairs[pair])
        task_id = f"trim:{position:04d}"
        link = shown(dict(mention, link_id=f"{task_id}.0"), people)
        entries = people.get(mention["person_id"], {}).get("entries") or []
        if entries:
            link["entry_opens"] = (entries[0].get("text_ar") or "")[:OPENING]
        tasks.append({"task_id": task_id, "book": row["book"],
                      "chain_ar": text_of.get(row["id"], ""), "links": [link]})
        key_rows.append({"task_id": task_id, "hadith": row["id"], "book": row["book"],
                         "pair_chains": len(pairs[pair]), "without_trim": before,
                         "answers": [{"link_id": f"{task_id}.0", "outcome": mention["outcome"],
                                      "kind": mention["kind"],
                                      "because": mention.get("because", ""),
                                      "person_id": mention["person_id"],
                                      "name": mention["text"], "key": pair[0]}]})

    planted = 0
    for task, key_row in zip(tasks, key_rows):
        if planted >= args.controls:
            break
        if plant(task, key_row, people, rng, pool):
            task["links"][0].pop("entry_opens", None)
            planted_to = people_by_name(people, task["links"][0]["person"])
            if planted_to and planted_to.get("entries"):
                task["links"][0]["entry_opens"] = \
                    (planted_to["entries"][0].get("text_ar") or "")[:OPENING]
            planted += 1

    copies = rng.sample(range(len(tasks)), min(args.controls, len(tasks)))
    for number, index in enumerate(copies):
        twin_id = f"trim:twin{number:02d}"
        twin = json.loads(json.dumps(tasks[index]))
        twin["task_id"] = twin_id
        for link in twin["links"]:
            link["link_id"] = link["link_id"].replace(tasks[index]["task_id"], twin_id)
        tasks.append(twin)
        key_rows.append({"task_id": twin_id, "twin_of": tasks[index]["task_id"],
                         "book": twin["book"], "answers": []})

    rng.shuffle(tasks)
    print(f"  {len(tasks):6,}  tasks ({planted} with the man replaced, {len(copies)} set twice)")
    print(f"  {len(set(t['book'] for t in tasks)):6,}  books represented")
    batches = [tasks[i::args.batches] for i in range(args.batches)]
    sizes = [len(json.dumps(b, ensure_ascii=False)) for b in batches]
    print(f"  {len(batches):6,}  batches, largest {max(sizes):,} chars")
    if args.dry_run:
        return

    fingerprint = hashlib.sha256(
        json.dumps(sorted(t["task_id"] for t in tasks)).encode()).hexdigest()[:16]
    name = f"trim-{len(drawn)}-{fingerprint}"
    run = f'{args.out_dir}/runs/{name}'
    os.makedirs(f'{run}/batches', exist_ok=True)
    os.makedirs(f'{run}/outputs', exist_ok=True)
    os.makedirs(f'{args.out_dir}/keys', exist_ok=True)
    for number, batch in enumerate(batches):
        write_json_atomic(f'{run}/batches/trim_{number:02d}.json',
                          {"batch": f"trim_{number:02d}.json", "tasks": batch}, indent=1)
    write_json_atomic(f'{run}/manifest.json',
                      {"run": name, "seed": args.seed, "pairs": len(pairs),
                       "links_resting_on_trim": len(found), "drawn": len(drawn),
                       "batches": len(batches), "planted": planted, "twins": len(copies)},
                      indent=1)
    write_json_atomic(f'{args.out_dir}/keys/{name}.json', key_rows, indent=1)
    print(f"\nwrote {len(batches)} batches -> {run}/batches")
    print(f"key (not in the run) -> {args.out_dir}/keys/{name}.json")


def people_by_name(people, display_ar):
    return next((p for p in people.values() if p.get("display_ar") == display_ar), None)


if __name__ == "__main__":
    main()
