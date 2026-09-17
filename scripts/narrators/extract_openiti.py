#!/usr/bin/env python3
"""Extract a Rijal book from the OpenITI text, by rule rather than by agent.

Phase 1 was called done on page coverage rather than on entry yield, and three books came out
truncated: Rijal al-Tusi kept 123 entries of 6,429, al-Fihrist 731 of 910, al-Kashshi 209 of 430.
That shortfall is most of why the corpus holds people with no main-book entry to attach to — the
entry they belong to was never extracted.

The OpenITI corpus carries these books openly licensed and machine-readable, in the same printed
editions our page numbers came from, with markup regular enough to parse:

    ### | أصحاب أبي عبد الله جعفر بن محمد الصادق     al-Tusi: the Imam whose companions follow
    ### || باب الهمزة                                 the alphabet chapter, or الكنى / النساء
    ### $ 1229 - إبراهيم بن حيان الأسدي الكوفي، نزل   the entry, numbered as the edition numbers it
    ~~واسط.                                           its continuation
    ~~PageV00P121                                     the printed page

al-Fihrist numbers its entries «### $ [17] 5 - name» (the edition's number, then the number within
the chapter); al-Kashshi has no entry numbers at all and heads each man with «### | في فلان», so
there the heading is the entry.

No agent reads a page here. What a rule can take faithfully it takes — the heading, the body as the
source's own words, the printed page, the Imam whose companion the man was, and a kunyah or a grade
word where the text states one. What a rule cannot take honestly it leaves empty: teachers and
students are named in running prose, and a regex that guessed at them would put invented chains
into a corpus that publishes reliability verdicts. The body is kept whole in `source_assessments`,
so every later pass still reads what the author actually wrote.

The extractor refuses to write if it parsed fewer entries than the file has entry markers. Silent
loss is what left these books truncated in the first place. Where a book's own numbering has gaps
it says so and names them, since a printed edition may itself skip a number.

Writes tmp/narrators_book_{book}_openiti.json (with --append, numbering the new entries after the
ones already on file, so no recorded decision is invalidated).

Usage:
    python3 scripts/narrators/extract_openiti.py --book tusi --dry-run
    python3 scripts/narrators/extract_openiti.py --book fihrist --append
"""

import argparse
from collections import Counter
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from identity import write_json_atomic  # noqa: E402
from narrator_schema import GRADE_KEYWORDS_AR, normalize_arabic  # noqa: E402

REPO = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
TMP = os.path.join(REPO, "tmp")
SOURCES = os.path.join(TMP, "narrators_sources")

BOOKS = {
    "tusi": {
        "source": "tusi_rijal_pri.mARkdown",
        "title": "Rijal al-Tusi",
        "author": "Muhammad ibn al-Hasan al-Tusi",
        # OpenITI drops a milestone («ms018») between the number and the dash in 18 entries
        "entry": re.compile(r"^###\s+\$+\s*(?P<number>\d+)\s*(?:ms\d+\s*)?-\s*(?P<name>.*?)\s*$"),
        "marker": re.compile(r"^###\s+\$"),
        # al-Tusi's sections are the Imams, so the section a man stands under is his generation
        "sections_are_generations": True,
    },
    "fihrist": {
        "source": "fihrist_pri.mARkdown",
        "title": "al-Fihrist",
        "author": "Muhammad ibn al-Hasan al-Tusi",
        # «[17] 5 - name»: the edition's number, then the number within the chapter, either of
        # which a milestone may interrupt and the second of which is sometimes absent
        "entry": re.compile(r"^###\s+\$+\s*\[(?P<number>\d+)\]\s*(?:\d+\s*)?(?:ms\d+\s*)?"
                            r"-?\s*(?P<name>.*?)\s*$"),
        "marker": re.compile(r"^###\s+\$"),
        # its sections are the alphabet, which says nothing about when a man lived
        "sections_are_generations": False,
    },
    "kashshi": {
        "source": "kashshi_pri.mARkdown",
        "title": "Ikhtiyar Ma'rifat al-Rijal",
        "author": "Muhammad ibn 'Umar al-Kashshi",
        # no entry numbers: the heading itself is the entry
        "entry": re.compile(r"^###\s+\|(?!\|)\s*(?P<name>.+?)\s*$"),
        "marker": re.compile(r"^###\s+\|(?!\|)\s*\S"),
        "sections_are_generations": False,
        # the heading stands on its own line here, so the name ends where the heading does
        "heading_is_standalone": True,
        # and the body under it is pages of reports, where «ثقة» may be a word in a story rather
        # than a verdict on this man. A rule cannot tell those apart, so it grades nothing and
        # normalize's `grade_recoverable` flag leaves the verdicts for a pass that reads them.
        "read_verdicts": False,
        # «في فلان», «ما روى في فلان» — the heading says «on so-and-so»
        "heading_strip": re.compile(r"^(?:ما\s+رو[ىي]\s+)?(?:في\s+(?:ذكر\s+)?|ومن\s+)"),
    },
}

