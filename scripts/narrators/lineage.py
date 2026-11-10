"""Is a name this man's, by his own lineage?

A name like «محمد بن عيسى» found among Aḥmad b. Muḥammad b. Khālid al-Barqī's names, or a
passage opening «الحسين بن زيدان الصرمي» shown as al-Najāshī's words on al-Ḥusayn b. Saʿīd, is
another man's. The test is the one a rijālī applies at a glance: the name's own ism must be
his, and its father must stand somewhere in his lineage.

Spelling is allowed to vary the way the manuscripts vary it: «حريز» and «جرير» are one
skeleton under the dots (taṣḥīf), «الحسن» and «الحسين» one letter apart, «مفضل» and «الفضل»
the same name with and without its prefix. A chain may skip a generation or add one —
«محمد بن الحسن بن سنان» is Muḥammad b. Sinān, raised by his grandfather — so the father of the
name need only match some ancestor of his. A father named by his kunya, «بن أبي زياد», is
matched by the name inside it.

Measured over the 38,108 Arabic names of prod's 15,509 narrators on 2026-11-09: 612 failed
the test, in 472 records. In a read sample the drops were another man's name far more often
than a variant of the man's own, and where a variant was lost it was an obscure man's.
"""

import re
import unicodedata

_DIACRITICS = re.compile(r"[ً-ْٰـ]")

# The dots are what a copyist drops or misplaces; the strokes under them are what he wrote.
_RASM = str.maketrans({
    "ب": "ٮ", "ت": "ٮ", "ث": "ٮ", "ن": "ٮ", "ي": "ٮ", "ج": "ح", "خ": "ح", "ذ": "د", "ز": "ر",
    "ش": "س", "ض": "ص", "ظ": "ط", "غ": "ع", "ف": "ڡ", "ق": "ڡ",
    "ء": "", "ا": "", "و": "", "ه": "",
})

# A title before the name, and the kunya words: «الشيخ أبي الحسين أحمد بن…» is Aḥmad.
_LEAD = {"الشيخ", "السيد", "الشريف", "المولى", "الفقيه"}
_KUNYA = {"ابو", "ابي", "ابا", "ام"}


def normalize(name):
    """The letters that decide a name: no marks, one alif, «عبد الله» as one word, «ابن» as «بن»."""
    text = _DIACRITICS.sub("", name or "")
    text = re.sub("[أإآٱ]", "ا", text)
    text = text.replace("ة", "ه").replace("ى", "ي").replace("ؤ", "و").replace("ئ", "ي")
    text = re.sub(r"[^ء-ي\s]", " ", text)
    text = re.sub(r"\b(عبد|عبيد)\s+(ال)", r"\1\2", text)
    text = re.sub(r"\bابن\b", "بن", text)
    return re.sub(r"\s+", " ", text).strip()


def _bare(word):
    return word[2:] if word.startswith("ال") else word


def _one_edit(a, b):
    if a == b:
        return True
    if abs(len(a) - len(b)) > 1:
        return False
    if len(a) == len(b):
        return sum(x != y for x, y in zip(a, b)) <= 1
    if len(a) > len(b):
        a, b = b, a
    return any(b[:i] + b[i + 1:] == a for i in range(len(b)))


def same_word(a, b):
    """One name word, allowing the article, one letter, or the dots."""
    a, b = _bare(a), _bare(b)
    return a == b or _one_edit(a, b) or a.translate(_RASM) == b.translate(_RASM)


def lineage(name):
    """(ism, ancestors) of a «X بن Y …» name, or None when the name is not of that shape."""
    words = normalize(name).split()
    while words and words[0] in _LEAD:
        words = words[1:]
    if len(words) >= 2 and words[0] in _KUNYA:
        words = words[2:]
    if len(words) < 3 or words[1] != "بن" or words[0] == "بن":
        return None
    ancestors = []
    for i in range(len(words) - 1):
        if words[i] == "بن":
            j = i + 1
            if words[j] in _KUNYA and j + 1 < len(words):
                j += 1
            ancestors.append(words[j])
    return words[0], ancestors


