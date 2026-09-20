#!/usr/bin/env python3
"""Recover chapter titles from the books themselves rather than translating them.

The chapter titles in the index are English translations. Rendering those back into
Arabic produces plausible headings that the book does not contain: "The Sins" came back
as الذنوب والمعاصي where al-Kāfī reads باب الذنوب, and "Rare Ahadith" produced two
different renderings in the same corpus where the book says باب النوادر both times.

So the Arabic is recovered, not translated. OpenITI carries the printed texts with their
structure marked - ``### |`` for a kitāb, ``### ||`` for a bāb - and the index carries the
join: ``part`` is the kitāb and ``section`` is the bāb's ordinal within it, which is
thaqalayn's ``chapterInCategoryId``. The nth bāb of a kitāb is therefore identifiable
without matching any English string.

Alignment is checked rather than assumed: --verify reports how often the recovered
heading agrees with an existing translation, and the disagreements are printed so they
can be read. A drop in agreement is not evidence of a bad alignment here - the existing
values are the untrustworthy side - so the check is for reading, not for a threshold.

    python3 scripts/i18n/recover_chapter_titles.py --fetch --book al-kafi
    python3 scripts/i18n/recover_chapter_titles.py --align --book al-kafi --verify
"""

import argparse
import json
import pathlib
import re
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
MAPPING_DIR = ROOT / "src" / "main" / "resources" / "i18n"
CACHE = ROOT / "tmp" / "openiti"

# Each entry: the index's book name, and the OpenITI version id carrying its text.
SOURCES = {
    "al-kafi": {
        "book": "Al-Kāfi",
        "repo": "0350AH",
        "author": "0329IbnYacqubKulayni",
        "work": "0329IbnYacqubKulayni.Kafi",
        "version": "0329IbnYacqubKulayni.Kafi.Shia001122Vols-ara1.mARkdown",
        "level": 2,
        "nested": True,
    },
    "man-la-yahduruh": {
        "book": "Man Lā Yaḥḍuruh al-Faqīh",
        "repo": "0400AH",
        "author": "0381IbnBabawayhSaduq",
        "work": "0381IbnBabawayhSaduq.ManLaYahduruhuFaqih",
        "version": "0381IbnBabawayhSaduq.ManLaYahduruhuFaqih.Shia001149Vols-ara1.mARkdown",
        "level": 2,
        "nested": True,
    },
    "al-khisal": {
        "book": "Al-Khiṣāl",
        "repo": "0400AH",
        "author": "0381IbnBabawayhSaduq",
        "work": "0381IbnBabawayhSaduq.Khisal",
        "version": "0381IbnBabawayhSaduq.Khisal.Shia001137-ara1.completed",
        "level": 2,
        "nested": True,
    },
    "uyun-akhbar": {
        "book": "ʿUyūn akhbār al-Riḍā",
        "repo": "0400AH",
        "author": "0381IbnBabawayhSaduq",
        "work": "0381IbnBabawayhSaduq.CuyunAkhbarRida",
        "version": "0381IbnBabawayhSaduq.CuyunAkhbarRida.Shia001142Vols-ara1.completed",
        "level": 1,
        "nested": False,
    },
    "maani-al-akhbar": {
        "book": "Maʿānī al-ʾAkhbār",
        "repo": "0400AH",
        "author": "0381IbnBabawayhSaduq",
        "work": "0381IbnBabawayhSaduq.MacaniAkhbar",
        "version": "0381IbnBabawayhSaduq.MacaniAkhbar.Shia001148-ara1.completed",
        "level": 1,
        "nested": False,
    },
    "al-tawhid": {
        "book": "Al-Tawḥīd",
        "repo": "0400AH",
        "author": "0381IbnBabawayhSaduq",
        "work": "0381IbnBabawayhSaduq.Tawhid",
        "version": "0381IbnBabawayhSaduq.Tawhid.Shia001136-ara1.completed",
        "level": 1,
        "nested": False,
    },
    "thawab-al-amal": {
        "book": "Thawāb al-Aʿmāl wa ʿiqāb al-Aʿmāl",
        "repo": "0400AH",
        "author": "0381IbnBabawayhSaduq",
        "work": "0381IbnBabawayhSaduq.ThawabAcmal",
        "version": "0381IbnBabawayhSaduq.ThawabAcmal.Shia001138BK1-ara1.completed",
        # The title is Thawāb al-Aʿmāl *wa ʿIqāb al-Aʿmāl* and OpenITI keeps the two
        # halves in separate files. The index splits them the same way, into a Rewards
        # division of 461 chapters and a Punishments division of 132, against 467 and 133
        # headings. Read as one flat sequence they mis-join, which is how "Punishment of
        # Qadiris" came back as a ثواب heading.
        "also": [("0381IbnBabawayhSaduq.CiqabAcmal",
                  "0381IbnBabawayhSaduq.CiqabAcmal.Shia001138BK2-ara1.completed")],
        "level": 2,
        "nested": True,
    },
    "fadail-al-shia": {
        "book": "Faḍaʾil al-Shīʿa",
        "repo": "0400AH",
        "author": "0381IbnBabawayhSaduq",
        "work": "0381IbnBabawayhSaduq.FadailShica",
        "version": "0381IbnBabawayhSaduq.FadailShica.Shia001145-ara1.completed",
        "level": 1,
        "nested": False,
    },
    "nahj-al-balagha": {
        "book": "Nahj al-Balāgha",
        "repo": "0425AH",
        "author": "0406SharifRadi",
        "work": "0406SharifRadi.NahjBalagha",
        "version": "0406SharifRadi.NahjBalagha.Shia20200618-ara1.mARkdown",
        "level": 2,
        "nested": True,
    },
}