# OpenITI's tag for text that is not the book: a title page, a colophon, a publisher's note
PARATEXT = re.compile(r"PARATEXT")
SECTION = re.compile(r"^###\s+\|(?!\|)\s*(.*?)\s*$")
CHAPTER = re.compile(r"^###\s+\|\|\s*(.+?)\s*$")
PAGE = re.compile(r"PageV(\d+)P(\d+)")
# OpenITI's own marks, not the book's words: milestones, page rules, footnote digits
NOISE = re.compile(r"ms\d+|PageV\d+P\d+|^#\s*-+\s*|\[\s*\d+\s*\]|~~|^#+\s*")
KUNYAH = re.compile(r"يكن[يى]\s+(أب[اوي][^،.,]{2,20})")
HEAD_KUNYAH = re.compile(r"^(أب[وا]\s+\S+)")
COMMA_KUNYAH = re.compile(r"[،,]\s*(أب[وا]\s+\S+)")
# the heading runs «name، description»: a name carries no comma, and none of these words either
# these editions mark a footnote with a bare digit, which is not part of anyone's name
FOOTNOTE_DIGIT = re.compile(r"(?:(?<=\s)|^)\d+(?=\s|$)")
NAME_END = re.compile(r"[،,؛:.(\[]|\s(?:نزل|روى|يكن|كان|مات|قتل|أسند|من أصحاب|له كتاب|ثقة|ضعيف)\s")


def clean(line):
    return NOISE.sub(" ", line).strip()


def parse(text, book, counts):
    """Every entry the book's markup marks, with the section and page it stands under."""
    entry_re, chapter_first = book["entry"], book["entry"].pattern.startswith("^###\\s+\\|")
    section, chapter, page = None, None, None
    entries, current = [], None

    def close():
        if current and current["body"]:
            current["body"] = re.sub(r"\s+", " ", " ".join(current["body"])).strip()
        if current:
            entries.append(current)

    for raw in text.splitlines():
        found = PAGE.search(raw)
        if found:
            page = int(found.group(2))
            if current is not None and page not in current["pages"]:
                current["pages"].append(page)
        if CHAPTER.match(raw):
            chapter = CHAPTER.match(raw).group(1)
            continue
        match = entry_re.match(raw)
        if match and not PARATEXT.search(raw):
            close()
            counts["entries"] += 1
            if section:
                counts[f"section: {section[:40]}"] += 1
            head = clean(match.group("name"))
            number = int(match.group("number")) if "number" in match.groupdict() else None
            current = {"number": number, "name": head, "section": section, "raw": raw,
                       "chapter": chapter, "page": page, "pages": [page] if page else [],
                       "body": [head]}
            continue
        if not chapter_first and SECTION.match(raw):
            section, chapter = SECTION.match(raw).group(1) or None, None
            continue
        if raw.startswith("#META#") or raw.startswith("######"):
            continue
        if current is not None:
            body = clean(raw)
            if body:
                current["body"].append(body)
    close()
    return entries


AR = r"[\u0621-\u064A]"

# al-Tusi's verdicts, each mapped to what the schema calls it. The word must stand alone: «غال»
# lives inside «غالب» and «حسن» is al-Hasan's name in 368 entries, which is why a substring
# match graded them all and why «حسن» is not read as a verdict here at all.
GRADES_AR = (("ثقة", "thiqa"), ("ثقه", "thiqa"), ("صدوق", "saduq"), ("ضعيف", "daif"),
             ("ضعيفة", "daif"), ("مجهول", "majhul"), ("مهمل", "muhmal"),
             ("كذاب", "kadhdhab"), ("وضاع", "kadhdhab"))
