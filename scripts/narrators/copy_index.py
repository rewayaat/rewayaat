#!/usr/bin/env python3
"""Copies an index from one cluster to another, mapping first, documents after.

The narrator index is built on a workstation and has to arrive on the cluster that serves
the site. Rebuilding it there would mean running the whole pipeline against production and
trusting that it lands on the same answers; copying what has already been verified is the
same bytes, and it takes a minute instead of eleven.

Only what the copy needs is carried over: the mapping as it stands, and the two settings a
new index actually wants — the analyzers the mapping names, and the shard count. Replicas
are set for the target rather than copied, because a one-node workstation runs without them
and a two-node cluster should not.

Nothing is overwritten. If the target already holds the index the copy refuses, so the only
way to replace one is to delete it deliberately — which is also the rollback: this writes a
new index and nothing else, so `DELETE /<index>` on the target undoes it entirely.

Usage:
    python3 scripts/narrators/copy_index.py --index rewayaat_narrators \
        --from http://localhost:9200 --to http://localhost:9201 --dry-run
    python3 scripts/narrators/copy_index.py --index rewayaat_narrators \
        --from http://localhost:9200 --to http://localhost:9201 --replicas 1
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request

#: Settings a fresh index wants; everything else ES fills in or refuses to accept.
KEPT_SETTINGS = ("analysis", "number_of_shards", "max_result_window")


def _call(host, path, body=None, method=None, ndjson=False):
    data = None
    headers = {}
    if body is not None:
        if ndjson:
            data = body.encode()
            headers["Content-Type"] = "application/x-ndjson"
        else:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
    request = urllib.request.Request(host.rstrip("/") + path, data=data,
                                     headers=headers, method=method)
    with urllib.request.urlopen(request) as answer:
        return json.load(answer)


def _exists(host, index):
    try:
        _call(host, f"/{index}")
        return True
    except urllib.error.HTTPError as refused:
        if refused.code == 404:
            return False
        raise


def source_shape(host, index):
    """The mapping and the settings worth carrying, read from whichever index the alias names."""
    mapping = _call(host, f"/{index}/_mapping")
    settings = _call(host, f"/{index}/_settings")
    real = next(iter(mapping))
    kept = {k: v for k, v in settings[real]["settings"]["index"].items()
            if k in KEPT_SETTINGS}
    return real, mapping[real]["mappings"], kept


def copy(source, target, index, replicas, batch, pause, dry_run=False):
    real, mappings, settings = source_shape(source, index)
    total = _call(source, f"/{index}/_count")["count"]
    fields = len(mappings.get("properties") or {})
    print(f"{index}: {total:,} documents, {fields} top-level fields, "
          f"from {real} on {source}")
    if _exists(target, index):
        sys.exit(f"{target} already holds {index}; delete it first if you mean to replace it")
    if dry_run:
        print("nothing written — drop --dry-run to copy")
        return 0

    settings["number_of_replicas"] = replicas
    _call(target, f"/{index}", {"settings": {"index": settings}, "mappings": mappings},
          method="PUT")
    print(f"created {index} on {target} with {replicas} replica(s)")

    sent = 0
    page = _call(source, f"/{index}/_search?scroll=5m",
                 {"size": batch, "query": {"match_all": {}}})
    scroll = page.get("_scroll_id")
    try:
        while page["hits"]["hits"]:
            lines = []
            for hit in page["hits"]["hits"]:
                lines.append(json.dumps({"index": {"_index": index, "_id": hit["_id"]}}))
                lines.append(json.dumps(hit["_source"], ensure_ascii=False))
            answer = _call(target, "/_bulk", "\n".join(lines) + "\n", ndjson=True)
            failed = [item for item in answer.get("items", [])
                      if item.get("index", {}).get("error")]
            if failed:
                print(json.dumps(failed[0], ensure_ascii=False)[:400], file=sys.stderr)
                sys.exit(f"{len(failed)} documents of this batch were refused; stopping "
                         f"with {sent:,} written. DELETE /{index} on the target to undo")
            sent += len(page["hits"]["hits"])
            print(f"  {sent:,}/{total:,}", end="\r", file=sys.stderr)
            # The target serves live traffic on a small heap. A pause between batches keeps
            # this a background trickle rather than a load test.
            time.sleep(pause)
            page = _call(source, "/_search/scroll", {"scroll": "5m", "scroll_id": scroll})
    finally:
        if scroll:
            try:
                _call(source, "/_search/scroll", {"scroll_id": scroll}, method="DELETE")
            except Exception:  # noqa: BLE001 - a scroll that times out is not a failure
                pass
    print(f"\n{sent:,} documents written")
    _call(target, f"/{index}/_refresh", method="POST")
    landed = _call(target, f"/{index}/_count")["count"]
    print(f"{landed:,} documents in {index} on the target "
          f"({'matches' if landed == total else 'DOES NOT MATCH'} the source)")
    return 0 if landed == total else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--index", required=True)
    parser.add_argument("--from", dest="source", default="http://localhost:9200")
    parser.add_argument("--to", dest="target", required=True)
    parser.add_argument("--replicas", type=int, default=1)
    parser.add_argument("--batch", type=int, default=500)
    parser.add_argument("--pause", type=float, default=0.2,
                        help="seconds between batches, to keep a live cluster quiet")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    sys.exit(copy(args.source, args.target, args.index, args.replicas, args.batch,
                  args.pause, args.dry_run))


if __name__ == "__main__":
    main()
