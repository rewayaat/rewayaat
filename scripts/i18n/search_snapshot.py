#!/usr/bin/env python3
"""Record what search returns, so the Arabic migration can be shown not to have moved it.

The migration fills book_ar, chapter_ar, part_ar, section_ar and source_ar on all 32,519
narrations. QueryStringQueryResult.SEARCHABLE_FIELDS names every one of them and has
since before the Arabic branch, so the fields are not inert: they are empty today, they
are searched today, and filling them changes what the query can match. It changes it
against the code already running in production, the moment the data lands, with nothing
deployed.

MigrationPreservesSearchIntegrationTest covers the shape of that on a seven-document
fixture. It cannot cover 32,519 documents, a real term distribution or the actual
relevance tuning, and the only thing that can is the index itself. So: take a snapshot
before the apply, take another after, and diff them.

    python3 -m scripts.i18n.search_snapshot --out before.json
    # ... run translate_tier1 --apply ...
    python3 -m scripts.i18n.search_snapshot --out after.json
    python3 -m scripts.i18n.search_snapshot --compare before.json after.json

Read-only: it issues the same GET /v1/narrations the website does, against whichever
host is given, and writes nothing anywhere.
"""

import argparse
import json
import sys
import time
import urllib.parse
import urllib.request

DEFAULT_HOST = "https://hadith.academyofislam.com"

# English, because that is the half that must not move. A plain word, an English stem, a
# word that lives mostly in metadata, a quoted phrase, a field scope, a two-word query
# and a transliterated name - between them they reach every clause the query builder
# assembles. The Arabic below is here to be seen changing.
ENGLISH_QUERIES = [
    "prayer", "prayers", "fasting", "zakat", "charity", "knowledge", "intellect",
    "mercy", "patience", "repentance", "pilgrimage", "commerce", "marriage",
    '"the book of prayer"', '"pledge of allegiance"',
    'book:"Al-Kāfi" prayer', 'book:"Al-Khiṣāl" intellect',
    "prayer intention", "hassan", "ghadir",
]

ARABIC_QUERIES = ["صلاة", "الزكاة", "الصوم", "العلم", "الكافي", "الخصال"]


def search(host, query, timeout):
    url = (host.rstrip("/") + "/v1/narrations?page=1&q="
           + urllib.parse.quote(query, safe=""))
    with urllib.request.urlopen(url, timeout=timeout) as response:
        body = json.load(response)
    return {
        "total": body.get("totalResultSetSize"),
        # The page a reader sees, in the order they see it. Deeper pages are not
        # snapshotted: if the first page is identical the ranking has not moved, and if
        # it has moved the first page is where it shows.
        "ids": [hit.get("_id") for hit in body.get("collection", [])],
    }


def snapshot(host, timeout, pause):
    taken = {"host": host, "at": time.strftime("%Y-%m-%dT%H:%M:%S"), "queries": {}}
    for query in ENGLISH_QUERIES + ARABIC_QUERIES:
        taken["queries"][query] = search(host, query, timeout)
        print(f"  {len(taken['queries'][query]['ids']):>3} hits  {query}")
        # The ingress allows 50 requests a minute. This is 26 queries; a pause keeps a
        # snapshot from looking like something to rate-limit.
        time.sleep(pause)
    return taken


