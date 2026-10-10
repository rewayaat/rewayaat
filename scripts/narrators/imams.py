#!/usr/bin/env python3
"""The fourteen, and which kunya in a chain means which of them.

The rijāl books file narrators, not Imams. Searching the 15,976 indexed people for al-Ṣādiq
finds «جعفر بن محمد بن يونس» and two Khoei one-liners — the Imams have no entry anywhere,
because no rijālī assesses the man he is assessing narrators *for*. Yet 12,469 of the names in
the corpus's chains are theirs, more than any single narrator, and «عن أبي عبد الله عليه
السلام» is the commonest clause in Shia hadith. So they are listed here by hand.

This roster is a different kind of thing from everything else in the pipeline and is kept
apart deliberately: it is not extracted from a book and carries no verdict. Nobody grades an
Imam. All it holds is which person a kunya names, which is reference knowledge, not a finding.

The settled chain conventions are encoded as such, and the genuinely contested ones are not:

- «أبو عبد الله» in a chain is al-Ṣādiq. The third Imam shares the kunya, but he is named in
  chains as «الحسين» or «أبي عبد الله الحسين», and a narrator transmitting from «أبي عبد
  الله» is transmitting from the sixth Imam. The manuals state this; 7,669 mentions turn on it.
- «أبو محمد» in a chain is al-ʿAskarī, the eleventh, on the same ground.
- «أبو جعفر» is al-Bāqir or al-Jawād and «أبو الحسن» is al-Kāẓim, al-Riḍā or al-Hādī. These
  are read from the qualifier when there is one — «أبي جعفر الثاني», «أبي الحسن الرضا» — and
  otherwise left undecided, because classical scholarship leaves them undecided too.
- «أحدهما» names neither of the two on purpose. It is never resolved.
"""
import re

# One entry a person: the forms that name him alone, and nothing about his standing.
ROSTER = [
    {"id": "i00", "ordinal": 0, "name_ar": "محمد بن عبد الله صلى الله عليه وآله",
     "name_en": "The Prophet Muhammad", "died": 11,
     "exact": ["النبي", "رسول الله", "النبي محمد", "محمد رسول الله", "نبي الله"],
     "en": ["prophet", "the messenger"]},
    {"id": "i01", "ordinal": 1, "name_ar": "علي بن أبي طالب", "name_en": "Ali ibn Abi Talib",
     "died": 40,
     "exact": ["علي بن ابي طالب", "امير المومنين", "علي", "عليا", "ابي تراب", "الوصي",
               "علي امير المومنين"],
     "en": ["amir al-muminin", "ali ibn abi talib", "imam ali"]},
    {"id": "i02", "ordinal": 2, "name_ar": "الحسن بن علي المجتبى", "name_en": "al-Hasan ibn Ali",
     "died": 50, "exact": ["الحسن بن علي بن ابي طالب", "الحسن المجتبي", "السبط"],
     "en": ["al-hasan ibn ali", "al-mujtaba"]},
    {"id": "i03", "ordinal": 3, "name_ar": "الحسين بن علي", "name_en": "al-Husayn ibn Ali",
     "died": 61, "exact": ["الحسين بن علي", "الحسين", "ابي عبد الله الحسين", "سيد الشهداء"],
     "en": ["al-husayn", "al-hussain", "sayyid al-shuhada"]},
    {"id": "i04", "ordinal": 4, "name_ar": "علي بن الحسين زين العابدين",
     "name_en": "Ali ibn al-Husayn (al-Sajjad)", "died": 95,
     "exact": ["علي بن الحسين", "زين العابدين", "السجاد", "علي بن الحسين زين العابدين"],
     "en": ["al-sajjad", "zayn al-abidin", "zain al-abideen", "ali ibn al-husayn"]},
    {"id": "i05", "ordinal": 5, "name_ar": "محمد بن علي الباقر", "name_en": "Muhammad al-Baqir",
     "died": 114,
     "exact": ["محمد بن علي الباقر", "الباقر", "ابي جعفر الباقر", "ابي جعفر الاول",
               "ابي جعفر محمد بن علي", "محمد الباقر"],
     "en": ["al-baqir", "baqir"]},
    {"id": "i06", "ordinal": 6, "name_ar": "جعفر بن محمد الصادق", "name_en": "Jafar al-Sadiq",
     "died": 148,
     "exact": ["ابي عبد الله", "ابو عبد الله", "ابا عبد الله", "الصادق", "جعفر بن محمد",
               "جعفر الصادق", "ابي عبد الله الصادق", "ابي عبد الله جعفر بن محمد",
               "جعفر بن محمد الصادق", "ابي عبد الله جعفر"],
     "en": ["al-sadiq", "sadiq", "abu abd allah", "abu abdullah", "abu 'abd allah"]},
    {"id": "i07", "ordinal": 7, "name_ar": "موسى بن جعفر الكاظم", "name_en": "Musa al-Kazim",
     "died": 183,
     "exact": ["موسي بن جعفر", "الكاظم", "ابي ابراهيم", "ابي الحسن الاول",
               "ابي الحسن موسي", "ابي الحسن الماضي", "العبد الصالح", "ابي الحسن الكاظم",
               "موسي بن جعفر الكاظم", "ابي الحسن موسي بن جعفر"],
     "en": ["al-kazim", "kazim", "abu ibrahim", "musa ibn ja'far", "musa ibn jafar"]},
    {"id": "i08", "ordinal": 8, "name_ar": "علي بن موسى الرضا", "name_en": "Ali al-Rida",
     "died": 203,
     "exact": ["علي بن موسي", "الرضا", "ابي الحسن الرضا", "ابي الحسن الثاني",
               "علي بن موسي الرضا", "ابي الحسن علي بن موسي"],
     "en": ["al-rida", "al-ridha", "rida", "ali ibn musa"]},
    {"id": "i09", "ordinal": 9, "name_ar": "محمد بن علي الجواد", "name_en": "Muhammad al-Jawad",
     "died": 220,
     "exact": ["ابي جعفر الثاني", "الجواد", "محمد بن علي الجواد", "ابي جعفر التقي",
               "التقي", "محمد بن علي الثاني"],
     "en": ["al-jawad", "jawad", "al-taqi"]},
    {"id": "i10", "ordinal": 10, "name_ar": "علي بن محمد الهادي", "name_en": "Ali al-Hadi",
     "died": 254,
     "exact": ["ابي الحسن الثالث", "الهادي", "علي بن محمد الهادي", "ابي الحسن الهادي",
               "علي بن محمد العسكري", "النقي", "ابي الحسن العسكري"],
     "en": ["al-hadi", "hadi", "al-naqi"]},
    {"id": "i11", "ordinal": 11, "name_ar": "الحسن بن علي العسكري",
     "name_en": "al-Hasan al-Askari", "died": 260,
     "exact": ["ابي محمد", "الحسن بن علي العسكري", "ابي محمد العسكري", "ابي محمد الحسن",
               "الحسن العسكري"],
     "en": ["al-askari", "askari", "abu muhammad"]},
    {"id": "i12", "ordinal": 12, "name_ar": "محمد بن الحسن المهدي",
     "name_en": "Muhammad al-Mahdi", "died": None,
     "exact": ["المهدي", "القايم", "صاحب الزمان", "صاحب الامر", "محمد بن الحسن المهدي",
               "الحجة", "ابي القاسم محمد بن الحسن"],
     "en": ["al-mahdi", "mahdi", "al-qaim", "sahib al-zaman"]},
    {"id": "i13", "ordinal": None, "name_ar": "فاطمة الزهراء", "name_en": "Fatima al-Zahra",
     "died": 11, "exact": ["فاطمة", "الزهراء", "فاطمة الزهراء", "فاطمة بنت رسول الله"],
     "en": ["fatima", "al-zahra"]},
]

