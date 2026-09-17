#!/usr/bin/env python3
"""Reconcile a re-extracted book with the entries already on file.

extract_openiti.py appends a book's full text after the entries phase 1 kept, so that every
source key stays where it was and the decisions resting on it stay true. That leaves each
already-extracted entry with a duplicate of itself further down the same file. This pass
matches them, merges each pair into the entry that holds the key, and records the duplicate
as `exclude`.

Which side governs a merge differs by book, because the two extractions differ in quality:

  tusi      the 123 entries phase 1 kept are thin — `assessment_ar` is the name repeated,
            and «unknown (majhul)» was read from silence for 74 of them — so the printed
            entry governs and only what the agent added (a footnote, a city) is kept.
  fihrist   these entries carry teachers, students, kunyahs, cities and English names that
  kashshi   no rule extracts, so they govern; what they gain is the entry's own page and the
            source's own words, added as a second assessment rather than over the first.

Both keep the printed page. The pages already on file are a batch range — 723 of al-Fihrist's
731 entries span exactly five pages — so they cannot say where an entry is, and they cannot
match either: al-Tusi's drift against the printed page ran from +9 to +22. The match is on the
name, with the drift used only to choose among namesakes.

Usage:
    python3 scripts/narrators/repair_from_openiti.py --book fihrist
    python3 scripts/narrators/repair_from_openiti.py --book fihrist --write
"""

import argparse
from collections import defaultdict
import importlib.util
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from identity import append_record, load_record, make_decision, write_json_atomic  # noqa: E402
from narrator_schema import normalize_arabic  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.join(HERE, "..", "..")
TMP = os.path.join(REPO, "tmp")
RECORD = os.path.join(TMP, "narrators_identity", "decisions.jsonl")

_spec = importlib.util.spec_from_file_location("extract_openiti",
                                               os.path.join(HERE, "extract_openiti.py"))
extract = importlib.util.module_from_spec(_spec)
sys.modules["extract_openiti"] = extract
_spec.loader.exec_module(extract)

# the drift of the recorded batch pages against the printed page, measured on al-Tusi
DRIFT = (5, 30)
# the printed page each book's first entry stands on. Phase 1 read the editions from page 1, so
# entries it took from pages before this one come from the edition's introduction — a modern
# editor arguing about the book — and were recorded as if the book itself had said them.
FIRST_ENTRY_PAGE = {"tusi": 19, "fihrist": 33, "kashshi": 25}
# what the agent extraction added that no rule reads, kept whatever governs the merge
AGENT_FIELDS = ("notes", "city_or_tribe", "death_year_hijri", "narrated_from", "narrated_to",
                "kunyah_arabic", "titles", "arabic_aliases", "english_aliases",
                "primary_english_name", "primary_arabic_name")
# grades the agent extraction read from silence rather than from a verdict
INFERRED_GRADES = ("unknown (majhul)", "assessed", "not_assessed", None)
GOVERNED_BY_PRINT = ("tusi",)


def trim(name):
    return extract.NAME_END.split(name, 1)[0].rstrip(".،,؛:").strip() or name


def match(existing, appended):
    """Each already-extracted entry against its counterpart in the full text."""
    by_name = defaultdict(list)
    for index, profile in enumerate(appended):
        by_name[normalize_arabic(profile["primary_arabic_name"])].append(index)
    chosen, unmatched, ambiguous = {}, [], []
    for index, profile in enumerate(existing):
        name = normalize_arabic(trim(profile["primary_arabic_name"]))
        candidates = by_name.get(name, [])
        if not candidates:
            # the two extractions head the same man differently: al-Fihrist's printed heading
            # carries a nisbah the agent left off, or the reverse
            extends = [key for key in by_name
                       if key.startswith(name + " ") or name.startswith(key + " ")]
            if len(extends) == 1:
                candidates = by_name[extends[0]]
        span = profile.get("source_pages") or []
        if not candidates:
            unmatched.append(index)
        elif len(candidates) == 1:
            chosen[index] = candidates[0]
        else:
            near = sorted((appended[c]["source_pages"][0] - span[0], c) for c in candidates
                          if appended[c]["source_pages"] and span
                          and DRIFT[0] <= appended[c]["source_pages"][0] - span[0] <= DRIFT[1])
            if near:
                chosen[index] = near[0][1]
            else:
                ambiguous.append(index)
    return chosen, unmatched, ambiguous


