#!/usr/bin/env python3
"""Build the narrator index from the people the decision record derives.

A narrator page shows what the Rijal books say about one man, and every line of it has to be
traceable: this is the stage where a verdict stops being a field on a merged profile and becomes
«al-Najashi, page 183, in these words». So each document carries, beside the person's names, one
`entries` record per source entry — the book, its author, the page, the heading as that book
writes it, the book's own words — and each verdict, generation and city points back at the entry
it was read from by its source key.

Only people holding an entry are indexed. The others are names in a chain with no entry anywhere
in the eight books (10,296 of them), and a page for such a name would say nothing but the name.
`--all` indexes them too, for a search that wants to say «this name appears, and nothing is
written about him».

The document id is the permanent person id, so a page's URL survives a rebuild: when two people
become one the loser redirects (identity.py's registry), and this loads the winner.

Usage:
    python3 scripts/narrators/index_people.py --dry-run          # build, write nothing
    python3 scripts/narrators/index_people.py --dry-run --show n000515
    python3 scripts/narrators/index_people.py --live
    python3 scripts/narrators/index_people.py --live --es-host http://HOST:9200
"""

import argparse
from collections import Counter, defaultdict
import json
import os
import re
import sys
import unicodedata

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_people import MAIN_BOOKS, is_entry, not_entries, unnamed_subjects  # noqa: E402
from grade_evidence import shown  # noqa: E402
import imams  # noqa: E402
import relation_evidence  # noqa: E402
import relations_join  # noqa: E402
import isnad_neighbours  # noqa: E402
from narrator_schema import (imam_generations, is_reasoned, normalize_arabic,  # noqa: E402
                             normalize_english, says_no_imam)
import transliterate  # noqa: E402
import field_check  # noqa: E402
import arabic_years  # noqa: E402

REPO = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
TMP = os.path.join(REPO, "tmp")
# the same table the page's band is drawn from, read rather than copied
CHRONOLOGY = os.path.join(REPO, "src", "main", "resources", "data", "chronology.json")
IDENTITY = os.path.join(TMP, "narrators_identity")
NORMALIZED = os.path.join(TMP, "narrators_normalized")
INDEX = "rewayaat_narrators"
BATCH = 300

# how a book is named on the page, and the order the books are shown in: the four early works
# first, as the tradition reads them, then the later collections
# slug, English title, English author, Arabic title, Arabic author. The Arabic is not a
# transliteration of the English: it is what the book is called, which is what an Arabic
# reader is looking for.
BOOKS = [
    ("najashi", "Rijāl al-Najāshī", "Aḥmad b. ʿAlī al-Najāshī (d. 450)",
     "رجال النجاشي", "أحمد بن علي النجاشي (ت ٤٥٠)"),
    ("fihrist", "Al-Fihrist", "Muḥammad b. al-Ḥasan al-Ṭūsī (d. 460)",
     "الفهرست", "محمد بن الحسن الطوسي (ت ٤٦٠)"),
    ("tusi", "Rijāl al-Ṭūsī", "Muḥammad b. al-Ḥasan al-Ṭūsī (d. 460)",
     "رجال الطوسي", "محمد بن الحسن الطوسي (ت ٤٦٠)"),
    ("kashshi", "Ikhtiyār Maʿrifat al-Rijāl", "Muḥammad b. ʿUmar al-Kashshī (4th c.)",
     "اختيار معرفة الرجال", "محمد بن عمر الكشي (القرن الرابع)"),
    ("duafa", "Kitāb al-Ḍuʿafāʾ", "Aḥmad b. al-Ḥusayn al-Ghaḍāʾirī (5th c.)",
     "كتاب الضعفاء", "أحمد بن الحسين الغضائري (القرن الخامس)"),
    ("ardabili", "Jāmiʿ al-Ruwāt", "Muḥammad b. ʿAlī al-Ardabīlī (d. 1101)",
     "جامع الرواة", "محمد بن علي الأردبيلي (ت ١١٠١)"),
    ("khoei", "Muʿjam Rijāl al-Ḥadīth", "Abū al-Qāsim al-Khoei (d. 1413)",
     "معجم رجال الحديث", "أبو القاسم الخوئي (ت ١٤١٣)"),
    ("mamaqani", "Tanqīḥ al-Maqāl", "ʿAbd Allāh al-Māmaqānī (d. 1351)",
     "تنقيح المقال", "عبد الله المامقاني (ت ١٣٥١)"),
]
BOOK_ORDER = {slug: n for n, (slug, *_rest) in enumerate(BOOKS)}
BOOK_TITLE = {slug: title for slug, title, _a, _ta, _aa in BOOKS}
BOOK_AUTHOR = {slug: author for slug, _t, author, _ta, _aa in BOOKS}
BOOK_TITLE_AR = {slug: title for slug, _t, _a, title, _aa in BOOKS}
BOOK_AUTHOR_AR = {slug: author for slug, _t, _a, _ta, author in BOOKS}
# Ibn al-Ghada'iri's book is shown under his name with Khoei's doubt beside it: Khoei held that
# «الكتاب المنسوب إلى ابن الغضائري لم يثبت», the book ascribed to him is not established as his.
# grades the phase 1 extraction read from a book's silence rather than from a verdict. They are
# kept on the entry, where «unknown» is honest, and left out of the verdicts a page leads with,
# where it would read as though the book had said it.
UNSTATED = {"not_assessed", "majhul", "assessed", "unknown (majhul)", None}
# how a grade is said on the page, and whether it speaks for or against the man
GRADE_WORDS = {"thiqa": ("thiqa — trustworthy", "for"), "saduq": ("ṣadūq — truthful", "for"),
               "hasan": ("ḥasan — good", "for"), "qawi": ("qawī — strong", "for"),
               "mukhtalaf_fih": ("disputed", "mixed"), "daif": ("ḍaʿīf — weak", "against"),
               "very_weak": ("very weak", "against"), "kadhdhab": ("a liar", "against"),
               "non_existent": ("no such narrator", "against"), "muhmal": ("passed over", "mixed")}
# The same verdicts in the words the books themselves use. An Arabic page saying «thiqa —
# trustworthy» would be transliterating a word its reader already has.
GRADE_WORDS_AR = {"thiqa": "ثقة", "saduq": "صدوق", "hasan": "حسن", "qawi": "قوي",
                  "mukhtalaf_fih": "مختلف فيه", "daif": "ضعيف", "very_weak": "ضعيف جدا",
                  "kadhdhab": "كذاب", "non_existent": "لا وجود له", "muhmal": "مهمل"}
# the Imams, in order, as a page names them
IMAM_NAMES = ["the Prophet", "ʿAlī", "al-Ḥasan", "al-Ḥusayn", "al-Sajjād", "al-Bāqir",
              "al-Ṣādiq", "al-Kāẓim", "al-Riḍā", "al-Jawād", "al-Hādī", "al-ʿAskarī",
              "the Mahdī"]
IMAM_NAMES_AR = ["النبي", "علي", "الحسن", "الحسين", "السجاد", "الباقر", "الصادق", "الكاظم",
                 "الرضا", "الجواد", "الهادي", "العسكري", "المهدي"]
# The rijālīs who wrote within reach of the men they assess. A verdict of theirs is a
# testimony; a verdict of al-Ardabīlī's or al-Khoei's is usually a reading of one.
EARLY_BOOKS = {"najashi", "fihrist", "tusi", "kashshi"}
# The compilers. Their books are indispensable and their own judgements are positions the
# field argues with — al-Khoei's tawthīq through Kāmil al-Ziyārāt is cited in every seminary
# — but their entries are built by quoting the early rijālīs first and adjudicating after.
# «قال النجاشي: ثقة» inside the Muʿjam is al-Najāshī's testimony, not a second one, and a
# page that counts it twice reports a consensus that does not exist.
LATE_BOOKS = {"ardabili", "khoei", "mamaqani"}
RELAYED = {"النجاشي": "najashi", "الفهرست": "fihrist", "رجال الشيخ": "tusi",
           "الكشي": "kashshi", "ابن الغضائري": "duafa", "الغضائري": "duafa",
           "ابن الغضائرى": "duafa"}
BOOK_NOTE = {"duafa": "The ascription of this book to Ibn al-Ghaḍāʾirī is disputed; al-Khoei "
                      "held it unproven («الكتاب المنسوب إلى ابن الغضائري… لم يثبت»)."}


# Which printed edition a page number belongs to, where the record knows. It knows for exactly
# five books, and only because their pages were read from an OpenITI digitisation whose header
# names the edition it was typed from (#META# 040.EdEDITOR, 041.EdNUMBER, 045.EdYEAR; for
# al-Najāshī the Shamela record line «المحقق … الناشر … الطبعة»). The page numbers on a
# printed entry are that digitisation's own page markers, so they are that edition's pages and
# nobody else's. Nothing is added to what the headers say: al-Najāshī's header leaves the
# edition number and year empty and so does this table. Jāmiʿ al-Ruwāt's header is NODATA in
# every edition field, and Māmaqānī and the Ḍuʿafāʾ were read by agents from files that name
# none, so those three carry no edition at all, and a page citing them cannot be placed in a
# printed copy from what this record holds. The same goes for any entry of a listed book that
# was not read from the digitisation (is_printed_entry false): its page came from somewhere
# unrecorded, and it is given no edition rather than a borrowed one.
EDITIONS = {
    "najashi": {"en": "ed. Mūsā al-Shubayrī al-Zanjānī, Muʾassasat al-Nashr al-Islāmī (Qom); "
                      "edition number and year not stated in the source",
                "ar": "تحقيق السيد موسى الشبيري الزنجاني، مؤسسة النشر الإسلامي (قم)؛ "
                      "رقم الطبعة وسنتها غير مذكورين في المصدر",
                "file": "najashi_rijal.txt"},
    "fihrist": {"en": "ed. Jawād al-Qayyūmī, 1st edition, Shaʿbān 1417 AH",
                "ar": "تحقيق الشيخ جواد القيومي، الطبعة الأولى، شعبان المعظم ١٤١٧",
                "file": "fihrist_pri.mARkdown"},
    "tusi": {"en": "ed. Jawād al-Qayyūmī al-Iṣfahānī, 1st edition, Ramaḍān 1415 AH",
             "ar": "تحقيق جواد القيومي الإصفهاني، الطبعة الأولى، رمضان المبارك ١٤١٥",
             "file": "tusi_rijal_pri.mARkdown"},
    "kashshi": {"en": "ed. Mahdī al-Rijāʾī (taḥqīq), with Mīr Dāmād al-Astarābādī "
                      "(taṣḥīḥ and taʿlīq), 1404 AH; edition number not stated",
                "ar": "تصحيح وتعليق: مير داماد الأسترابادي / تحقيق: السيد مهدي الرجائي، ١٤٠٤",
                "file": "kashshi_pri.mARkdown"},
    "khoei": {"en": "5th edition, 1413 AH / 1992 CE; editor and publisher not stated in the source",
              "ar": "الطبعة الخامسة، ١٤١٣ هـ / ١٩٩٢ م",
              "file": "khoei_mujam.txt"},
}
ENTRY_NUMBER = re.compile(r"printed entry (\d+)")
ENTRY_VOLUME = re.compile(r"الجزء (\d+)")


def slugify(name):
    """«Sahl ibn Ziyād» -> «sahl-ibn-ziyad», for a URL that says who it is about.

    The permanent id stays the authority — it survives two people becoming one — so the slug
    is only the readable half of the URL and nothing resolves by it.
    """
    folded = unicodedata.normalize("NFKD", name or "")
    plain = "".join(c for c in folded if not unicodedata.combining(c)).lower()
    # an apostrophe stands for a letter, not a word break: «Saʿīd» is sa'id, not «sa-id»
    plain = re.sub(r"['’ʿʾ`ʻ]", "", plain)
    return re.sub(r"(^-|-$)", "", re.sub(r"[^a-z0-9]+", "-", plain))[:60]


# An isnad names things that are not men. «أحدهما عليهما السلام» — «one of the two of them» —
# stands for either al-Baqir or al-Sadiq and is a convention, not a person; «بعض أصحابنا» is
# «some of our companions». Listed among a man's teachers they read as names, and 331 of them
# were.
CHAIN_FORMULA = ("أحدهما", "بعض أصحابنا", "بعض أصحابه", "بعض رجاله", "غير واحد",
                 "عدة من أصحابنا", "عمن حدثه", "من حدثه", "Subject of the entry",
                 # the same conventions where the extraction rendered them in English
                 "some of his companions", "some of our companions", "a number of our",
                 "one of our companions", "one of them", "a group of", "several of",
                 "someone who", "a man from", "unnamed")
# The Imams are transmitted from by everybody, so listing them beside a man's own masters
# buries the ones that identify him. True, and shown apart.
# Only an explicit signal counts. «أبو عبد الله» alone is one of the commonest kunyahs there
# is, and marking every man who carries it as an Imam would be worse than the problem — so a
# bare kunyah stays a person even where the Imam is probably meant, and the book's own words
# stand.
IMAM_MARK = re.compile(
    r"عليه السلام|عليهما السلام|عليهم السلام|\(ع\)|\(عج\)|صلى الله عليه"
    r"|الصادق|الباقر|الكاظم|الرضا|الجواد|الهادي|العسكري|السجاد|المهدي|زين العابدين"
    r"|رسول الله|النبي"
    r"|al-Sadiq|al-Baqir|al-Kazim|al-Rida|al-Jawad|al-Hadi|al-Askari|al-Sajjad|al-Mahdi"
    r"|the Prophet|Messenger of Allah", re.I)