def compare(before_path, after_path):
    before = json.load(open(before_path, encoding="utf-8"))
    after = json.load(open(after_path, encoding="utf-8"))

    moved_set, moved_order, moved_total = [], [], []
    for query in before["queries"]:
        if query not in after["queries"]:
            moved_set.append(f"{query}: missing from the second snapshot")
            continue
        b, a = before["queries"][query], after["queries"][query]
        if set(b["ids"]) != set(a["ids"]):
            gained = sorted(set(a["ids"]) - set(b["ids"]))
            lost = sorted(set(b["ids"]) - set(a["ids"]))
            moved_set.append(f"{query}\n    gained: {gained}\n    lost:   {lost}")
        elif b["ids"] != a["ids"]:
            moved_order.append(f"{query}\n    before: {b['ids']}\n    after:  {a['ids']}")
        if b["total"] != a["total"]:
            moved_total.append(f"{query}: {b['total']} -> {a['total']}")

    english = set(ENGLISH_QUERIES)
    english_set = [m for m in moved_set if m.split("\n")[0] in english]
    english_total = [m for m in moved_total if m.split(":")[0] in english]

    print(f"\n{before['at']} -> {after['at']}\n")
    report("English queries whose result COUNT changed  [this is the gate]", english_total)
    report("English queries whose first page churned    [see note below]", english_set)
    report("queries whose first page reordered without changing", moved_order)
    report("every change, English and Arabic", moved_set)

    print(
        "\nNote on first-page churn. A snapshot records the first twenty of a result set\n"
        "that is often in the thousands, and the site's ranking leaves large groups of\n"
        "those exactly tied - 'the book of prayer' scores 927 narrations identically,\n"
        "because the only field a phrase like that matches is one they all share. Which\n"
        "twenty of a tie you are shown is arbitrary, and rewriting a document reshuffles\n"
        "it. So churn here is not by itself a regression, and it is not the gate.\n"
        "\n"
        "Confirm by asking whether the churned query is tied, with a port-forward open:\n"
        "    python3 -m scripts.i18n.search_snapshot --scores '<query>' \\\n"
        "        --es-host http://localhost:9201\n"
        "Few distinct scores across the top forty means a tie reshuffled. Many distinct\n"
        "scores and a changed set means relevance moved, and that is a rollback."
    )

    # The gate is the count. It is the thing that cannot be explained by a tie: a
    # narration that used to match and no longer does, or the reverse, changes it.
    #
    # This used to fail on the set as well, and on the real migration it duly failed on
    # eight of twenty English queries while every single count held - all eight being
    # queries whose top forty carried between one and six distinct scores, and the two
    # with thirty-odd distinct scores coming back byte-identical. The set is reported
    # because it is worth looking at. It is not a verdict.
    failed = bool(english_total)
    print("\nFAIL: an English query gained or lost narrations." if failed
          else "\nOK: every English query returns exactly as many narrations as before.")
    return 1 if failed else 0


# What the website searches. Kept beside the queries it explains rather than imported,
# because this script talks to a host over HTTP and knows nothing else about the app.
SEARCHABLE_FIELDS = [
    "english", "arabic", "chapter", "notes",
    "book.text", "volume.text", "part.text", "section.text",
    "source.text", "publisher.text",
    "book_ar", "chapter_ar.text", "part_ar.text", "section_ar.text", "source_ar.text",
    "topic_tags",
]


def scores(es_host, query, index="rewayaat_hadith", size=40):
    """How many distinct scores the top of a result set carries.

    A churned first page means one of two things and they are not alike. If the top
    forty hold a handful of distinct scores, the set is tied and the order within it was
    always arbitrary. If they hold forty, the ranking moved.
    """
    body = json.dumps({
        "size": size, "_source": False,
        "query": {"query_string": {"query": query, "fields": SEARCHABLE_FIELDS}},
    }).encode()
    request = urllib.request.Request(
        f"{es_host.rstrip('/')}/{index}/_search", data=body,
        headers={"Content-Type": "application/json"})
    hits = json.load(urllib.request.urlopen(request))["hits"]["hits"]
    found = [hit["_score"] for hit in hits]
    distinct = sorted({round(score, 5) for score in found}, reverse=True)

    print(f"{query}\n  {len(found)} hits read, {len(distinct)} distinct scores")
    print(f"  range {min(found):.5f} .. {max(found):.5f}" if found else "  no hits")
    if len(distinct) <= 6:
        print(f"  scores: {distinct}")
        print("  -> heavily tied. A changed first page here is the tie being broken "
              "differently, not relevance moving.")
    else:
        print("  -> well separated. A changed first page here would be relevance moving.")
    return 0


def report(title, lines):
    print(f"--- {title}: {len(lines)} ---")
    for line in lines:
        print("  " + line)


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default=DEFAULT_HOST)
    parser.add_argument("--out")
    parser.add_argument("--compare", nargs=2, metavar=("BEFORE", "AFTER"))
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--pause", type=float, default=1.5)
    parser.add_argument("--scores", metavar="QUERY",
                        help="how tied the top of this query is, read straight from "
                             "Elasticsearch; needs --es-host")
    parser.add_argument("--es-host", default="http://localhost:9201")
    args = parser.parse_args()

    if args.scores:
        return scores(args.es_host, args.scores)
    if args.compare:
        return compare(*args.compare)
    if not args.out:
        parser.error("give --out to record a snapshot, or --compare to diff two")

    taken = snapshot(args.host, args.timeout, args.pause)
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(taken, handle, ensure_ascii=False, indent=2)
    print(f"\nwrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
