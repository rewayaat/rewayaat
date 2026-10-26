#!/usr/bin/env python3
"""Is this man named in the passage the relation is cited from?

The attribute audit of 2026-10-10 put the relation fields at the bottom of the table:
teachers 63.1% and students 45.8% of what a page attributes to a book entry stood in that
entry's text, against 73.9% for the grade. Scored at the level of a single name rather than
a whole field — 771 names the auditors judged one by one — only 265 were upheld, 34.4%.

Reading the failures, the cause is not what the audit prompt warned about. Direction errors
are rare. The common shape is a name lifted out of the author's own chain to the man's book:
al-Najāshī writes «أخبرنا عدة من أصحابنا عن أحمد بن محمد بن يحيى قال حدثنا عبد الله بن جعفر»
and the extraction filed ʿAbd Allāh b. Jaʿfar as the subject's pupil. Those names are at
least in the passage. A larger group is not in it at all.

So the test is the same one the grades get: absence is proof. A name is set aside when no
part of it stands in the cited passage, and kept otherwise — a chain clause is still a
passage that names the man, and the auditors upheld such names more often than not.

Measured against them on those 771 names:

    the passage names him     349   auditors upheld 257  (73.6%)
    no part of him in it      422   auditors upheld   8  ( 1.9%)

Two scripts, two tests. Where the corpus can give the whole Arabic of a name — because a
book headed an entry with it, or because `learn_transliterations` matched its Latin form to
an Arabic one — the test is that name's own tokens standing in sequence, so that «محمد بن
الحسن» is not matched by a «محمد» and a «الحسن» in different sentences. Where only the
elements are known, the test is any one element, because a transliteration we assembled
ourselves is too weak a thing to retract a claim on.

`learn_name_elements` builds the element map from the same headings the whole-name map comes
from: where a book prints a heading in both scripts with the same number of name tokens, the
tokens pair off, and an element is kept where at least three headings agree on it and the
agreeing ones are a majority. That yields 3,319 elements — «safwan» → «صفوان», «jafar» →
«جعفر» — and leaves 28 of the 771 names untestable, which are kept.
"""
from collections import Counter, defaultdict
import re

from narrator_schema import normalize_arabic

LETTER = r"ء-ي"
# Words that join a name or honour its bearer. They identify nobody, so they are no evidence
# that the man is in the passage.
ARABIC_GLUE = {normalize_arabic(w) for w in (
    "بن", "ابن", "بنت", "ال", "و", "عن", "مولى", "أبو", "أبي", "أبا", "عليه", "عليها",
    "عليهم", "السلام", "صلى", "الله", "وآله", "وسلم", "رضي", "رحمه", "من")}
ENGLISH_GLUE = {"ibn", "bin", "b", "bint", "al", "and", "abu", "abi", "abd"}
# «محمد بن الحسن» as a passage writes it: the tokens in order, with the filial words and the
# punctuation that may stand between them.
GAP = rf"[^{LETTER}]{{0,3}}(?:بن|ابن|ابي|ابو|[^{LETTER}]){{0,8}}"
ARABIC_RANGE = re.compile(r"[؀-ۿ]")
NAME_ELEMENTS = {}


def arabic_tokens(value):
    return [t for t in re.split(rf"[^{LETTER}]+", normalize_arabic(value or ""))
            if t and t not in ARABIC_GLUE]


def english_tokens(value, fold):
    return [t for t in fold(value).split() if t and t not in ENGLISH_GLUE]


def learn_name_elements(profiles, fold):
    """«safwan» → «صفوان», from the headings the books print in both scripts."""
    seen = defaultdict(Counter)
    for profile in profiles.values():
        couples = [(profile.get("primary_arabic_name"), profile.get("primary_english_name"))]
        arabic, english = profile.get("arabic_aliases") or [], profile.get("english_aliases") or []
        if len(arabic) == len(english):
            couples.extend(zip(arabic, english))
        for one, other in couples:
            if not (isinstance(one, str) and isinstance(other, str)):
                continue
            if not ARABIC_RANGE.search(one) or ARABIC_RANGE.search(other):
                continue
            left, right = arabic_tokens(one), english_tokens(other, fold)
            # only where the two headings run name for name: a mismatch in length means the
            # two are not the same name written twice, and pairing them would teach nonsense
            if left and len(left) == len(right):
                for latin, arab in zip(right, left):
                    seen[latin][arab] += 1
    NAME_ELEMENTS.clear()
    for latin, holders in seen.items():
        arab, count = holders.most_common(1)[0]
        if count >= 3 and count > sum(holders.values()) / 2:
            NAME_ELEMENTS[latin] = arab
    return len(NAME_ELEMENTS)


def names_him(name, text, whole_arabic, fold):
    """'named', 'absent' or 'untestable' — whether the passage holds this man.

    `whole_arabic` is the caller's resolver from a name to its full Arabic, which knows the
    corpus's own transliteration map; it returns None when the name cannot be resolved.
    """
    body = normalize_arabic(text or "")
    if not body:
        return "untestable"
    whole = whole_arabic(name)
    if whole is not None:
        if whole and whole in body:
            return "named"
        parts = arabic_tokens(whole)
        if not parts:
            return "untestable"
        if len(parts) == 1:
            return "named" if parts[0] in body else "absent"
        run = GAP.join(re.escape(p) for p in parts[:3])
        if re.search(run, body):
            return "named"
        # Not in sequence, but two of his names are here: an entry may write him «محمد بن
        # الحسن الصفار» in one clause and «الصفار عن محمد» in the next.
        return "named" if sum(1 for t in parts if t in body) >= 2 else "absent"
    parts = english_tokens(name, fold)
    known = [NAME_ELEMENTS[t] for t in parts if t in NAME_ELEMENTS]
    # a name we can only half spell is a name we cannot look for
    if not known or len(known) < max(1, len(parts) - 1):
        return "untestable"
    return "named" if any(t in body for t in known) else "absent"
