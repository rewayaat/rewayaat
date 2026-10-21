#!/usr/bin/env python3
"""Pair Mishkat al-Anwar's v1 narrations with the Arabic of the OpenITI edition.

The v1 Arabic of Mishkat was extracted from a PDF: letters are damaged in most narrations
and the honorifics became glyphs. OpenITI holds a clean text of the same book
(0700AbuFadlCaliTabarsi.MishkatAnwar.Shia001383-ara1, ed. Mahdi Hushmand, 1418), with every
narration opened by "() -". This script finds, for each v1 narration, the edition's
narration it is, and takes the Arabic, the section heading and the chapter heading from the
edition. The English stays v1's: it is the translation the site publishes.

Matching is by letter 4-grams, the share of the v1 narration's 4-grams found in an edition
narration, so that damaged letters lower a score without hiding a match. Both texts are in
the book's order, so the pairing is a monotone alignment (a dynamic programme), not a best
match per narration: a short saying that recurs in two chapters is paired with the copy in
its own place.

A pair is "auto" when the scores both ways clear AUTO_SCORE and no other v1 narration takes
the same edition narration. Everything else is "review": a reader decides, or it stays out.

    python3 scripts/ingest/align_mishkat_openiti.py \\
        --openiti /mnt/share/rewayaat-backup/v1-snapshot/openiti-mishkat/MishkatAnwar.Shia001383-ara1 \\
        --batches <converter --out-dir, run without --hold-back> \\
        --out scripts/ingest/v1_restore_mishkat_edition.json
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

EDITION = "openiti:0700AbuFadlCaliTabarsi.MishkatAnwar.Shia001383-ara1"
BOOK_PREFIX = "Mishkat-al-Anwar-Tabrisi:"
NARRATION_MARK = "() -"
GRAM = 4
# A pair counts towards the alignment only above this share of shared 4-grams.
MATCH_FLOOR = 0.35
AUTO_SCORE = 0.6

PAGE_MARK = re.compile(r"PageV\d+P\d+")
QURAN_QUOTE = re.compile(r"\*\s*\(((?:[^()]|\([^()]*\))*?)\)\s*\*")
MS_MARK = re.compile(r"\bms\d+\b")
FOOTNOTE_REF = re.compile(r"\s*\(\d+\)")
HEADING = re.compile(r"^(الباب|الفصل)\s")
SAYING_START = re.compile(r"(?<=[.،*؟!]) (?=(?:وقال|وعنه|وعن|وسئل|وروي|وفي رواية) )")
UNMARKED_START = re.compile(r"(?<=\s)(?=من كتاب |قال )")
HONORIFIC = re.compile(r"\((صلى الله عليه وآله|عليه السلام|عليهم السلام|عليها السلام|عليهما السلام)\)")
DIACRITICS = re.compile(r"[ً-ٰٟـ]")


def letters(text: str) -> str:
    """Bare letters with the spellings that vary between editions folded together."""
    out = DIACRITICS.sub("", text)
    out = re.sub("[إأآٱ]", "ا", out).replace("ى", "ي").replace("ة", "ه")
    out = out.replace("ؤ", "و").replace("ئ", "ي").replace("ء", "")
    return re.sub(r"[^ء-ي]", "", out)


def grams(text: str) -> set[str]:
    s = letters(text)
    return {s[i:i + GRAM] for i in range(len(s) - GRAM + 1)}


def tidy(text: str) -> str:
    """The edition's text without its page, manuscript and footnote marks. A Qur'anic
    quotation, «* ( … ) *» in OpenITI, takes the «﴿ … ﴾» the rest of the corpus uses."""
    out = FOOTNOTE_REF.sub("", MS_MARK.sub("", PAGE_MARK.sub("", text)))
    out = re.sub(r"\s+", " ", out).strip()
    # A " * " inside a quotation ends one verse and begins the next.
    out = QURAN_QUOTE.sub(lambda m: "﴿" + re.sub(r"\s*\*\s*", "﴾ ﴿", m.group(1).strip()) + "﴾", out)
    out = re.sub(r" +([.،:؛!؟)])", r"\1", out).strip(" -")
    return re.sub(r"[،,]$", ".", out)


def heading_title(text: str) -> str:
    """'الباب الأول في …، وفيه خمسة عشر فصلا:' -> 'الباب الأول في …'; dashes as one space."""
    out = re.sub(r"\s*،?\s*وفيه\b.*$", "", tidy(text)).rstrip(":.، ")
    return re.sub(r"\s*-\s*", " ", out)


def parse_edition(path: Path) -> list[dict]:
    """The edition's narrations in order, each with the section and chapter it sits under.

    A heading can wrap onto continuation lines, and the first narration under it can start
    on the heading's own line; both are split at the narration mark.
    """
    body = path.read_text(encoding="utf-8").split("#META#Header#End#", 1)[1]
    units, section, chapter, current, heading = [], None, None, None, None
    for line in body.splitlines():
        if line.startswith("~~"):
            text = line[2:]
        elif line.startswith("# "):
            text = line[2:]
            heading = None
            if HEADING.match(text.strip()):
                heading = {"raw": ""}
                if text.startswith("الباب"):
                    section, chapter = heading, None
                else:
                    chapter = heading
                current = None
        else:
            continue
        if heading is not None:
            head, mark, rest = text.partition(NARRATION_MARK)
            unmarked = UNMARKED_START.search(head)
            if unmarked:
                # Twice the edition runs a chapter's first narration (or its opening
                # verse) into the heading with no mark: «في المصافحة والتقبيل من كتاب …».
                head, mark, rest = head[:unmarked.start()], NARRATION_MARK, head[unmarked.start():] + mark + rest
            heading["raw"] += " " + head
            if not mark:
                continue
            heading, text = None, NARRATION_MARK + rest
        pieces = text.split(NARRATION_MARK)
        if current is not None:
            current["raw"] += " " + pieces[0]
        for piece in pieces[1:]:
            current = {"raw": piece, "section": section, "chapter": chapter}
            units.append(current)
    out = []
    for n, unit in enumerate(units):
        text = tidy(unit["raw"])
        if text:
            out.append({"index": n, "arabic": text,
                        "section": heading_title(unit["section"]["raw"]) if unit["section"] else None,
                        "chapter": heading_title(unit["chapter"]["raw"]) if unit["chapter"] else None})
    return out


def read_narrations(batches: Path) -> list[dict]:
    docs = []
    for path in sorted(batches.glob("batch_*.jsonl")):
        for line in path.open(encoding="utf-8"):
            doc = json.loads(line)
            if doc["_id"].startswith(BOOK_PREFIX):
                docs.append(doc)
    return sorted(docs, key=lambda d: int(d["_source"]["number"]))


def strip_running_heads(arabic: str, part_ar: str | None) -> str:
    """The PDF's running header, which v1 ran into many narrations: «… في محاسن الأفعال …»."""
    title = re.sub(r"^الباب\s+\S+\s+", "", part_ar or "")
    words = title.split()
    if len(words) < 3:
        return arabic
    cut = arabic.find(" ".join(words[:3]))
    return arabic[:cut] if cut > 0 else arabic