FLAGS_AR = (("واقفي", "waqifi"), ("واقف", "waqifi"), ("واقفة", "waqifi"),
            ("غالي", "ghali"), ("غال", "ghali"), ("فطحي", "fathi"), ("زيدي", "zaydi"))
# «روى عنه فلان الثقة» grades the student, not the man whose entry this is
STUDENT_CLAUSE = re.compile(r"رو[ىي]\s+عنه[^.]{0,40}$")


def whole_word(word, text):
    """Every position where `word` stands as a word of its own."""
    return [m.start() for m in re.finditer(rf"(?<!{AR}){re.escape(word)}(?!{AR})", text)]


def grade_and_flags(text, counts):
    """The verdict al-Tusi states himself, or None where he states none."""
    grade, flags = None, []
    for word, canonical in GRADES_AR + FLAGS_AR:
        for at in whole_word(word, text):
            if STUDENT_CLAUSE.search(text[:at]):
                counts["verdict belongs to the student"] += 1
                continue
            if canonical in [g for _, g in GRADES_AR]:
                grade = grade or canonical
            elif canonical not in flags:
                flags.append(canonical)
            break
    if grade:
        counts["grade stated"] += 1
    if flags:
        counts["sect flag stated"] += 1
    if not grade and not flags:
        return None
    return ", ".join(([grade] if grade else []) + flags)


def profile(entry, book, counts):
    """One entry as the extraction contract shapes it."""
    body = entry["body"] or entry["name"]
    # where the heading runs into the biography the name can continue past the heading line, and
    # OpenITI's line wrapping can break it in two; where the heading stands alone it does not
    read_from = entry["name"] if book.get("heading_is_standalone") else body
    name = NAME_END.split(read_from, 1)[0].rstrip(".،,؛:").strip() or entry["name"]
    name = re.sub(r"\s+", " ", FOOTNOTE_DIGIT.sub(" ", name)).strip() or name
    if book.get("heading_strip"):
        name = book["heading_strip"].sub("", name).strip() or name
    kunyah = None
    found = KUNYAH.search(body) or HEAD_KUNYAH.match(name) or COMMA_KUNYAH.search(entry["name"])
    if found:
        kunyah = found.group(1).strip()
        counts["kunyah found"] += 1
    grade = grade_and_flags(body, counts) if book.get("read_verdicts", True) else None
    female = entry["chapter"] and "النساء" in entry["chapter"]
    where = (f"printed entry {entry['number']}" if entry["number"] is not None
             else f"the entry headed «{entry['name'][:40]}»")
    return {
        "primary_arabic_name": name,
        "primary_english_name": None,
        "arabic_aliases": [],
        "english_aliases": [],
        "kunyah_arabic": kunyah,
        "kunyah_english": None,
        "titles": [],
        "reliability_grade": grade,
        "is_doubtful": False,
        "doubtful_reason": None,
        # these books name teachers and students in prose; a regex would invent them, so they
        # are left to the passes that read the text itself.
        "narrated_from": [],
        "narrated_to": [],
        "city_or_tribe": None,
        "generation": entry["section"] if book["sections_are_generations"] else None,
        "death_year_hijri": None,
        "gender": "female" if female else "male",
        "notes": f"{book['title']}, {where}"
                 + (f"; {entry['chapter']}" if entry["chapter"] else "")
                 + (f"; under {entry['section']}" if entry["section"]
                    and not book["sections_are_generations"] else ""),
        "source_assessments": [{
            "source_name": book["title"],
            "author": book["author"],
            "assessment_ar": body,
            "assessment_en": None,
        }],
        "rijal_sources": [book["title"]],
        "source_pages": entry["pages"],
    }


