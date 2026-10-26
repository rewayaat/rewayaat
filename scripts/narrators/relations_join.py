#!/usr/bin/env python3
"""One man, one row: join the teacher and student rows that name the same person.

A narrator page lists who he narrated from and to, and each row carries the books that
record the link. The rows are grouped by the name as a book spelled it, so one man
arrives as several rows whenever two books spell him differently — and they do, in three
ways. A book writes «أحمد ابن أبي عبد الله» where another writes «أحمد بن أبي عبد الله»;
Khoei's index is read in English and the rest in Arabic, so «هلال الحفار» and
«Hilal al-Haffar» sit one above the other; and a man filed under his kunya in one book is
filed under his nasab in the next, «Ibn Abi Nasr» beside «al-Bazanti».

So the rows are regrouped by the man rather than by the spelling: each name is looked up
against the names the corpus holds for every narrator, and rows reaching the same person
become one row carrying both books. A name reaching nobody, or reaching more than one, is
left exactly as it was — a row that cannot be placed is better than a row placed wrongly.

The lookup consults a man's aliases as well as the headings he is filed under, which is
what joins «Ibn Abi Nasr» to «al-Bazanti» and «أحمد بن عبد الواحد» to «أحمد بن عبدون».
Those merges are only as good as the alias lists: where an alias belongs to the wrong man,
two rows that are two men will become one. Measured over the whole index, 639 of 42,546
rows join, on 310 pages of 15,518.

Resolving also places a person id on every row it can, whether or not anything merged,
which is what lets the page link a teacher's name to his own page.
"""
import argparse
import json
import os
import sys
import urllib.request
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from narrator_schema import normalize_arabic, normalize_english  # noqa: E402

#: The six lists a narrator page draws its teachers and students from. Each is regrouped on
#: its own: «a book states this of him» and «he stands in a chain with him» are different
#: claims, kept in different fields, and merging across them would blur the two.
RELATION_FIELDS = ("narrated_from", "narrated_to", "chain_from", "chain_to",
                   "unsaid_from", "unsaid_to")

#: «ابن» and «بن» are the same word doing the same work. normalize_arabic folds the hamza
#: off «أحمد» but leaves these two apart, so «هارون ابن مسلم» and «هارون بن مسلم» were two
#: men. Folded here rather than in normalize_arabic, which the whole identity build keys on.
_LINK = {"ابن"}

#: A single word names nobody in particular. «بشر» alone, or «Ali», would collapse rows that
#: have nothing to do with each other, so a name must have at least two words to be looked up.
_LEAST_WORDS = 2


def arabic_key(name):
    """The Arabic folding, with «ابن» read as «بن»."""
    folded = normalize_arabic(name or "")
    if not folded:
        return ""
    return " ".join("بن" if word in _LINK else word for word in folded.split())


def english_key(name):
    return normalize_english(name or "")


def _has_arabic(text):
    return any("؀" <= ch <= "ۿ" for ch in text or "")


def lookup(documents):
    """Name → the one man who answers to it, in each script.

    A name two men answer to is dropped rather than given to either: the whole point is to
    join rows that are certainly one person, and «محمد بن علي» being ambiguous is a fact
    about the name, not a problem to be broken by picking a winner.
    """
    arabic, english = defaultdict(set), defaultdict(set)
    for doc in documents:
        pid = doc.get("person_id")
        if not pid:
            continue
        for name in list(doc.get("headings_ar") or []) + list(doc.get("names_ar") or []) \
                + [doc.get("display_ar")]:
            folded = arabic_key(name)
            if folded and len(folded.split()) >= _LEAST_WORDS:
                arabic[folded].add(pid)
        for name in list(doc.get("names_en") or []) + [doc.get("display_en")]:
            folded = english_key(name)
            if folded and len(folded.split()) >= _LEAST_WORDS:
                english[folded].add(pid)
    return ({name: next(iter(who)) for name, who in arabic.items() if len(who) == 1},
            {name: next(iter(who)) for name, who in english.items() if len(who) == 1})


def whose(row, tables):
    """The man a row names, read from its Arabic first and its transliteration second."""
    by_arabic, by_english = tables
    written = row.get("name") or ""
    folded = arabic_key(row.get("name_ar") or (written if _has_arabic(written) else ""))
    if folded in by_arabic:
        return by_arabic[folded]
    latin = english_key(written)
    if latin in by_english:
        return by_english[latin]
    return None


#: The letters a careful Arabic typesetter writes and a hasty one drops. Counting them is
#: how «أحمد بن أبي عبد الله» is told from «احمد ابن ابي عبد الله» — the same name, one of
#: them pointed. Length cannot tell them apart; the unpointed spelling is the longer of the
#: two, so picking the longer printed the worse one.
_POINTED = set("أإآؤئةى") | {chr(c) for c in range(0x064B, 0x0653)}


