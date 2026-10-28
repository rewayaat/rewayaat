#!/usr/bin/env python3
"""Gives a man an English name when no book printed one for him.

4,110 of the 15,526 narrator pages carried no English name at all, and so no slug either:
their addresses were bare identifiers and an English-reading visitor met a heading he could
not pronounce. No English name exists anywhere in the sources for those men — this is checked,
not assumed — so one has to be made.

It is not made letter by letter if it can be helped. The corpus already holds 8,065 names
printed in both scripts with the same number of words, and aligning those word for word
teaches what the books themselves write: «محمد» is Muhammad 1,915 times against Muḥammad 9,
«بن» is ibn 7,108 times against bin 887. A name built out of those words is spelled the way
this corpus spells, which matters more than being right by some standard nobody here follows.
Only a word the corpus has never printed in English falls back to the letters.

A made name is marked as made (`display_en_made`), because a transliteration is our word and
a heading is a book's, and the page should not pass one off as the other.

Usage:
    python3 scripts/narrators/transliterate.py --learn      # build and save the word map
    python3 scripts/narrators/transliterate.py --check      # score it against known names
"""
import argparse
from collections import Counter, defaultdict
import json
import os
import re

W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
MAP = f'{W}/scripts/data/narrators/transliteration.json'
DIACRITIC = re.compile(r'[ؗ-ًؚ-ْـٰ]')

# What a letter becomes when the corpus has never printed the word. Deliberately plain: no
# macrons and no dots, because the names the corpus does print are plain too and a page should
# not mix two spelling systems.
LETTER = {
    'ا': 'a', 'أ': 'a', 'إ': 'i', 'آ': 'a', 'ب': 'b', 'ت': 't', 'ث': 'th', 'ج': 'j',
    'ح': 'h', 'خ': 'kh', 'د': 'd', 'ذ': 'dh', 'ر': 'r', 'ز': 'z', 'س': 's', 'ش': 'sh',
    'ص': 's', 'ض': 'd', 'ط': 't', 'ظ': 'z', 'ع': "'", 'غ': 'gh', 'ف': 'f', 'ق': 'q',
    'ك': 'k', 'ل': 'l', 'م': 'm', 'ن': 'n', 'ه': 'h', 'و': 'w', 'ي': 'y', 'ى': 'a',
    'ة': 'a', 'ء': "'", 'ؤ': 'u', 'ئ': 'i', 'ک': 'k', 'ی': 'y',
}
CONSONANT = "bcdfghjklmnpqrstvxz'"  # y and w stand for vowels here, not consonants
DIGRAPH = ("th", "kh", "dh", "sh", "gh")
# A yāʾ or wāw between two consonants is a long vowel far more often than a diphthong:
# «الطوسي» is al-Tusi and «إشكيب» Ishkib, against Zaydan and Nawbakht the other way. Measured
# both ways against the names the books do print, i and u win.
LONG = {'ي': 'i', 'و': 'u'}


def learn(pairs):
    """Arabic word → the English the books print for it, where they agree well enough."""
    seen = defaultdict(Counter)
    for arabic, english in pairs:
        arabic, english = arabic.split(), english.split()
        if len(arabic) != len(english):
            continue  # only aligned names teach anything; a mismatch would pair the wrong words
        for one, other in zip(arabic, english):
            seen[strip(one)][other.strip(" ,.;:()[]")] += 1
    learned = {}
    for word, forms in seen.items():
        best, support = forms.most_common(1)[0]
        total = sum(forms.values())
        # Two printings and a clear majority. One printing is as likely to be a typo as a
        # spelling, and «محمد» once came out as «(Uncle» from a misaligned pair.
        if support >= 2 and support >= total * 0.5 and best and not best[0].isdigit():
            learned[word] = best
    return learned


def strip(word):
    return DIACRITIC.sub('', word).strip(" ،,.;:()[]«»‏")


def by_letter(word):
    """A word the corpus has never printed, spelled out so it can be said aloud.

    Arabic writes the short vowels only as marks, and the marks are not in these headings, so
    a letter-for-letter rendering gives «مهزم» as Mhzm and «زيدان» as Zydan. Two rules put the
    sounds back. A yāʾ or wāw standing between two consonants is the long vowel it almost
    always is there — «زيدان» becomes Zaydan, «نوبخت» Nawbakht, which is how the books spell
    them. And any consonants still left adjacent take an a between them, which is the
    commonest short vowel in these names: «مهزم» becomes Mahzam.
    """
    bare = strip(word)
    article = bare.startswith('ال') and len(bare) > 3
    if article:
        bare = bare[2:]
    letters = []
    for position, letter in enumerate(bare):
        before = bare[position - 1] if position else ''
        after = bare[position + 1] if position + 1 < len(bare) else ''
        if letter == 'ي' and not after and before:
            letters.append('i')          # a name ending in yāʾ ends in i: al-Tusi, not al-Tusy
            continue
        if letter in 'وي' and before and after and before not in 'اوي' and after not in 'اوي':
            letters.append(LONG[letter])
            continue
        letters.append(LETTER.get(letter, ''))
    out = vowelled(''.join(letters))
    if not out:
        return ''
    for position, letter in enumerate(out):
        if letter.isalpha():
            out = out[:position] + letter.upper() + out[position + 1:]
            break
    return ('al-' + out) if article else out


