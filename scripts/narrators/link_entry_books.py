#!/usr/bin/env python3
"""Join Khoei's entries to the al-Tusi and Jami' al-Ruwat entries of the same man.

Khoei's Mu'jam, Rijal al-Tusi and Jami' al-Ruwat are three indexes of largely the same men, each
giving a man an entry of his own. Joined by name across the whole corpus they meet badly: the
corpus holds thousands of phase 1 mentions sharing those names, so a bare two-token name is
rightly not enough to join on (merge_narrator_profiles.py), and one man stood as two or three
people. Between the entry books alone the question is sharper. When Khoei gives a name exactly
one entry, that entry is the man of that name, and the other books' entries of that name are his
unless they place him under a different Imam.

Each book says which Imams a man kept company with. Khoei says it in words, «من أصحاب الصادق عليه
السلام»; al-Tusi by the chapter the entry stands in, and he lists one man once under each Imam
he served; Jami' al-Ruwat by the same chapters' sigla, [ق] al-Sadiq, [قر] al-Baqir, [ظم]
al-Kazim, [ضا] al-Rida and so on. So:

  al-Tusi         every entry of the name is joined whose chapter is among Khoei's Imams; where
                  Khoei names no Imam, only a name al-Tusi gives a single entry
  Jami' al-Ruwat  the name's single entry is joined, unless its sigla and Khoei's Imams are two
                  generations apart or more (Jami' gives each man one entry). Where Khoei heads
                  the name more than once, the Jami' entry joins the one Khoei entry that shares
                  its words in order — the two quote the same older book, «فقيه دين» from
                  Muntajab al-Din for the Sayyid Abu Talib — at SHARED_SHARE of its opening word
                  triples with a SHARED_MARGIN over the next; a Khoei entry claimed by two Jami'
                  entries joins neither (`entry_books_jami_text`)

Jami' al-Ruwat also copies al-Tusi's listings word for word and marks them with his chapter's
siglum: «عمر بن مسلم الهراء كوفي (ق)» is al-Tusi's «عمر بن مسلم الهراء، كوفي.» under al-Sadiq. A
Jami' entry that opens with the whole text of an al-Tusi entry — at least as far as its own
heading runs, so a bare «عيسى بن راشد» copies nothing — and whose sigla name that entry's Imam,
is joined to it where exactly one such entry exists (`entry_books_jami_tusi`).

Measured against link_khoei_citations.py, where Khoei's own citation says which al-Tusi entry he
means: of names both books give one entry, 690 of 706 join the cited entry, and the sixteen others
are, save three, the same man's listing under another Imam — which the Imam test now requires.

Each join is a reviewer `same`, not taken across three generations or where it already holds.

Usage:
    python3 scripts/narrators/link_entry_books.py            # count, write nothing
    python3 scripts/narrators/link_entry_books.py --write
"""

import argparse
from collections import Counter, defaultdict
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from identity import append_record, load_record, make_decision  # noqa: E402
from narrator_schema import (  # noqa: E402
    IMAM_ORDINALS, generation_distance, imam_generations, name_tokens, normalize_arabic,
)

TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "tmp")
RECORD = os.path.join(TMP, "narrators_identity", "decisions.jsonl")
# the sigla of al-Tusi's chapters, as Jami' al-Ruwat and the later Rijal works write them
SIGLA = {"ل": "prophet", "ى": "ali", "ي": "ali", "ن": "hasan", "سن": "hasan", "سين": "husayn",
         "ين": "sajjad", "قر": "baqir", "ق": "sadiq", "ظم": "kazim", "ضا": "rida", "ج": "jawad",
         "دي": "hadi", "دى": "hadi", "كر": "askari"}
SIGLUM = re.compile(r"[\[\(]([^\]\)]{1,20})[\]\)]")
KHOEI_COMPANION = re.compile(r"أصحاب\s+([^،.()]{2,40})")
SIGLA_NOISE = re.compile(r"[\[\(][^\]\)]{1,15}[\]\)]|ms\d+|PageV\d+P\d+")
SHARED_OPENING, SHARED_SHARE, SHARED_MARGIN = 25, 0.5, 0.3


def words(text):
    return re.sub(r"[^\w\s]|_", " ", normalize_arabic(text)).split()


def triples(tokens):
    return [tuple(tokens[i:i + 3]) for i in range(len(tokens) - 2)]


def jami_opening(profile):
    """Jami's own words after its heading, its sigla and page marks set aside."""
    text = words(SIGLA_NOISE.sub(" ", body(profile)))
    heading = words(profile["primary_arabic_name"])
    return (text[len(heading):] if text[:len(heading)] == heading else text)[:SHARED_OPENING]


def entries(book):
    with open(os.path.join(TMP, "narrators_normalized", f"{book}.json")) as handle:
        return [p for p in json.load(handle) if "printed_entry" in p.get("flags", ())]


def body(profile):
    return (profile.get("source_assessments") or [{}])[0].get("assessment_ar") or ""


def khoei_imams(profile):
    found = set()
    for cited in KHOEI_COMPANION.finditer(body(profile)):
        found |= imam_generations("أصحاب " + cited.group(1))
    return frozenset(found)


def jami_imams(profile):
    """Imams from the sigla after the heading, before the first chain citation begins."""
    head = re.split(r"\s(?:عنه|عن)\s", body(profile), maxsplit=1)[0][:160]
    found = set()
    for group in SIGLUM.finditer(head):
        for mark in re.split(r"[.،\s]+", group.group(1)):
            if mark in SIGLA:
                found.add(IMAM_ORDINALS[SIGLA[mark]])
    return frozenset(found)


