#!/usr/bin/env python3
"""
Download complete Quran data from quran.com API
Includes Arabic text and English translation (Sahih International)
"""

import json
import urllib.request
import urllib.error
import time

def fetch_json(url):
    """Fetch JSON from URL with retries"""
    max_retries = 3
    for attempt in range(max_retries):
        try:
            with urllib.request.urlopen(url, timeout=30) as response:
                return json.load(response)
        except Exception as e:
            if attempt < max_retries - 1:
                print(f"  Retry {attempt + 1}/{max_retries}...")
                time.sleep(2)
            else:
                raise

def download_quran():
    """Download complete Quran data"""

    print("Fetching Quran metadata...")
    chapters_url = "https://api.quran.com/api/v4/chapters"
    chapters = fetch_json(chapters_url)['chapters']

    print(f"Found {len(chapters)} chapters")

    quran_data = {"surahs": []}

    for i, chapter in enumerate(chapters, 1):
        chapter_id = chapter['id']
        chapter_name = chapter['name_simple']
        verse_count = chapter['verses_count']

        print(f"[{i}/{len(chapters)}] Surah {chapter_id}: {chapter_name} ({verse_count} verses)")

        # Fetch verses with Arabic and English translation (translation 131 = Sahih International)
        verses_url = f"https://api.quran.com/api/v4/chapters/{chapter_id}/verses?language=en&words=true"

        try:
            data = fetch_json(verses_url)
            verses = data.get('verses', [])

            if not verses:
                print(f"  WARNING: No verses found, trying alternative endpoint...")
                # Alternative: fetch verse by verse
                ayahs = []
                for verse_num in range(1, verse_count + 1):
                    verse_key = f"{chapter_id}:{verse_num}"
                    verse_url = f"https://api.quran.com/api/v4/verses_by_key/{verse_key}?language=en&words=false"
                    try:
                        v_data = fetch_json(verse_url)
                        verse = v_data.get('verse', {})
                        ayahs.append({
                            "number": verse.get('id', verse_num),
                            "text": verse.get('text_uthmani', ''),
                            "numberInSurah": verse_num,
                            "juz": verse.get('juz_number', 1),
                            "page": verse.get('page_number', 1),
                            "hizbQuarter": verse.get('hizb_number', 1)
                        })
                    except Exception as e:
                        print(f"    Error fetching verse {verse_key}: {e}")
                verses = ayahs

            # Build surah object
            surah = {
                "number": chapter_id,
                "name": chapter.get('name_arabic', ''),
                "englishName": chapter.get('name_simple', ''),
                "englishNameTranslation": chapter.get('name_complex', ''),
                "revelationType": chapter.get('revelation_place', '').capitalize(),
                "numberOfAyahs": len(verses),
                "ayahs": []
            }

            # Extract verse data
            for verse in verses:
                v_text = verse.get('text_uthmani', verse.get('text', ''))

                # Get English translation
                translations = verse.get('translations', [])
                eng_text = ""
                if translations and len(translations) > 0:
                    eng_text = translations[0].get('text', '')

                ayah = {
                    "number": verse.get('id', verse.get('verse_key', '')),
                    "text": v_text,
                    "text_english": eng_text,
                    "numberInSurah": verse.get('verse_number', verse.get('numberInSurah', 0)),
                    "juz": verse.get('juz_number', verse.get('juz', 1)),
                    "manzil": verse.get('manzil_number', 1),
                    "page": verse.get('page_number', verse.get('page', 1)),
                    "ruku": verse.get('ruku_number', 1),
                    "hizbQuarter": verse.get('hizb_number', 1),
                    "sajda": verse.get('sajda_number', None) is not None
                }
                surah['ayahs'].append(ayah)

            quran_data['surahs'].append(surah)

            # Rate limiting
            time.sleep(0.5)

        except Exception as e:
            print(f"  ERROR processing Surah {chapter_id}: {e}")
            continue

    return quran_data

if __name__ == "__main__":
    print("=" * 60)
    print("Downloading Complete Quran from quran.com API")
    print("=" * 60)
    print()

    quran = download_quran()

    # Calculate stats
    total_verses = sum(len(s['ayahs']) for s in quran['surahs'])

    print()
    print("=" * 60)
    print(f"Download complete!")
    print(f"  Surahs: {len(quran['surahs'])}")
    print(f"  Total verses: {total_verses}")
    print("=" * 60)

    # Save to file
    output_file = "quran-full.json"
    with open(output_file, 'w', encoding='utf-8') as f:
        json.dump(quran, f, ensure_ascii=False, indent=2)

    print(f"Saved to: {output_file}")
    print(f"File size: {len(open(output_file).read())} bytes")
