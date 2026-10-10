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
from build_people import MAIN_BOOKS, is_entry, not_entries  # noqa: E402
from narrator_schema import imam_generations, normalize_arabic  # noqa: E402

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
BOOK_NOTE = {"duafa": "The ascription of this book to Ibn al-Ghaḍāʾirī is disputed; al-Khoei "
                      "held it unproven («الكتاب المنسوب إلى ابن الغضائري… لم يثبت»)."}


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


def sources_by_name(statements, stated_keys, key_of):
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
        held = found.setdefault(name, {"name": name, "books": [], "books_ar": [],
                                       "kind": kind, "stated": False})
        book = item.get("source_book")
        title = BOOK_TITLE.get(book, book)
        if title and title not in held["books"]:
            held["books"].append(title)
        title_ar = BOOK_TITLE_AR.get(book)
        if title_ar and title_ar not in held["books_ar"]:
            held["books_ar"].append(title_ar)
        key = key_of(book, item.get("source_pages") or [])
        if key in stated_keys:
            held["stated"] = True
    return sorted(found.values(),
                  key=lambda x: (not x["stated"], -len(x["books"]), x["name"]))[:200]


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
    key = re.sub(r"^(al-|el-)", "", value.strip().lower())
    key = re.sub(r"\s*\(.*\)\s*", "", key).strip(" .")
    return CITY_AR.get(key)


def load(path):
    with open(path) as handle:
        return json.load(handle)


def imam_years():
    """Each Imam's ordinal to the years his period ran, from the page's own chronology."""
    if not os.path.exists(CHRONOLOGY):
        return {}
    return {e["order"]: (e["fromAh"], e["toAh"]) for e in load(CHRONOLOGY).get("eras", [])}


YEARS = None
HOLDERS = {}


def profiles():
    found = {}
    for slug, *_rest in BOOKS:
        path = os.path.join(NORMALIZED, f"{slug}.json")
        if os.path.exists(path):
            for profile in load(path):
                found[f"{slug}:{profile['source_index']}"] = profile
    return found


def text_of(profile, arabic=True):
    """The source's own words: the printed entry where there is one, else what was read."""
    said = profile.get("source_assessments") or []
    field = "assessment_ar" if arabic else "assessment_en"
    printed = "printed_entry" in (profile.get("flags") or ())
    for assessment in (reversed(said) if printed else said):
        if (assessment or {}).get(field):
            return assessment[field]
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