def foreign(name, own_names):
    """True when «name» is a full name whose ism or lineage is not his.

    False whenever there is nothing to judge by: a kunya or bare ism, or a man none of whose
    own names is of the «X بن Y» shape.
    """
    theirs = lineage(name)
    if not theirs:
        return False
    mine = [found for found in (lineage(n) for n in own_names) if found]
    if not mine:
        return False
    isms = {m[0] for m in mine}
    ancestors = {a for m in mine for a in m[1]}
    ism_ok = any(same_word(theirs[0], ism) for ism in isms)
    father_ok = any(same_word(x, a) for x in theirs[1] for a in ancestors)
    return not (ism_ok and father_ok)


def opening_name(passage):
    """The name a passage opens with, as a book heads an entry: up to its first pause."""
    head = re.sub(r"\([^)]*\)", "", passage or "")[:80]
    return re.split(r"[،,:.؛]", head)[0]


# --- The same test on the transliterated side ---
#
# The English is a translator's reading of unvowelled Arabic, so words are compared by their
# consonants: «Bakir» and «Bukayr», «Hamiri» and «Himyari» are one word here, as they are one
# word in the Arabic.

_EN_CONNECTORS = {"ibn", "bin", "b", "bint", "bt"}
_EN_KUNYA = {"abu", "abi", "aba", "umm"}
_EN_LEAD = {"shaykh", "sayyid", "al-shaykh", "al-sayyid"}


def _en_words(name):
    text = unicodedata.normalize("NFKD", name or "")
    text = "".join(c for c in text if not unicodedata.combining(c)).lower()
    text = re.sub(r"[ʿʾ'‘’`]", "", text)
    text = re.sub(r"\babd(?:ul|al|el|u)?[\s-]*(?:al|el|ar|as|ash|ad|at|az|an)?[\s-]*(?=[a-z])", "abd", text)
    text = re.sub(r"\b(?:al|el|ar|as|ash|ad|adh|at|az|an)-", "", text)
    text = re.sub(r"[^a-z\s]", " ", text)
    return text.split()


def _consonants(word):
    word = re.sub(r"(?<=[aeiou])h$", "", word)
    word = word.replace("dh", "z").replace("th", "s").replace("sh", "x").replace("kh", "k")
    word = word.replace("gh", "g").replace("q", "k")
    word = re.sub(r"^[aeiou]+(?=.)", "", word)
    word = re.sub(r"(?<=.)[aeiouyw]+", "", word)
    return re.sub(r"(.)\1+", r"\1", word)


def _lineage_en(name):
    words = _en_words(name)
    while words and words[0] in _EN_LEAD:
        words = words[1:]
    if len(words) >= 2 and words[0] in _EN_KUNYA:
        words = words[2:]
    if len(words) < 3 or words[1] not in _EN_CONNECTORS:
        return None
    ancestors = []
    for i in range(len(words) - 1):
        if words[i] in _EN_CONNECTORS:
            j = i + 1
            if words[j] in _EN_KUNYA and j + 1 < len(words):
                j += 1
            ancestors.append(_consonants(words[j]))
    return _consonants(words[0]), ancestors


def foreign_en(name, own_names):
    """foreign(), for a transliterated name against his transliterated headings."""
    theirs = _lineage_en(name)
    if not theirs:
        return False
    mine = [found for found in (_lineage_en(n) for n in own_names) if found]
    if not mine:
        return False
    # One consonant either way, as the Arabic allows one letter: «Kharrabudh» and «Kharbud»
    # are one name to a translator who writes dh for ذ and one who writes d.
    ism_ok = any(_one_edit(theirs[0], m[0]) for m in mine)
    father_ok = any(_one_edit(x, a) for x in theirs[1] for m in mine for a in m[1])
    return not (ism_ok and father_ok)


# --- Correcting an English display name against its Arabic ---

# The connective words, by kind: «بن» is «ibn», «أبو» is «Abu». A name whose two scripts put
# different kinds in one place is aligned wrong, and is left alone.
_KIND_AR = {"بن": "son", "ابن": "son", "بنت": "daughter", "أبو": "father", "أبي": "father",
            "أبا": "father", "ابو": "father", "ابي": "father", "ابا": "father", "أم": "mother",
            "ام": "mother"}
_KIND_EN = {"ibn": "son", "bin": "son", "b.": "son", "b": "son", "bint": "daughter",
            "abu": "father", "abi": "father", "aba": "father", "umm": "mother"}
