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
import bisect
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
    "khoei": {
        "source": "khoei_mujam.txt",
        "title": "Mu'jam Rijal al-Hadith",
        "author": "Abu al-Qasim al-Khoei",
        "entry": re.compile(r"^###\s+\$+\s*(?P<number>\d+)\s*(?:ms\d+\s*)?-\s*(?P<name>.*?)\s*$"),
        "marker": re.compile(r"^###\s+\$+\s*\d+\s*(?:ms\d+\s*)?-"),
        # Khoei numbers the reports he quotes inside a long entry, and a list of the four books'
        # parts, with the same «### $ n -» mark as the entries themselves, restarting at 1 each
        # time. The entries are the marked lines whose numbers run in order through the book.
        "layout": "numbered_ordered",
        "not_heading": re.compile(r"^(?:\"|«|كتب\s+الجزء|كتاب\s+الجزء|الجزء\s+ال|حدث|وجدت|روى|قال|"
                                  r"ذكر|وبهذا|أن |أنه |أقول|ابن مهران،)"),
        "sections_are_generations": False,
        # an entry quotes al-Najashi, al-Shaykh and Ibn al-Ghada'iri on this man and on others,
        # so a verdict word in it is not Khoei's verdict on this man by rule
        "read_verdicts": False,
        "multi_volume": True,
        # each volume closes with a contents list («فهرست كتب أجزاء الكافي») that is not the last
        # entry's text; it ends that entry, and nothing after it joins a body until the next entry
        "body_ends": re.compile(r"^#+\s*فهرس|^#+ENDNOTES"),
    },
    "ardabili": {
        "source": "ardabili_jamic.txt",
        "title": "Jami' al-Ruwat",
        "author": "Muhammad ibn 'Ali al-Ardabili",
        # this book marks no entries at all — see parse_running() below
        "layout": "running",
        "sections_are_generations": False,
        # its entries are chains as often as biography, and «ثقة» in one grades whoever the
        # chain is about, so no verdict is read by rule here
        "read_verdicts": False,
    },
}

# --- Jami' al-Ruwat ---------------------------------------------------------
#
# Its text carries no entry marks. An entry and a chain citation both open a paragraph with «# »,
# and an entry can begin in the middle of one, after the «(مح)» closing al-Ardabili's own remark
# on the man before. What the book does have is order: chapters run through the alphabet, and
# within a chapter the entries run through it again. So a paragraph — or a piece of one following
# a «(مح)» — opens an entry when its leading name begins with the chapter's letter and follows
# the last entry in the book's own collation. Citations break that order, and are left out by
# keeping the longest run that does not: they are noise around a monotone sequence.
#
# Measured against the 1,796 entries phase 1 read independently, this recovers 84% of them, and
# 18 of 18 sampled entries are entries rather than citations. What it cannot do is prove a count:
# the book states no entry numbers, so there is nothing to check a total against, and the
# extractor reports every piece it set aside instead.
CHAPTER_LETTERS = {
    "الالف": "ا", "الهمزه": "ا", "الباء": "ب", "التاء": "ت", "الثاء": "ث", "الجيم": "ج",
    "الحاء": "ح", "الخاء": "خ", "الدال": "د", "الذال": "ذ", "الراء": "ر", "الزاي": "ز",
    "السين": "س", "الشين": "ش", "الصاد": "ص", "الضاد": "ض", "الطاء": "ط", "الظاء": "ظ",
    "العين": "ع", "الغين": "غ", "الفاء": "ف", "القاف": "ق", "الكاف": "ك", "اللام": "ل",
    "الميم": "م", "النون": "ن", "الهاء": "ه", "الواو": "و", "الياء": "ي", "الكني": "كنى",
}
CHAPTER_HEAD = re.compile(r"^#\s+(?:بسم الله الرحمن الرحيم\s+)?باب\s+(\S+)")
# «(مح)» closes al-Ardabili's remark on a man; the kunya chapter writes it " مح "
REMARK_END = re.compile("[\\(\"«]\\s*مح\\s*[\\)\"»]\\s*\\.?")
DIACRITICS = re.compile(r"[\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06ED\u0640]")
NAME_STOP = {"عن", "عنه", "روى", "رواه", "ذكره", "يروي", "يروى", "عده", "قال", "وقال",
             "وروى", "وعنه", "وفي", "وفى", "تقدم", "يأتي"}
