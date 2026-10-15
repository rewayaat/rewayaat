#!/usr/bin/env python3
"""Decides which man each name in an isnād is, and refuses to decide where the books do not.

The cutter (`chain_mentions.py`) gives spans of text. This gives them people, or says it
cannot. Four outcomes, and only the first two become a link:

- `one` — exactly one man in the rijāl books is filed under that name. Nothing to decide.
- `chosen` — several are, and one of them is attested transmitting with the name beside him in
  this very chain: «أحمد بن محمد» after «محمد بن يحيى» is the Aḥmad whom a book shows
  receiving from that Muḥammad. The reason is recorded with the link.
- `several` — several men hold the name and nothing in the books chooses between them. This is
  the honest answer for «أحمد بن محمد» standing alone: al-Bazanṭī, al-Barqī and Ibn ʿĪsā are
  all called that, and a reader is better served by the list than by our guess.
- `none` — no entry anywhere is filed under that name.

Two rules keep the candidate lists from filling with rubbish. Only men with an entry in a
classical book are targets: Khoei's Muʿjam indexes chains as well as men, so a Khoei-only
one-line record is usually the chain mention itself rather than a biography — eight of the
thirteen men holding the name «علي بن إبراهيم» are of that kind. And a name is matched in
the direction the isnād states it: for «X عن Y», Y is X's teacher, so the man before is his
student and the man after is his teacher. Matching without direction was what made the
relation gate necessary in the first place.

Usage:
    python3 scripts/narrators/chain_resolve.py --show 25
    python3 scripts/narrators/chain_resolve.py --out tmp/narrators_chains/resolved.jsonl
"""
import argparse
from collections import Counter, defaultdict
import json
import os
import re
import sys

W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
sys.path.insert(0, f'{W}/scripts/narrators')
import imams  # noqa: E402
from narrator_schema import normalize_arabic  # noqa: E402

TMP = f'{W}/tmp'
CONVENTIONS = f'{W}/scripts/data/narrators/chain_conventions.json'
MENTIONS = f'{TMP}/narrators_chains/mentions.jsonl'
PEOPLE = f'{TMP}/narrators_chains/people_index.json'

# The six books that file men as men. Khoei and Māmaqānī index narrations too, so a record
# holding nothing but a chain clause is not a man anybody can be linked to.
CLASSICAL = {"tusi", "duafa", "najashi", "ardabili", "kashshi", "fihrist"}

_PUNCT = re.compile(r"[،؛؟.:()\[\]\"'«»/\\|]")
_PERSIAN = str.maketrans({"ی": "ي", "ک": "ك", "ۀ": "ه", "ە": "ه", "ي": "ي"})


def key(name):
    """One folded key for a name, whether it came from a chain or from a book's heading."""
    if not name:
        return ""
    return normalize_arabic(_PUNCT.sub(" ", name).translate(_PERSIAN))


def same_name(one, other):
    """Whether two keys name the same man: equal, or one the opening of the other.

    A chain writes «صفوان» where a book writes «صفوان بن يحيى»; the shorter is the opening of
    the longer, which is how Arabic names shorten. One token is allowed to stand for a longer
    name only when it is the whole of what the book itself recorded, so «محمد» never matches
    «محمد بن يحيى».
    """
    if not one or not other:
        return False
    if one == other:
        return True
    first, second = one.split(), other.split()
    short, long = (first, second) if len(first) < len(second) else (second, first)
    return len(short) >= 2 and long[:len(short)] == short


# «أبيه» names nobody. As a teacher's name it would hand a man to whichever candidate was
# listed first, and as a name in its own right it was pointing six chains at a Qummi shaykh
# who happens to carry «أبيه» among his aliases. It is refused in both places.
RELATIVE = {"ابيه", "ابيها", "عمه", "جده", "اخيه", "ابنه", "امه", "ابي", "جدي", "ابايه",
            "ابوه", "اخوه", "ابويه", "عمته", "خاله", "عميه", "ابائه", "اجداده"}


def father_name(person):
    """The father's name as the book itself wrote it: what stands after the first «بن».

    A man's own heading carries his father — «علي بن إبراهيم بن هاشم» names Ibrāhīm b. Hāshim
    — so «عن أبيه» after him needs no guess, only the nasab the rijālī already recorded.
    """
    longest = ""
    for name in list(person.get("headings_ar") or []) + [person.get("display_ar") or ""]:
        folded = key(name)
        if len(folded.split()) > len(longest.split()):
            longest = folded
    tokens = longest.split()
    if "بن" not in tokens:
        return ""
    return " ".join(tokens[tokens.index("بن") + 1:])


