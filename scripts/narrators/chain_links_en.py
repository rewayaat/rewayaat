#!/usr/bin/env python3
"""Puts the same links on the English chain, by reading it against the Arabic one.

English is what most readers of this site see, and until now not one name in an English isnād
was a link. The obvious way to fix that — resolve the English names against the books the way
the Arabic ones were resolved — is the wrong way twice over. The books are in Arabic, so every
match would go through a transliteration and inherit its errors; and the two languages could
then disagree about the same name in the same chain, which is a defect no reader could explain.

So the English chain is not resolved at all. It is *aligned*: the translation runs name for
name with the Arabic, in the same order, and each English name takes the man the Arabic name
in the same place was already given. What the Arabic refused, the English refuses too.

Alignment is by consonant skeleton, which is what survives transliteration: «محمد» and
«Muhammad» both reduce to mhmd, «عيسى» and «'Isa» both to s. Measured against deliberately
shifted pairs, a skeleton agreement of 0.8 accepts 82% of names that do line up and 1.8% of
names that do not, so a name is linked only where the alignment and the skeleton agree. The
sequences are aligned with gaps because the translations drop names — most often the Imam who
closes the chain, «عن أبي عبد الله (عليه السلام) قال» becoming "who has narrated the
following" — and a dropped name must not shift every link after it.

Names are placed one at a time, in order, wherever in the English chain they are the same name
(`place_names`), and not one to a stretch between joining phrases: translations run two men
together without a join, write "his father" for «أبيه» and "Imam Jafar ibn Muhammad Al-Sadiq"
where the Arabic says «أبي عبد الله». Each of those has its own rule below, held to the same
standard as a skeleton: a name that is not plainly the same man is left without a link.

Usage:
    python3 scripts/narrators/chain_links_en.py --dry-run
    python3 scripts/narrators/chain_links_en.py --live --index rewayaat_hadith
"""
import argparse
from collections import Counter
import difflib
import functools
import json
import os
import re
import unicodedata

W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
TMP = f'{W}/tmp'
LINKED = ("one", "chosen", "convention")
# What a link is written as. `link_chain` also hangs the Arabic it came from on each one, for
# the audit; the index is not the place for it.
SHIPPED = ("s", "e", "t", "p", "g")
AGREE = 0.8      # the skeletons must look this alike before a name is linked
SHORT = 4        # a skeleton this short is the same name only if it is the same letters
GAP = -0.3       # what it costs to leave a name unplaced; a placed name is always worth more

# How a chain is joined together in English. Longest phrases first, so "has narrated from" is
# not cut at "from". What falls between two of these is a name, a kinship or a collective.
JOIN = re.compile(r"""
    ^\s*\d+\s*[\.\-]\s*
  | ^\s*\(\d+\)\s*
  | \b(?:has|have|had)\s+narrated\s+(?:to\s+\w+\s+)?from\b
  | \bnarrated\s+to\s+(?:me|us|him)\b
  | \bwho\s+has\s+(?:narrated|said|reported)\s+the\s+following\b
  | \bhas\s+said\s+the\s+following\b
  | \bwho\s+has\s+said\b
  | \bin\s+a\s+marfu'\s+manner\b
  | \bfrom\b | \bsaid\b | \band\b | \bthat\b
  # The Saduq translations join a chain with "on the authority of" and with a verb — "quoted",
  # "narrated", "informed", "told" — and without these a whole isnad was one slot, which can
  # hold one link however many men it names. A comma is a join for the same reason; what it
  # cuts off is either a name or an honorific, and an honorific matches nothing.
  | \bon\s+the\s+authority\s+of\b
  | \b(?:was|were|been)\s+(?:narrated|told|informed|related)\s+(?:to\s+\w+\s+)?by\b
  | \b(?:narrated|quoted|informed|told|reported|related|transmitted|heard)\b
  | ,
  # Muhsini's Mu'jam sets a chain with dashes rather than words — «Ibn Idris – his father –
  # Ibn Hashim – Ibn Abi Umayr» — and without these the whole isnad was one slot, which can
  # hold one link at most however many men it names.
  # A plain hyphen is not among them: this corpus prints «'Abd al- Rahman» and «al- Abbas»
  # with a space after the article, and cutting there cuts a man in half.
  | \s[–—]\s | ;
""", re.X | re.I)

DIACRITIC = re.compile(r'[ؗ-ًؚ-ْـٰ]')
ARABIC_CONSONANT = {
    'ب': 'b', 'ت': 't', 'ث': 't', 'ج': 'j', 'ح': 'h', 'خ': 'x', 'د': 'd', 'ذ': 'd',
    'ر': 'r', 'ز': 'z', 'س': 's', 'ش': 's', 'ص': 's', 'ض': 'd', 'ط': 't', 'ظ': 'd',
    'غ': 'g', 'ف': 'f', 'ق': 'q', 'ك': 'k', 'ل': 'l', 'م': 'm', 'ن': 'n', 'ه': 'h',
    'ة': 'h', 'ک': 'k',
}
# The letters a transliteration writes with two: they must fold before the vowels are dropped.
ENGLISH_PAIR = (('kh', 'x'), ('th', 't'), ('dh', 'd'), ('sh', 's'), ('gh', 'g'), ('ph', 'f'))
EDGE = " ,.;:()[]{}“”\"'’‘-–—…"
# A relation named alone, as the Arabic spells it and as the English says it.
KIN_AR = {"father": {"ابيه": "his", "ابيها": "his", "ابيهم": "his", "ابي": "any"},
          "grandfather": {"جده": "his", "جدي": "any"}}