def key(profile):
    """A man's name and his father's: al-Tusi adds a nisbah Khoei's heading leaves off."""
    return tuple(name_tokens(normalize_arabic(profile["primary_arabic_name"]))[:2])


def grandfathers_differ(a, b):
    """A third name both give, and give differently, that is not a nisbah."""
    x = name_tokens(normalize_arabic(a["primary_arabic_name"]))
    y = name_tokens(normalize_arabic(b["primary_arabic_name"]))
    return (len(x) >= 3 and len(y) >= 3 and x[2] != y[2]
            and not x[2].startswith("ال") and not y[2].startswith("ال"))


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()

    khoei, tusi, jami = entries("khoei"), entries("tusi"), entries("ardabili")
    with open(os.path.join(TMP, "narrators_identity", "membership.json")) as handle:
        membership = json.load(handle)

    def by_name(profiles):
        index = defaultdict(list)
        for p in profiles:
            index[key(p)].append(p)
        return index

    k_names, t_names, j_names = by_name(khoei), by_name(tusi), by_name(jami)
    counts, links = Counter(), []
    for name, held in k_names.items():
        if len(name) < 2:
            counts["a name without a father's name: left"] += 1
            continue
        if len(held) != 1:
            counts["Khoei gives the name several entries: left"] += 1
            continue
        k = held[0]
        k_key, imams = f"khoei:{k['source_index']}", khoei_imams(k)

        listings = t_names.get(name, [])
        listings = [t for t in listings if not grandfathers_differ(k, t)]
        if imams:
            chosen = [t for t in listings if imam_generations(t.get("generation")) & imams]
        else:
            chosen = listings if len(listings) == 1 else []
        if listings and not chosen:
            counts["al-Tusi: under other Imams, or several with none named"] += 1
        for t in chosen:
            links.append((k_key, f"tusi:{t['source_index']}", "tusi"))

        same_name = [j for j in j_names.get(name, []) if not grandfathers_differ(k, j)]
        if len(same_name) == 1:
            apart = generation_distance(imams, jami_imams(same_name[0]))
            if apart is not None and apart >= 2:
                counts["Jami' al-Ruwat: sigla generations apart"] += 1
            else:
                links.append((k_key, f"ardabili:{same_name[0]['source_index']}", "jami"))
        elif len(same_name) > 1:
            counts["Jami' al-Ruwat gives the name several entries: left"] += 1

    # names Khoei heads more than once: each Jami' entry to the Khoei entry sharing its words
    claimed = defaultdict(list)
    for name, held in k_names.items():
        if len(name) < 2 or len(held) < 2:
            continue
        texts = [(k, set(triples(words(body(k))))) for k in held]
        for j in j_names.get(name, []):
            grams = triples(jami_opening(j))
            if len(grams) < 4:
                continue
            scored = sorted(((sum(g in t for g in grams) / len(grams), k) for k, t in texts),
                            key=lambda x: -x[0])
            best, runner = scored[0][0], scored[1][0]
            if best < SHARED_SHARE or best - runner < SHARED_MARGIN:
                counts["Jami' al-Ruwat: no single Khoei homonym shares its words"] += 1
                continue
            k = scored[0][1]
            apart = generation_distance(khoei_imams(k), jami_imams(j))
            if apart is not None and apart >= 2:
                counts["Jami' al-Ruwat: sigla generations apart"] += 1
                continue
            claimed[k["source_index"]].append(j)
    for k_index, js in claimed.items():
        if len(js) > 1:
            counts["a Khoei entry two Jami' entries share words with: left"] += 1
            continue
        links.append((f"khoei:{k_index}", f"ardabili:{js[0]['source_index']}", "jami_text"))

    # Jami' al-Ruwat's copies of al-Tusi's listings
    opens = defaultdict(list)
    for t in tusi:
        text = words(body(t))
        if len(text) >= 3:
            opens[tuple(text[:3])].append((t, text))
    for j in jami:
        text = words(SIGLA_NOISE.sub(" ", body(j)))
        heading = len(words(SIGLA_NOISE.sub(" ", j["primary_arabic_name"])))
        copies = [t for t, t_text in opens.get(tuple(text[:3]), [])
                  if text[:len(t_text)] == t_text and len(t_text) >= heading
                  and imam_generations(t.get("generation")) & jami_imams(j)]
        if len(copies) == 1:
            links.append((f"ardabili:{j['source_index']}", f"tusi:{copies[0]['source_index']}",
                          "jami_tusi"))
        elif copies:
            counts["Jami' al-Ruwat copies several al-Tusi entries: left"] += 1

    fresh = [(a, b, w) for a, b, w in links
             if not (membership.get(a) and membership.get(a) == membership.get(b))]
    for _, _, which in links:
        counts[f"link to {which}"] += 1
    for _, _, which in fresh:
        counts[f"link to {which} that joins two people"] += 1
    print(f"{len(khoei)} Khoei entries, {len(tusi)} al-Tusi, {len(jami)} Jami' al-Ruwat")
    for label, n in sorted(counts.items()):
        print(f"  {n:6d}  {label}")
    if not args.write:
        return
    on_file = {d["decision_id"] for d in load_record(RECORD)}
    decisions = [d for d in (
        make_decision("same", sources=[a, b], method=f"entry_books_{which}", actor="reviewer",
                      confidence="high",
                      evidence={"why": "the one Khoei entry of this name, and an entry of the same "
                                       "name under an Imam Khoei says he served"},
                      origin={"run": "stage6:link_entry_books"})
        for a, b, which in fresh) if d["decision_id"] not in on_file]
    append_record(RECORD, decisions)
    print(f"\nrecorded {len(decisions)} links")


if __name__ == "__main__":
    main()
