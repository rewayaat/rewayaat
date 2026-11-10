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
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lineage  # noqa: E402

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
# More of the same, checked by hand on 2026-11-09: the learned map had «بكير» as Bakir (Bukayr),
# «حريث» as Harith (Hurayth), «الحميري» as al-Hamiri (al-Himyari), «جامع» as Jamil, «السبيعي»
# as al-Sulaybi; the books' own English had «دراج» as Dirar. A scholar reads «Jamil ibn Dirar»
# as a different man.
CORRECTIONS = os.path.join(W, 'scripts', 'data', 'narrators', 'transliteration_corrections.json')
try:
    with open(CORRECTIONS) as _handle:
        RESPELLED.update(json.load(_handle)["words"])
except (FileNotFoundError, KeyError):
    pass


def respell(arabic, english):
    """«english» with each word «RESPELLED» corrects put right, where the two names align.

    Only a name with as many words in each script can be corrected word for word; any other is
    returned as it is, since there is no telling which English word renders which Arabic one.
    """
    if not arabic or not english:
        return english
    if not _LEARNED:
        _LEARNED.update(load())
    # A word whose consonants cannot be its Arabic (Ishaq for «إبراهيم») is put right from the
    # learned map too, as long as it is the only such word: two means the names do not align.
    corrected, _ = lineage.corrected_english(arabic, english, _LEARNED, RESPELLED)
    return corrected


_LEARNED = {}


# --- Does the English name the man the Arabic does? ---
#
# The page shows the two side by side, and a reader takes the English for the Arabic's
# rendering. 2,264 of them did not render it: «سعد بن عبد الله» shown as Sa'd ibn Allah (1,463
# chains), «معاذ بن مسلم الهراء» as Mi'adh ibn Kathir al-Kisa'i, «أحمد بن هارون» as Ahmad ibn
# Qarun, «ضريس» as Dris, «مصدق» as Madaq — another book's heading for the man, or a misread
# one, carried onto a page whose Arabic says something else. A name a word or two shorter than
# its Arabic is a translator's abridgement and is left alone; a name with a word the Arabic does
# not hold is not his rendering at all.

_AR_LATIN = {
    'ب': 'b', 'ت': 't', 'ث': 'th', 'ج': 'j', 'ح': 'h', 'خ': 'kh', 'د': 'd', 'ذ': 'dh', 'ر': 'r',
    'ز': 'z', 'س': 's', 'ش': 'sh', 'ص': 's', 'ض': 'd', 'ط': 't', 'ظ': 'z', 'غ': 'gh', 'ف': 'f',
    'ق': 'q', 'ك': 'k', 'ل': 'l', 'م': 'm', 'ن': 'n', 'ه': 'h',
}


def _latin_consonants(text):
    """Digraphs folded the same way on both sides, so «إسحاق» (s, h) and Ishaq agree."""
    text = text.replace('dh', 'z').replace('th', 's').replace('sh', 'x').replace('kh', 'k')
    return text.replace('gh', 'g').replace('q', 'k')


def _arabic_skeleton(word):
    """The consonants of an Arabic name word (or of «عبد X»), read the way _english_skeleton
    reads its English: no article, no long vowels, no ʿayn or hamza (the English marks them, if
    at all, with an apostrophe it drops), and a wāw or yāʾ only where the word opens on it —
    «وهب» Wahb, «يحيى» Yahya, but «أعين» A'yan and «عيسى» Isa open on a vowel."""
    parts = []
    for bare in (strip(w) for w in word.split()):
        if bare == 'الله':
            bare = 'له'
        elif bare.startswith('ال') and len(bare) > 3:
            bare = bare[2:]
        parts.append(bare)
    bare = ''.join(parts)
    head = ''
    if bare[:1] in 'وي':
        head, bare = ('w' if bare[0] == 'و' else 'y'), bare[1:]
    latin = head + ''.join(_AR_LATIN.get(letter, '') for letter in bare)
    # Two readings: letter by letter («سهل» s-h-l, as Sahl spells it), and with the digraphs
    # an English spelling cannot tell from two letters folded («إسحاق» s-h, which Ishaq writes
    # sh). Either agreeing is agreement.
    whole = ''.join(_latin_consonants(_AR_LATIN.get(letter, '')) for letter in bare)
    return {re.sub(r'(.)\1+', r'\1', _latin_consonants(head) + whole),
            re.sub(r'(.)\1+', r'\1', _latin_consonants(latin))}


def _english_skeleton(word):
    out = []
    for w in lineage._en_words(word):
        w = _latin_consonants(re.sub(r'(?<=[aeiou])h$', '', w))
        if w[:1] in ('y', 'w'):
            head, w = w[0], w[1:]
        else:
            head = ''
        out.append(head + re.sub(r'[aeiouyw]', '', w))
    return re.sub(r'(.)\1+', r'\1', ''.join(out))


