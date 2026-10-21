#!/usr/bin/env python3
"""Write the Arabic titles and citations of the restored v1 books into the index.

Two inputs from a translation pass (plans/2026-10-19-restore-v1-books-phase-4.md, 4.1):
  v1_restore_translations.json     {"chapter": {english: arabic}, "part": {english: arabic}}
  v1_restore_cited_books_ar.json   {cited book in English: its Arabic title, or null}

A Mizan citation such as "Al-Kafi, volume:2, p. 491, number:9" becomes
"الكافي، ج 2، ص 491، ح 9". A citation with any part the rules do not know gets no Arabic
rather than a half-translated one; the card then shows the English.

Only fields a narration lacks are written, so a title the book itself gives in Arabic is
never replaced. Dry run by default:

    python3 scripts/ingest/apply_v1_restore_translations.py --es-url http://localhost:9200 \\
        --index rewayaat_hadith [--live]
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.request
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from import_hadith_batches import _request_ndjson  # noqa: E402

HERE = Path(__file__).parent
BOOKS = ["Ghurar al-Ḥikam", "Mīzān al-Ḥikma", "Mishkāt al-Anwār"]
TRANSLATED = "llm-translated"
BULK = 500

N = r"(\d+)"
LIST = r"(\d+)(?:\s*(?:[,;]|and)\s*(\d+))?"
RULES = [
    (re.compile(rf"(?:volume:|v\.)\s*{N}\.?"), lambda m: f"ج {m[1]}"),
    (re.compile(rf"p\.\s*{N}"), lambda m: f"ص {m[1]}"),
    (re.compile(rf"(?:number:|nos?\.)\s*{N}\s*-\s*{N}"), lambda m: f"ح {m[1]} - {m[2]}"),
    (re.compile(rf"(?:number:|nos?\.)\s*{LIST}"), lambda m: "ح " + "، ".join(x for x in m.groups() if x)),
    (re.compile(rf"Sermon\s+{N}"), lambda m: f"الخطبة {m[1]}"),
    (re.compile(rf"Letter\s+{N}"), lambda m: f"الكتاب {m[1]}"),
    (re.compile(rf"Sayings?\s+{LIST}"), lambda m: "الحكمة " + "، ".join(x for x in m.groups() if x)),
    (re.compile(rf"supplication\s+{N}", re.IGNORECASE), lambda m: f"الدعاء {m[1]}"),
    (re.compile(rf"{N}"), lambda m: m[1]),
]
LABEL = re.compile(r"(?<=\d)\.\s+(?=p\.|number:|nos?\.)")


def citation_ar(citation: str, books_ar: dict) -> str | None:
    """The Arabic of a Mizan citation, or None when any part of it is not understood."""
    text = (citation or "").strip()
    book = max((b for b in books_ar if text.startswith(b)), key=len, default=None)
    if book is None or not books_ar[book]:
        return None
    rest = text[len(book):].strip().lstrip(",").strip()
    if not rest:
        return books_ar[book]
    parts = []
    for token in (t.strip() for t in LABEL.sub(", ", rest).split(",")):
        # "number:9 and 10" stays one token: "and" joins numbers, never parts.
        for pattern, render in RULES:
            m = pattern.fullmatch(token)
            if m:
                parts.append(render(m))
                break
        else:
            return None
    return books_ar[book] + "، " + "، ".join(parts)


def partial_update(src: dict, translations: dict, books_ar: dict) -> dict:
    """The fields this narration lacks and the translation pass can supply."""
    update = {}
    chapter_ar = translations.get("chapter", {}).get(src.get("chapter"))
    if chapter_ar and not src.get("chapter_ar"):
        update.update(chapter_ar=chapter_ar, chapter_ar_source=TRANSLATED)
    part_ar = translations.get("part", {}).get(src.get("part"))
    if part_ar and not src.get("part_ar"):
        update["part_ar"] = part_ar
    if src.get("cited_from") and not src.get("cited_from_ar"):
        cited = citation_ar(src["cited_from"], books_ar)
        if cited:
            update["cited_from_ar"] = cited
    return update


def search(es_url: str, path: str, body: dict) -> dict:
    request = urllib.request.Request(f"{es_url}{path}", data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request) as response:
        return json.load(response)


def scroll(es_url: str, index: str):
    fields = ["chapter", "chapter_ar", "part", "part_ar", "cited_from", "cited_from_ar"]
    page = search(es_url, f"/{index}/_search?scroll=5m", {
        "size": 1000, "_source": fields, "query": {"terms": {"book": BOOKS}}, "sort": ["_doc"]})
    while page["hits"]["hits"]:
        yield from page["hits"]["hits"]
        page = search(es_url, "/_search/scroll", {"scroll": "5m", "scroll_id": page["_scroll_id"]})


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--es-url", default="http://localhost:9200")
    parser.add_argument("--index", default="rewayaat_hadith")
    parser.add_argument("--translations", default=str(HERE / "v1_restore_translations.json"))
    parser.add_argument("--cited-books", default=str(HERE / "v1_restore_cited_books_ar.json"))
    parser.add_argument("--unparsed-out", help="write the citations left without Arabic here")
    parser.add_argument("--live", action="store_true")
    args = parser.parse_args()

    translations = json.loads(Path(args.translations).read_text(encoding="utf-8"))
    books_ar = json.loads(Path(args.cited_books).read_text(encoding="utf-8"))
    counts, unparsed, lines = Counter(), [], []
    for hit in scroll(args.es_url, args.index):
        src = hit["_source"]
        update = partial_update(src, translations, books_ar)
        if src.get("cited_from") and not src.get("cited_from_ar") and "cited_from_ar" not in update:
            unparsed.append(f"{hit['_id']}\t{src['cited_from']}")
        counts.update(update.keys())
        if update:
            lines += [json.dumps({"update": {"_index": args.index, "_id": hit["_id"]}}),
                      json.dumps({"doc": update}, ensure_ascii=False)]
    failed = 0
    if args.live:
        for start in range(0, len(lines), 2 * BULK):
            response = _request_ndjson(f"{args.es_url}/_bulk", lines[start:start + 2 * BULK],
                                       headers={}, context=None)
            failed += sum(1 for item in response.get("items", []) if item.get("update", {}).get("error"))
    if args.unparsed_out:
        Path(args.unparsed_out).write_text("\n".join(unparsed) + "\n", encoding="utf-8")
    print(json.dumps({"live": args.live, "updates": len(lines) // 2, "fields": dict(counts),
                      "citations_unparsed": len(unparsed), "failed": failed}))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