_FUSED_ABD = re.compile(r"^['ʿ‘’]?abd(?:u|ul|al|ullah|allah|ullāh)", re.IGNORECASE)


def _fold_en(word):
    return re.sub(r"[ʿʾ'‘’`\-]", "", word).lower()


def _word_consonants(word):
    """The consonants alone: no vowels at all and no final h, which a transliteration adds
    for a tāʾ marbūṭa (Zurarah) and drops after a vowel (Ruh for Rawh) as it pleases."""
    letters = "".join(_consonants(w) for w in _en_words(word)).replace("v", "")
    return letters[:-1] if len(letters) > 1 and letters.endswith("h") else letters


def corrected_english(display_ar, display_en, learned, fixes):
    """The display name with each word the Arabic contradicts put right, and what changed.

    Words are aligned one to one, so a name whose two scripts differ in length is left alone,
    and so is one whose connectives differ in kind («Abu» where the Arabic has «بن») or that
    carries a variant reading in brackets. A word is respelt when the hand-checked list
    («fixes») spells its Arabic otherwise but its consonants agree (Bakir → Bukayr). It is
    replaced when its consonants cannot be its Arabic at all (Dirar for «دراج», Ishaq for
    «إبراهيم»), but only where that is the one such word and at least two others line up — a
    name where words disagree in two places is misaligned, not misspelt.
    """
    arabic = _DIACRITICS.sub("", display_ar or "").split()
    english = (display_en or "").split()
    if not arabic or len(arabic) != len(english):
        return display_en, []
    for a, e in zip(arabic, english):
        if _KIND_AR.get(a) != _KIND_EN.get(e.lower()) or _FUSED_ABD.match(e) or e.startswith("("):
            return display_en, []
    out, wrong, anchored = [], [], 0
    for i, (a, e) in enumerate(zip(arabic, english)):
        if a in _KIND_AR:
            anchored += 1
            out.append(e)
            continue
        right = fixes.get(a) or learned.get(a)
        if not right:
            out.append(e)
            continue
        if _word_consonants(right) == _word_consonants(e):
            anchored += 1
            out.append(right if a in fixes and _fold_en(right) != _fold_en(e) else e)
        else:
            wrong.append(i)
            out.append(right)
    if wrong and (len(wrong) > 1 or anchored < 2):
        # Misaligned: undo the replacements, keep only the respellings that agreed.
        for i in wrong:
            out[i] = english[i]
    changed = []
    for i, (old, new) in enumerate(zip(english, out)):
        if new != old:
            if i == 0 and new[:1].isalpha():
                new = new[0].upper() + new[1:]
            elif i == 0 and new[:1] in "'ʿ" and len(new) > 1:
                new = new[0] + new[1].upper() + new[2:]
            out[i] = new
            changed.append((old, new))
    return (" ".join(out), changed) if changed else (display_en, [])


# --- «ابن X» standing alone among a man's names ---
#
# A khoei entry says «روى عنه ابن عقدة» or «وهو ابن أخي فلان», and phase 1 kept «ابن عقدة» and
# «ابن أخي» as names of the man the entry is about. Two words are too few for foreign() to
# judge by, so these are judged by what X is. A relative («ابن أخي», «ابن عمك») is never a
# name. Another man's own heading («ابن عقدة» is how al-Khoei heads Aḥmad b. Muḥammad b. Saʿīd)
# is his, not this man's. And a bare ism — «ابن سعيد» on Abān b. Taghlib, «ابن جبلة» on ʿAlī b.
# Abī Ḥamza — is only a son of somebody, unless that somebody stands in his lineage. A laqab of
# his family («ابن السراج», «ابن فضال», «ابن الأدبر») is none of these, and stays.

_RELATIVES = {"اخي", "اخيه", "اخ", "اخت", "اخته", "اختي", "عم", "عمه", "عمي", "عمك", "خال",
              "خاله", "خالي", "خالك", "عمته", "خالته", "ابنه", "بنته", "بنت"}


def son_of_whom(name):
    """X of a name that is only «ابن X», or None."""
    words = normalize(name).split()
    if len(words) != 2 or words[0] != "بن":
        return None
    return words[1]