def _skeletons_agree(a, b):
    if isinstance(a, set):
        return any(_skeletons_agree(one, b) for one in a)
    if a == b:
        return True
    if a.rstrip('h') == b.rstrip('h'):
        return True
    # One consonant either way only in a word long enough to survive it: «Kharrabudh» and
    # «Kharbud» are one name, but «Qarun» is not «هارون» and «Madaq» is not «مصدق».
    return min(len(a), len(b)) >= 4 and lineage._one_edit(a, b)


def _arabic_units(arabic):
    """Words, with «عبد» and «عبيد» kept to the name they open, as the English writes them."""
    words = [w for w in (strip(x) for x in DIACRITIC.sub('', arabic or '').split()) if w]
    units, i = [], 0
    while i < len(words):
        if words[i] in ('عبد', 'عبيد') and i + 1 < len(words):
            units.append(words[i] + ' ' + words[i + 1])
            i += 2
        else:
            units.append(words[i])
            i += 1
    return units


_EN_ABD = re.compile(r"^['ʿ‘’`]?(abd|ubayd|'ubayd)$", re.IGNORECASE)


def _english_units(english):
    words = [w.strip(',;') for w in (english or '').replace(',', ' ').split() if w.strip(',;')]
    units, i = [], 0
    while i < len(words):
        if _EN_ABD.match(words[i]) and i + 1 < len(words):
            units.append(words[i] + ' ' + words[i + 1])
            i += 2
        else:
            units.append(words[i])
            i += 1
    return units


def _kind_ar(unit):
    return lineage._KIND_AR.get(unit) or lineage._KIND_AR.get(unit.replace('ى', 'ي'))


def _kind_en(unit):
    return lineage._KIND_EN.get(unit.lower())


def _unit_agrees(arabic, english):
    if _kind_ar(arabic) or _kind_en(english):
        return _kind_ar(arabic) == _kind_en(english)
    return _skeletons_agree(_arabic_skeleton(arabic), _english_skeleton(english))


def _align(short, long_, agree, joined):
    """Each unit of «short» matched, in order, to one unit of «long_» or to two adjacent ones
    («joined» says whether two may stand for one); units of «long_» may go unmatched. The
    matched spans, or None."""
    from functools import lru_cache

    @lru_cache(maxsize=None)
    def go(i, j):
        if j == len(short):
            return ()
        for k in range(i, len(long_)):
            if agree(long_[k], short[j]):
                rest = go(k + 1, j + 1)
                if rest is not None:
                    return ((k, k + 1),) + rest
            if k + 1 < len(long_) and joined(long_[k], long_[k + 1], short[j]):
                rest = go(k + 2, j + 1)
                if rest is not None:
                    return ((k, k + 2),) + rest
        return None

    return go(0, 0)


def _joined_arabic(first, second, english):
    """«الجو اليقي» printed apart for al-Jawaliqi."""
    if _kind_ar(first) or _kind_ar(second) or _kind_en(english):
        return False
    return _skeletons_agree(_arabic_skeleton(first + second), _english_skeleton(english))


def english_alignment(arabic, english):
    """For each English word, the Arabic word(s) it renders, in order; None when some English
    word renders nothing in the Arabic. An English word may render two Arabic ones a printer
    split, and Arabic words may go unrendered: the English is then an abridgement."""
    a, e = tuple(_arabic_units(arabic)), tuple(_english_units(english))
    if not a or not e:
        return None
    found = _align(e, a, _unit_agrees, _joined_arabic)
    return None if found is None else list(zip(found, e))


def english_renders(arabic, english):
    """Whether «english» renders «arabic»: abridged («Muhammad ibn Yahya al-'Attar» for the
    whole «محمد بن يحيى أبو جعفر العطار القمي»), or fuller, where every Arabic word is rendered
    and the English adds what the heading leaves out («Yunus, maternal uncle of Abi
    al-Mustahil» for «يونس»). Not when it says a word the Arabic contradicts."""
    if english_alignment(arabic, english) is not None:
        return True
    a, e = tuple(_arabic_units(arabic)), tuple(_english_units(english))
    if not a or not e:
        return False
    return _align(a, e, lambda en, ar: _unit_agrees(ar, en), lambda *_: False) is not None


def respelled_where_aligned(arabic, english):
    """«english» with each word the hand-checked list spells otherwise put right, wherever the
    word renders one Arabic word — in an abridged name too, which respell() cannot align."""
    found = english_alignment(arabic, english)
    if not found:
        return english
    units = _arabic_units(arabic)
    out = []
    for (start, end), word in found:
        right = RESPELLED.get(units[start]) if end == start + 1 else None
        if right and not _kind_en(word) and _fold(right) != _fold(word):
            if not out and right[:1].islower():
                right = right[0].upper() + right[1:]
            word = right
        out.append(word)
    return ' '.join(out)


