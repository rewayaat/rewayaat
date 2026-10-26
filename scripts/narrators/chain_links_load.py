#!/usr/bin/env python3
"""Puts the resolved chain links on the narrations themselves, for the page to render.

One field per narration, `chain_links`: the spans of its isnād that name a man we can link,
each as {s, e, t, p} — where the name starts, where it ends, the text it covers, and whose
page it points at. `ChainLinks.render` reads them back when the hadith card is built.

The text is carried with the offsets on purpose. The spans were measured against a chain
exported earlier; the page segments the chain again when it renders. If anything between the
two readings has moved, an offset alone would slice a name in half, so a span whose covered
text no longer matches is relocated when the name occurs exactly once in the served text, and
dropped otherwise. The same check runs again in Java, since the index outlives this script.

Usage:
    python3 scripts/narrators/chain_links_load.py --dry-run
    python3 scripts/narrators/chain_links_load.py --live --index rewayaat_hadith
"""
import argparse
from collections import Counter
import json
import os
import sys

W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
sys.path.insert(0, f'{W}/scripts/narrators')
from chain_mentions import not_a_man  # noqa: E402

TMP = f'{W}/tmp'
LINKED = ("one", "chosen", "convention")


def slugs(host, index="rewayaat_narrators", names=None, titles=None):
    """person_id → slug, so a link lands on the canonical page instead of a redirect.

    Paged, because a plain search stops at 10,000 hits and the index holds half again as
    many men: asking for all of them in one request quietly left the rest without a slug,
    and their links went out to the redirect instead of the page.

    Given a dict as `names`, it is also filled with person_id → display_ar, which the
    candidates of an undecided step need so the page can name them without a second lookup.
    `titles` likewise gets person_id → his first epithet: three men can all be «أحمد بن
    محمد» on the page, and the epithet («الكوفي», «الخليلي») is the one thing that tells
    them apart at a glance. Where a man carries no epithet the book that files him is used
    instead: 1,057 of the 8,879 undecided steps had two candidates reading the same, and
    414 had every candidate reading the same — three identical links, which answers
    nothing. «أبو زكريا» is the one this was found on.
    """
    import urllib.request
    found, after = {}, None
    while True:
        query = {"size": 2000,
                 "_source": ["person_id", "slug", "display_ar", "titles",
                             "entries.book_title_ar"],
                 "query": {"match_all": {}}, "sort": [{"person_id": "asc"}]}
        if after:
            query["search_after"] = after
        request = urllib.request.Request(f'{host}/{index}/_search',
                                         data=json.dumps(query).encode(),
                                         headers={"Content-Type": "application/json"})
        hits = json.load(urllib.request.urlopen(request))["hits"]["hits"]
        if not hits:
            return found
        for hit in hits:
            found[hit["_source"]["person_id"]] = hit["_source"].get("slug") or ""
            if titles is not None:
                source = hit["_source"]
                entries = source.get("entries") or []
                titles[source["person_id"]] = (
                    (source.get("titles") or [""])[0]
                    or (entries[0].get("book_title_ar") if entries else "")
                    or "")
            if names is not None:
                names[hit["_source"]["person_id"]] = hit["_source"].get("display_ar") or ""
        after = hits[-1]["sort"]


def numbers(path):
    """id → the narration's number, for naming the one a chain points back at."""
    found = {}
    with open(path) as handle:
        for line in handle:
            row = json.loads(line)
            found[row["id"]] = row.get("number") or ""
    return found


def served_chains(path):
    """The chain text as the index the site reads actually holds it."""
    chains = {}
    with open(path) as handle:
        for line in handle:
            row = json.loads(line)
            chains[row["id"]] = row["chain_ar"]
    return chains