# The index's own division names, mapped to the kitāb each one is in the printed text.
#
# Curated rather than derived, because the two obvious candidates both fail.
# part_ar carries the same back-translation defect as the chapter titles - it says
# كتاب التجارة where al-Kāfī has كتاب المعيشة, كتاب الموت والدار الآخرة where the book
# has كتاب الجنائز, and كتاب الدواب for كتاب الدواجن. Matching on the Arabic name would
# therefore inherit those errors. Matching on bāb counts alone is ambiguous: the index
# holds two zakāt divisions, 46 and 43, against كتاب الزكاة (47) and أبواب الصدقة (43).
#
# The counts are the check, not the key: every pair below agrees to within two abwāb
# except where noted. Each line is a claim about a printed book and can be read as one.
KITAB_ALIASES = {
    "nahj-al-balagha": {
        "Sermons": "باب المختار من خطب أمير",
        "Letters": "باب المختار من كتب مولانا",
        "Sayings": "باب المختار من حكم أمير",
    },
    "al-khisal": {
        "Part 1: On One-Numbered Characteristics": "باب الواحد",
        "Part 2: On Two-Numbered Characteristics": "باب الاثنين",
        "Part 3: On Three-Numbered Characteristics": "باب الثلاثة",
        "Part 4: On Four-Numbered Characteristics": "باب الأربعة",
        "Part 5: On Five-Numbered Characteristics": "باب الخمسة",
        "Part 6: On Six-Numbered Characteristics": "باب الستة",
        "Part 7: On Seven-Numbered Characteristics": "باب السبعة",
        "Part 8: On Eight-Numbered Characteristics": "باب الثمانية",
        "Part 9: On Nine-Numbered Characteristics": "باب التسعة",
        "Part 10: On Ten-Numbered Characteristics": "باب العشرة",
    },
    "man-la-yahduruh": {
        "Book of Livelihood": "كتاب المعيشة",
        "Book of Marriage (nikah)": "كتاب النكاح",
        "Book of Divorce (talaq)": "كتاب الطلاق",
        "Book of Zakat": "أبواب الزكاة",
        "Chapters on Legal Cases and Rulings": "أبواب القضايا والأحكام",
    },
    "thawab-al-amal": {
        "Rewards": "كتاب ثواب الأعمال",
        "Punishments": "كتاب عقاب الأعمال",
    },
    "al-kafi": {
        "The Book of Belief and Disbelief": "كتاب الايمان والكفر",
        "The Book of Haj": "كتاب الحج",
        "The Book of Commerce": "كتاب المعيشة",
        "The Book about people with Divine Authority": "كتاب الحجة",
        "The Book of Prayer": "كتاب الصلاة",
        "The Book of Marriage": "كتاب النكاح",
        "The Book of Food": "كتاب الأطعمة",
        "The Book of Dresses, Beautification and Kindness": "كتاب الزي والتجمل والمروءة",
        "The Book on Dying People": "كتاب الجنائز",
        "The Book of Talaq (Divorces)": "كتاب الطلاق",
        "The Book of Fasting": "كتاب الصيام",
        "The Book of Supplication": "كتاب الدعاء",
        "The Book of Taharat (Cleansing)": "كتاب الطهارة",
        "The Book of Inheritance": "كتاب المواريث",
        "The Book of al-Zakat": "كتاب الزكاة",
        "The Book of Zakat": "أبواب الصدقة",
        "The Book of Drinks": "كتاب الأشربة",
        "The Book of Wills": "كتاب الوصايا",
        "The Book of ‘Aqiqah (Offering Animal Sacrifice for a Newborn Child)": "كتاب العقيقة",
        "The Book on Oneness of Allah (God)": "كتاب التوحيد",
        "The Book of Social Manners": "كتاب العشرة",
        "The Book on Virtue of Knowledge": "كتاب فضل العلم",
        "The Book of Jihad (Serving in the Army)": "كتاب الجهاد",
        "The Book of Oaths, Vows and Expiations": "كتاب الايمان والنذور والكفارات",
        "The Book of the Excellence of the Holy Quran": "كتاب فضل القرآن",
        "The Book of Testimony": "كتاب الشهادات",
        "The Book of Hunting": "كتاب الصيد",
        "The Book of Domestic Animals": "كتاب الدواجن",
        "The Book of Hayd (menses)": "كتاب الحيض",
        "The Book of Adjudication and Rules": "كتاب القضاء والأحكام",
        "The Book of Slaughtering Animals for Food": "كتاب الذبائح",
    },
}

