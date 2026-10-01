#!/usr/bin/env python3
"""Scrape thaqalayn.com book index pages for English/Arabic chapter title pairs.

The book index page (/book/N and /ar/book/N) lists every chapter of the book
grouped under its category, with the chapter number and title inline. Fetching
both languages and pairing on (category number, chapter number) yields an
English -> Arabic chapter title map straight from the source our English titles
came from, so no text alignment is needed.

Output: scripts/data/thaqalayn_chapter_titles.json
"""
import html
import json
import re
import subprocess
import sys
import time
from pathlib import Path

# The Arabic homepage links only a subset of books; ids run 1..45 with a few
# gaps, so probe the whole range and skip what does not resolve.
BOOK_IDS = [b for b in range(1, 46) if b not in (15, 16, 18, 19, 20, 21)]
OUT = Path(__file__).resolve().parents[2] / "scripts/data/thaqalayn_chapter_titles.json"

CAT_RE = re.compile(r'<h2 class="font-title[^"]*">(.*?)</h2>')
LINK_RE = re.compile(
    r'<a class="block rounded[^"]*" href="/(?:ar/)?chapter/(\d+)/(\d+)/(\d+)">(.*?)</a>',
    re.S)


def strip_tags(s):
    s = re.sub(r'<!--.*?-->', '', s, flags=re.S)
    s = re.sub(r'<[^>]+>', '\x00', s)
    parts = [html.unescape(p).strip() for p in s.split('\x00') if p.strip()]
    return [p for p in parts if p]


def fetch(url):
    for attempt in range(3):
        r = subprocess.run(["/usr/bin/curl", "-sL", "-m", "60", url],
                           capture_output=True, text=True)
        if r.returncode == 0 and len(r.stdout) > 5000:
            return r.stdout
        time.sleep(3 * (attempt + 1))
    return None


def parse(page):
    """-> {(cat, ch): {'title':..., 'count':...}}, plus {cat: category_title}"""
    out, cats = {}, {}
    # Walk the page so each chapter link is attributed to the nearest heading
    # above it.
    pos, cur_cat_title = 0, None
    events = []
    for m in CAT_RE.finditer(page):
        events.append((m.start(), 'cat', m))
    for m in LINK_RE.finditer(page):
        events.append((m.start(), 'ch', m))
    events.sort(key=lambda e: e[0])
    for _, kind, m in events:
        if kind == 'cat':
            cur_cat_title = ' '.join(strip_tags(m.group(1)))
        else:
            cat, ch = int(m.group(2)), int(m.group(3))
            parts = strip_tags(m.group(4))
            # parts: ["Chapter 1", "-", "<title>", "9 hadiths"]
            if len(parts) < 3:
                continue
            title, count = parts[-2], parts[-1]
            out[(cat, ch)] = {'title': title, 'count': count}
            if cur_cat_title and cat not in cats:
                cats[cat] = cur_cat_title
    return out, cats


def main():
    result = {'chapters': [], 'categories': []}
    for bid in BOOK_IDS:
        en_page = fetch(f"https://thaqalayn.com/book/{bid}")
        ar_page = fetch(f"https://thaqalayn.com/ar/book/{bid}")
        if not en_page or not ar_page:
            print(f"book {bid}: FETCH FAILED", file=sys.stderr)
            continue
        en, en_cats = parse(en_page)
        ar, ar_cats = parse(ar_page)
        shared = sorted(set(en) & set(ar))
        for cat, ch in shared:
            result['chapters'].append({
                'book': bid, 'category': cat, 'chapter': ch,
                'en': en[(cat, ch)]['title'], 'ar': ar[(cat, ch)]['title'],
                'count_en': en[(cat, ch)]['count'],
            })
        for cat in sorted(set(en_cats) & set(ar_cats)):
            result['categories'].append({
                'book': bid, 'category': cat,
                'en': en_cats[cat], 'ar': ar_cats[cat]})
        print(f"book {bid}: {len(shared)} chapters "
              f"(en {len(en)}, ar {len(ar)}), {len(set(en_cats) & set(ar_cats))} categories")
        time.sleep(1)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    tmp = OUT.with_suffix('.tmp')
    tmp.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    tmp.replace(OUT)
    print(f"\nwrote {len(result['chapters'])} chapter pairs, "
          f"{len(result['categories'])} category pairs -> {OUT}")


if __name__ == '__main__':
    main()