FILLER = ("بن", "ابن", "بنت")
# The book's own collation: hamza forms count as alif, but «آ» sorts before everything — آدم
# opens the alif chapter ahead of أبان and إبراهيم, which folding it to alif would reverse.
COLLATE = str.maketrans({"آ": "\u0600", "أ": "ا", "إ": "ا", "ٱ": "ا", "ء": "ا",
                         "ى": "ي", "ة": "ه", "ؤ": "و", "ئ": "ي"})
KUNYA_OPENERS = ("أبو", "ابو", "أبي", "ابي", "أبا", "ابا", "ابن", "أم")

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


def ordered_entry_lines(lines, book, counts):
    """The marked lines that are entries: numbered in order through the book, not quoted reports.

    A marked line opens an entry when it is not a quotation or a list item, and its number falls
    on the longest run of numbers that never goes backwards. A line off that run whose number no
    kept line carries is taken back: it is an entry whose neighbour's number is out of place.
    """
    candidates = []
    for n, line in enumerate(lines):
        found = book["entry"].match(line)
        if not found:
            continue
        head = NOISE.sub(" ", found.group("name")).strip()
        if book["not_heading"].match(head):
            counts["marked line that is a quotation or list item"] += 1
            continue
        candidates.append((int(found.group("number")), n, head))
    kept = longest_ordered_run([number for number, _, _ in candidates])
    numbers = {candidates[k][0] for k in kept}
    starts = {candidates[k][1] for k in kept}
    for k, (number, n, head) in enumerate(candidates):
        if k in kept:
            continue
        if number not in numbers and head.rstrip(". ").endswith(":"):
            starts.add(n)
            numbers.add(number)
            counts["entry taken back off the ordered run"] += 1
        else:
            counts["marked line off the ordered run"] += 1
    return starts


def recover_inline_entries(lines, book, counts):
    """Put back on a line of its own each entry the markup left inside another line.

    OpenITI tags an entry «### $ n - name:», and for a few hundred of Khoei's it did not: the
    heading runs on inside the line before, «… (189) 42 - أبان …». Such an entry is still pinned
    by the numbers around it, so it is looked for only between the entries numbered below and
    above it, and taken only where exactly one «n - name:» stands there.
    """
    starts = ordered_entry_lines(lines, book, counts)
    number_at = {}
    for n in sorted(starts):
        found = book["entry"].match(lines[n])
        number_at.setdefault(int(found.group("number")), n)
    present = sorted(number_at)
    last = max(present)
    splits = {}
    for number in range(1, last + 1):
        if number in number_at:
            continue
        below = [p for p in present if p < number]
        above = [p for p in present if p > number]
        lo = number_at[below[-1]] if below else 0
        hi = number_at[above[0]] if above else len(lines)
        pattern = re.compile(rf"(?<![\d\u0660-\u0669]){number}\s*(?:ms\d+\s*)?-\s*"
                             rf"[\u0621-\u064A][^:\n]{{0,90}}:")
        spots = [(n, m.start()) for n in range(lo, hi) for m in pattern.finditer(lines[n])]
        if len(spots) == 1:
            splits.setdefault(spots[0][0], []).append(spots[0][1])
            counts["entry recovered from inside a line"] += 1
        else:
            counts["entry missing from the markup"] += 1
    out = []
    for n, line in enumerate(lines):
        if n not in splits:
            out.append(line)
            continue
        cut = 0
        for at in sorted(splits[n]):
            out.append(line[cut:at].rstrip())
            cut = at
            out.append(None)                 # placeholder: the next piece is an entry heading
        rest = line[cut:]
        out[-1] = "### $ " + rest
    return [l for l in out if l is not None and l != ""]