def merge(old, new, book):
    """One entry from the two readings of it, by whichever governs this book."""
    if book in GOVERNED_BY_PRINT:
        merged = dict(new)
        for field in AGENT_FIELDS:
            if not merged.get(field) and old.get(field):
                merged[field] = old[field]
        if old.get("notes") and old["notes"] != merged.get("notes"):
            merged["notes"] = f"{new['notes']}. {old['notes']}"
        kept = None if old.get("reliability_grade") in INFERRED_GRADES else old["reliability_grade"]
        merged["reliability_grade"] = new["reliability_grade"] or kept
        if kept and not new["reliability_grade"]:
            merged["notes"] += (f" Grade «{kept}» read by the earlier extraction, "
                                "not stated in the printed entry.")
        return merged
    merged = dict(old)
    merged["source_pages"] = new["source_pages"] or old.get("source_pages") or []
    merged["generation"] = old.get("generation") or new.get("generation")
    merged["reliability_grade"] = old.get("reliability_grade") or new.get("reliability_grade")
    if old.get("notes") != new["notes"]:
        merged["notes"] = f"{new['notes']}. {old.get('notes') or ''}".strip()
    # the source's own words beside the reading of them, never over it
    spoken = new["source_assessments"][0]["assessment_ar"]
    already = {(a or {}).get("assessment_ar") for a in (old.get("source_assessments") or [])}
    if spoken and spoken not in already:
        merged["source_assessments"] = list(old.get("source_assessments") or []) + [
            dict(new["source_assessments"][0], assessment_en=None)]
    for field in ("normalized_arabic", "normalized_english"):
        merged.pop(field, None)
    return merged


def relabel_front_matter(existing, book):
    """Say who actually spoke, where phase 1 read the edition's introduction as the book.

    «وسيجيء في كلام أبي عمرو الكشي» — "it will come in the words of Abu 'Amr al-Kashshi" — is
    the editor writing about the book, and was recorded as Rijal al-Kashshi's own verdict. The
    reading stays; what changes is whose words it is said to be.
    """
    first, relabelled = FIRST_ENTRY_PAGE[book], 0
    for profile in existing:
        pages = profile.get("source_pages") or []
        if not pages or max(pages) >= first:
            continue
        for assessment in profile.get("source_assessments") or []:
            title = assessment.get("source_name") or ""
            if "introduction" in title:
                continue
            assessment["source_name"] = f"{title} (the edition's introduction)"
            assessment["author"] = "the edition's editor"
            relabelled += 1
        profile["notes"] = ((profile.get("notes") or "") + " Read from the edition's introduction, "
                            "before the book's first entry; the words are the editor's.").strip()
    return relabelled


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--book", required=True, choices=sorted(extract.BOOKS))
    parser.add_argument("--kept", type=int, required=True,
                        help="how many entries were already on file before the append")
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()

    path = os.path.join(TMP, f"narrators_book_{args.book}_openiti.json")
    profiles = json.load(open(path))
    existing, appended = profiles[:args.kept], profiles[args.kept:]
    chosen, unmatched, ambiguous = match(existing, appended)

    print(f"{args.book}: {len(existing)} already on file, {len(appended)} appended")
    print(f"  matched   {len(chosen)}")
    first = FIRST_ENTRY_PAGE[args.book]
    front = [i for i in unmatched
             if (existing[i].get("source_pages") or [first])[-1] < first]
    print(f"  no counterpart in the printed text: {len(unmatched)}"
          f" ({len(front)} of them read from the edition's introduction)")
    for index in unmatched[:10]:
        print(f"     [{index}] «{existing[index]['primary_arabic_name'][:50]}»")
    print(f"  several counterparts, none within the page drift: {len(ambiguous)}")
    for index in ambiguous[:10]:
        print(f"     [{index}] «{existing[index]['primary_arabic_name'][:50]}»")
    print(f"  governed by: {'the printed entry' if args.book in GOVERNED_BY_PRINT else 'the entry already on file'}")

    if not args.write:
        return
    relabelled = relabel_front_matter(existing, args.book)
    if relabelled:
        print(f"  re-attributed {relabelled} assessments read from the edition's introduction")
    for old_index, new_index in chosen.items():
        existing[old_index] = merge(existing[old_index], appended[new_index], args.book)
    write_json_atomic(path, existing + appended, indent=1)

    # an entry the printed text does not head is a man named inside another man's entry, or read
    # from the introduction: it anchors identity as if it were a main-book entry of its own, and
    # deciding each one needs reading, so they are listed rather than guessed at.
    write_json_atomic(os.path.join(TMP, "narrators_sources", f"{args.book}_not_an_entry.json"),
                      [{"source_key": f"{args.book}:{i}",
                        "name": existing[i]["primary_arabic_name"],
                        "recorded_pages": existing[i].get("source_pages"),
                        "from_the_introduction": bool(existing[i].get("source_pages"))
                        and max(existing[i]["source_pages"]) < first}
                       for i in unmatched + ambiguous], indent=1)

    on_file = {d["decision_id"] for d in load_record(RECORD)}
    decisions = [d for d in (
        make_decision("exclude", method=f"{args.book}_reextraction_duplicate", actor="reviewer",
                      confidence="high", sources=[f"{args.book}:{args.kept + new_index}"],
                      evidence={"repaired_into": f"{args.book}:{old_index}",
                                "why": "the entry already on file was repaired from this one, "
                                       "keeping its key"},
                      origin={"run": f"stage6:{args.book}_openiti"})
        for old_index, new_index in sorted(chosen.items())) if d["decision_id"] not in on_file]
    append_record(RECORD, decisions)
    print(f"\nwrote {len(existing) + len(appended)} profiles; {len(chosen)} repaired; "
          f"{len(decisions)} excludes recorded ({len(chosen) - len(decisions)} already on file)")


if __name__ == "__main__":
    main()