def strip_conjunction_waw(profiles, counts):
    """al-Tusi runs a list of names joined by «و», so a heading can open with a conjunction.

    «وهب», «وائل», «وردان» are names that begin with the letter; «وعمرو بن يحيى» is «and ʿAmr».
    Two signals separate them: the corpus itself, where the bare first token is common and the
    waw-bearing one is not, and the printed run, where a conjunction entry follows another on
    the same page.
    """
    first = Counter(normalize_arabic(p["primary_arabic_name"]).split()[0]
                    for p in profiles if normalize_arabic(p["primary_arabic_name"] or "").split())
    previous_was = False
    for n, prof in enumerate(profiles):
        name = prof["primary_arabic_name"]
        if not name.startswith("و") or len(name) < 3:
            previous_was = False
            continue
        token = normalize_arabic(name).split()[0]
        common = first.get(token[1:], 0) >= max(3, first.get(token, 0) * 3)
        run = previous_was and n and prof["source_pages"] == profiles[n - 1]["source_pages"]
        if common or run:
            prof["primary_arabic_name"] = name[1:].strip()
            counts["conjunction waw stripped"] += 1
            previous_was = True
        else:
            previous_was = False


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--book", required=True, choices=sorted(BOOKS))
    parser.add_argument("--source", default=None)
    parser.add_argument("--append", action="store_true",
                        help="number the new entries after the ones already on file")
    parser.add_argument("--dry-run", action="store_true", help="count, write nothing")
    args = parser.parse_args()
    book = BOOKS[args.book]
    source = args.source or os.path.join(SOURCES, book["source"])
    on_file = os.path.join(TMP, f"narrators_book_{args.book}.json")
    out = os.path.join(TMP, f"narrators_book_{args.book}_openiti.json")

    with open(source) as handle:
        text = handle.read()
    counts = Counter()
    entries = parse(text, book, counts)
    marked = [line for line in text.splitlines()
              if book["marker"].match(line) and not PARATEXT.search(line)]
    markers = len(marked)
    with open(on_file) as handle:
        existing = json.load(handle)
    existing = existing if isinstance(existing, list) else existing.get("profiles", existing)
    # a source key is book:index = POSITION in the raw file, so appended entries start past it
    start = len(existing) if args.append else 0

    profiles = [profile(e, book, counts) for e in entries]
    strip_conjunction_waw(profiles, counts)
    print(f"parsed {len(profiles)} entries of {markers} marked in {source}")
    pages = {p["source_pages"][0] for p in profiles if p["source_pages"]}
    print(f"  pages seen {len(pages)} (min {min(pages) if pages else 0}, "
          f"max {max(pages) if pages else 0}); indices {start}..{start + len(profiles) - 1}")
    print(f"  entries already on file: {len(existing)}")
    for key in ("kunyah found", "grade stated", "sect flag stated",
                "verdict belongs to the student", "conjunction waw stripped"):
        if counts[key]:
            print(f"  {key}: {counts[key]}")
    for key, n in sorted(counts.items()):
        if key.startswith("section: "):
            print(f"     {n:5d} under {key[9:]}")

    # An entry the parser did not take is an entry lost in silence, which is what truncated
    # these books before. A gap in the edition's own numbering is a different matter: it is
    # reported and named, because a printed edition may skip a number itself.
    if len(profiles) < markers:
        taken = {e["raw"] for e in entries}
        missed = [line for line in marked if line not in taken][:10]
        print(f"  REFUSING: {markers - len(profiles)} marked entries were not parsed")
        for line in missed:
            print(f"    MISSED: {line[:100]}")
        raise SystemExit(1)
    numbers = {e["number"] for e in entries if e["number"] is not None}
    if numbers:
        absent = [n for n in range(1, max(numbers) + 1) if n not in numbers]
        print(f"  the edition numbers its entries 1..{max(numbers)}; "
              f"{len(absent)} numbers are absent from the markup"
              + (f", first {absent[:10]}" if absent else ""))

    if args.dry_run:
        for n in (0, 1, 2, len(profiles) - 2, len(profiles) - 1):
            prof = profiles[n]
            print(f"    [{start + n}] p{prof['source_pages']} «{prof['primary_arabic_name'][:40]}» "
                  f"gen={(prof['generation'] or '')[:24]} grade={prof['reliability_grade']}")
        return
    # append-only: the entries already on file keep their positions, and so their source keys
    combined = (existing + profiles) if args.append else profiles
    write_json_atomic(out, combined)
    print(f"\nwrote {len(combined)} profiles ({len(existing)} kept + {len(profiles)} new) -> {out}")


if __name__ == "__main__":
    main()