def relation_kind(name):
    """What a name in a chain actually is: a man, an Imam, or a convention standing for one."""
    folded = name.casefold()
    if any(f.casefold() in folded for f in CHAIN_FORMULA):
        return "formula"
    if IMAM_MARK.search(name):
        return "imam"
    if len(name.split()) == 1:
        # «الفضيل» alone is a real reference and not an identification: it is whoever the
        # chain's reader already knows. Kept, and marked, because dropping it would hide a
        # link the book does make.
        return "bare"
    return "person"


# The corpus transliterates its own names. Two in five relation names are stored in Latin
# script — "Safwan ibn Yahya" beside «صفوان بن يحيى» — because phase 1 wrote whichever the
# passage gave it, and the two never met: al-Hakam b. Hakim listed Safwan as a stated pupil
# under the Latin spelling and again, as a separate man, under the Arabic one in the chain
# indexes. Every profile carries its own heading in both scripts, and its aliases in both, so
# the books supply the mapping themselves: 44,269 forms, 92.6% of them pointing at a single
# Arabic name, resolving 73.2% of the Latin relation names. What it cannot resolve is left as
# it stands rather than transliterated back by guess — and most of that remainder is the Imams,
# «Abu Abdullah (al-Sadiq)», which are marked as Imams and shown on their own line anyway.
TRANSLITERATED = {}
ARABIC_RANGE = re.compile(r"[\u0600-\u06ff]")


def fold_english(value):
    """A Latin name reduced to what two spellings of it share: «Abu 'Abd Allah» ~ «Abu Abdallah»."""
    folded = unicodedata.normalize("NFKD", value or "").encode("ascii", "ignore").decode()
    folded = folded.lower().replace("'", "").replace("`", "").replace("-", " ")
    folded = re.sub(r"\b(?:ibn|bin|b)\b", "ibn", folded)
    folded = re.sub(r"\bal\s+", "al", folded)
    return " ".join(folded.split())


def learn_transliterations(profiles):
    """Build the English-to-Arabic map from the headings and aliases the books print."""
    seen = defaultdict(Counter)
    for profile in profiles.values():
        couples = [(profile.get("primary_arabic_name"), profile.get("primary_english_name"))]
        arabic, english = profile.get("arabic_aliases") or [], profile.get("english_aliases") or []
        if len(arabic) == len(english):
            couples.extend(zip(arabic, english))
        for one, other in couples:
            if not (isinstance(one, str) and isinstance(other, str)):
                continue
            if ARABIC_RANGE.search(one) and not ARABIC_RANGE.search(other):
                seen[fold_english(other)][one.strip()] += 1
    TRANSLITERATED.clear()
    for form, holders in seen.items():
        # an English form two different men answer to is no use for joining them
        arabic, support = holders.most_common(1)[0]
        if len(holders) > 1 and support <= sum(holders.values()) / 2:
            continue
        # One heading pair is not a transliteration, it is a coincidence: a profile may hold
        # an English name and an Arabic name that are not the same words. «kayssan» came out
        # as «سعد بن أبي سعد المقبري» that way, «al-Sadiq» as «جعفر بن علي بن أبي طالب» —
        # the wrong Ja'far, printed on an Arabic page as his teacher. A second heading
        # agreeing, or a name long enough that a single coincidence is unlikely, is the bar.
        if support < 2 and len(form.split()) < 3:
            continue
        TRANSLITERATED[form] = arabic
    return len(TRANSLITERATED)


# Words that stand in the prose around a heading and never inside a man's filing name. An
# extraction that ran on past the name leaves them in it: «الحسين بن عثمان بن شريك العامري
# الوحيدي ثقة» ends in a verdict, and one heading runs to 360 words of al-Ardabīlī on who is
# called Abū Jaʿfar. A long name is not the fault — «سلامة بن محمد بن إسماعيل بن عبد الله بن
# موسى بن أبي الاكرم» is thirteen words of genuine lineage — so the cut is made at the prose
# and never at a length.
PROSE = re.compile(r'(?:كنية|المعروف|يعرف|يكنى|قدوة|سيدنا|مولانا|رحمه|رضي|قدس|عداده|وهو|'
                   r'وكان|قال|ذكره|روى|صاحب|المشايخ|ثقة|ضعيف|مجهول|عالم|فقيه|نزيل|أسند)')
AFTER_NASAB = ("بن", "ابن", "أبي", "أبو", "ابو")


# A heading that describes a group, a topic or a relation instead of naming a man. Al-Kashshī
# sets his sections off with these and the extraction read eight of them as narrators: «اخوة
# زرارة» is «the brothers of Zurāra», and its own text says so — «في اخوة زرارة حمران وبكير
# وعبد الملك وعبد الرحمن بني أعين». Transliterating it into «Akhua Zurarah» made a chapter
# heading look even more like a man. They are not published; the defect belongs to the
# extraction and is recorded there.
NOT_A_MAN = re.compile(r'^(?:في\s|باب\s|ذكر\s|[اأإ]خوة\s|بنو\s|بني\s|آل\s|من\s(?:روى|لم)|'
                       r'ما\s|[اأ]صحاب\s|فيمن\s|تسمية\s|من\s(?:اسمه|كنيته)\s)')


# «عبد» means «servant of» and never stands alone: a kunya ending there has been cut off
# mid-name. 133 of them print «أبو عبد» where the entry reads «أبو عبد الله» or «أبو عبد
# الرحمن», and the field audit called every one it met contradicted. Completed from a name
# the man himself carries where one extends it, and otherwise dropped, because half a kunya
# claims a man is called something nobody calls him.
HANGING = ("عبد", "ابو", "أبو", "ابي", "أبي", "ابا", "أبا", "ام", "أم", "ابن")


def whole_kunya(kunya, names):
    tokens = normalize_arabic(kunya).split()
    if not tokens or tokens[-1] not in HANGING:
        return kunya
    for name in sorted(names, key=len, reverse=True):
        folded = normalize_arabic(name).split()
        for start in range(len(folded) - len(tokens) + 1):
            if folded[start:start + len(tokens)] == tokens and len(folded) > start + len(tokens):
                return ' '.join(name.split()[start:start + len(tokens) + 1])
    return ""


# The Imams as an English generation sentence names them, and as Arabic writes them.
IMAM_EN_AR = {}
for _imam in imams.ROSTER:
    _short = _imam["name_en"].replace("Imam ", "").strip()
    # The laqab alone: a chapter heading says «أصحاب الصادق», never «أصحاب جعفر بن محمد
    # الصادق». The last word of the roster name is that laqab for the Imams, and the
    # Prophet and Fāṭima keep their own.
    _ar = _imam["name_ar"].split()[-1]
    for _form in {_short, _short.replace("al-", "al"), _short.split()[-1]}:
        if _form:
            IMAM_EN_AR[_form.lower()] = _ar
IMAM_EN_AR["the prophet"] = "النبي"
IMAM_EN_AR["prophet"] = "النبي"


def generation_ar(value):
    """«Companion of al-Baqir and al-Sadiq» as «من أصحاب الباقر والصادق»."""
    found = []
    lowered = (value or "").lower()
    for form, arabic in sorted(IMAM_EN_AR.items(), key=lambda kv: -len(kv[0])):
        if form in lowered and arabic not in found:
            found.append(arabic)
    if not found:
        return None
    return "من أصحاب " + " و".join(found)


def name_only(heading):
    """A heading with the prose cut off it, or the heading unchanged when it carries none."""
    if not heading:
        return heading
    words = heading.split()
    for position, word in enumerate(words):
        if position and words[position - 1] in AFTER_NASAB:
            continue   # «محمد بن المعروف» is a name, not a name followed by a remark
        if PROSE.fullmatch(word.strip('،,.:()')):
            cut = ' '.join(words[:position]).strip(' -،,')
            return cut if len(cut.split()) >= 2 else heading
    return heading


# A page name and a genealogy are not the same thing. Al-Najāshī opens his entry on Ibn
# Adhinah with twenty-five generations back to ʿAdnān, because an entry's first line is
# where a book establishes who it is talking about; every citation after it, al-Najāshī's
# own included, writes «عمر بن أذينة». Taking the anchor entry's opening line as the man's
# name put the whole nasab in the page's heading, in its slug, and in every chain list that
# names him. So: where the anchor's name runs past a nasab's length, the page takes the
# fullest heading a book actually files him under that still fits, and prints the long form
# once in the body under his other names.
NASAB_IS_LONG = 7      # «a bn b bn c bn d» is seven words — a name; eight is a lineage
HEADING_IS_ENOUGH = 3  # «عمر بن أذينة»: his own name and one link, at the very least


def _nasab_links(name):
    """The names in a nasab, with the «بن» and the prose words between them dropped."""
    return [normalize_arabic(word) for word in name.split()
            if word not in NOT_A_NAME_WORD and normalize_arabic(word)]


def short_heading(long_name, headings):
    """The fullest heading among «headings» that names the man without reciting his line.

    A candidate has to be his: it opens on the same ism, and every name in it is a name the
    long form carries, so «ابن أذينة البصري» — which adds a nisba the nasab never had — is
    not silently promoted over what the books print. Nothing qualifying means the long form
    stays: a nasab cut at a point no book cuts it is a name we invented.
    """
    words = long_name.split() if long_name else []
    if len(words) <= NASAB_IS_LONG:
        return long_name
    mine = _nasab_links(long_name)
    if not mine:
        return long_name
    best = None
    for heading in sorted(set(headings), key=lambda h: (-len(h.split()), h)):
        parts = heading.split()
        if not HEADING_IS_ENOUGH <= len(parts) <= NASAB_IS_LONG:
            continue
        if name_only(heading) != heading:
            continue
        links = _nasab_links(heading)
        if not links or links[0] != mine[0] or not set(links) <= set(mine):
            continue
        best = heading
        break
    return best or long_name


def english_for_heading(arabic, aliases, table):
    """A book's own spelling of this heading where one exists, else ours.

    Shortening the Arabic strands the English: the anchor's English name is the long form,
    or in two cases a truncation ending in an ellipsis, which is not a name at all. The
    spelling a book printed is still better than the one we generate, so the aliases are
    searched for it first, folded, before falling back to the table.
    """
    made = transliterate.english_for(arabic, table)
    if not made:
        return None, False
    def fold(text):
        return re.sub(r"[^a-z]", "", text.lower())
    target = fold(made)
    for alias in aliases:
        if fold(alias) == target:
            return alias, False
    return made, True


NOT_A_NAME_WORD = {"بن", "ابن", "أبو", "أبي", "أبا", "ال", "عن", "مولى"}


def another_mans_name(person, alias):
    """Is this «alias» the full name of somebody else, read out of the subject's entry?

    al-Khoei's entry on Zurara names Muhammad b. Muslim in the course of discussing a
    report, and phase 1 recorded «محمد بن مسلم» as a name of Zurara. The chain search was
    taught to ignore the aliases for exactly this reason, but his Names tab went on offering
    another man's name as his, which to a rijali reads as a merge we have made and cannot
    defend.

    A name is somebody else's when all three hold: it is a full name rather than a kunya or
    a bare ism — «أبو سعيد» and «أسلم» are how the books legitimately name a man by
    something his own heading does not contain — it is the heading some other person is
    filed under, and it shares no word with any name this person is headed by. 309 aliases
    in the corpus answer to that, from al-Khoei and Mamaqani above all, which is where a
    long discursive entry names the most other men.
    """
    words = normalize_arabic(alias).split()
    if len(words) < 3 or not ({"بن", "ابن"} & set(words)):
        return False
    form = normalize_arabic(alias)
    own = {normalize_arabic(n["value"]) for n in (person.get("primary_names") or [])
           if n.get("value")}
    if person.get("primary_arabic_name"):
        own.add(normalize_arabic(person["primary_arabic_name"]))
    if form in own:
        return False
    # «محمد بن مسلم مضمرة» is «محمد بن مسلم» with a word about the form of the report stuck
    # on the end, so the first three words of a name are what is looked up: the man they
    # belong to is filed under them whatever follows.
    if not (HOLDERS.get(form) or PREFIXES.get(" ".join(words[:3]))):
        return False
    mine = {word for name in own for word in name.split()
            if word not in NOT_A_NAME_WORD and len(word) > 1}
    theirs = {word for word in words if word not in NOT_A_NAME_WORD and len(word) > 1}
    return not (mine & theirs)


def another_mans_english(person, alias):
    """The same rule on the transliterated side, where «Muhammad bin Muslim» sat under
    Zurara for the same reason its Arabic did."""
    words = fold_english(alias).split()
    if len(words) < 3 or not ({"ibn", "bin", "b"} & set(words)):
        return False
    form = fold_english(alias)
    own = {fold_english(person.get("primary_english_name") or "")}
    own |= {fold_english(n["value"]) for n in (person.get("english_aliases") or [])
            if n.get("value") and n["value"] == person.get("primary_english_name")}
    if form in own:
        return False
    if not (ENGLISH_HOLDERS.get(form) or ENGLISH_PREFIXES.get(" ".join(words[:3]))):
        return False
    skip = {"ibn", "bin", "b", "abu", "abi", "al", "bint"}
    mine = {word for name in own for word in name.split() if word not in skip and len(word) > 1}
    theirs = {word for word in words if word not in skip and len(word) > 1}
    return not (mine & theirs)


