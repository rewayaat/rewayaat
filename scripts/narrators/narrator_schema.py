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


# Spellings of one name that the fold above cannot reach, because they differ by a letter rather
# than by a form of one. Both are ordinary in printed Rijal works and neither is another name:
# «عبد الرحمان» for «عبد الرحمن» stands in 403 headings, «الحرث» for «الحارث» in 43, and a rule
# comparing them as strings keeps one man in two people.
_AR_SPELLINGS = (
    ("عبد الرحمان", "عبد الرحمن"),
    ("الحرث", "الحارث"),
)


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
    s = " ".join(parts).strip()
    for written, folded in _AR_SPELLINGS:
        s = s.replace(written, folded)
    return s


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


# --- Generations ---
#
# Every book says when a man lived by naming the Imam he kept company with, but each says it its
# own way: Rijal al-Tusi as a chapter heading, «أصحاب أبي عبد الله جعفر بن محمد الصادق»; Khoei and
# Mamaqani as free text, in 1,926 and 2,564 distinct spellings; al-Du'afa' with the name
# transliterated, «companion of al-Ṣādiq». Compared as strings they never agree and so never
# disagree either, which wastes the one piece of evidence al-Tusi gives for all 6,429 of its
# entries. Read as the Imams they name, they place a man in time to within a generation.
#
# The Imams in order, the Prophet at 0. A man may serve two or three in succession, so two
# readings that overlap corroborate each other, adjacent ones say nothing, and ones three apart
# — al-Sadiq's companion and the Prophet's, 130 years — cannot be one man.

IMAM_ORDINALS = {
    "prophet": 0, "ali": 1, "hasan": 2, "husayn": 3, "sajjad": 4, "baqir": 5, "sadiq": 6,
    "kazim": 7, "rida": 8, "jawad": 9, "hadi": 10, "askari": 11, "mahdi": 12,
}

# Ordered: a longer lineage is tested before the shorter one it contains, so «الحسن بن علي بن
# محمد» is read as al-'Askari and not as al-Hasan, and «علي بن الحسين» as al-Sajjad, not 'Ali.
# The Arabic is folded the same way a name is — ى to ي, ؤ to و — so the patterns are written
# plainly and folded here rather than spelled in their folded form.
_IMAM_PATTERNS = [
    # al-Tusi heads al-'Askari's chapter with his lineage, «الحسن بن علي بن محمد بن علي الرضا»:
    # read whole, or its tail is taken for al-Rida
    # The eleventh Imam is al-Hasan, and the second Imam is al-Hasan. Written out, «al-Hasan
    # al-'Askari» holds both names, and matching «askar» alone leaves «al-hasan» behind for
    # the second Imam's pattern to find: Safwan b. Yahya, who died in 210, was being shown as
    # a companion of an Imam killed in 50. The whole title is matched here so that the whole
    # title is consumed.
    ("askari", r"(?:abu\s+muhammad\s+)?(?:al[\s\-']*)?hasan(?:\s+(?:b\.?|ibn|bin)\s+ali)?"
               r"[\s\-'()]*(?:al[\s\-']*)?askari|askari",
     ["الحسن بن علي بن محمد بن علي الرضا", "الحسن بن علي بن محمد",
      "الحسن بن علي العسكري", "الحسن العسكري", "أبي محمد العسكري", "العسكري"]),
    ("hadi", r"(?:abu\s*al[\s\-']*hasan\s+|ali\s+)?(?:al[\s\-']*)?(?:hadi|naqi)|"
             r"abu\s*al[\s\-']*hasan\s+(?:al[\s\-']*)?thalith",
     ["علي بن محمد بن علي", "أبي الحسن الثالث", "علي بن محمد عليهم",
      "علي الهادي", "الهادي"]),
    ("jawad", r"abu\s*ja.?far\s+(?:al[\s\-']*)?thani|jawad|taqi", ["محمد بن علي الثاني", "أبي جعفر الثاني", "محمد بن علي بن موسى",
                              "محمد الجواد", "الجواد"]),
    ("rida", r"ali\s+(?:b\.?|ibn|bin)\s+musa|(?:abu\s*al[\s\-']*hasan\s+|ali\s+)?(?:al[\s\-']*)?rid(?:a|ha)|"
             r"abu\s*al[\s\-']*hasan\s+(?:al[\s\-']*)?thani",
     ["علي بن موسى", "أبي الحسن الثاني", "الرضا"]),
    ("kazim", r"(?:abu\s*al[\s\-']*hasan\s+)?musa(?:\s+(?:b\.?|ibn|bin)\s+ja.?far)?|"
              r"abu\s*al[\s\-']*hasan\s+(?:al[\s\-']*)?awwal|kazim|kadhim",
     ["موسى بن جعفر", "أبي الحسن موسى", "الكاظم", "أبي الحسن الأول"]),
    ("sadiq", r"ja.?far\s+(?:b\.?|ibn|bin)\s+muhammad|sadiq", ["جعفر بن محمد", "أبي عبد الله جعفر", "الصادق"]),
    ("baqir", r"baqir", ["محمد بن علي بن الحسين", "أبي جعفر محمد بن علي", "الباقر"]),
    ("sajjad", r"ali\s+(?:b\.?|ibn|bin)\s+(?:al[\s\-]*)?(?:husayn|husain|hussain)|"
               r"(?:ali\s+)?(?:al[\s\-]*)?sajjad|zayn al.?abidin|zayn ul.?abidin",
     ["علي بن الحسين", "أبي محمد علي بن الحسين", "السجاد", "زين العابدين"]),
    # Khoei names the second and third Imams and the first bare: «من أصحاب الحسن عليه السلام»
    ("husayn", r"husayn|husain|hussain", ["الحسين بن علي", "أبي عبد الله الحسين",
                                          "أصحاب الحسين(?! بن)"]),
    ("hasan", r"\bhasan\b", ["الحسن بن علي(?! بن)", "أبي محمد الحسن بن علي(?! بن)",
                              "أصحاب الحسن(?! بن)"]),
    ("ali", r"\bali\b|amir al.?mu.?minin|amir ul.?mu.?minin",
     ["أمير المؤمنين", "علي بن أبي طالب", "أصحاب علي(?! بن)"]),
    ("mahdi", r"mahdi|qaim", ["الحجة", "صاحب الزمان", "صاحب الأمر"]),
    ("prophet", r"prophet|sahabi|sahabiyya|messenger",
     ["النبي", "رسول الله", "صحابي", "الصحابة"]),
]


