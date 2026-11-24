#!/usr/bin/env python3
"""Find the Qur'anic verses a narration quotes, and where in its text it quotes them.

Two detectors, both deterministic:

* ``arabic_quote`` — a run of at least MIN_RUN words of the narration's Arabic that
  matches a verse word for word. The hadith books write the Qur'an in ordinary
  (imlāʾī) spelling and the verse index is Uthmani, so both sides are reduced to a
  consonant skeleton first: diacritics, long vowels and hamza seats are dropped,
  ة becomes ه. ٱلصَّلَوٰة and الصَّلاة both become صله.
* ``reference`` — an explicit citation in the English, such as "(2:196)",
  "(Surah An-Nisa 4:93)" or "[Qur'an 21:69]". Bare "N:M" counts only inside
  brackets or after Surah/Qur'an/verse, so clock times and hadith numbers do not.

Every hit carries the exact substring it matched and its offsets in the field it came
from, so a page can make that text a link to the verse.

    python3 scripts/quranic-insights/quoted_verses.py \
        --hadith tmp/qlight-v2/sources/hadith.jsonl \
        --quran tmp/qlight-v2/sources/quran.jsonl \
        --out tmp/qlight-v2/quoted_verses.jsonl
"""

import argparse
import json
import re
from collections import defaultdict

MIN_RUN = 4            # words in a row that must match a verse
COMMON_MIN_RUN = 6     # a run made only of stock phrases must be this long
STOCK_LIMIT = 8        # an n-gram found at more than this many verse positions is a stock phrase

DIACRITICS = re.compile(r"[ؐ-ًؚ-ٟۖ-ۭ࣓-ࣿـ]")
DROP = set("اأإآٱءؤئوىيٰ")
ARABIC_WORD = re.compile(r"[ء-ي٠-٩ٮ-ۓۺ-ۿٰٱً-ٟۖ-ۭـ]+")

# Bismillah opens 113 surahs and is said before almost anything; a narration that
# contains it is not quoting 1:1 or 27:30.
BASMALA = ["بسم", "لله", "لرحمن", "لرحم"]


def skeleton(word):
    w = DIACRITICS.sub("", word).replace("ٰ", "")
    w = w.replace("ة", "ه")
    return "".join(ch for ch in w if ch not in DROP)


def light(word):
    """A stricter form that keeps و and ي, so قال and قيل, or إن and إني, stay apart.
    Short runs must match at this level too; the skeleton alone collides too often."""
    w = DIACRITICS.sub("", word).replace("\u0670", "")
    w = w.replace("ة", "ه").replace("ى", "ي").replace("ؤ", "و").replace("ئ", "ي")
    return "".join(ch for ch in w if ch not in "اأإآٱء")


def tokens(text):
    """(skeleton, start, end, light) for each Arabic word, skipping words that reduce to
    nothing. A conjunction written apart, «وَ مَنْ», reduces to nothing and drops out."""
    out = []
    for m in ARABIC_WORD.finditer(text or ""):
        sk = skeleton(m.group())
        if sk:
            out.append((sk, m.start(), m.end(), light(m.group())))
    return out


# Said in prayer and speech far more often than quoted: a short run that contains one
# of these is not counted as quoting the verse it happens to match.
FORMULAE = [("ل", "له", "ل"), ("ن", "ش", "لله"), ("تكل", "عل", "لله"),
            ("نك", "نت", "علم", "لغب"), ("ل", "نشرك", "به", "ش")]
INNER_PUNCT = re.compile(r"[:：.,،؛;«»\"“”()\[\]]")
SHORT_MIN_CHARS = 11   # skeleton letters a short run must carry


class VerseIndex:
    def __init__(self, verses):
        self.words = {}                       # verse_key -> [skeleton]
        self.light = {}                       # verse_key -> [light form]
        self.grams = defaultdict(list)        # 4-gram -> [(verse_key, position)]
        for v in verses:
            toks = tokens(v["text_arabic"])
            sk = [t[0] for t in toks]
            self.words[v["_id"]] = sk
            self.light[v["_id"]] = [t[3] for t in toks]
            for i in range(len(sk) - MIN_RUN + 1):
                self.grams[tuple(sk[i:i + MIN_RUN])].append((v["_id"], i))

    def stock(self, gram):
        return len(self.grams.get(gram, ())) > STOCK_LIMIT


