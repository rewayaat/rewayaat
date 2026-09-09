#!/usr/bin/env python3
"""Exercises the search the way readers use it, and reports where it misbehaves.

Not a unit test: it asks the running application real questions and checks the
answers against properties that have to hold however relevance is tuned - a
word spelled two ways finds the same narrations, a book's name finds the book,
a rare term is not buried under near-misses. Run it against a candidate index
before promoting one.

    python3 scripts/search/evaluate_search.py [--base http://localhost:8002]
"""
import argparse, json, sys, urllib.parse, urllib.request

def search(base, q, mode=None, page=1):
    url = f"{base}/v1/narrations?page={page}&q=" + urllib.parse.quote(q)
    if mode:
        url += "&match_mode=" + mode
    with urllib.request.urlopen(url, timeout=120) as r:
        return json.load(r)

def total(base, q, mode=None):
    return search(base, q, mode)["totalResultSetSize"]

def top_field(hit):
    """Which field the first result actually matched on, by its highlight."""
    for k in ("part", "book", "section", "chapter", "english", "arabic", "notes"):
        v = hit.get(k)
        if v and "highlight" in str(v):
            return k
    return None

# Analysis-level equivalence: the two forms reduce to the same token, so they must
# return exactly the same narrations. A difference here is a fault in the analyzer.
IDENTICAL = [
    ("Arabic vowelling",   "مسلم", "مُسلِم"),
    ("Arabic vowelling",   "صلاة", "صَلاة"),
    ("Arabic vowelling",   "الصوم جنة", "الصَّوْمُ جُنَّةٌ"),
    ("Arabic alef",        "امام", "إمام"),
    ("transliteration",    "Muhammad", "Muḥammad"),
    ("transliteration",    "Ali", "ʿAlī"),
    ("transliteration",    "hadith", "ḥadīth"),
    ("transliteration",    "Sadiq", "Ṣādiq"),
    ("transliteration",    "Husayn", "Ḥusayn"),
    ("english plural",     "narration", "narrations"),
]

# Synonym-level equivalence: two accepted spellings of a name, joined by the
# translit_names list rather than by the analyzer. Both search the same narrations,
# so the totals agree closely, but they need not be identical - each fuzzies from
# the form typed and so picks up a few near-misses the other does not.
VARIANTS = [
    ("husayn", "hussain"), ("ghadir", "ghadeer"), ("abdullah", "abdallah"),
    ("muhammad", "muhammed"), ("hasan", "hassan"), ("jafar", "jaffar"),
]

# A search for a book, part or section name must surface that division first.
METADATA_FIRST = [
    ("commerce", "part"), ("zakat", "part"), ("prayer", "part"),
    ("fasting", "part"), ("pilgrimage", "part"), ("marriage", "part"),
    ("knowledge", "part"), ("inheritance", "part"),
]

# Ordinary topical searches: must return something, and not everything.
TOPICAL = ["intention", "patience", "repentance", "charity", "orphan",
           "neighbour", "backbiting", "forgiveness", "justice", "intercession",
           "الشفاعة", "التوبة", "الصبر", "اليتيم", "العدل"]

# Rare terms: flexible mode must not bury the real hits under near-misses.
RARE = ["ghadir", "mubahala", "kisa", "غدير", "المباهلة"]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8002")
    args = ap.parse_args()
    b = args.base
    failures = []

    print("=" * 74)
    print("1. THE SAME WORD, TYPED TWO WAYS, MUST FIND THE SAME NARRATIONS")
    print("=" * 74)
    print("   %-18s %-14s %-14s %8s %8s" % ("class", "a", "b", "a hits", "b hits"))
    for label, a, bb in IDENTICAL:
        x, y = total(b, a), total(b, bb)
        ok = x == y
        if not ok:
            failures.append(f"{label}: {a}={x} but {bb}={y}")
        print("   %-18s %-14s %-14s %8d %8d  %s" % (label, a, bb, x, y, "" if ok else "<-- DIFFER"))

    print()
    print("=" * 74)
    print("1b. TWO SPELLINGS OF A NAME MUST SEARCH THE SAME NARRATIONS")
    print("=" * 74)
    print("   %-12s %-12s %8s %8s %10s" % ("a", "b", "a hits", "b hits", "agreement"))
    for a, bb in VARIANTS:
        # Both spellings resolve to the same synonym set, so they search the same
        # narrations and their totals should be close. They are not identical: the
        # spelling actually typed keeps its exact-match boost, and fuzziness expands
        # from the typed form, so each adds a few near-misses the other does not.
        # Ordering is deliberately not checked - typing the rarer spelling should put
        # that spelling first, and muhammad outnumbers muhammed 22,901 to 807.
        x, y = total(b, a), total(b, bb)
        agreement = min(x, y) / max(x, y) if max(x, y) else 0
        ok = agreement >= 0.85
        if not ok:
            failures.append(f"{a}={x} but {bb}={y} - only {agreement:.0%} agreement")
        print("   %-12s %-12s %8d %8d %9.0f%%  %s" % (a, bb, x, y, agreement * 100,
                                                      "" if ok else "<-- diverged"))

    print()
    print("=" * 74)
    print("2. A DIVISION'S NAME MUST SURFACE THAT DIVISION")
    print("=" * 74)
    for term, expected in METADATA_FIRST:
        d = search(b, term)
        c = d["collection"]
        if not c:
            failures.append(f"{term}: no results"); print("   %-14s NO RESULTS" % term); continue
        f = top_field(c[0])
        ok = f == expected
        if not ok:
            failures.append(f"{term}: top result matched on {f}, expected {expected}")
        print("   %-14s total=%-7d top matched on %-9s %s" % (term, d["totalResultSetSize"], f, "" if ok else "<-- expected " + expected))

    print()
    print("=" * 74)
    print("3. ORDINARY TOPICS RETURN A USABLE NUMBER OF RESULTS")
    print("=" * 74)
    for t in TOPICAL:
        n = total(b, t)
        ok = 0 < n < 12000
        if not ok:
            failures.append(f"{t}: {n} results")
        print("   %-16s %8d  %s" % (t, n, "" if ok else "<-- empty or unbounded"))

    print()
    print("=" * 74)
    print("4. FLEXIBLE MODE MUST NOT BURY A RARE TERM")
    print("=" * 74)
    # Judged by rank, not by count. Fuzziness legitimately widens a search - ziyara
    # should reach Ziyarat - so a large total is not itself a fault. What would be a
    # fault is the narration that actually contains the word falling down the page.
    print("   %-14s %8s %10s %6s" % ("term", "precise", "flexible", "rank"))
    for t in RARE:
        p, f = total(b, t, "precise"), total(b, t)
        hits = search(b, t)["collection"][:10]
        rank = None
        for i, h in enumerate(hits, 1):
            if t.lower() in json.dumps(h, ensure_ascii=False).lower():
                rank = i
                break
        ok = rank == 1
        if not ok:
            failures.append(f"{t}: exact match ranks {rank or 'outside top 10'}, not first")
        print("   %-14s %8d %10d %6s  %s" % (t, p, f, rank or "-", "" if ok else "<-- exact match not first"))

    print()
    if failures:
        print("FAILURES (%d):" % len(failures))
        for f in failures:
            print("   -", f)
        return 1
    print("All checks passed.")
    return 0

if __name__ == "__main__":
    sys.exit(main())
