#!/usr/bin/env python3
"""
Phase 1B-G: Parse external Rijal books into narrator profiles.

Downloads actual Arabic text from usul.ai (or eshia.ir as fallback),
then uses Claude to parse each page/entry into structured narrator profiles.

Usage:
    python3 scripts/parse_external_rijal.py --book khoei --test          # download 3 pages, parse entries
    python3 scripts/parse_external_rijal.py --book khoei --pages 100-120 # download pages 100-120
    python3 scripts/parse_external_rijal.py --book khoei --all           # full book
    python3 scripts/parse_external_rijal.py --book khoei --resume        # continue from checkpoint

Supported books:
    khoei       Mu'jam Rijal al-Hadith (Ayatollah Khoei) — 10,924 pages
    mamaqani    Tanqih al-Maqal (Mamaqani) — eshia.ir only, 34 vols
    kashshi     Rijal al-Kashshi — usul.ai, 94+ pages
    najashi     Rijal al-Najashi — usul.ai, 461 pages
    tusi        Rijal al-Tusi — usul.ai, 417 pages
    fihrist     Fihrist al-Tusi — usul.ai, 253 pages
    ardabili    Jami' al-Ruwat (Ardabili) — usul.ai, 1,210 pages
"""

import json
import os
import sys
import re
import time
import unicodedata
import argparse
import urllib.request
import urllib.error

# --- Book definitions ---

BOOKS = {
    "khoei": {
        "name": "Mu'jam Rijal al-Hadith",
        "name_ar": "معجم رجال الحديث",
        "author": "Ayatollah Abul-Qasim Khoei",
        "author_ar": "أبو القاسم الخوئي",
        "slug": "khoei",
        "source": "usul",
        "usul_slug": "mucjam-rijal",
        "total_pages": 10924,
        "eshia_id": 14036,
        "eshia_volumes": 24,
        "description": "Comprehensive biographical dictionary. Alphabetical by narrator name. Each entry: name, kunyah, laqab, teachers, students, reliability assessment with cross-references to classical sources.",
    },
    "mamaqani": {
        "name": "Tanqih al-Maqal",
        "name_ar": "تنقيح المقال",
        "author": "Abdullah Mamaqani",
        "author_ar": "عبد الله المامقاني",
        "slug": "mamaqani",
        "source": "eshia",
        "eshia_id": 10510,
        "eshia_volumes": 34,
        "eshia_vol_pages": {
            0: 925, 1: 529, 2: 561, 3: 438, 4: 438, 5: 459,
            6: 440, 7: 440, 8: 457, 9: 440, 10: 435, 11: 426,
            12: 459, 13: 452, 14: 390, 15: 399, 16: 390, 17: 460,
            18: 436, 19: 441, 20: 447, 21: 458, 22: 464, 23: 479,
            24: 499, 25: 444, 26: 412, 27: 430, 28: 428, 29: 380,
            30: 464, 31: 449, 32: 468, 33: 431, 34: 445,
        },
        "description": "Major Rijal work evaluating narrators alphabetically with reliability assessments and cross-references.",
    },
    "kashshi": {
        "name": "Rijal al-Kashshi",
        "name_ar": "رجال الكشي",
        "author": "Muhammad ibn Umar al-Kashshi",
        "author_ar": "محمد بن عمر الكشي",
        "slug": "kashshi",
        "source": "usul",
        "usul_slug": "rijal-al-kashshi-maa-taliqat-al-mirdamad",
        "total_pages": 94,
        "eshia_id": 14015,
        "eshia_volumes": 1,
        "description": "One of the earliest Shia Rijal works. Narrator evaluations with detailed reports (akhbar).",
    },
    "najashi": {
        "name": "Rijal al-Najashi",
        "name_ar": "رجال النجاشي",
        "author": "Ahmad ibn Ali al-Najashi",
        "author_ar": "أحمد بن علي النجاشي",
        "slug": "najashi",
        "source": "usul",
        "usul_slug": "rijal-2",
        "total_pages": 461,
        "eshia_id": 14028,
        "eshia_volumes": 1,
        "description": "Foundational Shia biographical dictionary. Lists narrators with books, teachers, students, reliability.",
    },
    "tusi": {
        "name": "Rijal al-Tusi",
        "name_ar": "رجال الطوسي",
        "author": "Muhammad ibn al-Hasan al-Tusi",
        "author_ar": "محمد بن الحسن الطوسي",
        "slug": "tusi",
        "source": "usul",
        "usol_slug": "rijal-3",
        "usul_slug": "rijal-3",
        "total_pages": 417,
        "eshia_id": 86760,
        "eshia_volumes": 1,
        "description": "Lists narrators organized by which Imam they narrated from.",
    },
    "fihrist": {
        "name": "Fihrist al-Tusi",
        "name_ar": "فهرست الطوسي",
        "author": "Muhammad ibn al-Hasan al-Tusi",
        "author_ar": "محمد بن الحسن الطوسي",
        "slug": "fihrist",
        "source": "usul",
        "usul_slug": "fihrist-2",
        "total_pages": 253,
        "eshia_id": 14010,
        "eshia_volumes": 1,
        "description": "Bibliographic work listing hadith scholars and their books.",
    },
    "ardabili": {
        "name": "Jami' al-Ruwat",
        "name_ar": "جامع الرواة",
        "author": "Muhammad ibn Ali al-Ardabili",
        "author_ar": "محمد بن علي الأردبيلي",
        "slug": "ardabili",
        "source": "usul",
        "usul_slug": "jami-al-ruwat-li-muhammad-ali-al-urdubili",
        "total_pages": 1210,
        "eshia_id": 14021,
        "eshia_volumes": 2,
        "description": "Aggregator compiling narrator assessments from earlier Rijal sources.",
    },
}