def _fold_pattern(latin, arabic):
    """One pattern per Imam, its Arabic alternatives folded as a name is folded."""
    # The Latin side is matched as whole words: «hadi» must not be found inside «hadith», nor
    # «musa» inside «Musawi». The Arabic side is left exactly as it was.
    parts = ["(?<![a-z])(?:" + latin + ")(?![a-z])"] if latin else []
    for phrase in arabic:
        tail = ""
        if phrase.endswith(")"):                      # keep a lookahead out of the folding
            phrase, tail = phrase.split("(", 1)
            tail = "(" + tail
        parts.append(normalize_arabic_raw_fold(phrase) + tail)
    return re.compile("|".join(p for p in parts if p))


_IMAM_COMPILED = [(name, _fold_pattern(latin, arabic))
                  for name, latin, arabic in _IMAM_PATTERNS]
# «باب ذكر أسماء من لم يرو عن واحد من الأئمة» — al-Tusi's chapter for men who kept company with no
# Imam at all. It names no generation, so it must not be read as one.
_NO_IMAM = re.compile("|".join(normalize_arabic_raw_fold(p) for p in
                               ("لم يرو عن واحد من الأئمة", "لم يرو عنهم", "لم يروِ عن")))

# «أبو الحسن» is the kunya of the seventh Imam, the eighth and the tenth, and of nobody in
# particular when it stands alone. The Arabic side already refuses it — «من أصحاب أبي الحسن»
# names no generation — and the transliterated side must refuse it too, instead of handing
# the bare «hasan» inside it to the second Imam. Where a qualifier follows, the qualifier is
# left standing and matched on its own.
_AMBIGUOUS_KUNYA = re.compile(
    r"abu[\s\-']*(?:al[\s\-']*)?hasan"
    r"(?!\s*(?:al[\s\-']*)?(?:rid|musa|thalith|thani|awwal|hadi|naqi|kazim|kadhim))")