KIN_EN = {"father": r"\b(my|his|her|their)\s+father\b",
          "grandfather": r"\b(my|his|her|their)\s+(?:paternal\s+)?grandfather\b"}
# «أبي» is "my father", and also "his father" where the translation puts the compiler's father
# in the third person ("that his father - may God be pleased with him - narrated"). «أبيه» is
# "his father" and never "my father": a link from one to the other names the wrong man.


# «ابن» and «بن» are one word in Arabic practice and «ibn», «bin» and «b.» one in English,
# and none of them is part of a name. Folded to one token first, so that the vowel a name
# begins with can be marked without «ibn» disagreeing with «بن» in every chain on the site.
CONNECTOR = {"ibn", "bin", "ben", "b", "bn", "ابن", "بن", "أبن"}
VOWEL = "aeiou"
OPENER = "اأإآءئؤع"
# The words a kunya opens on, however the translation spells them.
# Words that read as a short name once the vowels are gone: «علي» and "Allah" are both 'l, and
# "al-Alawi" too, and «يحيى» and "He" are both h. None is ever the man a chain means.
NOT_MEN = {"allah", "god", "alawi", "alawiy", "alawite", "he", "she", "it", "the", "this", "that",
           "his", "her", "they", "we", "who", "and", "then", "there", "as", "by", "at", "to", "in",
           "is", "on", "of", "from", "said", "also", "one", "a", "an", "i", "was", "were", "has",
           "had", "have", "be", "been", "with", "for", "all", "some", "other", "another"}
# The first words of Arabic names that 20 or more different men in the corpus open on, as
# skeletons: Muhammad, Abd, Hasan/Husayn, Abu, Ali, Ahmad, Umar/Amr, Ibrahim, Jafar, Yahya,
# Sad, Ismail, Zayd, Sulayman, Musa, Ishaq, Dawud, Fadl, Qasim, Isa, Salim, Hammad, Salih.
COMMON = {"mhmd", "'bd", "hsn", "'b", "'l", "'hmd", "'mr", "'brhm", "jfr", "h", "sd", "'sml",
          "zd", "slmn", "ms", "'shq", "d", "fdl", "qsm", "'s", "slm", "hmd", "slh"}
# How an Imam is named in English where the Arabic names him another way: the Arabic says his
# kunya, «أبي عبد الله», and the Faqih's translation says "Imam Jafar ibn Muhammad Al-Sadiq".
# No skeleton can tell those two are one man, but the pipeline has already said who the Arabic
# one is, and these are the names of that man and no other.
IMAM_AS = {
    "i01": r"Am[ie]r(?:ul|\s+al|\s+ul)?[\s-]*Mu'?[’‘]?m[iu]ne?e?n|Commander\s+of\s+the\s+Faithful",
    "i05": r"Muhammad\s+(?:ibn|bin|b\.)\s+Ali\s+(?:al-|as-)?Baqir|\b(?:al-|Al-)?Baqir\b",
    "i06": r"Ja'?[’‘]?far\s+(?:ibn|bin|b\.)\s+Muhammad\s+(?:al-|Al-|as-)?Sadiq|\b(?:al-|as-|Al-)?Sadiq\b",
    "i07": r"Musa\s+(?:ibn|bin|b\.)\s+Ja'?[’‘]?far\s+(?:al-|Al-)?K[ah]?[dz]him|\b(?:al-|Al-)?K[ah]?[dz]im\b",
    "i08": r"Ali\s+(?:ibn|bin|b\.)\s+Musa\s+(?:al-|Al-|ar-|Ar-)?Ri[dz]h?a|\b(?:al-|Al-|ar-|Ar-)?Ri[dz]h?a\b",
}
# The words that stand in front of a chain's first name without being part of it.
LEADERS = {"hadith", "h", "narrated", "it", "is", "by", "from", "also", "and", "then", "that"}
MAWLA = {"mawla", "mawlat", "mawlah", "servant", "client", "freedman", "slave"}
KUNYA = {"abu", "abi", "aba", "abul", "abil", "abou"}


def collapse(letters):
    out = []
    for letter in letters:
        if not out or out[-1] != letter:
            out.append(letter)
    return ''.join(out)


def skeleton_ar(text):
    """What is left of an Arabic name when the vowels and the hamzas are taken out."""
    words = []
    for word in DIACRITIC.sub('', text).split():
        if word in CONNECTOR:
            words.append("bn")
            continue
        # A name that opens on an alif keeps a mark for it. Without one «أحمد» reduces to hmd
        # and «محمد» to mhmd, and since a skeleton is compared as a bag of letters, «محمد بن
        # أحمد» and «أحمد بن محمد» — two different men, and a pair the translations do get
        # the wrong way round — came out as the same name.
        opener = "'" if word and word[0] in OPENER else ""
        body = ''.join(ARABIC_CONSONANT.get(c, '') for c in word)
        words.append(opener + collapse(body))
    return ' '.join(w for w in words if w)


# What the scan of the English printed for letters it could not read. Each is a spelling no
# name has, and each is the same few mistakes over and over: the H of «الحسين» read as "ff" or
# "FI", the l of Ali and of the article read as f, i or I, the I of Isa read as T.
SCAN_ERRORS = ((r"\bff(?=[a-z])", "H"), (r"\b(?:FI|Fl)(?=[a-z])", "H"), (r"\baI-", "al-"),
               (r"\bAfi(?=\s|$)", "Ali"), (r"\bAii\b", "Ali"), (r"\bTs(?=a\b|ma)", "Is"))


def repair_scan(text):
    for pattern, right in SCAN_ERRORS:
        text = re.sub(pattern, right, text)
    return text


