#!/usr/bin/env python3
"""Reads a hijri year written in Arabic words, so a misread one can be caught.

Death years were never parsed by rule: `extract_openiti.py` leaves the field empty and the
numbers on the pages were read by agents in phase 1. The field audit found 7 of 108 printed
years contradicted by their own passage, and the misreadings are mechanical — «إحدى
وثلاثمائة» is 301 and the page prints 311, «ثمان وثلاثمائة» is 308 and the page prints 318,
«الف واحد عشر» is 1011 and the page prints 1101. A year in words is not a judgement call: it
is arithmetic, and arithmetic is the one thing that should never have been asked of a reader.

Usage:
    python3 scripts/narrators/arabic_years.py --check     # the parser against known phrases
    python3 scripts/narrators/arabic_years.py --sweep     # every stored year against its entry
"""
import argparse
import json
import os
import re
import sys

W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
sys.path.insert(0, f'{W}/scripts/narrators')

DIACRITIC = re.compile(r'[ؗ-ًؚ-ْـٰ]')
UNITS = {
    "واحد": 1, "واحدة": 1, "إحدى": 1, "احدى": 1, "احد": 1, "أحد": 1,
    "اثنتين": 2, "اثنين": 2, "اثنان": 2, "ثنتين": 2, "اثنتان": 2,
    "ثلاث": 3, "ثلاثة": 3, "أربع": 4, "اربع": 4, "أربعة": 4, "اربعة": 4,
    "خمس": 5, "خمسة": 5, "ست": 6, "ستة": 6, "سبع": 7, "سبعة": 7,
    "ثمان": 8, "ثماني": 8, "ثمانية": 8, "تسع": 9, "تسعة": 9,
}
TENS = {
    "عشر": 10, "عشرة": 10, "عشرين": 20, "ثلاثين": 30, "اربعين": 40, "أربعين": 40,
    "خمسين": 50, "ستين": 60, "سبعين": 70, "ثمانين": 80, "تسعين": 90,
}
HUNDREDS = {
    "مائة": 100, "مئة": 100, "مايه": 100, "مأة": 100,
    "مائتين": 200, "مئتين": 200, "مأتين": 200, "مائتان": 200, "مئتان": 200,
    "ثلاثمائة": 300, "ثلثمائة": 300, "ثلاثمئة": 300, "ثلاثمأة": 300,
    "أربعمائة": 400, "اربعمائة": 400, "اربعمئة": 400,
    "خمسمائة": 500, "خمسمئة": 500, "ستمائة": 600, "ستمئة": 600,
    "سبعمائة": 700, "سبعمئة": 700, "ثمانمائة": 800, "ثمانمئة": 800,
    "تسعمائة": 900, "تسعمئة": 900,
}
THOUSAND = {"الف": 1000, "ألف": 1000, "آلاف": 1000}
# «سنة» and «عام» open a year; «توفي», «مات» and «قتل» say whose it is.
FOLDED_UNITS, FOLDED_TENS = None, None       # filled below, once fold() exists
FOLDED_HUNDREDS, FOLDED_THOUSAND = None, None
OPENER = re.compile(r'(?:سنة|عام)\s+')
DIED = re.compile(r'(?:توف[يى]|مات|قتل|استشهد|وفاته|مضى)')
STOP = {"و", "في", "من", "بعد", "قبل", "نحو", "رحمه", "الله"}


def _folded(table):
    """The table keyed the way fold() leaves a word, or «مأتين» never matches «ماتين»."""
    return {fold(key): value for key, value in table.items()}


def fold(text):
    return DIACRITIC.sub('', text or '').replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')


FOLDED_UNITS = _folded(UNITS)
FOLDED_TENS = _folded(TENS)
FOLDED_HUNDREDS = _folded(HUNDREDS)
FOLDED_THOUSAND = _folded(THOUSAND)