# Divisions with no bāb structure to align against, and why. Left alone rather than
# guessed at: a heading invented for these would be indistinguishable from a recovered one.
NO_STRUCTURE = {
    "thawab-al-amal": {
        # A preface, not a division with numbered abwāb.
        "Introduction": "not a kitāb",
    },
    "al-kafi": {
        # The book runs straight through in the printed text; it has no abwāb to number.
        "The Book of Intelligence and Ignorance": "undivided in the source",
        # al-Kāfī's preface, which is not a kitāb.
        "Introduction": "not a kitāb",
        # The Rawḍa is a miscellany, not a divided book: the index counts 594 chapters
        # where the text carries 51 headings, so an ordinal means different things on
        # each side and cannot be joined.
        "The Book - Garden (of Flowers)": "miscellany; 594 index chapters vs 51 headings",
    },
}


# Books whose recovery is not yet trustworthy, and why. Checked by reading the output:
# a matching chapter count is not evidence of an alignment.
#
#   thawab-al-amal   592 recovered against 592 chapters, and misaligned. The book is
#                    Thawāb al-Aʿmāl *wa ʿIqāb al-Aʿmāl* and OpenITI splits it in two -
#                    ThawabAcmal...BK1 and CiqabAcmal...BK2 - so only the rewards were
#                    fetched while the index numbers rewards and punishments in one
#                    sequence. Chapter 10, "Punishment of Qadiris", came back as a ثواب
#                    heading. Needs both parts merged in order before it can be used.
#
#   fadail-al-shia   45 recovered and correctly aligned, but the source carries no
#                    chapter titles: its headings are الحديث الأول, الحديث العاشر. The
#                    English titles in the index are descriptions someone wrote, not
#                    translations of a heading, so there is nothing to recover here.
#
#   al-khisal        nested under kutub and has no KITAB_ALIASES entry yet, so nothing
#   man-la-yahduruh  aligns (al-Khiṣāl) or only the divisions whose Arabic name happens
#   nahj-al-balagha  to match do (the other two).
NEEDS_WORK = {"thawab-al-amal", "fadail-al-shia", "al-khisal", "man-la-yahduruh",
              "nahj-al-balagha", "uyun-akhbar"}

RAW = "https://raw.githubusercontent.com/OpenITI/{repo}/master/data/{author}/{work}/{version}"



ARABIC_DIGITS = str.maketrans("٠١٢٣٤٥٦٧٨٩", "0123456789")


def numbered(title):
    """Split a heading into the ordinal it states and the heading itself.

    Several of these texts number their own abwāb - "9 - باب القدرة", "١٢ - باب تفسير..." -
    in either set of digits. Where they do, that number is the join rather than the
    heading's position in the file, which is the safer key: a stray heading near the front
    of al-Tawḥīd shifted every chapter by one and produced titles that were plausible,
    adjacent, and wrong.
    """
    text = title.strip()
    match = re.match(r"^\s*([0-9\u0660-\u0669]{1,4})\s*[-–—.]\s*(.+)$", text)
    if not match:
        return None, text
    number = match.group(1).translate(ARABIC_DIGITS)
    try:
        return int(number), match.group(2).strip()
    except ValueError:
        return None, text


