#!/usr/bin/env python3
"""Mends the narration text the corpus audit of 2026-10-22 found damaged.

Six classes, found by scanning all 50,038 narrations:

**Lost characters.** 89 narrations carry U+FFFD where a character used to be — al-Kāfi and
the other OCR'd books lost it at ingest, and the run length says what went: an Arabic letter
is two bytes, so «��» is one lost letter or vowel mark. Each is restored by name in
`data/corpus_text_repairs.json`, with the reading that settles it; one, in a line of poetry,
is left lost rather than guessed.

**Placeholders.** Three narrations serve «To be added» or «arabicnotfound» as their Arabic.
The field is emptied, so the page shows the translation alone instead of a promise.

**Invisible characters.** 3,779 narrations carry zero-width joiners, directional marks or
bidi embedding controls — «بقاء‌ه» inside a word, and in al-Tawḥīd's English whole runs
of them left by the extraction. They are invisible on the page but they sit inside words,
where they defeat any literal match.

**Misplaced shadda.** Ghurar al-Ḥikam and Mīzān al-Ḥikma write the salutation «الّسَلامُ»,
with the doubling mark on the article's lām instead of the sīn, 14,447 times. The rule is
aimed at that word alone: «الّذي» and «الّتي» carry the mark on the lām correctly and are
left as they are.

**Glued words.** 282 places where a space went missing after a tāʾ marbūṭa or an alif
maqṣūra — «المستحاضةوالشراب», «يحيىالعطار». Both letters are word-final, so the split point
is not a guess; «شىء», where the alif maqṣūra is medial, is the one exception and is excluded.

**Tabs and non-breaking spaces.** A tab separates the list markers Word left in 783
translations; three narrations use U+00A0 to separate words. Both become an ordinary space.

Chain links are stored as offsets into the segmenter's chain, not into the whole narration,
so this remaps every one of them: it recovers where the chain starts, shifts each span by
however much the text before it changed, and refuses to write a document whose spans no
longer cover the names they claim. A span whose own text was mended keeps its man.

Usage:
    python3 scripts/repair_corpus_text.py                 # what it would change
    python3 scripts/repair_corpus_text.py --live          # change it
    python3 scripts/repair_corpus_text.py --verify        # what is left
    python3 scripts/repair_corpus_text.py --live --only invisible,tab
"""
import argparse
import json
import os
import re
import sys
import urllib.request
from collections import Counter

W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
ES = os.environ.get("ES_HOST", "http://localhost:9200")
INDEX = os.environ.get("ES_INDEX", "rewayaat_hadith")
MANIFEST = f"{W}/scripts/data/corpus_text_repairs.json"

# chain spans and the field each one indexes into
LINK_FIELDS = {"chain_links": "arabic", "chain_links_en": "english", "chain_people": "arabic"}

LETTER = "ء-ي"
SUN = "تثدذرزسشصضطظن"

# Zero-width joiners, the directional marks, the bidi embedding controls and the BOM. All of
# them render as nothing and all of them break a literal match.
INVISIBLE = re.compile("[​-‏‪-‮⁠﻿]")
NBSP = re.compile(" ")
TAB = re.compile("\t")
# The doubling mark one letter early, on the article's lām. Aimed at «الّسَلام» by the «لا»
# that follows it, which is what keeps «الّذي» and «الّتي» — correct as they stand — out.
ARTICLE_SHADDA = re.compile(f"(?<![{LETTER}])([وفبكل]?ال)"
                            f"ّ(س)(?=[ً-ٕ]*لا)")
# A tāʾ marbūṭa or alif maqṣūra with a word glued to it. Both are word-final — except the
# alif maqṣūra of «شىء», which is why a hamza never counts as the start of the next word.
GLUED_TA = re.compile(f"(ة[ً-ٕ]*)(?=[{LETTER}])")
GLUED_YA = re.compile(f"(ى[ً-ٕ]*)(?=[آ-ي])")