def parse(text, book, counts):
    """Every entry the book's markup marks, with the section and page it stands under."""
    entry_re, chapter_first = book["entry"], book["entry"].pattern.startswith("^###\\s+\\|")
    section, chapter, page, volume = None, None, None, None
    entries, current = [], None
    all_lines = text.splitlines()
    starts = None
    if book.get("layout") == "numbered_ordered":
        all_lines = recover_inline_entries(all_lines, book, counts)
        starts = ordered_entry_lines(all_lines, book, Counter())

    def close():
        if current and current["body"]:
            current["body"] = re.sub(r"\s+", " ", " ".join(current["body"])).strip()
        if current:
            entries.append(current)

    for line_no, raw in enumerate(all_lines):
        found = PAGE.search(raw)
        if found:
            volume, page = int(found.group(1)), int(found.group(2))
            if current is not None and page not in current["pages"]:
                current["pages"].append(page)
        if CHAPTER.match(raw):
            chapter = CHAPTER.match(raw).group(1)
            continue
        match = entry_re.match(raw)
        if match and starts is not None and line_no not in starts:
            match = None                   # a quoted report or list item: part of the body
        if match and not PARATEXT.search(raw):
            close()
            counts["entries"] += 1
            if section:
                counts[f"section: {section[:40]}"] += 1
            head = clean(match.group("name"))
            number = int(match.group("number")) if "number" in match.groupdict() else None
            current = {"number": number, "name": head, "section": section, "raw": raw,
                       "chapter": chapter, "page": page, "pages": [page] if page else [],
                       "volume": volume, "body": [head]}
            continue
        if not chapter_first and SECTION.match(raw):
            section, chapter = SECTION.match(raw).group(1) or None, None
            continue
        if book.get("body_ends") and book["body_ends"].match(raw):
            close()
            current = None
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


def chapter_letter(line):
    found = CHAPTER_HEAD.match(line)
    return CHAPTER_LETTERS.get(normalize_arabic(found.group(1))) if found else None


def leading_name(text):
    """The name a piece of running text opens with, up to where the biography begins."""
    text = NOISE.sub(" ", text)
    out = []
    for token in re.split(r"[\[\(\"».]", text, 1)[0].split():
        if normalize_arabic(token) in NAME_STOP:
            break
        out.append(token)
        if len(out) >= 10:
            break
    return " ".join(out).strip(" .،-")


def collation_key(name):
    """A name as the book orders it: by its tokens, «بن» skipped, «ال» ignored."""
    tokens = []
    for token in DIACRITICS.sub("", name).split():
        token = token.strip(".,()[]«»،-\"")
        if not token:
            continue
        folded = token.translate(COLLATE)
        if folded.startswith("ال") and len(folded) > 3:
            folded = folded[2:]
        if folded in FILLER:
            continue
        tokens.append(folded)
    return tuple(tokens)


def longest_ordered_run(keys):
    """Indices of a longest non-decreasing run — the entries, without the citations between."""
    tails, ends, previous = [], [], [None] * len(keys)
    for n, key in enumerate(keys):
        at = bisect.bisect_right(tails, key)
        if at == len(tails):
            tails.append(key)
            ends.append(n)
        else:
            tails[at], ends[at] = key, n
        previous[n] = ends[at - 1] if at else None
    kept, cursor = set(), ends[-1] if ends else None
    while cursor is not None:
        kept.add(cursor)
        cursor = previous[cursor]
    return kept


def running_pieces(lines, start, end):
    """Every piece of a chapter that may open an entry, with the page it stands on."""
    page, volume, pieces, buffer = None, None, [], []

    def flush():
        if buffer:
            joined = re.sub(r"\s+", " ", " ".join(buffer)).strip()
            for part in REMARK_END.split(joined):
                if part.strip():
                    pieces.append((page, volume, part.strip()))
        buffer.clear()

    for n in range(start, end):
        line = lines[n]
        found = PAGE.search(line)
        if found:
            volume, page = int(found.group(1)), int(found.group(2))
        if line.startswith("# ---"):
            continue
        if line.startswith("# "):
            flush()
            buffer.append(CHAPTER_HEAD.sub("", line).strip() if n == start else line[2:])
        elif line.startswith("~~"):
            buffer.append(line[2:])
    flush()
    return pieces


