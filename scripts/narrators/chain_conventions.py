#!/usr/bin/env python3
"""Collects the chain conventions the auditors named, so the resolver stops refusing them.

A `several` is the resolver saying the books do not choose between the men who hold a name.
Over two audit rounds the auditors answered `settled` to four fifths of those: the chain does
choose, by a convention of the book it stands in. «محمد بن يحيى» opening a chain in al-Kāfī is
al-ʿAṭṭār; «أحمد بن محمد» after him is Ibn ʿĪsā al-Ashʿarī; «محمد بن الحسن» at the head of one
of al-Ṣadūq's chains is Ibn al-Walīd. These are not our inventions — they are what the rijālī
literature says about reading isnāds, and eight auditors named them independently and agreed.

This turns those answers into a table the resolver can read. Two kinds of entry:

- `settles` — a name the resolver refused, and the man the auditors say the chain means.
- `corrects` — a link the resolver made and the auditors called wrong, with the right man.
  These are rarer and they matter more: a wrong link is worse than a missing one.

What is admitted is deliberately narrow. The auditors must agree — one dissent and the entry
is dropped — and the man they name must resolve to exactly one person in the books, by his own
heading. An entry is written against the book it was seen in; it is promoted to every book only
when auditors reached the same man in two different books, because most of these conventions
belong to a particular author's practice and not to the tradition at large.

Usage:
    python3 scripts/narrators/chain_conventions.py --harvest
    python3 scripts/narrators/chain_conventions.py --show 30
"""
import argparse
from collections import Counter, defaultdict
import glob
import json
import os
import sys

W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
sys.path.insert(0, f'{W}/scripts/narrators')
import imams  # noqa: E402
from chain_resolve import CLASSICAL, held_by, key, load_people  # noqa: E402
from identity import write_json_atomic  # noqa: E402

TMP = f'{W}/tmp'
AUDIT = f'{TMP}/narrators_audit/chain'
TABLE = f'{W}/scripts/data/narrators/chain_conventions.json'


def judgements(run_dir):
    """Every auditor's answers for one run, by link id."""
    answers = {}
    for path in sorted(glob.glob(f'{run_dir}/outputs/chain_*.json')):
        if path.endswith("_helper.py") or path.endswith("_build.py"):
            continue
        with open(path) as handle:
            try:
                body = json.load(handle)
            except json.JSONDecodeError:
                continue
        for item in body.get("judgements") or []:
            if item.get("link_id"):
                answers[item["link_id"]] = item
    return answers


def linked(run_dir, audit_dir):
    """link id → the man the resolver had already linked, from the run's key.

    A correction has to correct something. An auditor who calls a link wrong and then names
    the man we had linked all along has told us his own reading was the same as ours — the
    disagreement was with our display name, or with nothing. Written down as a correction it
    would entrench the link it looks like it overturns.
    """
    run = os.path.basename(run_dir.rstrip('/'))
    try:
        with open(f'{audit_dir}/keys/{run}.json') as handle:
            key = json.load(handle)
    except FileNotFoundError:
        return {}
    return {a["link_id"]: a.get("person_id", "")
            for row in key for a in row.get("answers") or []}


def asked(run_dir):
    """What each link showed the auditor: the book it stands in and the name in the chain."""
    shown = {}
    for path in sorted(glob.glob(f'{run_dir}/batches/chain_*.json')):
        with open(path) as handle:
            for task in json.load(handle).get("tasks") or []:
                for link in task.get("links") or []:
                    shown[link["link_id"]] = (task["book"], link["name"], link.get("we_say"))
    return shown


def whom(named, data):
    """The person an auditor's answer names, when exactly one man in the books answers to it."""
    folded = key(named)
    found = imams.identify(folded)
    if len(found) == 1:
        return found[0]
    held = held_by(folded, data)
    if len(held) == 1:
        return held[0]
    return ""


def harvest(runs, data, audit_dir):
    """Every (book, name) the auditors settled or corrected, with who said what."""
    votes = defaultdict(lambda: defaultdict(Counter))
    for run_dir in runs:
        answers, shown = judgements(run_dir), asked(run_dir)
        already = linked(run_dir, audit_dir)
        for link_id, item in answers.items():
            if link_id.startswith("chain:twin") or link_id not in shown:
                continue
            book, name, we_said = shown[link_id]
            verdict = item.get("verdict")
            kind = "settles" if verdict == "settled" else "corrects" if verdict == "wrong" else ""
            if not kind or not item.get("who"):
                continue
            person = whom(item["who"], data)
            if kind == "corrects" and person and person == already.get(link_id):
                continue  # he named the man we had linked; there is nothing to correct
            votes[kind][(book, key(name))][person or f"?{item['who']}"] += 1
        # a dissent is a vote against: an auditor who upheld the refusal, or the link
        for link_id, item in answers.items():
            if link_id.startswith("chain:twin") or link_id not in shown:
                continue
            book, name, we_said = shown[link_id]
            if item.get("verdict") == "ambiguous":
                votes["settles"][(book, key(name))]["-"] += 1
            if item.get("verdict") == "right":
                votes["corrects"][(book, key(name))]["-"] += 1
    return votes


