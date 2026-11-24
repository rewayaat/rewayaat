#!/usr/bin/env python3
"""Load the v2 Quranic-insights documents into a new dated index, and point the alias at it.

The app reads the alias `rewayaat_quranic_insights` (quranic.insights.index). Rolling back
is pointing the alias at the previous index again — including the old concrete
`rewayaat_quranic_light_filtered`, which the v2 code still reads.

    # load only; the alias is untouched
    python3 scripts/quranic-insights/load_insights_index.py --es-host http://localhost:9202 \
        --index rewayaat_quranic_insights_20261125

    # point the alias at it (or back at any other index)
    python3 scripts/quranic-insights/load_insights_index.py --es-host ... \
        --alias-to rewayaat_quranic_insights_20261125
"""

import argparse
import json
import urllib.error
import urllib.request

ALIAS = "rewayaat_quranic_insights"


def call(method, url, body=None, ndjson=False):
    data = None
    if body is not None:
        data = (body if ndjson else json.dumps(body)).encode()
    req = urllib.request.Request(url, data=data, method=method, headers={
        "Content-Type": "application/x-ndjson" if ndjson else "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=600) as resp:
            return json.load(resp)
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{method} {url}: {e.code} {e.read().decode()[:400]}")


def load(host, index, docs_path, mapping_path, replicas):
    mapping = json.load(open(mapping_path))
    call("PUT", f"{host}/{index}", {
        "settings": {"number_of_shards": 1, "number_of_replicas": 0, "refresh_interval": "-1"},
        "mappings": mapping})
    n, batch = 0, []

    def flush():
        if not batch:
            return
        r = call("POST", f"{host}/_bulk", "\n".join(batch) + "\n", ndjson=True)
        if r.get("errors"):
            bad = next(i for i in r["items"] if "error" in i["index"])
            raise SystemExit(f"bulk error: {bad}")
        batch.clear()

    for line in open(docs_path):
        d = json.loads(line)
        batch.append(json.dumps({"index": {"_index": index, "_id": d["hadith_id"]}}))
        batch.append(json.dumps(d, ensure_ascii=False))
        n += 1
        if len(batch) >= 1000:
            flush()
    flush()
    call("PUT", f"{host}/{index}/_settings",
         {"index": {"refresh_interval": "1s", "number_of_replicas": replicas}})
    call("POST", f"{host}/{index}/_refresh")
    count = call("GET", f"{host}/{index}/_count")["count"]
    print(f"{index}: {n} documents sent, {count} indexed")
    if count != n:
        raise SystemExit("count mismatch")


def point_alias(host, target):
    actions = []
    current = call("GET", f"{host}/_alias/{ALIAS}") if alias_exists(host) else {}
    for idx in current:
        actions.append({"remove": {"index": idx, "alias": ALIAS}})
    actions.append({"add": {"index": target, "alias": ALIAS}})
    call("POST", f"{host}/_aliases", {"actions": actions})
    print(f"{ALIAS}: {sorted(current) or 'none'} -> {target}")


def alias_exists(host):
    req = urllib.request.Request(f"{host}/_alias/{ALIAS}", method="HEAD")
    try:
        urllib.request.urlopen(req, timeout=60)
        return True
    except urllib.error.HTTPError:
        return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--es-host", required=True)
    ap.add_argument("--index", help="new index to create and load")
    ap.add_argument("--docs", default="tmp/qlight-v2/insights_v2.jsonl")
    ap.add_argument("--mapping", default="tmp/qlight-v2/insights_v2_mapping.json")
    ap.add_argument("--replicas", type=int, default=0)
    ap.add_argument("--alias-to", help="point the alias at this index")
    args = ap.parse_args()
    host = args.es_host.rstrip("/")
    if args.index:
        load(host, args.index, args.docs, args.mapping, args.replicas)
    if args.alias_to:
        point_alias(host, args.alias_to)


if __name__ == "__main__":
    main()
