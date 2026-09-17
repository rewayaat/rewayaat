#!/usr/bin/env python3
"""Canonical narrator profile schema: normalization, vocabulary, Infallible detection.

Shared by the extraction normalizer and the merge. Everything here is deterministic —
no model output is trusted as a key, a grade, or a matching key.
"""

import re
import unicodedata

# --- Name normalization (ports NarratorNameMatcher, deleted in 9b6adb6) ---

_AR_DIACRITICS = re.compile(r"[ؐ-ًؚ-ٰٟۖ-ۭ]")
_AR_NON_ARABIC = re.compile(r"[^؀-ۿ\s]+")
_WS = re.compile(r"\s+")
_EN_MARKS = re.compile(r"\p{M}+".replace(r"\p{M}", r"[̀-ͯٓ-ٕ]"))
_EN_NON_ALNUM = re.compile(r"[^a-z0-9\s]+")

_AR_FOLD = str.maketrans({
    "أ": "ا", "إ": "ا", "آ": "ا", "ٱ": "ا",
    "ى": "ي", "ة": "ه", "ؤ": "و", "ئ": "ي",
    "ـ": " ",
})


# Piety formulae that trail a name and identify nobody.
_AR_NAME_HONORIFICS = (
    "عليه السلام", "عليهم السلام", "عليها السلام", "عليهما السلام",
    "صلى الله عليه واله وسلم", "صلى الله عليه واله", "صلى الله عليه وسلم",
    "رحمه الله", "رضي الله عنه", "رضي الله عنها", "قدس سره",
)
_AR_HONORIFIC_TOKENS = {"ره", "رح", "ع", "ص", "عج", "قده", "رضي", "رحمه"}


def normalize_arabic(raw):
    """Fold an Arabic name to its matching key. Deterministic; never extracted."""
    if not raw or not raw.strip():
        return ""
    s = _AR_DIACRITICS.sub("", raw).translate(_AR_FOLD)
    s = _AR_NON_ARABIC.sub(" ", s)
    s = _WS.sub(" ", s).strip()
    for h in _AR_NAME_HONORIFICS:
        s = s.replace(normalize_arabic_raw_fold(h), " ")
    parts = [t for t in s.split(" ") if t and t not in _AR_HONORIFIC_TOKENS]
    return " ".join(parts).strip()


def normalize_arabic_raw_fold(raw):
    """Fold without honorific stripping — used to build the honorific patterns."""
    s = _AR_DIACRITICS.sub("", raw).translate(_AR_FOLD)
    return _WS.sub(" ", _AR_NON_ARABIC.sub(" ", s)).strip()


def normalize_english(raw):
    """Fold a transliterated name to its matching key. Deterministic; never extracted."""
    if not raw or not raw.strip():
        return ""
    s = unicodedata.normalize("NFKD", raw)
    s = "".join(c for c in s if not unicodedata.combining(c))
    for ch in ("ʿ", "ʾ", "ʻ", "'", "’", "`"):
        s = s.replace(ch, "")
    s = _EN_NON_ALNUM.sub(" ", s.lower())
    return _WS.sub(" ", s).strip()


# Connectors and honorific particles that carry no identifying weight.
_AR_STOP_TOKENS = {
    "بن", "ابن", "بنت", "ابو", "ابي", "ابا", "ام", "عبد", "مولى", "اخو", "اخي",
    "ال", "الشيخ", "السيد", "القاضي", "الامير", "الحاج",
}


def name_tokens(normalized_ar):
    """Identifying tokens of a normalized Arabic name.

    Used for the merge's name-depth test: a match on a short form is never on its own
    sufficient to merge two profiles.
    """
    if not normalized_ar:
        return []
    return [t for t in normalized_ar.split(" ") if t and t not in _AR_STOP_TOKENS]


# Arabic editorial shorthand for "the person under discussion". Mamaqani uses these
# constantly and the extractor recorded them as aliases; they are not names in any sense
# and 280 profiles carry one.
EDITORIAL_PLACEHOLDERS = {
    "المترجم", "المترجم له", "المعنون", "المعنون له", "صاحب الترجمه",
    "الرجل", "المذكور", "المزبور", "نفسه", "هو", "المشار اليه", "الراوي",
}