def _fold(word):
    import unicodedata
    text = unicodedata.normalize('NFKD', word)
    text = ''.join(c for c in text if not unicodedata.combining(c))
    return re.sub(r"[ʿʾ'‘’`\-]", '', text).lower()


# A heading that says what the man was rather than who («أخو مرازم», «خادم إبراهيم بن عبدة»,
# «شيخ من ولد عدي بن حاتم») is translated by the books, not transliterated: "Brother of Marazim"
# is right and "Akhu Murazim" is not English. Those are left as the book has them.
_DESCRIBES = {"أخو", "أخي", "أخا", "اخو", "اخي", "أخت", "اخت", "عم", "عمة", "خال", "خالة", "جد",
              "جدة", "خادم", "غلام", "شيخ", "من", "ولد", "يلقب", "وليس", "صاحب", "زوج", "زوجة",
              "امرأة", "رجل", "بعض", "أولاد", "بنو", "بني", "آل", "صلى", "وآله", "مولاه"}
# Honorifics the Arabic heading carries and no English rendering of a name should.
_HONORIFIC = re.compile(r"\s*(?:عليهم?\s*السلام|عليهما\s*السلام|صلى\s*الله\s*عليه(?:\s*وآله)?(?:\s*وسلم)?|"
                        r"رضي\s*الله\s*عنه|رحمه\s*الله|قدس\s*سره)")


def checked_english(arabic, english, aliases, table):
    """The English to show beside «arabic», and what was done to it: «kept», «respelled» (a
    word the hand-checked list spells otherwise) or «rebuilt» (the English rendered something
    the Arabic does not say, and was rendered again from the Arabic)."""
    if not arabic or not english:
        return english, "kept"
    plain = _HONORIFIC.sub("", arabic).strip()
    if english_renders(plain, english):
        fixed = respelled_where_aligned(plain, english)
        return fixed, ("respelled" if fixed != english else "kept")
    units = _arabic_units(plain)
    if any(w in _DESCRIBES for unit in units for w in unit.split()) or '(' in english:
        return english, "kept"
    # Every word must have a spelling someone checked, the corpus prints, or a book of his
    # gives: a word only the letters can spell («وقاص» as Wqas) leaves the book's English,
    # wrong as it may be, the better of two bad names.
    spelt_by = [english] + list(aliases or [])
    for unit in units:
        if _kind_ar(unit):
            continue
        parts = unit.split()
        if all(RESPELLED.get(p) or table.get(p) for p in parts) or RESPELLED.get(unit.replace(' ', '')):
            continue
        if any(_unit_agrees(unit, w) for src in spelt_by for w in _english_units(src)):
            continue
        return english, "kept"
    return rebuilt_english(plain, english, aliases, table), "rebuilt"


def rebuilt_english(arabic, english, aliases, table):
    """The Arabic rendered word by word, each word spelt the way a book spells it where one
    does: from the English shown, as far as it does render the Arabic, then from the man's
    other English names, then the table, then the letters."""
    units = _arabic_units(arabic)
    if not units:
        return english
    spelt = {}
    for source in [english] + list(aliases or []):
        words = _english_units(source)
        for unit in units:
            if unit in spelt or _kind_ar(unit):
                continue
            for word in words:
                if not _kind_en(word) and _unit_agrees(unit, word) and not word.startswith('('):
                    spelt[unit] = word
                    break
    out = []
    for position, unit in enumerate(units):
        if _kind_ar(unit):
            out.append(table.get(unit) or {'son': 'ibn', 'father': 'Abu', 'mother': 'Umm',
                                           'daughter': 'bint'}[_kind_ar(unit)])
            continue
        # The hand-checked spelling, then the corpus's usual one, then a book's for a word the
        # corpus has no usual spelling of, then the letters: a name rebuilt because the English
        # shown was another book's misreading should not keep that book's vowels.
        word = RESPELLED.get(unit.replace(' ', '')) or RESPELLED.get(unit)
        if not word and ' ' not in unit:
            word = table.get(unit)
        if not word:
            word = spelt.get(unit)
        if not word:
            word = english_for(unit, table) if ' ' not in unit else ' '.join(
                table.get(part) or by_letter(part) for part in unit.split())
        out.append(word)
    text = ' '.join(w for w in out if w)
    if text[:1].islower():
        text = text[0].upper() + text[1:]
    elif text[:1] in "'ʿ" and len(text) > 1:
        text = text[0] + text[1].upper() + text[2:]
    return text


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