def scores(docs, units):
    index = defaultdict(set)
    unit_grams = [grams(HONORIFIC.sub("", u["arabic"])) for u in units]
    for i, g in enumerate(unit_grams):
        for gram in g:
            index[gram].add(i)
    table = []
    for doc in docs:
        src = doc["_source"]
        g = grams(strip_running_heads(src["arabic"], src.get("part_ar")))
        counts = Counter()
        for gram in g:
            for i in index.get(gram, ()):
                counts[i] += 1
        row = {}
        for i, shared in counts.items():
            forward = shared / len(g)
            if forward >= MATCH_FLOOR:
                row[i] = (forward, shared / max(1, len(unit_grams[i])))
        table.append(row)
    return table


def align(table, unit_count: int) -> list[int | None]:
    """The monotone pairing with the greatest total score. Two v1 narrations may share one
    edition narration (v1 sometimes split one in two); order may not go backwards."""
    n = len(table)
    best = [[0.0] * (unit_count + 1) for _ in range(n + 1)]
    move = [[0] * (unit_count + 1) for _ in range(n + 1)]
    for i in range(1, n + 1):
        row, prev, here = table[i - 1], best[i - 1], best[i]
        for j in range(1, unit_count + 1):
            take = prev[j] + (row[j - 1][0] if (j - 1) in row else 0.0)
            skip_unit = here[j - 1]
            if take >= skip_unit:
                here[j], move[i][j] = take, 1 if (j - 1) in row else 2
            else:
                here[j], move[i][j] = skip_unit, 0
    pairing: list[int | None] = [None] * n
    i, j = n, unit_count
    while i > 0 and j > 0:
        step = move[i][j]
        if step == 0:
            j -= 1
        else:
            if step == 1:
                pairing[i - 1] = j - 1
            i -= 1
    return pairing


def segments(text: str) -> list[str]:
    """An edition narration cut where a further saying begins without its own mark."""
    return SAYING_START.split(text)