def skeleton_en(text):
    """The same for a transliteration, so the two can be compared at all."""
    text = repair_scan(text).lower()
    # A scholarly transliteration writes the letters Arabic distinguishes with a mark over or
    # under a Latin one — «Muḥammad ibn Yaḥyā» — and `isalpha()` is true of every one of them,
    # so they came through the vowel strip below and were compared as consonants: «Muḥammad»
    # reduced to mḥmd where «محمد» reduces to mhmd, and the two disagree at the letter that
    # decides. 1,323 of the 25,508 English chains print at least one of these marks, Kāmil
    # al-Ziyārāt most of them, and every marked name in them was unreachable — 1,661 links.
    #
    # Decomposing and dropping the combining marks folds all of them at once, including ones
    # nobody listed: ā to a and so into the vowels, ḥ to h, ḍ to d, ṣ to s, ṭ to t, and the
    # caron this typesetter uses for ṯ and ḏ to t and d, which is where the digraph pairs
    # would have put them anyway. It runs before those pairs and before the vowel strip so
    # that both still see plain letters. «ʿ» and «ʾ» are letters in their own right rather
    # than marks, so they survive this and go on being read as the hamza just below.
    text = ''.join(c for c in unicodedata.normalize('NFD', text)
                   if unicodedata.category(c) != 'Mn')
    for mark in "’‘`ʿʾ":
        text = text.replace(mark, "'")
    # «الرازي» is printed "ar-Razi", «التيملي» "at-Taymali": the article's lām takes the sound
    # of the letter after it. It is the article all the same, and is read as «al-» so that it is
    # carried onto the name like the other.
    text = re.sub(r"(?<![a-z'])(?:ar|an|as|at|ad|az|ash|ath|adh|al|all)-\s?(?=[a-z'’‘ʿ])", "al-", text)
    for mark in "-.,":
        text = text.replace(mark, ' ')
    words, article = [], ""
    for word in text.split():
        bare = article + word.strip("'")
        article = ""
        # «al-Hassan» is two words to a hyphen and one to Arabic, so the article is carried
        # onto the name it belongs to rather than dropped: «الحسن» is 'lhsn either way.
        # This corpus prints «af-Hassan» for «al-Hassan» in places, an f where the scan read
        # the stem of an l, and the article carries over the same way.
        if bare in ("al", "el", "af"):
            article = bare
            continue
        # "Abu 'l Aswad", "Abu'l-Hasan", "Abul Qasim": the kunya, and the article after it.
        if bare == "l":
            article = "al"
            continue
        kunya = re.fullmatch(r"(ab[uia])'?l", bare)
        if kunya:
            words.append("'b")
            article = "al"
            continue
        # «عبد الرحمن» is "Abdur Rahman", "Abdul Rahman" and "Abd al-Rahman": the article's lām
        # takes the sound of the letter after it and is written onto the word before.
        if re.fullmatch(r"abd[ua](?:l|r|s|n|t|d|z|sh|th|dh)", bare):
            words.append("'bd")
            article = "al"
            continue
        # A title in front of a name, "Abu Ja'far Imam Muhammad ibn Ali", is no part of it.
        if bare == "imam":
            continue
        if bare in CONNECTOR:
            words.append("bn")
            continue
        for pair, one in ENGLISH_PAIR:
            bare = bare.replace(pair, one)
        opener = "'" if bare and bare[0] in VOWEL else ""
        body = ''.join(c for c in bare if c.isalpha() and c not in 'aeiouwyv')
        words.append(opener + collapse(body))
    return ' '.join(w for w in words if w)


def alike(one, other):
    return difflib.SequenceMatcher(None, one, other).ratio() if one and other else 0.0


def comparable(skeleton):
    """A skeleton written to the rule the other script uses.

    Two differences are spelling, not identity. A mention cut at «ابن مسكان» keeps the
    connector the translation leaves off — "Muskan" — and «عبد الله» is two words in Arabic
    and one in most transliterations. Both sides are reduced to one shape before they are
    compared, so that «ابن مسكان» against "Muskan" is a name against the same name rather
    than a name against two thirds of one.
    """
    words = skeleton.split()
    if words and words[0] == "bn" and len(words) > 1:
        words = words[1:]
    joined, at = [], 0
    while at < len(words):
        if words[at] in ("'bd", "'bdl") and at + 1 < len(words):
            joined.append(words[at] + words[at + 1])
            at += 2
            continue
        joined.append(words[at])
        at += 1
    return " ".join(joined)


