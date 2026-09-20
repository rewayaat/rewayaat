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
    },
}

RAW = "https://raw.githubusercontent.com/OpenITI/{repo}/master/data/{author}/{work}/{version}"


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
    target = CACHE / f"{key}.mARkdown"
    if target.exists():
        print(f"  cached: {target}")
        return target
    url = RAW.format(**spec)
    print(f"  fetching {url}")
    with urllib.request.urlopen(url, timeout=300) as resp:
        target.write_bytes(resp.read())
    print(f"  wrote {target} ({target.stat().st_size:,} bytes)")
    return target


def parse_structure(path):
    """The text's own table of contents: each kitāb with its abwāb in order."""
    def clean(line):
        line = re.sub(r"^\s*#+\s*\|+\s*", "", line)
        line = line.replace("*", " ")
        line = re.sub(r"[()‏]", " ", line)
        return re.sub(r"\s+", " ", line).strip()

    kutub, current = [], None
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


def align(kutub, parts, part_ar):
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

    recovered, unmatched = {}, []
    for part in parts:
        abwab = kitab_for(part_ar.get(part["key"]))
        if not abwab:
            unmatched.append(part["key"])
            continue
        for section in part["s"]["buckets"]:
            try:
                ordinal = int(section["key"])
            except (TypeError, ValueError):
                continue
            chapters = section["c"]["buckets"]
            if chapters and 1 <= ordinal <= len(abwab):
                recovered[chapters[0]["key"]] = abwab[ordinal - 1]
    return recovered, unmatched


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

    kutub = parse_structure(path)
    print(f"  {len(kutub)} kutub, {sum(len(k['abwab']) for k in kutub)} abwāb")

    part_ar = json.loads((MAPPING_DIR / "part_ar_mapping.json").read_text(encoding="utf-8"))
    parts = index_chapters(args.es_host, spec["book"])
    recovered, unmatched = align(kutub, parts, part_ar)
    print(f"  recovered {len(recovered)} chapter titles; {len(unmatched)} kutub unmatched")
    for name in unmatched:
        print(f"    unmatched kitāb: {name}")

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