def whole_arabic(name):
    """The full Arabic of a name where the corpus itself can give it, else None."""
    if ARABIC_RANGE.search(name):
        return normalize_arabic(name)
    arabic = TRANSLITERATED.get(fold_english(name))
    return normalize_arabic(arabic) if arabic else None


def one_man(name):
    """The key under which one man's name is counted, whichever script a book spelled it in."""
    return whole_arabic(name) or fold_english(name)


def sources_by_name(statements, stated_keys, key_of, text_for):
    """«Ibn Abi 'Umayr → [al-Najashi, Khoei]»: each man once, with who records the link.

    A teacher named by four books is better evidence than one named in a single chain note,
    and the page cannot show that unless the books travel with the name.

    Each link also carries whether a book *states* it of this man. Khoei indexes the chains a
    man stands in as well as writing him up, and the extraction read both: 96 of Zurara's
    «students» come from «وقع بعنوان زرارة في إسناد كثير من الروايات» — the list of 2,094
    chains he appears in — and not from anything a book says about his pupils. Both are true
    and they are not the same claim, so they are not mixed.
    """
    found = {}
    for item in statements:
        name = (item.get("value") or "").strip()
        if not name:
            continue
        kind = relation_kind(name)
        if kind == "formula":
            continue
        # keyed by the man, not by the spelling, so one pupil is one row
        held = found.setdefault(one_man(name), {"name": name, "kind": kind,
                                                "names": [], "absent": []})
        if ARABIC_RANGE.search(name) and not ARABIC_RANGE.search(held["name"]):
            held["name"] = name          # the book's own script wins the display
        elif not ARABIC_RANGE.search(held["name"]) and len(name) > len(held["name"]):
            held["name"] = name          # otherwise the fuller of two Latin spellings
        book = item.get("source_book")
        key = key_of(book, item.get("source_pages") or [])
        if key not in stated_keys:
            # an index of chains, not a book's statement: it names no passage to test
            held.setdefault("indexed", []).append(book)
            continue
        # A book states this of him; the question is whether its entry names the man. The
        # audit scored the relation fields lowest of the six, and at the level of one name
        # only 34.4% of 771 were upheld. Set aside the ones the passage does not hold and
        # the rest read 73.3%, at the cost of 7 names in 419 that an auditor would have kept.
        says = relation_evidence.names_him(name, text_for(key), whole_arabic, fold_english)
        (held["absent"] if says == "absent" else held["names"]).append(book)

    rows = []
    for held in found.values():
        books = held["names"] or held["absent"] or held.get("indexed") or []
        # An Arabic page should not read «روى عنه Abu al-Hasan Musa». Where the books
        # themselves spelled this man's name in Arabic somewhere in the corpus, that
        # spelling is what an Arabic reader gets; where they never did, the Latin stands.
        name_ar = (held["name"] if ARABIC_RANGE.search(held["name"])
                   else TRANSLITERATED.get(fold_english(held["name"])))
        rows.append({"name": held["name"], "name_ar": name_ar, "kind": held["kind"],
                     "books": [BOOK_TITLE.get(b, b) for b in dict.fromkeys(books)
                               if BOOK_TITLE.get(b, b)],
                     "books_ar": [BOOK_TITLE_AR[b] for b in dict.fromkeys(books)
                                  if BOOK_TITLE_AR.get(b)],
                     "stated": bool(held["names"]),
                     # a book states it, and the entry it states it in does not name the man
                     "unsaid": not held["names"] and bool(held["absent"])})
    return sorted(rows, key=lambda x: (not x["stated"], x["unsaid"],
                                       -len(x["books"]), x["name"]))[:200]


# One Imam, one row. A man's Imams used to be listed exactly as each book spelled them, so
# Zurāra's «Narrated from the Imams» line carried fifteen rows for four people —
# «أبي عبد الله (ع)», «Abu Abdullah (al-Sadiq)», «al-Sadiq», «الصادق», «Imam al-Sadiq» and
# «Ja'far al-Sadiq» all for the sixth — and meanwhile the bare «أبي جعفر», which in his chains
# is al-Bāqir, stood among his human teachers because relation_kind() declines to call a bare
# kunya an Imam. That caution is right in general (a bare «أبو عبد الله» in a stranger's list
# is often a man) and wrong for a companion of the Imams listing the man he narrated from, so
# the kunya is read against the Imams the subject himself kept company with: «أبي جعفر» is
# al-Bāqir or al-Jawād, and for a man whom the books place under al-Bāqir and al-Ṣādiq only
# al-Bāqir is possible. Where the subject's own Imams do not leave exactly one, the kunya
# stays a person, because the books leave it open too (imams.py: «left undecided»). It is
# applied to the teachers side only: an Imam never narrates from a narrator, so «أبو جعفر» in
# the list of a man's pupils (24 times) is a man, and is left alone.
BLESSING = re.compile(r"عليه السلام|عليهما السلام|عليهم السلام|عليه السّلام|\(ع\)|\(عج\)|"
                      r"صلى الله عليه وآله|صلى الله عليه وآله وسلم|^الإمام\s+|^الامام\s+|^امام\s+")
PAREN = re.compile(r"\(([^)]*)\)")
IMAM_STATS = Counter()


def _imam_ids(name):
    """Which Imams a relation name could mean: (ids from the base, ids it is qualified with).

    A qualifier in brackets outranks the rest of the name, because «Ali bin al-Husayn
    (al-Sajjad)» contains «al-Husayn» and would otherwise name the third Imam as well.
    """
    inside = " ".join(PAREN.findall(name))
    base = BLESSING.sub(" ", PAREN.sub(" ", name))
    ids = set(imams.identify(normalize_arabic(base)))

    def hints(text):
        found = set()
        low = text.lower()
        for imam in imams.ROSTER:
            if any(re.search(rf"(?<![a-z]){re.escape(h)}(?![a-z])", low) for h in imam["en"]):
                found.add(imam["id"])
        found |= set(imams.identify(normalize_arabic(BLESSING.sub(" ", text))))
        return found

    hinted = hints(inside) if inside else set()
    if not hinted and not ARABIC_RANGE.search(name):
        hinted = hints(name)
    return ids, hinted


def which_imam(name, subject_imams):
    """The one Imam this relation name means for this subject, or None."""
    ids, hinted = _imam_ids(name)
    found = ((hinted & ids) or hinted) if hinted else ids
    if len(found) == 1:
        return next(iter(found))
    if found and subject_imams:
        mine = {i for i in found if imams.BY_ID[i]["ordinal"] in subject_imams}
        if len(mine) == 1:
            return next(iter(mine))
    return None


def settle_imams(rows, subject_imams, teachers_side):
    """Move an Imam out of the human rows and give each Imam one row under one name."""
    out, held = [], {}
    # An Imam the books already name outright on this side is one the man is known to have
    # narrated from, even where his companionship list does not include him: Zurāra's own
    # generations stop at al-Kāẓim, while al-Fihrist says «عن علي بن الحسين (السجاد)», and the
    # bare «علي بن الحسين» beside it is the same man, not a second teacher.
    named = {imams.BY_ID[i]["ordinal"] for i in (which_imam(r["name"], subject_imams)
                                                 for r in rows if r["kind"] == "imam") if i}
    subject_imams = set(subject_imams) | named
    for row in rows:
        iid = None
        if row["kind"] == "imam":
            iid = which_imam(row["name"], subject_imams)
        elif teachers_side and subject_imams and row["kind"] in ("person", "bare"):
            ids, hinted = _imam_ids(row["name"])
            # a parenthesis that is not an Imam's name («Ahmad ibn Muhammad's father») marks a
            # man, and a name with a qualifier the roster does not know is left as written
            if PAREN.search(row["name"]) and not hinted:
                iid = None
            else:
                iid = which_imam(row["name"], subject_imams)
                # a name that is also an ordinary man's («علي بن الحسين», «جعفر بن محمد») is
                # taken for the Imam only when the subject's own books put him in that Imam's
                # company; otherwise it is whichever man of that name the book meant
                if iid and imams.BY_ID[iid]["ordinal"] not in subject_imams:
                    iid = None
            if iid:
                IMAM_STATS["a human teacher row that was an Imam"] += 1
        if not iid:
            out.append(row)
            continue
        imam = imams.BY_ID[iid]
        mate = held.get(iid)
        if mate is None:
            mate = dict(row, name=imam["name_en"], name_ar=imam["name_ar"], kind="imam",
                        imam_id=iid, books=[], books_ar=[], stated=False, unsaid=True)
            held[iid] = mate
            out.append(mate)
        else:
            IMAM_STATS["Imam rows folded into one"] += 1
        for book in row["books"]:
            if book not in mate["books"]:
                mate["books"].append(book)
        for book in row.get("books_ar") or []:
            if book not in mate["books_ar"]:
                mate["books_ar"].append(book)
        mate["stated"] = mate["stated"] or row["stated"]
        mate["unsaid"] = mate["unsaid"] and row["unsaid"]
    return out


def join_names(names):
    """«A, B and C» — a list as a sentence reads it."""
    names = list(names)
    if len(names) <= 1:
        return names[0] if names else ""
    return ", ".join(names[:-1]) + " and " + names[-1]


ARABIC_DIGITS = str.maketrans("0123456789", "٠١٢٣٤٥٦٧٨٩")


def arabic_digits(value):
    """«150» -> «١٥٠»: an Arabic page reads its own numerals."""
    return str(value).translate(ARABIC_DIGITS) if value is not None else None


def join_names_ar(names):
    """«الصادق والكاظم والرضا» — wa- binds to the word that follows it, so no space after it."""
    names = [n for n in names if n]
    return " و".join(names)


# The city clause of the Arabic blurb, for the forms the books actually use. The field is free
# text in eight books' hands — 5,700 distinct values, half of them tribes or nisbas — so
# anything unmapped is left out of the Arabic sentence rather than transliterated back by guess.
# The tribes and the towns the books name most often, in the Arabic they were named in.
# These are not translations: «Ansar» and «Banu Asad» are English spellings of «الأنصار» and
# «بني أسد», and an Arabic page printing the spelling back is the error. 2,588 city facts had
# no Arabic at all, and these are the forms that account for most of them.
TRIBES_AND_TOWNS_AR = {
    "ansar": "الأنصار", "ansari": "الأنصاري", "hamdan": "همدان", "hamdani": "الهمداني",
    "banu asad": "بنو أسد", "asad": "أسد", "asadi": "الأسدي",
    "azd": "الأزد", "azdi": "الأزدي", "banu hashim": "بنو هاشم", "hashimi": "الهاشمي",
    "quraysh": "قريش", "qurashi": "القرشي", "shayban": "شيبان", "shaybani": "الشيباني",
    "banu shayban": "بنو شيبان", "tamim": "تميم", "tamimi": "التميمي",
    "bajila": "بجيلة", "bajali": "البجلي", "khath'am": "خثعم", "kinda": "كندة",
    "kindi": "الكندي", "khuza'a": "خزاعة", "khuzai": "الخزاعي", "juhayna": "جهينة",
    "nakha": "النخع", "nakhai": "النخعي", "hadrami": "الحضرمي", "ijli": "العجلي",
    "mosul": "الموصل", "mawsili": "الموصلي", "ray": "الري", "rayy": "الري",
    "razi": "الرازي", "astarabad": "أستراباد", "astarabadi": "الأستراباذي",
    "amili": "العاملي", "jabal amil": "جبل عامل", "hilla": "الحلة", "hilli": "الحلي",
    "isfahan": "أصفهان", "isfahani": "الأصفهاني", "ahwaz": "الأهواز", "ahwazi": "الأهوازي",
    "sana": "صنعاء", "yemen": "اليمن", "yaman": "اليمن", "yamani": "اليماني",
    "tus": "طوس", "tusi": "الطوسي", "jurjan": "جرجان", "jurjani": "الجرجاني",
    "sarakhs": "سرخس", "nishapur": "نيسابور", "naysaburi": "النيسابوري",
    "wasit": "واسط", "wasiti": "الواسطي", "anbar": "الأنبار", "madain": "المدائن",
    "sabat": "ساباط", "sabati": "الساباطي", "qummi": "قم", "baghdadi": "بغداد",
}

