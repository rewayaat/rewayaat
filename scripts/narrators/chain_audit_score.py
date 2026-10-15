#!/usr/bin/env python3
"""Reads the auditors' answers and says how much of the chain linking is right.

Three numbers come out of a run, and they answer different questions:

- the precision of `one` — links made because only one man holds the name
- the precision of `chosen` — links made because a book shows him beside his neighbour
- the precision of `convention` — links made because the book the chain stands in reads that
  name as one man. These come from what earlier auditors said, so they are the claims least
  entitled to be believed on our own word, and they are shown to the auditor as any other
  link made on evidence is shown: nothing in a batch says which kind a link is.
- the share of `several` the auditors call `settled` — refusals we could have decided, each
  one carrying the convention that decides it, which is the next thing to encode

Then the controls, which say whether to believe any of it. A planted link is a link we swapped
for a different man: an auditor who calls it `right` is not reading. A twin is the same chain
twice under two ids: an auditor who answers differently is guessing.

The corpus-wide estimate weights each outcome's precision by how many links of that kind the
resolver actually made, so it is an estimate about the site, not about the sample.

Usage:
    python3 scripts/narrators/chain_audit_score.py --run chain-862-c49febdf3db53cc4
    python3 scripts/narrators/chain_audit_score.py --run <name> --notes settled
"""
import argparse
from collections import Counter, defaultdict
import glob
import json
import os
import sys

W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
TMP = f'{W}/tmp'
AUDIT = f'{TMP}/narrators_audit/chain'

# What the resolver made of the whole corpus, to weight the sample by (chain_resolve.py).
CORPUS = {"one": 62173, "chosen": 7536, "convention": 18219, "several": 6391}
RIGHT = {"one": "right", "chosen": "right", "convention": "right", "several": "ambiguous"}
WRONG = {"one": "wrong", "chosen": "wrong", "convention": "wrong", "several": "settled"}


def load(path):
    with open(path) as handle:
        return json.load(handle)


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--run", required=True)
    parser.add_argument("--audit-dir", default=AUDIT)
    parser.add_argument("--notes", default="", help="print the notes for one verdict")
    args = parser.parse_args()

    key = load(f'{args.audit_dir}/keys/{args.run}.json')
    answer_of, planted, twin_of = {}, {}, {}
    for row in key:
        if row.get("twin_of"):
            twin_of[row["task_id"]] = row["twin_of"]
        for answer in row.get("answers") or []:
            answer_of[answer["link_id"]] = dict(answer, book=row["book"])
        if row.get("planted"):
            planted[row["planted"]] = True

    judged, missing_files = {}, []
    for number in range(64):
        path = f'{args.audit_dir}/runs/{args.run}/outputs/chain_{number:02d}.json'
        batch_path = f'{args.audit_dir}/runs/{args.run}/batches/chain_{number:02d}.json'
        if not os.path.exists(batch_path):
            break
        if not os.path.exists(path):
            missing_files.append(os.path.basename(batch_path))
            continue
        for item in load(path).get("judgements") or []:
            judged[item["link_id"]] = item
    if missing_files:
        print(f"  no answers yet for {', '.join(missing_files)}\n")

    # Controls first: a measurement is worth nothing until they pass.
    caught = sum(1 for link_id in planted
                 if judged.get(link_id, {}).get("verdict") == "wrong")
    print(f"  planted controls caught  {caught}/{len(planted)}")
    agreed = total_twins = 0
    for twin, original in twin_of.items():
        for link_id, item in judged.items():
            if not link_id.startswith(twin + "."):
                continue
            mirror = item["link_id"].replace(twin, original)
            if mirror in judged:
                total_twins += 1
                agreed += judged[mirror]["verdict"] == item["verdict"]
    print(f"  twins answered the same  {agreed}/{total_twins}")

    verdicts = defaultdict(Counter)
    per_book, notes = defaultdict(Counter), []
    for link_id, answer in answer_of.items():
        if link_id.startswith("chain:twin") or link_id in planted:
            continue
        item = judged.get(link_id)
        verdict = item["verdict"] if item else "unanswered"
        verdicts[answer["outcome"]][verdict] += 1
        verdicts[("kind", answer["kind"])][verdict] += 1
        per_book[answer["book"]][verdict] += 1
        if item and args.notes and verdict == args.notes:
            notes.append((answer["book"], link_id, item.get("who", ""), item.get("note", "")))

    print(f"\n  {len(judged):,} links judged of {len(answer_of):,} asked\n")
    estimate_right = estimate_total = 0.0
    for outcome in ("one", "chosen", "convention", "several"):
        counts = verdicts[outcome]
        held = sum(counts.values())
        if not held:
            continue
        right, wrong = counts[RIGHT[outcome]], counts[WRONG[outcome]]
        decided = right + wrong
        rate = 100 * right / decided if decided else 0.0
        print(f"  {outcome:8s} {held:5,} asked   {rate:5.1f}% {RIGHT[outcome]} "
              f"({right:,} {RIGHT[outcome]}, {wrong:,} {WRONG[outcome]}, "
              f"{counts['cannot_tell']:,} cannot tell, {counts['unanswered']:,} unanswered)")
        if decided and outcome in CORPUS:
            estimate_right += CORPUS[outcome] * right / decided
            estimate_total += CORPUS[outcome]

    print("\n  by what the mention is:")
    for kind in ("name", "imam", "relative"):
        counts = verdicts[("kind", kind)]
        held = sum(counts.values())
        if not held:
            continue
        good = counts["right"] + counts["ambiguous"]
        bad = counts["wrong"] + counts["settled"]
        print(f"      {kind:9s} {held:5,}   {100 * good / (good + bad) if good + bad else 0:5.1f}%"
              f" upheld ({good} upheld, {bad} not, {counts['cannot_tell']} cannot tell)")

    print("\n  by book:")
    for book, counts in sorted(per_book.items(), key=lambda kv: -sum(kv[1].values()))[:10]:
        good = counts["right"] + counts["ambiguous"]
        bad = counts["wrong"] + counts["settled"]
        held = sum(counts.values())
        print(f"      {held:5,}  {100 * good / (good + bad) if good + bad else 0:5.1f}%  {book}")

    if estimate_total:
        print(f"\n  weighting the sample by what the resolver made of the corpus: "
              f"{100 * estimate_right / estimate_total:.1f}% of "
              f"{int(estimate_total):,} claims stand")

    for book, link_id, who, note in notes[:40]:
        print(f"\n  {link_id}  {book}\n      {who}\n      {note}")


if __name__ == "__main__":
    main()
