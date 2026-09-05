#!/bin/bash
# Download complete Quran data using curl and Python for processing
# This bypasses API restrictions by using curl with proper headers

OUTPUT_DIR="/root/git/hadi/rewayaat/src/main/resources/static"
OUTPUT_FILE="$OUTPUT_DIR/quran.json"
TEMP_DIR="/tmp/quran-download-$$"

mkdir -p "$TEMP_DIR"
mkdir -p "$OUTPUT_DIR"

echo "============================================================"
echo "Downloading Complete Quran from quran.com API"
echo "============================================================"
echo ""

# First, get list of all chapters
echo "Fetching chapter list..."
curl -sL "https://api.quran.com/api/v4/chapters?language=en" \
  -H "Accept: application/json" \
  -H "User-Agent: Mozilla/5.0" > "$TEMP_DIR/chapters.json"

# Extract chapter IDs and names
python3 << 'PYTHON_SCRIPT'
import json
import subprocess
import sys
import time

with open('/tmp/quran-download-$$/chapters.json'.replace('$$', str(os.getpid()))) as f:
    data = json.load(f)

chapters = data.get('chapters', [])
print(f"Found {len(chapters)} chapters")

quran_data = {"surahs": []}

for i, ch in enumerate(chapters, 1):
    chapter_id = ch['id']
    name = ch.get('name_simple', '')
    name_arabic = ch.get('name_arabic', '')
    verses_count = ch.get('verses_count', 0)

    print(f"[{i}/{len(chapters)}] Surah {chapter_id}: {name} ({verses_count} verses)")

    # Fetch verses for this chapter
    # Using translation 131 (Sahih International) or get the text directly
    url = f"https://api.quran.com/api/v4/chapters/{chapter_id}/verses?language=en&words=false"

    try:
        result = subprocess.run([
            'curl', '-sL', url,
            '-H', 'Accept: application/json',
            '-H', 'User-Agent: Mozilla/5.0'
        ], capture_output=True, text=True, timeout=60)

        verse_data = json.loads(result.stdout)
        verses = verse_data.get('verses', [])

        if not verses:
            print(f"  WARNING: No verses returned")
            continue

        # Build surah object
        surah = {
            "number": chapter_id,
            "name": name_arabic,
            "englishName": name,
            "englishNameTranslation": ch.get('name_complex', ''),
            "revelationType": ch.get('revelation_place', '').capitalize(),
            "numberOfAyahs": len(verses),
            "ayahs": []
        }

        for v in verses:
            # Extract Arabic text
            text_arabic = v.get('text_uthmani', v.get('text', ''))

            # Extract English translation
            translations = v.get('translations', [])
            text_english = ""
            if translations and len(translations) > 0:
                text_english = translations[0].get('text', '')

            ayah = {
                "number": v.get('id', ''),
                "text": text_arabic,
                "text_english": text_english,
                "numberInSurah": v.get('verse_number', 0),
                "juz": v.get('juz_number', 1),
                "page": v.get('page_number', 1),
                "hizbQuarter": v.get('hizb_number', 1)
            }
            surah['ayahs'].append(ayah)

        quran_data['surahs'].append(surah)

        # Small delay to avoid rate limiting
        time.sleep(0.3)

    except Exception as e:
        print(f"  ERROR: {e}")
        continue

# Save to file
with open('/root/git/hadi/rewayaat/src/main/resources/static/quran.json', 'w', encoding='utf-8') as f:
    json.dump(quran_data, f, ensure_ascii=False, indent=2)

total_verses = sum(len(s['ayahs']) for s in quran_data['surahs'])
print()
print("=" * 60)
print(f"Complete!")
print(f"  Surahs: {len(quran_data['surahs'])}")
print(f"  Total verses: {total_verses}")
print(f"  Saved to: quran.json")
print("=" * 60)
PYTHON_SCRIPT

echo ""
echo "Download complete!"
ls -lh "$OUTPUT_FILE"