def best_span(src: dict, arabic: str):
    """The run of segments of an edition narration that is this v1 narration, and its Dice
    score, when the edition narration holds more than one saying."""
    doc = grams(strip_running_heads(src["arabic"], src.get("part_ar")))
    parts = segments(arabic)
    if not doc or len(parts) < 2:
        return None
    best = None
    for i in range(len(parts)):
        for j in range(i + 1, len(parts) + 1):
            text = " ".join(parts[i:j])
            g = grams(HONORIFIC.sub("", text))
            dice = 2 * len(doc & g) / (len(doc) + len(g)) if g else 0.0
            if best is None or dice > best[1]:
                best = (text, dice)
    return best


def fill_by_position(docs, pairing, unit_count: int):
    """v1 narrations whose Arabic is empty or unmatchable, paired with the edition narrations
    left free in the same gap of the alignment, when the counts agree exactly."""
    used = {p for p in pairing if p is not None}
    out, gap_docs, last = [], [], -1
    for doc, p in list(zip(docs, pairing)) + [(None, unit_count)]:
        if p is None:
            gap_docs.append(doc)
            continue
        free = [u for u in range(last + 1, p) if u not in used]
        if gap_docs and len(free) == len(gap_docs):
            out += list(zip(gap_docs, free))
        gap_docs, last = [], max(last, p)
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--openiti", required=True)
    parser.add_argument("--batches", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--review-out", help="pairs needing a reader, with both texts")
    args = parser.parse_args()

    units = parse_edition(Path(args.openiti))
    docs = read_narrations(Path(args.batches))
    table = scores(docs, units)
    pairing = align(table, len(units))
    shared = Counter(p for p in pairing if p is not None)

    # The edition's heading for each v1 heading, by the majority of the pairs under it.
    votes = defaultdict(Counter)
    for doc, p in zip(docs, pairing):
        if p is not None:
            src = doc["_source"]
            votes[("part", src.get("part"))][units[p]["section"]] += 1
            votes[("chapter", src.get("part"), src["chapter"])][units[p]["chapter"]] += 1

    narrations, review = {}, []
    for n, (doc, p) in enumerate(zip(docs, pairing)):
        if p is None:
            continue
        forward, backward = table[n][p]
        record = {"edition_index": units[p]["index"], "arabic": units[p]["arabic"],
                  "forward": round(forward, 3), "backward": round(backward, 3)}
        if forward >= AUTO_SCORE and backward >= AUTO_SCORE and shared[p] == 1:
            record["verdict"] = "auto"
        else:
            span = best_span(doc["_source"], units[p]["arabic"])
            if span and span[1] >= AUTO_SCORE:
                record.update(arabic=tidy(span[0]), dice=round(span[1], 3), verdict="span")
            else:
                record["verdict"] = "shared" if shared[p] > 1 else "weak"
        narrations[doc["_id"]] = record

    for doc, p in fill_by_position(docs, pairing, len(units)):
        narrations[doc["_id"]] = {"edition_index": units[p]["index"], "arabic": units[p]["arabic"],
                                  "verdict": "positional"}

    for doc in docs:
        src = doc["_source"]
        record = narrations.get(doc["_id"], {"verdict": "no-match"})
        if record["verdict"] != "auto":
            review.append({"id": doc["_id"], "number": src["number"], "english": src["english"],
                           "v1_arabic": src["arabic"], **record})

    headings = {"part": {}, "chapter": {}}
    # Each v1 heading takes the edition heading most of its narrations sit under, and no
    # two take the same one: where v1 and the edition draw a chapter boundary one
    # narration apart, a two-narration chapter can otherwise tie with its neighbour.
    taken = defaultdict(set)
    for key, counter in sorted(votes.items(), key=lambda kv: -kv[1].most_common(1)[0][1]):
        kind = key[0]
        for title, _ in counter.most_common():
            if title is not None and title not in taken[kind]:
                taken[kind].add(title)
                name = key[1] if kind == "part" else f"{key[1]}\u0000{key[2]}"
                headings[kind][name] = title
                break

    out = {"edition": EDITION, "narrations": narrations, "headings": headings}
    Path(args.out).write_text(json.dumps(out, ensure_ascii=False, indent=1, sort_keys=True) + "\n",
                              encoding="utf-8")
    if args.review_out:
        Path(args.review_out).write_text(json.dumps(review, ensure_ascii=False, indent=1) + "\n",
                                         encoding="utf-8")
    verdicts = Counter(r["verdict"] for r in narrations.values())
    print(json.dumps({"edition_narrations": len(units), "v1_narrations": len(docs),
                      "paired": len(narrations), "unpaired": len(docs) - len(narrations),
                      **verdicts}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
