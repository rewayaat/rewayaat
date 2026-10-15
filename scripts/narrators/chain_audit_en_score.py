#!/usr/bin/env python3
"""Reads the auditors' answers on the English links and says how much of the alignment is right.

One number comes out of this that the Arabic audits cannot give: the share of English links
sitting on the name they were taken from. An English link is right when both halves are right
— the Arabic resolver chose the man, and the alignment carried him to the right words — so the
two measurements multiply, and that product is what a reader of the English site actually gets.

The controls say whether to believe it. A moved link is one we shifted onto the English name
beside the right one: an auditor who calls that `same` is not reading. A twin is the same
chain twice under two ids.

Usage:
    python3 scripts/narrators/chain_audit_en_score.py --run en-505-e7f9409515951083
    python3 scripts/narrators/chain_audit_en_score.py --run <name> --notes different
"""
import argparse
from collections import Counter, defaultdict
import json
import os

W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
AUDIT = f'{W}/tmp/narrators_audit/chain_en'
ARABIC_PRECISION = 95.3   # three rounds of chain audit, the latest chain-541


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--run", required=True)
    parser.add_argument("--audit-dir", default=AUDIT)
    parser.add_argument("--notes", default="", help="print the notes for one verdict")
    args = parser.parse_args()

    with open(f'{args.audit_dir}/keys/{args.run}.json') as handle:
        key = json.load(handle)
    asked, planted, twin_of = {}, set(), {}
    for row in key:
        if row.get("twin_of"):
            twin_of[row["task_id"]] = row["twin_of"]
        if row.get("planted"):
            planted.add(row["planted"])
        for answer in row.get("answers") or []:
            asked[answer["link_id"]] = dict(answer, book=row["book"])

    judged, missing = {}, []
    for number in range(64):
        batch = f'{args.audit_dir}/runs/{args.run}/batches/en_{number:02d}.json'
        out = f'{args.audit_dir}/runs/{args.run}/outputs/en_{number:02d}.json'
        if not os.path.exists(batch):
            break
        if not os.path.exists(out):
            missing.append(os.path.basename(batch))
            continue
        with open(out) as handle:
            for item in json.load(handle).get("judgements") or []:
                judged[item["link_id"]] = item
    if missing:
        print(f"  no answers yet for {', '.join(missing)}\n")

    caught = sum(1 for link in planted
                 if judged.get(link, {}).get("verdict") == "different")
    print(f"  moved links caught       {caught}/{len(planted)}")
    agreed = total = 0
    for twin, original in twin_of.items():
        for link_id, item in judged.items():
            if not link_id.startswith(twin + "."):
                continue
            mirror = link_id.replace(twin, original)
            if mirror in judged:
                total += 1
                agreed += judged[mirror]["verdict"] == item["verdict"]
    print(f"  twins answered the same  {agreed}/{total}")

    counts, per_book, notes = Counter(), defaultdict(Counter), []
    for link_id, answer in asked.items():
        if link_id in planted:
            continue
        item = judged.get(link_id)
        verdict = item["verdict"] if item else "unanswered"
        counts[verdict] += 1
        per_book[answer["book"]][verdict] += 1
        if item and args.notes and verdict == args.notes:
            notes.append((answer["book"], answer["english"], answer["arabic"],
                          item.get("note", "")))

    decided = counts["same"] + counts["different"]
    rate = 100 * counts["same"] / decided if decided else 0.0
    print(f"\n  {len(judged):,} links judged of {len(asked):,} asked\n")
    print(f"  the link sits on the name it was taken from:  {rate:5.1f}%")
    print(f"      {counts['same']:5,} same   {counts['different']:5,} different   "
          f"{counts['cannot_tell']:5,} cannot tell   {counts['unanswered']:5,} unanswered")

    print("\n  by book:")
    for book, held in sorted(per_book.items(), key=lambda kv: -sum(kv[1].values()))[:10]:
        good, bad = held["same"], held["different"]
        print(f"      {sum(held.values()):4,}  "
              f"{100 * good / (good + bad) if good + bad else 0:5.1f}%  {book}")

    if decided:
        # Both halves have to be right for a reader to land on the right man.
        print(f"\n  an English link is right when the resolver chose the right man and the "
              f"alignment\n  carried him to the right words: {rate:.1f}% × "
              f"{ARABIC_PRECISION}% = {rate * ARABIC_PRECISION / 100:.1f}%")

    for book, english, arabic, note in notes[:40]:
        print(f"\n  {book}\n      «{english}»  vs  «{arabic}»\n      {note}")


if __name__ == "__main__":
    main()