# --- Page downloading ---

def fetch_url(url: str, timeout: int = 20) -> str:
    """Fetch URL content with basic error handling."""
    req = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36",
        "Accept": "text/html,application/xhtml+xml",
    })
    resp = urllib.request.urlopen(req, timeout=timeout)
    return resp.read().decode("utf-8")


def extract_text_from_usul_html(html: str) -> str:
    """Extract clean Arabic text from usul.ai HTML page."""
    # usul.ai pages have the text content in specific divs
    # Remove script/style tags first
    text = re.sub(r"<script[^>]*>.*?</script>", "", html, flags=re.DOTALL)
    text = re.sub(r"<style[^>]*>.*?</style>", "", text, flags=re.DOTALL)
    text = re.sub(r"<[^>]+>", " ", text)
    text = re.sub(r"\s+", " ", text).strip()
    # Remove navigation/UI text (English parts)
    # Keep Arabic text (unicode Arabic block)
    return text


def download_usul_page(slug: str, page: int) -> str:
    """Download a single page from usul.ai."""
    url = f"https://usul.ai/ar/t/{slug}/{page}"
    html = fetch_url(url)
    return extract_text_from_usul_html(html)


def download_eshia_page(book_id: int, volume: int, page: int) -> str:
    """Download a single page from eshia.ir."""
    url = f"https://ar.lib.eshia.ir/{book_id}/{volume}/{page}"
    html = fetch_url(url)
    return extract_text_from_usul_html(html)  # Same extraction works


def download_page(book_info: dict, page: int, volume: int = 1) -> str:
    """Download a page from the appropriate source."""
    if book_info["source"] == "usul":
        return download_usul_page(book_info["usul_slug"], page)
    else:
        return download_eshia_page(book_info["eshia_id"], volume, page)


# --- Claude parsing ---

