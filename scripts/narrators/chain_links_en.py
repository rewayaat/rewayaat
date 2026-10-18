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

Usage:
    python3 scripts/narrators/chain_links_en.py --dry-run
    python3 scripts/narrators/chain_links_en.py --live --index rewayaat_hadith
"""
import argparse
from collections import Counter
import difflib
import json
import os
import re
import unicodedata

W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
TMP = f'{W}/tmp'
LINKED = ("one", "chosen", "convention")
AGREE = 0.8      # the skeletons must look this alike before a name is linked
GAP = -0.3       # what it costs to leave a name unmatched; a poor pair still beats two gaps

# How a chain is joined together in English. Longest phrases first, so "has narrated from" is
# not cut at "from". What falls between two of these is a name, a kinship or a collective.
JOIN = re.compile(r"""
    ^\s*\d+\s*[\.\-]\s*
  | \b(?:has|have|had)\s+narrated\s+(?:to\s+\w+\s+)?from\b
  | \bnarrated\s+to\s+(?:me|us|him)\b
  | \bwho\s+has\s+(?:narrated|said|reported)\s+the\s+following\b
  | \bhas\s+said\s+the\s+following\b
  | \bwho\s+has\s+said\b
  | \bin\s+a\s+marfu'\s+manner\b
  | \bfrom\b | \bsaid\b | \band\b | \bthat\b
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
    'ر': 'r', 'ز': 'z', 'س': 's', 'ش': 's', 'ص': 's', 'ض': 'd', 'ط': 't', 'ظ': 'z',
    'غ': 'g', 'ف': 'f', 'ق': 'q', 'ك': 'k', 'ل': 'l', 'م': 'm', 'ن': 'n', 'ه': 'h',
    'ة': 'h', 'ک': 'k',
}
# The letters a transliteration writes with two: they must fold before the vowels are dropped.
ENGLISH_PAIR = (('kh', 'x'), ('th', 't'), ('dh', 'd'), ('sh', 's'), ('gh', 'g'), ('ph', 'f'))
EDGE = " ,.;:()[]{}“”\"'’‘"


# «ابن» and «بن» are one word in Arabic practice and «ibn», «bin» and «b.» one in English,
# and none of them is part of a name. Folded to one token first, so that the vowel a name
# begins with can be marked without «ibn» disagreeing with «بن» in every chain on the site.
CONNECTOR = {"ibn", "bin", "ben", "b", "bn", "ابن", "بن", "أبن"}
VOWEL = "aeiou"
OPENER = "اأإآءئؤع"


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


def skeleton_en(text):
    """The same for a transliteration, so the two can be compared at all."""
    text = text.lower()
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
    for mark in "-.,":
        text = text.replace(mark, ' ')
    words, article = [], ""
    for word in text.split():
        bare = article + word.strip("'")
        article = ""
        # «al-Hassan» is two words to a hyphen and one to Arabic, so the article is carried
        # onto the name it belongs to rather than dropped: «الحسن» is 'lhsn either way.
        if bare in ("al", "el"):
            article = bare
            continue
        if bare in CONNECTOR:
            words.append("bn")
            continue
        for pair, one in ENGLISH_PAIR:
            bare = bare.replace(pair, one)
        opener = "'" if bare and bare[0] in VOWEL else ""
        body = ''.join(c for c in bare if c.isalpha() and c not in 'aeiouwy')
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
               alike(" ".join(one.split()[:words]), " ".join(other.split()[:words])))
    if best < agree:
        return False
    head_en = heading_word(comparable(english))
    head_ar = heading_word(comparable(arabic))
    # «السندي بن محمد» is printed "Sindi bin Muhammed" as often as "al-Sindi", so the head is
    # tried both with its article and without: the alif the article opens with is not the
    # alif the reversal guard is looking for.
    # On a short name the head is half the evidence, and «حماد بن عثمان» against "Hammad
    # ibn Isa" shares it: two men the chains keep apart. Where there is nothing after the
    # patronymic to tell them apart, the patronymic itself has to agree.
    mine, theirs = one.split(), other.split()
    if mine and theirs and min(len(mine), len(theirs)) <= 3:
        # Over the shorter name's length, so that a trailing nisba the translation adds —
        # "Abdallah ibn Maymun al-Qaddah" for «عبد الله بن ميمون» — is not read as the
        # patronymic disagreeing.
        at = min(len(mine), len(theirs)) - 1
        if alike(mine[at], theirs[at]) < 0.7:
            return False
    heads = {(head_en, head_ar), (without_article(head_en), without_article(head_ar))}
    for one, other in heads:
        if one.startswith("'") != other.startswith("'"):
            continue
        if alike(one, other) >= 0.8:
            return True
    return False


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
    return kept


