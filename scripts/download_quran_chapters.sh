#!/bin/bash
set -e

OUTPUT_FILE="/root/git/hadi/rewayaat/src/main/resources/static/quran.json"
OUTPUT_DIR=$(dirname "$OUTPUT_FILE")
TEMP_DIR="/tmp/quran-download-$$"

mkdir -p "$TEMP_DIR"
mkdir -p "$OUTPUT_DIR"

echo "============================================================"
echo "Downloading Complete Quran - Chapter by Chapter"
echo "============================================================"
echo ""

# Get chapter list first
echo "Fetching chapter metadata..."
curl -sL "https://api.quran.com/api/v4/chapters?language=en" \
  -H "Accept: application/json" \
  -H "User-Agent: Mozilla/5.0" > "$TEMP_DIR/chapters.json"

# Get total chapters
TOTAL_CHAPTERS=$(python3 -c "import json; data=json.load(open('$TEMP_DIR/chapters.json')); print(len(data.get('chapters', [])))")
echo "Found $TOTAL_CHAPTERS chapters"
echo ""

# Initialize output file
echo '{"surahs":[' > "$OUTPUT_FILE"

FIRST=true

# Process each chapter
for i in $(seq 1 $TOTAL_CHAPTERS); do
    echo "[$i/$TOTAL_CHAPTERS] Fetching Surah $i..."

    # Fetch verses for this chapter
    JSON_DATA=$(curl -sL "https://api.quran.com/api/v4/chapters/$i/verses?language=en&words=false" \
      -H "Accept: application/json" \
      -H "User-Agent: Mozilla/5.0")

    # Process with Python to format correctly
    python3 << PYTHON
import json
import sys

data = json.loads('''$JSON_DATA''')
verses = data.get('verses', [])

if not verses:
    sys.exit(0)

# Get chapter info
chapters = json.load(open('$TEMP_DIR/chapters.json'))
ch = chapters['chapters'][$i-1]

surah = {
    "number": i,
    "name": ch.get('name_arabic', ''),
    "englishName": ch.get('name_simple', ''),
    "englishNameTranslation": ch.get('name_complex', ''),
    "revelationType": ch.get('revelation_place', '').capitalize(),
    "numberOfAyahs": len(verses),
    "ayahs": []
}

for v in verses:
    text_ar = v.get('text_uthmani', v.get('text', ''))
    translations = v.get('translations', [])
    text_en = translations[0].get('text', '') if translations else ''

    ayah = {
        "number": v.get('id', ''),
        "text": text_ar,
        "text_english": text_en,
        "numberInSurah": v.get('verse_number', 0),
        "juz": v.get('juz_number', 1),
        "manzil": v.get('manzil_number', 1),
        "page": v.get('page_number', 1),
        "ruku": v.get('ruku_number', 1),
        "hizbQuarter": v.get('hizb_number', 1),
        "sajda": v.get('sajda_number', None) is not None
    }
    surah['ayahs'].append(ayah)

# Output as JSON line
json_str = json.dumps(surah, ensure_ascii=False)

# Add comma if not first
if not $FIRST:
    sys.stdout.write(',')
sys.stdout.write(json_str)
sys.stdout.flush()

with open('$TEMP_DIR/flag', 'w') as f:
    f.write('done')
PYTHON

    # Check if successful
    if [ -f "$TEMP_DIR/flag" ]; then
        FIRST=false
        rm -f "$TEMP_DIR/flag"
    fi

    # Small delay
    sleep 0.2
done

# Close JSON array
echo ']}' >> "$OUTPUT_FILE"

echo ""
echo "============================================================"
echo "Download complete!"
echo "============================================================"

# Verify
python3 << VERIFY
import json
with open('$OUTPUT_FILE') as f:
    data = json.load(f)

surahs = data['surahs']
total_verses = sum(len(s['ayahs']) for s in surahs)

print(f"Surahs: {len(surahs)}")
print(f"Total verses: {total_verses}")
print(f"File: $OUTPUT_FILE")
VERIFY

ls -lh "$OUTPUT_FILE"
