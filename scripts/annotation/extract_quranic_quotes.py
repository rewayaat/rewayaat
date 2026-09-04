#!/usr/bin/env python3
"""Extract Quranic verse quotes from hadith and match them to actual Quranic verses.

For each hadith containing "عز وجل يقول" (or similar), this script:
1. Extracts the Quranic text being quoted
2. Normalizes Arabic (strip diacritics, normalize alef variants)
3. Matches to actual Quranic verses via substring matching
4. Fetches Quranic insights + tafsir snippets for matched verses
5. Outputs per-hadith JSON files ready for highlighting

Usage:
    python3 scripts/annotation/extract_quranic_quotes.py                # Full run
    python3 scripts/annotation/extract_quranic_quotes.py --limit 50     # Process first 50 results
    python3 scripts/annotation/extract_quranic_quotes.py --pages 1-5    # Process pages 1-5
    python3 scripts/extract_quanic_quotes.py --page-size 50  # Results per page (default 50)
"""

import json
import os
import re
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


API_BASE = os.environ.get("REWAYAAT_API", "http://localhost:8002")
ES_BASE = os.environ.get("ELASTICSEARCH_URL", "http://localhost:9200")
OUTPUT_DIR = Path(os.environ.get("QSM_OUTPUT_DIR", "tmp/quranic-snippet-matches"))
QURAN_INDEX = "rewayaat_quran"
LIGHT_INDEX = "rewayaat_quranic_light_filtered"
PAGE_SIZE = int(os.environ.get("QSM_PAGE_SIZE", "50"))
REQUEST_TIMEOUT = 30

# --- CLI args ---
LIMIT = 0
PAGES = None
for i, arg in enumerate(sys.argv[1:], 1):
    if arg == "--limit" and i < len(sys.argv) - 1:
        LIMIT = int(sys.argv[i + 1])
    elif arg == "--pages" and i < len(sys.argv) - 1:
        parts = sys.argv[i + 1].split("-")
        PAGES = range(int(parts[0]), int(parts[1]) + 1)
    elif arg == "--page-size" and i < len(sys.argv) - 1:
        PAGE_SIZE = int(sys.argv[i + 1])


def http_json(url, payload=None, method="GET", timeout=REQUEST_TIMEOUT):
    body = None
    headers = {}
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
        method = "POST"
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read().decode("utf-8")
        return json.loads(raw) if raw else {}


# --- Arabic normalization ---
ALEF_VARIANTS = "إأآٱا"
YA_VARIANTS = "يىي"

def normalize_arabic(text):
    """Strip diacritics and normalize alef/ya variants for matching."""
    # Remove HTML tags
    text = re.sub(r'<[^>]+>', '', text)
    # Remove diacritics (combining marks in Arabic range)
    text = unicodedata.normalize('NFD', text)
    text = ''.join(c for c in text if unicodedata.category(c) != 'Mn')
    text = unicodedata.normalize('NFC', text)
    # Normalize alef variants
    for v in ALEF_VARIANTS[1:]:
        text = text.replace(v, 'ا')
    # Normalize ya variants
    text = text.replace('ى', 'ي')
    # Remove tatweel
    text = text.replace('ـ', '')
    # Normalize whitespace
    text = re.sub(r'\s+', ' ', text).strip()
    return text


# --- Build Quran lookup ---
def build_quran_lookup():
    """Load all Quran verses into memory with normalized text for matching."""
    print("Loading Quran verses...")
    req = urllib.request.Request(
        f"{ES_BASE}/{QURAN_INDEX}/_search?scroll=5m",
        data=json.dumps({
            "size": 500,
            "query": {"match_all": {}},
            "_source": ["surah_number", "ayah_number", "text_arabic", "text_english",
                        "surah_name_english"]
        }).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST"
    )

    verses = []
    with urllib.request.urlopen(req, timeout=60) as resp:
        page = json.loads(resp.read().decode("utf-8"))
        scroll_id = page.get("_scroll_id", "")
        total = page["hits"]["total"]["value"]

        while True:
            hits = page.get("hits", {}).get("hits", [])
            if not hits:
                break

            for hit in hits:
                s = hit["_source"]
                norm = normalize_arabic(s["text_arabic"])
                verses.append({
                    "verse_key": f"{s['surah_number']}:{s['ayah_number']}",
                    "surah_number": s["surah_number"],
                    "ayah_number": s["ayah_number"],
                    "surah_name": s.get("surah_name_english", ""),
                    "text_arabic": s["text_arabic"],
                    "text_english": s.get("text_english", ""),
                    "normalized": norm,
                })

            # Scroll
            req2 = urllib.request.Request(
                f"{ES_BASE}/_search/scroll",
                data=json.dumps({"scroll": "5m", "scroll_id": scroll_id}).encode("utf-8"),
                headers={"Content-Type": "application/json"},
                method="POST"
            )
            with urllib.request.urlopen(req2, timeout=60) as resp2:
                page = json.loads(resp2.read().decode("utf-8"))
                scroll_id = page.get("_scroll_id", scroll_id)

            if len(verses) >= total:
                break

        # Clear scroll
        try:
            http_json(f"{ES_BASE}/_search/scroll", method="DELETE")
        except Exception:
            pass

    print(f"  Loaded {len(verses)} Quranic verses")
    return verses


