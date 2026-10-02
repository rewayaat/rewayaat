#!/usr/bin/env python3
"""What readers searched for, aggregated over a window.

    python3 scripts/ops/search_terms_report.py                      # top 20, last 30 days
    python3 scripts/ops/search_terms_report.py --top 50 --days 90
    python3 scripts/ops/search_terms_report.py --zero-hits          # searches that found nothing
    python3 scripts/ops/search_terms_report.py --monthly            # a rolling month-by-month series
    python3 scripts/ops/search_terms_report.py --es-host http://HOST:9200

Reads ``rewayaat_search_log``, which the application writes on every search that a reader
- not a crawler - runs. Nothing in that index identifies anybody: a row is a phrase, a
result count and a time.

Two numbers are worth more than the ranking itself:

  * **zero-hit terms.** A phrase people search for and find nothing for is either a gap in
    the corpus or a gap in the search. Both are worth knowing, and neither shows up in a
    list of popular terms.
  * **the monthly series.** A single month is a number; twelve months is an argument, which
    is what a bookseller being asked to sponsor a placement actually wants to see.
"""

import argparse
import json
import sys
import urllib.error
import urllib.request

INDEX = "rewayaat_search_log"


def search(host: str, body: dict) -> dict:
    request = urllib.request.Request(
        f"{host.rstrip('/')}/{INDEX}/_search",
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return json.loads(response.read())
    except urllib.error.HTTPError as e:
        if e.code == 404:
            print(f"{INDEX} does not exist yet - nothing has been searched since it shipped.",
                  file=sys.stderr)
            sys.exit(1)
        raise


def window(days: int) -> dict:
    return {"range": {"timestamp": {"gte": f"now-{days}d/d", "lte": "now"}}}


def report_top(host: str, days: int, top: int, zero_hits: bool) -> None:
    query = {"bool": {"filter": [window(days)]}}
    if zero_hits:
        query["bool"]["filter"].append({"term": {"hits": 0}})
    body = {
        "size": 0,
        "query": query,
        "aggs": {
            "terms": {
                "terms": {"field": "query_normalized", "size": top,
                          "order": {"_count": "desc"}},
                "aggs": {"median_hits": {"percentiles": {"field": "hits", "percents": [50]}}},
            },
            "searches": {"value_count": {"field": "query_normalized"}},
            "distinct": {"cardinality": {"field": "query_normalized"}},
        },
    }
    data = search(host, body)
    aggs = data["aggregations"]
    total = int(aggs["searches"]["value"])
    label = "searches that found nothing" if zero_hits else "searches"
    print(f"\nLast {days} days: {total:,} {label}, "
          f"{int(aggs['distinct']['value']):,} distinct terms\n")
    if not total:
        return
    buckets = aggs["terms"]["buckets"]
    width = max((len(b["key"]) for b in buckets), default=10)
    width = min(max(width, 12), 50)
    print(f"  {'#':>3}  {'term'.ljust(width)}  {'searches':>9}  {'share':>6}  {'median hits':>11}")
    print(f"  {'-' * 3}  {'-' * width}  {'-' * 9}  {'-' * 6}  {'-' * 11}")
    for i, b in enumerate(buckets, 1):
        term = b["key"][:width]
        count = b["doc_count"]
        median = b["median_hits"]["values"].get("50.0")
        median_text = "-" if median is None else f"{median:,.0f}"
        print(f"  {i:>3}  {term.ljust(width)}  {count:>9,}  {100 * count / total:>5.1f}%  {median_text:>11}")


def report_monthly(host: str, months: int, top: int) -> None:
    body = {
        "size": 0,
        "query": {"bool": {"filter": [window(months * 31)]}},
        "aggs": {
            "months": {
                "date_histogram": {"field": "timestamp", "calendar_interval": "month",
                                   "min_doc_count": 1},
                "aggs": {
                    "terms": {"terms": {"field": "query_normalized", "size": top}},
                    "zero": {"filter": {"term": {"hits": 0}}},
                },
            }
        },
    }
    data = search(host, body)
    for bucket in data["aggregations"]["months"]["buckets"]:
        month = bucket["key_as_string"][:7]
        total = bucket["doc_count"]
        zero = bucket["zero"]["doc_count"]
        share = f"{100 * zero / total:.0f}%" if total else "-"
        print(f"\n{month}: {total:,} searches, {zero:,} found nothing ({share})")
        for i, t in enumerate(bucket["terms"]["buckets"][:top], 1):
            print(f"    {i:>2}. {t['key'][:50]:52} {t['doc_count']:>6,}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--es-host", default="http://localhost:9200")
    parser.add_argument("--days", type=int, default=30)
    parser.add_argument("--top", type=int, default=20)
    parser.add_argument("--zero-hits", action="store_true",
                        help="only searches that returned no results")
    parser.add_argument("--monthly", action="store_true",
                        help="a month-by-month series rather than one window")
    parser.add_argument("--months", type=int, default=12)
    args = parser.parse_args()

    if args.monthly:
        report_monthly(args.es_host, args.months, args.top)
    else:
        report_top(args.es_host, args.days, args.top, args.zero_hits)
    return 0


if __name__ == "__main__":
    sys.exit(main())