def words_of(text, at):
    """Every word of a slot with the place it stands in the chain."""
    found = []
    for match in re.finditer(r"[^\s]+", text):
        word = match.group(0).strip(EDGE)
        if word:
            found.append((word, at + match.start(), at + match.start() + len(match.group(0))))
    return found


def windows_of(slot_words, longest):
    """Every run of words in a slot, with its skeleton, computed once.

    A chain asks the same slot about every name in it, and the skeleton of a run of words is
    the same answer each time, so computing it per pair made the pass several times longer
    than it needed to be.
    """
    made = []
    for start in range(len(slot_words)):
        piece = []
        for end in range(start, min(start + longest, len(slot_words))):
            piece.append(slot_words[end][0])
            skeleton = skeleton_en(" ".join(piece))
            if skeleton:
                made.append((len(piece), skeleton, slot_words[start][1], slot_words[end][2]))
    return made


def best_window(windows, arabic):
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
    best = (0.0, None, None, "")
    # One matcher with the Arabic side fixed, and difflib's two cheap bounds before the real
    # comparison: a chain asks this question tens of thousands of times and all but a few of
    # the answers are «not even close».
    matcher = difflib.SequenceMatcher(None)
    matcher.set_seq2(arabic)
    for count, skeleton, start, end in windows:
        if count < shortest or count > longest:
            continue
        matcher.set_seq1(skeleton)
        if matcher.real_quick_ratio() <= best[0] or matcher.quick_ratio() <= best[0]:
            continue
        score = matcher.ratio()
        if score > best[0]:
            best = (score, start, end, skeleton)
    return best


def align(english, arabic):
    """Pair two lists of skeletons up in order. Kept for the tests and for --show."""
    scores = {(i, j): (alike(english[i], arabic[j]), 0, 0, english[i])
              for i in range(len(english)) for j in range(len(arabic))}
    return align_on(scores, len(english), len(arabic))


def align_on(scores, rows, columns):
    """Pair the two sequences up in order, leaving a gap where one side has no partner.

    Needleman-Wunsch on the scores. Order is what makes this safe: a chain is a sequence
    and the translation keeps it, so a name can only ever be matched to one that stands in the
    same place in the same order. A dropped name opens a gap instead of shifting the rest.
    """
    best = [[0.0] * (columns + 1) for _ in range(rows + 1)]
    for i in range(1, rows + 1):
        best[i][0] = best[i - 1][0] + GAP
    for j in range(1, columns + 1):
        best[0][j] = best[0][j - 1] + GAP
    for i in range(1, rows + 1):
        for j in range(1, columns + 1):
            pair = best[i - 1][j - 1] + scores[(i - 1, j - 1)][0] - 0.5
            best[i][j] = max(pair, best[i - 1][j] + GAP, best[i][j - 1] + GAP)
    pairs, i, j = [], rows, columns
    while i and j:
        pair = best[i - 1][j - 1] + scores[(i - 1, j - 1)][0] - 0.5
        if best[i][j] == pair:
            pairs.append((i - 1, j - 1))
            i, j = i - 1, j - 1
        elif best[i][j] == best[i - 1][j] + GAP:
            i -= 1
        else:
            j -= 1
    return list(reversed(pairs))


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
            arabic = [skeleton_ar(m["text"]) for m in mentions]
            slot_words = [words_of(chain[s:e], s) for s, e in places]
            widest = max((len(name.split()) for name in arabic), default=1) + 3
            slot_windows = [windows_of(words, widest) for words in slot_words]
            # One search per pair, kept, because the alignment asks for the same score it
            # then reads back: a slot's worth to a name is the best name-sized run inside it.
            windows = {}
            for slot_at, slot in enumerate(slot_windows):
                for mention_at, name in enumerate(arabic):
                    windows[(slot_at, mention_at)] = best_window(slot, name)
            pairs = align_on(windows, len(slot_words), len(arabic))
            links = []
            for slot, mention in pairs:
                row_mention = mentions[mention]
                score, start, end, skeleton = windows[(slot, mention)]
                if row_mention["outcome"] not in LINKED:
                    continue
                counts["Arabic links to carry over"] += 1
                if start is None or not same_man(skeleton, arabic[mention], args.agree):
                    counts["the two names are not the same name"] += 1
                    continue
                person = row_mention.get("person_id") or row_mention.get("imam_id")
                links.append({"s": start, "e": end, "t": chain[start:end], "p": person,
                              "g": slug_of.get(person, "")})
            for mention in mentions:
                if mention["outcome"] in LINKED and not any(
                        p[1] == mentions.index(mention) for p in pairs):
                    counts["Arabic link with no English name"] += 1
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
              "doc": {"chain_links_en": u["chain_links_en"]}} for u in updates),
        chunk_size=500, raise_on_error=False)
    es.indices.refresh(index=args.index)
    print(f"\nwrote chain_links_en on {loaded:,} narrations in {args.index}"
          + (f"; {len(failed)} failed" if failed else ""))


if __name__ == "__main__":
    main()