def match_verse(quote_text, verses):
    """Match a Quranic quote to actual verses using normalized substring matching.
    Returns list of (verse, match_length) sorted by match quality."""
    norm_quote = normalize_arabic(quote_text)
    if len(norm_quote) < 15:  # Too short to match reliably
        return []

    matches = []
    for v in verses:
        norm_v = v["normalized"]
        # Check if the quote is a substring of the verse, or vice versa
        if norm_quote in norm_v:
            matches.append((v, len(norm_quote)))
        elif norm_v in norm_quote:
            matches.append((v, len(norm_v)))

    # Also try word-level overlap for partial quotes
    if not matches:
        quote_words = set(norm_quote.split())
        for v in verses:
            verse_words = set(v["normalized"].split())
            overlap = quote_words & verse_words
            if len(overlap) >= max(3, len(quote_words) * 0.6):
                # Verify order: at least 3 words appear in sequence
                qw = norm_quote.split()
                vw = v["normalized"].split()
                seq_match = 0
                for i in range(len(vw) - 2):
                    for j in range(len(qw) - 2):
                        if (vw[i:i+3] == qw[j:j+3]):
                            seq_match = 1
                            break
                    if seq_match:
                        break
                if seq_match:
                    matches.append((v, len(overlap)))

    # Deduplicate by verse_key and sort by match length
    seen = set()
    unique = []
    for v, score in sorted(matches, key=lambda x: -x[1]):
        if v["verse_key"] not in seen:
            seen.add(v["verse_key"])
            unique.append((v, score))

    return unique[:3]  # Top 3 matches


# --- Extract Quranic quotes from hadith text ---
def extract_quranic_quotes(arabic_text):
    """Extract Quranic text quotes from hadith Arabic text using multiple patterns.

    Returns list of extracted quote strings.
    """
    # Clean HTML
    text = re.sub(r'<[^>]+>', '', arabic_text)
    quotes = []

    # Pattern 1: ﴿...﴾ brackets (formal Quranic quotation marks)
    bracket_quotes = re.findall(r'﴿([^﴾]+)﴾', text)
    quotes.extend(bracket_quotes)

    # Pattern 2: Regular quotes after "عز وجل:" or "يقول:" etc.
    # e.g., عز وجل يقول: "quoted text"
    quote_patterns = [
        r'(?:عز وجل|جل جلاله|سبحانه وتعالى)(?:\s+يقول)?\s*[©:]\s*["""]([^"""]+)["""]',
        r'في قول(?:ه| الله)\s+(?:عز وجل|جل جلاله)?\s*[©:]\s*["""]?([^"""\n]+?)["""]?(?:\s|$)',
        r'يقول الله(?:\s+عز وجل)?\s*[©:]\s*["""]?([^"""\n]{15,}?)["""]?(?:\s|$)',
        r'(?:قال|يقول)\s+(?:الله\s+)?(?:عز وجل|جل جلاله)\s*[©:]\s*["""]?([^"""\n]{15,}?)["""]?(?:\s|$)',
    ]
    for p in quote_patterns:
        found = re.findall(p, text)
        quotes.extend(found)

    # Pattern 3: Text after "عز وجل:" followed by what looks like Quranic Arabic
    # This catches patterns like: في قوله عز وجل: TEXT
    after_patterns = [
        r'قوله عز وجل\s*[©:]\s*([^\n,،.]+?)(?:\s+(?:قال|يعني|أي|هو|فقال|فهو|يعني ذلك|فسره))',
        r'قول الله عز وجل\s*[©:]\s*([^\n,،.]+?)(?:\s+(?:قال|يعني|أي|هو|فقال|فهو))',
    ]
    for p in after_patterns:
        found = re.findall(p, text)
        quotes.extend(found)

    # Deduplicate and filter short ones
    seen = set()
    filtered = []
    for q in quotes:
        q = q.strip()
        if len(q) >= 15 and q not in seen:
            seen.add(q)
            filtered.append(q)

    return filtered