def load_people(path, books):
    """Name → the men filed under it, and each man's attested teachers, students and Imams.

    The names are kept in two tiers, because they are not equal evidence. A heading is what a
    rijālī filed the man under; an alias is any other name his entry mentions, and those lists
    carry names lifted out of chains quoted inside the entry. Isḥāq b. ʿAmmār holds «ابن أبي
    عمير» among his aliases, Zurāra holds «محمد بن مسلم», and the audit caught both of them
    being handed somebody else's name. So an alias is consulted only when no heading matches.
    """
    with open(path) as handle:
        people = json.load(handle)
    headings, aliases = defaultdict(set), defaultdict(set)
    teachers, students, by_id = {}, {}, {}
    imams_of, fathers = {}, {}
    for person in people:
        if books and not books & set(person.get("books") or []):
            continue
        pid = person["person_id"]
        by_id[pid] = person
        own = set(person.get("headings_ar") or [])
        if person.get("display_ar"):
            own.add(person["display_ar"])
        for name in own:
            folded = key(name)
            if folded and folded not in RELATIVE:
                headings[folded].add(pid)
        for name in person.get("names_ar") or []:
            folded = key(name)
            if folded and folded not in RELATIVE and folded not in headings:
                aliases[folded].add(pid)
        teachers[pid] = {key(r.get("name_ar") or r.get("name"))
                         for field in ("narrated_from", "chain_from", "unsaid_from")
                         for r in person.get(field) or []}
        students[pid] = {key(r.get("name_ar") or r.get("name"))
                         for field in ("narrated_to", "chain_to", "unsaid_to")
                         for r in person.get(field) or []}
        teachers[pid].discard("")
        students[pid].discard("")
        imams_of[pid] = set()
        for field in ("imams_from", "imams_to"):
            for relation in person.get(field) or []:
                name = relation.get("name_ar") or relation.get("name") or ""
                imams_of[pid] |= imams.from_relation(name, key(name))
        fathers[pid] = father_name(person)
    return {"headings": headings, "aliases": aliases, "teachers": teachers,
            "students": students, "by_id": by_id, "imams_of": imams_of, "fathers": fathers}


def held_by(folded, data):
    """The men filed under a name — by their own heading, or failing that by an alias."""
    if not folded:
        return []
    found = data["headings"].get(folded)
    if found:
        return sorted(found)
    return sorted(data["aliases"].get(folded, ()))


def support(pid, before, after, teachers, students):
    """Why this candidate and not the others: a book shows him beside the name next to him."""
    if before in RELATIVE:
        before = ""
    if after in RELATIVE:
        after = ""
    for name in teachers.get(pid, ()):  # the man after him in the chain is his teacher
        if name not in RELATIVE and same_name(name, after):
            return f"narrated from {name}"
    for name in students.get(pid, ()):  # the man before him received from him
        if name not in RELATIVE and same_name(name, before):
            return f"narrated to {name}"
    return ""


def one_of(candidates, data):
    """A single man, or nothing: the holder index's answer when it is not ambiguous."""
    return candidates[0] if len(candidates) == 1 else ""


def conventions(path):
    """The chain conventions the auditors named, as the resolver reads them.

    A convention is not a fifth kind of evidence smuggled in: it is what the rijālī literature
    says about reading a particular author's isnāds — «محمد بن يحيى» opening a chain in al-Kāfī
    is al-ʿAṭṭār — written down only where the auditors agreed and the man they named resolves
    to one person. Where the file is absent the resolver simply refuses those names as before.
    """
    try:
        with open(path) as handle:
            table = json.load(handle)
    except FileNotFoundError:
        return {"settles": {}, "corrects": {}, "everywhere": {}}
    return table


def by_convention(table, book, folded):
    """The man a book's own practice names, with the reason to show beside the link."""
    row = (table.get("settles") or {}).get(f"{book}\t{folded}")
    if row and row.get("applied", True):
        return row["person_id"], f"{book} reads this name as one man"
    person = (table.get("everywhere") or {}).get(folded)
    if person:
        return person, "the books read this name as one man"
    return "", ""