CITY_AR = {
    "kufa": "الكوفة", "kufan": "الكوفة", "kufi": "الكوفة", "basra": "البصرة", "basran": "البصرة",
    "qum": "قم", "qom": "قم", "qummi": "قم", "baghdad": "بغداد", "medina": "المدينة",
    "madinah": "المدينة", "madina": "المدينة", "madani": "المدينة", "mecca": "مكة", "makkah": "مكة",
    "makki": "مكة", "nishapur": "نيشابور", "naysabur": "نيشابور", "rayy": "الري", "razi": "الري",
    "hamadan": "همدان", "qazwin": "قزوين", "qazvin": "قزوين", "isfahan": "أصفهان", "wasit": "واسط",
    "najaf": "النجف", "khurasan": "خراسان", "balkh": "بلخ", "hilla": "الحلة", "bahrain": "البحرين",
    "egypt": "مصر", "misr": "مصر", "jurjan": "جرجان", "ahwaz": "الأهواز", "yemen": "اليمن",
    "marw": "مرو", "merv": "مرو", "mada'in": "المدائن", "madain": "المدائن", "samarra": "سامراء",
    "sijistan": "سجستان", "tus": "طوس", "bukhara": "بخارى", "samarqand": "سمرقند", "aleppo": "حلب",
    "halab": "حلب", "damascus": "دمشق", "sham": "الشام", "syria": "الشام", "jabal amil": "جبل عامل",
}


def city_ar(value):
    """The city in Arabic, or nothing: a name we cannot vouch for is better left unsaid."""
    if not value:
        return None
    if re.search(r"[\u0600-\u06ff]", value):
        return value
    # «Kufa / Shayban (Tribe)» is a city and a tribe joined by the extraction. The place
    # comes first, so the first part is what a reader is told; the tribe is in the entry.
    key = re.sub(r"^(al-|el-)", "", value.strip().lower())
    key = re.sub(r"\s*\(.*\)\s*", "", key).strip(" .")
    key = key.split("/")[0].split(",")[0].strip(" .")
    key = re.sub(r"^(al-|el-)", "", key)
    return CITY_AR.get(key) or TRIBES_AND_TOWNS_AR.get(key)


def load(path):
    with open(path) as handle:
        return json.load(handle)


def imam_years():
    """Each Imam's ordinal to the years his period ran, from the page's own chronology."""
    if not os.path.exists(CHRONOLOGY):
        return {}
    return {e["order"]: (e["fromAh"], e["toAh"]) for e in load(CHRONOLOGY).get("eras", [])}


YEARS = None
TRANSLITERATION = {}
DEATH_YEARS = {}
UNNAMED = set()  # al-Ṭūsī's «باب من لم يسم» keys, filled in main()
HOLDERS = {}
ENGLISH_HOLDERS = {}
PREFIXES = {}
ENGLISH_PREFIXES = {}
BOOKS_WITH_TEXT = set()


def profiles():
    found = {}
    for slug, *_rest in BOOKS:
        path = os.path.join(NORMALIZED, f"{slug}.json")
        if os.path.exists(path):
            for profile in load(path):
                found[f"{slug}:{profile['source_index']}"] = profile
    return found


# What the extraction left in the text and no reader should see: «PageV01P324» is a page
# marker from the OpenITI source and «~:section:» is its own structural mark. 288 and 772 of
# them stand inside entries, in both scripts, and a page printing a book's own words should
# print the book's own words.
LEFTOVER = re.compile(r'\s*(?:PageV\d+P\d+|~:[a-z_]+:)\s*')
# More of the same OpenITI apparatus, measured over the 32,614 entries before it was stripped.
# «ms0115» is the digitiser's manuscript-page milestone, 599 of them in 597 Jāmiʿ al-Ruwāt
# entries, each standing between two spaces inside a sentence («محمد بن علي ms0115 بن محبوب»),
# so a reader of al-ʿAṭṭār's entry met a Latin token in the middle of a name; extract_openiti.py
# already counts the same token as noise in the books it re-read. «|PARATEXT|» (5, Kashshī) and
# «NoteV00P195N2441» (3, Ṭūsī) are the digitiser's tags for a colophon and a footnote anchor,
# «[empty]» (1) is its placeholder, and «&quot;» (14, Jāmiʿ) is an HTML entity for a quotation
# mark. The «$» (467 in 194 Khoei entries) is OpenITI's record mark and is followed by a digit
# every single time («الحديث $ 417», «$ 1 - أنه روى»): the number is the book's, the dollar is
# not, so only the dollar goes.
# What is NOT removed, deliberately: the sigla «(جش)», «(لم)», «[ست]», «(ق)». They look like
# noise and are not. Jāmiʿ al-Ruwāt writes them itself, as its abbreviations for the book it is
# quoting (جش al-Najāshī, ست al-Fihrist, كش al-Kashshī, صه al-Khulāṣa, ق al-Ṣādiq's chapter in
# al-Ṭūsī), 6,527 times in 4,554 entries; Khoei repeats them when he quotes Jāmiʿ or Mamaqani
# («م - كش - جش - جخ»). They are the provenance of each clause, and a scholar reading «ثقة (جش)»
# is reading the author's own citation. link_entry_books.py reads them as such.
OPENITI_MARKS = re.compile(r'\s*(?:ms\d{3,4}|NoteV\d+P\d+N\d+|\|PARATEXT\||\[empty\])\s*')


def clean_text(text):
    """The entry's words without the digitiser's marks in them (see OPENITI_MARKS)."""
    text = LEFTOVER.sub(" ", text)
    text = OPENITI_MARKS.sub(" ", text)
    text = re.sub(r"\$\s*(?=\d)", "", text)
    return text.replace("&quot;", '"').strip()


def text_of(profile, arabic=True):
    """The source's own words: the printed entry where there is one, else what was read."""
    said = profile.get("source_assessments") or []
    field = "assessment_ar" if arabic else "assessment_en"
    printed = "printed_entry" in (profile.get("flags") or ())
    for assessment in (reversed(said) if printed else said):
        if (assessment or {}).get(field):
            return clean_text(assessment[field])
    return None


def values(person, field):
    """A person's statements of one kind, each with the book and pages that say it."""
    out = []
    for item in person.get(field) or []:
        if isinstance(item, dict):
            out.append(item)
        elif item:
            out.append({"value": item})
    return out


def _same_value(value):
    """Two statements of one fact, however the book spelled it: «Kufan (city)» ~ «Kufa»."""
    if not isinstance(value, str):
        return value
    if ARABIC_RANGE.search(value):
        return normalize_arabic(value)
    # CITY_AR already holds the forms the books use — kufa, kufan, kufi all point at
    # «الكوفة» — so the place names fold through it rather than through a second rule of
    # my own. A first attempt stripped a trailing «an» and made «Kufan» and «Kufa» differ.
    place = city_ar(value)
    if place:
        return normalize_arabic(place)
    folded = re.sub(r"\s*\(.*?\)\s*", " ", value).strip().lower()
    folded = re.sub(r"^(al-|el-)", "", folded).strip(" .")
    return " ".join(folded.split()) or value.lower()


def entry_key_for(person, book, pages, entry_keys):
    """The entry a statement was read from: its book, and its pages if they say which."""
    same_book = [k for k in entry_keys if k.split(":")[0] == book]
    if len(same_book) == 1:
        return same_book[0]
    for key in same_book:
        held = set(person["pages_of"].get(key) or ())
        if pages and held and set(pages) & held:
            return key
    return None


# Given names so common that holding one alone proves nothing, however the corpus counts
# them. A chain saying «عن محمد» names one of hundreds of men.
COMMON_ISM = {normalize_arabic(n) for n in (
    "محمد", "علي", "أحمد", "الحسن", "الحسين", "جعفر", "إبراهيم", "إسماعيل", "موسى",
    "عبد الله", "عبد الرحمن", "حماد", "يونس", "سعد", "سعيد", "عمر", "عمرو", "يحيى",
    "داود", "زيد", "صالح", "عيسى", "هارون", "سليمان", "يعقوب", "يوسف", "أبان", "بكر")}


# A name that names a group or a rank rather than a man. Measured over the 38,977 Arabic names
# the index holds: family and tribe names are rare (5 names on 4 people: «آل أعين» on Zurāra and
# on his brother Ḥumrān, «بنو جعفر», «بني عدى»; and 5 English ones, «Banu Ayn»), while bare title
# words are commoner (27 names on 20 people: «القاضي» 8, «الشيخ» 4, «الشريف» 3, «السيد» 2,
# «المحدث», «المفسر», «الفقيه», «النسابة», «الثقة», «مولى»). Neither is wrong to hold. «آل أعين»
# is exactly how the books refer to Zurāra's clan, and «الشيخ» is how al-Mufīd and al-Ṭūsī are
# both cited, so a reader who searches either must still land on the man, and deleting them
# would also erase the fact that a book used them. But listed among «names» they read as
# something he was called, and «المحدّث» is not what anybody called Zurāra: it is a descriptor
# a book attached to him. So they are kept, in their own labelled list (`names_group_ar`,
# `names_group_en`), kept in the searched forms (`normalized_ar`, `normalized_en`), and left out
# of `names_ar` and `names_en`, which are the names he bore.
# Not in the list on purpose: «الصدوق» (a laqab that really is one man's name, Ibn Bābawayh's),
# and any word that is part of this man's own heading, because then it is his ism: the Sayyid
# b. Muḥammad whose filing name is «السيد بن محمد» keeps «السيد».
FAMILY_EN = re.compile(r"^(?:banu|bani|banī|aal|ahl|awlad|house of|family of|sons of)\b", re.I)
TITLE_WORDS_AR = {normalize_arabic(w) for w in (
    "المحدث", "الفقيه", "المفسر", "النسابة", "الثقة", "القاضي", "الشيخ", "السيد", "الشريف",
    "مولى", "الراوي", "العالم", "الإمام", "الأديب", "المتكلم")}


def not_a_personal_name(value, headings_folded, english=False):
    """«family» or «title» if this alias names a group or a rank, else None."""
    if english:
        return "family" if FAMILY_EN.match(value.strip()) else None
    text = value.strip()
    # «ال» alone is the article, so «الكوفي» must not read as a family: the family prefixes
    # need a following word, and «آل» is matched before normalisation, which would fold it to «ال»
    if re.match(r"^(?:آل|بنو|بني|بنى|أولاد|اولاد|أهل|اهل)\s+\S", text):
        return "family"
    folded = normalize_arabic(text)
    if folded in TITLE_WORDS_AR:
        own = {w for h in headings_folded for w in h.split()}
        if folded not in own:
            return "title"
    return None


def searchable(headings, aliases, holders):
    """The names safe to search the chains by.

    A chain says «عن زرارة», not «عن زرارة بن أعين». Requiring three words — which is what
    the service did — found Zurara 84 narrations out of well over a thousand. Length was
    never what made a name safe to search; one man holding it was. So a short heading is
    searched when exactly one narrator in the corpus is headed by it, and a bare given name
    never is, however the count falls: «محمد» looks unique here only because the other
    Muhammads are headed more fully.

    Headings alone are not enough either. Muhammad b. Muslim is headed «محمد بن مسلم
    الطائفي» and «محمد بن مسلم بن رباح», never by the bare «محمد بن مسلم» the chains
    actually use — that sits among his aliases — and searching headings alone found him
    nothing at all. But an alias is where another man's name strays in: Khoei's entry on
    Zurara left «محمد بن مسلم» among his, and searching it put a thousand of the wrong
    man's narrations on Zurara's page.

    So an alias is searched when it is a shortening of one of this man's own headings. The
    bare «محمد بن مسلم» opens «محمد بن مسلم الطائفي», so it is his; on Zurara it opens
    nothing he is headed by, so it is not.
    """
    folded = [normalize_arabic(h) for h in headings]

    def allowed(name, from_heading):
        words = normalize_arabic(name).split()
        if not words:
            return False
        plain = normalize_arabic(name)
        if not from_heading and not any(h == plain or h.startswith(plain + " ") for h in folded):
            return False
        if len(words) >= 3:
            return True
        return holders.get(plain, 99) == 1 and plain not in COMMON_ISM

    out = [h for h in headings if allowed(h, True)]
    seen = {normalize_arabic(h) for h in out}
    for alias in aliases:
        if normalize_arabic(alias) not in seen and allowed(alias, False):
            out.append(alias)
            seen.add(normalize_arabic(alias))
    return out


def where_in_the_book(book, profile):
    """What lets a reader find this entry in a printed copy: its number, volume and edition.

    The number is the one the edition prints before the entry («printed entry 945» is
    al-Najāshī's 945), which extract_openiti.py recorded in the profile's notes when it read the
    heading; only a profile read from the digitisation (flag printed_entry) has one, and the
    other entries of a book are given none rather than the number an agent once wrote down.
    For al-Ṭūsī and al-Fihrist it is the edition's running number, not the per-chapter number
    al-Khoei cites them by. The volume is stated for al-Khoei («الجزء 19», of twenty-four, where
    a bare page 32 is ambiguous across the set) and for Māmaqānī (source_volume); the other
    books are one volume or do not say.
    """
    printed = "printed_entry" in (profile.get("flags") or ())
    notes = profile.get("notes") or ""
    number = ENTRY_NUMBER.search(notes) if printed else None
    volume = ENTRY_VOLUME.search(notes) if printed and book == "khoei" else None
    edition = EDITIONS.get(book) if printed else None
    return {
        "entry_number": int(number.group(1)) if number else None,
        "volume": (int(volume.group(1)) if volume
                   else profile.get("source_volume") if book == "mamaqani" else None),
        "edition_en": edition["en"] if edition else None,
        "edition_ar": edition["ar"] if edition else None,
        "edition_file": edition["file"] if edition else None,
    }


