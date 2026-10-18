#!/usr/bin/env python3
"""Is a narrator's own name underlined in the narrations his page lists?

The page finds a man's narrations by every name form the books head him under, which is a
wider net than the chain links: it will list a narration whose isnad was never resolved, or
whose English was never aligned, and then show him nothing to click. This measures that gap
the way a reader meets it — through the page itself, not through the index behind it.

For each narrator it fetches the narrations tab, and for every card on it asks two questions:
does his id appear as a link in the Arabic chain, and does it appear in the English one.

    python3 scripts/narrators/underline_check.py --top 20 --random 20
"""
import argparse
import json
import random
import re
import urllib.parse
import urllib.request

CARD = re.compile(r'<div class="hadith-entry">(.*?)(?=<div class="hadith-entry">|\Z)', re.S)
ARABIC_CHAIN = re.compile(r'class="hadith-chain[^"]*"[^>]*dir="rtl".*?</div>', re.S)


def get(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return response.read().decode("utf-8", "replace")


def post(url, body):
    request = urllib.request.Request(url, data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def narrators(es, top, sample, seed):
    """The heaviest men, and a random sample of the rest for a fairer picture."""
    chosen = []
    weighted = post(f"{es}/rewayaat_hadith/_search",
                    {"size": 0, "aggs": {"men": {"terms": {"field": "chain_links.p.keyword",
                                                           "size": top * 3}}}})
    for bucket in weighted["aggregations"]["men"]["buckets"]:
        if len(chosen) >= top:
            break
        if not bucket["key"].startswith("n"):
            continue
        chosen.append(bucket["key"])
    if sample:
        page = post(f"{es}/rewayaat_narrators/_search",
                    {"size": 400, "_source": ["person_id"], "query": {"function_score": {
                        "query": {"range": {"mention_count": {"gte": 1}}},
                        "random_score": {"seed": seed, "field": "_seq_no"}}}})
        pool = [h["_source"]["person_id"] for h in page["hits"]["hits"]
                if h["_source"]["person_id"] not in chosen]
        chosen += pool[:sample]
    return chosen


def page_of(site, person, slug, tab="narrations"):
    # The cards, not the compact index the tab now opens on: a compact row carries one
    # chain in the page's own language, and this measures both chains of the same
    # narration, which is what the figures before the redesign counted.
    return get(f"{site}/narrator/{person}/{urllib.parse.quote(slug)}"
               f"?tab={tab}&layout=cards")


# A link in a chain, with the words it was put on. The rijal panel's links carry the same
# class and are excluded by their own: they are the panel's own list, not the running text.
CHAIN_LINK = re.compile(r'<a class="chain-name" href="([^"]+)"[^>]*>(.*?)</a>', re.S)
ARABIC_LETTER = re.compile(r'[\u0600-\u06ff]')


def chains_of(site, hadith):
    """What the index holds for one narration: its links, and the men its chain names."""
    url = f"{site}/v1/hadith/{urllib.parse.quote(hadith, safe='')}"
    return url


def measure(html, person):
    """Per card: was he linked in the Arabic chain, and in the English one.

    The two chains are told apart by their own words rather than by their markup: a link
    whose text is Arabic stands in the Arabic chain, and one whose text is Latin stands in
    the translation. That holds however the card is laid out, which the class names have
    not always done.
    """
    arabic_hits = english_hits = cards = 0
    for card in CARD.findall(html):
        cards += 1
        in_arabic = in_english = False
        for href, words in CHAIN_LINK.findall(card):
            if not re.search(r'/narrator/' + person + r'(?:[/"?]|$)', href):
                continue
            if ARABIC_LETTER.search(words):
                in_arabic = True
            else:
                in_english = True
        arabic_hits += in_arabic
        english_hits += in_english
    return cards, arabic_hits, english_hits


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--site", default="http://localhost:8010")
    parser.add_argument("--es-host", default="http://localhost:9200")
    parser.add_argument("--top", type=int, default=15)
    parser.add_argument("--random", type=int, default=15, dest="sample")
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()
    random.seed(args.seed)

    people = narrators(args.es_host, args.top, args.sample, args.seed)
    totals = {"cards": 0, "ar": 0, "en": 0, "men": 0}
    rows = []
    for person in people:
        doc = post(f"{args.es_host}/rewayaat_narrators/_search",
                   {"size": 1, "query": {"term": {"person_id": person}},
                    "_source": ["slug", "display_en", "display_ar"]})
        hits = doc["hits"]["hits"]
        if not hits:
            continue
        source = hits[0]["_source"]
        try:
            html = page_of(args.site, person, source.get("slug") or "x")
        except Exception as problem:
            print(f"  {person}: page failed, {problem}")
            continue
        cards, arabic, english = measure(html, person)
        if not cards:
            continue
        totals["cards"] += cards
        totals["ar"] += arabic
        totals["en"] += english
        totals["men"] += 1
        rows.append((person, (source.get("display_en") or source.get("display_ar") or "")[:34],
                     cards, arabic, english))

    rows.sort(key=lambda r: (r[4] / r[2]) if r[2] else 0)
    if not args.quiet:
        print(f"{'person':<10} {'name':<34} {'cards':>5} {'arabic':>7} {'english':>8}")
        for person, name, cards, arabic, english in rows:
            print(f"{person:<10} {name:<34} {cards:>5} "
                  f"{arabic:>6}{'':1}{english:>8}")
    cards = totals["cards"] or 1
    print(f"\n  {totals['men']} narrators, {totals['cards']} narration cards")
    print(f"  his name linked in the Arabic chain : {totals['ar']:,} "
          f"({totals['ar'] / cards:.1%})")
    print(f"  his name linked in the English chain: {totals['en']:,} "
          f"({totals['en'] / cards:.1%})")


if __name__ == "__main__":
    main()