def short_run_ok(run, text, verse_light, all_stock):
    if all_stock:
        return False
    if [t[3] for t in run] != verse_light:
        return False
    if INNER_PUNCT.search(text[run[0][1]:run[-1][2]]):
        return False
    if sum(len(t[0]) for t in run) < SHORT_MIN_CHARS:
        return False
    sk = [t[0] for t in run]
    for f in FORMULAE:
        for j in range(len(sk) - len(f) + 1):
            if tuple(sk[j:j + len(f)]) == f:
                return False
    return True


def find_arabic_quotes(text, index):
    toks = tokens(text)
    sk = [t[0] for t in toks]
    hits = []
    i = 0
    while i <= len(sk) - MIN_RUN:
        gram = tuple(sk[i:i + MIN_RUN])
        starts = index.grams.get(gram)
        if not starts or gram == tuple(BASMALA):
            i += 1
            continue
        # Extend each alignment as far as it goes; keep the longest.
        best = None
        for vk, pos in starts:
            vw = index.words[vk]
            n = MIN_RUN
            while i + n < len(sk) and pos + n < len(vw) and sk[i + n] == vw[pos + n]:
                n += 1
            if best is None or n > best[2]:
                best = (vk, pos, n)
        vk, pos, n = best
        all_stock = all(index.stock(tuple(sk[j:j + MIN_RUN])) for j in range(i, i + n - MIN_RUN + 1))
        if n < COMMON_MIN_RUN and not short_run_ok(toks[i:i + n], text, index.light[vk][pos:pos + n], all_stock):
            i += 1
            continue
        # Same run can be several verses at once (a phrase repeated in the Qur'an).
        same = sorted({k for k, p in starts
                       if index.words[k][p:p + n] == sk[i:i + n]})
        start, end = toks[i][1], toks[i + n - 1][2]
        hits.append({
            "verse_key": vk,
            "also_matches": [k for k in same if k != vk][:10],
            "source": "arabic_quote",
            "field": "arabic",
            "start": start, "end": end,
            "text": text[start:end],
            "words": n,
            "verse_words": len(index.words[vk]),
            "verse_offset": pos,
        })
        i += n
    return hits


SURAH_WORD = r"(?:S[uū]ra[ht]?|Sura|Qur['’]?[aā]n|Quran|Koran|verse|verses|[AĀ]ya[th]?|Q\.?)"
REF = re.compile(
    r"(?:(?P<pre>" + SURAH_WORD + r")[^()\[\]\d\n]{0,40}?|(?P<open>[(\[]))\s*"
    r"(?P<s>\d{1,3})\s*[:：]\s*(?P<a>\d{1,3})(?:\s*[-–]\s*(?P<a2>\d{1,3}))?",
    re.IGNORECASE)


def find_references(text, ayah_counts):
    hits = []
    for m in REF.finditer(text or ""):
        s, a = int(m.group("s")), int(m.group("a"))
        a2 = int(m.group("a2")) if m.group("a2") else a
        if not (1 <= s <= 114) or not (1 <= a <= ayah_counts.get(s, 0)) or a2 < a:
            continue
        a2 = min(a2, ayah_counts[s], a + 5)
        # The quoted words usually sit just before the citation, in quotation marks.
        quote = None
        before = text[:m.start()].rstrip(" ,.(")
        q = re.search(r"[\"“‘']([^\"“”‘’']{8,600})[\"”’']\s*$", before)
        if q:
            quote = {"start": q.start(1), "end": q.end(1), "text": q.group(1)}
        for ayah in range(a, a2 + 1):
            hits.append({
                "verse_key": f"{s}:{ayah}",
                "source": "reference",
                "field": "english",
                "start": m.start(), "end": m.end(),
                "text": text[m.start():m.end()],
                "quoted_english": quote,
            })
    return hits


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--hadith", required=True)
    ap.add_argument("--quran", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    verses = [json.loads(l) for l in open(args.quran)]
    ayah_counts = defaultdict(int)
    for v in verses:
        ayah_counts[v["surah_number"]] = max(ayah_counts[v["surah_number"]], v["ayah_number"])
    index = VerseIndex(verses)

    n_docs = n_with = 0
    with open(args.out, "w") as out:
        for line in open(args.hadith):
            d = json.loads(line)
            n_docs += 1
            hits = find_arabic_quotes(d.get("arabic") or "", index)
            hits += find_references(d.get("english") or "", ayah_counts)
            if hits:
                n_with += 1
                out.write(json.dumps({"hadith_id": d["_id"], "book": d.get("book"),
                                      "quotes": hits}, ensure_ascii=False) + "\n")
    print(f"{n_with} of {n_docs} narrations quote or cite at least one verse")


if __name__ == "__main__":
    main()
