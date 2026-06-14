#!/usr/bin/env python3
"""
Phase 1A: Parse Kitab al-Du'afa (Ibn al-Ghada'iri) entries into narrator profiles.

Uses Claude to parse each entry's Arabic+English text into structured narrator data.
Reads the 226 entries from batch files and writes structured JSON.

Usage:
    python3 scripts/parse_duafa_narrators.py [--output tmp/narrators_book_duafa.json]
    python3 scripts/parse_duafa_narrators.py --resume  (skip already parsed entries)
"""

import json
import glob
import os
import sys
import re
import time
import unicodedata
from typing import Optional

# --- Data loading ---

def load_duafa_entries(batch_dirs: list[str]) -> list[dict]:
    """Load all Kitab al-Du'afa entries from batch files."""
    entries = []
    for d in batch_dirs:
        for f in sorted(glob.glob(os.path.join(d, "*.jsonl"))):
            for line in open(f):
                doc = json.loads(line)
                if doc.get("_source", {}).get("book") == "Kitāb al-Ḍuʿafāʾ":
                    entries.append(doc)
    return entries


def normalize_arabic(raw: str) -> str:
    """Strip diacritics and normalize Arabic for matching."""
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
    """Normalize English transliteration for matching."""
    if not raw:
        return ""
    return (
        unicodedata.normalize("NFKD", raw)
        .replace("ʿ", "").replace("ʾ", "").replace("ʻ", "")
        .replace("'", "").replace("\u2019", "")
        .lower()
        .strip()
    )


# --- Claude parsing ---

SYSTEM_PROMPT = """You are an expert in Shia Hadith sciences and Ilm al-Rijal (biographical evaluation).
You are parsing entries from Kitab al-Du'afa by Ibn al-Ghada'iri, a classical Shia Rijal work that
evaluates weak narrators.

For each entry, extract a structured narrator profile. Return ONLY valid JSON (no markdown, no explanation).
The JSON must have exactly this shape:

{
  "primary_arabic_name": "The narrator's full name in Arabic as stated in the text",
  "primary_english_name": "The narrator's full name in English transliteration",
  "arabic_aliases": ["any alternative Arabic names, nicknames, shortened forms mentioned"],
  "english_aliases": ["corresponding English transliterations of aliases"],
  "kunyah_arabic": "kunyah in Arabic if mentioned, e.g. أبو جعفر, or null",
  "kunyah_english": "kunyah in English if mentioned, e.g. Abū Jaʿfar, or null",
  "titles": ["nisbahs, laqabs, etc. e.g. القمي, الكوفي, الرازي"],
  "reliability_grade": "one of: liar/fabricator, ghālī, wāqifī, very weak, weak, corrupt, deviated, mixed/unclear, disregarded, unknown, assessed",
  "is_doubtful": true/false,
  "doubtful_reason": "brief reason in English or null",
  "assessment_ar": "the complete Arabic assessment text from the source",
  "assessment_en": "the complete English assessment text from the source",
  "narrated_from": ["names of people this narrator narrated from, if mentioned"],
  "city_or_tribe": "geographic or tribal affiliation if mentioned, or null",
  "generation": "e.g. 'companion of al-Sadiq', 'tabi\\'i', or null",
  "gender": "male/female",
  "notes": "any additional noteworthy information from the text"
}

Important:
- Extract ALL name variants mentioned in the text (full name, shortened, with/without lineage, nisbah-only, kunyah-only)
- The assessment text should be the FULL original text (both Arabic and English), not a summary
- For reliability_grade, use the most severe assessment mentioned
- If the entry is a preamble/introduction (not about a specific narrator), return null
- If the narrator is one of the 14 Infallibles (Prophet Muhammad, Fatima, the 12 Imams), set is_imam_or_prophet hint in notes
- Arabic names should preserve original spelling; do NOT strip diacritics from display names"""