def value(word):
    bare = fold(word).strip('()،,.؛:«»').lstrip('و')
    for table in (FOLDED_HUNDREDS, FOLDED_TENS, FOLDED_UNITS, FOLDED_THOUSAND):
        if bare in table:
            return table[bare], table
    return None, None


def year_after(words):
    """Add up the number words that follow, stopping at the first word that is not one."""
    total, seen, parts = 0, False, 0
    for word in words:
        bare = word.strip('()،,.؛:«»')
        if bare in STOP and not bare.lstrip('و'):
            continue
        amount, table = value(word)
        if amount is None:
            break
        # Every part adds: «تسع وثلاثين وثلاثمائة» is 9 + 30 + 300. «ألف» adds too, since
        # a hijri year never needs more than one thousand.
        total += amount
        seen = True
        parts += 1
        if parts > 4:
            break
    return total if seen and 1 <= total <= 1500 else None


def years_in(text):
    """Every year the passage states, in order, with whether a death word stands near it."""
    folded = fold(text)
    found = []
    for match in OPENER.finditer(folded):
        after = folded[match.end():].split()
        year = year_after(after)
        if year is None:
            continue
        before = folded[max(0, match.start() - 60):match.start()]
        found.append({"year": year, "about_death": bool(DIED.search(before))})
    return found


def stated_deaths(page):
    """Every year this man's own entries give as the year he died."""
    said = set()
    for entry in page.get("entries") or []:
        said |= {y["year"] for y in years_in(entry.get("text_ar") or "") if y["about_death"]}
    return said


def pages(host, index):
    import urllib.request
    after = None
    while True:
        body = {"size": 1000, "sort": [{"person_id": "asc"}],
                "query": {"exists": {"field": "life.died"}},
                "_source": ["person_id", "display_ar", "life", "entries"]}
        if after:
            body["search_after"] = after
        request = urllib.request.Request(f'{host}/{index}/_search',
                                         data=json.dumps(body).encode(),
                                         headers={"Content-Type": "application/json"})
        hits = json.load(urllib.request.urlopen(request))["hits"]["hits"]
        if not hits:
            return
        for hit in hits:
            yield hit["_source"]
        after = hits[-1]["sort"]


def a_misreading(printed, said):
    """Whether the difference between the two looks like a misread year and not another one.

    The first cut of this corrected «1094» to «94», because an eleventh-century entry writes
    «سنة أربع وتسعين» with the thousand understood and the parser takes it literally. It also
    corrected 463 to 167 and 325 to 190, where the parser had simply found a different
    sentence. Twelve of its sixteen corrections were wrong, and applying them would have done
    more harm than the fault they were mending.

    A misreading of a year written in words leaves a near miss or a transposition — «إحدى
    وثلاثمائة» read as 311 instead of 301, «الف واحد عشر» as 1101 instead of 1011. A year with
    a different number of digits, or a different set of them and far away, is the parser
    reading the wrong sentence, and the page is left alone.
    """
    if len(str(printed)) != len(str(said)):
        return False
    if sorted(str(printed)) == sorted(str(said)):
        return True
    return abs(printed - said) <= 10


