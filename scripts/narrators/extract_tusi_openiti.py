#!/usr/bin/env python3
"""Re-extract Rijal al-Tusi from the OpenITI text, by rule rather than by agent.

Phase 1 read all 417 pages of this book and kept 123 entries of about 6,400: dense pages of bare
names truncated the extractor's output, the run was called done on page coverage, and 98% of the
largest single source of main-book entries was lost. That is why 11,806 of the corpus's 14,378
unattached profiles have no main-book namesake to attach to — the entry they belong to was never
extracted.

The OpenITI corpus carries the same edition (Jawad al-Qayyumi al-Isfahani, Mu'assasat al-Nashr
al-Islami, Qom — its 417 page marks match the page count already recorded here), openly licensed
and machine-readable, in a markup regular enough to parse:

    ### | أصحاب أبي عبد الله جعفر بن محمد الصادق     the Imam whose companions follow
    ### || باب الهمزة                                 the alphabet chapter, or الكنى / النساء
    ### $ 1229 - إبراهيم بن حيان الأسدي الكوفي، نزل   the entry, numbered as the edition numbers it
    ~~واسط.                                           its continuation
    ~~PageV00P121                                     the printed page

So no agent reads a page here. What a rule can take faithfully it takes — the heading, the body as
the source's own words, the printed page, the Imam whose companion the man was, and a kunyah or a
grade word where the text states one. What a rule cannot take honestly it leaves empty: teachers
and students are named in running prose, and a regex that guesses at them would put invented
chains into a corpus that publishes reliability verdicts. The body is kept whole in
`source_assessments`, so every later pass still reads what al-Tusi actually wrote.

Writes tmp/narrators_book_tusi_openiti.json (append with --append, which never renumbers what is
already there, so no recorded decision is invalidated).

Usage:
    python3 scripts/narrators/extract_tusi_openiti.py --dry-run
    python3 scripts/narrators/extract_tusi_openiti.py --append
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
SOURCE = os.path.join(TMP, "narrators_sources", "tusi_rijal_pri.mARkdown")
BOOK = os.path.join(TMP, "narrators_book_tusi.json")
OUT = os.path.join(TMP, "narrators_book_tusi_openiti.json")

# OpenITI drops a milestone («ms018») between the number and the dash in 18 of the entries
ENTRY = re.compile(r"^###\s+\$+\s*(\d+)\s*(?:ms\d+\s*)?-\s*(.+?)\s*$")
SECTION = re.compile(r"^###\s+\|\s*(.+?)\s*$")
CHAPTER = re.compile(r"^###\s+\|\|\s*(.+?)\s*$")
PAGE = re.compile(r"PageV(\d+)P(\d+)")
# OpenITI's own marks, not the book's words: milestones, page rules, footnote digits
NOISE = re.compile(r"ms\d+|PageV\d+P\d+|^#\s*-+\s*|\[\s*\d+\s*\]|~~|^#+\s*")
KUNYAH = re.compile(r"يكن[يى]\s+(أب[اوي][^،.,]{2,20})")
HEAD_KUNYAH = re.compile(r"^(أب[وا]\s+\S+)")
COMMA_KUNYAH = re.compile(r"[،,]\s*(أب[وا]\s+\S+)")
# the heading runs «name، description»: a name carries no comma, and none of these words either
NAME_END = re.compile(r"[،,؛:.(\[]|\s(?:نزل|روى|يكن|كان|مات|قتل|أسند|من أصحاب|له كتاب|ثقة|ضعيف)\s")


def clean(line):
    return NOISE.sub(" ", line).strip()


def parse(text, counts):
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
        match = ENTRY.match(raw)
        if match:
            close()
            counts["entries"] += 1
            if section:
                counts[f"section: {section[:40]}"] += 1
            head = clean(match.group(2))
            current = {"number": int(match.group(1)), "name": head,
                       "section": section, "chapter": chapter, "page": page, "body": [head]}
            continue
        if CHAPTER.match(raw):
            chapter = CHAPTER.match(raw).group(1)
            continue
        if SECTION.match(raw):
            section, chapter = SECTION.match(raw).group(1), None
            continue
        if raw.startswith("#META#") or raw.startswith("######"):
            continue
        if current is not None:
            body = clean(raw)
            if body:
                current["body"].append(body)
    close()
    return entries


def grade_word(text):
    """The reliability word the source itself uses, for the contract to canonicalise."""
    for word in GRADE_KEYWORDS_AR:
        if word and word in text:
            return word
    return None


def profile(entry, index, counts):
    body = entry["body"] or entry["name"]
    name = NAME_END.split(body, 1)[0].rstrip(".،,؛:").strip() or entry["name"]
    kunyah = None
    found = KUNYAH.search(body) or HEAD_KUNYAH.match(name) or COMMA_KUNYAH.search(entry["name"])
    if found:
        kunyah = found.group(1).strip()
        counts["kunyah found"] += 1
    grade = grade_word(body)
    if grade:
        counts["grade word found"] += 1
    female = entry["chapter"] and "النساء" in entry["chapter"]
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
        # al-Tusi names teachers and students in prose; a regex would invent them, so they are
        # left to the passes that read the text itself.
        "narrated_from": [],
        "narrated_to": [],
        "city_or_tribe": None,
        "generation": entry["section"],
        "death_year_hijri": None,
        "gender": "female" if female else "male",
        "notes": f"Rijal al-Tusi, printed entry {entry['number']}"
                 + (f"; {entry['chapter']}" if entry["chapter"] else ""),
        "source_assessments": [{
            "source_name": "Rijal al-Tusi",
            "author": "Muhammad ibn al-Hasan al-Tusi",
            "assessment_ar": body,
            "assessment_en": None,
        }],
        "rijal_sources": ["Rijal al-Tusi"],
        "source_pages": [entry["page"]] if entry["page"] else [],
    }


def strip_conjunction_waw(profiles, counts):
    """al-Tusi runs a list of names joined by «و», so a heading can open with a conjunction.

    «وهب», «وائل», «وردان» are names that begin with the letter; «وعمرو بن يحيى» is «and ʿAmr».
    Two signals separate them: the corpus itself, where the bare first token is common and the
    waw-bearing one is not, and the printed run, where a conjunction entry follows another on
    the same page.
    """
    first = Counter(normalize_arabic(p["primary_arabic_name"]).split()[0]
                    for p in profiles if p["primary_arabic_name"])
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
    parser.add_argument("--source", default=SOURCE)
    parser.add_argument("--append", action="store_true",
                        help="number the new entries after the ones already on file")
    parser.add_argument("--dry-run", action="store_true", help="count, write nothing")
    args = parser.parse_args()

    with open(args.source) as handle:
        text = handle.read()
    counts = Counter()
    entries = parse(text, counts)
    with open(BOOK) as handle:
        existing = json.load(handle)
    existing = existing if isinstance(existing, list) else existing.get("profiles", existing)
    # a source key is book:index = POSITION in the raw file, so appended entries start past it
    start = len(existing) if args.append else 0

    profiles = [profile(e, start + n, counts) for n, e in enumerate(entries)]
    strip_conjunction_waw(profiles, counts)
    numbers = {e["number"] for e in entries}
    absent = [n for n in range(1, max(numbers) + 1) if n not in numbers]
    counts["printed numbers absent"] = len(absent)
    pages = {p["source_pages"][0] for p in profiles if p["source_pages"]}
    if absent:
        print(f"  REFUSING: {len(absent)} printed entry numbers absent, first {absent[:10]}")
        raise SystemExit(1)
    print(f"parsed {len(profiles)} entries from {args.source}")
    print(f"  pages seen {len(pages)} (min {min(pages) if pages else 0}, "
          f"max {max(pages) if pages else 0}); indices {start}..{start + len(profiles) - 1}")
    print(f"  entries already on file: {len(existing)}")
    for key in ("kunyah found", "grade word found"):
        print(f"  {key}: {counts[key]}")
    print("  sections:")
    for key, n in counts.items():
        if key.startswith("section: "):
            print(f"     {n:5d} under {key[9:]}")
    if args.dry_run:
        for n in (0, 1, 2, len(profiles) - 2, len(profiles) - 1):
            prof = profiles[n]
            print(f"    [{start + n}] p{prof['source_pages']} «{prof['primary_arabic_name'][:40]}» "
                  f"gen={(prof['generation'] or '')[:28]} grade={prof['reliability_grade']}")
        return
    # append-only: the entries already on file keep their positions, and so their source keys
    combined = (existing + profiles) if args.append else profiles
    write_json_atomic(OUT, combined)
    print(f"\nwrote {len(combined)} profiles ({len(existing)} kept + {len(profiles)} new) -> {OUT}")


if __name__ == "__main__":
    main()