def agreed(votes, by_id):
    """Keep only what the auditors agreed on and what resolves to one man."""
    table, dropped = defaultdict(dict), Counter()
    for kind, names in votes.items():
        for (book, name), counted in names.items():
            if set(counted) == {"-"}:
                # nobody proposed anything here; the upheld answers are not a disagreement
                continue
            if counted.get("-"):
                dropped["an auditor disagreed"] += 1
                continue
            people = [p for p in counted if not p.startswith("?")]
            if len(counted) > len(people):
                dropped["the man named is not one person in the books"] += 1
                continue
            if len(people) != 1:
                dropped["the auditors named different men"] += 1
                continue
            person = people[0]
            voices = counted[person]
            # A correction generalises one reading of one chain to every occurrence of that
            # name in that book, which is a long step from a single voice. Those are written
            # down and left unapplied rather than thrown away.
            applied = kind != "corrects" or voices >= 2
            if kind == "corrects" and len(imams.identify(name)) > 1:
                # Which Imam «أبي جعفر» or «محمد بن علي» means is settled by the layer of the
                # man receiving from him, so one chain's reading is not the book's rule. The
                # correction is recorded and left for the resolver to earn chain by chain.
                applied = False
            table[kind][f"{book}\t{name}"] = {"person_id": person, "voices": voices,
                                               "applied": applied,
                                               "display": display(person, by_id)}
    return table, dropped


def display(person, by_id):
    if person in by_id:
        return by_id[person]["display_ar"]
    return imams.BY_ID[person]["name_ar"] if person in imams.BY_ID else person


def promote(table):
    """A convention seen in two books is the tradition's, not one author's.

    With one exception, which the third audit paid for: a kunya the books share between two
    Imams is never promoted. «أبي جعفر» is al-Bāqir or al-Jawād and «أبي الحسن» is one of
    three, and which of them a chain means is settled by the layer of the man receiving from
    him — a property of that chain, not of the corpus. Promoted to every book, «أبي جعفر» read
    as al-Bāqir put him above al-Riḍā in ʿUyūn, where the Imam is al-Riḍā's own father, and
    answered six bare kunyas in Kitāb al-Muʾmin that nothing in their chains places. The books
    whose auditors did vote on the kunya keep their entry; it is the promotion that was wrong.
    """
    everywhere = {}
    seen = defaultdict(Counter)
    for row in table.get("settles", {}):
        book, name = row.split("\t")
        seen[name][table["settles"][row]["person_id"]] += 1
    for name, people in seen.items():
        if len(imams.identify(name)) > 1:
            continue
        if len(people) == 1 and list(people.values())[0] >= 2:
            everywhere[name] = list(people)[0]
    return everywhere


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--audit-dir", default=AUDIT)
    parser.add_argument("--people", default=f'{TMP}/narrators_chains/people_index.json')
    parser.add_argument("--out", default=TABLE)
    parser.add_argument("--harvest", action="store_true", help="write the table")
    parser.add_argument("--show", type=int, default=0, help="print N entries")
    args = parser.parse_args()

    data = load_people(args.people, CLASSICAL)
    runs = sorted(glob.glob(f'{args.audit_dir}/runs/chain-*'))
    print(f"  {len(runs)} audit runs read")
    votes = harvest(runs, data, args.audit_dir)
    table, dropped = agreed(votes, data["by_id"])
    everywhere = promote(table)

    for kind in ("settles", "corrects"):
        print(f"  {len(table.get(kind, {})):5,}  {kind}")
    print(f"  {len(everywhere):5,}  hold in every book (the same man in two or more)")
    for reason, count in dropped.most_common():
        print(f"  {count:5,}  dropped: {reason}")

    if args.show:
        for kind in ("settles", "corrects"):
            print(f"\n  {kind}:")
            for row, entry in sorted(table.get(kind, {}).items(),
                                     key=lambda kv: -kv[1]["voices"])[:args.show]:
                book, name = row.split("\t")
                where = "everywhere" if name in everywhere else book[:20]
                print(f"      {entry['voices']}×  {where:20s} «{name[:26]:26s}» -> "
                      f"{entry['display'][:34]}")

    if not args.harvest:
        return
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    write_json_atomic(args.out, {
        "note": "Chain conventions named by the audit's auditors and agreed by them. "
                "Keys are '<book>\\t<folded name>'; `everywhere` is keyed by the name alone.",
        "settles": table.get("settles", {}),
        "corrects": table.get("corrects", {}),
        "everywhere": everywhere,
    }, indent=1)
    print(f"\nwrote {args.out}")


if __name__ == "__main__":
    main()