def write_fixes(args):
    """Only where the man's own entries state exactly one year, and it is not the one printed.

    A passage often carries several years — a birth, a teacher's death, the year a book was
    finished — so a disagreement is only safe to act on when the entries name one death year
    and one alone. Everything else is left for a reader to settle, because a confident wrong
    correction is worse than the misreading it replaces.
    """
    from collections import Counter
    counts, fixes = Counter(), {}
    for page in pages(args.es_host, args.index):
        printed = (page.get("life") or {}).get("died")
        if not printed:
            continue
        counts["death years printed"] += 1
        said = stated_deaths(page)
        if not said:
            counts["no death year in any passage we hold"] += 1
        elif printed in said:
            counts["the passage states the year we print"] += 1
        elif len(said) == 1 and a_misreading(printed, sorted(said)[0]):
            counts["one year stated, and the difference looks like a misreading"] += 1
            fixes[page["person_id"]] = {"was": printed, "year": sorted(said)[0],
                                        "name_ar": page.get("display_ar")}
        elif len(said) == 1:
            counts["one year stated, but too far off to be a misreading — left alone"] += 1
        else:
            counts["several years stated — left for a reader"] += 1
    for label, count in counts.most_common():
        print(f"  {count:6,}  {label}")
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out + ".part", "w") as handle:
        json.dump({"note": "Death years their own passage contradicts, read by rule from the "
                           "entry. Only where the entries state exactly one year.",
                   "years": fixes}, handle, ensure_ascii=False, indent=1, sort_keys=True)
    os.replace(args.out + ".part", args.out)
    print(f"\nwrote {len(fixes)} corrections -> {args.out}")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="the parser on known phrases")
    parser.add_argument("--sweep", action="store_true",
                        help="every stored death year against the passage it came from")
    parser.add_argument("--fix", action="store_true",
                        help="write the corrections that are safe to apply")
    parser.add_argument("--out", default=f'{W}/scripts/data/narrators/death_years.json')
    parser.add_argument("--es-host", default="http://localhost:9200")
    parser.add_argument("--index", default="rewayaat_narrators")
    args = parser.parse_args()

    if args.check:
        known = [("توفي سنة إحدى وثلاثمائة", 301), ("مات سنة ثمان وثلاثمائة", 308),
                 ("سنة تسع وثلاثين وثلاثمائة", 339), ("توفي سنة ثمان ومائتين", 208),
                 ("سنة أربع وتسعين ومائة", 194), ("سنة الف واحد عشر", 1011),
                 ("سنة ثمان وتسعين ومأتين", 298), ("مات سنة خمس وعشرين وثلاثمائة", 325),
                 ("سنة مائة", 100), ("توفي سنة سبعين", 70)]
        wrong = 0
        for phrase, want in known:
            found = years_in(phrase)
            got = found[0]["year"] if found else None
            wrong += got != want
            print(f"  {'ok ' if got == want else 'BAD'}  {phrase:38s} -> {got} (want {want})")
        print(f"\n  {len(known) - wrong}/{len(known)} parsed correctly")
        return

    if args.fix:
        write_fixes(args)
        return
    if not args.sweep:
        return
    import urllib.request
    from collections import Counter
    after, counts, wrong = None, Counter(), []
    while True:
        body = {"size": 1000, "sort": [{"person_id": "asc"}],
                "query": {"exists": {"field": "life.died"}},
                "_source": ["person_id", "display_ar", "life", "entries"]}
        if after:
            body["search_after"] = after
        request = urllib.request.Request(f'{args.es_host}/{args.index}/_search',
                                         data=json.dumps(body).encode(),
                                         headers={"Content-Type": "application/json"})
        hits = json.load(urllib.request.urlopen(request))["hits"]["hits"]
        if not hits:
            break
        for hit in hits:
            page = hit["_source"]
            printed = (page.get("life") or {}).get("died")
            if not printed:
                continue
            counts["death years printed"] += 1
            said = []
            for entry in page.get("entries") or []:
                said += years_in(entry.get("text_ar") or "")
            deaths = [y["year"] for y in said if y["about_death"]] or [y["year"] for y in said]
            if not deaths:
                counts["no year in any passage we hold"] += 1
                continue
            if printed in deaths:
                counts["the passage states the year we print"] += 1
            else:
                counts["the passage states a different year"] += 1
                wrong.append((page["person_id"], page.get("display_ar"), printed, deaths[:3]))
        after = hits[-1]["sort"]
    for label, count in counts.most_common():
        print(f"  {count:6,}  {label}")
    print()
    for pid, name, printed, said in wrong[:14]:
        print(f"  {pid}  «{(name or '')[:30]:30s}» page says {printed}, passage says {said}")


if __name__ == "__main__":
    main()
