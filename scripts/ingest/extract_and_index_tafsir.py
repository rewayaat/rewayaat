#!/usr/bin/env python3
"""
Index tafsir documents from cached HTML files into Elasticsearch.

This script is a workaround for the Rest5Client compatibility issue between
the Elasticsearch Java client v9.x and ES server 8.x. It reads the cached
HTML files created by TafsirExtractionTool and indexes them directly via HTTP.

It uses the same parsing logic as the Java extractors but in Python,
and indexes via simple HTTP requests to Elasticsearch.

Usage:
    # First run the Java tool in dry-run mode to cache HTML:
    # TAFSIR_DRY_RUN=true TAFSIR_EXTRACTOR=all mvn exec:java -Dexec.mainClass=...
    #
    # Then run this script:
    python3 scripts/extract_and_index_tafsir.py

    # Or extract + index a specific tafsir:
    python3 scripts/extract_and_index_tafsir.py --slug enlightening-commentary
"""

import hashlib
import json
import os
import re
import sys
import time
import urllib.request
import urllib.error
from pathlib import Path

ES_BASE_URL = os.environ.get("ELASTICSEARCH_URL", "http://localhost:9200").rstrip("/")
TAFSIR_INDEX = os.environ.get("TAFSIR_INDEX", "rewayaat_tafsir")
SOURCE_DIR = os.environ.get("TAFSIR_SOURCE_DIR", "/tmp/tafsir-sources")
BULK_BATCH_SIZE = int(os.environ.get("BULK_BATCH_SIZE", "100"))
DRY_RUN = os.environ.get("DRY_RUN", "false").lower() == "true"
SLUG_FILTER = os.environ.get("SLUG_FILTER", "")  # comma-separated slugs, empty = all


def es_request(method, path, payload=None):
    url = f"{ES_BASE_URL}{path}"
    body = json.dumps(payload).encode("utf-8") if payload else None
    headers = {"Content-Type": "application/json"} if body else {}
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=30) as resp:
        raw = resp.read().decode("utf-8")
        return resp.getcode(), json.loads(raw) if raw else {}


