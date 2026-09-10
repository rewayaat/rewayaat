#!/usr/bin/env python3
"""Apply reader-reported text corrections to hadith documents in Elasticsearch.

Reads reported_corrections.json alongside this script, where each entry names the documents
to touch, the field to edit, and exact (old, new) string pairs. A replacement is
only applied when its `old` string is found verbatim, so a document that has already
been corrected -- or that never matched -- is reported and skipped rather than
guessed at.

`mirror_fields` carries the same replacement into the derived fields that feed the
embedding pipeline. semantic_english_hint_source is a separate stored copy of the
first ~120 characters of the English, so correcting `english` alone leaves the
embedding input describing the old wording.

Dry run by default; pass --live to write.

Usage:
  python3 scripts/ops/apply_reported_corrections.py
  python3 scripts/ops/apply_reported_corrections.py --live
  python3 scripts/ops/apply_reported_corrections.py --live --es-host http://prod:9200
"""
import argparse
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path


CORRECTIONS_JSON = Path(__file__).resolve().with_name("reported_corrections.json")


def es_request(es_host, method, path, payload=None, timeout=60):
    url = f"{es_host.rstrip('/')}/{path.lstrip('/')}"
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"{method} {path} -> {exc.code}: {exc.read().decode('utf-8')[:400]}") from exc
    return json.loads(body) if body.strip() else {}


def fetch(es_host, index, ids):
    docs = es_request(es_host, "POST", f"/{index}/_mget", {"ids": ids})["docs"]
    return {d["_id"]: (d.get("_source") if d.get("found") else None) for d in docs}


def plan_field(source, field, replacements, required):
    """Return (new_value, applied, missing) for one field of one document.

    `required` is False for mirror fields, where a replacement that does not
    match is expected rather than a problem: the hint holds only the opening of
    the English, so a correction further in simply is not there.
    """
    original = source.get(field) or ""
    value = original
    applied, missing = [], []
    for old, new in replacements:
        # `new` is checked first: an append-style correction keeps `old` intact
        # inside its own replacement, so testing `old` first would re-append on
        # every run.
        if new in value:
            missing.append((old, "already applied"))
        elif old in value:
            value = value.replace(old, new)
            applied.append(old)
        elif required:
            missing.append((old, "not found"))
    return (value if value != original else None), applied, missing


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--es-host", default="http://localhost:9200")
    parser.add_argument("--index", default="rewayaat_updated")
    parser.add_argument("--corrections", type=Path, default=CORRECTIONS_JSON)
    parser.add_argument("--live", action="store_true", help="write to Elasticsearch")
    parser.add_argument("--ids-out", type=Path,
                        help="write the changed ids here (feed to the embedding regen)")
    args = parser.parse_args()

    spec = json.loads(args.corrections.read_text(encoding="utf-8"))
    entries = spec["corrections"]

    wanted = sorted({i for e in entries for i in e["ids"]})
    sources = fetch(args.es_host, args.index, wanted)

    updates, skipped, problems = {}, [], []

    mirrors = spec.get("mirror_fields", {})

    for entry in entries:
        field = entry["field"]
        targets = [(field, True)] + [(m, False) for m in mirrors.get(field, [])]
        for doc_id in entry["ids"]:
            source = sources.get(doc_id)
            if source is None:
                problems.append((doc_id, "document not found"))
                continue
            for target, required in targets:
                new_value, applied, missing = plan_field(
                    source, target, entry["replacements"], required)
                for old, why in missing:
                    (skipped if why == "already applied" else problems).append(
                        (doc_id, f"{target}: {why}: {old[:50]}"))
                if new_value is not None:
                    doc = updates.setdefault(doc_id, {"fields": {}, "kind": entry["kind"],
                                                      "count": 0, "reindex": False})
                    doc["fields"][target] = new_value
                    doc["count"] += len(applied)
                    if target != field:
                        doc["reindex"] = True

    for doc_id, info in sorted(updates.items()):
        flags = "  <-- REVIEW" if info["kind"] == "rewrite" else ""
        flags += "  <-- RE-EMBED" if info["reindex"] else ""
        print(f"  {doc_id:46} {info['kind']:10} {info['count']} edit(s){flags}")

    print(f"\n{len(updates)} document(s) to change, {len(skipped)} already correct, "
          f"{len(problems)} problem(s)")
    for doc_id, why in problems:
        print(f"  PROBLEM {doc_id}: {why}")

    for item in spec.get("deferred", []):
        print(f"  DEFERRED {item['id']}: {item['reason']}")

    if not updates:
        return 0
    if not args.live:
        print("\nDry run. Re-run with --live to write.")
        return 0

    lines = []
    for doc_id, info in updates.items():
        lines.append(json.dumps({"update": {"_id": doc_id, "_index": args.index}}))
        lines.append(json.dumps({"doc": info["fields"]}, ensure_ascii=False))
    payload = ("\n".join(lines) + "\n").encode("utf-8")

    url = f"{args.es_host.rstrip('/')}/_bulk?refresh=true"
    req = urllib.request.Request(url, data=payload, method="POST",
                                 headers={"Content-Type": "application/x-ndjson"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        result = json.loads(resp.read().decode("utf-8"))

    if result.get("errors"):
        for item in result["items"]:
            err = item.get("update", {}).get("error")
            if err:
                print(f"  ERROR {item['update']['_id']}: {err.get('reason')}")
        return 1

    print(f"\nWrote {len(updates)} document(s) to {args.index} on {args.es_host}.")

    reindex = sorted(d for d, i in updates.items() if i["reindex"])
    if args.ids_out:
        args.ids_out.write_text("\n".join(reindex) + "\n", encoding="utf-8")
        print(f"Re-embed ids -> {args.ids_out}")
    if reindex:
        print(f"{len(reindex)} document(s) had their embedding input "
              f"(semantic_english_hint_source) corrected and need re-embedding. The "
              f"rest were corrected past the hint cutoff, so their vectors never saw "
              f"the old wording.")
    else:
        print("No embedding input changed; no re-embedding needed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