def foreign_ibn(name, own_names, own_headings, headed_by_others, common_isms):
    """True when «ابن X» is not a name of the man whose names are «own_names».

    «headed_by_others» says whether a normalized name is some other man's heading;
    «common_isms» holds the normalized isms many men in the corpus are named by.
    """
    x = son_of_whom(name)
    if x is None:
        return False
    form = normalize(name)
    if form in {normalize(h) for h in own_headings}:
        return False
    if x in _RELATIVES:
        return True
    for own in own_names:
        found = lineage(own)
        words = ([found[0]] + found[1]) if found else []
        words += [w for w in normalize(own).split() if w not in ("بن",) and w not in _KUNYA]
        if any(same_word(x, w) for w in words):
            return False
    if headed_by_others(form):
        return True
    return x in common_isms


_CHAIN_WORDS = re.compile(
    r"^(?:حدثني|حدثنا|أخبرنا|أخبرني|اخبرنا|اخبرني|أنبأنا|انبأنا|ثنا|نا|عن|وعن|روى|وروى|رواه|قال|سمعت)\s+")


def without_chain_words(name):
    """«حدثني محمد بن قولويه» is «محمد بن قولويه»; «عن سمرة، عن أبي سعيد» is two men, so none."""
    if not name or not _CHAIN_WORDS.match(name):
        return name
    rest = _CHAIN_WORDS.sub("", name, count=1)
    if re.search(r"(?:^|[\s،,])(?:عن|وعن)\s", rest):
        return None
    return rest.strip(" ،,")


# --- The nisbas a man's names end in ---

_NOT_NISBA = {"الله", "الرحمن", "الرحمان", "الملك", "الحسن", "الحسين", "العباس", "القاسم",
              "الفضل", "الحكم", "المغيرة", "الحارث", "الربيع", "العلاء", "النعمان", "الصلت",
              "المفضل", "الفضيل", "الهيثم", "المنذر", "الوليد", "السري", "الاصبغ", "الأصبغ"}


def nisbas(name):
    """The «ال…» words that close a name after its lineage: «الطوسي» of «محمد بن الحسن بن علي
    الطوسي», «العطار القمي» of «محمد بن يحيى أبو جعفر العطار القمي». Read from the front: the
    ism, each «بن X» (and «بن أبي X»), a kunya, then the run of nisbas — and nothing after the
    first word that is not one, so a heading that ran on into prose («النيشابوري عليه اعتمد أبو
    عمرو الكشي») gives «النيشابوري» and not the «الكشي» of the man it goes on to name."""
    words = [w.strip("،,.:()") for w in _DIACRITICS.sub("", name or "").split()]
    kunya = ("أبو", "ابو", "أبي", "ابي", "أبا", "ابا", "أم", "ام")
    i = 1
    if words and words[0] in kunya:
        i = 2
    while i < len(words):
        if words[i] in ("بن", "ابن", "بنت") and i + 1 < len(words):
            i += 3 if words[i + 1] in kunya and i + 2 < len(words) else 2
        elif words[i] in kunya and i + 1 < len(words):
            i += 2
        else:
            break
    out = []
    for bare in words[i:]:
        # a nisba ends in its yāʾ, «الطوسي», «الساباطي»; a trade is in its faʿʿāl shape,
        # «العطار», «الخزاز»
        trade = len(bare) == 6 and bare[4] == "ا"
        if not (bare.startswith("ال") and len(bare) > 4 and (bare.endswith("ي") or trade)
                and bare not in _NOT_NISBA):
            break
        out.append(bare)
    return out


def nisbas_en(name):
    """The same, of a transliterated name: «al-Tusi» of «Muhammad ibn al-Hasan al-Tusi»."""
    words = (name or "").replace(",", " ").split()
    start = 1
    for i, word in enumerate(words):
        low = word.lower()
        if low in ("ibn", "bin", "b.", "bint") and i + 1 < len(words):
            j = i + 1
            if words[j].lower() in ("abi", "abu", "aba") and j + 1 < len(words):
                j += 1
            start = max(start, j + 1)
        if low in ("abu", "abi", "aba", "umm") and i + 1 < len(words):
            start = max(start, i + 2)
    return [w for w in words[start:] if re.match(r"(?i)^(al|el)-[a-zʿ'’]{2,}i$", w)]