# The English counterparts of _AR_STOP_TOKENS, across the transliteration schemes the
# sources use.
_EN_STOP_TOKENS = {
    "ibn", "bin", "b", "bint", "abu", "abi", "aba", "umm", "um", "al", "abd",
    "abdul", "mawla", "ben", "the", "of", "sheikh", "shaykh", "sayyid",
}


def english_name_tokens(normalized_en):
    """Identifying tokens of a normalized English name."""
    if not normalized_en:
        return []
    return [t for t in normalized_en.split(" ") if t and t not in _EN_STOP_TOKENS]


def is_identifying_english_alias(name):
    """English counterpart of is_identifying_alias.

    A word count is not enough: "abu muhammad" is two words and no more identifying than
    أبو محمد, which is why it kept generating candidates after the Arabic side was fixed.
    """
    normalized = normalize_english(name or "")
    if not normalized:
        return False
    tokens = normalized.split(" ")
    if tokens[0] in ("aba", "abi"):
        normalized = " ".join(["abu"] + tokens[1:])
    if _strips_to_generic_kunyah(normalized, _EN_GENERIC_KUNYAHS, _EN_STOP_TOKENS):
        return False
    return len(english_name_tokens(normalized)) >= 2


# Kunyahs shared by hundreds of narrators — the Imams' own, and the handful of everyday
# ones. "أبو ذر" and "أبو غالب" are not here, because those name one person each.
GENERIC_KUNYAHS = {
    "ابو عبد الله", "ابو جعفر", "ابو الحسن", "ابو محمد", "ابو علي",
    "ابو القاسم", "ابو الحسين", "ابراهيم", "ابو ابراهيم", "ابو بكر",
    "ابو احمد", "ابو الفضل", "ابو العباس", "ابو يوسف", "ابو الطيب",
}
_EN_GENERIC_KUNYAHS = {
    "abu abd allah", "abu abdallah", "abu abdullah", "abu jafar", "abu al hasan",
    "abu muhammad", "abu ali", "abu al qasim", "abu al husayn", "abu ibrahim",
    "abu bakr", "abu ahmad", "abu al fadl", "abu al abbas", "abu yusuf",
}


def fold_kunyah_case(normalized):
    """Fold an accusative or genitive kunyah (أبا، أبي) to the nominative أبو.

    Sources inflect kunyahs by grammatical case — «يكنى أبا جعفر», «عن أبي جعفر» — so one
    kunyah arrives in three spellings, and compared unfolded, two sources naming the same
    kunyah looked like a conflict. أبي followed by بن is not a kunyah but the name Ubayy
    (أبي بن كعب), and is left alone.
    """
    tokens = normalized.split(" ")
    if tokens and tokens[0] in ("ابا", "ابي") and not (len(tokens) > 1 and tokens[1] == "بن"):
        tokens[0] = "ابو"
    return " ".join(tokens)


_KUNYAH_JUNK = {"", "ا", "ابو", "ابي", "ابا", "ام"}


def fold_kunyah(raw):
    """A kunyah's comparison key — normalized and case-folded — or None for truncated junk."""
    folded = fold_kunyah_case(normalize_arabic(raw or ""))
    return None if folded in _KUNYAH_JUNK or len(folded) <= 2 else folded


def ancestor_tails(normalized, separators):
    """Every part of a normalized name that follows a separator: the ancestors' names.

    `عبد الله بن احمد بن عامر` yields `احمد بن عامر` and `عامر` — his father and grandfather.
    """
    tails = set()
    for separator in separators:
        start = normalized.find(separator)
        while start != -1:
            tail = normalized[start + len(separator):].strip()
            if tail:
                tails.add(tail)
            start = normalized.find(separator, start + 1)
    return tails


SEPARATORS_AR = (" بن ", " ابن ", " بنت ")
SEPARATORS_EN = (" ibn ", " b ", " bin ", " bint ")


def _opening(normalized, separators):
    """Everything before a name's first بن — titles, kunyah and the man's own first name."""
    cut = len(normalized)
    for separator in separators:
        at = normalized.find(separator)
        if at != -1:
            cut = min(cut, at)
    return normalized[:cut].strip()


def _bare(token):
    """A first name without the definite article, so حسين and الحسين compare equal."""
    return token[2:] if token.startswith("ال") and len(token) > 3 else token


