#!/usr/bin/env python3
"""Measure what weighting the matn field does to isnād pollution in search results.

Issue #66's evaluation recorded that a keyword search for a narrator drowns in isnād:
`arabic` and `english` both carry the chain of transmission, and a chain is a dense
thicket of names, so narrations that merely pass through a narrator outrank the ones
about him.

`semantic_matn_source` is the same narration with the chain removed. It exists as an
embedding input, but it is an ordinary text field, so BM25 can search it.

This measures the difference on the live corpus, because it does not reproduce on a
handful of documents - BM25's length normalisation and IDF behave nothing like they do
across 32,519. Run it against a populated index before changing SEARCH_FIELDS in
NarrationRepository.

    python3 scripts/search/measure_field_weighting.py [--es http://localhost:9200]
"""
import argparse
import json
import urllib.request

WEIGHTED = [
    "semantic_matn_source^4", "english^2", "arabic", "chapter", "chapter_ar",
    "semantic_significant_terms_source", "notes", "book", "topic_tags",
]

# The evaluation's own query, fuzzied the way HadithQueryService fuzzies a flexible search.
NARRATOR_QUERY = "(يحيى^6 OR يحيى~) (بن^6 OR بن~) (زكريا^6 OR زكريا~)"
NARRATOR_NAME = "يحيى"

RECALL_QUERIES = ["غدير", "بكت السماء", "intellect", "backbiting", "patience",
                  "الصوم جنة", "Kāmil al-Ziyārāt", "يحيى بن زكريا"]


def search(es, index, body):
    req = urllib.request.Request(f"{es}/{index}/_search", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=60))


def query_string(query, fields):
    qs = {"query": query}
    if fields:
        qs["fields"] = fields
    return {"query_string": qs}


def pollution(es, index, fields, size=20):
    """Of the top `size` hits, how many carry the name in the matn vs only in the chain."""
    hits = search(es, index, {"size": size, "query": query_string(NARRATOR_QUERY, fields),
                              "_source": ["semantic_matn_source", "arabic"]})["hits"]["hits"]
    matn = chain_only = 0
    for hit in hits:
        source = hit["_source"]
        if NARRATOR_NAME in str(source.get("semantic_matn_source") or ""):
            matn += 1
        elif NARRATOR_NAME in str(source.get("arabic") or ""):
            chain_only += 1
    return matn, chain_only


def total(es, index, query, fields):
    body = {"size": 0, "track_total_hits": True, "query": query_string(query, fields)}
    return search(es, index, body)["hits"]["total"]["value"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--es", default="http://localhost:9200")
    parser.add_argument("--index", default="rewayaat_updated")
    args = parser.parse_args()

    print(f"Top 20 for the evaluation's narrator query, on {args.index}:")
    for label, fields in [("every field", None), ("weighted set", WEIGHTED)]:
        matn, chain_only = pollution(args.es, args.index, fields)
        print(f"  {label:14s} -> {matn:2d} matn matches, {chain_only:2d} chain-only")

    print("\nRecall, so the weighting is not bought with lost matches:")
    print(f"  {'query':24s} {'every field':>12s} {'weighted':>10s} {'delta':>8s}")
    for query in RECALL_QUERIES:
        everything = total(args.es, args.index, query, None)
        weighted = total(args.es, args.index, query, WEIGHTED)
        delta = f"{(weighted - everything) / everything * 100:+.1f}%" if everything else "n/a"
        print(f"  {query:24s} {everything:12d} {weighted:10d} {delta:>8s}")


if __name__ == "__main__":
    main()