def es_ndjson(lines):
    url = f"{ES_BASE_URL}/_bulk"
    body = ("\n".join(lines) + "\n").encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        headers={"Content-Type": "application/x-ndjson"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        raw = resp.read().decode("utf-8")
        return json.loads(raw) if raw else {}


def ensure_index():
    """Create the tafsir index if it doesn't exist."""
    try:
        es_request("HEAD", f"/{TAFSIR_INDEX}")
        print(f"Index {TAFSIR_INDEX} exists")
    except urllib.error.HTTPError as e:
        if e.code != 404:
            raise
        mapping = {
            "mappings": {
                "properties": {
                    "tafsir_slug": {"type": "keyword"},
                    "tafsir_name": {"type": "text"},
                    "surah_number": {"type": "integer"},
                    "ayah_start": {"type": "integer"},
                    "ayah_end": {"type": "integer"},
                    "verse_key": {"type": "keyword"},
                    "verse_keys": {"type": "keyword"},
                    "verse_text_english": {"type": "text"},
                    "commentary_text": {"type": "text"},
                    "commentary_text_arabic": {"type": "text"},
                    "commentary_text_english": {"type": "text"},
                    "section_title": {"type": "text"},
                    "commentary_word_count": {"type": "integer"},
                    "volume": {"type": "keyword"},
                    "source_url": {"type": "keyword"},
                    "language": {"type": "keyword"},
                }
            }
        }
        es_request("PUT", f"/{TAFSIR_INDEX}", mapping)
        print(f"Created index {TAFSIR_INDEX}")


def compute_word_count(text):
    if not text:
        return 0
    return len(text.split())


def is_substantive(text):
    if not text or len(text.strip()) < 20:
        return False
    words = [w for w in text.split() if any(c.isalpha() for c in w)]
    return len(words) >= 6


def normalize_commentary(text):
    if not text:
        return ""
    return re.sub(r'\s+', ' ', text).strip()


def parse_cached_html_to_docs(slug, html_dir):
    """Parse cached HTML files for a given tafsir slug into documents."""
    docs = []
    slug_dir = Path(html_dir) / slug
    if not slug_dir.exists():
        print(f"  No cached files for {slug}")
        return docs

    html_files = sorted(slug_dir.glob("*.html"))
    if not html_files:
        print(f"  No HTML files in {slug_dir}")
        return docs

    print(f"  Processing {len(html_files)} cached HTML files for {slug}")

    # Try to use BeautifulSoup for better parsing, fall back to regex
    try:
        from bs4 import BeautifulSoup
        use_bs4 = True
    except ImportError:
        use_bs4 = False
        print("  Note: install beautifulsoup4 for better HTML parsing")

    for html_file in html_files:
        try:
            text = html_file.read_text(encoding="utf-8", errors="replace")
            if use_bs4:
                soup = BeautifulSoup(text, "html.parser")
                doc = extract_doc_from_html(slug, soup, str(html_file), use_bs4=True)
            else:
                doc = extract_doc_from_html(slug, text, str(html_file), use_bs4=False)
            if doc:
                docs.append(doc)
        except Exception as e:
            print(f"    Error parsing {html_file.name}: {e}")

    return docs


def extract_doc_from_html(slug, content, source_path, use_bs4=False):
    """Extract a tafsir document from HTML content."""
    # Try to find verse reference and commentary text
    if use_bs4:
        from bs4 import BeautifulSoup
        soup = content if hasattr(content, 'find') else BeautifulSoup(content, "html.parser")

        # Try headings
        for heading in soup.find_all(["h1", "h2", "h3"]):
            heading_text = heading.get_text(strip=True)
            ref = parse_verse_reference(heading_text)
            if ref:
                # Get commentary text after heading
                commentary_parts = []
                for sibling in heading.find_next_siblings():
                    if sibling.name in ["h1", "h2", "h3"]:
                        break
                    text = sibling.get_text(strip=True)
                    if text and len(text) > 20:
                        commentary_parts.append(text)

                if commentary_parts:
                    commentary = normalize_commentary(" ".join(commentary_parts))
                    if is_substantive(commentary):
                        return build_doc(slug, ref, commentary, heading_text, source_path)

        # Fallback: get main content
        main_content = soup.find("article") or soup.find("main") or soup.find(class_="content")
        if main_content:
            text = main_content.get_text(separator=" ", strip=True)
            if len(text) > 100:
                # Try to extract verse reference from title
                title = soup.find("title")
                if title:
                    ref = parse_verse_reference(title.get_text())
                    if ref:
                        return build_doc(slug, ref, normalize_commentary(text), title.get_text(strip=True), source_path)
    else:
        # Regex-based fallback
        # Try to find verse reference in headings
        heading_patterns = [
            r'<h[123][^>]*>(.*?)</h[123]>',
        ]
        for pattern in heading_patterns:
            matches = re.findall(pattern, content, re.DOTALL | re.IGNORECASE)
            for match_text in matches:
                clean = re.sub(r'<[^>]+>', '', match_text).strip()
                ref = parse_verse_reference(clean)
                if ref:
                    # Get text content
                    text = re.sub(r'<[^>]+>', ' ', content)
                    text = re.sub(r'\s+', ' ', text).strip()
                    if len(text) > 100:
                        return build_doc(slug, ref, normalize_commentary(text[:10000]), clean, source_path)

    return None


# Verse reference parsing (simplified version of VerseReferenceParser)
SURAH_NAMES = {
    "al-fatihah": 1, "baqarah": 2, "al-i-imran": 3, "nisaa": 4, "nisa": 4,
    "maidah": 5, "an-am": 6, "a'raf": 7, "araf": 7, "anfal": 8, "tawbah": 9,
    "yunus": 10, "hud": 11, "yusuf": 12, "rad": 13, "ibrahim": 14, "hijr": 15,
    "nahl": 16, "isra": 17, "kahf": 18, "maryam": 19, "ta-ha": 20, "anbiya": 21,
    "hajj": 22, "mu'minun": 23, "nur": 24, "furqan": 25, "shu'ara": 26,
    "naml": 27, "qasas": 28, "ankabut": 29, "rum": 30, "luqman": 31, "sajdah": 32,
    "ahzab": 33, "saba": 34, "fatir": 35, "ya-sin": 36, "saffat": 37, "sad": 38,
    "zumar": 39, "ghafir": 40, "fussilat": 41, "shura": 42, "zukhruf": 43,
    "dukhan": 44, "jathiyah": 45, "ahqaf": 46, "muhammad": 47, "fath": 48,
    "hujurat": 49, "qaf": 50, "dhariyat": 51, "tur": 52, "najm": 53, "qamar": 54,
    "rahman": 55, "waqi'ah": 56, "hadid": 57, "mujadilah": 58, "hashr": 59,
    "mumtahinah": 60, "saff": 61, "jumu'ah": 62, "munafiqun": 63, "taghabun": 64,
    "talaq": 65, "tahrim": 66, "mulk": 67, "qalam": 68, "haqqah": 69, "ma'arij": 70,
    "nuh": 71, "jinn": 72, "muzzammil": 73, "muddaththir": 74, "qiyamah": 75,
    "insan": 76, "mursalat": 77, "naba": 78, "nazi'at": 79, "abasa": 80,
    "takwir": 81, "infitar": 82, "mutaffifin": 83, "inshiqaq": 84, "buruj": 85,
    "tariq": 86, "a'la": 87, "ghashiyah": 88, "fajr": 89, "balad": 90,
    "shams": 91, "layl": 92, "duha": 93, "sharh": 94, "tin": 95, "alaq": 96,
    "qadr": 97, "bayyinah": 98, "zalzalah": 99, "adiyat": 100, "qari'ah": 101,
    "takathur": 102, "asr": 103, "humazah": 104, "fil": 105, "quraysh": 106,
    "ma'un": 107, "kawthar": 108, "kafirun": 109, "nasr": 110, "masad": 111,
    "ikhlas": 112, "falaq": 113, "nas": 114,
    "fatihah": 1, "i-imran": 3, "an'am": 6, "ta ha": 20, "ya sin": 36,
}

VERSE_REF_PATTERNS = [
    # "Surah Al-Baqarah, Verses 21-22"
    re.compile(r"sura(?:h)?\s+[\w'-]+\s*,?\s*(?:chapter\s+\d+\s*,?\s*)?verses?\s+(\d+)(?:\s*[-–—]\s*(\d+))?", re.I),
    # "Surah 2:255"
    re.compile(r"(?:sura(?:h)?\s+)?(\d+)\s*:\s*(\d+)(?:\s*-\s*(\d+))?"),
    # "[2:255]"
    re.compile(r"[\[(](\d+):(\d+)[\])]"),
    # "Chapter 2, Verses 21-22"
    re.compile(r"chapter\s+(\d+)\s*,\s*verses?\s+(\d+)(?:\s*-\s*(\d+))?", re.I),
    # "Verses 1-7" (relative - needs surah context)
    re.compile(r"^verses?\s+(\d+)(?:\s*[-–—]\s*(\d+))?", re.I),
]


def parse_verse_reference(text):
    """Try to parse a verse reference from text."""
    if not text:
        return None

    # Try full patterns first
    for pattern in VERSE_REF_PATTERNS[:4]:
        m = pattern.search(text)
        if m:
            groups = m.groups()
            if groups[0] and int(groups[0]) > 114:
                continue
            # Determine surah and ayah
            if pattern == VERSE_REF_PATTERNS[0]:
                # "Surah X, Verses Y-Z" - try to get surah from name
                surah_match = re.search(r"sura(?:h)?\s+([\w'-]+)", text, re.I)
                surah = None
                if surah_match:
                    name = surah_match.group(1).lower().replace("'", "").replace("’", "")
                    surah = SURAH_NAMES.get(name)
                if not surah:
                    continue
                ayah_start = int(groups[0])
                ayah_end = int(groups[1]) if groups[1] else ayah_start
            elif pattern == VERSE_REF_PATTERNS[1]:
                surah = int(groups[0])
                ayah_start = int(groups[1])
                ayah_end = int(groups[2]) if groups[2] else ayah_start
            elif pattern == VERSE_REF_PATTERNS[2]:
                surah = int(groups[0])
                ayah_start = int(groups[1])
                ayah_end = ayah_start
            elif pattern == VERSE_REF_PATTERNS[3]:
                surah = int(groups[0])
                ayah_start = int(groups[1])
                ayah_end = int(groups[2]) if groups[2] else ayah_start
            else:
                continue

            if 1 <= surah <= 114 and ayah_start > 0 and ayah_end >= ayah_start:
                return (surah, ayah_start, ayah_end)

    return None


def build_doc(slug, ref, commentary, section_title, source_path):
    """Build a tafsir document dict."""
    surah, ayah_start, ayah_end = ref
    verse_key = f"{surah}:{ayah_start}"
    verse_keys = [f"{surah}:{i}" for i in range(ayah_start, ayah_end + 1)]

    return {
        "tafsir_slug": slug,
        "tafsir_name": slug.replace("-", " ").title(),
        "surah_number": surah,
        "ayah_start": ayah_start,
        "ayah_end": ayah_end,
        "verse_key": verse_key,
        "verse_keys": verse_keys if len(verse_keys) > 1 else [verse_key],
        "commentary_text": commentary,
        "section_title": section_title[:500] if section_title else "",
        "commentary_word_count": compute_word_count(commentary),
        "source_url": "",
        "language": "en",
        "_id": f"{slug}_{verse_key}",
    }


def index_docs(docs):
    """Bulk index documents to ES."""
    if not docs or DRY_RUN:
        return 0

    lines = []
    for doc in docs:
        doc_id = doc.pop("_id")
        lines.append(json.dumps({"index": {"_index": TAFSIR_INDEX, "_id": doc_id}}, ensure_ascii=False))
        lines.append(json.dumps(doc, ensure_ascii=False))

    body = es_ndjson(lines)
    indexed = 0
    errors = 0
    for item in body.get("items", []):
        if item.get("index", {}).get("error"):
            errors += 1
        else:
            indexed += 1

    if errors:
        print(f"    Indexed {indexed}, errors {errors}")
    return indexed


def main():
    ensure_index()

    source_dir = Path(SOURCE_DIR)
    if not source_dir.exists():
        print(f"Source directory not found: {SOURCE_DIR}")
        print("Run the Java extraction tool first with TAFSIR_DRY_RUN=true to cache HTML files.")
        sys.exit(1)

    # Discover available tafsir slugs
    slugs = sorted([d.name for d in source_dir.iterdir() if d.is_dir()])
    if not slugs:
        print(f"No cached tafsir directories found in {SOURCE_DIR}")
        sys.exit(1)

    # Apply filter
    if SLUG_FILTER:
        filter_slugs = {s.strip() for s in SLUG_FILTER.split(",")}
        slugs = [s for s in slugs if s in filter_slugs]

    print(f"Found {len(slugs)} tafsir sources: {', '.join(slugs)}")
    print(f"Target index: {TAFSIR_INDEX}")
    print(f"Dry run: {DRY_RUN}")
    print()

    total_indexed = 0
    total_docs = 0

    for slug in slugs:
        print(f"Processing {slug}...")
        docs = parse_cached_html_to_docs(slug, SOURCE_DIR)
        print(f"  Extracted {len(docs)} documents")
        total_docs += len(docs)

        if docs and not DRY_RUN:
            # Index in batches
            for i in range(0, len(docs), BULK_BATCH_SIZE):
                batch = docs[i:i + BULK_BATCH_SIZE]
                count = index_docs(batch)
                total_indexed += count
                if (i + BULK_BATCH_SIZE) % 500 == 0:
                    print(f"  Progress: {min(i + BULK_BATCH_SIZE, len(docs))}/{len(docs)}")

        print()

    print(f"Summary: {total_docs} documents extracted, {total_indexed} indexed")
    if DRY_RUN:
        print("DRY RUN - no documents were indexed to Elasticsearch")


if __name__ == "__main__":
    main()