def lineage_relation(subject, alias, separators=SEPARATORS_AR, tokens_of=None):
    """Whether `alias` names the subject's ancestor or descendant — or neither (None).

    An entry opens with its subject's lineage and names his sons and transmitters, so an
    extractor's alias list mixes the man's own names with his relatives'. Indexed as aliases,
    a relative's name merges the relative into him: 1405 fused a father and son because the
    father's Najashi entry, which names the son who transmitted his book, listed the son as
    an alias. Both arguments are normalized names.

      ancestor    the alias is the start of what follows a بن in the subject's name —
                  «أحمد بن عامر» on «عبد الله بن أحمد بن عامر»
      descendant  «X بن» followed by the start of the subject's own name, two identifying
                  names deep — «عبد الله بن أحمد بن عامر» on «أحمد بن عامر بن سليمان»

    None whenever the alias's own first name is among the names that open the subject's,
    ignoring the article: a man who shares his grandfather's name, or a heading that begins
    with titles, would otherwise have his own name read as a relative's.

    Siblings are deliberately not detected. A different first name over the same lineage is
    far more often a variant reading of the man's own name — الحسن and الحسين, سليمان and
    سلمان — than a brother, and treating it as a brother would discard his own names.
    """
    tokens_of = tokens_of or name_tokens
    if not subject or not alias or alias == subject:
        return None
    has_separator = any(sep in alias for sep in separators)
    if has_separator:
        opening = _opening(alias, separators).split()
        alias_first = opening[-1] if opening else ""
    else:
        alias_first = alias.split()[0]
    if _bare(alias_first) in {_bare(t) for t in _opening(subject, separators).split()}:
        return None
    for tail in ancestor_tails(subject, separators):
        if tail == alias or tail.startswith(alias + " "):
            return "ancestor"
    if not has_separator:
        return None
    own = subject.split()
    for tail in ancestor_tails(alias, separators):
        shared = []
        for a, b in zip(tail.split(), own):
            if a != b:
                break
            shared.append(a)
        if len(tokens_of(" ".join(shared))) >= 2:
            return "descendant"
    return None


def _strips_to_generic_kunyah(normalized, generic, stop_tokens):
    """True when a name is a generic kunyah plus too little else.

    `أبو الحسن القزويني` is "Abu al-Hasan the Qazwini" — a kunyah hundreds of men share
    plus one nisbah. As a merge key it fused حنظلة بن زكريا with علي بن محمد بن عبد الله.
    `أبو ذر الغفاري` has the same shape but names one man, because its kunyah is his alone.
    The discriminator is whether the kunyah itself is generic, not the shape of the phrase.
    """
    for kunyah in generic:
        if normalized == kunyah:
            return True
        if normalized.startswith(kunyah + " "):
            rest = normalized[len(kunyah) + 1:]
            tokens = [t for t in rest.split(" ") if t and t not in stop_tokens]
            return len(tokens) < 2
    return False


def is_identifying_alias(name):
    """True when an alias may generate merge candidates.

    Aliases legitimately carry kunyahs (أبو العباس) and bare nisbahs (الكوفي) alongside
    real name variants. Those are disambiguators, not identifiers — the same rule that
    keeps `titles` and `kunyah_arabic` out of the name index has to apply to alias strings
    of the same shape, or the exclusion is laundered through the alias list. `الكوفي`
    alone once linked nine unrelated narrators into one profile.

    Two identifying tokens is the bar; name_tokens already discards بن/ابن/أبو/أم and the
    other connectors, so a bare kunyah or single nisbah scores zero or one. Such aliases
    are still stored and displayed — they just cannot be the reason two profiles merge.
    """
    normalized = normalize_arabic(name or "")
    if not normalized or normalized in EDITORIAL_PLACEHOLDERS:
        return False
    if _strips_to_generic_kunyah(fold_kunyah_case(normalized), GENERIC_KUNYAHS, _AR_STOP_TOKENS):
        return False
    return len(name_tokens(normalized)) >= 2


# --- Reliability vocabulary ---
#
# Two axes, deliberately separate. Reliability is a verdict on a narrator's transmission;
# a sect flag is a doctrinal charge. Sources routinely state one without the other, and
# collapsing them loses the distinction between "weak" and "reliable but Waqifi".

