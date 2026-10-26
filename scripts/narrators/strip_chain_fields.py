#!/usr/bin/env python3
"""Takes the chain fields back off the narrations. The undo for a chain-link load.

Loading the links onto a serving index is the one step of the narrator rollout that writes
to documents the site already serves. It is additive — three fields appear on a narration
and nothing else changes — so undoing it is removing those three fields, and this is the
script that does it, by `_update_by_query` with a painless script rather than by restoring
the whole index.

Counted before and after. A run that removes nothing says so instead of claiming success,
and `--dry-run` reports how many narrations carry each field without touching anything.

The snapshot remains the heavier rollback, for the case where something other than these
fields has gone wrong.

Usage:
    python3 scripts/narrators/strip_chain_fields.py --es-host http://localhost:9201 --dry-run
    python3 scripts/narrators/strip_chain_fields.py --es-host http://localhost:9201 --apply
"""
import argparse
import json
import sys
import urllib.request

#: Everything a chain load writes, and nothing else.
FIELDS = ("chain_links", "chain_links_en", "chain_people")


def _call(host, path, body=None, method=None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(host.rstrip("/") + path, data=data,
                                     headers={"Content-Type": "application/json"},
                                     method=method)
    with urllib.request.urlopen(request) as answer:
        return json.load(answer)


def carrying(host, index, field):
    return _call(host, f"/{index}/_count",
                 {"query": {"exists": {"field": field}}})["count"]


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--es-host", default="http://localhost:9200")
    parser.add_argument("--index", default="rewayaat_hadith")
    parser.add_argument("--apply", action="store_true", help="remove the fields")
    parser.add_argument("--dry-run", action="store_true", help="count and stop")
    args = parser.parse_args()

    before = {f: carrying(args.es_host, args.index, f) for f in FIELDS}
    for field, count in before.items():
        print(f"  {count:7,}  narrations carry {field}")
    if not any(before.values()):
        print("nothing to remove")
        return
    if not args.apply or args.dry_run:
        print("nothing written — pass --apply")
        return

    # One pass: a narration carrying any of the three is rewritten without all three.
    answer = _call(args.es_host,
                   f"/{args.index}/_update_by_query?conflicts=proceed&refresh=true"
                   f"&wait_for_completion=true&requests_per_second=500",
                   {"query": {"bool": {"should": [{"exists": {"field": f}} for f in FIELDS],
                                       "minimum_should_match": 1}},
                    "script": {"lang": "painless",
                               "source": "for (f in params.fields) { "
                                         "ctx._source.remove(f); }",
                               "params": {"fields": list(FIELDS)}}})
    print(f"{answer.get('updated', 0):,} narrations rewritten, "
          f"{answer.get('version_conflicts', 0)} conflicts, "
          f"{len(answer.get('failures') or [])} failures")
    if answer.get("failures"):
        print(json.dumps(answer["failures"][:2], ensure_ascii=False)[:600], file=sys.stderr)

    after = {f: carrying(args.es_host, args.index, f) for f in FIELDS}
    for field, count in after.items():
        print(f"  {count:7,}  narrations still carry {field}")
    if any(after.values()):
        sys.exit("the fields are not all gone; look before running anything else")


if __name__ == "__main__":
    main()