def _pointing(name):
    return sum(1 for ch in name or "" if ch in _POINTED)


def _fuller(one, other):
    """Which of two rows gives the merged row its name.

    The Arabic wins over a transliteration — an Arabic page should not read «روى عنه Abu
    al-Hasan Musa». Between two Arabic spellings of the same name the better pointed one
    wins; between two different names, the fuller, since «سليم بن قيس الهلالي» says more
    than «سليم بن قيس» and they are the same man.
    """
    left, right = one.get("name") or "", other.get("name") or ""
    if _has_arabic(right) and not _has_arabic(left):
        return other
    if _has_arabic(left) and not _has_arabic(right):
        return one
    if _has_arabic(left) and arabic_key(left) == arabic_key(right):
        return other if _pointing(right) > _pointing(left) else one
    return other if len(right) > len(left) else one


def _merge(rows, pid, slugs, displays=None):
    """One row out of several, carrying every book that recorded the link."""
    chosen = rows[0]
    for row in rows[1:]:
        chosen = _fuller(chosen, row)
    merged = dict(chosen)
    for field in ("books", "books_ar"):
        seen, out = set(), []
        for row in rows:
            for book in row.get(field) or []:
                if book not in seen:
                    seen.add(book)
                    out.append(book)
        if out:
            merged[field] = out
    # The Arabic of the name may be on a row that did not win the display, and an Arabic
    # page reads name_ar: «روى عنه Abu al-Hasan Musa» is what its absence looks like.
    if not merged.get("name_ar"):
        for row in rows:
            if row.get("name_ar"):
                merged["name_ar"] = row["name_ar"]
                break
    if pid:
        merged["person_id"] = pid
        if slugs.get(pid):
            merged["slug"] = slugs[pid]
        # The man's own display names, so a page can read in its own script. A row carries
        # the name the book printed, and the book decides the script: Khoei's index was
        # read in English, so «أحمد بن الحسن الميثمي» reaches an Arabic page as "Ahmad ibn
        # al-Hasan al-Mithamani", and the Arabic books reach an English one as Arabic.
        # Only offered here — which of the two a page shows is the template's decision,
        # and it keeps the book's own wording whenever that is already in the right script.
        for field in ("display_ar", "display_en"):
            name = (displays or {}).get(pid, {}).get(field)
            if name:
                merged[field] = name
    return merged


def _spelling(row):
    """The fallback key for a row naming nobody we know: its own folded name.

    Arabic and Latin cannot share this key — normalize_arabic empties a Latin name — and
    that is right: two spellings we cannot resolve are two spellings we cannot join.
    """
    written = row.get("name") or ""
    folded = arabic_key(row.get("name_ar") or (written if _has_arabic(written) else ""))
    if folded:
        return ("arabic", folded)
    latin = english_key(written)
    return ("latin", latin) if latin else ("row", id(row))


def settled_for(settled, subject, field, row):
    """The man a judge settled this row on, where no name reaches one man.

    A name two men answer to is dropped by `whose` on purpose, and about half of those
    rows can still be settled by reading the page: the dating, the direction of the claim,
    which candidate the served chains stand beside him, which candidate's own entry names
    him back. That reading is done offline and lands in a table keyed by the man, the list
    and the folded name — the same row the page prints. The judge's answer is consulted
    only after the names have failed, so it can never overrule a name that does reach one
    man.
    """
    if not settled or not subject or not field:
        return None
    kind, key = _spelling(row)
    if kind not in ("arabic", "latin"):
        return None
    return (settled.get(f"{subject}|{field}|{key}") or {}).get("person_id")


def join_rows(rows, tables, slugs, displays=None, settled=None, subject=None, field=None):
    """Regroup one relation list by the man each row names, keeping the printed order."""
    if not rows:
        return []
    order, groups = [], defaultdict(list)
    by_name, by_judge = set(), set()
    for row in rows:
        pid = whose(row, tables)
        if pid:
            by_name.add(pid)
        else:
            pid = settled_for(settled, subject, field, row)
            if pid:
                by_judge.add(pid)
        # A row reaching nobody falls back to its own spelling, which is what the grouping
        # did before any of this: two rows that are the same name are the same row whether
        # or not we can say whose name it is. Only a row with no key at all stands alone,
        # so two unplaceable *different* names never collapse into each other.
        gkey = ("man", pid) if pid else _spelling(row)
        if gkey not in groups:
            order.append(gkey)
        groups[gkey].append(row)
    out = []
    for gkey in order:
        members = groups[gkey]
        pid = gkey[1] if gkey[0] == "man" else None
        joined = (_merge(members, pid, slugs, displays)
                  if (len(members) > 1 or pid) else members[0])
        # Said on the row, because it is a different kind of claim from a name that
        # reaches one man, and a reader is entitled to know which one he is reading.
        if pid and pid in by_judge and pid not in by_name:
            joined["settled_by"] = "judge"
        out.append(joined)
    return out