@functools.lru_cache(maxsize=None)
def same_man(english, arabic, agree):
    """Whether two skeletons are the same name — not merely the same letters in some order.

    A ratio alone cannot tell «محمد بن أحمد» from «أحمد بن محمد». They are two men, the
    translations do put them the wrong way round, and reversing a name leaves every letter
    where a bag-of-letters comparison can still find it. So the first word of the name has to
    agree on its own, and the mark a name opens with — the alif of «أحمد» against the mīm of
    «محمد» — is what decides it.
    """
    # A chain names a man as long or as short as it likes: «عبد الله بن ميمون» against
    # "Abdallah ibn Maymun al-Qaddah" is one nisba apart, and comparing the whole of each
    # put a name against a name and a half. The shorter one's length is what they are
    # compared over; the head test below is what keeps a prefix from matching the wrong man.
    one, other = comparable(english), comparable(arabic)
    words = min(len(one.split()), len(other.split()))
    best = max(alike(english, arabic), alike(one, other),
               alike(" ".join(one.split()[:words]), " ".join(other.split()[:words])),
               alike(one.replace("z", "d"), other.replace("z", "d")),
               alike(" ".join(map(without_article, one.split())),
                     " ".join(map(without_article, other.split()))))
    if best < agree:
        return False
    # Eight tenths of a two-letter skeleton is one letter, and one letter is the difference
    # between «أبان» and "one", or between «أبان» and the kunya «أبي». Where the shorter name is
    # that short there is nothing for a ratio to measure, so the two must be the same.
    # The one difference allowed is the final «ة» the Arabic has and the translation leaves
    # off: «زرارة» is "Zurara". It is the Arabic that may have the h; "Allah" is not «علي».
    if (min(len(one.replace(" ", "")), len(other.replace(" ", ""))) <= SHORT and best < 1.0
            and without_article(one).lstrip("'")
            != without_article(unmarked(other)).lstrip("'")):
        return False
    # Each element of one name has to be the element of the other that stands in its place.
    # The ratio above would let «أحمد بن محمد بن عيسى» pass for "Ahmad ibn Muhammad ibn Abi
    # Nasr", being one short word apart in a long string, and they are two men.
    mine = [w for w in one.split() if w != "bn"]
    theirs = [w for w in other.split() if w != "bn"]
    for here, there in zip(mine, theirs):
        if alike(loose(here), loose(there)) < 0.6:
            return False
    head_en = heading_word(comparable(english))
    head_ar = heading_word(comparable(arabic))
    # «السندي بن محمد» is printed "Sindi bin Muhammed" as often as "al-Sindi", so the head is
    # tried both with its article and without: the alif the article opens with is not the
    # alif the reversal guard is looking for.
    # On a short name the head is half the evidence, and «حماد بن عثمان» against "Hammad
    # ibn Isa" shares it: two men the chains keep apart. Where there is nothing after the
    # patronymic to tell them apart, the patronymic itself has to agree.
    # «بن» is left out of both, since the translations leave it out of "al-Hasan as-Sariy" for
    # «الحسن بن السري», and the article is left off, since they leave that out as often.
    if mine and theirs and min(len(mine), len(theirs)) <= 3:
        # Over the shorter name's length, so that a trailing nisba the translation adds —
        # "Abdallah ibn Maymun al-Qaddah" for «عبد الله بن ميمون» — is not read as the
        # patronymic disagreeing.
        at = min(len(mine), len(theirs)) - 1
        if alike(loose(mine[at]), loose(theirs[at])) < 0.7:
            return False
    heads = {(head_en, head_ar), (without_article(head_en), without_article(head_ar)),
             (head_en, unmarked(head_ar))}
    for one, other in heads:
        if one.startswith("'") != other.startswith("'"):
            continue
        if alike(one, other) >= 0.8 or alike(one.replace("z", "d"), other.replace("z", "d")) >= 0.8:
            return True
    return False


def unmarked(skeleton):
    """A skeleton with the h that ends a word taken off, since the translations differ on it."""
    return " ".join(w[:-1] if len(w) >= 2 and w.endswith("h") else w for w in skeleton.split())


def heading_word(skeleton):
    """The word a name is headed by, which is not always its first.

    Two shapes get in the way of comparing heads. A mention cut at «ابن مسكان» keeps the
    connector, and the translation prints "Muskan" without it, so the heads being compared
    were «bn» against «mskn». And «عبد الله» is two words in Arabic and one in most
    transliterations — "Abdallah" — so the heads were «'bd» against «'bdlh». Both are the
    same name written to a different rule, and both were being read as two names.
    """
    words = skeleton.split()
    return words[0] if words else ""


def loose(word):
    """A word of a skeleton for comparing element against element.

    The article is taken off, and z is read as d: «ضاد» and «ظ» are written with z by the
    transliterations that follow the Persian and Urdu sound, "Fazeyl" and "Dhaheer" for
    «الفضيل» and «ظهير», where the skeleton keeps d for the one and z for the other.
    """
    return without_article(word).replace("z", "d")


def without_article(head):
    """A name's head with «ال» taken off, where it had one."""
    return head[2:] if head.startswith("'l") and len(head) > 3 else head


def slots(chain):
    """Every stretch of an English chain that stands between two of its joining phrases."""
    found, at = [], 0
    for cut in JOIN.finditer(chain):
        if cut.start() > at:
            found.append((at, cut.start()))
        at = cut.end()
    if at < len(chain):
        found.append((at, len(chain)))
    kept = []
    for start, end in found:
        text = chain[start:end]
        lead = len(text) - len(text.lstrip(EDGE))
        tail = len(text) - len(text.rstrip(EDGE))
        if end - tail - (start + lead) > 1:
            kept.append((start + lead, end - tail))
    # "Abul Qasim, Ja'far ibn Muhammad ibn Qawlawayh" is one man, a kunya and then his name,
    # and the comma that separates them is not a join. Mended here rather than by leaving the
    # comma out of JOIN, because most commas do separate one man from the next.
    merged = []
    for start, end in kept:
        if merged:
            before_start, before_end = merged[-1]
            words = chain[before_start:before_end].split()
            following = chain[start:end].split()
            joined = chain[before_end:start].strip() == ","
            # The same goes for "Abd al-A'la', Mawla ale Sam" and for "`Abd al-Rahman, ibn
            # Muhammad": what follows the comma is the rest of the name.
            if joined and ((len(words) <= 3 and bare_word(words[0]) in KUNYA)
                           or bare_word(following[0]) in CONNECTOR | MAWLA):
                merged[-1] = (before_start, end)
                continue
        merged.append((start, end))
    return merged


