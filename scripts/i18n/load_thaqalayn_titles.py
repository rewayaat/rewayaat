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
import json
import pathlib
import re
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
SCRAPE = ROOT / "scripts" / "data" / "thaqalayn_chapter_titles.json"
SOURCE_TAG = "thaqalayn.com/ar"


def flatten(text):
    """Fold the marks and spellings that differ without changing the reading."""
    text = re.sub(r"[ً-ْـ]", "", text or "")
    text = re.sub(r"[()\[\]«»\"']", " ", text)
    text = (text.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
                .replace("ى", "ي").replace("ة", "ه"))
    text = re.sub(r"^باب\s+", "", text.strip())
    return re.sub(r"\s+", " ", text).strip()


def display(text):
    """Strip vocalisation so headings read consistently across books.

    Some of thaqalayn's books are fully vocalised and most are not, so taking
    their titles verbatim leaves a fifth of the index wearing tashkeel and the
    rest bare. Headings on the site are shown unvocalised, and the scrape file
    keeps the marks if they are ever wanted back.
    """
    text = re.sub(r"[\u064B-\u0652\u0670\u0640]", "", text or "")
    return re.sub(r"\s+", " ", text).strip()


def key(english):
    """Fold the English title to what identifies the chapter across the two pages."""
    text = re.sub(r"\s+", " ", (english or "").strip().lower())
    text = re.sub(r"^\d+\.\s*", "", text)
    text = re.sub(r"^chapter\s+(on\s+)?", "", text)
    return text.strip(" .:-")


def scraped():
    """English title -> Arabic title, from thaqalayn's own book index pages.

    Both language versions of a book index list the same chapters in the same
    (category, chapter) slots, so the pair is the site's own, not a guess of
    ours. A title that resolves to more than one distinct Arabic reading is
    dropped rather than guessed at.
    """
    data = json.loads(SCRAPE.read_text(encoding="utf-8"))
    rows = list(data["chapters"])
    rows += [{"en": c["en"], "ar": c["ar"]} for c in data["categories"]]
    candidates = {}
    for row in rows:
        english, arabic = key(row["en"]), (row["ar"] or "").strip()
        if english and arabic:
            candidates.setdefault(english, set()).add(display(arabic))
    titles, ambiguous = {}, 0
    for english, readings in candidates.items():
        if len({flatten(r) for r in readings}) == 1:
            titles[english] = sorted(readings)[0]
        else:
            ambiguous += 1
    if ambiguous:
        print(f"  dropped {ambiguous} English titles with conflicting Arabic readings")
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
        held[key(bucket["key"])] = (bucket["key"],
                                    arabic[0]["key"] if arabic else None)
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
        raw, existing = held[english]
        if existing is None:
            new.append((raw, arabic))
        elif flatten(existing) == flatten(arabic):
            agree.append(raw)
        else:
            differ.append((raw, existing, arabic))

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

    writes = [(held[e][0], a) for e, a in titles.items() if e in held]
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
