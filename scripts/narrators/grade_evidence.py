#!/usr/bin/env python3
"""Can this verdict be read from this passage?

A page prints «al-Najāshī: thiqa — trustworthy» and cites an entry. The attribute audit of
2026-10-10 asked eight auditors whether the cited passage actually carries the grade, and for
positive verdicts on quotable entries the answer was no 63 times in 163. The commonest shape
is not a misreading but a stray: the entry grades nobody, or it grades a different man of the
same name, and the grade came from somewhere the page cannot show.

Most of that is catchable without judgement. A rijāl verdict is read from a word — «ثقة»,
«ضعيف», «كذاب» — or from one of a small set of formulas that stand in for one: al-Kashshī's
«أحد الأركان الأربعة», al-Najāshī's «أوثق الناس في حديثه», al-Ḍuʿafāʾ's «لا يلتفت إليه».
Measured against the auditors on the 163 word-testable verdicts in that sample:

    the grade's own word stands in the passage   105 tasks   auditors call  9 wrong  ( 8.6%)
    some other grade's word, not this one         22 tasks                18 wrong  (81.8%)
    no verdict word of any kind                   36 tasks                36 wrong  (100.0%)

So absence of the word is proof of absence of the verdict: every one of the 36 was an error.
Presence of the word is good but not conclusive — the 9 are the case where «ضعيف» in the
passage belongs to a neighbour, as in najashi:916, where al-Najāshī distinguishes four men
named Muḥammad b. Qays and weakens one of them. That residue needs reading, not a lexicon.

Two kinds of verdict are exempt because no word carries them:

  * `mukhtalaf_fih` is read from the sense — «اختل في آخر عمره», «غلا في آخر عمره»,
    «لم يكن بذاك». Six of the twelve the first lexicon wrongly failed were of this kind.
  * `majhul`, `muhmal` and `not_assessed` assert that the books do *not* grade the man, so a
    silent passage confirms them rather than failing them.

Usage:
    python3 scripts/narrators/grade_evidence.py            # measure the whole corpus
    python3 scripts/narrators/grade_evidence.py --show 40  # and print examples of each class
"""
import argparse
from collections import Counter, defaultdict
import json
import os
import random
import re
import sys

W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
sys.path.insert(0, f'{W}/scripts/narrators')
from narrator_schema import normalize_arabic  # noqa: E402

# The Arabic a verdict is read from. Each grade lists the words of its own root first and
# then the formulas the books use in place of a word, which the audit's own notes supplied.
GRADE_EVIDENCE_AR = {
    # Tawthiq words, and the formulas that carry a tawthiq: «صحيح الحديث» is al-Najashi's own,
    # «أحد الأركان الأربعة» al-Kashshi's, «شيخ العصابة» the highest of them.
    #
    # Madh and jalala are deliberately absent, and the auditors are the reason. Told only to
    # judge whether the passage grades the man, they drew the classical line without being
    # asked to: «فاضل، شاعر، أديب» is not tawthiq, «فقيه أصولي» is praise and no reliability
    # grade, «العالم العامل والورع الزاهد والفقيه المحدّث» grades nobody, and al-Najashi's
    # «وجه من وجوه العرب» is eminence among Arabs and not in hadith. Admitting that
    # vocabulary took the shown claims from 3.1% wrong to 12.0% and rescued one claim.
    "thiqa": ["ثقة", "ثقه", "ثقات", "ثقتان", "وثقه", "وثقوه", "وثق", "وثاق", "توثيق", "موثق",
              "أوثق", "صحيح الحديث", "صحيح الرواية", "صالح الحديث", "مسكون إلى روايته",
              "مأمون", "متقن", "ثبت", "لا يطعن عليه", "الأركان الأربعة", "شيخ العصابة",
              "مستقيم", "عين", "عينا", "عيون"],
    "saduq": ["صدوق", "صدق", "مصدق"],
    "hasan": ["حسن", "حسان", "ممدوح", "مدح"],
    "qawi": ["قوي", "قوة"],
    "daif": ["ضعيف", "ضعف", "ضعاف", "ضعفاء", "تضعيف", "مطعون", "لا يلتفت", "لا يعبأ",
             "متروك", "غير معتمد", "مرتفع القول", "ليس بشيء", "ليس بذاك", "فاسد الحديث",
             "منكر الحديث", "مناكير", "مضطرب", "لم يوثق", "في حديثه نظر",
             "يروي عن الضعفاء", "لا يعتمد", "لا يعول عليه"],
    # A passage that weakens the man carries the substance of «very weak» even where it does
    # not reach for the word, so this grade reads from al-ḍaʿīf's words as well as its own.
    "very_weak": ["ضعيف جدا", "ضعيف جد"],
    "kadhdhab": ["كذاب", "كذب", "وضاع", "يضع الحديث", "يكذب"],
    "non_existent": ["لا وجود له", "لا ذكر له"],
}
# Needles that must stand as a word of their own. «عين» — an eminent man — is also the tail
# of «أعين» (Zurāra b. Aʿyan), of «معين» and of «عين رسول الله»: all five of a sample resting
# on it alone were of that kind. The definite article and the conjunctions may still precede
# it. «حجة» was dropped outright: every sample was al-Kāfī's «كتاب الحجة» or the Mahdī's own
# title, and no boundary can tell those from the praise.
# A stem matched as a substring finds itself inside words that mean something else:
# «ضعف» stands in «الضعفاء», so «يروي عن الضعفاء» — a statement about whom he narrates from —
# was being read as a grade of him. These have to stand as words of their own.
WHOLE_WORD = {"عين", "ثبت", "قوي", "قوة", "صدق", "مدح", "كذب",
              "حسن", "حسان", "ضعف", "ثقة", "ثقه", "وثق", "ضعيف", "صالح الحديث"}