def place(span, text, chain, counts):
    """Where this name sits in the served chain, or nothing if it cannot be found once."""
    start, end = span
    if chain[start:end] == text:
        return start, end
    found = chain.find(text)
    if found < 0:
        counts["name gone from the served text"] += 1
        return None
    if chain.find(text, found + 1) >= 0:
        counts["name occurs more than once, offsets unsafe"] += 1
        return None
    counts["name found at a new offset"] += 1
    return found, found + len(text)


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--resolved", default=f'{TMP}/narrators_chains/resolved.jsonl')
    parser.add_argument("--served", default=f'{TMP}/narrators_chains/chains_served.jsonl')
    parser.add_argument("--index", default="rewayaat_hadith")
    parser.add_argument("--es-host", default="http://localhost:9200")
    parser.add_argument("--live", action="store_true", help="write the field")
    parser.add_argument("--dry-run", action="store_true", help="count, write nothing")
    args = parser.parse_args()

    chains = served_chains(args.served)
    numbered = numbers(f'{TMP}/narrators_chains/chains_served.jsonl')
    name_of, title_of = {}, {}
    slug_of = slugs(args.es_host, names=name_of, titles=title_of)
    print(f"  {len(slug_of):7,}  people the links can point at")
    print(f"  {len(chains):7,}  narrations in the index the site reads")

    counts, updates = Counter(), []
    with open(args.resolved) as handle:
        for line in handle:
            row = json.loads(line)
            chain = chains.get(row["id"])
            if chain is None:
                counts["narration not in the served index"] += 1
                continue
            links = []
            for mention in row["mentions"]:
                if mention["outcome"] not in LINKED:
                    continue
                person = mention.get("person_id") or mention.get("imam_id")
                if not person:
                    continue
                counts["links to place"] += 1
                where = place((mention["start"], mention["end"]), mention["text"], chain, counts)
                if not where:
                    continue
                links.append({"s": where[0], "e": where[1],
                              "t": chain[where[0]:where[1]], "p": person,
                              "g": slug_of.get(person, "")})
            # Every name in the isnād, in order, linked or not — the rijāl view reads the
            # chain as a sequence and a name nobody could identify is part of what it says.
            # A chain of three men we know and three we do not is weaker evidence than one
            # of six we know, and a reader can only see that if the gaps are drawn too.
            people = []
            for mention in row["mentions"]:
                if mention["kind"] not in ("name", "imam", "collective", "relative",
                                           "backref_man", "backref_chain"):
                    continue
                # A book's notice of when an Imam was born and died carries no isnād, and
                # the reader was giving al-Kāfī 1319 five steps out of «وُلِدَ», «رَجَبٍ»
                # and «اثْنَتَيْ عَشْرَةَ وَمِائَتَيْنِ». `chain_mentions` refuses these
                # now; asked again here so the index is right without the whole corpus
                # being read and resolved again, since not one of them was ever a link.
                if not_a_man(mention["text"]):
                    counts["a date or a verb, not a man"] += 1
                    continue
                where = place((mention["start"], mention["end"]), mention["text"], chain,
                              Counter())
                if not where:
                    continue
                person = mention.get("person_id") or mention.get("imam_id") or ""
                step = {"s": where[0], "e": where[1], "t": chain[where[0]:where[1]],
                        "p": person if mention["outcome"] in LINKED else "",
                        "g": slug_of.get(person, "") if mention["outcome"] in LINKED else "",
                        "k": mention["kind"], "o": mention["outcome"]}
                # «وبهذا الإسناد» is the chain above repeated, not a man. It carries the
                # narration it points at so the page can name it instead of leaving the
                # reader with a step that says nothing.
                # How many other men answer to this name. Only a `one` carries it, and only
                # when there are any: the claim the reader is shown for a `one` is that a
                # single man bears the name, and where a filing won it instead the step has
                # to say so. See chain_resolve.contested_by.
                if mention.get("also"):
                    step["a"] = mention["also"]
                    counts["steps whose name other men answer to as well"] += 1
                if mention.get("refers_to"):
                    step["r"] = mention["refers_to"]
                    step["rn"] = numbered.get(mention["refers_to"], "")
                # A pointer inside a quoted chain points back inside the book it was
                # quoted from. There is nothing here to link it to, so it carries the
                # name of that book and the page says so rather than guessing.
                if mention.get("quoted_from"):
                    step["qf"] = mention["quoted_from"]
                # The resolver found between two and nine men of this name and would not
                # choose. The panel used to say "not found" for these, which tells the reader
                # nobody is known when three men are. The candidates travel with the step,
                # named and slugged, so the page lists them without a second lookup. Nine is
                # the most the resolver ever produced (202 chains' worth), so the cap loses
                # nothing today and keeps a row from growing without bound if that changes.
                if mention["outcome"] == "several" and mention.get("candidates"):
                    step["c"] = [{"p": c, "n": name_of.get(c, ""), "g": slug_of.get(c, ""),
                                  "h": title_of.get(c, "")}
                                 for c in mention["candidates"][:9]]
                    counts["undecided steps carrying their candidates"] += 1
                    if mention.get("refused"):
                        step["rf"] = mention["refused"]
                people.append(step)
            people.sort(key=lambda row: row["s"])
            # Written whenever the chain has steps at all, not only when one of them is a
            # link. A chain of four men no book identifies is still a chain, and a reader
            # is better served seeing it than seeing nothing — and «وبهذا الإسناد» with an
            # unidentifiable name after it was dropping the pointer along with the name.
            if links or people:
                updates.append({"_id": row["id"], "chain_links": links,
                                "chain_people": people})
                counts["links placed"] += len(links)
                if links:
                    counts["narrations with at least one link"] += 1
                else:
                    counts["narrations drawn with no link in them"] += 1
            else:
                counts["narrations with no chain to draw"] += 1

    for label, count in counts.most_common():
        print(f"  {count:7,}  {label}")
    if args.dry_run or not args.live:
        return

    from elasticsearch import Elasticsearch, helpers
    es = Elasticsearch(args.es_host, request_timeout=120)
    loaded, failed = helpers.bulk(
        es, ({"_op_type": "update", "_index": args.index, "_id": u["_id"],
              "doc": {"chain_links": u["chain_links"],
                      "chain_people": u.get("chain_people") or []}} for u in updates),
        chunk_size=500, raise_on_error=False)
    es.indices.refresh(index=args.index)
    print(f"\nwrote chain_links on {loaded:,} narrations in {args.index}"
          + (f"; {len(failed)} failed" if failed else ""))
    if failed:
        print(json.dumps(failed[:2], ensure_ascii=False)[:600])


if __name__ == "__main__":
    main()
