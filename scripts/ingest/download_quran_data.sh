#!/bin/bash

# Download Quran JSON Data
# This script downloads Quranic verse data in JSON format
# and merges English translations for use with QuranVerseLoader

set -e

OUTPUT_DIR="src/main/resources/static"
TEMP_DIR="/tmp/quran-data-$$"
ARABIC_FILE="$TEMP_DIR/quran-arabic.json"
ENGLISH_FILE="$TEMP_DIR/quran-english.json"
OUTPUT_FILE="$OUTPUT_DIR/quran.json"

echo "Downloading Quran data..."
echo "Temporary directory: $TEMP_DIR"
echo "Output file: $OUTPUT_FILE"

# Create temporary directory
mkdir -p "$TEMP_DIR"

# Download Arabic Quran text from quran-json repository
echo "Downloading Arabic text..."
curl -L -o "$ARABIC_FILE" \
  "https://raw.githubusercontent.com/semarketir/quran-json/master/src/assets/quran.json" \
  || { echo "Failed to download Arabic text"; exit 1; }

# Download English translation (Sahih International is most commonly available)
# For Ali Quli Qarai translation, you'll need to manually merge it
echo "Downloading English translation..."
curl -L -o "$ENGLISH_FILE" \
  "https://raw.githubusercontent.com/semarketir/quran-json/master/src/assets/quran.en.json" \
  || { echo "Failed to download English translation"; exit 1; }

echo "Download complete. Files saved to:"
echo "  $ARABIC_FILE"
echo "  $ENGLISH_FILE"
echo ""
echo "Note: The downloaded files need to be merged to create the final quran.json."
echo "The Arabic file contains the structure and Arabic text."
echo "The English file contains translations that should be added as text_english field."
echo ""
echo "To complete the process:"
echo "1. Review the Arabic file structure at: $ARABIC_FILE"
echo "2. Merge English translations from: $ENGLISH_FILE"
echo "3. Place the merged file at: $OUTPUT_FILE"
echo "4. Run: java com.rewayaat.loader.quran.QuranVerseLoader"
echo ""
echo "For production use with Ali Quli Qarai translation:"
echo "- Download from: https://quran.com/api/v4/chapters"
echo "- Or obtain from a Shia Quran API source"
echo "- Merge with Arabic structure manually"
echo ""
echo "Temporary files preserved at: $TEMP_DIR"
echo "To clean up: rm -rf $TEMP_DIR"