RELIABILITY_GRADES = (
    "thiqa",          # ثقة — reliable
    "saduq",          # صدوق — truthful
    "hasan",          # حسن — good
    "qawi",           # قوي — strong
    "mukhtalaf_fih",  # مختلف فيه — sources disagree
    "majhul",         # مجهول — the source has an entry and states the person is unknown
    "muhmal",         # مهمل — named without comment
    "daif",           # ضعيف — weak
    "very_weak",      # explicit intensifier
    "kadhdhab",       # كذاب — liar/fabricator
    "not_assessed",   # the source mentions the person but issues no verdict
    "non_existent",   # the source denies the person exists
)

SECT_FLAGS = (
    "ghali",              # غالي — extremist
    "waqifi",             # واقفي
    "fathi",              # فطحي
    "zaydi",              # زيدي
    "nasibi",             # ناصبي
    "batri",              # بتري
    "mulhid",             # ملحد
    "fasid_al_madhhab",   # فاسد المذهب — "corrupt"/"deviated" in the raw output
)

# Longest-first: "reliable (thiqa)" must not match on the bare "reliable" fragment first.
_GRADE_LEXICON = [
    ("very weak", "very_weak"), ("very reliable", "thiqa"),
    ("liar/fabricator", "kadhdhab"), ("liar", "kadhdhab"), ("fabricator", "kadhdhab"),
    ("reliable (thiqa)", "thiqa"), ("thiqa", "thiqa"), ("reliable", "thiqa"),
    ("truthful (saduq)", "saduq"), ("saduq", "saduq"), ("truthful", "saduq"), ("honest", "saduq"),
    ("good (hasan)", "hasan"), ("hasan", "hasan"), ("good", "hasan"),
    ("strong (qawi)", "qawi"), ("qawi", "qawi"), ("strong", "qawi"),
    ("unknown (majhul)", "majhul"), ("majhul", "majhul"),
    ("unknown (mahmal)", "muhmal"), ("mahmal", "muhmal"), ("muhmal", "muhmal"),
    ("disputed (mukhtalaf fihi)", "mukhtalaf_fih"), ("mukhtalaf fihi", "mukhtalaf_fih"),
    ("mixed/unclear", "mukhtalaf_fih"), ("disputed", "mukhtalaf_fih"),
    ("contested", "mukhtalaf_fih"), ("unclear", "mukhtalaf_fih"),
    ("weak (da'if)", "daif"), ("da'if", "daif"), ("daif", "daif"), ("weak", "daif"),
    ("non-existent", "non_existent"), ("nonexistent", "non_existent"),
    ("not_assessed", "not_assessed"), ("not assessed", "not_assessed"),
    ("assessed", "not_assessed"), ("disregarded", "not_assessed"),
    ("n/a", "not_assessed"), ("unknown", "majhul"),
]

_FLAG_LEXICON = [
    ("ghali", "ghali"), ("ghālī", "ghali"), ("ghulat", "ghali"),
    ("waqifi", "waqifi"), ("wāqifī", "waqifi"), ("waqif", "waqifi"),
    ("fathi", "fathi"), ("fatahi", "fathi"),
    ("zaydi", "zaydi"), ("zaidi", "zaydi"),
    ("nasibi", "nasibi"), ("batri", "batri"), ("mulhid", "mulhid"),
    ("corrupt", "fasid_al_madhhab"), ("deviated", "fasid_al_madhhab"),
    ("fasid", "fasid_al_madhhab"),
]

# Arabic verdict keywords, for detecting that a grade is recoverable from the quotation.
GRADE_KEYWORDS_AR = (
    "ثقة", "ثقه", "صدوق", "ضعيف", "مجهول", "مهمل", "كذاب", "وضاع",
    "غال", "واقف", "فطحي", "حسن", "لم يوثق", "لا بأس به", "مختلف فيه",
)


def parse_grade(raw):
    """Split a free-text grade into (grade, tokens, flags).

    Returns the canonical reliability grade, every reliability token found in order, and
    the doctrinal flags. Anything unrecognised yields grade None so the caller can decide
    whether to fail the batch — it is never silently coerced to a plausible value.
    """
    if raw is None:
        return "not_assessed", [], [], True
    text = str(raw).strip().lower()
    if not text:
        return "not_assessed", [], [], True

    flags, remaining = [], text
    for needle, flag in _FLAG_LEXICON:
        if needle in remaining:
            if flag not in flags:
                flags.append(flag)
            remaining = remaining.replace(needle, " ")

    tokens, scan = [], remaining
    for needle, grade in _GRADE_LEXICON:
        while needle in scan:
            tokens.append((scan.index(needle), grade))
            scan = scan.replace(needle, " " * len(needle), 1)
    tokens.sort(key=lambda t: t[0])

    ordered = []
    for _, g in tokens:
        if g not in ordered:
            ordered.append(g)

    if ordered:
        return ordered[0], ordered, flags, True
    if flags:
        # A purely doctrinal verdict ("corrupt", "waqifi") says nothing about reliability.
        return "not_assessed", [], flags, True
    return None, [], flags, False