def parse_entry_with_claude(client, entry: dict) -> Optional[dict]:
    """Use Claude to parse a single Du'afa entry."""
    source = entry["_source"]
    doc_id = entry["_id"]
    chapter = source.get("chapter", "")
    arabic = source.get("arabic", "").strip()
    english = source.get("english", "").strip()

    # Skip introduction entries outright
    if "Introduction" in chapter or "مقدمة" in chapter:
        return None

    user_prompt = f"""Parse this Kitab al-Du'afa entry:

Entry ID: {doc_id}
Chapter (narrator name): {chapter}

Arabic text:
{arabic}

English text:
{english}

Return the narrator profile as JSON."""

    response = client.messages.create(
        model="claude-sonnet-4-20250514",
        max_tokens=1024,
        system=SYSTEM_PROMPT,
        messages=[{"role": "user", "content": user_prompt}],
    )

    text = response.content[0].text.strip()
    # Strip markdown code fences if present
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*\n?", "", text)
        text = re.sub(r"\n?```\s*$", "", text)

    data = json.loads(text)

    # If Claude returned null, skip this entry
    if data is None:
        return None

    # Add computed fields
    data["_id"] = doc_id
    data["normalized_arabic"] = normalize_arabic(data.get("primary_arabic_name", ""))
    data["normalized_english"] = normalize_english(data.get("primary_english_name", ""))
    data["source_assessments"] = [{
        "source_name": "Kitab al-Du'afa",
        "author": "Ibn al-Ghada'iri",
        "assessment_ar": data.pop("assessment_ar", arabic),
        "assessment_en": data.pop("assessment_en", english),
    }]
    data["rijal_sources"] = ["Kitab al-Du'afa"]

    return data


def main():
    import argparse
    parser = argparse.ArgumentParser(description="Parse Kitab al-Du'afa into narrator profiles")
    parser.add_argument("--input-dirs", nargs="+", default=["batches/", "batches_new/"],
                        help="Directories containing batch JSONL files")
    parser.add_argument("--output", default="tmp/narrators_book_duafa.json",
                        help="Output JSON file")
    parser.add_argument("--resume", action="store_true",
                        help="Skip entries already in the output file")
    parser.add_argument("--limit", type=int, default=None,
                        help="Max entries to process (for testing)")
    args = parser.parse_args()

    # Initialize Anthropic client
    try:
        import anthropic
        client = anthropic.Anthropic()
    except ImportError:
        print("ERROR: anthropic package not installed. Run: pip install anthropic")
        sys.exit(1)

    print(f"Loading Du'afa entries from {args.input_dirs}...")
    entries = load_duafa_entries(args.input_dirs)
    print(f"Found {len(entries)} entries")

    # Filter out introductions
    narrator_entries = [e for e in entries
                        if "Introduction" not in e.get("_source", {}).get("chapter", "")
                        and "مقدمة" not in e.get("_source", {}).get("chapter", "")]
    print(f"Narrator entries (excluding preambles): {len(narrator_entries)}")

    if args.limit:
        narrator_entries = narrator_entries[:args.limit]
        print(f"Limited to {args.limit} entries")

    # Resume: load existing results
    existing = {}
    if args.resume and os.path.exists(args.output):
        existing_list = json.load(open(args.output, encoding="utf-8"))
        existing = {p["_id"]: p for p in existing_list}
        print(f"Loaded {len(existing)} existing profiles for resume")

    # Process entries
    profiles = list(existing.values())
    processed = len(existing)
    errors = 0

    for i, entry in enumerate(narrator_entries):
        doc_id = entry["_id"]

        if doc_id in existing:
            continue

        try:
            profile = parse_entry_with_claude(client, entry)
            if profile is None:
                print(f"  [{i+1}/{len(narrator_entries)}] {doc_id}: SKIPPED (non-narrator)")
                continue

            profiles.append(profile)
            processed += 1
            print(f"  [{i+1}/{len(narrator_entries)}] {doc_id}: {profile.get('primary_english_name', '?')[:50]} — {profile.get('reliability_grade', '?')}")

        except Exception as e:
            errors += 1
            print(f"  [{i+1}/{len(narrator_entries)}] {doc_id}: ERROR — {e}")
            continue

        # Save checkpoint every 25 entries
        if processed % 25 == 0:
            os.makedirs(os.path.dirname(args.output) or ".", exist_ok=True)
            with open(args.output, "w", encoding="utf-8") as f:
                json.dump(profiles, f, ensure_ascii=False, indent=2)
            print(f"  [checkpoint: {processed} profiles saved]")

    # Final write
    os.makedirs(os.path.dirname(args.output) or ".", exist_ok=True)
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(profiles, f, ensure_ascii=False, indent=2)

    print(f"\nDone! {processed} profiles written to {args.output} ({errors} errors)")


if __name__ == "__main__":
    main()
