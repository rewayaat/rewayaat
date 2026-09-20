#!/usr/bin/env python3
"""Find each chapter's bāb heading by locating its narrations in the printed text.

Aligning by ordinal was fragile for the reason any positional join is: the moment an
edition carries one heading the index does not, everything after it is off by one and
still looks plausible. al-Tawḥīd matched 68 chapters against 68 and every title was the
neighbouring one; al-Khiṣāl drifted from its second chapter.

This joins on the text instead. Each narration's Arabic is in both places - the index
holds it and the printed book contains it under some bāb - so the bāb that contains a
narration is that narration's chapter, whatever either side calls it and however the
editions differ in structure. Nothing here depends on a chapter count, an ordinal, or a
kitāb name.

Two details make it work. The probe is taken from the back half of the narration, because
the front is isnād and the same chain introduces hundreds of hadith. And a chapter is
decided by its narrations together: each one votes for the bāb it was found in, and a
chapter is only accepted when the winner holds a majority, so one hadith quoted in two
places cannot carry the chapter with it.

    python3 scripts/i18n/match_chapters_by_matn.py --book al-khisal
"""

import argparse
import json
import pathlib
import re
import sys
import urllib.request
from collections import Counter, defaultdict

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
CACHE = ROOT / "tmp" / "openiti"

# Book key -> the index's book name, and the heading depth its abwāb sit at. al-Kāfī and
# al-Khiṣāl nest them under kutub at ### ||; al-Tawḥīd and Maʿānī al-ʾAkhbār have no kitāb
# layer and carry them at ### |. Reading the wrong depth finds no segments at all.
BOOKS = {
    "al-kafi": ("Al-Kāfi", 2),
    "al-khisal": ("Al-Khiṣāl", 2),
    "man-la-yahduruh": ("Man Lā Yaḥḍuruh al-Faqīh", 2),
    "thawab-al-amal": ("Thawāb al-Aʿmāl wa ʿiqāb al-Aʿmāl", 2),
    "maani-al-akhbar": ("Maʿānī al-ʾAkhbār", 1),
    "al-tawhid": ("Al-Tawḥīd", 1),
    "uyun-akhbar": ("ʿUyūn akhbār al-Riḍā", 1),
}

# Several windows rather than one. A single probe can still land in the isnād of a long
# chain, or inside a hadith the book quotes twice, and one bad window then decides the
# chapter. Windows must agree before the narration votes at all.
PROBE_AT = (0.40, 0.55, 0.70, 0.85)
PROBE_LEN = 45
MIN_TEXT = 120       # shorter narrations have too little matn to probe safely


def flatten(text):
    """Fold the spellings and marks that differ between an index and a printing."""
    text = re.sub(r"[ً-ْـ]", "", text or "")
    text = re.sub(r"<[^>]*>", " ", text)
    text = re.sub(r"[^ء-ي ]", " ", text)
    text = (text.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
                .replace("ى", "ي").replace("ة", "ه"))
    return re.sub(r"\s+", " ", text).strip()


def segments(path, level=2):
    """Every bāb heading with the text that follows it."""
    found, heading, body = [], None, []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if line.startswith("### "):
            if heading is not None:
                found.append((heading, " ".join(body)))
            if level == 2:
                is_heading = line.startswith("### ||")
            else:
                is_heading = line.startswith("### |") and not line.startswith("### ||")
            heading = (re.sub(r"^\s*#+\s*\|+\s*", "", line).replace("*", " ").strip()
                       if is_heading else None)
            body = []
        elif heading is not None:
            body.append(line)
    if heading is not None:
        found.append((heading, " ".join(body)))
    return [(h, b) for h, b in found if h]


def narrations(es_host, index, book):
    """Every narration of a book, with its chapter and its Arabic."""
    body = {"size": 1000, "_source": ["chapter", "arabic"],
            "query": {"term": {"book": book}}}
    request = urllib.request.Request(f"{es_host}/{index}/_search?scroll=5m",
                                     data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json"})
    page = json.load(urllib.request.urlopen(request, timeout=120))
    scroll_id = page.get("_scroll_id")
    while True:
        hits = page["hits"]["hits"]
        if not hits:
            break
        for hit in hits:
            yield hit["_source"]
        request = urllib.request.Request(f"{es_host}/_search/scroll",
                                         data=json.dumps({"scroll": "5m",
                                                          "scroll_id": scroll_id}).encode(),
                                         headers={"Content-Type": "application/json"})
        page = json.load(urllib.request.urlopen(request, timeout=120))


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--book", required=True, choices=sorted(BOOKS))
    parser.add_argument("--es-host", default="http://localhost:9200")
    parser.add_argument("--index", default="rewayaat_hadith")
    args = parser.parse_args()

    source = CACHE / f"{args.book}.txt"
    if not source.exists():
        print(f"  no source at {source}; fetch it with recover_chapter_titles.py --fetch")
        return 1

    book_name, level = BOOKS[args.book]
    bodies = [(heading, flatten(text)) for heading, text in segments(source, level)]
    print(f"  {len(bodies)} bāb segments in the source")

    votes = defaultdict(Counter)
    probed = 0
    for record in narrations(args.es_host, args.index, book_name):
        chapter = (record.get("chapter") or "").strip()
        text = flatten(record.get("arabic", ""))
        if not chapter or len(text) < MIN_TEXT:
            continue
        hits_here = Counter()
        for fraction in PROBE_AT:
            start = int(len(text) * fraction)
            probe = text[start:start + PROBE_LEN]
            if len(probe) < 30:
                continue
            for heading, body in bodies:
                if probe in body:
                    hits_here[heading] += 1
                    break
        if not hits_here:
            continue
        heading, agreeing = hits_here.most_common(1)[0]
        # One window is a coincidence; two windows landing in the same bāb is the hadith.
        if agreeing < 2:
            continue
        probed += 1
        votes[chapter][heading] += 1

    def tidy(heading):
        """Editors bracket the parts they supplied: "(باب)   (فضل الصلاة)" is one title."""
        text = re.sub(r"[()\[\]]", " ", heading)
        text = re.sub(r"\bms\d+", " ", text)
        text = re.sub(r"\s*\d+\s*$", "", text)
        return re.sub(r"\s+", " ", text).strip()

    matched, weak = {}, 0
    for chapter, counter in votes.items():
        heading, count = counter.most_common(1)[0]
        if count * 2 > sum(counter.values()):
            matched[chapter] = tidy(heading)
        else:
            weak += 1

    print(f"  probed {probed} narrations across {len(votes)} chapters")
    print(f"  matched {len(matched)} chapters; {weak} had no majority")
    out = CACHE / f"{args.book}_matn_matched.json"
    out.write_text(json.dumps(matched, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"  written: {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