def build_system_prompt(book_name: str, author: str) -> str:
    return f"""You are an expert in Shia Hadith sciences and Ilm al-Rijal (biographical evaluation).

You are parsing entries from {book_name} by {author}, a classical Shia Rijal work.

You will be given the raw Arabic text from one or more pages of this book. Extract all narrator entries
from the text. Each narrator entry typically contains: the narrator's name, lineage, kunyah, nisbah,
and an assessment or biographical information.

Return a JSON array of narrator profile objects. Each object must have:
[
  {{
    "primary_arabic_name": "Full name in Arabic as stated in the text",
    "primary_english_name": "Full name transliterated to English",
    "arabic_aliases": ["all alternative Arabic names/forms mentioned in the text"],
    "english_aliases": ["corresponding English transliterations"],
    "kunyah_arabic": "kunyah in Arabic if mentioned, or null",
    "kunyah_english": "kunyah in English if mentioned, or null",
    "titles": ["nisbahs, laqabs mentioned"],
    "reliability_grade": "one of: reliable (thiqa), truthful (saduq), weak (da'if), very weak, liar/fabricator, ghali, waqifi, corrupt, deviated, mixed/unclear, unknown (majhul), disputed (mukhtalaf fihi), assessed",
    "is_doubtful": true/false,
    "doubtful_reason": "brief reason in English or null",
    "assessment_ar": "the assessment/biographical text from the source in Arabic (direct quotation)",
    "assessment_en": "English translation/summary of the assessment",
    "narrated_from": ["names of people this narrator narrated from, if mentioned"],
    "narrated_to": ["names of people who narrated from this person, if mentioned"],
    "city_or_tribe": "geographic/tribal affiliation if mentioned, or null",
    "generation": "e.g. companion of al-Sadiq, or null",
    "death_year_hijri": "death year if mentioned, or null",
    "gender": "male/female",
    "notes": "any additional noteworthy information"
  }}
]

CRITICAL RULES:
- Only extract narrators actually present in the provided text. Do NOT add narrators from your own knowledge.
- Extract ALL name variants present in the text (full name, shortened forms, nisbah-only, kunyah-only references).
- The assessment_ar field MUST be a direct quotation from the source text, not a paraphrase.
- If a page contains no narrator entries (e.g. introduction, index, or blank), return an empty array [].
- Do NOT include the 14 Infallibles.
- Preserve Arabic spelling exactly as in the source text.
- Return ONLY valid JSON. No markdown fences, no explanations."""


def parse_pages_with_claude(client, book_info: dict, page_texts: list[tuple[int, str]]) -> list[dict]:
    """Parse downloaded page texts using Claude."""
    system = build_system_prompt(book_info["name"], book_info["author"])

    # Combine pages into one prompt (Claude handles multi-page context well)
    pages_desc = "\n\n".join(
        f"--- Page {page_num} ---\n{text}"
        for page_num, text in page_texts
    )

    user_prompt = f"""Parse the narrator entries from this text from {book_info['name']} by {book_info['author']}.

{pages_desc}

Return the JSON array of narrator profiles found in these pages."""

    max_retries = 2
    for attempt in range(max_retries + 1):
        response = client.messages.create(
            model="claude-sonnet-4-20250514",  # TODO: migrate to claude-sonnet-4-6
            max_tokens=8000,
            system=system,
            messages=[{"role": "user", "content": user_prompt}],
        )

        text = response.content[0].text.strip()
        if text.startswith("```"):
            text = re.sub(r"^```(?:json)?\s*\n?", "", text)
            text = re.sub(r"\n?```\s*$", "", text)

        try:
            data = json.loads(text)
        except json.JSONDecodeError as e:
            if attempt < max_retries:
                continue
            last_bracket = text.rfind("]")
            if last_bracket > 0:
                try:
                    data = json.loads(text[:last_bracket + 1])
                    break
                except json.JSONDecodeError:
                    pass
            raise ValueError(f"JSON parse failed after {max_retries + 1} attempts: {e}")

        if not isinstance(data, list):
            raise ValueError(f"Expected JSON array, got {type(data)}")
        break

    return data


def normalize_arabic(raw: str) -> str:
    if not raw:
        return ""
    return (
        re.sub(r"[\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06ED]", "", raw)
        .replace("\u0640", " ")
        .replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
        .replace("ى", "ي").replace("ة", "ه").replace("ؤ", "و").replace("ئ", "ي")
        .replace("(", "").replace(")", "")
        .strip()
    )


def normalize_english(raw: str) -> str:
    if not raw:
        return ""
    return (
        unicodedata.normalize("NFKD", raw)
        .replace("ʿ", "").replace("ʾ", "").replace("ʻ", "")
        .replace("'", "").replace("\u2019", "")
        .lower()
        .strip()
    )


def enrich_profile(profile: dict, book_info: dict, page_nums: list[int]) -> dict:
    """Add computed fields to a raw profile."""
    profile["normalized_arabic"] = normalize_arabic(profile.get("primary_arabic_name", ""))
    profile["normalized_english"] = normalize_english(profile.get("primary_english_name", ""))
    profile["source_assessments"] = [{
        "source_name": book_info["name"],
        "author": book_info["author"],
        "assessment_ar": profile.pop("assessment_ar", ""),
        "assessment_en": profile.pop("assessment_en", ""),
    }]
    profile["rijal_sources"] = [book_info["name"]]
    profile["source_pages"] = page_nums
    return profile


