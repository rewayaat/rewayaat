#!/usr/bin/env python3
"""Draws English chain links for auditors, to find out whether the alignment put them right.

The English links were never resolved against the books. Each one took the man the Arabic name
in the same place had already been given, so whether a link is right turns on two things: the
Arabic resolution, measured at 95.3% by three rounds of audit, and the alignment that carried
it across. Only the second is unmeasured, and only the second is what this asks about.

So the question is not «is this the right man» — that has been asked — but «is this English
name the same name as the Arabic one the link was taken from». An auditor is shown both chains
whole, and for each link the English words it covers beside the Arabic words it came from.
A reader who knows no rijāl can answer it, and a reader who does cannot be misled by agreeing
with our choice of man.

Controls go in blind. In some tasks a link has been moved to the English name beside the right
one — the exact failure the alignment can make — and the auditors are expected to call those
wrong. Others are the same chain twice under different ids. Nothing in a batch says which.

Writes tmp/narrators_audit/chain_en/runs/en-<count>-<sha16>/{batches,outputs,manifest.json}
       tmp/narrators_audit/chain_en/keys/en-<count>-<sha16>.json

Usage:
    python3 scripts/narrators/chain_audit_en.py --dry-run
    python3 scripts/narrators/chain_audit_en.py --chains 160 --batches 4
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
from chain_links_en import link_chain, slots, AGREE  # noqa: E402
from identity import write_json_atomic  # noqa: E402

TMP = f'{W}/tmp'
SEED = 41
MAX_LINKS = 7


def placed(row, chain_en, agree, slug_of):
    """The links this narration's English chain carries, with the Arabic each came from.

    Taken from `link_chain`, the one that writes them, rather than worked out again here. This
    read the slots and aligned them itself until 2026-10-19, which was already a cruder
    placement than the linker's — a slot held one name at most — and once the linker placed
    names one at a time the two were answering different questions. An audit of a placement
    nobody ships measures nothing, however careful the auditors are.
    """
    mentions = [m for m in row["mentions"] if m["kind"] != "text"]
    places = slots(chain_en)
    if not places or not mentions:
        return []
    return [{"english": link["t"].strip(), "arabic": link["a"], "person": link["p"]}
            for link in link_chain(chain_en, places, mentions, agree, slug_of, Counter())]


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--resolved", default=f'{TMP}/narrators_chains/resolved.jsonl')
    parser.add_argument("--served", default=f'{TMP}/narrators_chains/chains_served.jsonl')
    parser.add_argument("--out-dir", default=f'{TMP}/narrators_audit/chain_en')
    parser.add_argument("--es-host", default="http://localhost:9200")
    parser.add_argument("--chains", type=int, default=160)
    parser.add_argument("--batches", type=int, default=4)
    parser.add_argument("--controls", type=int, default=10)
    parser.add_argument("--agree", type=float, default=AGREE)
    parser.add_argument("--seed", type=int, default=SEED)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    rng = random.Random(args.seed)
    from chain_links_load import slugs
    slug_of = slugs(args.es_host)
    english, arabic_of = {}, {}
    with open(args.served) as handle:
        for line in handle:
            row = json.loads(line)
            if row.get("chain_en"):
                english[row["id"]] = row["chain_en"]
            # The Arabic chain lives here and not in the resolved file, which carries the
            # mentions but not the text they were cut from. The first run shipped it empty,
            # so four auditors judged each pair of names without the chain around it.
            arabic_of[row["id"]] = row.get("chain_ar", "")

    by_book = defaultdict(list)
    with open(args.resolved) as handle:
        for line in handle:
            row = json.loads(line)
            chain_en = english.get(row["id"])
            if not chain_en:
                continue
            links = placed(row, chain_en, args.agree, slug_of)
            if not links or len(links) > MAX_LINKS:
                continue
            by_book[row["book"]].append((row, chain_en, links))

    books = sorted(by_book, key=lambda b: -len(by_book[b]))
    share = max(1, args.chains // max(len(books), 1))
    drawn = []
    for book in books:
        drawn += rng.sample(by_book[book], min(share, len(by_book[book])))
    taken = {id(r) for r in drawn}
    rest = [r for book in books for r in by_book[book] if id(r) not in taken]
    rng.shuffle(rest)
    drawn += rest[:max(0, args.chains - len(drawn))]
    rng.shuffle(drawn)
    drawn = drawn[:args.chains]

    tasks, key_rows = [], []
    for position, (row, chain_en, links) in enumerate(drawn):
        task_id = f"en:{position:04d}"
        shown, answers = [], []
        for number, link in enumerate(links):
            link_id = f"{task_id}.{number}"
            shown.append({"link_id": link_id, "english": link["english"],
                          "arabic": link["arabic"]})
            answers.append({"link_id": link_id, "english": link["english"],
                            "arabic": link["arabic"]})
        tasks.append({"task_id": task_id, "book": row["book"],
                      "chain_en": chain_en, "chain_ar": arabic_of.get(row["id"], ""),
                      "links": shown})
        key_rows.append({"task_id": task_id, "hadith": row["id"], "book": row["book"],
                         "answers": answers})

    # A control moves one link onto the English name beside the right one, which is the one
    # mistake the alignment can make on its own.
    planted = 0
    for task, key_row, (row, chain_en, links) in zip(tasks, key_rows, drawn):
        if planted >= args.controls or len(links) < 2:
            continue
        number = rng.randrange(len(links))
        other = links[(number + 1) % len(links)]
        if other["english"] == links[number]["english"]:
            continue
        task["links"][number]["english"] = other["english"]
        key_row["planted"] = task["links"][number]["link_id"]
        planted += 1

    copies = rng.sample(range(len(tasks)), min(args.controls, len(tasks)))
    for number, index in enumerate(copies):
        twin_id = f"en:twin{number:02d}"
        twin = json.loads(json.dumps(tasks[index]))
        twin["task_id"] = twin_id
        for link in twin["links"]:
            link["link_id"] = link["link_id"].replace(tasks[index]["task_id"], twin_id)
        tasks.append(twin)
        key_rows.append({"task_id": twin_id, "twin_of": tasks[index]["task_id"],
                         "book": twin["book"], "answers": []})

    rng.shuffle(tasks)
    questions = sum(len(k.get("answers") or []) for k in key_rows)
    print(f"  {len(tasks):5,}  chains drawn ({planted} with a link moved, "
          f"{len(copies)} drawn twice)")
    print(f"  {questions:5,}  links to judge")
    print(f"  {len(set(t['book'] for t in tasks)):5,}  books represented")
    batches = [tasks[i::args.batches] for i in range(args.batches)]
    print(f"  {len(batches):5,}  batches, largest "
          f"{max(len(json.dumps(b, ensure_ascii=False)) for b in batches):,} chars")
    if args.dry_run:
        return

    fingerprint = hashlib.sha256(
        json.dumps(sorted(t["task_id"] for t in tasks)).encode()).hexdigest()[:16]
    name = f"en-{questions}-{fingerprint}"
    run = f'{args.out_dir}/runs/{name}'
    os.makedirs(f'{run}/batches', exist_ok=True)
    os.makedirs(f'{run}/outputs', exist_ok=True)
    os.makedirs(f'{args.out_dir}/keys', exist_ok=True)
    for number, batch in enumerate(batches):
        write_json_atomic(f'{run}/batches/en_{number:02d}.json',
                          {"batch": f"en_{number:02d}.json", "tasks": batch}, indent=1)
    write_json_atomic(f'{run}/manifest.json',
                      {"run": name, "seed": args.seed, "chains": len(tasks),
                       "links": questions, "batches": len(batches),
                       "planted": planted, "twins": len(copies)}, indent=1)
    write_json_atomic(f'{args.out_dir}/keys/{name}.json', key_rows, indent=1)
    print(f"\nwrote {len(batches)} batches -> {run}/batches")
    print(f"key (not in the run) -> {args.out_dir}/keys/{name}.json")


if __name__ == "__main__":
    main()
