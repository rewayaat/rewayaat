#!/usr/bin/env python3
"""Join each Khoei entry to the al-Najashi, al-Fihrist and al-Kashshi entries he quotes.

Khoei's Mu'jam opens most entries by quoting what the older books say of the man, word for word:
«قال النجاشي: "سهل بن زياد أبو سعيد الآدمي الرازي، كان ضعيفا في الحديث…"». The books' own text is
now in the corpus, one profile per printed entry, so the quotation can be found. Among the entries
of a book whose name and father's name agree with Khoei's heading, the one whose opening words
stand in Khoei's entry is the one he quotes — which settles homonyms by what the entries say
rather than by what they are called.

An entry is joined when at least MIN_SHARE of its opening word triples stand in Khoei's entry and no
other candidate of that book comes within MARGIN of it; and a printed entry joins only the one
Khoei entry that quotes it most fully. Each join is a reviewer `same`
(`khoei_quotes_<book>`), not taken where the two people already stand three generations apart.

Jami' al-Ruwat quotes the same books the same way, abridged — «ضعيف في الحديث غير معتمد عليه
فيه… [جش]» — and gives each man one entry, so the same test joins its entries with --quoting
ardabili (`jami_quotes_<book>`). Its namesakes score as Khoei's do, under 0.3.

link_khoei_citations.py does the same for Rijal al-Tusi by the number Khoei cites, and
link_entry_books.py for Jami' al-Ruwat by name and Imam.

Usage:
    python3 scripts/narrators/link_khoei_quotations.py            # count, write nothing
    python3 scripts/narrators/link_khoei_quotations.py --write
    python3 scripts/narrators/link_khoei_quotations.py --quoting ardabili --write
"""

import argparse
from collections import Counter, defaultdict
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from identity import append_record, load_record, make_decision  # noqa: E402
from narrator_schema import name_tokens, normalize_arabic  # noqa: E402

TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "tmp")
RECORD = os.path.join(TMP, "narrators_identity", "decisions.jsonl")
BOOKS = ("najashi", "fihrist", "kashshi")
QUOTING = {"khoei": "khoei_quotes_", "ardabili": "jami_quotes_"}   # quoting book -> method prefix
# Jami' al-Ruwat quotes right after the heading and its sigla. Where its running text was cut
# badly one entry carries the next — al-Mahabadi's runs on into al-'Aqiqi's — and the next
# man's quotation then scores under the first man's heading. So in a book listed here a
# quotation counts only where it begins within its first words, and either stands where it
# stands in the quoted entry (give or take the sigla, ALIGN_SLACK words) or the quoting heading's
# own words are in the quoted entry's opening — «الحسيني» is nowhere in Ibn Tarkhan's. Its
# headings are not varied: its entry on Ibn 'Uyayna reproduces al-Kashshi's report on al-Thawri.
STARTS_WITHIN = {"ardabili": 30}
ALIGN_SLACK = 12
VARIANTS = {"khoei"}
OPENING = 25            # words of a quoted entry to look for
MIN_SHARE = 0.4           # of the opening's word triples; namesakes score under 0.3
MARGIN = 0.25
# Khoei heads a man more fully than al-Najashi at times — «أحمد بن علي بن أحمد بن العباس» for
# al-Najashi's own «أحمد بن العباس النجاشي» — so where no entry of the name and father's name
# is quoted, the entries of the same first name are tried, on longer openings and a stricter bar
VARIANT_MIN_WORDS = 10
VARIANT_SHARE = 0.6
VARIANT_MARGIN = 0.35


def entries(book):
    with open(os.path.join(TMP, "narrators_normalized", f"{book}.json")) as handle:
        return [p for p in json.load(handle) if "printed_entry" in p.get("flags", ())]


def body(profile):
    return (profile.get("source_assessments") or [{}])[-1].get("assessment_ar") or ""


def key(profile):
    return tuple(name_tokens(normalize_arabic(profile["primary_arabic_name"]))[:2])


def words(text):
    """The words alone: «عروة،» in one edition is «عروة» in the other."""
    return re.sub(r"[^\w\s]|_", " ", normalize_arabic(text)).split()