# An Imam's name is also the opening of many a narrator's longer one: «al-Hasan b. Ali b.
# Faddal», «Ali b. Abd al-Ali», «Muhammad Baqir al-Majlisi», «Hasan b. al-Shahid al-Thani».
# A match is therefore accepted only when it is the whole name. What may follow an Imam's own
# name is his own recorded nasab, listed here; a further «b. X» beyond it, or a nisba that is not
# one of the Imams' titles, makes it somebody else, and the whole name is consumed unread so that
# the «Ali» inside it is not then taken for the first Imam.
_B = r"(?:b\.?|ibn|bin)"
_ALI_ABI = _B + r"\s+abi\s+talib"
_ALI_TAIL = re.compile(r"(?:\s+" + _B + r"\s+ali(?:\s+" + _ALI_ABI + r")?)?")
_OWN_TAIL = {
    1: re.compile(r"(?:\s+" + _ALI_ABI + r")?"),
    2: _ALI_TAIL, 3: _ALI_TAIL, 4: _ALI_TAIL,
    6: re.compile(r"(?:\s+" + _B + r"\s+ali(?:\s+" + _B + r"\s+(?:al[\s\-]*)?husayn)?)?"),
    7: re.compile(r"(?:\s+" + _B + r"\s+muhammad(?:\s+" + _B + r"\s+ali)?)?"),
    8: re.compile(r"(?:\s+" + _B + r"\s+ja.?far(?:\s+" + _B + r"\s+muhammad)?)?"),
}
# The same, in Arabic: the alternatives above end wherever the book's own heading did, and an
# alternative like «أبي عبد الله الحسين» is followed by the rest of the Imam's name.
_BN = normalize_arabic_raw_fold("بن")
_ALI_AR = r"(?:\s+" + _BN + r"\s+" + normalize_arabic_raw_fold("علي") + r"(?:\s+" + _BN + r"\s+" + \
          normalize_arabic_raw_fold("أبي طالب") + r")?)?"
_OWN_TAIL_AR = {
    2: re.compile(_ALI_AR), 3: re.compile(_ALI_AR), 4: re.compile(_ALI_AR),
    5: re.compile(r"(?:\s+" + _BN + r"\s+" + normalize_arabic_raw_fold("الحسين") + r")?"),
    7: re.compile(r"(?:\s+" + _BN + r"\s+" + normalize_arabic_raw_fold("جعفر") + r")?"),
    8: re.compile(r"(?:\s+" + _BN + r"\s+" + normalize_arabic_raw_fold("موسى") + r")?"),
}
_NASAB_MORE = re.compile(r"\s*(?:b\.?|ibn|bin|" + normalize_arabic_raw_fold("بن") +
                         r")(?=\s)\s+(?:al[\s\-]*)?\S+")
_NASAB_BEFORE = re.compile(r"(?:\b(?:b\.?|ibn|bin)|\babu|\babd|\bbint)\s+(?:al[\s\-]*)?$")
_NISBA_AFTER = re.compile(r"\s+al[\s\-]+([a-z]+)")
# Titles that may follow an Imam's name without making it a different man's.
_TITLE_OK = {"murtada", "mujtaba", "rida", "ridha", "hadi", "naqi", "askari", "sajjad", "kazim",
             "kadhim", "baqir", "sadiq", "jawad", "taqi", "mahdi", "qaim", "shahid", "imam",
             "awwal", "thani", "thalith", "abidin", "abideen"}

# «Father of», «Student of», «Shaykh of»: a relation to a man, not companionship with an Imam.
# These are the heads that occur in the data. A son, daughter, brother, uncle, servant or agent
# of an Imam himself lived in that Imam's time, so those keep the Imam when he is the object
# and nothing else stands between: «Servant of al-Rida», but not «Son of a companion of
# al-Baqir», who is a generation later.
_ROLE_HEAD = re.compile(r"^\s*(?:late\s+|former\s+)?(shaykh|sheikh|student|teacher|son|daughter|"
                        r"father|brother|uncle|servant|agent|successor)\s+of\b(.*)$", re.S)
_ROLE_KEEPS_IMAM_OBJECT = {"son", "daughter", "brother", "uncle", "servant", "agent"}
_PLAIN_HEAD = re.compile(r"^\s*(?:late\s+)?(?:companion|contemporary|narrat\w*|associate|"
                         r"disciple|met)\b")
_IMAM_PREFIX = re.compile(r"\s*(?:the\s+)?(?:imam\s+)?")


def _clauses(text):
    """Split at commas and semicolons outside brackets."""
    out, depth, cur = [], 0, []
    for ch in text:
        depth += (ch in "([") - (ch in ")]")
        if ch in ",;" and depth <= 0:
            out.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    out.append("".join(cur))
    return out