def mark_same_work(entries):
    """Say so when two entries of one man are the same book, rather than two books.

    al-Khoei files Muḥammad b. Yaḥyā al-ʿAṭṭār under «العطار», under «الأشعري» and again under
    «أبو جعفر العطار», three headings with three different texts (571, 590 and 2,993
    characters), two of them on p. 32. They are not one entry shown twice, so they are not
    merged: merging would splice three of a compiler's separate discussions into a text he
    never wrote. But a page listing «Muʿjam Rijāl al-Ḥadīth p. 32» twice, unmarked, reads as two
    books. Each entry therefore carries which of the book's entries for this man it is, how
    many there are, and the headings the others are filed under, so the page can print «a
    second heading in the same work».
    """
    by_book = defaultdict(list)
    for entry in entries:
        by_book[entry["book"]].append(entry)
    for group in by_book.values():
        for n, entry in enumerate(group, 1):
            entry["book_entry_index"] = n
            entry["book_entry_total"] = len(group)
            entry["same_work_other_headings"] = [o["heading_ar"] for o in group
                                                 if o is not entry and o["heading_ar"]]
            entry["shares_page_with"] = [o["source_key"] for o in group if o is not entry
                                         and set(o["pages"] or ()) & set(entry["pages"] or ())]


def document(person, source, headless):
    """One narrator, as a page will read him."""
    keys = sorted(person["source_keys"],
                  key=lambda k: (BOOK_ORDER.get(k.split(":")[0], 99), int(k.split(":")[1])))
    # An entry of al-Ṭūsī's «باب من لم يسم» is about the unnamed man, not this one: it stays
    # joined to him and is not shown as his notice.
    entry_keys = [k for k in keys if k in source and is_entry(k, source[k], headless)
                  and k not in UNNAMED]
    person["pages_of"] = {k: (source[k].get("source_pages") or []) for k in keys if k in source}

    entries = []
    for key in entry_keys:
        book, profile = key.split(":")[0], source[key]
        pages = profile.get("source_pages") or []
        entries.append({
            "source_key": key,
            "book": book,
            "book_title": BOOK_TITLE.get(book, book),
            "book_title_ar": BOOK_TITLE_AR.get(book),
            "book_author": BOOK_AUTHOR.get(book),
            "book_author_ar": BOOK_AUTHOR_AR.get(book),
            "book_note": BOOK_NOTE.get(book),
            "heading_ar": profile.get("primary_arabic_name"),
            "heading_en": profile.get("primary_english_name"),
            "pages": pages,
            "page_first": pages[0] if pages else None,
            "text_ar": text_of(profile),
            "text_en": text_of(profile, arabic=False),
            "grade": profile.get("reliability_grade"),
            # the grade in words, as the verdicts use: a badge reading «VERY_WEAK» is our
            # vocabulary showing through, not the book's
            "grade_said": GRADE_WORDS.get(profile.get("reliability_grade"), (None, None))[0],
            "grade_said_ar": GRADE_WORDS_AR.get(profile.get("reliability_grade")),
            # The entry's own leaning («for», «against», «mixed»), from the same table said_by
            # uses, so a chip can be tinted from the entry itself. said_by is built only from
            # verdicts traced to a passage that holds the grade's word, so an entry can carry a
            # grade (Sahl b. Ziyād's «كذاب») that has no said_by row at all, and a chip that
            # looked its colour up there rendered grey beside green thiqa and red daif.
            "grade_leaning": GRADE_WORDS.get(profile.get("reliability_grade"), (None, None))[1],
            # Which Imam this entry places him under, in short. The raw statement is the book's
            # chapter heading — «أصحاب أبي جعفر محمد بن علي الثاني عليهما السلام» — and printed
            # under all three of al-Tusi's listings it said the same thing three times in the
            # longest possible way. The names are already in the hero; what an entry adds is
            # which of them this one is filed under.
            "under_imams": [IMAM_NAMES[n] for n in sorted(imam_generations(profile.get("generation")))
                            if n < len(IMAM_NAMES)],
            "under_imams_ar": [IMAM_NAMES_AR[n]
                               for n in sorted(imam_generations(profile.get("generation")))
                               if n < len(IMAM_NAMES_AR)],
            "generation": profile.get("generation"),
            "is_printed_entry": "printed_entry" in (profile.get("flags") or ()),
            **where_in_the_book(book, profile),
        })
    mark_same_work(entries)

    def traced(field, key_name="value"):
        """What the books state about him, each line attributed to the entry that states it.

        A line here names a book, so it has to be a line that book can be held to. Anything
        read from a record the page cannot open is left out — the same rule the verdicts and
        the chain links answer to — because «Kufa — Tanqih al-Maqal» beside a book with no
        entry in the corpus invites a reader to go and check a page that is not there.

        Values that differ only in spelling are one line. The city field is free text in eight
        books' hands, so one man carried «Kufan (city)» from al-Najashi and «Kufa» from
        Mamaqani and the page printed his city twice.
        """
        out, seen = [], {}
        for item in values(person, field):
            book, pages = item.get("source_book"), item.get("source_pages") or []
            value = item.get(key_name) or item.get("value")
            if not value:
                continue
            key = entry_key_for(person, book, pages, entry_keys)
            if not key:
                continue
            same = seen.get(_same_value(value))
            if same is not None:
                if BOOK_TITLE.get(book, book) not in same["also"]:
                    same["also"].append(BOOK_TITLE.get(book, book))
                    if BOOK_TITLE_AR.get(book):
                        same["also_ar"].append(BOOK_TITLE_AR[book])
                continue
            row = {key_name: value, "book": book, "book_title": BOOK_TITLE.get(book, book),
                   "book_title_ar": BOOK_TITLE_AR.get(book), "pages": pages,
                   "source_key": key, "also": [], "also_ar": []}
            # the city in Arabic where CITY_AR vouches for it, so an Arabic page does not
            # print «Kufan (city)» in the middle of a line of Arabic
            if field == "city_or_tribe":
                row["value_ar"] = city_ar(value)
            # «Companion of al-Sadiq» is not a thing a book says; it is what the extraction
            # made of a chapter the book wrote in Arabic. The Arabic is not translated back
            # — it is composed from the Imams the sentence names, in the forms the roster
            # carries, so an Arabic page reads «من أصحاب الباقر والصادق».
            if field == "generations":
                row["value_ar"] = generation_ar(value)
            seen[_same_value(value)] = row
            out.append(row)
        return out

    # Only what a book says. 45,729 of the 53,552 rows this used to publish were read from a
    # book's silence and then handed out under that book's name with its page numbers: 35,760
    # «not_assessed», and 9,969 «majhul», which is a positive claim — the man is unknown —
    # that no rijālī made. The connector already refused them («a grade read from a book's
    # silence is not its verdict») and the page never showed them, but the REST API handed
    # out the document whole, so an academic reading it would have found «Rijāl al-Najāshī:
    # majhūl, pp. 5–6» where al-Najāshī wrote nothing of the sort. A book that says nothing
    # produces no row; its silence is already in the entry, and in «no book grades him».
    verdicts = []
    for grade in values(person, "reliability_grades"):
        if grade.get("grade") in UNSTATED:
            continue
        book, pages = grade.get("source_book"), grade.get("source_pages") or []
        verdicts.append({"grade": grade.get("grade"), "said": grade.get("raw"),
                         "stated": True,
                         "book": book, "book_title": BOOK_TITLE.get(book, book),
                         "book_title_ar": BOOK_TITLE_AR.get(book), "pages": pages,
                         "book_note": BOOK_NOTE.get(book),
                         "source_key": entry_key_for(person, book, pages, entry_keys)})

    # One position per book, not one per profile. A book is read into several profiles — Khoei's
    # entry spans five pages and phase 1 read each — and the same verdict came back once per
    # profile, so a page showed «Tanqīḥ al-Maqāl: thiqa» three times. Where a book really does
    # take two positions, as Mamaqani does in quoting al-Najashi's «weak» before concluding
    # «thiqa» himself, both are kept and the disagreement is marked.
    # A verdict is a book's position only when we can show the passage it stands in. Half of
    # the stated verdicts in the corpus — 3,735 of 7,822 — cannot be tied to any entry the page
    # holds, and 621 pages carried a reliability badge resting on nothing a reader could open.
    # They come from phase 1 records whose «pages» are a five-page window rather than a
    # citation: Tanqih al-Maqal has no printed entry in the corpus at all, so every one of its
    # verdicts is of this kind, and Khoei's sub-records carry «thiqa» into passages whose whole
    # text is «أقول: هو متحد مع ما تقدم عليه». The attribute audit measured such records at 37%
    # against 65% for entries, so presenting them as a book's word was wrong about two times
    # in three. They are kept and shown apart, named for what they are.
    stands, unsourced = {}, {}

    def set_aside(verdict, reason):
        """A grade the page holds but cannot show the reader a passage for.

        «No passage quoted» covers two different situations and a reader is owed the
        difference. Tanqih al-Maqal is not in this corpus at all — not one of its entries
        was ever extracted, though 736 grades are attributed to it — so no passage of it
        exists here to quote and none ever will until the book is added. That is a fact
        about the corpus and it is said as one. The rest are books we do hold, where the
        entry we stored does not carry the word.
        """
        if reason == "no_entry" and verdict["book"] not in BOOKS_WITH_TEXT:
            reason = "book_not_held"
        held = unsourced.setdefault(verdict["book"], {
            "book": verdict["book"], "book_title": verdict["book_title"],
            "book_title_ar": verdict["book_title_ar"],
            "grades": [], "grades_ar": [], "reason": reason})
        if held["reason"] != reason:
            held["reason"] = "either"
        word = GRADE_WORDS.get(verdict["grade"], (verdict["said"], "mixed"))[0]
        if word not in held["grades"]:
            held["grades"].append(word)
        word_ar = GRADE_WORDS_AR.get(verdict["grade"])
        if word_ar and word_ar not in held["grades_ar"]:
            held["grades_ar"].append(word_ar)

    for verdict in verdicts:
        if not verdict["stated"]:
            continue
        if not verdict["source_key"]:
            set_aside(verdict, "no_entry")
            continue
        # The passage is citable; the question is whether it says it. A verdict is read from
        # a word, and the audit of 2026-10-10 found that where the grade's own word is absent
        # from the cited passage the claim is wrong — 36 of 36 where no verdict word stood at
        # all, 18 of 22 where only another grade's word did. 1,125 of the 4,087 verdicts the
        # page presented as a book's position are of those two kinds, 816 of them Khoei's.
        if not shown(verdict["grade"], text_of(source[verdict["source_key"]])):
            set_aside(verdict, "not_in_passage")
            continue
        book = verdict["book"]
        held = stands.setdefault(book, {"book": book, "book_title": verdict["book_title"],
                                        "book_title_ar": verdict["book_title_ar"],
                                        "book_note": verdict["book_note"], "positions": []})
        already = next((p for p in held["positions"] if p["grade"] == verdict["grade"]), None)
        if already:
            already["pages"] = sorted(set(already["pages"]) | set(verdict["pages"] or []))
            continue
        word, leaning = GRADE_WORDS.get(verdict["grade"], (verdict["said"], "mixed"))
        # Whom a compiler is quoting, where his entry names an earlier rijālī. His position
        # is still shown — he did take it — but it is not counted a second time as though
        # another book had spoken.
        relays, relays_ar = [], []
        if book in LATE_BOOKS:
            passage = normalize_arabic(text_of(source[verdict["source_key"]]) or "")
            # Named for the reader, not keyed: «quoting Rijāl al-Najāshī», not «najashi».
            # In both scripts: an Arabic page was printing «نقلاً عن Ikhtiyār Maʿrifat
            # al-Rijāl», which is the leak this very feature introduced.
            quoted = sorted({code for name, code in RELAYED.items()
                             if normalize_arabic(name) in passage})
            relays = [BOOK_TITLE.get(c, c) for c in quoted]
            relays_ar = [BOOK_TITLE_AR[c] for c in quoted if BOOK_TITLE_AR.get(c)]
        held["positions"].append({"grade": verdict["grade"], "said": word,
                                  "said_ar": GRADE_WORDS_AR.get(verdict["grade"]),
                                  "leaning": leaning, "pages": list(verdict["pages"] or []),
                                  "relays": relays, "relays_ar": relays_ar})
    where_books_stand = []
    for book, *_rest in BOOKS:
        held = stands.get(book)
        if not held:
            continue
        held["divided"] = len({p["leaning"] for p in held["positions"]}) > 1
        where_books_stand.append(held)
    reported_without_a_passage = [unsourced[b] for b, *_r in BOOKS if b in unsourced]

    # Which Imams he kept company with, on the books' own say-so.
    #
    # A generation statement comes from one of two places. al-Tusi files a man under «من
    # أصحاب الصادق» and that is the book speaking; a phase 1 agent, asked the same question,
    # sometimes wrote reasoning instead — «Companion of al-Sadiq (implied by context, though
    # the entry is about his father)», «Contemporary of al-Mahdi or later (likely 4th
    # century)». Both were being read for Imam names and unioned, so Muhammad b. Yahya
    # al-'Attar — whom al-Tusi files under «باب ذكر أسماء من لم يرو عن واحد من الأئمة»,
    # among the men who narrated from no Imam at all — was printed as a companion of
    # al-Sadiq, al-Kazim, al-Rida and the Mahdi, on the same page that quoted the chapter
    # denying it.
    #
    # So the books rule. Where a book names an Imam, that is the answer and prose is not
    # consulted. Where a book says he narrated from none, that settles it too, and the page
    # says so instead of leaving a silence for a guess to fill. Prose answers only where no
    # book speaks at all, and never when it is hedged.
    from_books, from_prose, no_imam_books = set(), set(), []
    for statement in values(person, "generations"):
        said = statement.get("value")
        if not said:
            continue
        if ARABIC_RANGE.search(str(said)):
            if says_no_imam(said):
                no_imam_books.append(statement.get("source_book"))
            from_books |= imam_generations(said)
        elif not is_reasoned(said):
            from_prose |= imam_generations(said)
    if from_books:
        imams = set(from_books)
    elif no_imam_books:
        imams = set()
    else:
        imams = set(from_prose)

    # Who he was, in the terms the books use: the Imams he kept company with place a man in time
    # better than a date, and most men have no date at all. The city is the one the books name
    # most often; where two books name different cities both are listed under the facts below.
    imams_named = [IMAM_NAMES[n] for n in sorted(imams) if n < len(IMAM_NAMES)]
    imams_named_ar = [IMAM_NAMES_AR[n] for n in sorted(imams) if n < len(IMAM_NAMES_AR)]
    spans = [YEARS[n] for n in sorted(imams) if n in (YEARS or {})]
    # Cited or not said. A city or a death year read from a record we cannot show the reader
    # is an assertion the page cannot support, and the audit put the city field at 67% on
    # entries against 48% across every record that carries one.
    def cited(field, key_name="value"):
        out = []
        for item in values(person, field):
            if not item.get(key_name):
                continue
            if entry_key_for(person, item.get("source_book"),
                             item.get("source_pages") or [], entry_keys):
                out.append(item[key_name])
        return out

    def reported(field, key_name="value"):
        """What a record says but cannot show: the same claim, without a passage behind it.

        The gate above is right to keep an untraceable city out of the field the page states
        as fact. Dropping it altogether is a different thing, and it was costing readers what
        the sources do say: a city for 8% of men, a death year for 1.7%, with no way to tell
        «no book says where he lived» from «a book says it and we would not pass it on». A
        verdict in that position is already carried and marked — `reported_without_a_passage`
        — so a city and a death year are carried the same way and marked the same way.
        """
        out = []
        for item in values(person, field):
            if not item.get(key_name):
                continue
            if not entry_key_for(person, item.get("source_book"),
                                 item.get("source_pages") or [], entry_keys):
                out.append(item[key_name])
        return out

    cities = cited("city_or_tribe")
    died = cited("death_years")
    said_cities, said_died = reported("city_or_tribe"), reported("death_years")
    city = Counter(cities).most_common(1)[0][0] if cities else None
    # A year read out of the entry by rule beats a year an agent read in phase 1, where the
    # two differ in the way a misreading differs — «إحدى وثلاثمائة» is 301, not 311. The
    # table holds only those, each one checked by hand; see arabic_years.py.
    # A death year the entry does not state is reported, never stated. The books give one
    # for 4% of men and the extraction read many of the rest off something else — a teacher's
    # year, the year a man taught, a year he was still alive. The entry either says when he
    # died or it does not, and that is a thing a rule can settle for every man at once.
    stated_years = set()
    for passage in (e.get("text_ar") or "" for e in entries):
        stated_years |= {y["year"] for y in arabic_years.years_in(passage) if y["about_death"]}

    death_year = died[0] if died else None
    corrected = DEATH_YEARS.get(person["person_id"])
    if corrected and death_year == corrected.get("was"):
        death_year = corrected["year"]
    said_death_year = None
    if death_year and stated_years and death_year not in stated_years:
        said_death_year, death_year = death_year, None
    elif death_year and not stated_years:
        said_death_year, death_year = death_year, None
    # Every entry's own words, to test a claim against. The same test `field_check.py` runs
    # over the corpus — grades have had one since the beginning and relations too; a city, a
    # kunya and a title were printed and searched with nothing behind them.
    passages = [e.get("text_ar") or "" for e in entries]
    passages.append(person.get("primary_arabic_name") or "")

    # A city the entry does not bear out joins the ones that were never traceable: shown as
    # reported, never as stated.
    if city and field_check.borne_out(city, city_ar(city), passages) == "not in the passage":
        said_cities, city = list(cities), None
    said_city = (Counter(said_cities).most_common(1)[0][0]
                 if said_cities and not city else None)
    said_death = (said_died[0] if said_died and not died else None) or said_death_year
    life = {"imams": imams_named,
            "imams_ar": imams_named_ar,
            "city": city,
            "city_ar": city_ar(city),
            "died": death_year,
            "died_ar": arabic_digits(death_year) if death_year else None,
            # Said by a record we cannot show a passage for, and only where the traced field
            # is empty. The page must print these differently: they are reported, not shown.
            "city_reported": said_city,
            "city_reported_ar": city_ar(said_city) if said_city else None,
            "died_reported": said_death,
            "died_reported_ar": arabic_digits(said_death) if said_death else None,
            # Said, not missing: a book that files him among those who narrated from no Imam
            # has answered the question, and the page prints the answer rather than an empty
            # line. Where another book does name an Imam the two stand together, because the
            # books disagreeing is itself what a reader needs to know.
            "no_imam": bool(no_imam_books),
            "no_imam_books": [BOOK_TITLE.get(b, b) for b in sorted(set(no_imam_books)) if b],
            "no_imam_books_ar": [BOOK_TITLE_AR[b] for b in sorted(set(no_imam_books))
                                 if BOOK_TITLE_AR.get(b)]}

    # Where the books leave him, in one word: a reader wants to know whether he is relied on
    # before reading eight entries, and the honest answer is often «they disagree».
    # What the books actually say, grouped by the word they say it with, so a page can quote
    # them instead of pronouncing over them. «Held reliable» is nobody's word: the rijālī
    # wrote «ثقة», and a reader — a scholar above all — wants that word and the book it
    # stands in, not our summary of it.
    said_by = []
    for book in where_books_stand:
        for position in book["positions"]:
            row = next((r for r in said_by if r["grade"] == position["grade"]), None)
            if row is None:
                row = {"grade": position["grade"], "said": position["said"],
                       "said_ar": position["said_ar"], "leaning": position["leaning"],
                       "books": [], "books_ar": [], "relayed": [], "relayed_ar": []}
                said_by.append(row)
            if book["book_title"] not in row["books"]:
                row["books"].append(book["book_title"])
                if book.get("book_title_ar"):
                    row["books_ar"].append(book["book_title_ar"])
            for title in position.get("relays") or []:
                if title not in row["relayed"]:
                    row["relayed"].append(title)
            for title in position.get("relays_ar") or []:
                if title not in row["relayed_ar"]:
                    row["relayed_ar"].append(title)
    said_by.sort(key=lambda r: (r["leaning"] != "for", -len(r["books"])))

    def speaks_for_itself(book, position):
        """A compiler quoting a book that has spoken here is that book, not another voice."""
        if book["book"] not in LATE_BOOKS or not position.get("relays"):
            return True
        return not (set(position["relays"])
                    & {b["book_title"] for b in where_books_stand})

    for_him = {b["book_title"] for b in where_books_stand
               for p in b["positions"] if p["leaning"] == "for" and speaks_for_itself(b, p)}
    against_him = {b["book_title"] for b in where_books_stand
                   for p in b["positions"]
                   if p["leaning"] == "against" and speaks_for_itself(b, p)}
    # the same two sets by book, so the Arabic sentence can name the books it means in Arabic
    # and keep the printed order of the eight rather than sorting them by a Latin title
    # Through the same filter as the English, which it did not use to run through: the
    # Arabic page was counting al-Khoei quoting al-Najashi as a fourth voice for Zurara
    # where the English counted three, so the two pages gave a reader two different answers
    # to «how many books hold him reliable».
    for_him_ar = [b["book_title_ar"] for b in where_books_stand if b.get("book_title_ar")
                  and any(p["leaning"] == "for" and speaks_for_itself(b, p)
                          for p in b["positions"])]
    against_him_ar = [b["book_title_ar"] for b in where_books_stand if b.get("book_title_ar")
                      and any(p["leaning"] == "against" and speaks_for_itself(b, p)
                              for p in b["positions"])]
    standing = ("disputed" if for_him and against_him else "trusted" if for_him
                else "weakened" if against_him else "unstated")

    # Whose authority the one word rests on. The eight books are not eight equal voices: al-
    # Najāshī and al-Ṭūsī wrote within living memory of the men they assess, al-Ardabīlī in
    # 1101 and al-Khoei in 1413 are reading the same early books we are, and the ascription
    # of the Ḍuʿafāʾ to Ibn al-Ghaḍāʾirī is disputed — al-Khoei held it unproven and would
    # not weaken a man on it. Flattening them put «Held weak» at the head of 48 pages on the
    # Ḍuʿafāʾ alone, and «Held reliable» on 319 where no early book says so. The verdict
    # stands, because the books do say it; the page now says on whose word.
    spoke = {b["book_title"] for b in where_books_stand
             for p in b["positions"]
             if p["leaning"] == ("against" if standing == "weakened" else "for")}
    said_it = {b["book"] for b in where_books_stand
               for p in b["positions"]
               if standing == "disputed" or p["leaning"] == (
                   "against" if standing == "weakened" else "for")}
    # Whom the compilers were quoting, where no early book of ours states a position: the
    # authority is early even though it reaches the reader through al-Khoei.
    early_titles = {BOOK_TITLE[c] for c in EARLY_BOOKS if c in BOOK_TITLE}
    relayed_early = {code for b in where_books_stand for p in b["positions"]
                     for code in p.get("relays") or []
                     if code in early_titles and (
                         standing == "disputed"
                         or p["leaning"] == ("against" if standing == "weakened" else "for"))}
    rests_on = ""
    if standing in ("trusted", "weakened", "disputed"):
        if said_it == {"duafa"}:
            rests_on = "duafa_only"
        elif said_it & EARLY_BOOKS:
            rests_on = "early"
        elif relayed_early:
            rests_on = "early_relayed"
        else:
            rests_on = "late"

    anchor = source.get(person.get("anchor")) or {}

    def key_of(book, pages):
        """Which of this man's records a statement was read from."""
        same = [k for k in keys if k.split(":")[0] == book]
        if len(same) == 1:
            return same[0]
        for key in same:
            held = set(person["pages_of"].get(key) or ())
            if pages and held and set(pages) & held:
                return key
        return None

    stated_keys = set(entry_keys)
    def text_for(key):
        return text_of(source[key]) if key in source else None

    all_from = sources_by_name(values(person, "narrated_from"), stated_keys, key_of, text_for)
    all_to = sources_by_name(values(person, "narrated_to"), stated_keys, key_of, text_for)

    # Three lists, because they are three different claims and the page may not blur them:
    # men a book says he learned from, men who merely stand beside him in an index of
    # chains, and the Imams, who everyone transmits from and who bury the rest if mixed in.
    # Four lists now, because the gate adds a fourth claim: men a book says he learned from
    # and names in the entry it says it in; men a book says it of in an entry that does not
    # name them; men who merely stand beside him in an index of chains; and the Imams, who
    # everyone transmits from and who bury the rest if mixed in.
    def split(links):
        return ([m for m in links if m["kind"] != "imam" and m["stated"]],
                [m for m in links if m["kind"] != "imam" and not m["stated"]
                 and not m["unsaid"]],
                [m for m in links if m["kind"] == "imam" and not m["unsaid"]],
                [m for m in links if m["unsaid"]])

    all_from = settle_imams(all_from, imams, True)
    all_to = settle_imams(all_to, imams, False)
    narrated_from, chain_from, imams_from, unsaid_from = split(all_from)
    narrated_to, chain_to, imams_to, unsaid_to = split(all_to)

    all_names_ar = [n["value"] for n in values(person, "primary_names") + values(person, "arabic_aliases")
                    if n.get("value") and not another_mans_name(person, n["value"])]
    own_headings = [normalize_arabic(n["value"]) for n in values(person, "primary_names")
                    if n.get("value")]
    own_headings += [normalize_arabic(e["heading_ar"]) for e in entries if e.get("heading_ar")]
    group_kind = {n: not_a_personal_name(n, own_headings) for n in set(all_names_ar)}
    names_ar = [n for n in all_names_ar if not group_kind[n]]
    names_group_ar = [{"value": n, "kind": group_kind[n]} for n in sorted(set(all_names_ar))
                      if group_kind[n]]
    # The names a book actually headed an entry with, apart from every name merely written
    # inside one. The chain search uses these and not the aliases: Khoei's entry on Zurara
    # carries «محمد بن مسلم» among the names in its text — one of them «محمد بن مسلم مضمرة»,
    # which is a form of narration and not a name — and searching the chains by it put a
    # thousand of Muhammad b. Muslim's narrations on Zurara's page. A heading is the book
    # saying «this entry is about this man»; an alias is only a name that appeared nearby.
    # Nothing is lost by the distinction: the short forms the chains use are headings too,
    # «زرارة بن أعين» and «سهل بن زياد» among them.
    headings_ar = {n["value"] for n in values(person, "primary_names") if n.get("value")}
    headings_ar |= {e["heading_ar"] for e in entries if e.get("heading_ar")}
    # The anchor's own opening line, explicitly: the heading below may shorten it, and a
    # search for the lineage, or a chain name resolved against it, must still reach him.
    headings_ar |= {n for n in (anchor.get("primary_arabic_name"),
                                person.get("primary_arabic_name")) if n}
    search_ar = searchable(sorted(headings_ar),
                           sorted({n["value"] for n in values(person, "arabic_aliases")
                                   if n.get("value")}), HOLDERS)
    all_names_en = [n["value"] for n in values(person, "english_aliases")
                    if n.get("value") and not another_mans_english(person, n["value"])]
    names_en = [n for n in all_names_en if not not_a_personal_name(n, [], english=True)]
    names_group_en = [{"value": n, "kind": "family"} for n in sorted(set(all_names_en))
                      if not_a_personal_name(n, [], english=True)]

    display_ar = anchor.get("primary_arabic_name") or person.get("primary_arabic_name")
    def split_by_evidence(raw):
        """What the entry's words hold, and what only a record says."""
        held, said = [], []
        for value in sorted(raw):
            (held if field_check.borne_out(value, value, passages) != "not in the passage"
             else said).append(value)
        return held, said

    kunyahs, kunyahs_said = split_by_evidence(
        {whole for whole in (whole_kunya(k["value"], set(names_ar) | headings_ar)
                             for k in values(person, "kunyahs_arabic") if k.get("value"))
         if whole})
    titles, titles_said = split_by_evidence(
        {t["value"] for t in values(person, "titles") if t.get("value")})

    clean = name_only(display_ar)
    if clean != display_ar:
        # Prefer a heading of his own that was never contaminated, and only cut when there
        # is none: a book's own filing name beats our repair of another one.
        whole = [h for h in sorted(headings_ar, key=lambda h: len(h.split()))
                 if 2 <= len(h.split()) and name_only(h) == h]
        display_ar = whole[0] if whole else clean
    # The whole line, kept for the body: a reader who wants to see the lineage the entry
    # opens with should not have to take our word that we shortened a real name.
    nasab_ar = display_ar
    display_ar = short_heading(display_ar, headings_ar)
    display_en = anchor.get("primary_english_name") or person.get("primary_english_name")
    if display_en and display_en.rstrip().endswith(("...", "\u2026")):
        display_en = None      # a truncation, not a name
    made = False
    if display_ar != nasab_ar:
        # The Arabic moved to his filing name, so the English follows it rather than going on
        # reciting a lineage the Arabic beside it no longer does.
        display_en, made = english_for_heading(display_ar, names_en, TRANSLITERATION)
    if not display_en and display_ar:
        display_en = transliterate.english_for(display_ar, TRANSLITERATION)
        made = bool(display_en)
    if display_ar == nasab_ar:
        nasab_ar = None

    # The blurb opens on the man's name, so it is written after the heading is settled: it
    # used to recite the whole nasab a second time, directly under the line that already
    # shows it.
    display = display_en or display_ar or "He"
    # The blurb a reader wants before the eight entries: when he lived, where, and where the
    # books leave him. It is assembled from the statements below it and says nothing they do
    # not — the books are named, so a reader can see whose judgment each clause carries.
    said = []
    if imams_named:
        said.append(f"{display} kept company with " + join_names(imams_named))
    elif life["city"]:
        said.append(f"{display} was of {life['city']}")
    if life["city"] and imams_named:
        said.append(f"and was of {life['city']}")
    if life["died"]:
        said.append((f"and died in {life['died']} AH") if said
                    else f"{display} died in {life['died']} AH")
    where = ""
    if standing == "disputed":
        where = ("The books are divided about him: " + join_names(sorted(for_him))
                 + (" hold" if len(for_him) > 1 else " holds") + " him reliable, while "
                 + join_names(sorted(against_him))
                 + (" weaken" if len(against_him) > 1 else " weakens") + " him.")
    elif standing == "trusted":
        where = join_names(sorted(for_him)) + (" hold" if len(for_him) > 1 else " holds") + \
            " him reliable."
    elif standing == "weakened":
        where = join_names(sorted(against_him)) + (" weaken" if len(against_him) > 1 else " weakens") + \
            " him."
    blurb = ((" ".join(said) + ". ") if said else "") + where

    said_ar = []
    if imams_named_ar:
        said_ar.append((display_ar + " " if display_ar else "") + "من أصحاب "
                       + join_names_ar(imams_named_ar))
    elif life["city_ar"]:
        said_ar.append((display_ar + " من " if display_ar else "من ") + life["city_ar"])
    if life["city_ar"] and imams_named_ar:
        said_ar.append("وكان من " + life["city_ar"])
    if life["died_ar"]:
        said_ar.append(("وتوفي سنة " + life["died_ar"] + " هـ") if said_ar
                       else ((display_ar + " " if display_ar else "") + "توفي سنة "
                             + life["died_ar"] + " هـ"))
    where_ar = ""
    if standing == "disputed":
        where_ar = ("اختلفت فيه الكتب: يوثّقه " + join_names_ar(for_him_ar)
                    + "، ويضعّفه " + join_names_ar(against_him_ar) + ".")
    elif standing == "trusted" and for_him_ar:
        where_ar = "يوثّقه " + join_names_ar(for_him_ar) + "."
    elif standing == "weakened" and against_him_ar:
        where_ar = "يضعّفه " + join_names_ar(against_him_ar) + "."
    blurb_ar = (("، ".join(said_ar) + ". ") if said_ar else "") + where_ar

    return {
        "person_id": person["person_id"],
        # the anchor entry's own heading, not the longest name the merge found: that gave Ibn Abi
        # 'Umayr the garbled «أبو أحمد بن محمد بن زياد الأزدي»
        "display_ar": display_ar,
        "display_en": display_en,
        # True where no book printed him an English name and we made one from his Arabic, so
        # a page can say so rather than passing our spelling off as a book's.
        "display_en_made": made,
        # The anchor entry's opening line, where the heading above is a shorter name a book
        # files him under. None where the two are the same, so a page can show it or not.
        "nasab_ar": nasab_ar,
        "anchor_key": person.get("anchor"),
        "slug": slugify(display_en or ""),
        "names_ar": sorted(set(names_ar)),
        "headings_ar": sorted(headings_ar),
        "search_ar": sorted(search_ar),
        "names_en": sorted(set(names_en)),
        # Family and title forms stay in the searched fields: a reader looking up «آل أعين» must
        # find Zurāra even though the page no longer lists it among his names.
        "names_group_ar": names_group_ar,
        "names_group_en": names_group_en,
        "normalized_ar": sorted({normalize_arabic(n) for n in all_names_ar}),
        "normalized_en": sorted({n.lower() for n in all_names_en}),
        "kunyahs_ar": kunyahs,
        "titles": titles,
        # Said by a record whose entry does not hold the words: kept, because a book did say
        # it, but out of the fields a search matches on. A title nobody's entry bears out
        # makes a man findable under a name that is not his.
        "kunyahs_reported_ar": kunyahs_said,
        "titles_reported": titles_said,
        "books": sorted({k.split(":")[0] for k in entry_keys}, key=lambda b: BOOK_ORDER.get(b, 99)),
        "entries": entries,
        "entry_count": len(entries),
        "verdicts": verdicts,
        "where_books_stand": where_books_stand,
        "reported_without_a_passage": reported_without_a_passage,
        "for_him": sorted(for_him),
        "against_him": sorted(against_him),
        "standing": standing,
        "standing_rests_on": rests_on,
        "said_by": said_by,
        "life": life,
        "blurb": blurb.strip(),
        "blurb_ar": blurb_ar.strip(),
        "for_him_ar": for_him_ar,
        "against_him_ar": against_him_ar,
        # The books' own words only. A phase 1 agent reading al-Najashi wrote «Companion of
        # al-Sadiq» in English, and under a heading that says «what the books state» that is
        # two wrongs: it is our sentence, not al-Najashi's, and on the Arabic page it printed
        # in Latin. Where a book heads a man under a chapter — «أصحاب أبي عبد الله جعفر بن
        # محمد الصادق» — that chapter is the book speaking and it stays.
        "generations": [row for row in traced("generations")
                        if ARABIC_RANGE.search(str(row.get("value", "")))],
        "imam_ordinals": sorted(imams),
        "cities_or_tribes": traced("city_or_tribe"),
        "death_years": traced("death_years"),
        # Whom he takes from and who takes from him: the evidence a rijali argument about
        # his generation actually runs on, so the counts are kept whole even where the
        # lists shown are trimmed.
        # each name once, with the books that record the link, so the tab can show who says it
        "narrated_from": narrated_from,
        "narrated_to": narrated_to,
        "unsaid_from": unsaid_from,
        "unsaid_to": unsaid_to,
        "chain_from": chain_from,
        "chain_to": chain_to,
        "imams_from": imams_from,
        "imams_to": imams_to,
        # the counts the ledger shows are of men a book states he learned from or taught,
        # not of everyone who stands beside him in an index of chains
        "teacher_count": len(narrated_from),
        "student_count": len(narrated_to),
        "chain_only_count": len(chain_from) + len(chain_to),  # grown below by isnad_neighbours
        # The years his imamates cover, first to last — but never past his own death. The
        # ledger reads this as «Active, AH», and al-Kazim's imamate running to 183 had
        # Zurara, who died in 150, active for thirty-three years after it.
        "span_from": min((y[0] for y in spans), default=None),
        "span_to": (min(max((y[1] for y in spans), default=0), death_year)
                    if spans and death_year else max((y[1] for y in spans), default=None)),
        "sect_flags": traced("sect_flags"),
        "mention_count": len(keys) - len(entry_keys),
        "holds_entry": bool(entry_keys),
        # Always present, so a page asking a document for its kind never asks a map for a key
        # it lacks: SpEL throws on that rather than reading it as null.
        "kind": "narrator",
    }


