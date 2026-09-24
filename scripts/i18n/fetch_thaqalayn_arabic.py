#!/usr/bin/env python3
"""Read the Arabic chapter titles off thaqalayn's own Arabic site.

This is the source the corpus was imported from, so it needs no alignment: the same
edition, the same chapter numbering, the same rows. Where the OpenITI recovery had to
join a printed book to the index by locating narrations in it, here the chapter the site
shows for a hadith simply is that hadith's chapter.

The Arabic title is not in the API - /api/v2 returns the English one whatever lang is
asked for - but the Arabic site renders it into the page's BreadcrumbList, where position
two is the book and position three is the chapter. That is structured data rather than
scraped markup, so it does not depend on the page's classes.

One request per chapter, not per hadith, with a pause between them. Results are cached to
disk and the cache is read on restart, so a run can be stopped and resumed.

    python3 scripts/i18n/fetch_thaqalayn_arabic.py --book Kamil-al-Ziyarat-Qummi
    python3 scripts/i18n/fetch_thaqalayn_arabic.py --all
"""

import argparse
import json
import pathlib
import re
import sys
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
CACHE = ROOT / "tmp" / "thaqalayn"
API = "https://www.thaqalayn-api.net/api/v2"
SITE = "https://thaqalayn.com"
PAUSE = 1.0          # be a guest on someone else's server
UA = "Mozilla/5.0 (compatible; hadith-database-i18n/1.0)"


def get(url, tries=3):
    for attempt in range(tries):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(request, timeout=60) as resp:
                return resp.read().decode("utf-8", "replace")
        except (urllib.error.URLError, TimeoutError) as e:
            if attempt == tries - 1:
                return None
            time.sleep(2 * (attempt + 1))
    return None


def books():
    payload = json.loads(get(f"{API}/allbooks") or "[]")
    return payload.get("data") if isinstance(payload, dict) and "data" in payload else payload


def narrations(book_id):
    payload = json.loads(get(f"{API}/{urllib.parse.quote(book_id)}") or "[]")
    return payload.get("data") if isinstance(payload, dict) and "data" in payload else payload


def breadcrumb_chapter(html):
    """Position 3 of the BreadcrumbList is the chapter; position 2 is the book."""
    for blob in re.findall(r'"@type"\s*:\s*"BreadcrumbList".*?\]', html, re.S):
        items = re.findall(r'"position"\s*:\s*(\d+)\s*,\s*"name"\s*:\s*"((?:[^"\\]|\\.)*)"', blob)
        found = {int(pos): name for pos, name in items}
        if 3 in found:
            return json.loads(f'"{found[3]}"'), json.loads(f'"{found.get(2, "")}"')
    return None, None


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--book", help="a bookId from /api/v2/allbooks")
    parser.add_argument("--all", action="store_true", help="every book")
    parser.add_argument("--limit", type=int, default=0, help="stop after this many chapters")
    args = parser.parse_args()

    CACHE.mkdir(parents=True, exist_ok=True)
    targets = [b["bookId"] for b in books()] if args.all else [args.book]
    if not targets or targets == [None]:
        print("  give --book or --all")
        return 1

    for book_id in targets:
        out = CACHE / f"{book_id}.json"
        done = json.loads(out.read_text(encoding="utf-8")) if out.exists() else {}
        rows = narrations(book_id) or []
        # one hadith per chapter is enough; the page states the chapter it belongs to
        first_of_chapter = {}
        for row in rows:
            english = (row.get("chapter") or "").strip()
            if english and english not in first_of_chapter and row.get("URL"):
                first_of_chapter[english] = row["URL"]

        todo = [c for c in first_of_chapter if c not in done]
        print(f"  {book_id}: {len(first_of_chapter)} chapters, {len(todo)} to fetch")
        for index, english in enumerate(todo, 1):
            if args.limit and index > args.limit:
                break
            url = first_of_chapter[english].replace("thaqalayn.net", "thaqalayn.com")
            url = url.replace(f"{SITE}/hadith/", f"{SITE}/ar/hadith/")
            html = get(url)
            if not html:
                print(f"    [{index}] fetch failed: {url}")
                continue
            arabic, book_ar = breadcrumb_chapter(html)
            if arabic:
                done[english] = {"chapter_ar": arabic, "book_ar": book_ar, "url": url}
            if index % 20 == 0 or index == len(todo):
                out.write_text(json.dumps(done, ensure_ascii=False, indent=1), encoding="utf-8")
                print(f"    [{index}/{len(todo)}] {len(done)} cached")
            time.sleep(PAUSE)
        out.write_text(json.dumps(done, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"  {book_id}: {len(done)} chapters with Arabic -> {out}")
    return 0


if __name__ == "__main__":
    import urllib.parse
    sys.exit(main())