def _named(working, arabic):
    """(start, ordinal) of every Imam named as a whole name in one clause."""
    found = []
    for name, pattern in _IMAM_COMPILED:
        ordinal, pos = IMAM_ORDINALS[name], 0
        while True:
            m = pattern.search(working, pos)
            if not m:
                break
            start, end = m.span()
            reject = (not arabic) and bool(_NASAB_BEFORE.search(working[:start]))
            tail = (_OWN_TAIL_AR if arabic else _OWN_TAIL).get(ordinal)
            if tail and not reject:
                end = tail.match(working, end).end()
            if not reject and _NASAB_MORE.match(working, end):
                reject = True
            if not reject and not arabic:
                nisba = _NISBA_AFTER.match(working, end)
                if nisba and nisba.group(1) not in _TITLE_OK:
                    reject, end = True, nisba.end()
            if reject:
                more = _NASAB_MORE.match(working, end)
                while more:
                    end = more.end()
                    more = _NASAB_MORE.match(working, end)
            else:
                found.append((start, ordinal))
            working = working[:start] + " " * (end - start) + working[end:]
            pos = end
    return found


def imam_generations(raw):
    """The Imams a generation statement names, as ordinals; empty when it names none."""
    if not raw:
        return frozenset()
    arabic = any("؀" <= ch <= "ۿ" for ch in str(raw))
    text = normalize_arabic_raw_fold(str(raw)).lower() if arabic else str(raw).lower()
    text = re.sub(r"['’]s\b", "", text)          # «al-Rida's time» names al-Rida
    text = re.sub(r"[ʿʾ`'’]", "", text)
    for fold, plain in (("ā", "a"), ("ī", "i"), ("ū", "u"), ("ṣ", "s"), ("ḍ", "d"), ("ṭ", "t"),
                        ("ẓ", "z"), ("ḥ", "h"), ("ḳ", "k"), ("ġ", "g"), ("š", "sh")):
        text = text.replace(fold, plain)
    if _NO_IMAM.search(text):
        return frozenset()
    if arabic:
        return frozenset(o for _s, o in _named(_AMBIGUOUS_KUNYA.sub(" ", text), True))
    result, role = set(), None
    for clause in _clauses(text):
        head = _ROLE_HEAD.match(clause)
        if head:
            role = head.group(1)
        elif _PLAIN_HEAD.match(clause):
            role = None
        working = _AMBIGUOUS_KUNYA.sub(" ", clause)
        found = _named(working, False)
        if not role:
            result |= {o for _s, o in found}
        elif head and role in _ROLE_KEEPS_IMAM_OBJECT:
            # the object follows «of»; an Imam is kept only when he stands right there
            at = _IMAM_PREFIX.match(working, _ROLE_HEAD.match(working).start(2)).end()
            first = min((s for s, _o in found), default=None)
            gap = working[at:first] if first is not None else " of "
            # «Son of Ja'far al-Sadiq» has a given name before the title; «Son of a companion
            # of al-Baqir» has another man.
            if first is not None and first >= at and len(gap.split()) <= 3 and not re.search(
                    r"\b(?:of|a|an|companions?|students?|followers?|disciples?)\b", gap):
                result |= {o for _s, o in found}
    return frozenset(result)


# Phase 1 agents were asked what generation a man belongs to, and some of them reasoned
# instead of reading: «Companion of al-Sadiq (implied by context…)», «Contemporary of
# al-Mahdi or later (likely 4th century)». A statement hedged like that is the agent's
# opinion, not the book's, and it must not place a man in time.
_HEDGED = re.compile(r"implied|likel|probabl|possibl|perhaps|apparent|seem|uncertain|"
                     r"presumab|or later|unclear|may have|might|assum|inferr|by context|"
                     r"unknown|unidentified", re.I)


def says_no_imam(raw):
    """True where a book states he kept company with no Imam at all.

    al-Tusi files such men under «باب ذكر أسماء من لم يرو عن واحد من الأئمة». That is a
    statement about a man, not a silence about him, and a page should say it rather than
    leave the line empty for a guess to fill.
    """
    if not raw:
        return False
    return bool(_NO_IMAM.search(normalize_arabic_raw_fold(str(raw))))


def is_reasoned(raw):
    """True where a generation statement is an agent's inference rather than a reading."""
    return bool(raw) and bool(_HEDGED.search(str(raw)))


def generation_distance(a, b):
    """Generations apart, or None where either statement names no Imam.

    0 means they share an Imam. A man serving two or three Imams in succession is ordinary, so
    1 and 2 are no evidence of difference; 3 is about eighty years and is.
    """
    if not a or not b:
        return None
    if a & b:
        return 0
    return min(abs(x - y) for x in a for y in b)