def normalise(text):
    """Fold the spellings that differ between editions but not in meaning."""
    text = re.sub(r"[\[\]()]", "", text or "")
    text = re.sub(r"[ً-ْـ]", "", text)      # harakat and tatweel
    text = (text.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
                .replace("ى", "ي").replace("ة", "ه"))
    return re.sub(r"\s+", " ", text).strip()


def bare(title):
    """A bāb title without its leading word, for comparing two headings."""
    return re.sub(r"^باب\s+", "", normalise(title))


def fetch(key):
    spec = SOURCES[key]
    CACHE.mkdir(parents=True, exist_ok=True)
    target = CACHE / f"{key}.txt"
    if target.exists():
        print(f"  cached: {target}")
        return target
    parts = [RAW.format(**spec)]
    for work, version in spec.get("also", []):
        parts.append(RAW.format(repo=spec["repo"], author=spec["author"],
                                work=work, version=version))
    chunks = []
    for url in parts:
        print(f"  fetching {url}")
        with urllib.request.urlopen(url, timeout=300) as resp:
            chunks.append(resp.read())
    # Concatenated, so each file's own "### |" stays a kitāb of its own.
    target.write_bytes(b"\n".join(chunks))
    print(f"  wrote {target} ({target.stat().st_size:,} bytes)")
    return target


def parse_structure(path, level=2):
    """The text's own table of contents: each kitāb with its abwāb in order.

    Books mark their chapters at different depths. al-Kāfī and al-Khiṣāl nest abwāb under
    kutub, so the chapters are at ``### ||``; al-Tawḥīd and Maʿānī al-ʾAkhbār have no kitāb
    layer and put their abwāb at ``### |``. Reading the wrong depth silently yields a
    handful of headings instead of hundreds, so the level is configured per book and
    checked against the index's own chapter count.
    """
    def clean(line):
        line = re.sub(r"^\s*#+\s*\|+\s*", "", line)
        line = line.replace("*", " ")
        # OpenITI keeps manuscript shelf marks inline, at the end of a heading and inside
        # one: "كتاب الحج ms1683", "باب ms0826 خلف الوعد". A word boundary does not hold
        # between an Arabic letter and a Latin one, so this matches without one.
        line = re.sub(r"ms\d+", " ", line)
        line = re.sub(r"[()‏]", " ", line)
        return re.sub(r"\s+", " ", line).strip()

    kutub, current = [], None
    if level == 1:
        # Flat: every top-level heading is a chapter, gathered under one pseudo-kitāb.
        current = {"kitab": "", "abwab": []}
        kutub.append(current)
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            if re.match(r"^### \|[^|]", line):
                title = clean(line)
                if title:
                    current["abwab"].append(title)
        return kutub

    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if re.match(r"^### \|[^|]", line) or line.strip() == "### |":
            title = clean(line)
            if title:
                current = {"kitab": title, "abwab": []}
                kutub.append(current)
        elif line.startswith("### ||") and current is not None:
            title = clean(line)
            if title:
                current["abwab"].append(title)
    return kutub


