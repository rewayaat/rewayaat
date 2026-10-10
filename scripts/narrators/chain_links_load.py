#!/usr/bin/env python3
"""Puts the resolved chain links on the narrations themselves, for the page to render.

One field per narration, `chain_links`: the spans of its isnād that name a man we can link,
each as {s, e, t, p} — where the name starts, where it ends, the text it covers, and whose
page it points at. `ChainLinks.render` reads them back when the hadith card is built.

The text is carried with the offsets on purpose. The spans were measured against a chain
exported earlier; the page segments the chain again when it renders. If anything between the
two readings has moved, an offset alone would slice a name in half, so a span whose covered
text no longer matches is relocated when the name occurs exactly once in the served text, and
dropped otherwise. The same check runs again in Java, since the index outlives this script.

Usage:
    python3 scripts/narrators/chain_links_load.py --dry-run
    python3 scripts/narrators/chain_links_load.py --live --index rewayaat_hadith
"""
import argparse
from collections import Counter
import json
import os
import sys

W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
TMP = f'{W}/tmp'
LINKED = ("one", "chosen")


def served_chains(path):
    """The chain text as the index the site reads actually holds it."""
    chains = {}
    with open(path) as handle:
        for line in handle:
            row = json.loads(line)
            chains[row["id"]] = row["chain_ar"]
    return chains


def place(span, text, chain, counts):
    """Where this name sits in the served chain, or nothing if it cannot be found once."""
    start, end = span
    if chain[start:end] == text:
        return start, end
    found = chain.find(text)
    if found < 0:
        counts["name gone from the served text"] += 1
        return None
    if chain.find(text, found + 1) >= 0:
        counts["name occurs more than once, offsets unsafe"] += 1
        return None
    counts["name found at a new offset"] += 1
    return found, found + len(text)


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--resolved", default=f'{TMP}/narrators_chains/resolved.jsonl')
    parser.add_argument("--served", default=f'{TMP}/narrators_chains/chains_served.jsonl')
    parser.add_argument("--index", default="rewayaat_hadith")
    parser.add_argument("--es-host", default="http://localhost:9200")
    parser.add_argument("--live", action="store_true", help="write the field")
    parser.add_argument("--dry-run", action="store_true", help="count, write nothing")
    args = parser.parse_args()

    chains = served_chains(args.served)
    print(f"  {len(chains):7,}  narrations in the index the site reads")

    counts, updates = Counter(), []
    with open(args.resolved) as handle:
        for line in handle:
            row = json.loads(line)
            chain = chains.get(row["id"])
            if chain is None:
                counts["narration not in the served index"] += 1
                continue
            links = []
            for mention in row["mentions"]:
                if mention["outcome"] not in LINKED:
                    continue
                person = mention.get("person_id") or mention.get("imam_id")
                if not person:
                    continue
                counts["links to place"] += 1
                where = place((mention["start"], mention["end"]), mention["text"], chain, counts)
                if not where:
                    continue
                links.append({"s": where[0], "e": where[1],
                              "t": chain[where[0]:where[1]], "p": person})
            if links:
                updates.append({"_id": row["id"], "chain_links": links})
                counts["links placed"] += len(links)
                counts["narrations with at least one link"] += 1
            else:
                counts["narrations with no link"] += 1

    for label, count in counts.most_common():
        print(f"  {count:7,}  {label}")
    if args.dry_run or not args.live:
        return

    from elasticsearch import Elasticsearch, helpers
    es = Elasticsearch(args.es_host, request_timeout=120)
    loaded, failed = helpers.bulk(
        es, ({"_op_type": "update", "_index": args.index, "_id": u["_id"],
              "doc": {"chain_links": u["chain_links"]}} for u in updates),
        chunk_size=500, raise_on_error=False)
    es.indices.refresh(index=args.index)
    print(f"\nwrote chain_links on {loaded:,} narrations in {args.index}"
          + (f"; {len(failed)} failed" if failed else ""))
    if failed:
        print(json.dumps(failed[:2], ensure_ascii=False)[:600])


if __name__ == "__main__":
    main()