MAPPING = {
    "settings": {"number_of_shards": 1, "number_of_replicas": 0,
                 "analysis": {"analyzer": {"arabic_names": {"tokenizer": "standard",
                                                            "filter": ["lowercase"]}}}},
    "mappings": {"properties": {
        "person_id": {"type": "keyword"},
        "display_ar": {"type": "text", "analyzer": "arabic_names",
                       "fields": {"keyword": {"type": "keyword", "ignore_above": 256}}},
        "display_en": {"type": "text", "fields": {"keyword": {"type": "keyword", "ignore_above": 256}}},
        "nasab_ar": {"type": "text", "analyzer": "arabic_names"},
        "names_ar": {"type": "text", "analyzer": "arabic_names"},
        "headings_ar": {"type": "text", "analyzer": "arabic_names"},
        "search_ar": {"type": "text", "analyzer": "arabic_names"},
        "names_en": {"type": "text"},
        "normalized_ar": {"type": "text", "analyzer": "arabic_names",
                          "fields": {"keyword": {"type": "keyword", "ignore_above": 256}}},
        "normalized_en": {"type": "text"},
        "kunyahs_ar": {"type": "text", "analyzer": "arabic_names"},
        "titles": {"type": "text", "analyzer": "arabic_names"},
        "books": {"type": "keyword"},
        "entry_count": {"type": "integer"},
        "mention_count": {"type": "integer"},
        "imam_ordinals": {"type": "integer"},
        "holds_entry": {"type": "boolean"},
        "standing_rests_on": {"type": "keyword"},
        "said_by": {"type": "object", "enabled": False},
        "display_en_made": {"type": "boolean"},
        "kunyahs_reported_ar": {"type": "text"},
        "titles_reported": {"type": "text"},
        # set only on a document that is really somebody else's, so a search can leave it out
        # with `must_not exists` and a page can send the reader on
        "moved_to": {"type": "keyword"},
        "entries": {"type": "object", "properties": {
            "source_key": {"type": "keyword"}, "book": {"type": "keyword"},
            "text_ar": {"type": "text", "analyzer": "arabic_names"},
            "text_en": {"type": "text"}, "grade": {"type": "keyword"},
            "page_first": {"type": "integer"}}},
        "narrated_from": {"type": "object", "properties": {
            "name": {"type": "text", "analyzer": "arabic_names"}}},
        "narrated_to": {"type": "object", "properties": {
            "name": {"type": "text", "analyzer": "arabic_names"}}},
        "teacher_count": {"type": "integer"},
        "student_count": {"type": "integer"},
        "verdicts": {"type": "object", "properties": {
            "grade": {"type": "keyword"}, "book": {"type": "keyword"},
            "source_key": {"type": "keyword"}}},
    }},
}