# --- The 14 Infallibles ---
#
# Split by ambiguity. Laqabs identify an Imam on their own; bare lineage names such as
# محمد بن علي and الحسن بن علي are also ordinary narrator names, so those only exclude a
# profile when the entry itself carries an Imam honorific.

INFALLIBLE_UNAMBIGUOUS_AR = {
    "امير المومنين", "زين العابدين", "السجاد", "الباقر", "الصادق", "الكاظم",
    "الرضا", "الجواد", "التقي", "الهادي", "النقي", "العسكري", "المهدي",
    "القائم", "صاحب الزمان", "الحجه", "فاطمه الزهراء", "الزهراء", "البتول",
    "فاطمه بنت محمد", "رسول الله", "النبي",
    # Ordinal kunyahs are standard Imam shorthand and identify no one else.
    "ابو جعفر الاول", "ابو جعفر الثاني",
    "ابو الحسن الاول", "ابو الحسن الثاني", "ابو الحسن الثالث",
    "ابو الحسن الماضي", "ابو الحسن الرضا", "العبد الصالح",
    "ابو عبد الله الصادق", "ابو ابراهيم موسي", "ابو محمد العسكري",
}

INFALLIBLE_UNAMBIGUOUS_EN = {
    "amir al-muminin", "amir al muminin", "commander of the faithful",
    "zayn al-abidin", "zayn al abidin", "al-sajjad", "al-baqir", "al-sadiq",
    "al-kadhim", "al-kazim", "al-rida", "al-riza", "al-jawad", "al-taqi",
    "al-hadi", "al-naqi", "al-askari", "al-mahdi", "al-qaim", "sahib al-zaman",
    "al-hujjah", "fatima al-zahra", "fatimah al-zahra", "al-zahra", "al-batul",
    "fatima bint muhammad", "the prophet", "messenger of allah",
    "abu jafar al awwal", "abu jafar al thani",
    "abu al hasan al awwal", "abu al hasan al thani", "abu al hasan al thalith",
    "abu al hasan al madi", "abu al hasan al rida", "al abd al salih",
    "abu abd allah al sadiq", "abu ibrahim musa", "abu muhammad al askari",
}

INFALLIBLE_AMBIGUOUS_AR = {
    "محمد", "احمد", "علي بن ابي طالب", "الحسن بن علي", "الحسين بن علي",
    "علي بن الحسين", "محمد بن علي", "جعفر بن محمد", "موسى بن جعفر",
    "علي بن موسي", "علي بن محمد", "محمد بن الحسن", "موسي بن جعفر",
}

_HONORIFIC_AR = ("عليه السلام", "عليهم السلام", "عليها السلام", "عليهما السلام",
                 "صلى الله عليه", "صلوات الله علي", "سلام الله علي",
                 "عليه الصلاة", "روحي له الفداء", "(ع)", "(ص)", "(عج)")
_HONORIFIC_EN = ("(as)", "(a.s.)", "(pbuh)", "(p.b.u.h.)", "peace be upon him",
                 "peace be upon them", "(af)", "(a.j.)")


def has_imam_honorific(text):
    if not text:
        return False
    lower = text.lower()
    return (any(h in text for h in _HONORIFIC_AR)
            or any(h in lower for h in _HONORIFIC_EN))


def is_infallible(normalized_ar, normalized_en, context_text=""):
    """True when a profile is one of the 14 Infallibles.

    Ambiguous lineage names require an honorific in the entry, because they are also
    ordinary narrator names — excluding them unconditionally deletes real narrators.
    """
    if normalized_ar in INFALLIBLE_UNAMBIGUOUS_AR:
        return True
    if normalized_en in INFALLIBLE_UNAMBIGUOUS_EN:
        return True
    ambiguous = (normalized_ar in INFALLIBLE_AMBIGUOUS_AR)
    return ambiguous and has_imam_honorific(context_text)