_LETTER = r"\u0621-\u064a"
_PREFIX = r"(?:و|ف|ب|ل|ك|ال|وال|فال|بال|لل)?"


def _needle(word):
    """A matcher for one needle: a substring, or a standalone word where that is required."""
    normed = normalize_arabic(word)
    if word not in WHOLE_WORD:
        return normed
    return re.compile(f"(?:^|[^{_LETTER}]){_PREFIX}{re.escape(normed)}(?:$|[^{_LETTER}])")


# Read from the sense of a passage, never from a word.
GRADE_EVIDENCE_AR["very_weak"] = (GRADE_EVIDENCE_AR["very_weak"]
                                  + GRADE_EVIDENCE_AR["daif"])
# «Disputed» was exempt from the word test and so was printed as a book's own position
# without one: 642 of the 2,584 positions a page states, a quarter of them, and the field
# audit of 2026-10-15 put stated grades at 73.7% with almost exactly that shortfall. The
# auditors found them resting on «مخلط», on a refutation written against the man, or on
# nothing — none of which is a book saying the Shīʿa differ over him. It is testable like
# every other grade now: the passage has to say there is a dispute.
GRADE_EVIDENCE_AR["mukhtalaf_fih"] = (
    "مختلف فيه", "مختلف فيها", "اختلف فيه", "اختلفوا فيه", "فيه خلاف", "فيه كلام",
    "اختلف اصحابنا", "اختلف أصحابنا", "اختلف الأصحاب", "اختلف الاصحاب",
    "وقع الخلاف", "محل خلاف", "موضع خلاف",
)
BY_SENSE = set()

# Words that were letting a grade through which the passage says of somebody else, or does
# not say at all. The audit of 2026-10-15 found «ثقات» carrying a `thiqa` for Ḥafṣ b. Sūqa
# when it is said of his maternal uncles, «يروي عن الضعفاء» carrying a `daif` when it says
# whom he narrates from, and «عين» — a notable — read as a grade of reliability. A plural or
# a dual is almost never about the one man an entry is about, and praise is not a verdict.
TOO_LOOSE = {
    "thiqa": {"ثقات", "ثقتان", "عيون", "عين", "عينا", "مستقيم"},
    "daif": {"يروي عن الضعفاء", "ضعفاء", "ضعاف"},
    "very_weak": {"يروي عن الضعفاء", "ضعفاء", "ضعاف"},
}
for _grade, _drop in TOO_LOOSE.items():
    GRADE_EVIDENCE_AR[_grade] = [w for w in GRADE_EVIDENCE_AR[_grade] if w not in _drop]