# --- Fetch Quranic insights for a hadith ---
def get_quranic_insights(hadith_id):
    """Fetch existing Quranic insights for a hadith."""
    try:
        data = http_json(f"{API_BASE}/v1/narrations/quranic_insights?id={hadith_id}")
        if data.get("ok"):
            return data
    except Exception:
        pass
    return None


# --- Get tafsir snippets for a specific verse from the light index ---
def get_verse_snippets_from_light(hadith_id, verse_key):
    """Get tafsir snippets for a specific verse from the hadith's Quranic light data."""
    try:
        data = http_json(f"{ES_BASE}/{LIGHT_INDEX}/_doc/{hadith_id}")
        if data.get("found"):
            for c in data["_source"].get("candidates", []):
                if c.get("verse_key") == verse_key:
                    return c.get("tafsir_snippets", [])
    except Exception:
        pass
    return []


# --- Get tafsir snippets directly for a verse from rewayaat_tafsir ---
def get_tafsir_for_verse(verse_key, limit=5):
    """Get tafsir snippets for a specific verse from the tafsir index."""
    try:
        data = http_json(f"{ES_BASE}/rewayaat_tafsir/_search", payload={
            "query": {
                "bool": {
                    "should": [
                        {"term": {"verse_key": verse_key}},
                        {"term": {"verse_keys": verse_key}}
                    ],
                    "minimum_should_match": 1
                }
            },
            "size": limit,
            "_source": ["tafsir_slug", "tafsir_name", "commentary_text",
                        "commentary_text_english", "source_url", "section_title",
                        "verse_key"]
        })
        snippets = []
        for hit in data.get("hits", {}).get("hits", []):
            s = hit["_source"]
            ct = s.get("commentary_text_english", "") or s.get("commentary_text", "")
            if ct:
                snippets.append({
                    "tafsir_slug": s.get("tafsir_slug", ""),
                    "tafsir_name": s.get("tafsir_name", ""),
                    "commentary_text": ct[:2000],  # Cap length
                    "source_url": s.get("source_url", ""),
                    "section_title": s.get("section_title", ""),
                })
        return snippets
    except Exception:
        return []


# --- Main pipeline ---
def fetch_search_results():
    """Fetch all hadith matching 'عز وجل يقول' query."""
    query = 'عز وجل يقول'
    all_results = []
    page = 1

    while True:
        if PAGES is not None and page not in PAGES:
            page += 1
            if page > max(PAGES):
                break
            continue

        url = (f"{API_BASE}/v1/narrations?"
               f"q={urllib.parse.quote(query)}"
               f"&match_mode=flexible"
               f"&per_page={PAGE_SIZE}"
               f"&page={page}")

        try:
            data = http_json(url)
        except Exception as e:
            print(f"  Error fetching page {page}: {e}")
            break

        collection = data.get("collection", [])
        total = data.get("totalResultSetSize", 0)

        if not collection:
            break

        all_results.extend(collection)
        print(f"  Page {page}: {len(collection)} results (total fetched: {len(all_results)}/{total})")

        if LIMIT and len(all_results) >= LIMIT:
            all_results = all_results[:LIMIT]
            break

        if len(all_results) >= total:
            break

        page += 1
        time.sleep(0.2)  # Rate limit

    return all_results


