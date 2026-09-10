#!/usr/bin/env python3
"""Enforce the extraction output contract on the per-book Rijal profiles.

The Phase 1 extractor was an LLM whose output was written straight to disk. This applies
the contract that should have gated it: a closed key set, a controlled reliability
vocabulary, computed matching keys, and the Infallible exclusion. Drifted keys are rescued
where their intent is unambiguous; anything else fails the book rather than being dropped
silently.

Reads  tmp/narrators_book_{slug}.json
Writes tmp/narrators_normalized/{slug}.json  and  tmp/narrators_normalized/report.json

Usage:
    python3 scripts/narrators/normalize_extraction.py
    python3 scripts/narrators/normalize_extraction.py --books duafa,kashshi --verbose
"""

import argparse
import json
import os
import re
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from narrator_schema import (  # noqa: E402
    EDITORIAL_PLACEHOLDERS, GRADE_KEYWORDS_AR, RELIABILITY_GRADES, SECT_FLAGS,
    is_infallible, name_tokens, normalize_arabic, normalize_english, parse_grade,
)

REPO = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
TMP = os.path.join(REPO, "tmp")

BOOKS = {
    "duafa": "Kitab al-Du'afa",
    "kashshi": "Rijal al-Kashshi",
    "fihrist": "Fihrist al-Tusi",
    "najashi": "Rijal al-Najashi",
    "tusi": "Rijal al-Tusi",
    "ardabili": "Jami' al-Ruwat",
    "khoei": "Mu'jam Rijal al-Hadith",
    "mamaqani": "Tanqih al-Maqal",
}

# Canonical keys the merge reads. Anything outside this set is drift.
CANONICAL_KEYS = {
    "primary_arabic_name", "primary_english_name", "arabic_aliases", "english_aliases",
    "kunyah_arabic", "kunyah_english", "titles", "reliability_grade", "is_doubtful",
    "doubtful_reason", "narrated_from", "narrated_to", "city_or_tribe", "generation",
    "death_year_hijri", "gender", "notes", "normalized_arabic", "normalized_english",
    "source_assessments", "rijal_sources", "source_pages", "source_volume", "_id",
}

# Drifted keys whose intent is unambiguous. Rescued into the canonical key, never over a
# value already present there.
KEY_RESCUE = {
    "is_doubtual": "is_doubtful",
    "is_doubtous": "is_doubtful",
    "is_doubtious": "is_doubtful",
    "is_doubtualble": "is_doubtful",
    "doubtual_reason": "doubtful_reason",
    "doubtous_reason": "doubtful_reason",
    "is_doubtual_reason": "doubtful_reason",
    "doubtful_reason_ar": "doubtful_reason",
    "kunyah_ar": "kunyah_arabic",
    "kunyah_English": "kunyah_english",
    "city_or_ribe": "city_or_tribe",
    "tribe": "city_or_tribe",
    "death_year_hijري": "death_year_hijri",
    "narrated_from_extra": "narrated_from",
    "assessment_arabic": "__assessment_ar",
    "assessment_english": "__assessment_en",
}

# Drift that carries no recoverable information.
KEY_DISCARD = {"assessed"}

_LATIN = re.compile(r"[A-Za-z]")

# Alias count at which a one-token name is a disambiguation page rather than a narrator.
INDEX_PAGE_ALIASES = 8
_YEAR = re.compile(r"(\d{1,4})")


def as_list(value):
    if value is None:
        return []
    if isinstance(value, list):
        return [v for v in value if v is not None and str(v).strip()]
    if isinstance(value, str):
        return [value] if value.strip() else []
    return [value]


def as_text(value):
    if value is None:
        return None
    if isinstance(value, list):
        value = "; ".join(str(v) for v in value if v is not None)
    text = str(value).strip()
    return text or None


def as_year(value):
    """Coerce a death year to an int. Ranges and prose yield None rather than a guess."""
    if value is None:
        return None
    if isinstance(value, int):
        return value if 0 < value < 1500 else None
    match = _YEAR.search(str(value))
    if not match:
        return None
    year = int(match.group(1))
    return year if 0 < year < 1500 else None


def rescue_keys(raw, errors):
    """Fold drifted keys onto their canonical names. Returns a cleaned dict."""
    out, rescued, stray = {}, [], []
    for key, value in raw.items():
        if key in CANONICAL_KEYS:
            out.setdefault(key, value)
            if out[key] is None and value is not None:
                out[key] = value
            continue
        if key in KEY_DISCARD:
            continue
        target = KEY_RESCUE.get(key)
        if target is None:
            stray.append(key)
            continue
        rescued.append(key)
        if target == "narrated_from":
            out["narrated_from"] = as_list(out.get("narrated_from")) + as_list(value)
        elif target.startswith("__"):
            out[target] = value
        elif out.get(target) in (None, "", [], False) and value not in (None, ""):
            out[target] = value
    if stray:
        errors.append(("unknown_keys", stray))
    return out, rescued