def words_of(text, at):
    """Every word of a slot with the place it stands in the chain."""
    found = []
    for match in re.finditer(r"[^\s]+", text):
        raw = match.group(0)
        word = raw.strip(EDGE)
        # A number or a bullet in front of a name — "422 - Fadhl bin Shazan" — is no word of it.
        if word and any(c.isalpha() for c in word):
            # Where the word itself sits, not the punctuation that closes it: a link on
            # "Abul Hasan," should not carry the comma.
            start = at + match.start() + raw.index(word)
            found.append((word, start, start + len(word)))
    return found


def windows_of(slot_words, longest, chain=""):
    """Every run of words in a slot, with its skeleton, computed once.

    A chain asks the same slot about every name in it, and the skeleton of a run of words is
    the same answer each time, so computing it per pair made the pass several times longer
    than it needed to be.

    Each run also records what stands around it, because a run is only a name when it is not
    a piece of a longer one:
      - «ibn» before it, or «ibn» as its first word: it is the end of somebody's patronymic —
        "Ahmad ibn Abi Abdillah al-Barqi" holds "Abi Abdillah", which is the Imam's kunya and
        not the man. Only an Arabic mention that is itself cut at «ابن» can be that.
      - «ibn» after it: the English name goes on past where this run stops. "Ahmad ibn
        Muhammad" is not the same man as the "Ahmad ibn Muhammad ibn Abi Nasr" it begins.
      - a kunya, then a name and an «ibn»: "Abu Abdillah Muhammad ibn Khalid" is a man called
        Abu Abdillah, not the Imam ("Abu Ja'far Imam Muhammad ibn Ali" is the Imam, in full).
      - no capital letter anywhere in it, bar the particles a name is built from: "is" and
        "one" are words, not men. Nor does a name end on «ibn» or on a bare kunya ("Ali ibn
        Abi" is the front of "Ali ibn Abi Talib"), or read as "Allah".
    """
    made = []
    for start in range(len(slot_words)):
        piece = []
        before = bare_word(slot_words[start - 1][0]) if start else ""
        for end in range(start, min(start + longest, len(slot_words))):
            piece.append(slot_words[end][0])
            skeleton = skeleton_en(" ".join(piece))
            if skeleton:
                following = [bare_word(w[0]) for w in slot_words[end + 1:end + 4]]
                runs_on = (bare_word(piece[0]) in KUNYA and following[:1] != ["imam"]
                           and any(word in CONNECTOR for word in following[1:2]))
                of_a_son = before in CONNECTOR or bare_word(piece[0]) in CONNECTOR
                # A lowercase run is a name only when it is all there is in the slot: "from
                # yunus, from" is Yunus, and "has been entrusted" is not a man.
                whole_slot = start == 0 and end == len(slot_words) - 1
                named = (any(c.isupper() for word in piece for c in word) or whole_slot
                         or bare_word(piece[0]) in CONNECTOR | KUNYA | {"al", "el", "af"})
                spoken = [bare_word(w) for w in piece]
                # A nisba alone, with the rest of a man's name in front of it, is the end of his
                # name: «السياري» and the "al-Ash'ariy" of "Abu Ali al-Ash'ariy" share every
                # consonant, and only the words before it tell them apart.
                tail = (start > 0 and len(piece) == 1 and before not in CONNECTOR | LEADERS
                        and slot_words[start - 1][0][:1].isupper()
                        and re.match(r"(?:a[lrnstdz]|ash|ath|adh|al|all)-", piece[0], re.I))
                unfit = (not named or tail or (following[:1] and following[0] in CONNECTOR)
                         or spoken[-1] in CONNECTOR | KUNYA
                         or set(w[2:] if w[:2] in ("al", "el") and len(w) > 4 else w
                                for w in spoken) <= NOT_MEN | CONNECTOR | KUNYA)
                # Kafi's opening "Al-Husayn from Muhammad has narrated from Ahmad" is «الحسين بن
                # محمد» with the ibn put as "from"; 113 chains begin so, and the name that
                # stands before that "from" is the whole of the first man's name in the Arabic.
                after = chain[slot_words[end][2]:slot_words[end][2] + 40]
                from_for_ibn = bool(re.match(r"\s+from\s+[A-Z]\S*\s+(?:has|have)\s+narrated", after)
                                    # and "Ali has narrated from his father", which is Ali ibn
                                    # Ibrahim: the Kafi's way of writing him, and no one else's.
                                    or re.match(r"\s+has\s+narrated\s+from\s+his\s+father", after))
                made.append((len(piece), skeleton, slot_words[start][1], slot_words[end][2],
                             of_a_son, runs_on, bool(unfit), from_for_ibn))
    return made


def bare_word(word):
    """A word with its case, marks, hyphens and apostrophes taken off, for looking it up."""
    word = ''.join(c for c in unicodedata.normalize('NFD', word.lower())
                   if unicodedata.category(c) != 'Mn')
    return ''.join(c for c in word if c.isalpha())


