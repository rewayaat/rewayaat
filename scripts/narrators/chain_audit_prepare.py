#!/usr/bin/env python3
"""Draws chains for auditors to read, to find out whether the links name the right men.

The resolver's own counts say how many names it linked, not how many it got right. Only
somebody reading the isnād can say that, so this draws chains at random — stratified by book,
so al-Kāfī's conventions do not stand for everybody's — and asks eight auditors one question
per link: is this the man the chain means?

Three answers are wanted, one per kind of claim the resolver makes:

- a link we made (`one` or `chosen`) — is it the right man?
- a refusal (`several`) — is the name really undecidable here, or does the chain settle it?

Controls go in blind. Some tasks have a link quietly replaced with a different man of the
same name shape, and the auditors are expected to call those wrong; others are the same chain
twice under different ids, to see whether an auditor agrees with himself. Nothing in a batch
says which is which.

Writes tmp/narrators_audit/chain/runs/chain-<count>-<sha16>/{batches,outputs,manifest.json}
       tmp/narrators_audit/chain/keys/chain-<count>-<sha16>.json   (the key, outside the run)

Usage:
    python3 scripts/narrators/chain_audit_prepare.py --dry-run
    python3 scripts/narrators/chain_audit_prepare.py --chains 200
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
import imams  # noqa: E402
from identity import write_json_atomic  # noqa: E402

TMP = f'{W}/tmp'
SEED = 11
JUDGED = ("one", "chosen", "convention", "several")
MAX_LINKS = 7

# A convention link is a link made on evidence — the evidence being how the book in question
# reads that name — so the auditor is shown it the way every other link made on evidence is
# shown: the name, the man, and the reason. Which links came from the conventions table is
# written in the key and not in the batch, so that an auditor cannot hold one kind of claim to
# a gentler standard than another by knowing which kind he is looking at.
AS_SHOWN = {"convention": "chosen"}


def load_people(path):
    with open(path) as handle:
        return {p["person_id"]: p for p in json.load(handle)}


def shown(mention, people):
    """One link as an auditor sees it: the name, what we say about it, and nothing more."""
    row = {"link_id": mention["link_id"], "name": mention["text"].strip(),
           "we_say": AS_SHOWN.get(mention["outcome"], mention["outcome"])}
    who = mention.get("person_id") or mention.get("imam_id")
    if mention["outcome"] == "several":
        names = []
        for pid in mention.get("candidates", [])[:8]:
            person = people.get(pid)
            names.append(person["display_ar"] if person
                         else imams.BY_ID[pid]["name_ar"] if pid in imams.BY_ID else pid)
        row["candidates"] = names
        return row
    if who in people:
        person = people[who]
        row["person"] = person["display_ar"]
        row["also"] = [n for n in (person.get("names_ar") or [])
                       if n != person["display_ar"]][:3]
        row["in_books"] = sorted({e.get("book_title_ar") or e.get("book")
                                  for e in person.get("entries") or []} - {None})[:4] or \
            person.get("books") or []
    elif who in imams.BY_ID:
        row["person"] = imams.BY_ID[who]["name_ar"]
        row["also"] = []
        row["in_books"] = []
    if mention.get("because"):
        row["because"] = mention["because"]
    return row


def plant(task, key_row, people, rng, pool):
    """Swap one link for a different man, so a batch holds a wrong answer we know about."""
    linked = [l for l in task["links"] if l["we_say"] in ("one", "chosen") and "person" in l]
    if not linked:
        return False
    link = rng.choice(linked)
    for _ in range(12):
        other = people[rng.choice(pool)]
        if other["display_ar"] != link.get("person"):
            break
    else:
        return False
    link["person"] = other["display_ar"]
    link["also"] = [n for n in (other.get("names_ar") or [])
                    if n != other["display_ar"]][:3]
    link["in_books"] = other.get("books") or []
    link.pop("because", None)
    key_row["planted"] = link["link_id"]
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--resolved", default=f'{TMP}/narrators_chains/resolved.jsonl')
    parser.add_argument("--chains-file", default=f'{TMP}/narrators_chains/chains_served.jsonl')
    parser.add_argument("--people", default=f'{TMP}/narrators_chains/people_index.json')
    parser.add_argument("--out-dir", default=f'{TMP}/narrators_audit/chain')
    parser.add_argument("--chains", type=int, default=200, help="chains to draw")
    parser.add_argument("--batches", type=int, default=8)
    parser.add_argument("--controls", type=int, default=12,
                        help="planted controls, and the same number of verbatim ones")
    parser.add_argument("--seed", type=int, default=SEED,
                        help="a different seed draws a different sample, for a re-audit")
    parser.add_argument("--ask", default=",".join(JUDGED),
                        help="which outcomes become questions")
    parser.add_argument("--require", default="",
                        help="draw only chains holding a link of this outcome")
    parser.add_argument("--thin", default="",
                        help="at most N links of an outcome per chain, as `one:1`, so a "
                             "reference class does not crowd out what is being measured")
    parser.add_argument("--dry-run", action="store_true", help="count, write nothing")
    args = parser.parse_args()

    ask = tuple(o.strip() for o in args.ask.split(",") if o.strip())
    require = tuple(o.strip() for o in args.require.split(",") if o.strip())
    thin = {}
    for rule in args.thin.split(","):
        if rule.strip():
            outcome, cap = rule.split(":")
            thin[outcome.strip()] = int(cap)
    rng = random.Random(args.seed)
    people = load_people(args.people)
    pool = sorted(pid for pid, p in people.items() if p.get("entry_count", 0) > 1)

    text_of = {}
    with open(args.chains_file) as handle:
        for line in handle:
            row = json.loads(line)
            text_of[row["id"]] = row["chain_ar"]

    by_book = defaultdict(list)
    with open(args.resolved) as handle:
        for line in handle:
            row = json.loads(line)
            judged = [m for m in row["mentions"] if m["outcome"] in ask]
            if not judged or len(judged) > MAX_LINKS:
                continue
            if require and not any(m["outcome"] in require for m in row["mentions"]):
                continue
            by_book[row["book"]].append(row)

    books = sorted(by_book, key=lambda b: -len(by_book[b]))
    share = max(1, args.chains // max(len(books), 1))
    drawn = []
    for book in books:
        rows = by_book[book]
        drawn += rng.sample(rows, min(share, len(rows)))
    taken = {id(r) for r in drawn}
    rest = [r for book in books for r in by_book[book] if id(r) not in taken]
    rng.shuffle(rest)
    drawn += rest[:max(0, args.chains - len(drawn))]
    rng.shuffle(drawn)
    drawn = drawn[:args.chains]

    tasks, key_rows = [], []
    for position, row in enumerate(drawn):
        task_id = f"chain:{position:04d}"
        keep, held = [], Counter()
        for mention in row["mentions"]:
            if mention["outcome"] not in ask:
                continue
            held[mention["outcome"]] += 1
            if held[mention["outcome"]] > thin.get(mention["outcome"], MAX_LINKS):
                continue
            keep.append(mention)
        judged = [dict(m, link_id=f"{task_id}.{number}")
                  for number, m in enumerate(keep)]
        tasks.append({"task_id": task_id, "book": row["book"],
                      "chain_ar": text_of.get(row["id"], ""),
                      "links": [shown(m, people) for m in judged]})
        key_rows.append({"task_id": task_id, "hadith": row["id"], "book": row["book"],
                         "answers": [{"link_id": m["link_id"], "outcome": m["outcome"],
                                      "kind": m["kind"], "because": m.get("because", ""),
                                      "person_id": m.get("person_id") or m.get("imam_id", ""),
                                      "candidates": m.get("candidates", [])}
                                     for m in judged]})

    planted = 0
    for task, key_row in zip(tasks, key_rows):
        if planted >= args.controls:
            break
        if plant(task, key_row, people, rng, pool):
            planted += 1

    copies = rng.sample(range(len(tasks)), min(args.controls, len(tasks)))
    for number, index in enumerate(copies):
        twin_id = f"chain:twin{number:02d}"
        twin = json.loads(json.dumps(tasks[index]))
        twin["task_id"] = twin_id
        for link in twin["links"]:
            link["link_id"] = link["link_id"].replace(tasks[index]["task_id"], twin_id)
        tasks.append(twin)
        key_rows.append({"task_id": twin_id, "twin_of": tasks[index]["task_id"],
                         "book": twin["book"], "answers": []})

    rng.shuffle(tasks)
    counts = Counter(a["outcome"] for k in key_rows for a in k.get("answers") or [])
    questions = sum(counts.values())
    print(f"  {len(tasks):5,}  chains drawn ({planted} with a link replaced, "
          f"{len(copies)} drawn twice)")
    print(f"  {questions:5,}  links to judge")
    for outcome in ask:
        print(f"      {counts[outcome]:5,}  {outcome}"
              + (f"  (shown as {AS_SHOWN[outcome]})" if outcome in AS_SHOWN else ""))
    print(f"  {len(set(t['book'] for t in tasks)):5,}  books represented")

    batches = [tasks[i::args.batches] for i in range(args.batches)]
    sizes = [len(json.dumps(b, ensure_ascii=False)) for b in batches]
    print(f"  {len(batches):5,}  batches, largest {max(sizes):,} chars")
    if args.dry_run:
        return

    fingerprint = hashlib.sha256(
        json.dumps(sorted(t["task_id"] for t in tasks)).encode()).hexdigest()[:16]
    name = f"chain-{questions}-{fingerprint}"
    run = f'{args.out_dir}/runs/{name}'
    os.makedirs(f'{run}/batches', exist_ok=True)
    os.makedirs(f'{run}/outputs', exist_ok=True)
    os.makedirs(f'{args.out_dir}/keys', exist_ok=True)
    for number, batch in enumerate(batches):
        write_json_atomic(f'{run}/batches/chain_{number:02d}.json',
                          {"batch": f"chain_{number:02d}.json", "tasks": batch}, indent=1)
    write_json_atomic(f'{run}/manifest.json',
                      {"run": name, "seed": args.seed, "ask": list(ask),
                       "require": list(require),
                       "chains": len(tasks), "links": questions,
                       "batches": len(batches), "planted": planted, "twins": len(copies),
                       "by_outcome": dict(counts)}, indent=1)
    write_json_atomic(f'{args.out_dir}/keys/{name}.json', key_rows, indent=1)
    print(f"\nwrote {len(batches)} batches -> {run}/batches")
    print(f"key (not in the run) -> {args.out_dir}/keys/{name}.json")


if __name__ == "__main__":
    main()
