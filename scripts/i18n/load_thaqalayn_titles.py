#!/usr/bin/env python3
"""Compare thaqalayn's Arabic chapter titles with what the index holds, then load them.

thaqalayn wins where the two disagree. It is the edition this corpus was imported from,
so its chapter titles are the ones its chapter numbers refer to; a printed bāb heading
recovered from OpenITI is the more authentic reading of the book, but not necessarily of
this row.

--report prints the disagreement rate and samples and writes nothing. That is the step
worth reading: a high rate is expected, because most of what is in the index was
translated from the English rather than taken from a source, but a high rate would also
be what a broken join looks like.

    python3 scripts/i18n/load_thaqalayn_titles.py --report
    python3 scripts/i18n/load_thaqalayn_titles.py --apply
"""

import argparse
import glob
import json
import pathlib
import re
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
CACHE = ROOT / "tmp" / "thaqalayn"
SOURCE_TAG = "thaqalayn.com/ar"


def flatten(text):
    """Fold the marks and spellings that differ without changing the reading."""
    text = re.sub(r"[ً-ْـ]", "", text or "")
    text = re.sub(r"[()\[\]«»\"']", " ", text)
    text = (text.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
                .replace("ى", "ي").replace("ة", "ه"))
    text = re.sub(r"^باب\s+", "", text.strip())
    return re.sub(r"\s+", " ", text).strip()


def scraped():
    titles = {}
    for path in sorted(glob.glob(str(CACHE / "*.json"))):
        for english, record in json.loads(pathlib.Path(path).read_text(encoding="utf-8")).items():
            arabic = (record or {}).get("chapter_ar")
            if english and arabic:
                titles[english.strip()] = arabic
    return titles


def post(url, body=None, ndjson=False, method="POST"):
    data = None
    if body is not None:
        data = (body if ndjson else json.dumps(body)).encode()
    headers = {"Content-Type": "application/x-ndjson" if ndjson else "application/json"}
    request = urllib.request.Request(url, data=data, method=method, headers=headers)
    with urllib.request.urlopen(request, timeout=180) as resp:
        return json.load(resp)


def current_titles(es_host, index):
    """What the index holds now, one Arabic title per English chapter."""
    body = {"size": 0, "aggs": {"c": {"terms": {"field": "chapter.keyword", "size": 10000},
                                      "aggs": {"ar": {"terms": {"field": "chapter_ar", "size": 1}}}}}}
    held = {}
    for bucket in post(f"{es_host}/{index}/_search", body)["aggregations"]["c"]["buckets"]:
        arabic = bucket["ar"]["buckets"]
        held[bucket["key"]] = arabic[0]["key"] if arabic else None
    return held


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--es-host", default="http://localhost:9200")
    parser.add_argument("--index", default="rewayaat_hadith")
    parser.add_argument("--report", action="store_true")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()

    titles = scraped()
    print(f"  scraped chapters available: {len(titles)}")
    held = current_titles(args.es_host, args.index)
    print(f"  chapters in the index: {len(held)}")

    agree, differ, new = [], [], []
    for english, arabic in titles.items():
        if english not in held:
            continue
        existing = held[english]
        if existing is None:
            new.append((english, arabic))
        elif flatten(existing) == flatten(arabic):
            agree.append(english)
        else:
            differ.append((english, existing, arabic))

    covered = len(agree) + len(differ)
    rate = len(agree) * 100 // max(covered, 1)
    print(f"\n  already had Arabic: {covered} -> agree {len(agree)} ({rate}%), differ {len(differ)}")
    print(f"  had none: {len(new)}")
    if differ:
        print("\n  --- disagreements (index, then thaqalayn) ---")
        for english, existing, arabic in differ[:12]:
            print(f"    {english[:44]}")
            print(f"       index     : {existing[:62]}")
            print(f"       thaqalayn : {arabic[:62]}")
    if not args.apply:
        print("\n  report only; nothing written")
        return 0

    writes = [(e, a) for e, a in titles.items() if e in held]
    sent = 0
    for start in range(0, len(writes), 200):
        lines = []
        for english, arabic in writes[start:start + 200]:
            ids = post(f"{args.es_host}/{args.index}/_search",
                       {"size": 10000, "_source": False,
                        "query": {"term": {"chapter.keyword": english}}})["hits"]["hits"]
            for hit in ids:
                lines.append(json.dumps({"update": {"_id": hit["_id"], "_index": args.index}}))
                lines.append(json.dumps({"doc": {"chapter_ar": arabic,
                                                 "chapter_ar_source": SOURCE_TAG}},
                                        ensure_ascii=False))
        if not lines:
            continue
        result = post(f"{args.es_host}/_bulk", "\n".join(lines) + "\n", ndjson=True)
        failed = [i for i in result.get("items", []) if i.get("update", {}).get("error")]
        sent += len(result.get("items", [])) - len(failed)
        if failed:
            print(f"    {len(failed)} failures; first: {failed[0]}")
    post(f"{args.es_host}/{args.index}/_refresh", method="POST")
    print(f"\n  wrote {sent} documents")
    return 0


if __name__ == "__main__":
    sys.exit(main())