def best_window(windows, arabic, agree=None, veto=None):
    """The run of words inside a slot that best answers one Arabic name.

    A slot is whatever stands between two joining phrases, and that is often more than a
    name: «My father, may Allah grant him mercy, said: We were told by Muhammed bin Yahya
    al-'Attar» is one slot, and «Abu Ja'far, peace be upon him,» is another. Compared whole,
    neither looks like the Arabic name inside it, so both were refused and the reader saw a
    chain with its first and last men unlinked. Comparing every run of words instead finds
    the name and nothing else, and hands back where it starts and ends so the link lands on
    the name rather than on the sentence around it.

    The run is bounded by the length of the Arabic name, give or take: a name of three words
    is not hiding in a run of nine.
    """
    target = len(arabic.split()) or 1
    shortest, longest = max(1, target - 2), target + 3
    # A mention that opens on «ابن» is already a cut from the middle of a name; any other
    # one is a whole name, and so cannot be the tail of an English patronymic. Nor can a
    # name with no «ابن» in it be the kunya at the head of a longer English name.
    whole = not arabic.startswith("bn")
    names_a_son = "bn" in arabic.split()
    best = (0.0, None, None, "")
    # One matcher with the Arabic side fixed, and difflib's two cheap bounds before the real
    # comparison: a chain asks this question tens of thousands of times and all but a few of
    # the answers are «not even close».
    matcher = difflib.SequenceMatcher(None)
    matcher.set_seq2(arabic)
    # A common given name is no evidence of a man whose Arabic name has a father in it:
    # "Muhammad" is the first word of «محمد بن الحسن» and of 270 other men in the corpus. A
    # rarer one is: "Sama'a" for «سماعة بن مهران» is how the translations write him.
    fathered = "bn" in arabic.split()[1:]
    for count, skeleton, start, end, of_a_son, runs_on, unfit, from_for_ibn in windows:
        if count < shortest or count > longest or unfit:
            continue
        if (fathered and len(skeleton.split()) == 1 and not from_for_ibn
                and without_article(skeleton) in COMMON):
            continue
        if whole and of_a_son:
            continue
        if runs_on and not names_a_son:
            continue
        matcher.set_seq1(skeleton)
        if matcher.real_quick_ratio() <= best[0] or matcher.quick_ratio() <= best[0]:
            continue
        score = matcher.ratio()
        # The best run that is the same name, not the best run: "Zurara who" outscores
        # "Zurara" against «زرارة» for the h of "who", and is then no name at all.
        if score > best[0] and (agree is None or same_man(skeleton, arabic, agree)):
            if veto is None or not veto(start, end):
                best = (score, start, end, skeleton)
    return best


def place_names(candidates, count):
    """Choose, for each Arabic name in order, where in the English chain it stands.

    `candidates[j]` holds every place name j could be, as (score, start, end, extra), and the
    choice keeps them in order and apart: each name that is placed starts where the one before
    it ended or later. That is the whole of the alignment. It is not one name to a stretch
    between joining phrases, as it was, because translations run two men together without a
    join ("Hammad ibn 'Isa form Hariz", "Ibn Abi 'Umayr Mu'awiyah ibn 'Ammar") and a stretch
    with two names in it could hold one of them at most.

    Order is still what makes it safe. A name can only be placed after the one before it, and a
    place that is not the same name is never a candidate at all, so a name is left unplaced
    rather than put on the nearest stranger. Every placed name is worth the same to the total
    and every name left out costs a little, so as many are placed as can be, and where there
    is a choice the better match wins.
    """
    best = {}
    for j in range(count):
        for c, (score, start, end, _) in enumerate(candidates[j]):
            # Either nothing before it was placed, or the nearest earlier placement that ends
            # before this one begins; skipping the names between costs a gap apiece.
            value, came_from = GAP * j, None
            for k in range(j):
                for d, (_, _, before_end, _) in enumerate(candidates[k]):
                    behind = best[(k, d)][0] + GAP * (j - k - 1)
                    if before_end <= start and behind > value:
                        value, came_from = behind, (k, d)
            best[(j, c)] = (value + score - 0.5, came_from)
    # Walk back from the best finish, to recover who stood where. Nothing placed at all is
    # worth a gap per name, and a finish has to beat that.
    finish, at = GAP * count, None
    for (j, c), (value, _) in best.items():
        if value + GAP * (count - 1 - j) > finish:
            finish, at = value + GAP * (count - 1 - j), (j, c)
    placed = []
    while at is not None:
        placed.append(at)
        at = best[at][1]
    return list(reversed(placed))


def adds_to_the_name(name, arabic, slug, following=""):
    """Whether the English name says more than the Arabic one, and what it adds is not the man's.

    The Arabic mention may be as short as «فضالة», where the translation writes "Fadalah ibn
    Jamil" and the man the chain resolved is Fadala ibn Ayyub. The skeletons agree as far as
    the Arabic goes, and what the English adds after it is exactly what tells two men of one
    name apart. The person's own canonical name is on hand in the slug, so a patronymic the
    English adds has to be found there. A slug that is empty, or cut short before it reaches
    the word, cannot contradict anything, and does not.

    A nisba is held to less. The slug often has none, and a man is no different for one, so the
    nisba that stands after the name — "as-Saffar" after "Muhammad ibn al-Hasan" — counts
    against the link only when the slug is whole (not cut at its 60 characters) and long
    enough to have carried it, which "muhammad-ibn-al-hasan-ibn-ahmad-ibn-al-walid" is.
    """
    if not slug:
        return False
    asked = [w for w in skeleton_ar(arabic).split() if w != "bn"]
    seen, after_ibn, extra, nisbas = 0, False, [], []
    for token in name.split():
        skeleton = skeleton_en(token)
        if not skeleton:
            continue
        if skeleton == "bn":
            after_ibn = True
            continue
        seen += 1
        # A patronymic past where the Arabic stops. Not a piece of a name the scan broke up,
        # "ay" and "r" in "ibn abu 'Um ay r", which are not words at all.
        if seen > len(asked) and len(bare_word(token)) > 2:
            (extra if after_ibn else nisbas).append(skeleton)
        after_ibn = False
    known = [without_article(w) for w in skeleton_en(slug.replace("-", " ")).split() if w != "bn"]
    if len(slug) < 58 and len(known) >= 4:
        after = re.match(r"((?:\s+(?:a[lrnstdz]|ash|ath|adh|al|all)-\s?[^\s,;:()]+){1,2})",
                         following, re.I)
        if after:
            nisbas += [w for w in skeleton_en(after.group(1)).split() if len(w) > 2]
        held = [without_article(w) for w in asked]
        extra += [w for w in nisbas if w.startswith("'l")
                  and max([alike(without_article(w), have) for have in held] or [0.0]) < 0.75]
    return any(max([alike(without_article(word), have) for have in known] or [0.0]) < 0.75
               for word in extra)