def main():
    parser = argparse.ArgumentParser(description="Parse external Rijal books from actual source texts")
    parser.add_argument("--book", required=True, choices=list(BOOKS.keys()))
    parser.add_argument("--test", action="store_true", help="Download 3 pages and parse")
    parser.add_argument("--pages", type=str, default=None, help="Page range (e.g. '100-120')")
    parser.add_argument("--all", action="store_true", help="Process entire book")
    parser.add_argument("--resume", action="store_true", help="Skip already-processed pages")
    parser.add_argument("--batch-size", type=int, default=5, help="Pages per Claude call (default: 5)")
    parser.add_argument("--output-dir", default="tmp")
    args = parser.parse_args()

    if not any([args.test, args.pages, args.all, args.resume]):
        parser.error("Specify one of: --test, --pages, --all, --resume")

    try:
        import anthropic
        client = anthropic.Anthropic()
    except ImportError:
        print("ERROR: anthropic package not installed. Run: pip install anthropic")
        sys.exit(1)

    book_info = BOOKS[args.book]
    output_file = os.path.join(args.output_dir, f"narrators_book_{book_info['slug']}.json")
    checkpoint_file = os.path.join(args.output_dir, f"narrators_book_{book_info['slug']}_checkpoint.json")

    print(f"Book: {book_info['name']} by {book_info['author']}")
    print(f"Source: {book_info['source']}")
    if book_info.get("usul_slug"):
        print(f"Usul.ai URL: https://usul.ai/ar/t/{book_info['usul_slug']}")
        print(f"Total pages: {book_info.get('total_pages', '?')}")
    print(f"Output: {output_file}")

    # Determine page range
    is_eshia_multivol = book_info["source"] == "eshia" and book_info.get("eshia_volumes", 1) > 1
    pages_per_vol = 500  # default for eshia multi-volume books

    if args.test:
        start, end = 50, 53
    elif args.pages:
        parts = args.pages.split("-")
        start, end = int(parts[0]), int(parts[1])
    elif args.all or args.resume:
        start = 1
        end = book_info.get("total_pages", 100)
    else:
        start, end = 1, 1

    # Load checkpoint for resume
    processed_keys = set()  # "vol:page" for eshia, or just page number as int for usul
    all_profiles = []
    if args.resume:
        if os.path.exists(checkpoint_file):
            ckpt = json.load(open(checkpoint_file, encoding="utf-8"))
            processed_keys = set(ckpt.get("processed_keys", []))
            all_profiles = ckpt.get("profiles", [])
            print(f"Resuming: {len(processed_keys)} pages done, {len(all_profiles)} profiles")
        elif os.path.exists(output_file):
            all_profiles = json.load(open(output_file, encoding="utf-8"))
            for p in all_profiles:
                for pg in p.get("source_pages", []):
                    vol = p.get("source_volume", 1)
                    processed_keys.add(f"{vol}:{pg}")
            print(f"Resuming from output: {len(processed_keys)} pages done, {len(all_profiles)} profiles")

    # Process pages in batches
    os.makedirs(args.output_dir, exist_ok=True)
    batch_size = args.batch_size
    total_parsed = 0
    errors = 0
    empty_streak = 0  # track consecutive empty/duplicate pages to detect end of volume

    if is_eshia_multivol:
        # Multi-volume: iterate volumes 0..N with known page counts
        vol_pages_map = book_info.get("eshia_vol_pages", {})
        num_volumes = book_info.get("eshia_volumes", 1)
        all_vols = sorted(vol_pages_map.keys()) if vol_pages_map else list(range(1, num_volumes + 1))
        for vol in all_vols:
            vol_max_page = vol_pages_map.get(vol, 500)  # fallback guess
            print(f"\n{'='*50}")
            print(f"Volume {vol} ({vol_max_page} pages)")
            print(f"{'='*50}")
            page_num = 1
            while page_num <= vol_max_page:
                # Build batch
                batch_keys = []
                batch_items = []  # (vol, page) tuples
                while len(batch_keys) < batch_size and page_num <= vol_max_page:
                    key = f"{vol}:{page_num}"
                    if key not in processed_keys:
                        batch_keys.append(key)
                        batch_items.append((vol, page_num))
                    page_num += 1

                if not batch_items:
                    continue

                print(f"\n--- Vol {vol}, pages {batch_items[0][1]}-{batch_items[-1][1]} ---")

                # Download pages
                page_texts = []
                for v, pg in batch_items:
                    try:
                        text = download_page(book_info, pg, volume=v)
                        page_texts.append((pg, text))
                    except Exception as e:
                        print(f"  ERROR downloading vol {v} page {pg}: {e}")
                        errors += 1

                if not page_texts:
                    empty_streak += 1
                    continue

                # Parse with Claude
                try:
                    profiles = parse_pages_with_claude(client, book_info, page_texts)
                    if not profiles:
                        empty_streak += 1
                    else:
                        empty_streak = 0
                    for p in profiles:
                        ep = enrich_profile(p, book_info, [pt[0] for pt in page_texts])
                        ep["source_volume"] = vol
                        all_profiles.append(ep)
                    total_parsed += len(profiles)
                    print(f"  Parsed {len(profiles)} narrator profiles")
                    for p in profiles[:3]:
                        print(f"    - {p.get('primary_arabic_name', '?')}")
                    if len(profiles) > 3:
                        print(f"    ... and {len(profiles) - 3} more")
                except Exception as e:
                    print(f"  ERROR parsing: {e}")
                    errors += 1
                    empty_streak += 1

                # Save checkpoint
                for v, pg in batch_items:
                    processed_keys.add(f"{v}:{pg}")
                with open(checkpoint_file, "w", encoding="utf-8") as f:
                    json.dump({
                        "processed_keys": sorted(processed_keys),
                        "profiles": all_profiles,
                    }, f, ensure_ascii=False, indent=2)
                with open(output_file, "w", encoding="utf-8") as f:
                    json.dump(all_profiles, f, ensure_ascii=False, indent=2)
    else:
        # Single-volume (usul.ai or single eshia volume)
        page_nums = [p for p in range(start, end + 1) if p not in processed_keys]

        for i in range(0, len(page_nums), batch_size):
            batch_pages = page_nums[i:i + batch_size]
            print(f"\n--- Batch: pages {batch_pages[0]}-{batch_pages[-1]} ---")

            # Download pages
            page_texts = []
            for pg in batch_pages:
                try:
                    text = download_page(book_info, pg)
                    page_texts.append((pg, text))
                    print(f"  Downloaded page {pg}: {len(text)} chars")
                except Exception as e:
                    print(f"  ERROR downloading page {pg}: {e}")
                    errors += 1

            if not page_texts:
                continue

            # Parse with Claude
            try:
                profiles = parse_pages_with_claude(client, book_info, page_texts)
                for p in profiles:
                    ep = enrich_profile(p, book_info, [pt[0] for pt in page_texts])
                    all_profiles.append(ep)
                total_parsed += len(profiles)
                print(f"  Parsed {len(profiles)} narrator profiles")
                for p in profiles[:5]:
                    print(f"    - {p.get('primary_arabic_name', '?')}")
                if len(profiles) > 5:
                    print(f"    ... and {len(profiles) - 5} more")

            except Exception as e:
                print(f"  ERROR parsing pages {batch_pages}: {e}")
                errors += 1

            # Save checkpoint
            processed_keys.update(batch_pages)
            with open(checkpoint_file, "w", encoding="utf-8") as f:
                json.dump({
                    "processed_keys": sorted(processed_keys),
                    "profiles": all_profiles,
                }, f, ensure_ascii=False, indent=2)
            with open(output_file, "w", encoding="utf-8") as f:
                json.dump(all_profiles, f, ensure_ascii=False, indent=2)

    print(f"\nDone! {total_parsed} profiles from {len(processed_keys)} pages ({errors} errors)")
    print(f"Output: {output_file}")

    # Clean up checkpoint file
    if os.path.exists(checkpoint_file) and not (args.all or args.resume):
        os.remove(checkpoint_file)


if __name__ == "__main__":
    main()