def shingles(tokens, n=3):
    return [tuple(tokens[i:i + n]) for i in range(len(tokens) - n + 1)]


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--quoting", choices=sorted(QUOTING), default="khoei")
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    quoting, prefix = args.quoting, QUOTING[args.quoting]

    with open(os.path.join(TMP, "narrators_identity", "membership.json")) as handle:
        membership = json.load(handle)
    khoei = entries(quoting)
    targets = {book: defaultdict(list) for book in BOOKS}
    by_first = {book: defaultdict(list) for book in BOOKS}
    for book in BOOKS:
        for p in entries(book):
            opening = words(body(p))[:OPENING]
            if len(opening) >= 5 and len(key(p)) == 2:
                targets[book][key(p)].append((p, opening))
            if len(opening) >= VARIANT_MIN_WORDS and key(p):
                by_first[book][key(p)[0]].append((p, opening))

    # a quotation is words in order: the share of the opening's word triples found in Khoei's
    # entry. Counting single words instead let a long entry, which holds nearly every common word,
    # "quote" every namesake, so the margin failed exactly on the best-documented men.
    within = STARTS_WITHIN.get(quoting)

    def share(opening, text):
        grams = shingles(opening)
        if within is not None and not any(text.get(g, within) < within for g in grams):
            return 0.0
        return sum(1 for g in grams if g in text) / len(grams)

    def run_on(opening, text, heading):
        """A quoting entry that carries another man's entry after its own."""
        if within is None:
            return False
        grams = shingles(opening)
        aligned = sum(1 for i, g in enumerate(grams) if text.get(g, i + ALIGN_SLACK + 1)
                      <= i + ALIGN_SLACK) / len(grams)
        return aligned < MIN_SHARE and not all(t in opening for t in heading)

    def rank(candidates, text, heading=()):
        scored = sorted(((0.0 if run_on(opening, text, heading) else share(opening, text), p)
                         for p, opening in candidates), key=lambda x: -x[0])
        return scored[0][0], (scored[1][0] if len(scored) > 1 else 0.0), scored[0][1]

    counts, links = Counter(), []
    for k in khoei:
        heading = key(k)
        if len(heading) < 2:
            continue
        own = name_tokens(normalize_arabic(k["primary_arabic_name"]))[:4]
        text = {}                      # word triple -> where it first stands
        for at, gram in enumerate(shingles(words(body(k)))):
            text.setdefault(gram, at)
        for book in BOOKS:
            candidates = targets[book].get(heading, [])
            if candidates:
                best, runner, chosen = rank(candidates, text, own)
                if best >= MIN_SHARE and best - runner >= MARGIN:
                    links.append((f"{quoting}:{k['source_index']}", f"{book}:{chosen['source_index']}",
                                  book, round(best, 2)))
                    counts[f"{book}: linked"] += 1
                    continue
                counts[f"{book}: " + ("Khoei does not quote it" if best < MIN_SHARE
                                      else "two entries he may be quoting")] += 1
            variants = by_first[book].get(heading[0], []) if quoting in VARIANTS else []
            if not variants:
                continue
            best, runner, chosen = rank(variants, text, own)
            if best >= VARIANT_SHARE and best - runner >= VARIANT_MARGIN:
                links.append((f"{quoting}:{k['source_index']}", f"{book}:{chosen['source_index']}",
                              book, round(best, 2)))
                counts[f"{book}: linked under a variant heading"] += 1

    # One printed entry is one man: where two Khoei entries both quote it, only the one that
    # quotes it most fully is joined. Khoei heads one man under two forms at times, and his own
    # cross-references join those; he also quotes al-Najashi to say a namesake is someone else,
    # and a second link would bridge the two.
    best_for = {}
    for l in links:
        prev = best_for.get(l[1])
        if prev is None or l[3] > prev[3]:
            best_for[l[1]] = l
        elif l[3] == prev[3]:
            best_for[l[1]] = (None,) + l[1:]
    kept = {id(l) for l in best_for.values() if l[0] is not None}
    dropped = [l for l in links if id(l) not in kept]
    counts["links dropped: a printed entry another Khoei entry quotes as fully or more"] += len(dropped)
    links = [l for l in links if id(l) in kept]
    fresh = [l for l in links if not (membership.get(l[0]) and membership.get(l[0]) == membership.get(l[1]))]
    for l in fresh:
        counts[f"{l[2]}: linked, joining two people"] += 1
    for label, n in sorted(counts.items()):
        print(f"  {n:6d}  {label}")
    if not args.write:
        return
    record = load_record(RECORD)
    on_file = {d["decision_id"] for d in record}
    decisions = [d for d in (
        make_decision("same", sources=[a, b], method=f"{prefix}{book}", actor="reviewer",
                      confidence="high",
                      evidence={"why": f"the {quoting} entry reproduces this {book} entry's opening words",
                                "share": share},
                      origin={"run": "stage6:link_khoei_quotations"})
        for a, b, book, share in links) if d["decision_id"] not in on_file]
    # links an earlier scoring made and this one does not are withdrawn
    wanted = {frozenset((a, b)) for a, b, _, _ in links}
    withdrawn = {t for d in record if d["kind"] == "retract" for t in d.get("targets", [])}
    stale = [d["decision_id"] for d in record if d["method"].startswith(prefix)
             and d["kind"] == "same" and d["decision_id"] not in withdrawn
             and frozenset(d["sources"]) not in wanted]
    if stale:
        decisions.append(make_decision("retract", targets=stale, method=f"{prefix}rescored",
                                       actor="reviewer", confidence="high",
                                       evidence={"why": "not a quotation by the word-triple score"},
                                       origin={"run": "stage6:link_khoei_quotations"}))
    append_record(RECORD, decisions)
    print(f"\nrecorded {len(decisions)} decisions ({len(stale)} earlier links withdrawn)")


if __name__ == "__main__":
    main()