def balanced(chain, start, end):
    """A span pulled back so that it does not stop inside a bracket it opened.

    The editions put part of a name in brackets — "Muhammad ibn Ali (al-Sayrafiy)", "(Abdullah)
    ibn Bikair" — and an honorific too, "Ali ibn Musa Ar-Ridha (as)". A run of words chosen for
    the name can begin or end between the two brackets, and the link then draws an underline
    with half a bracket in it. Where the span opens one it never closes, the opening word comes
    off; where it closes one it never opened, the span reaches back to take the opener in, since
    what stands in the brackets there is the name's own first word.
    """
    while chain.count("(", start, end) > chain.count(")", start, end):
        at = chain.rfind("(", start, end)
        end = len(chain[:at].rstrip())
    while chain.count(")", start, end) > chain.count("(", start, end):
        at = chain.rfind("(", 0, start)
        if at < 0 or chain.count(")", at, start):
            break
        start = at
    return start, end


def kin_of(mention):
    """Which relation a mention names, when it names only a relation: «أبيه» is "his father".

    Returns the relation and whose it must be in English ("his" or "any"), or None.
    """
    if mention["kind"] != "relative":
        return None
    text = DIACRITIC.sub('', mention["text"]).replace('ی', 'ي').replace('ى', 'ي')
    text = text.replace('أ', 'ا').replace('إ', 'ا').strip()
    for relation, forms in KIN_AR.items():
        if text in forms:
            return relation, forms[text]
    return None


