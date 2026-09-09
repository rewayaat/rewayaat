#!/usr/bin/env python3
"""Builds a new narrations index and moves an alias onto it.

The application reads the index named by REWAYAAT_INDEX. Pointing that at an
alias rather than at an index is what makes a mapping change routine: analyzers
cannot be altered in place, so every change means a reindex, and without an
alias every reindex means an application deploy timed against it.

The alias carries writes as well as reads. Elasticsearch only accepts that while
the alias resolves to exactly one index, so this script always moves it and
never adds a second - see move_alias.

Nothing is destroyed. The previous index is left in place and the rollback is
one call, printed on completion.

    # build and verify, but do not touch the alias
    python3 scripts/search/migrate_index.py --host http://localhost:9200 --dry-run

    # build, verify, then move the alias onto the new index
    python3 scripts/search/migrate_index.py --host http://localhost:9200

    # afterwards, point the deployment at the alias
    REWAYAAT_INDEX=rewayaat_hadith
"""
import argparse
import datetime
import json
import os
import sys
import urllib.error
import urllib.request

ALIAS = "rewayaat_hadith"
MAPPING = os.path.join(os.path.dirname(os.path.abspath(__file__)), "v2_mapping.json")


def call(host, method, path, body=None, timeout=3600):
    req = urllib.request.Request(
        host + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json"},
        method=method,
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{method} {path} failed: {e.code}\n{e.read().decode()[:800]}")


def count(host, index):
    r = call(host, "GET", f"/{index}/_count")
    return r["count"]


def resolve_alias(host, alias):
    """The indices an alias currently points at, or [] if it does not exist."""
    try:
        r = call(host, "GET", f"/_alias/{alias}", timeout=60)
        return sorted(r.keys())
    except SystemExit:
        return []


def move_alias(host, alias, new_index, old_indices):
    """Repoint the alias in one atomic action.

    Removing and adding in a single _aliases call means no instant exists in
    which the alias is missing or, worse, resolves to two indices - which would
    make it unwritable and fail every hadith edit.
    """
    actions = [{"remove": {"index": i, "alias": alias}} for i in old_indices]
    actions.append({"add": {"index": new_index, "alias": alias}})
    return call(host, "POST", "/_aliases", {"actions": actions}, timeout=120)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="http://localhost:9200")
    ap.add_argument("--source", default="rewayaat_updated",
                    help="index to read documents from")
    ap.add_argument("--target", default=None,
                    help="index to create (default: rewayaat_hadith_<today>)")
    ap.add_argument("--alias", default=ALIAS)
    ap.add_argument("--dry-run", action="store_true",
                    help="build and verify the new index but leave the alias alone")
    ap.add_argument("--promote", action="store_true",
                    help="move the alias onto an index built by an earlier --dry-run, "
                         "without rebuilding it")
    args = ap.parse_args()
    if args.dry_run and args.promote:
        raise SystemExit("--dry-run and --promote are opposites; pass one")

    host = args.host.rstrip("/")
    target = args.target or f"{args.alias}_{datetime.date.today():%Y%m%d}"

    info = call(host, "GET", "/", timeout=30)
    print(f"cluster : {info['cluster_name']} ({info['cluster_uuid']})")
    print(f"source  : {args.source}")
    print(f"target  : {target}")
    print(f"alias   : {args.alias}")
    print()

    # An alias cannot share a name with an index. Say so plainly rather than
    # letting Elasticsearch reject the _aliases call further down.
    existing = call(host, "GET", "/_cat/indices?h=index&format=json", timeout=60)
    names = {i["index"] for i in existing}
    if args.alias in names:
        raise SystemExit(
            f"'{args.alias}' already exists as an index, so it cannot become an alias.\n"
            f"Pick another alias name, or reindex that index away first."
        )

    source_count = count(host, args.source)
    print(f"source holds {source_count:,} documents")

    if args.promote:
        # The index was built and checked by an earlier --dry-run and an application
        # has been pointed at it since. Rebuilding it here would discard exactly the
        # thing that was verified, so only the counts are re-checked.
        if target not in names:
            raise SystemExit(f"index '{target}' does not exist; run without --promote to build it")
        print(f"promoting existing {target} (not rebuilding)")
    else:
        if target in names:
            raise SystemExit(
                f"target index '{target}' already exists.\n"
                f"If an earlier --dry-run built it and you have verified it, promote it with:\n"
                f"  --promote --target {target}"
            )
        with open(MAPPING) as f:
            mapping = json.load(f)

        print(f"creating {target} ...")
        call(host, "PUT", f"/{target}", mapping, timeout=120)

        print("reindexing ...")
        r = call(host, "POST", "/_reindex?wait_for_completion=true&refresh=true",
                 {"source": {"index": args.source}, "dest": {"index": target}})
        failures = r.get("failures") or []
        print(f"  created {r.get('created'):,}, failures {len(failures)}")
        if failures:
            print(json.dumps(failures[:2], ensure_ascii=False, indent=1)[:900])
            raise SystemExit("reindex reported failures; the alias has not been moved")

    target_count = count(host, target)
    print(f"target holds {target_count:,} documents")
    if target_count != source_count:
        raise SystemExit(
            f"count mismatch: source {source_count:,} vs target {target_count:,}. "
            f"The alias has not been moved and {target} is left for inspection."
        )

    current = resolve_alias(host, args.alias)
    print(f"alias currently points at: {current or '(does not exist yet)'}")

    if args.dry_run:
        print()
        print(f"--dry-run: {target} is built and verified; the alias was not touched.")
        print(f"Point an application at it directly with REWAYAAT_INDEX={target} to try it,")
        print(f"then re-run without --dry-run to move the alias.")
        return 0

    move_alias(host, args.alias, target, current)
    print(f"alias now points at: {resolve_alias(host, args.alias)}")

    print()
    print("Done. The application still reads whatever REWAYAAT_INDEX names, so set")
    print(f"    REWAYAAT_INDEX={args.alias}")
    print("in k8s/deployment.yaml and roll it out.")
    print()
    if current:
        print("To roll back, move the alias to the previous index:")
        print(f"  curl -X POST {host}/_aliases -H 'Content-Type: application/json' -d '"
              + json.dumps({"actions": [
                  {"remove": {"index": target, "alias": args.alias}},
                  {"add": {"index": current[0], "alias": args.alias}}]}) + "'")
    else:
        print(f"To roll back, set REWAYAAT_INDEX={args.source} and roll out.")
    print(f"{args.source} is untouched and still holds {source_count:,} documents.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