def roster_documents(like):
    """The fourteen, shaped like every other document so one page can render them all.

    The Imams hold no entry in any rijāl book, because no rijālī assesses the man he is
    assessing narrators for, yet theirs are the commonest names in the chains the site prints.
    A link needs somewhere to land, so they are indexed from the roster in `imams.py` with
    every field a narrator document has, emptied — no entries, no verdicts, no standing. The
    page then says what is true of them: the books do not grade them.
    """
    blank = {field: ([] if isinstance(value, list) else {} if isinstance(value, dict)
                     else "" if isinstance(value, str) else False if isinstance(value, bool)
                     else None)
             for field, value in like.items()}
    documents = []
    for imam in imams.ROSTER:
        names_ar = sorted({imam["name_ar"]} | {f for f in imam["exact"]})
        document_ = dict(blank)
        document_.update({
            "person_id": imam["id"],
            "display_ar": imam["name_ar"],
            "display_en": imam["name_en"],
            "slug": slugify(imam["name_en"]),
            "names_ar": names_ar,
            "names_en": [imam["name_en"]],
            "normalized_ar": sorted({normalize_arabic(n) for n in names_ar} - {""}),
            "normalized_en": sorted({normalize_english(imam["name_en"])} - {""}),
            "search_ar": names_ar,
            "kind": "imam",
            "holds_entry": True,
            "standing": "not_graded",
            "life": {"imams": [], "imams_ar": [], "city": None, "city_ar": None,
                     "died": imam["died"], "died_ar": arabic_digits(imam["died"])},
            # death_years holds years traced to a book's words; the roster's own year is
            # not one of those, so it stands in `life` alone.
            "entry_count": 0, "mention_count": 0, "teacher_count": 0, "student_count": 0,
            "chain_only_count": 0, "blurb": "", "blurb_ar": "",
        })
        documents.append(document_)
    return documents