def vowelled(word):
    """Put back the short vowels Arabic script does not write, where a name needs them.

    Not between every pair of consonants: «أرقم» is Arqam, not Araqam, and «أحمر» is Ahmar.
    Arabic names carry their short vowel before the last consonant, so that is where one goes
    when the word would otherwise end in a cluster, and any run of three consonants still
    standing is broken after the first. Digraphs count as one consonant, or «نوبخت» would be
    split through the middle of its kh.
    """
    units, position = [], 0
    while position < len(word):
        pair = word[position:position + 2]
        if pair in DIGRAPH:
            units.append(pair)
            position += 2
        else:
            units.append(word[position])
            position += 1
    hard = [u for u in units if u[0] in CONSONANT or u in DIGRAPH]
    if len(hard) < 2:
        return word
    out = []
    for index, unit in enumerate(units):
        solid = unit[0] in CONSONANT or unit in DIGRAPH
        if solid and out:
            last = out[-1]
            before = out[-2] if len(out) > 1 else ''
            last_solid = last[0] in CONSONANT or last in DIGRAPH
            before_solid = bool(before) and (before[0] in CONSONANT or before in DIGRAPH)
            ending = index == len(units) - 1
            # No Arabic word opens on two consonants, so a cluster at the front always
            # takes a vowel: «بشار» is Bashar, never Bshar.
            opening = len(out) == 1
            if last_solid and (ending or before_solid or opening):
                out.append('a')
        out.append(unit)
    return ''.join(out)


def english_for(arabic, learned):
    """The whole name in English, word by word, or nothing if it comes out empty."""
    words = []
    for word in (arabic or '').split():
        bare = strip(word)
        if not bare:
            continue
        words.append(learned.get(bare) or by_letter(word))
    words = [w for w in words if w]
    return ' '.join(words) if words else ''


# Words the books' English gets wrong, set by hand. The phase 1 readers vowelled a name they did
# not know by guesswork, and the guess can be printed as a book's own English: «عمرو بن شمر», the
# Juʿfī every chain of Jābir runs through, came out Shumar on one page and Shamar on his namesake's,
# and a reader who knows him as Shimr took the two for different men. Short and kept that way: a
# word belongs here only when the right vowelling is not in doubt.
RESPELLED = {
    'شمر': 'Shimr',
}


def respell(arabic, english):
    """«english» with each word «RESPELLED» corrects put right, where the two names align.

    Only a name with as many words in each script can be corrected word for word; any other is
    returned as it is, since there is no telling which English word renders which Arabic one.
    """
    if not arabic or not english:
        return english
    ours, theirs = arabic.split(), english.split()
    if len(ours) != len(theirs):
        return english
    return ' '.join(RESPELLED.get(strip(one), other) for one, other in zip(ours, theirs))


def load(path=MAP):
    try:
        with open(path) as handle:
            return {**json.load(handle)["words"], **RESPELLED}
    except (FileNotFoundError, KeyError):
        return dict(RESPELLED)


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--es-host", default="http://localhost:9200")
    parser.add_argument("--index", default="rewayaat_narrators")
    parser.add_argument("--out", default=MAP)
    parser.add_argument("--learn", action="store_true", help="build and save the word map")
    parser.add_argument("--check", action="store_true", help="score it against known names")
    args = parser.parse_args()

    import urllib.request
    rows, after = [], None
    while True:
        body = {"size": 2000, "sort": [{"person_id": "asc"}], "query": {"match_all": {}},
                "_source": ["display_ar", "display_en"]}
        if after:
            body["search_after"] = after
        request = urllib.request.Request(f'{args.es_host}/{args.index}/_search',
                                         data=json.dumps(body).encode(),
                                         headers={"Content-Type": "application/json"})
        hits = json.load(urllib.request.urlopen(request))["hits"]["hits"]
        if not hits:
            break
        rows += [h["_source"] for h in hits]
        after = hits[-1]["sort"]
    pairs = [(r["display_ar"], r["display_en"]) for r in rows
             if r.get("display_ar") and r.get("display_en")]
    print(f"  {len(pairs):,} names the books print in both scripts")

    if args.check:
        # Held out by name, so a name cannot teach the words it is then scored on.
        held = {i for i in range(len(pairs)) if i % 5 == 0}
        learned = learn([p for i, p in enumerate(pairs) if i not in held])
        counts = Counter()
        for i in sorted(held):
            arabic, english = pairs[i]
            made = english_for(arabic, learned)
            counts["held out"] += 1
            bare = lambda s: re.sub(r"[^a-z ]", "", s.lower())
            if bare(made) == bare(english):
                counts["word for word the same"] += 1
            elif bare(made).split()[:1] == bare(english).split()[:1]:
                counts["first word agrees"] += 1
            if not made:
                counts["came out empty"] += 1
        for label, count in counts.most_common():
            print(f"  {count:6,}  {100*count/counts['held out']:5.1f}%  {label}")
        return

    learned = learn(pairs)
    print(f"  {len(learned):,} Arabic words the corpus prints in English often enough to trust")
    if not args.learn:
        return
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out + ".part", "w") as handle:
        json.dump({"note": "Arabic word -> the English this corpus prints for it, learned "
                           "from names the books give in both scripts.",
                   "words": learned}, handle, ensure_ascii=False, indent=1, sort_keys=True)
    os.replace(args.out + ".part", args.out)
    print(f"\nwrote {args.out}")


if __name__ == "__main__":
    main()
