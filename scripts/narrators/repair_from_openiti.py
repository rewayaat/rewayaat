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
    python3 scripts/narrators/repair_from_openiti.py --book fihrist --move-flags
    python3 scripts/narrators/repair_from_openiti.py --book ardabili --kept 1796 --refresh [--write]
"""

import argparse
from collections import Counter, defaultdict
import importlib.util
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from identity import append_record, load_record, make_decision, write_json_atomic  # noqa: E402
from narrator_schema import name_tokens, normalize_arabic  # noqa: E402

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
# Jami' al-Ruwat is absent: phase 1 read it in a pagination of its own, running to page 976
# where this edition's two volumes each stop at 660, so its pages say nothing about this text.
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


def match(existing, appended, drift_applies=True):
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
                          if drift_applies and appended[c]["source_pages"] and span
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
    merged["printed_entry"] = True
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
    first, relabelled = FIRST_ENTRY_PAGE.get(book), 0
    if first is None:
        return 0
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


def move_flags(book):
    """Mark the key holder, not its excluded duplicate, as the printed entry.

    Until 2026-09-19 a merge the existing entry governs kept that entry's flags, so the key
    holder went unmarked and the mark stayed on the duplicate recorded as `exclude`. Everything
    reading the mark — the Khoei linkers, the merge's same-book guard — then chose a key the
    build drops: 2,153 links had joined nothing. This moves the mark on a book already repaired.

    Phase 1 read some entries twice, where two batches overlapped, and both readings were
    repaired from the one printed entry. They are one man where one heading extends the other
    («أبو رافع», «أبو رافع مولى رسول الله»): the first keeps the mark and the rest are joined to
    it as a reviewer `same`. Where the headings part — al-Kashshi's bare «حذيفة» matched both
    Hudhayfa b. Mansur and Hudhayfa b. al-Yaman — each keeps the mark, and the guard keeps them
    apart.
    """
    path = os.path.join(TMP, f"narrators_book_{book}.json")
    profiles = json.load(open(path))
    record = load_record(RECORD)
    holders = defaultdict(list)
    for d in record:
        if d["kind"] == "exclude" and d["method"] == f"{book}_reextraction_duplicate":
            for key in d["sources"]:
                holders[int(key.split(":")[1])].append(int(d["evidence"]["repaired_into"].split(":")[1]))

    def tokens(i):
        return name_tokens(normalize_arabic(profiles[i]["primary_arabic_name"]))

    def extends(a, b):
        shorter, longer = sorted((tokens(a), tokens(b)), key=len)
        return longer[:len(shorter)] == shorter

    moved, joins, apart = 0, [], 0
    for duplicate, held in holders.items():
        profiles[duplicate]["printed_entry"] = False
        held = sorted(set(held))
        first = held[0]
        for other in held[1:]:
            if extends(first, other):
                profiles[other]["printed_entry"] = False
                joins.append((first, other))
            else:
                profiles[other]["printed_entry"] = True
                apart += 1
        moved += not profiles[first].get("printed_entry")
        profiles[first]["printed_entry"] = True
    write_json_atomic(path, profiles, indent=1)
    on_file = {d["decision_id"] for d in record}
    decisions = [d for d in (
        make_decision("same", method=f"{book}_read_twice", actor="reviewer", confidence="high",
                      sources=[f"{book}:{a}", f"{book}:{b}"],
                      evidence={"why": "phase 1 read one printed entry twice; both were repaired "
                                       "from it"},
                      origin={"run": f"stage6:{book}_openiti"})
        for a, b in joins) if d["decision_id"] not in on_file]
    append_record(RECORD, decisions)
    print(f"{book}: the mark moved to {moved} key holders; {len(joins)} double readings joined "
          f"({len(decisions)} new); {apart} readings of one heading left apart")


def refresh(book, kept, write):
    """Re-read a book already appended, keeping every key where it stands.

    A parser fix (Jami' al-Ruwat's letter order, 2026-09-19) finds entries the first append
    missed. The entries already appended keep their index and their text — the running parse
    drops what breaks the book's order rather than folding it into a neighbour, so none of them
    changed. What is new is appended after the last key, and the phase 1 profiles the first
    repair found no printed entry for are matched against the new entries alone, merged and
    their duplicates excluded, as the repair does.
    """
    path = os.path.join(TMP, f"narrators_book_{book}.json")
    profiles = json.load(open(path))
    config = extract.BOOKS[book]
    with open(os.path.join(TMP, "narrators_sources", config["source"])) as handle:
        text = handle.read()
    counts = Counter()
    layout = config.get("layout")
    parsed = (extract.parse_running(text, config, counts) if layout == "running"
              else extract.parse_inline_numbered(text, config, counts) if layout == "inline_numbered"
              else extract.parse(text, config, counts))
    fresh = [extract.profile(e, config, counts) for e in parsed]
    extract.strip_conjunction_waw(fresh, counts)

    def where(p):
        """An entry by its heading and the page it opens on."""
        return (normalize_arabic(p["primary_arabic_name"]), (p.get("source_pages") or [None])[0])
    held = defaultdict(list)
    for at in range(kept, len(profiles)):
        held[where(profiles[at])].append(at)
    new, updated = [], {}
    for p in fresh:
        if held[where(p)]:
            at = held[where(p)].pop(0)
            # where a heading the parse had missed stood inside it, its text, pages and chapter
            # shrink to its own
            if any(p.get(f) != profiles[at].get(f) for f in
                   ("source_pages", "notes", "source_assessments", "reliability_grade")):
                updated[at] = p
        else:
            new.append(p)
    gone = sum(len(v) for v in held.values())
    print(f"{book}: {len(fresh)} entries read, {len(new)} new, {len(updated)} re-read with a "
          f"changed text, pages or chapter, {gone} on file no longer read")
    for p in new[:5]:
        print(f"     new: «{p['primary_arabic_name'][:60]}» p. {p['source_pages'][:3]}")

    listed = os.path.join(TMP, "narrators_sources", f"{book}_not_an_entry.json")
    listing = json.load(open(listed)) if os.path.exists(listed) else []   # Khoei was never repaired
    orphans = [int(x["source_key"].split(":")[1]) for x in listing]
    chosen, unmatched, ambiguous = match([profiles[i] for i in orphans], new,
                                         drift_applies=book in FIRST_ENTRY_PAGE)
    print(f"  of {len(orphans)} phase 1 profiles without a printed entry, {len(chosen)} now have "
          f"one; {len(unmatched)} still none, {len(ambiguous)} several")
    for at, n in list(chosen.items())[:8]:
        print(f"     «{profiles[orphans[at]]['primary_arabic_name'][:40]}» = «{new[n]['primary_arabic_name'][:40]}»")
    if not write:
        return
    start = len(profiles)
    for at, p in updated.items():
        profiles[at] = dict(p, printed_entry=profiles[at].get("printed_entry", True))
    for at, n in chosen.items():
        profiles[orphans[at]] = merge(profiles[orphans[at]], new[n], book)
        new[n]["printed_entry"] = False
    write_json_atomic(path, profiles + new, indent=1)
    still = [orphans[i] for i in unmatched + ambiguous]
    if listing:
        write_json_atomic(listed, [x for x in listing
                                   if int(x["source_key"].split(":")[1]) in still], indent=1)
    record = load_record(RECORD)
    ids = {d["decision_id"] for d in record}
    decisions = [d for d in (
        make_decision("exclude", method=f"{book}_reextraction_duplicate", actor="reviewer",
                      confidence="high", sources=[f"{book}:{start + n}"],
                      evidence={"repaired_into": f"{book}:{orphans[at]}",
                                "why": "the entry already on file was repaired from this one, "
                                       "keeping its key"},
                      origin={"run": f"stage6:{book}_openiti_refresh"})
        for at, n in sorted(chosen.items())) if d["decision_id"] not in ids]
    append_record(RECORD, decisions)
    print(f"  wrote {len(profiles) + len(new)} profiles, {len(new)} appended from {start}; "
          f"{len(decisions)} excludes recorded")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--book", required=True, choices=sorted(extract.BOOKS))
    parser.add_argument("--kept", type=int,
                        help="how many entries were already on file before the append")
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--refresh", action="store_true",
                        help="re-read a book already appended, appending only what is new")
    parser.add_argument("--move-flags", action="store_true",
                        help="on a book already repaired, mark the key holders as printed entries")
    args = parser.parse_args()
    if args.move_flags:
        return move_flags(args.book)
    if args.refresh:
        if args.kept is None:
            parser.error("--refresh needs --kept")
        return refresh(args.book, args.kept, args.write)
    if args.kept is None:
        parser.error("--kept is required")

    path = os.path.join(TMP, f"narrators_book_{args.book}_openiti.json")
    profiles = json.load(open(path))
    existing, appended = profiles[:args.kept], profiles[args.kept:]
    chosen, unmatched, ambiguous = match(existing, appended,
                                         drift_applies=args.book in FIRST_ENTRY_PAGE)

    print(f"{args.book}: {len(existing)} already on file, {len(appended)} appended")
    print(f"  matched   {len(chosen)}")
    first = FIRST_ENTRY_PAGE.get(args.book)
    front = [] if first is None else [i for i in unmatched
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
        appended[new_index]["printed_entry"] = False    # the key holder is the entry now
    write_json_atomic(path, existing + appended, indent=1)

    # an entry the printed text does not head is a man named inside another man's entry, or read
    # from the introduction: it anchors identity as if it were a main-book entry of its own, and
    # deciding each one needs reading, so they are listed rather than guessed at.
    write_json_atomic(os.path.join(TMP, "narrators_sources", f"{args.book}_not_an_entry.json"),
                      [{"source_key": f"{args.book}:{i}",
                        "name": existing[i]["primary_arabic_name"],
                        "recorded_pages": existing[i].get("source_pages"),
                        "from_the_introduction": first is not None
                        and bool(existing[i].get("source_pages"))
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