def process_hadith(hadith, verses):
    """Process a single hadith: extract quotes, match verses, get snippets."""
    hid = hadith["_id"]
    arabic = hadith.get("arabic", "")
    english = hadith.get("english", "")

    # Strip HTML for display
    ar_clean = re.sub(r'<[^>]+>', '', arabic)
    en_clean = re.sub(r'<[^>]+>', '', english)

    # Extract Quranic quotes
    quotes = extract_quranic_quotes(arabic)
    if not quotes:
        return None

    # Match each quote to Quranic verses
    matched_verses = []
    for quote in quotes:
        matches = match_verse(quote, verses)
        if matches:
            best = matches[0][0]
            matched_verses.append({
                "extracted_quote": quote,
                "matched_verse_key": best["verse_key"],
                "matched_surah": best["surah_name"],
                "matched_text_arabic": best["text_arabic"],
                "matched_text_english": best["text_english"],
                "match_confidence": "high" if matches[0][1] > 30 else "medium",
            })

    if not matched_verses:
        return None

    # Get existing Quranic insights for this hadith
    insights = get_quranic_insights(hid)
    existing_verse_keys = set()
    if insights:
        for c in insights.get("candidates", []):
            existing_verse_keys.add(c["verse_key"])

    # For each matched verse, get tafsir snippets
    for mv in matched_verses:
        vk = mv["matched_verse_key"]

        # Check if this verse is already in insights
        mv["already_in_insights"] = vk in existing_verse_keys

        # Get snippets from light index first, then tafsir index
        snippets = get_verse_snippets_from_light(hid, vk)
        if not snippets:
            snippets = get_tafsir_for_verse(vk, limit=5)
        mv["tafsir_snippets"] = snippets

    return {
        "hadith_id": hid,
        "book": hadith.get("book", ""),
        "number": hadith.get("number", ""),
        "arabic_clean": ar_clean[:500],
        "english_clean": en_clean[:500],
        "matched_verses": matched_verses,
        "has_existing_insights": insights is not None,
        "existing_insight_count": insights.get("count", 0) if insights else 0,
    }


def main():
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    (OUTPUT_DIR / "highlighted").mkdir(exist_ok=True)

    print("=== Quranic Quote Extraction Pipeline ===")
    print(f"Output: {OUTPUT_DIR}")

    # Step 1: Load Quran verses for matching
    verses = build_quran_lookup()

    # Step 2: Fetch search results
    print("\nFetching search results...")
    results = fetch_search_results()
    print(f"Total results to process: {len(results)}")

    # Step 3: Process each hadith
    print("\nProcessing hadith...")
    matched_count = 0
    no_match_count = 0
    stats = {
        "total_processed": 0,
        "with_quranic_quotes": 0,
        "verses_matched": 0,
        "verses_already_in_insights": 0,
        "verses_new": 0,
    }

    # Save batch manifest for sub-agent processing
    manifest = []

    for i, hadith in enumerate(results):
        hid = hadith["_id"]
        stats["total_processed"] += 1

        result = process_hadith(hadith, verses)

        if result is None:
            no_match_count += 1
            if stats["total_processed"] % 100 == 0:
                print(f"  Progress: {stats['total_processed']}/{len(results)} "
                      f"(matched={matched_count}, no_match={no_match_count})")
            continue

        matched_count += 1
        stats["with_quranic_quotes"] += 1

        for mv in result["matched_verses"]:
            stats["verses_matched"] += 1
            if mv["already_in_insights"]:
                stats["verses_already_in_insights"] += 1
            else:
                stats["verses_new"] += 1

        # Save per-hadith JSON
        output_file = OUTPUT_DIR / f"{hid.replace(':', '_')}.json"
        with open(output_file, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=2)

        manifest.append({
            "hadith_id": hid,
            "output_file": str(output_file),
            "verse_count": len(result["matched_verses"]),
            "snippet_count": sum(len(mv.get("tafsir_snippets", [])) for mv in result["matched_verses"]),
        })

        if matched_count % 50 == 0 or stats["total_processed"] % 200 == 0:
            print(f"  Progress: {stats['total_processed']}/{len(results)} "
                  f"(matched={matched_count}, no_match={no_match_count})")

    # Save manifest
    with open(OUTPUT_DIR / "manifest.json", "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)

    # Save stats
    with open(OUTPUT_DIR / "stats.json", "w", encoding="utf-8") as f:
        json.dump(stats, f, ensure_ascii=False, indent=2)

    print(f"\n=== Done ===")
    print(f"  Processed: {stats['total_processed']}")
    print(f"  With Quranic quotes matched: {stats['with_quranic_quotes']}")
    print(f"  Total verse matches: {stats['verses_matched']}")
    print(f"  Already in insights: {stats['verses_already_in_insights']}")
    print(f"  New (gap to fill): {stats['verses_new']}")
    print(f"  Manifest: {OUTPUT_DIR / 'manifest.json'}")


if __name__ == "__main__":
    main()