# Claims that the books say nothing: a silent passage is the evidence, not the absence of it.
NO_VERDICT = {"majhul", "muhmal", "not_assessed", "unknown", "assessed", "unknown (majhul)"}
ANY_EVIDENCE = sorted({n for words in GRADE_EVIDENCE_AR.values() for n in words},
                      key=len, reverse=True)
_NORM = {}


def _normed(words):
    key = id(words)
    if key not in _NORM:
        _NORM[key] = [_needle(w) for w in words]
    return _NORM[key]


def _stands_in(needle, body):
    return bool(needle.search(body)) if hasattr(needle, "search") else needle in body


def reads_as(grade, text):
    """'own', 'other' or 'none' — what verdict evidence the passage holds for this grade.

    'own' means a word or formula this grade can be read from stands in the passage; 'other'
    means some other grade's does but not this one; 'none' means the passage grades nobody.
    """
    if not text:
        return "none"
    body = normalize_arabic(text)
    for needle in _normed(GRADE_EVIDENCE_AR.get(grade, [])):
        if _stands_in(needle, body):
            return "own"
    for needle in _normed(ANY_EVIDENCE):
        if _stands_in(needle, body):
            return "other"
    return "none"


def testable(grade):
    """Whether a word test can speak to this verdict at all."""
    return grade not in NO_VERDICT and grade not in BY_SENSE and grade in GRADE_EVIDENCE_AR


def shown(grade, text):
    """Whether the page may present this verdict as the cited book's position."""
    return not testable(grade) or reads_as(grade, text) == "own"


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--show", type=int, default=0, help="print N examples of each class")
    args = parser.parse_args()

    sys.path.insert(0, f'{W}/scripts/narrators')
    import index_people as ip
    from build_people import is_entry, not_entries

    people = ip.load(os.path.join(ip.IDENTITY, "people.json"))
    source, headless = ip.profiles(), not_entries(ip.TMP)
    counts, by_book, examples = Counter(), defaultdict(Counter), defaultdict(list)
    for person in people:
        if person.get("holds_entry") is False:
            continue
        keys = list(person.get("source_keys") or ())
        person["pages_of"] = {k: (source[k].get("source_pages") or []) for k in keys
                              if k in source}
        entry_keys = [k for k in keys if k in source and is_entry(k, source[k], headless)]
        if not entry_keys:
            continue
        for grade in ip.values(person, "reliability_grades"):
            value = grade.get("grade")
            if value in ip.UNSTATED:
                continue
            key = ip.entry_key_for(person, grade.get("source_book"),
                                   grade.get("source_pages") or [], entry_keys)
            if not key:
                counts["no passage to test"] += 1
                continue
            counts["stated, with a passage"] += 1
            book = key.split(":")[0]
            if not testable(value):
                counts["read from the sense, exempt"] += 1
                by_book[book]["exempt"] += 1
                continue
            verdict = reads_as(value, ip.text_of(source[key]))
            counts[{"own": "the grade's own word stands in it",
                    "other": "another grade's word, not this one",
                    "none": "no verdict word at all"}[verdict]] += 1
            by_book[book][verdict] += 1
            if args.show:
                examples[verdict].append((key, value, person.get("person_id"),
                                          person.get("primary_arabic_name")))
    for label, n in counts.most_common():
        print(f"  {n:7,}  {label}")
    print("\nby book, of the verdicts a word test can speak to:")
    print(f"  {'book':12s} {'own':>7s} {'other':>7s} {'none':>7s} {'exempt':>7s}   own share")
    for book in sorted(by_book, key=lambda b: -sum(by_book[b].values())):
        row = by_book[book]
        tested = row["own"] + row["other"] + row["none"]
        share = f"{row['own'] / tested:6.1%}" if tested else "      —"
        print(f"  {book:12s} {row['own']:7,} {row['other']:7,} {row['none']:7,} "
              f"{row['exempt']:7,}   {share}")
    if args.show:
        random.seed(11)
        for verdict in ("none", "other"):
            pool = examples[verdict]
            print(f"\n{min(args.show, len(pool))} of {len(pool):,} «{verdict}», to read:")
            for key, value, pid, name in random.sample(pool, min(args.show, len(pool))):
                print(f"  {key:16s} {value:14s} {pid}  «{name}»")


if __name__ == "__main__":
    main()
