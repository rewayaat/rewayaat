#!/usr/bin/env python3
"""Checks every stated city, kunya and title against the passage it is cited from.

An audit reads a sample. 852 statements were read by people out of 23,936 a page states, 3.6%
of them, and the answer came back as a range rather than a number — stated grades anywhere
between 59.7% and 87.7%. A rule reads all of them. It cannot judge whether «عظيم المنزلة» is
a grade of trustworthiness, which is why the audits matter, but it can say whether the words
a claim rests on stand in the entry at all, and it can say it for the whole corpus at once.

Three fields have no such check. Grades have `shown()` and relations have `names_him`; a city,
a kunya and a title are printed on a page with nothing testing them. This is that test, and
where the rule and the auditors disagree the auditors are right — the rule is a floor, not a
verdict.

Usage:
    python3 scripts/narrators/field_check.py            # count, corpus-wide
    python3 scripts/narrators/field_check.py --show 12  # print what fails
"""
import argparse
from collections import Counter
import json
import os
import re
import sys
import urllib.request

W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
sys.path.insert(0, f'{W}/scripts/narrators')
from narrator_schema import normalize_arabic  # noqa: E402

ARABIC = re.compile(r'[؀-ۿ]')
# A nisbah carries the place: «الكوفي» for Kufa, «القمي» for Qum. A claim is borne out by
# either the place or the nisbah built on it, since a book says a man is of Kufa both ways.
NISBAH = ("ي", "ية", "يه")


def forms(value, arabic):
    """Every Arabic string that would bear this claim out."""
    out = set()
    for candidate in (value, arabic):
        if not candidate or not ARABIC.search(candidate or ""):
            continue
        folded = normalize_arabic(candidate)
        out.add(folded)
        bare = folded[2:] if folded.startswith("ال") else folded
        out.add(bare)
        for tail in NISBAH:
            out.add(bare.rstrip("ةه") + tail)
    return {f for f in out if len(f) >= 3}


def borne_out(claim, arabic, passages):
    body = " ".join(normalize_arabic(p) for p in passages if p)
    if not body:
        return "no passage held"
    candidates = forms(claim, arabic)
    if not candidates:
        # «Kufa» cannot be looked for in an Arabic entry, and two cities in five have no
        # Arabic form recorded. Those are untestable by rule, not failures.
        return "no Arabic form to look for"
    for form in candidates:
        if form in body:
            return "in the passage"
    return "not in the passage"


def pages(host, index):
    after = None
    while True:
        body = {"size": 500, "sort": [{"person_id": "asc"}], "query": {"match_all": {}},
                "_source": ["person_id", "display_ar", "life", "titles", "kunyahs_ar",
                            "entries"]}
        if after:
            body["search_after"] = after
        request = urllib.request.Request(f'{host}/{index}/_search',
                                         data=json.dumps(body).encode(),
                                         headers={"Content-Type": "application/json"})
        hits = json.load(urllib.request.urlopen(request))["hits"]["hits"]
        if not hits:
            return
        for hit in hits:
            yield hit["_source"]
        after = hits[-1]["sort"]


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--es-host", default="http://localhost:9200")
    parser.add_argument("--index", default="rewayaat_narrators")
    parser.add_argument("--show", type=int, default=0)
    args = parser.parse_args()

    counts, shown = Counter(), Counter()
    examples = []
    for page in pages(args.es_host, args.index):
        passages = [e.get("text_ar") or "" for e in page.get("entries") or []]
        passages.append(page.get("display_ar") or "")
        life = page.get("life") or {}
        claims = [("city", life.get("city"), life.get("city_ar"))]
        claims += [("kunya", k, k) for k in page.get("kunyahs_ar") or []]
        claims += [("title", t, t) for t in page.get("titles") or []]
        for field, value, arabic in claims:
            if not value:
                continue
            verdict = borne_out(value, arabic, passages)
            counts[(field, verdict)] += 1
            if verdict == "not in the passage" and len(examples) < args.show:
                examples.append((page["person_id"], field, value))
    for field in ("city", "kunya", "title"):
        held = sum(n for (f, _), n in counts.items() if f == field)
        good = counts[(field, "in the passage")]
        bad = counts[(field, "not in the passage")]
        mute = counts[(field, "no Arabic form to look for")] + counts[(field, "no passage held")]
        print(f"  {field:6s} {held:6,} stated   {100 * good / max(good + bad, 1):5.1f}% borne out"
              f"  ({good:,} in the entry, {bad:,} not, {mute:,} the rule cannot test)")
    for pid, field, value in examples:
        print(f"    {pid}  {field}  «{value}»")


if __name__ == "__main__":
    main()