def parse_running(text, book, counts):
    """Recover the entries of a book that marks none, from its chapters and its own order."""
    lines = text.splitlines()
    heads = [(n, chapter_letter(line)) for n, line in enumerate(lines) if chapter_letter(line)]
    if len(heads) < len(set(CHAPTER_LETTERS.values())) - 1:
        raise SystemExit(f"  REFUSING: found only {len(heads)} chapters")
    counts["chapters"] = len(heads)
    entries = []
    for n, (start, letter) in enumerate(heads):
        end = heads[n + 1][0] if n + 1 < len(heads) else len(lines)
        candidates = []
        for page, volume, piece in running_pieces(lines, start, end):
            name = leading_name(piece)
            key = collation_key(name)
            if not name or not key:
                counts["no name in the piece"] += 1
                continue
            head = next((t.strip(".,()[]«»،-\"") for t in DIACRITICS.sub("", name).split()
                         if t.strip(".,()[]«»،-\"") not in FILLER), "")
            if head.startswith("ال") and len(head) > 3:
                head = head[2:]
            if letter == "كنى":
                fits = head[:3] in KUNYA_OPENERS or head in ("أم", "ابنه", "بنت", "أبو")
                key = key[1:] or key
            else:
                fits = head.startswith(letter) or (letter == "ا" and head[:1] in "اأإآء") \
                    or (letter == "ه" and head[:1] == "ة") or (letter == "ي" and head[:1] == "ى")
            if not fits:
                counts["piece opens on another letter"] += 1
                continue
            candidates.append((page, volume, name, key, piece))
        kept = longest_ordered_run([candidate[3] for candidate in candidates])
        for at, (page, volume, name, key, piece) in enumerate(candidates):
            if at not in kept:
                counts["piece breaks the book's order"] += 1
                continue
            counts["entries"] += 1
            counts[f"section: باب {letter}"] += 1
            # the book runs to two volumes, each numbered from 1, so a page says nothing alone
            entries.append({"number": None, "name": name, "section": None, "raw": piece,
                            "chapter": f"باب {letter}" + (f"، الجزء {volume}" if volume else ""),
                            "page": page, "pages": [page] if page else [], "body": piece})
    return entries


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
    # «ما روى في فلان» must lose its «ما روى في» before the name is cut, or the cut at « روى »
    # leaves «ما» — which is what happened to 109 of al-Kashshi's headings
    if book.get("heading_strip"):
        read_from = book["heading_strip"].sub("", read_from).strip() or read_from
    name = NAME_END.split(read_from, 1)[0].rstrip(".،,؛:").strip() or entry["name"]
    name = re.sub(r"\s+", " ", FOOTNOTE_DIGIT.sub(" ", name)).strip() or name
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
                 + (f", الجزء {entry['volume']}" if book.get("multi_volume") and entry.get("volume")
                    else "")
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
        # one profile per printed entry: never a page-batch fragment of another
        "printed_entry": True,
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
    entries = (parse_running(text, book, counts) if book.get("layout") == "running"
               else parse(text, book, counts))
    # where the book marks its quoted reports as it marks its entries, the entries are what the
    # ordered numbering picks out, and the numbering below is the check on what was lost
    marked = [line for line in text.splitlines()
              if book.get("marker") and book["marker"].match(line) and not PARATEXT.search(line)
              and book.get("layout") != "numbered_ordered"]
    markers = len(marked)
    with open(on_file) as handle:
        existing = json.load(handle)
    existing = existing if isinstance(existing, list) else existing.get("profiles", existing)
    # a source key is book:index = POSITION in the raw file, so appended entries start past it
    start = len(existing) if args.append else 0

    profiles = [profile(e, book, counts) for e in entries]
    strip_conjunction_waw(profiles, counts)
    if markers:
        print(f"parsed {len(profiles)} entries of {markers} marked in {source}")
    else:
        # this book marks no entries, so there is no count to check the parse against; what it
        # reports instead is everything it set aside, which is where a lost entry would be
        print(f"parsed {len(profiles)} entries from {source}, which marks none")
        for key in ("chapters", "no name in the piece", "piece opens on another letter",
                    "piece breaks the book's order"):
            print(f"  {key}: {counts[key]}")
    pages = {p["source_pages"][0] for p in profiles if p["source_pages"]}
    print(f"  pages seen {len(pages)} (min {min(pages) if pages else 0}, "
          f"max {max(pages) if pages else 0}); indices {start}..{start + len(profiles) - 1}")
    print(f"  entries already on file: {len(existing)}")
    for key in ("kunyah found", "grade stated", "sect flag stated",
                "verdict belongs to the student", "conjunction waw stripped",
                "marked line that is a quotation or list item", "marked line off the ordered run",
                "entry taken back off the ordered run", "entry recovered from inside a line",
                "entry missing from the markup"):
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
