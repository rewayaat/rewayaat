#!/usr/bin/env python3
"""Reads the auditors' answers and says how much of what a narrator page states is in the books.

Three numbers, one per field, and they answer a question no earlier audit asked. The identity
audits say we have the right man; the chain audits say a link points at him. This says whether
the facts printed beside his name — where he lived, when he died, whose companion he was — are
in the passage they were read from.

`not_in_passage` is the verdict that matters. A contradiction is a misreading and rare; a fact
whose own passage does not contain it is a claim the page cannot support, and that is the
failure this was built to count.

The controls come first. A planted value is one swapped for another man's: an auditor who
calls it supported is not reading the passage. A twin is the same statement twice.

Usage:
    python3 scripts/narrators/field_audit_score.py --run fields-490-9e2ff34f434f1180
    python3 scripts/narrators/field_audit_score.py --run <name> --notes not_in_passage
"""
import argparse
from collections import Counter, defaultdict
import json
import os

W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
AUDIT = f'{W}/tmp/narrators_audit/fields'
# What the corpus prints, to weight the sample by: statements that reached a page.
CORPUS = {"city_or_tribe": 2969, "death_years": 217, "generations": 7051,
          "reliability_grades": 4773, "narrated_from": 3232, "narrated_to": 5223,
          "titles": 4690, "kunyahs_arabic": 2668}


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--run", required=True)
    parser.add_argument("--audit-dir", default=AUDIT)
    parser.add_argument("--notes", default="")
    args = parser.parse_args()

    with open(f'{args.audit_dir}/keys/{args.run}.json') as handle:
        key = json.load(handle)
    asked, planted, twin_of = {}, set(), {}
    for row in key:
        if row.get("twin_of"):
            twin_of[row["task_id"]] = row["twin_of"]
            continue
        if row.get("planted"):
            planted.add(row["task_id"])
        asked[row["task_id"]] = row

    shown = {}
    for number in range(64):
        batch = f'{args.audit_dir}/runs/{args.run}/batches/fields_{number:02d}.json'
        if not os.path.exists(batch):
            break
        with open(batch) as handle:
            for task in json.load(handle).get("tasks") or []:
                shown[task["task_id"]] = task

    judged, missing = {}, []
    for number in range(64):
        batch = f'{args.audit_dir}/runs/{args.run}/batches/fields_{number:02d}.json'
        out = f'{args.audit_dir}/runs/{args.run}/outputs/fields_{number:02d}.json'
        if not os.path.exists(batch):
            break
        if not os.path.exists(out):
            missing.append(os.path.basename(batch))
            continue
        with open(out) as handle:
            for item in json.load(handle).get("judgements") or []:
                judged[item["task_id"]] = item
    if missing:
        print(f"  no answers yet for {', '.join(missing)}\n")

    caught = sum(1 for task in planted
                 if judged.get(task, {}).get("verdict") in ("not_in_passage", "contradicted"))
    print(f"  planted values caught    {caught}/{len(planted)}")
    agreed = total = 0
    for twin, original in twin_of.items():
        if twin in judged and original in judged:
            total += 1
            agreed += judged[twin]["verdict"] == judged[original]["verdict"]
    print(f"  twins answered the same  {agreed}/{total}")

    by_field, notes = defaultdict(Counter), []
    for task_id, row in asked.items():
        if task_id in planted:
            continue
        item = judged.get(task_id)
        verdict = item["verdict"] if item else "unanswered"
        by_field[row["field"]][verdict] += 1
        if item and args.notes and verdict == args.notes:
            notes.append((row["field"], shown.get(task_id, {}).get("book", ""),
                          row.get("value"), item.get("note", "")))

    print(f"\n  {len(judged):,} statements judged of {len(asked):,} asked\n")
    right = whole = 0.0
    for field in CORPUS:
        counts = by_field[field]
        held = sum(counts.values())
        if not held:
            continue
        decided = held - counts["unanswered"]
        rate = 100 * counts["supported"] / decided if decided else 0.0
        print(f"  {field:18s} {held:4,} asked   {rate:5.1f}% supported "
              f"({counts['supported']:,} supported, {counts['contradicted']:,} contradicted, "
              f"{counts['not_in_passage']:,} not in the passage, "
              f"{counts['unanswered']:,} unanswered)")
        if decided and field in CORPUS:
            right += CORPUS[field] * counts["supported"] / decided
            whole += CORPUS[field]
    if whole:
        print(f"\n  weighting each field by how many statements of it the corpus prints: "
              f"{100 * right / whole:.1f}% of {int(whole):,} stand")

    for field, book, value, note in notes[:40]:
        print(f"\n  {field}  «{value}»  from {book}\n      {note}")


if __name__ == "__main__":
    main()
