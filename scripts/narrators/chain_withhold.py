#!/usr/bin/env python3
"""Withholds a new link that chain_check finds impossible, and keeps the rest of a resolution.

A re-resolution against a fresher people index moves links: most of the moves follow merges
and better names, but a few put a man on the wrong side of his own teacher («أحمد بن محمد عن
محمد بن علي» read as al-Barqī receiving from Ibn Maḥbūb, his own student) or before a shaykh
he could not have met. chain_check flags those pairs. This takes the flagged pairs that the
served resolution did not already have, and in each such chain turns the link that is new —
the one the served resolution did not make — back into an unlinked name with its candidate,
so a reader sees «several» rather than a link the books contradict.

Usage:
    python3 scripts/narrators/chain_check.py --resolved NEW --people NEW_PEOPLE --flagged-out new.json
    python3 scripts/narrators/chain_check.py --flagged-out old.json
    python3 scripts/narrators/chain_withhold.py --served tmp/narrators_chains/resolved.jsonl \\
        --new NEW --old-flags old.json --new-flags new.json --out OUT
"""
import argparse
import json

LINKED = ("one", "chosen", "convention")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--served", required=True)
    parser.add_argument("--new", required=True)
    parser.add_argument("--old-flags", required=True)
    parser.add_argument("--new-flags", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    old = {(f["id"], f["check"], f["student"], f["teacher"]) for f in json.load(open(args.old_flags))}
    flagged = {}
    for f in json.load(open(args.new_flags)):
        if (f["id"], f["check"], f["student"], f["teacher"]) not in old:
            flagged.setdefault(f["id"], set()).update({f["student"], f["teacher"]})
    served = {}
    with open(args.served) as handle:
        for line in handle:
            row = json.loads(line)
            if row["id"] in flagged:
                served[row["id"]] = {(m["start"], m["end"]): (m.get("person_id") or m.get("imam_id"))
                                     for m in row["mentions"] if m.get("outcome") in LINKED}
    withheld = 0
    with open(args.new) as src, open(args.out, "w") as dst:
        for line in src:
            row = json.loads(line)
            if row["id"] in flagged:
                for m in row["mentions"]:
                    person = m.get("person_id")
                    if (m.get("outcome") in LINKED and person in flagged[row["id"]]
                            and served[row["id"]].get((m["start"], m["end"])) != person):
                        m["outcome"], m["candidates"] = "several", [person]
                        m["because"] = ("withheld: chain_check finds the books contradict the "
                                        "pair this link would make")
                        m.pop("person_id", None)
                        withheld += 1
            dst.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"{len(flagged):,} chains with a new contradiction, {withheld:,} new links withheld")


if __name__ == "__main__":
    main()