def link_chain(chain, places, mentions, agree, slug_of, counts, missed=None):
    """The links one English chain carries, taken from the Arabic mentions it was read against.

    Names go through the alignment. A relation does not: «أبيه» has no consonants to compare
    with "his father", and left among the names it was paired with whatever stood in its place
    and refused, which is why not one of the 6,400 relatives the Arabic links was ever linked
    here. A relation is carried over only where the two chains agree on how many of that
    relation they hold, because "his father" twice in one chain, one of which the translation
    drops, is a pair no one can match by position and a link on the wrong one names the wrong man.

    `missed`, where given, collects the positions of the Arabic links that found no English name.

    Each link carries `a`, the Arabic words it was taken from. The site has no use for it and
    `main` leaves it out of what it writes, but it is the whole of the audit's question — «is
    this English name the same name as the Arabic one the link came from» — and the audit draws
    its tasks from this function so that it measures the placement that ships, not a second one.
    """
    kin = {}
    for at, mention in enumerate(mentions):
        found = kin_of(mention)
        if found:
            kin.setdefault(found[0], []).append((at, found[1]))
    named = [at for at in range(len(mentions)) if kin_of(mentions[at]) is None]
    arabic = [skeleton_ar(mentions[at]["text"]) for at in named]
    slot_words = [words_of(chain[s:e], s) for s, e in places]
    widest = max((len(name.split()) for name in arabic), default=1) + 3
    slot_windows = [windows_of(words, widest, chain) for words in slot_words]
    # Each name's candidates are the best run it has in each slot that is the same name. A name
    # that is not the same name never reaches the alignment.
    candidates = []
    for at, name in enumerate(arabic):
        mention = mentions[named[at]]
        person = mention.get("person_id") or mention.get("imam_id") or ""
        slug = slug_of.get(person, "") if person.startswith("n") else ""

        def veto(start, end):
            return adds_to_the_name(chain[start:end], mention["text"], slug, chain[end:end + 40])

        found = []
        for slot in slot_windows:
            score, start, end, skeleton = best_window(slot, name, agree, veto)
            if start is not None:
                found.append((score, start, end, skeleton))
        candidates.append(found)
    pairs = place_names(candidates, len(arabic))
    links = []
    for at, which in pairs:
        row_mention = mentions[named[at]]
        score, start, end, skeleton = candidates[at][which]
        if row_mention["outcome"] not in LINKED:
            continue
        start, end = balanced(chain, start, end)
        person = row_mention.get("person_id") or row_mention.get("imam_id")
        counts["Arabic links to carry over"] += 1
        links.append({"s": start, "e": end, "t": chain[start:end], "p": person,
                      "g": slug_of.get(person, ""), "a": row_mention["text"].strip()})
    handled = {named[at] for at, _ in pairs} | {at for ats in kin.values() for at, _ in ats}
    for at, mention in enumerate(mentions):
        if mention["outcome"] in LINKED and at not in handled:
            counts["Arabic link with no English name"] += 1
            if missed is not None:
                missed.append(at)
    for relation, ats in kin.items():
        # "his grandfather al-Hassan ibn Rashid" is the grandfather by name, which is a mention
        # of its own in the Arabic and not the relation, so a phrase a name follows is not one.
        found = [hit for hit in re.finditer(KIN_EN[relation], chain, re.I)
                 if not re.match(r"\s+(?:[A-Z]|al-|ibn\b|bin\b|abu\b|abi\b|aba\b)",
                                 chain[hit.end():])]
        if len(found) != len(ats):
            counts["relation counted differently in the two chains"] += sum(
                mentions[at]["outcome"] in LINKED for at, _ in ats)
            if missed is not None:
                missed.extend(at for at, _ in ats if mentions[at]["outcome"] in LINKED)
            continue
        # One pair that disagrees on whose father it is means the two chains are not lined up
        # as the count made them look, and no pair of the relation can be trusted.
        if any(whose == "his" and hit.group(1).lower() == "my"
               for (_, whose), hit in zip(ats, found)):
            counts["relation said as 'my' in English and 'his' in Arabic"] += len(ats)
            if missed is not None:
                missed.extend(at for at, _ in ats if mentions[at]["outcome"] in LINKED)
            continue
        for (at, whose), hit in zip(ats, found):
            if mentions[at]["outcome"] not in LINKED:
                continue
            counts["Arabic links to carry over"] += 1
            counts["relations carried over"] += 1
            person = mentions[at].get("person_id") or mentions[at].get("imam_id")
            links.append({"s": hit.start(), "e": hit.end(), "t": chain[hit.start():hit.end()],
                          "p": person, "g": slug_of.get(person, ""),
                          "a": mentions[at]["text"].strip()})
    # An Imam the skeletons could not place, named in English by something else.
    for imam, pattern in IMAM_AS.items():
        wanted = [at for at, mention in enumerate(mentions)
                  if mention["outcome"] in LINKED and at not in handled
                  and (mention.get("imam_id") or mention.get("person_id")) == imam]
        # Not beside a link to the same Imam: "Abu Ja'far Imam Muhammad ibn Ali Al-Baqir" is one
        # naming of him, and the part of it the skeleton placed is not a second mention.
        taken = [(link["s"] - 40, link["e"] + 40) for link in links if link["p"] == imam]
        free = [hit for hit in re.finditer(pattern, chain)
                if not any(s < hit.end() and hit.start() < e for s, e in taken)
                and bare_word(chain[:hit.start()].split()[-1] if chain[:hit.start()].split() else "")
                not in CONNECTOR]
        if not wanted or len(free) != len(wanted):
            continue
        for at, hit in zip(wanted, free):
            counts["Imam named otherwise in English"] += 1
            links.append({"s": hit.start(), "e": hit.end(), "t": chain[hit.start():hit.end()],
                          "p": imam, "g": slug_of.get(imam, ""),
                          "a": mentions[at]["text"].strip()})
            if missed is not None and at in missed:
                missed.remove(at)
    links.sort(key=lambda link: link["s"])
    return links


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--resolved", default=f'{TMP}/narrators_chains/resolved.jsonl')
    parser.add_argument("--served", default=f'{TMP}/narrators_chains/chains_served.jsonl')
    parser.add_argument("--index", default="rewayaat_hadith")
    parser.add_argument("--es-host", default="http://localhost:9200")
    parser.add_argument("--agree", type=float, default=AGREE)
    parser.add_argument("--live", action="store_true", help="write the field")
    parser.add_argument("--dry-run", action="store_true", help="count, write nothing")
    parser.add_argument("--show", type=int, default=0, help="print N aligned chains")
    parser.add_argument("--dump", help="write every link placed to this file, so that a "
                                       "change to the alignment can be diffed against a run "
                                       "made before it")
    args = parser.parse_args()

    from chain_links_load import slugs
    slug_of = slugs(args.es_host)

    english = {}
    with open(args.served) as handle:
        for line in handle:
            row = json.loads(line)
            if row.get("chain_en"):
                english[row["id"]] = row["chain_en"]

    counts, updates, shown = Counter(), [], 0
    with open(args.resolved) as handle:
        for line in handle:
            row = json.loads(line)
            chain = english.get(row["id"])
            if not chain:
                counts["no English chain"] += 1
                continue
            mentions = [m for m in row["mentions"] if m["kind"] != "text"]
            places = slots(chain)
            if not places or not mentions:
                counts["nothing to align"] += 1
                continue
            counts["chains aligned"] += 1
            links = link_chain(chain, places, mentions, args.agree, slug_of, counts)
            if links:
                updates.append({"_id": row["id"], "chain_links_en": links})
                counts["links placed"] += len(links)
                counts["narrations with at least one English link"] += 1
                if shown < args.show:
                    shown += 1
                    print(f"\n  {row['id']}")
                    for link in links:
                        print(f"      «{link['t']}» -> {link['p']} {link['g'][:30]}")
            else:
                # Written as an empty list, not left out. A narration whose links this run
                # refuses keeps the ones an earlier run wrote if nothing is sent for it, and
                # those are exactly the links a guard has since decided were wrong — 46 of
                # them the first time the count went down.
                updates.append({"_id": row["id"], "chain_links_en": []})
                counts["narrations with no English link"] += 1

    if args.dump:
        with open(args.dump, "w") as handle:
            for update in updates:
                for link in update["chain_links_en"]:
                    handle.write(json.dumps({"id": update["_id"], **link},
                                            ensure_ascii=False) + "\n")
        print(f"  wrote every link to {args.dump}")

    for label, count in counts.most_common():
        print(f"  {count:7,}  {label}")
    if args.dry_run or not args.live:
        return

    from elasticsearch import Elasticsearch, helpers
    es = Elasticsearch(args.es_host, request_timeout=120)
    loaded, failed = helpers.bulk(
        es, ({"_op_type": "update", "_index": args.index, "_id": u["_id"],
              "doc": {"chain_links_en": [{k: link[k] for k in SHIPPED}
                                         for link in u["chain_links_en"]]}} for u in updates),
        chunk_size=500, raise_on_error=False)
    es.indices.refresh(index=args.index)
    print(f"\nwrote chain_links_en on {loaded:,} narrations in {args.index}"
          + (f"; {len(failed)} failed" if failed else ""))


if __name__ == "__main__":
    main()