def resolve_chain(mentions, data, book="", table=None):
    """Give each mention in one chain an outcome, in three passes over the same chain.

    Names first, because the other two kinds are read off their neighbours: an Imam's kunya is
    settled by which Imams the books say the narrator beside him transmitted from, and «أبيه»
    is settled by whose father he is.
    """
    keys = [key(m["text"]) for m in mentions]
    rows = [dict(m) for m in mentions]
    table = table or {}

    for position, row in enumerate(rows):
        if row["kind"] != "name":
            row["outcome"] = row["kind"]
            continue
        row["target"] = "narrator"
        candidates = held_by(keys[position], data)
        before = keys[position - 1] if position else ""
        after = keys[position + 1] if position + 1 < len(keys) else ""
        if not candidates:
            row["outcome"] = "none"
        elif len(candidates) == 1:
            row["outcome"], row["person_id"] = "one", candidates[0]
        else:
            backed = [(pid, support(pid, before, after, data["teachers"], data["students"]))
                      for pid in candidates]
            backed = [(pid, why) for pid, why in backed if why]
            if len(backed) == 1:
                row["outcome"], row["person_id"], row["because"] = "chosen", *backed[0]
            else:
                row["outcome"], row["candidates"] = "several", candidates
                if backed:
                    row["backed"] = [pid for pid, _ in backed]

    for position, row in enumerate(rows):
        if row["kind"] != "imam" and not (keys[position] in imams.CHAIN_FORMS
                                          and row.get("outcome") in ("several", "none")):
            continue
        found = imams.identify(keys[position])
        if not found:
            # blessed, but not one of the fourteen: «أبي ذر رضي الله عنه», «جبريل عليه السلام»
            row["outcome"] = "none"
            continue
        row["target"] = "imam"
        row.pop("candidates", None)
        row.pop("backed", None)
        if len(found) == 1:
            row["outcome"], row["imam_id"] = "one", found[0]
            continue
        narrower = set()
        for neighbour in (position - 1, position + 1):
            if 0 <= neighbour < len(rows):
                pid = rows[neighbour].get("person_id")
                if pid:
                    narrower |= data["imams_of"].get(pid, set())
        backed = [i for i in found if i in narrower]
        if len(backed) == 1:
            row["outcome"], row["imam_id"] = "chosen", backed[0]
            row["because"] = "the books file the narrator beside him with this Imam"
        else:
            row["outcome"], row["candidates"] = "several", list(found)

    for position, row in enumerate(rows):
        if row.get("kind") not in ("name", "imam"):
            continue
        if row["outcome"] in ("several", "none"):
            person, why = by_convention(table, book, keys[position])
            if person:
                row["outcome"] = "convention"
                row["because"] = why
                row.pop("candidates", None)
                row.pop("backed", None)
                row["target"] = "imam" if person in imams.BY_ID else "narrator"
                row["imam_id" if person in imams.BY_ID else "person_id"] = person
            continue
        fix = (table.get("corrects") or {}).get(f"{book}\t{keys[position]}")
        if fix and fix.get("applied") and row["outcome"] in ("one", "chosen"):
            # the audit found this link wrong and named the man; a wrong link is worse than
            # a missing one, so the correction stands over our own match
            row["outcome"] = "convention"
            row["because"] = "the audit read this chain and named the man"
            row.pop("person_id", None)
            row.pop("imam_id", None)
            row["target"] = "imam" if fix["person_id"] in imams.BY_ID else "narrator"
            row["imam_id" if fix["person_id"] in imams.BY_ID else "person_id"] = fix["person_id"]

    for position, row in enumerate(rows):
        if row["kind"] != "relative":
            continue
        folded = keys[position]
        steps = 2 if folded in ("جده", "جدي") else 1
        if folded not in ("ابيه", "ابيها", "ابي", "جده", "جدي", "ابوه"):
            row["outcome"] = "relative"
            continue
        previous = rows[position - 1] if position else {}
        if previous.get("imam_id"):
            imam = previous["imam_id"]
            for _ in range(steps):
                imam = imams.FATHER_OF.get(imam, "")
            if imam:
                row["target"], row["outcome"], row["imam_id"] = "imam", "chosen", imam
                row["because"] = f"the father of {imams.BY_ID[previous['imam_id']]['name_en']}"
                continue
        pid = previous.get("person_id")
        name = data["fathers"].get(pid, "") if pid else ""
        for _ in range(steps - 1):
            tokens = name.split()
            name = " ".join(tokens[tokens.index("بن") + 1:]) if "بن" in tokens else ""
        candidates = held_by(name, data) if name else []
        if not candidates:
            row["outcome"] = "relative"
        elif len(candidates) == 1:
            row["target"], row["outcome"], row["person_id"] = "narrator", "chosen", candidates[0]
            row["because"] = f"the nasab of the man before him names his father «{name}»"
        else:
            row["target"], row["outcome"], row["candidates"] = "narrator", "several", candidates

    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--mentions", default=MENTIONS)
    parser.add_argument("--people", default=PEOPLE)
    parser.add_argument("--out", default=f'{TMP}/narrators_chains/resolved.jsonl')
    parser.add_argument("--conventions", default=CONVENTIONS)
    parser.add_argument("--all-books", action="store_true",
                        help="let Khoei-only and Māmaqānī-only records be link targets too")
    parser.add_argument("--show", type=int, default=0, help="print N of each outcome")
    parser.add_argument("--dry-run", action="store_true", help="measure, write nothing")
    args = parser.parse_args()

    books = set() if args.all_books else CLASSICAL
    data = load_people(args.people, books)
    table = conventions(args.conventions)
    print(f"  {len(table.get('settles') or {}):7,}  names a book's own practice settles, "
          f"{len(table.get('everywhere') or {}):,} of them in every book")
    by_id = data["by_id"]
    print(f"  {len(by_id):7,}  men can be linked to"
          f"{'' if args.all_books else ' (an entry in a classical book)'}")
    print(f"  {len(data['headings']):7,}  names a book files one of them under, "
          f"{len(data['aliases']):,} more they are called elsewhere")

    outcomes, examples, chains = Counter(), defaultdict(list), 0
    by_kind, per_book = Counter(), defaultdict(Counter)
    rows = []
    with open(args.mentions) as handle:
        for line in handle:
            row = json.loads(line)
            chains += 1
            resolved = resolve_chain(row["mentions"], data, row["book"], table)
            for mention in resolved:
                outcomes[mention["outcome"]] += 1
                by_kind[(mention["kind"], mention["outcome"])] += 1
                per_book[row["book"]][mention["outcome"] in
                                     ("one", "chosen", "convention")] += 1
                pool = examples[mention["outcome"]]
                if len(pool) < max(args.show, 0):
                    pool.append((row["book"], mention))
            rows.append({"id": row["id"], "book": row["book"], "mentions": resolved})

    names = sum(outcomes[k] for k in ("one", "chosen", "convention", "several", "none"))
    print(f"\n  {chains:7,}  chains, {sum(outcomes.values()):,} mentions, {names:,} of them names")
    for outcome in ("one", "chosen", "convention", "several", "none"):
        count = outcomes[outcome]
        print(f"      {count:7,}  {100 * count / names:5.1f}%  {outcome}")
    linked = outcomes["one"] + outcomes["chosen"] + outcomes["convention"]
    print(f"      {linked:7,}  {100 * linked / names:5.1f}%  become a link")
    for outcome in ("relative", "collective", "text"):
        print(f"      {outcomes[outcome]:7,}         {outcome} (not a link)")

    print("\n  by what the mention is:")
    for kind in ("name", "imam", "relative", "collective", "text"):
        held = sum(c for (k, _), c in by_kind.items() if k == kind)
        if not held:
            continue
        parts = " ".join(f"{o} {by_kind[(kind, o)]:,}"
                         for o in ("one", "chosen", "convention", "several", "none", kind)
                         if by_kind[(kind, o)])
        linked = (by_kind[(kind, "one")] + by_kind[(kind, "chosen")]
                  + by_kind[(kind, "convention")])
        print(f"      {kind:11s} {held:7,}  {100 * linked / held:5.1f}% linked   {parts}")

    print("\n  by book:")
    for book, counts in sorted(per_book.items(), key=lambda kv: -sum(kv[1].values())):
        held = sum(counts.values())
        print(f"      {counts[True]:7,}/{held:7,}  {100 * counts[True] / held:5.1f}%  {book}")

    for outcome in ("one", "chosen", "convention", "several", "none"):
        if not examples[outcome]:
            continue
        print(f"\n  {outcome}:")
        for book, mention in examples[outcome]:
            who = mention.get("person_id") or mention.get("imam_id", "")
            name = (by_id[who]["display_ar"] if who in by_id
                    else imams.BY_ID[who]["name_ar"] if who in imams.BY_ID else "")
            extra = mention.get("because", "") or f"{len(mention.get('candidates', []))} men"
            print(f"      «{mention['text'][:40]}» {book[:18]:18s} {who:8s} {name[:28]:28s} {extra}")

    if args.dry_run:
        return
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"\nwrote {len(rows):,} chains -> {args.out}")


if __name__ == "__main__":
    main()