def attach_served(doc, beside, by_id):
    """Put the corpus's own count on a man's isnād rows, and add the men no index names.

    Both halves of «In the same isnād» say the same weak thing — these two stood together
    in a chain — but they are counted two different ways, because the evidence differs.

    A row a rijālī index already prints takes the unordered count, singletons included:
    the order is the book's claim and not ours, and the index is the support a lone
    co-occurrence would otherwise lack. Before this read the directional, floored count,
    71 of the 11,207 such rows carried a number; 3,301 of them do now.

    A man no index names has nothing behind him but the chains, so the direction is read
    from them and a single co-occurrence is held back, that being exactly what one
    mis-resolved name produces.

    Returns (rows given a count, rows the corpus added).
    """
    shared = beside.get("beside") or {}
    marked = placed = 0
    # Read once, before anything is appended: a man a book already places stays where the
    # book put him rather than being printed a second time.
    every_list = tuple(relations_join.RELATION_FIELDS) + ("imams_from", "imams_to")
    already = {row.get("person_id") for field in every_list
               for row in (doc.get(field) or []) if row.get("person_id")}
    for field, side in (("chain_from", "before"), ("chain_to", "after")):
        counted = beside.get(side) or {}
        rows = list(doc.get(field) or [])
        for row in rows:
            narrations = shared.get(row.get("person_id"))
            if narrations:
                # A pairing both the books and the corpus hold. One row, saying so.
                row["narrations"] = narrations
                marked += 1
        for pid, narrations in sorted(counted.items(), key=lambda s: (-s[1], s[0])):
            man = by_id.get(pid)
            if pid in already or not man:
                continue
            rows.append({
                "name": man.get("display_en"), "name_ar": man.get("display_ar"),
                "display_en": man.get("display_en"),
                "display_ar": man.get("display_ar"),
                "person_id": pid, "slug": man.get("slug"),
                "books": [], "books_ar": [], "narrations": narrations,
                "kind": "chain", "stated": False, "unsaid": False,
            })
            placed += 1
        doc[field] = rows
    # The count under his name reads «N men stand with him in the chains», built from these
    # two lists. It is set when the document is built, before any of this, so it has to be
    # taken again or the headline undercounts what the page goes on to print.
    doc["chain_only_count"] = (len(doc.get("chain_from") or [])
                               + len(doc.get("chain_to") or []))
    return marked, placed


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--live", action="store_true", help="create the index and load it")
    parser.add_argument("--dry-run", action="store_true", help="build the documents only")
    parser.add_argument("--all", action="store_true", help="index people holding no entry too")
    parser.add_argument("--show", help="print one person's document")
    parser.add_argument("--dump", help="write the documents to this file, which is what the "
                                       "chain resolver reads; it must be rebuilt whenever "
                                       "people merge, or a link can point at a retired id")
    parser.add_argument("--recreate", action="store_true",
                        help="drop the index first: needed when a field changes shape, since "
                             "a mapping inferred once (narrated_from as text) rejects the new "
                             "shape (an object) for every document")
    parser.add_argument("--es-host", default="http://localhost:9200")
    parser.add_argument("--index", default=INDEX)
    args = parser.parse_args()

    global YEARS, HOLDERS, ENGLISH_HOLDERS, PREFIXES, ENGLISH_PREFIXES, TRANSLITERATION
    YEARS = imam_years()
    TRANSLITERATION = transliterate.load()
    try:
        with open(os.path.join(REPO, "scripts/data/narrators/death_years.json")) as handle:
            DEATH_YEARS.update(json.load(handle)["years"])
    except (FileNotFoundError, KeyError):
        pass
    print(f"{len(DEATH_YEARS):,} death years their own passage contradicts, corrected by rule")
    print(f"{len(TRANSLITERATION):,} Arabic words the corpus prints in English, for the men "
          f"no book names in English")
    people = load(os.path.join(IDENTITY, "people.json"))
    HOLDERS, ENGLISH_HOLDERS = Counter(), Counter()
    PREFIXES, ENGLISH_PREFIXES = Counter(), Counter()
    for person in people:
        if person.get("holds_entry") is False:
            continue
        forms = {normalize_arabic(i["value"]) for i in (person.get("primary_names") or [])
                 if i.get("value")}
        if person.get("primary_arabic_name"):
            forms.add(normalize_arabic(person["primary_arabic_name"]))
        for form in forms:
            HOLDERS[form] += 1
            words = form.split()
            if len(words) >= 3:
                PREFIXES[" ".join(words[:3])] += 1
        if person.get("primary_english_name"):
            english = fold_english(person["primary_english_name"])
            ENGLISH_HOLDERS[english] += 1
            if len(english.split()) >= 3:
                ENGLISH_PREFIXES[" ".join(english.split()[:3])] += 1
    source, headless = profiles(), not_entries(TMP)
    UNNAMED.update(unnamed_subjects(TMP))
    # Which books the corpus holds printed entries of, so a grade attributed to a book never
    # extracted can say that rather than look like a gap in our reading of it. Māmaqānī has
    # profiles in the record — names and verdicts gathered from other books quoting him —
    # and not one printed entry of his own, which is exactly the distinction a reader needs.
    # «Holds the book» means the same thing here as everywhere else in the build — is_entry,
    # the rule that decides what becomes an entry at all — rather than any proxy for it.
    # Māmaqānī has 15,740 profiles in the record, gathered from books that quote him, and
    # not one entry of his own; Kitāb al-Ḍuʿafāʾ has 218 entries and not one profile flagged
    # printed. A proxy gets one of those two wrong whichever one you pick.
    BOOKS_WITH_TEXT.update(key.split(":", 1)[0] for key, profile in source.items()
                           if is_entry(key, profile, headless))
    learned = learn_transliterations(source)
    print(f"{learned:,} English name forms mapped to Arabic from the books' own headings")
    counts = Counter()
    documents = []
    for person in people:
        doc = document(person, source, headless)
        # A man who is one of the fourteen is not a narrator the books assess. The extraction
        # let a few through all the same — al-Ṭūsī lists ʿAlī, al-Ḥasan and al-Riḍā among the
        # companions he catalogues, and Khoei's index carries «علي بن أبي طالب» three separate
        # times — so the search answered a query with three cards for the first Imam and a
        # chain naming al-Riḍā in the book written about him landed on a three-entry stub.
        # They keep their documents, so no link 404s, and point at the roster page instead.
        # Their entries are not moved onto it: an entry headed «علي» may be any ʿAlī, and
        # publishing it under the Imam's name would be a worse error than not showing it.
        if NOT_A_MAN.match((doc.get("display_ar") or "").strip()):
            counts["a chapter heading, not a man — left out"] += 1
            continue
        moved = imams.identify(normalize_arabic(doc.get("display_ar") or ""))
        if len(moved) == 1:
            doc["moved_to"] = moved[0]
            counts["one of the fourteen, moved to the roster page"] += 1
        counts["holds an entry" if doc["holds_entry"] else "no entry, left out"] += 1
        if doc["holds_entry"] or args.all:
            documents.append(doc)
            counts["entries shown"] += doc["entry_count"]
            counts["verdicts shown"] += len(doc["verdicts"])
            counts["verdicts traced to an entry"] += sum(1 for v in doc["verdicts"] if v["source_key"])
    # One man is one row. The relation lists are grouped by the name as each book spelled
    # him, so a man two books spell differently — «أحمد ابن أبي عبد الله» against «أحمد بن
    # أبي عبد الله», or Khoei's index read in English against the Arabic of the rest —
    # arrived as two rows each carrying one book. Regrouped by the man the name reaches,
    # which also puts his id on the row so the page can link it.
    settled = relations_join.load_settled()
    if settled:
        print(f"  {len(settled):,} rows a judge settled, where no name reaches one man")
    counts["teacher and student rows joined to another naming the same man"] = \
        relations_join.join(documents, settled)

    if documents and args.dump:
        with open(args.dump + ".part", "w") as handle:
            json.dump(documents, handle, ensure_ascii=False)
        os.replace(args.dump + ".part", args.dump)
        print(f"dumped {len(documents):,} people -> {args.dump}")
    if documents:
        roster = roster_documents(documents[0])
        documents += roster
        counts["the fourteen, who hold no entry anywhere"] = len(roster)
        # Again, now that the fourteen are in the set. The pass above ran before them, so
        # its lookup held no Imam names: «أبي الحسن موسى» and «أبي جعفر (الثاني)» reached
        # nobody and stayed plain text while every human around them became a link. The
        # roster cannot simply be appended earlier — the dump above is what the chain
        # resolver reads, and it carries its own roster. join() regroups rather than
        # rewrites, so a second pass over rows it has already grouped changes nothing
        # except the names it could not read the first time.
        counts["rows joined to an Imam, once the roster was in the set"] = \
            relations_join.join(documents, settled)
        # The Imams' own block is built from imams_from/imams_to, which relations_join does
        # not touch — those rows are settled by name against the roster long before it runs,
        # and they already carry imam_id. They only lack the slug, so a link had to go to
        # the bare id and take a 301. Stamped here, where the roster is finally in hand.
        roster_slugs = {d["person_id"]: d.get("slug") for d in roster}
        slugged = 0
        for doc in documents:
            for field in ("imams_from", "imams_to"):
                for link in doc.get(field) or []:
                    slug = roster_slugs.get(link.get("imam_id"))
                    if slug and not link.get("slug"):
                        link["slug"] = slug
                        slugged += 1
        counts["Imam rows given the roster slug, so the name can link"] = slugged

        # «In the same isnād» is built from the chain indexes the rijāl books print, which
        # are a summary of what their authors read rather than the chains this database
        # serves. For Aḥmad b. Idrīs the books give 66 names; the corpus puts him beside
        # 121 men, 108 of them unnamed by any index. Both are the same weak claim — these
        # men stood together in a chain, nobody called anyone a teacher — so they belong in
        # one list, with every row saying which it came from.
        try:
            seen_here, chain_steps = isnad_neighbours.build(args.es_host)
        except Exception as unreachable:  # noqa: BLE001 - any failure means book-only
            seen_here, chain_steps = {}, 0
            print(f"  could not read the served chains ({unreachable}); the isnād lists "
                  f"stay book-only", file=sys.stderr)
        by_id = {d["person_id"]: d for d in documents if d.get("person_id")}
        placed = marked = 0
        for doc in documents:
            beside = seen_here.get(doc.get("person_id"))
            if not beside:
                continue
            was_marked, was_placed = attach_served(doc, beside, by_id)
            marked += was_marked
            placed += was_placed
        counts["isnad rows the books also hold, now counted here too"] = marked
        counts["men the corpus puts beside him that no index names"] = placed
    for label, n in counts.most_common():
        print(f"  {n:7d}  {label}")
    for label, n in IMAM_STATS.most_common():
        print(f"  {n:7d}  {label}")

    if args.show:
        wanted = [d for d in documents if d["person_id"] == args.show]
        print(json.dumps(wanted[0] if wanted else {"not indexed": args.show},
                         ensure_ascii=False, indent=1)[:4000])
    if not args.live:
        return

    from elasticsearch import Elasticsearch, helpers
    es = Elasticsearch(args.es_host, request_timeout=120)
    if args.recreate and es.indices.exists(index=args.index):
        es.indices.delete(index=args.index)
        print(f"dropped {args.index}")
    if es.indices.exists(index=args.index):
        print(f"{args.index} exists; replacing its documents")
    else:
        es.indices.create(index=args.index, **MAPPING)
        print(f"created {args.index}")
    loaded, failed = helpers.bulk(
        es, ({"_index": args.index, "_id": d["person_id"], "_source": d} for d in documents),
        chunk_size=BATCH, raise_on_error=False)
    es.indices.refresh(index=args.index)
    print(f"loaded {loaded} narrators into {args.index}"
          + (f"; {len(failed)} failed" if failed else ""))
    if failed:
        print(json.dumps(failed[:2], ensure_ascii=False)[:800])


if __name__ == "__main__":
    main()