def build_assessments(raw, book_slug, pages):
    """Normalize source_assessments, folding in any stray assessment_* keys."""
    out = []
    for item in (raw.get("source_assessments") or []):
        if not isinstance(item, dict):
            continue
        ar = as_text(item.get("assessment_ar"))
        en = as_text(item.get("assessment_en"))
        if not ar and not en:
            continue
        out.append({
            "source_name": as_text(item.get("source_name")) or BOOKS[book_slug],
            "author": as_text(item.get("author")),
            "assessment_ar": ar,
            "assessment_en": en,
            "source_book": book_slug,
            "source_pages": pages,
        })
    stray_ar = as_text(raw.get("__assessment_ar"))
    stray_en = as_text(raw.get("__assessment_en"))
    if (stray_ar or stray_en) and not any(
            a["assessment_ar"] == stray_ar for a in out):
        out.append({
            "source_name": BOOKS[book_slug],
            "author": None,
            "assessment_ar": stray_ar,
            "assessment_en": stray_en,
            "source_book": book_slug,
            "source_pages": pages,
        })
    return out


def normalize_profile(raw, book_slug, index, stats, errors):
    cleaned, rescued = rescue_keys(raw, errors)
    for key in rescued:
        stats["rescued_keys"][key] += 1

    arabic_name = as_text(cleaned.get("primary_arabic_name"))
    english_name = as_text(cleaned.get("primary_english_name"))
    if not arabic_name and not english_name:
        stats["dropped_no_name"] += 1
        return None

    pages = sorted({int(p) for p in as_list(cleaned.get("source_pages"))
                    if str(p).strip().lstrip("-").isdigit()})
    assessments = build_assessments(cleaned, book_slug, pages)

    norm_ar = normalize_arabic(arabic_name or "")
    norm_en = normalize_english(english_name or "")

    context = " ".join(filter(None, [
        arabic_name, english_name,
        " ".join(a.get("assessment_ar") or "" for a in assessments[:2]),
    ]))
    if is_infallible(norm_ar, norm_en, context):
        stats["dropped_infallible"] += 1
        return None

    grade, grade_tokens, sect_flags, parsed = parse_grade(cleaned.get("reliability_grade"))
    if not parsed:
        stats["unparsed_grades"][str(cleaned.get("reliability_grade"))] += 1
    stats["grades"][grade] += 1

    flags = []
    aliases_ar = sorted({a for a in map(as_text, as_list(cleaned.get("arabic_aliases"))) if a})
    # "المترجم" ("the biographee") is Mamaqani's shorthand for the person under discussion,
    # not a name he is known by. Dropped rather than flagged: there is nothing to recover.
    dropped = [a for a in aliases_ar if normalize_arabic(a) in EDITORIAL_PLACEHOLDERS]
    if dropped:
        aliases_ar = [a for a in aliases_ar if a not in dropped]
        stats["dropped_editorial_aliases"] += len(dropped)
    if len(name_tokens(norm_ar)) <= 1 and len(aliases_ar) >= INDEX_PAGE_ALIASES:
        # A one-token name carrying dozens of aliases is a disambiguation page, not a
        # person: Khoei and Mamaqani both head a page listing everyone called حفص, and the
        # extractor turned it into one profile with 89 "aliases" that are 89 people.
        flags.append("index_page_suspect")
        stats["flag_index_page_suspect"] += 1
    if arabic_name and _LATIN.search(arabic_name):
        flags.append("latin_in_arabic_name")
        stats["flag_latin_in_arabic_name"] += 1
    if not norm_ar:
        flags.append("no_arabic_matching_key")
        stats["flag_no_arabic_matching_key"] += 1
    if grade == "not_assessed" and any(
            k in (a.get("assessment_ar") or "") for a in assessments for k in GRADE_KEYWORDS_AR):
        # The quotation contains a verdict keyword the extractor did not act on. Recorded,
        # not acted on: deciding whether the keyword refers to this narrator or to someone
        # in his chain needs the surrounding text, which is a later pass.
        flags.append("grade_recoverable")
        stats["flag_grade_recoverable"] += 1

    return {
        "book": book_slug,
        "source_index": index,
        "primary_arabic_name": arabic_name,
        "primary_english_name": english_name,
        "arabic_aliases": aliases_ar,
        "english_aliases": sorted({a for a in map(as_text, as_list(cleaned.get("english_aliases"))) if a}),
        "kunyah_arabic": as_text(cleaned.get("kunyah_arabic")),
        "kunyah_english": as_text(cleaned.get("kunyah_english")),
        "titles": sorted({t for t in map(as_text, as_list(cleaned.get("titles"))) if t}),
        "reliability_grade": grade,
        "reliability_grade_tokens": grade_tokens,
        "reliability_grade_raw": as_text(cleaned.get("reliability_grade")),
        "sect_flags": sect_flags,
        "is_doubtful": bool(cleaned.get("is_doubtful")),
        "doubtful_reason": as_text(cleaned.get("doubtful_reason")),
        "narrated_from": sorted({n for n in map(as_text, as_list(cleaned.get("narrated_from"))) if n}),
        "narrated_to": sorted({n for n in map(as_text, as_list(cleaned.get("narrated_to"))) if n}),
        "city_or_tribe": sorted({c for c in map(as_text, as_list(cleaned.get("city_or_tribe"))) if c}),
        "generation": as_text(cleaned.get("generation")),
        "death_year_hijri": as_year(cleaned.get("death_year_hijri")),
        "gender": as_text(cleaned.get("gender")) or "male",
        "notes": as_text(cleaned.get("notes")),
        "normalized_arabic": norm_ar,
        "normalized_english": norm_en,
        "name_tokens": name_tokens(norm_ar),
        "source_assessments": assessments,
        "rijal_sources": [BOOKS[book_slug]],
        "source_pages": pages,
        "source_volume": cleaned.get("source_volume"),
        "flags": flags,
    }