BY_ID = {imam["id"]: imam for imam in ROSTER}

# Kunyas two or three of them share, which a chain does not settle by itself.
SHARED = {
    "ابي جعفر": ("i05", "i09"),
    "ابو جعفر": ("i05", "i09"),
    "ابا جعفر": ("i05", "i09"),
    "ابي الحسن": ("i07", "i08", "i10"),
    "ابو الحسن": ("i07", "i08", "i10"),
    "ابا الحسن": ("i07", "i08", "i10"),
    "احدهما": ("i05", "i06"),          # «one of the two» — named on purpose as neither
    "ابي القاسم": ("i00", "i12"),
}

EXACT = {}
for imam in ROSTER:
    for form in imam["exact"]:
        EXACT.setdefault(form, imam["id"])

_LEADING = re.compile(r"^(?:سال|سالت|كتبت|قلت|دخلت|قال|الي|عند|مع)\s+")


def identify(folded):
    """Which of the fourteen a chain form names: one id, several, or none at all."""
    if not folded:
        return ()
    for _ in range(3):
        if folded in EXACT:
            return (EXACT[folded],)
        if folded in SHARED:
            return SHARED[folded]
        trimmed = _LEADING.sub("", folded, count=1)
        if trimmed == folded:
            break
        folded = trimmed
    return ()


def from_relation(name, folded):
    """Which Imam a rijāl book's own relation line means, Arabic or transliterated.

    `imams_from` holds 791 spellings of fourteen people — «أبي عبد الله عليه السلام»,
    "Abu Ja'far (al-Baqir)", "Imam al-Sadiq" — so a title found anywhere in the string is
    taken, and a string naming two is taken as naming neither.
    """
    found = set(identify(folded))
    if found:
        return found
    low = (name or "").lower()
    for imam in ROSTER:
        if any(hint in low for hint in imam["en"]):
            found.add(imam["id"])
    for form, ids in SHARED.items():
        if form in folded:
            found |= set(ids)
    return found


# Who each one's father is, for «عن أبيه عن جده» — the commonest relative clause there is.
# The second and third are brothers, so the third's father is the first, not the second.
FATHER_OF = {"i12": "i11", "i11": "i10", "i10": "i09", "i09": "i08", "i08": "i07",
             "i07": "i06", "i06": "i05", "i05": "i04", "i04": "i03", "i03": "i01",
             "i02": "i01", "i13": "i00"}
