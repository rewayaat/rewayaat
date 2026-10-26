#!/usr/bin/env python3
"""Who stands next to whom in the chains this database actually holds.

The «In the same isnād» list on a narrator page is built from the chain indexes printed
in the rijāl books — al-Khūʾī's Muʿjam and Tanqīḥ al-Maqāl, mostly. Those indexes are a
summary of the books their authors read, and they are not the chains we serve: for Aḥmad
b. Idrīs the books give 66 names, while he stands in 1,093 resolved steps here, beside 121
men, 108 of whom no index names.

So this reads the corpus itself. Every narration carries `chain_links`, the spans the
resolver settled, each with the id of the man it names; sorted by where they fall in the
printed chain, consecutive spans are a man and the man he took from. Counting those pairs
over the whole corpus gives, for each narrator, who came immediately before and after him
and in how many narrations.

Two deliberate limits, both of them on the men no book index names at all — where an
index has named the pairing, `beside` reports it whatever the corpus says about the order
and however few times it holds it:

* A pairing seen in a single narration is not reported. Half of them are singletons, and a
  single pair is exactly what one mis-resolved name produces; the ones worth printing are
  the ones the corpus repeats. `LEAST_NARRATIONS` is that floor.
* The fourteen are skipped. They have their own section on the page, settled by different
  machinery, and a man who appears in both lists reads as two different claims about him.

What this cannot say is that a man was another's teacher. Standing next to someone in an
isnād is the same weak claim the book indexes make, which is why this feeds that section
rather than «narrates from».
"""
import json
import os
import sys
import urllib.request
from collections import Counter, defaultdict

#: Report a pairing only once the corpus has it this many times over.
LEAST_NARRATIONS = 2

#: The fourteen, who are listed in their own section.
IMAM_PREFIX = "i"


def _post(host, path, body):
    request = urllib.request.Request(host + path, data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(request))


def _chains(host, index):
    """Every narration's resolved chain, in printed order, one page held at a time."""
    page = _post(host, f"/{index}/_search?scroll=5m",
                 {"size": 500, "_source": ["chain_links"],
                  "query": {"exists": {"field": "chain_links"}}})
    scroll = page.get("_scroll_id")
    while True:
        hits = page["hits"]["hits"]
        if not hits:
            return
        for hit in hits:
            links = hit["_source"].get("chain_links") or []
            yield sorted(links, key=lambda span: span.get("s", 0))
        page = _post(host, "/_search/scroll", {"scroll": "5m", "scroll_id": scroll})


def build(host="http://localhost:9200", index="rewayaat_hadith",
          least=LEAST_NARRATIONS):
    """{person id: {"before": {id: narrations}, "after": {id: narrations}}}.

    Both halves of a pair are recorded from one walk, so the result is symmetric: if B is
    counted after A, A is counted before B with the same number.
    """
    before = defaultdict(Counter)
    after = defaultdict(Counter)
    steps = 0
    for links in _chains(host, index):
        for position, span in enumerate(links):
            who = span.get("p")
            if not who:
                continue
            steps += 1
            if position + 1 < len(links):
                nxt = links[position + 1].get("p")
                # A name repeated across two spans is one man, not a pair. «أبيه» resolved
                # to the same id as the name before it is the common way that happens.
                if nxt and nxt != who:
                    after[who][nxt] += 1
                    before[nxt][who] += 1
    out = {}
    for who in set(before) | set(after):
        kept_before = {k: v for k, v in before[who].items()
                       if v >= least and not k.startswith(IMAM_PREFIX)}
        kept_after = {k: v for k, v in after[who].items()
                      if v >= least and not k.startswith(IMAM_PREFIX)}
        # And the same pairings with both limits lifted: every man the corpus ever stands
        # next to him, which way round and how often not asked. Neither limit holds once a
        # rijālī index has named the pairing itself. The order is then the book's claim and
        # not ours — the books put ʿAlī b. al-Ḥasan b. Faḍḍāl above Aḥmad b. Muhammad b.
        # Saʿīd where the corpus has him below, 51 times — and a lone co-occurrence is no
        # longer an unsupported singleton, because the index is the support.
        beside = Counter()
        beside.update(before[who])
        beside.update(after[who])
        beside = {k: v for k, v in beside.items() if not k.startswith(IMAM_PREFIX)}
        if kept_before or kept_after or beside:
            out[who] = {"before": kept_before, "after": kept_after, "beside": beside}
    return out, steps


def main():
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--es-host", default="http://localhost:9200")
    parser.add_argument("--index", default="rewayaat_hadith")
    parser.add_argument("--least", type=int, default=LEAST_NARRATIONS)
    parser.add_argument("--show", help="print one man's neighbours and stop")
    args = parser.parse_args()

    print("reading the chains this database serves", file=sys.stderr)
    table, steps = build(args.es_host, args.index, args.least)
    pairs = sum(len(v["before"]) + len(v["after"]) for v in table.values())
    print(f"{steps:,} resolved chain steps; {len(table):,} men have a neighbour the corpus "
          f"repeats; {pairs:,} pairings at {args.least}+ narrations")
    if args.show:
        print(json.dumps(table.get(args.show, {}), ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