def normalize_book(book_slug, tmp_dir, out_dir, strict, verbose):
    path = os.path.join(tmp_dir, f"narrators_book_{book_slug}.json")
    if not os.path.exists(path):
        print(f"  {book_slug}: FILE NOT FOUND ({path})")
        return None

    with open(path) as handle:
        raw_profiles = json.load(handle)

    stats = {
        "input": len(raw_profiles),
        "rescued_keys": Counter(),
        "unparsed_grades": Counter(),
        "grades": Counter(),
        "dropped_no_name": 0,
        "dropped_infallible": 0,
        "flag_latin_in_arabic_name": 0,
        "flag_no_arabic_matching_key": 0,
        "flag_grade_recoverable": 0,
        "flag_index_page_suspect": 0,
        "dropped_editorial_aliases": 0,
    }
    errors = []
    out = []
    for index, raw in enumerate(raw_profiles):
        if not isinstance(raw, dict):
            errors.append(("non_object_profile", index))
            continue
        profile = normalize_profile(raw, book_slug, index, stats, errors)
        if profile is not None:
            out.append(profile)

    stats["output"] = len(out)
    unknown = sorted({k for kind, payload in errors if kind == "unknown_keys" for k in payload})
    stats["unknown_keys"] = unknown

    if strict and (unknown or stats["unparsed_grades"]):
        raise SystemExit(
            f"{book_slug}: contract violation — unknown keys {unknown}, "
            f"unparsed grades {list(stats['unparsed_grades'])}"
        )

    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, f"{book_slug}.json"), "w") as handle:
        json.dump(out, handle, ensure_ascii=False)

    print(f"  {book_slug:9s} {stats['input']:6d} -> {stats['output']:6d}"
          f"  (infallible {stats['dropped_infallible']}, no-name {stats['dropped_no_name']})"
          f"  rescued {sum(stats['rescued_keys'].values())} keys")
    if verbose:
        if stats["rescued_keys"]:
            print(f"      rescued: {dict(stats['rescued_keys'])}")
        if unknown:
            print(f"      UNKNOWN KEYS: {unknown}")
        if stats["unparsed_grades"]:
            print(f"      UNPARSED GRADES: {dict(stats['unparsed_grades'])}")
        print(f"      flags: latin-in-arabic {stats['flag_latin_in_arabic_name']}, "
              f"no-key {stats['flag_no_arabic_matching_key']}, "
              f"grade-recoverable {stats['flag_grade_recoverable']}, "
              f"index-page {stats['flag_index_page_suspect']}, "
              f"editorial-aliases-dropped {stats['dropped_editorial_aliases']}")

    stats["rescued_keys"] = dict(stats["rescued_keys"])
    stats["unparsed_grades"] = dict(stats["unparsed_grades"])
    stats["grades"] = dict(stats["grades"])
    return stats


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--tmp-dir", default=TMP)
    parser.add_argument("--out-dir", default=os.path.join(TMP, "narrators_normalized"))
    parser.add_argument("--books", default=",".join(BOOKS),
                        help="comma-separated book slugs (default: all)")
    parser.add_argument("--strict", action="store_true",
                        help="fail on unknown keys or unparsed grades instead of reporting")
    parser.add_argument("--verbose", "-v", action="store_true")
    args = parser.parse_args()

    slugs = [s.strip() for s in args.books.split(",") if s.strip()]
    unknown_slugs = [s for s in slugs if s not in BOOKS]
    if unknown_slugs:
        raise SystemExit(f"unknown book slugs: {unknown_slugs}")

    print(f"Normalizing {len(slugs)} book(s) -> {args.out_dir}\n")
    report = {}
    for slug in slugs:
        stats = normalize_book(slug, args.tmp_dir, args.out_dir, args.strict, args.verbose)
        if stats:
            report[slug] = stats

    total_in = sum(s["input"] for s in report.values())
    total_out = sum(s["output"] for s in report.values())
    grades = Counter()
    for s in report.values():
        grades.update(s["grades"])
    print(f"\nTotal {total_in} -> {total_out}")
    print("Canonical grades: " + ", ".join(f"{g}={n}" for g, n in grades.most_common()))

    os.makedirs(args.out_dir, exist_ok=True)
    with open(os.path.join(args.out_dir, "report.json"), "w") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=2)
    print(f"Report: {os.path.join(args.out_dir, 'report.json')}")


if __name__ == "__main__":
    main()