def load_settled(path=None):
    """The judged rows, or nothing at all if the table has not been built."""
    path = path or os.path.join(
        os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
        "scripts", "data", "narrators", "settled_relations.json")
    if not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def join(documents, settled=None):
    """Regroup every relation list on every document. Returns how many rows went away."""
    tables = lookup(documents)
    slugs = {doc.get("person_id"): doc.get("slug") for doc in documents if doc.get("person_id")}
    displays = {doc["person_id"]: {"display_ar": doc.get("display_ar"),
                                   "display_en": doc.get("display_en")}
                for doc in documents if doc.get("person_id")}
    removed = 0
    for doc in documents:
        for field in RELATION_FIELDS:
            rows = doc.get(field)
            if not rows:
                continue
            joined = join_rows(rows, tables, slugs, displays, settled,
                               doc.get("person_id"), field)
            removed += len(rows) - len(joined)
            doc[field] = joined
    return removed


# --- applying it to an index that is already built -------------------------------------

def _post(host, path, body):
    request = urllib.request.Request(host + path, data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(request))


def _scan(host, index, fields):
    """Every document, one page held at a time."""
    page = _post(host, f"/{index}/_search?scroll=5m",
                 {"size": 500, "_source": fields, "query": {"match_all": {}}})
    scroll = page.get("_scroll_id")
    while True:
        hits = page["hits"]["hits"]
        if not hits:
            return
        for hit in hits:
            yield hit["_source"]
        page = _post(host, "/_search/scroll", {"scroll": "5m", "scroll_id": scroll})


def _bulk(host, index, updates):
    lines = []
    for pid, doc in updates:
        lines.append(json.dumps({"update": {"_index": index, "_id": pid}}))
        lines.append(json.dumps({"doc": doc}, ensure_ascii=False))
    body = "\n".join(lines) + "\n"
    request = urllib.request.Request(host + "/_bulk", data=body.encode(),
                                     headers={"Content-Type": "application/x-ndjson"})
    answer = json.load(urllib.request.urlopen(request))
    return sum(1 for item in answer.get("items", [])
               if item.get("update", {}).get("error"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live", action="store_true", help="write the joined rows back")
    parser.add_argument("--es-host", default="http://localhost:9200")
    parser.add_argument("--index", default="rewayaat_narrators")
    parser.add_argument("--show", help="print one person's joined rows and stop")
    args = parser.parse_args()

    settled = load_settled()
    if settled:
        print(f"  {len(settled):,} rows a judge settled", file=sys.stderr)
    names = ["person_id", "slug", "headings_ar", "names_ar", "display_ar",
             "names_en", "display_en"]
    print("reading the names every narrator answers to", file=sys.stderr)
    people = list(_scan(args.es_host, args.index, names))
    tables = lookup(people)
    slugs = {p["person_id"]: p.get("slug") for p in people if p.get("person_id")}
    displays = {p["person_id"]: {"display_ar": p.get("display_ar"),
                                 "display_en": p.get("display_en")}
                for p in people if p.get("person_id")}
    print(f"  {len(people):,} people; {len(tables[0]):,} Arabic names and "
          f"{len(tables[1]):,} transliterations name exactly one man", file=sys.stderr)

    updates, removed, touched, rows = [], 0, 0, 0
    failures = 0
    for doc in _scan(args.es_host, args.index, ["person_id"] + list(RELATION_FIELDS)):
        pid = doc.get("person_id")
        if not pid:
            continue
        changed = {}
        for field in RELATION_FIELDS:
            before = doc.get(field) or []
            if not before:
                continue
            rows += len(before)
            after = join_rows(before, tables, slugs, displays, settled,
                              pid, field)
            if after != before:
                changed[field] = after
                removed += len(before) - len(after)
        if not changed:
            continue
        touched += 1
        if args.show and pid == args.show:
            print(json.dumps(changed, ensure_ascii=False, indent=2))
            return
        updates.append((pid, changed))
        if args.live and len(updates) >= 400:
            failures += _bulk(args.es_host, args.index, updates)
            updates = []
    if args.live and updates:
        failures += _bulk(args.es_host, args.index, updates)

    print(f"{rows:,} relation rows, {removed:,} of them joined into another")
    print(f"{touched:,} narrators changed")
    if args.live:
        print(f"written to {args.index}" + (f"; {failures} failed" if failures else ""))
    else:
        print("nothing written — pass --live")


if __name__ == "__main__":
    main()