def index_chapters(es_host, book):
    """Every (part, section) the index holds for a book, with its English chapter."""
    query = {
        "size": 0,
        "query": {"term": {"book": book}},
        "aggs": {"p": {"terms": {"field": "part", "size": 100},
                       "aggs": {"s": {"terms": {"field": "section", "size": 500},
                                      "aggs": {"c": {"terms": {"field": "chapter.keyword",
                                                               "size": 1}}}}}}},
    }
    request = urllib.request.Request(f"{es_host}/rewayaat_hadith/_search",
                                     data=json.dumps(query).encode(),
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as resp:
        return json.load(resp)["aggregations"]["p"]["buckets"]


def align(kutub, parts, part_ar, book_key, nested=True):
    """Join the index to the text on kitāb and bāb ordinal."""
    by_name = [(normalise(k["kitab"]), k["abwab"]) for k in kutub]

    def kitab_for(arabic_name):
        # OpenITI titles carry extra words - "من كتاب الكافي" - so an exact match is
        # tried first and containment second.
        if not arabic_name:
            return None
        target = normalise(arabic_name)
        for name, abwab in by_name:
            if name == target:
                return abwab
        for name, abwab in by_name:
            if target and abwab and (target in name or name in target):
                return abwab
        return None

    if not nested:
        # No kitāb layer in the text, so the ordinal runs across the book. Every division
        # the index shows is a view of the same sequence.
        raw = kutub[0]["abwab"] if kutub else []
        # Where the text numbers its own abwāb, index them by that number; the file's
        # order is only a fallback for texts that do not.
        by_number, positional = {}, []
        for entry in raw:
            stated, heading = numbered(entry)
            positional.append(heading)
            if stated is not None and stated not in by_number:
                by_number[stated] = heading
        stated_share = len(by_number) / max(len(raw), 1)

        recovered = {}
        for part in parts:
            for section in part["s"]["buckets"]:
                try:
                    ordinal = int(section["key"])
                except (TypeError, ValueError):
                    continue
                chapters = section["c"]["buckets"]
                if not chapters:
                    continue
                if stated_share > 0.5:
                    if ordinal in by_number:
                        recovered[chapters[0]["key"]] = by_number[ordinal]
                elif 1 <= ordinal <= len(positional):
                    recovered[chapters[0]["key"]] = positional[ordinal - 1]
        return recovered, [], []

    aliases = KITAB_ALIASES.get(book_key, {})
    skip = NO_STRUCTURE.get(book_key, {})
    recovered, unmatched, skipped = {}, [], []
    for part in parts:
        if part["key"] in skip:
            skipped.append((part["key"], skip[part["key"]]))
            continue
        # The curated name first; part_ar only where the index has no claim to check.
        abwab = kitab_for(aliases.get(part["key"]) or part_ar.get(part["key"]))
        if not abwab:
            unmatched.append(part["key"])
            continue
        for section in part["s"]["buckets"]:
            try:
                ordinal = int(section["key"])
            except (TypeError, ValueError):
                continue
            chapters = section["c"]["buckets"]
            if not chapters:
                continue
            stated = {}
            for entry in abwab:
                n, heading = numbered(entry)
                if n is not None and n not in stated:
                    stated[n] = heading
            if len(stated) > len(abwab) / 2:
                if ordinal in stated:
                    recovered[chapters[0]["key"]] = stated[ordinal]
            elif 1 <= ordinal <= len(abwab):
                recovered[chapters[0]["key"]] = numbered(abwab[ordinal - 1])[1]
    return recovered, unmatched, skipped


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--book", default="al-kafi", choices=sorted(SOURCES))
    parser.add_argument("--es-host", default="http://localhost:9200")
    parser.add_argument("--fetch", action="store_true", help="Download the source text")
    parser.add_argument("--align", action="store_true", help="Recover titles and write them out")
    parser.add_argument("--verify", action="store_true",
                        help="Report agreement with the existing translations")
    args = parser.parse_args()

    spec = SOURCES[args.book]
    path = fetch(args.book) if (args.fetch or args.align) else None
    if not args.align:
        return 0

    kutub = parse_structure(path, spec.get("level", 2))
    print(f"  {len(kutub)} kutub, {sum(len(k['abwab']) for k in kutub)} abwāb")

    part_ar = json.loads((MAPPING_DIR / "part_ar_mapping.json").read_text(encoding="utf-8"))
    parts = index_chapters(args.es_host, spec["book"])
    recovered, unmatched, skipped = align(kutub, parts, part_ar, args.book,
                                          spec.get("nested", True))
    print(f"  recovered {len(recovered)} chapter titles; {len(unmatched)} kutub unmatched")
    for name in unmatched:
        print(f"    unmatched kitāb: {name}")
    for name, why in skipped:
        print(f"    no structure to align: {name} ({why})")

    if args.verify:
        existing = json.loads((MAPPING_DIR / "chapter_ar_mapping.json").read_text(encoding="utf-8"))
        both = {en: existing[en] for en in recovered if existing.get(en)}
        agree = sum(1 for en, ar in both.items() if bare(ar) == bare(recovered[en]))
        pct = agree * 100 // max(len(both), 1)
        print(f"\n  had Arabic already: {len(both)}; source agrees: {agree} ({pct}%)")
        print("  disagreements are expected: the existing values were translated from the")
        print("  English rather than taken from the book.")

    out = CACHE / f"{args.book}_recovered.json"
    out.write_text(json.dumps(recovered, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"\n  written: {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