RULES = (
    ("invisible", INVISIBLE, "", ("arabic", "english")),
    ("nbsp", NBSP, " ", ("arabic", "english")),
    ("tab", TAB, " ", ("arabic", "english")),
    ("shadda", ARTICLE_SHADDA, r"\1\2ّ", ("arabic",)),
    ("glue", GLUED_TA, r"\1 ", ("arabic",)),
    ("glue", GLUED_YA, r"\1 ", ("arabic",)),
)
CLASSES = ("lost", "placeholder", "invisible", "nbsp", "tab", "shadda", "glue")


def ask(path, body=None, method=None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(f"{ES}{path}", data=data, method=method,
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=300) as answer:
        return json.load(answer)


def narrations(source):
    """Every narration, a page at a time, so the whole corpus never sits in memory.

    The scan holds a point in time. Document order is segment order, and writing a document
    moves it to a new segment: without a frozen snapshot a scan that repairs as it reads
    meets some narrations twice and never reaches others.
    """
    pit = ask(f"/{INDEX}/_pit?keep_alive=30m", {}, method="POST")["id"]
    after = None
    try:
        while True:
            body = {"size": 500, "sort": ["_doc"], "_source": source,
                    "pit": {"id": pit, "keep_alive": "30m"}}
            if after:
                body["search_after"] = after
            answer = ask("/_search", body)
            pit = answer.get("pit_id", pit)
            page = answer["hits"]["hits"]
            if not page:
                return
            for hit in page:
                yield hit["_id"], hit["_source"]
            after = page[-1]["sort"]
    finally:
        try:
            ask("/_pit", {"id": pit}, method="DELETE")
        except OSError:
            pass


def edits_for(text, field, per_case, only):
    """Every change this text wants, as (start, end, instead) over the text as it stands."""
    edits = []
    for case in per_case:
        if case.get("skip") or case.get("blank") or case["field"] != field:
            continue
        found = occurrences(text, case["old"])
        if not found and case["new"] and occurrences(text, case["new"]):
            case["done"] = True           # already mended; a re-run must not undo or refuse
            continue
        if len(found) != 1:
            raise Refused(f"{case['old']!r} occurs {len(found)} times, expected once")
        # The manifest names the whole word, so that a reader can see what was decided and
        # so the anchor is unambiguous. The edit itself is narrowed to the characters that
        # actually differ: a word is long enough to reach across the end of a chain span,
        # and an edit that straddles one cannot be shifted.
        old, new = case["old"], case["new"]
        head = len(os.path.commonprefix([old, new]))
        tail = len(os.path.commonprefix([old[head:][::-1], new[head:][::-1]]))
        edits.append((found[0] + head, found[0] + len(old) - tail,
                      new[head:len(new) - tail], "lost"))
    for name, rule, instead, fields in RULES:
        if field not in fields or name not in only:
            continue
        for match in rule.finditer(text):
            edits.append((match.start(), match.end(), match.expand(instead), name))
    edits.sort()
    for before, after in zip(edits, edits[1:]):
        if before[1] > after[0]:
            raise Refused(f"two repairs overlap at {after[0]}")
    return edits


class Refused(Exception):
    """The document is left exactly as it was."""


def occurrences(text, needle):
    """Every place `needle` sits in `text`."""
    found = []
    at = text.find(needle)
    while at >= 0:
        found.append(at)
        at = text.find(needle, at + 1)
    return found


def apply_edits(text, edits):
    out = []
    at = 0
    for start, end, instead, _ in edits:
        out.append(text[at:start])
        out.append(instead)
        at = end
    out.append(text[at:])
    return "".join(out)


def shift(position, edits):
    """Where `position` lands once every repair before it has been made."""
    moved = position
    for start, end, instead, _ in edits:
        if end <= position:
            moved += len(instead) - (end - start)
        elif start < position:
            raise Refused(f"a repair straddles offset {position}")
    return moved


def chain_start(text, links):
    """Where the chain the offsets were taken from begins in this field.

    The spans index into the segmenter's chain, which usually begins at the narration's
    first character but not always: a leading space is not part of it, and in al-Ṣadūq's
    Mashyakha the entry's preamble comes first. The offset is recovered from the spans
    themselves — the one shift that makes every span cover the name it claims.
    """
    candidates = None
    for link in links:
        here = {i - link["s"] for i in occurrences(text, link["t"])}
        candidates = here if candidates is None else candidates & here
        if not candidates:
            return None
    return min(candidates, key=abs) if candidates else None


def repair(doc, per_case, only):
    """One narration: the new text, the new spans, and what changed. Refuses, or returns."""
    new = {}
    counts = Counter()
    for field in ("arabic", "english"):
        text = doc.get(field) or ""
        if not text and not any(c.get("blank") and c["field"] == field for c in per_case):
            continue
        blanks = [c for c in per_case if c.get("blank") and c["field"] == field]
        if blanks and not text.strip():
            for case in blanks:
                case["done"] = True       # already emptied
            blanks = []
        if blanks:
            new[field] = ""
            counts["placeholder"] += 1
            continue
        edits = edits_for(text, field, per_case, only)
        if not edits:
            continue
        new[field] = apply_edits(text, edits)
        for *_, name in edits:
            counts[name] += 1
        for array, indexed in LINK_FIELDS.items():
            if indexed != field or not doc.get(array):
                continue
            links = doc[array]
            start = chain_start(text, links)
            if start is None:
                raise Refused(f"{array}: cannot tell where the chain starts")
            moved_start = shift(start, edits)
            spans = []
            for link in links:
                low = shift(start + link["s"], edits) - moved_start
                high = shift(start + link["e"], edits) - moved_start
                covered = new[field][moved_start + low:moved_start + high]
                # what the span should now read: its own text with the repairs that fall
                # inside it, and nothing else. Anything else means an offset has drifted.
                inside = [(s - start - link["s"], e - start - link["s"], instead, name)
                          for s, e, instead, name in edits
                          if s >= start + link["s"] and e <= start + link["e"]]
                if covered != apply_edits(link["t"], inside):
                    raise Refused(f"{array}: span {link['s']}-{link['e']} drifted off "
                                  f"{link['t']!r} onto {covered!r}")
                spans.append({**link, "s": low, "e": high, "t": covered})
            new[array] = spans
    return new, counts


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--live", action="store_true", help="write the repairs to the index")
    parser.add_argument("--verify", action="store_true",
                        help="count what is still damaged, and check every chain span")
    parser.add_argument("--only", default=",".join(CLASSES),
                        help=f"the classes to mend ({', '.join(CLASSES)})")
    parser.add_argument("--es-host", default=None)
    parser.add_argument("--index", default=None)
    args = parser.parse_args()
    global ES, INDEX
    ES = args.es_host or ES
    INDEX = args.index or INDEX
    only = {c.strip() for c in args.only.split(",") if c.strip()}
    unknown = only - set(CLASSES)
    if unknown:
        parser.error(f"unknown class: {', '.join(sorted(unknown))}")

    manifest = json.load(open(MANIFEST, encoding="utf-8"))
    per_doc = {}
    if "lost" in only:
        for case in manifest["lost_characters"] + manifest["replacements"]:
            per_doc.setdefault(case["id"], []).append(case)
    if "placeholder" in only:
        for case in manifest["blank_field"]:
            per_doc.setdefault(case["id"], []).append({**case, "blank": True})

    if args.verify:
        verify()
        return

    source = ["arabic", "english", "book"] + list(LINK_FIELDS)
    counts = Counter()
    touched = refused = 0
    spans_moved = 0
    actions = []
    seen_cases = set()
    for doc_id, doc in narrations(source):
        cases = per_doc.get(doc_id, [])
        try:
            new, changed = repair(doc, cases, only)
        except Refused as why:
            refused += 1
            print(f"  refused {doc_id}: {why}")
            continue
        if not new:
            continue
        for case in cases:
            seen_cases.add(id(case))
        touched += 1
        counts.update(changed)
        spans_moved += sum(len(v) for k, v in new.items() if k in LINK_FIELDS)
        actions.append({"update": {"_index": INDEX, "_id": doc_id}})
        actions.append({"doc": new})
        if args.live and len(actions) >= 1000:
            flush(actions)
    if args.live and actions:
        flush(actions)

    missed = [c for c in sum(per_doc.values(), []) if id(c) not in seen_cases
              and not c.get("skip") and not c.get("done")]
    already = sum(1 for c in sum(per_doc.values(), []) if c.get("done"))
    print(f"\n  {touched:,} narrations {'repaired' if args.live else 'to repair'}, "
          f"{refused} refused, {spans_moved:,} chain spans remapped")
    for name in CLASSES:
        if counts[name]:
            print(f"      {counts[name]:7,}  {name}")
    if already:
        print(f"      {already:7,}  named repairs already in place")
    if missed:
        print(f"  {len(missed)} manifest cases never matched a narration:")
        for case in missed[:10]:
            print(f"      {case['id']} {case['field']} {case.get('old', '')!r}")
    if not args.live:
        print("\n  nothing was written; pass --live to write it")


def flush(actions):
    body = "\n".join(json.dumps(a, ensure_ascii=False) for a in actions) + "\n"
    request = urllib.request.Request(f"{ES}/_bulk", data=body.encode(), method="POST",
                                     headers={"Content-Type": "application/x-ndjson"})
    with urllib.request.urlopen(request, timeout=600) as answer:
        result = json.load(answer)
    if result.get("errors"):
        for item in result["items"]:
            for _, outcome in item.items():
                if outcome.get("error"):
                    raise SystemExit(f"bulk update failed: {outcome['error']}")
    actions.clear()


def verify():
    """What the audit's own scan says now, and whether every chain span still holds."""
    damaged = Counter()
    aligned = Counter()
    adrift = []
    seen = 0
    for doc_id, doc in narrations(["arabic", "english"] + list(LINK_FIELDS)):
        seen += 1
        both = (doc.get("arabic") or "") + "\n" + (doc.get("english") or "")
        if "�" in both:
            damaged["lost characters"] += 1
        if re.search(r"^\s*(to be added|arabicnotfound)\s*$", doc.get("arabic") or "", re.I):
            damaged["placeholders"] += 1
        if INVISIBLE.search(both):
            damaged["invisible characters"] += 1
        if NBSP.search(both):
            damaged["non-breaking spaces"] += 1
        if TAB.search(both):
            damaged["tabs"] += 1
        if ARTICLE_SHADDA.search(doc.get("arabic") or ""):
            damaged["misplaced shadda"] += 1
        if GLUED_TA.search(doc.get("arabic") or "") or GLUED_YA.search(doc.get("arabic") or ""):
            damaged["glued words"] += 1
        for array, field in LINK_FIELDS.items():
            links = doc.get(array) or []
            if not links:
                continue
            text = doc.get(field) or ""
            start = chain_start(text, links)
            if start is None:
                adrift.append((doc_id, array))
                aligned[f"{array} adrift"] += len(links)
            else:
                aligned[array] += len(links)
    print(f"  {seen:,} narrations")
    print("  still damaged:" if damaged else "  nothing left damaged")
    for name, n in damaged.most_common():
        print(f"      {n:7,}  {name}")
    print("  chain spans:")
    for name, n in aligned.most_common():
        print(f"      {n:7,}  {name}")
    for doc_id, array in adrift[:10]:
        print(f"      adrift: {doc_id} {array}")


if __name__ == "__main__":
    main()