def document(person, source, headless):
    """One narrator, as a page will read him."""
    keys = sorted(person["source_keys"],
                  key=lambda k: (BOOK_ORDER.get(k.split(":")[0], 99), int(k.split(":")[1])))
    entry_keys = [k for k in keys if k in source and is_entry(k, source[k], headless)]
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
        })

    def traced(field, key_name="value"):
        out = []
        for item in values(person, field):
            book, pages = item.get("source_book"), item.get("source_pages") or []
            out.append({key_name: item.get(key_name) or item.get("value"),
                        "book": book, "book_title": BOOK_TITLE.get(book, book),
                        "book_title_ar": BOOK_TITLE_AR.get(book), "pages": pages,
                        "source_key": entry_key_for(person, book, pages, entry_keys)})
        return [x for x in out if x.get(key_name)]

    verdicts = []
    for grade in values(person, "reliability_grades"):
        book, pages = grade.get("source_book"), grade.get("source_pages") or []
        verdicts.append({"grade": grade.get("grade"), "said": grade.get("raw"),
                         "stated": grade.get("grade") not in UNSTATED,
                         "book": book, "book_title": BOOK_TITLE.get(book, book),
                         "book_title_ar": BOOK_TITLE_AR.get(book), "pages": pages,
                         "book_note": BOOK_NOTE.get(book),
                         "source_key": entry_key_for(person, book, pages, entry_keys)})

    # One position per book, not one per profile. A book is read into several profiles — Khoei's
    # entry spans five pages and phase 1 read each — and the same verdict came back once per
    # profile, so a page showed «Tanqīḥ al-Maqāl: thiqa» three times. Where a book really does
    # take two positions, as Mamaqani does in quoting al-Najashi's «weak» before concluding
    # «thiqa» himself, both are kept and the disagreement is marked.
    stands = {}
    for verdict in verdicts:
        if not verdict["stated"]:
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
        held["positions"].append({"grade": verdict["grade"], "said": word,
                                  "said_ar": GRADE_WORDS_AR.get(verdict["grade"]),
                                  "leaning": leaning, "pages": list(verdict["pages"] or [])})
    where_books_stand = []
    for book, *_rest in BOOKS:
        held = stands.get(book)
        if not held:
            continue
        held["divided"] = len({p["leaning"] for p in held["positions"]}) > 1
        where_books_stand.append(held)

    imams = set()
    for statement in values(person, "generations"):
        imams |= imam_generations(statement.get("value"))

    # Who he was, in the terms the books use: the Imams he kept company with place a man in time
    # better than a date, and most men have no date at all. The city is the one the books name
    # most often; where two books name different cities both are listed under the facts below.
    imams_named = [IMAM_NAMES[n] for n in sorted(imams) if n < len(IMAM_NAMES)]
    imams_named_ar = [IMAM_NAMES_AR[n] for n in sorted(imams) if n < len(IMAM_NAMES_AR)]
    spans = [YEARS[n] for n in sorted(imams) if n in (YEARS or {})]
    cities = [c["value"] for c in values(person, "city_or_tribe") if c.get("value")]
    died = [d["value"] for d in values(person, "death_years") if d.get("value")]
    city = Counter(cities).most_common(1)[0][0] if cities else None
    life = {"imams": imams_named,
            "imams_ar": imams_named_ar,
            "city": city,
            "city_ar": city_ar(city),
            "died": died[0] if died else None,
            "died_ar": arabic_digits(died[0]) if died else None}

    # Where the books leave him, in one word: a reader wants to know whether he is relied on
    # before reading eight entries, and the honest answer is often «they disagree».
    for_him = {b["book_title"] for b in where_books_stand
               if any(p["leaning"] == "for" for p in b["positions"])}
    against_him = {b["book_title"] for b in where_books_stand
                   if any(p["leaning"] == "against" for p in b["positions"])}
    # the same two sets by book, so the Arabic sentence can name the books it means in Arabic
    # and keep the printed order of the eight rather than sorting them by a Latin title
    for_him_ar = [b["book_title_ar"] for b in where_books_stand if b.get("book_title_ar")
                  and any(p["leaning"] == "for" for p in b["positions"])]
    against_him_ar = [b["book_title_ar"] for b in where_books_stand if b.get("book_title_ar")
                      and any(p["leaning"] == "against" for p in b["positions"])]
    standing = ("disputed" if for_him and against_him else "trusted" if for_him
                else "weakened" if against_him else "unstated")

    anchor = source.get(person.get("anchor")) or {}
    display = (anchor.get("primary_english_name") or person.get("primary_english_name")
               or anchor.get("primary_arabic_name") or "He")

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

    display_ar = anchor.get("primary_arabic_name") or person.get("primary_arabic_name")
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
    all_from = sources_by_name(values(person, "narrated_from"), stated_keys, key_of)
    all_to = sources_by_name(values(person, "narrated_to"), stated_keys, key_of)

    # Three lists, because they are three different claims and the page may not blur them:
    # men a book says he learned from, men who merely stand beside him in an index of
    # chains, and the Imams, who everyone transmits from and who bury the rest if mixed in.
    def split(links):
        return ([m for m in links if m["kind"] != "imam" and m["stated"]],
                [m for m in links if m["kind"] != "imam" and not m["stated"]],
                [m for m in links if m["kind"] == "imam"])

    narrated_from, chain_from, imams_from = split(all_from)
    narrated_to, chain_to, imams_to = split(all_to)

    names_ar = [n["value"] for n in values(person, "primary_names") + values(person, "arabic_aliases")
                if n.get("value")]
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
    search_ar = searchable(sorted(headings_ar),
                           sorted({n["value"] for n in values(person, "arabic_aliases")
                                   if n.get("value")}), HOLDERS)
    names_en = [n["value"] for n in values(person, "english_aliases") if n.get("value")]

    return {
        "person_id": person["person_id"],
        # the anchor entry's own heading, not the longest name the merge found: that gave Ibn Abi
        # 'Umayr the garbled «أبو أحمد بن محمد بن زياد الأزدي»
        "display_ar": anchor.get("primary_arabic_name") or person.get("primary_arabic_name"),
        "display_en": anchor.get("primary_english_name") or person.get("primary_english_name"),
        "anchor_key": person.get("anchor"),
        "slug": slugify(anchor.get("primary_english_name") or person.get("primary_english_name")
                        or ""),
        "names_ar": sorted(set(names_ar)),
        "headings_ar": sorted(headings_ar),
        "search_ar": sorted(search_ar),
        "names_en": sorted(set(names_en)),
        "normalized_ar": sorted({normalize_arabic(n) for n in names_ar}),
        "normalized_en": sorted({n.lower() for n in names_en}),
        "kunyahs_ar": sorted({k["value"] for k in values(person, "kunyahs_arabic") if k.get("value")}),
        "titles": sorted({t["value"] for t in values(person, "titles") if t.get("value")}),
        "books": sorted({k.split(":")[0] for k in entry_keys}, key=lambda b: BOOK_ORDER.get(b, 99)),
        "entries": entries,
        "entry_count": len(entries),
        "verdicts": verdicts,
        "where_books_stand": where_books_stand,
        "for_him": sorted(for_him),
        "against_him": sorted(against_him),
        "standing": standing,
        "life": life,
        "blurb": blurb.strip(),
        "blurb_ar": blurb_ar.strip(),
        "for_him_ar": for_him_ar,
        "against_him_ar": against_him_ar,
        "generations": traced("generations"),
        "imam_ordinals": sorted(imams),
        "cities_or_tribes": traced("city_or_tribe"),
        "death_years": traced("death_years"),
        # Whom he takes from and who takes from him: the evidence a rijali argument about
        # his generation actually runs on, so the counts are kept whole even where the
        # lists shown are trimmed.
        # each name once, with the books that record the link, so the tab can show who says it
        "narrated_from": narrated_from,
        "narrated_to": narrated_to,
        "chain_from": chain_from,
        "chain_to": chain_to,
        "imams_from": imams_from,
        "imams_to": imams_to,
        # the counts the ledger shows are of men a book states he learned from or taught,
        # not of everyone who stands beside him in an index of chains
        "teacher_count": len(narrated_from),
        "student_count": len(narrated_to),
        "chain_only_count": len(chain_from) + len(chain_to),
        # the years his imamates cover, first to last
        "span_from": min((y[0] for y in spans), default=None),
        "span_to": max((y[1] for y in spans), default=None),
        "sect_flags": traced("sect_flags"),
        "mention_count": len(keys) - len(entry_keys),
        "holds_entry": bool(entry_keys),
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


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--live", action="store_true", help="create the index and load it")
    parser.add_argument("--dry-run", action="store_true", help="build the documents only")
    parser.add_argument("--all", action="store_true", help="index people holding no entry too")
    parser.add_argument("--show", help="print one person's document")
    parser.add_argument("--recreate", action="store_true",
                        help="drop the index first: needed when a field changes shape, since "
                             "a mapping inferred once (narrated_from as text) rejects the new "
                             "shape (an object) for every document")
    parser.add_argument("--es-host", default="http://localhost:9200")
    parser.add_argument("--index", default=INDEX)
    args = parser.parse_args()

    global YEARS, HOLDERS
    YEARS = imam_years()
    people = load(os.path.join(IDENTITY, "people.json"))
    HOLDERS = Counter()
    for person in people:
        if person.get("holds_entry") is False:
            continue
        forms = {normalize_arabic(i["value"]) for i in (person.get("primary_names") or [])
                 if i.get("value")}
        if person.get("primary_arabic_name"):
            forms.add(normalize_arabic(person["primary_arabic_name"]))
        for form in forms:
            HOLDERS[form] += 1
    source, headless = profiles(), not_entries(TMP)
    counts = Counter()
    documents = []
    for person in people:
        doc = document(person, source, headless)
        counts["holds an entry" if doc["holds_entry"] else "no entry, left out"] += 1
        if doc["holds_entry"] or args.all:
            documents.append(doc)
            counts["entries shown"] += doc["entry_count"]
            counts["verdicts shown"] += len(doc["verdicts"])
            counts["verdicts traced to an entry"] += sum(1 for v in doc["verdicts"] if v["source_key"])
    for label, n in counts.most_common():
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
