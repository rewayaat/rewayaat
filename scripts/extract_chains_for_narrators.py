#!/usr/bin/env python3
"""Extract chain (isnad) text from hadith batches for narrator extraction.

Scans JSONL or JSON batch files and extracts chain portions from Arabic text
by looking for isnad markers like حدثنا, أخبرنا, عن, etc.
"""

import json
import os
import sys
import re
from typing import List, Dict, Any, Optional

# Arabic isnad markers that indicate chain narration
ISNAD_MARKERS = [
    'حدثنا', 'حدثني', 'أخبرنا', 'أخبرني', 'حدثكم',
    'أخبركم', 'أنبأنا', 'أنبأني', 'حدّثنا', 'حدّثني',
    'أخبرنا', 'أخبرني', 'سمعت', 'سمعته', 'سمعنا',
    'عن أبي', 'عن أبيه', 'عن عبد الله', 'عن علي',
    'عن محمد', 'عن أحمد', 'عن الحسين', 'عن الحسن',
    'قال أبو', 'قال رسول الله', 'قال أمير المؤمنين',
    'عن النبي', 'عن الإمام', 'عن الصادق', 'عن الباقر',
    'عن الكاظم', 'عن الرضا', 'عن الجواد', 'عن الهادي',
    'عن العسكري', 'عن المهدي',
    'ابن أبي', 'بن أبي', 'ابن محمد', 'بن أحمد',
    'بن علي', 'بن الحسين', 'بن الحسن', 'بن إبراهيم',
    'أبو عبد الله', 'أبو جعفر', 'أبو إبراهيم',
    'أبو الحسن', 'أبو محمد', 'أبو علي',
]

# Minimum chain length to consider
MIN_CHAIN_LENGTH = 40

def extract_chain_from_arabic(arabic_text: str) -> Optional[str]:
    """Extract chain portion from Arabic hadith text."""
    if not arabic_text or len(arabic_text) < MIN_CHAIN_LENGTH:
        return None

    # Look for isnad patterns in the first part of the text
    # Chains typically appear at the beginning before the matn (content)

    # Try to find isnad markers and extract the chain portion
    best_start = -1
    for marker in ISNAD_MARKERS[:20]:  # Check main markers
        idx = arabic_text.find(marker)
        if idx >= 0:
            if best_start < 0 or idx < best_start:
                best_start = idx

    if best_start < 0:
        # No explicit isnad marker found, check for implicit patterns
        # like "قال فلان عن فلان"
        if any(m in arabic_text[:200] for m in ['عن', 'قال']):
            # Take first portion that likely contains chain
            chain_part = arabic_text[:min(300, len(arabic_text))]
            if _looks_like_chain(chain_part):
                return chain_part
        return None

    # Extract from marker to end of chain (before matn)
    chain_text = arabic_text[best_start:]

    # Chain usually ends at colons, quotes, or content markers
    # Try to find a natural break point
    break_patterns = [
        r'[":"]\s*[«"]',       # colon/quote before speech
        r'قال\s*[:":]',        # "said:" pattern
        r'يقول\s*[:":]',       # "says:" pattern
        r'قالوا\s*[:":]',      # "they said:" pattern
        r'فقال\s*[:":]',       # "then he said:" pattern
    ]

    for pattern in break_patterns:
        match = re.search(pattern, chain_text)
        if match:
            chain_text = chain_text[:match.start()]
            break

    # If chain is too long, truncate at reasonable point
    if len(chain_text) > 500:
        chain_text = chain_text[:500]

    chain_text = chain_text.strip()
    if len(chain_text) < MIN_CHAIN_LENGTH:
        return None

    return chain_text


def _looks_like_chain(text: str) -> bool:
    """Heuristic to check if text looks like a hadith chain."""
    # Must contain at least one "عن" (from/narrated from) and a name pattern
    if 'عن' not in text:
        return False
    # Check for Arabic name patterns (bin/ibn/abu)
    name_patterns = ['بن ', 'ابن ', 'أبو ', 'أبي ', 'عن ']
    count = sum(1 for p in name_patterns if p in text)
    return count >= 2


def extract_narrators_from_batches(batch_dir: str, max_docs: int = 500) -> List[Dict[str, Any]]:
    """Extract chain data from hadith batch files."""
    results = []

    # Find all batch files
    batch_files = sorted([
        os.path.join(batch_dir, f)
        for f in os.listdir(batch_dir)
        if f.endswith('.json') and f.startswith('batch_')
    ])

    for batch_file in batch_files:
        if len(results) >= max_docs:
            break

        with open(batch_file, 'r', encoding='utf-8') as f:
            data = json.load(f)

        for doc in data:
            if len(results) >= max_docs:
                break

            source = doc.get('_source', doc)
            arabic = source.get('arabic', '')
            english = source.get('english', '')
            book = source.get('book', '')
            doc_id = doc.get('_id', '')

            chain = extract_chain_from_arabic(arabic)
            if chain:
                results.append({
                    '_id': doc_id,
                    'book': book,
                    'arabic_chain': chain,
                    'english_preview': english[:300] if english else '',
                })

    return results


def main():
    import argparse
    parser = argparse.ArgumentParser(description='Extract chains from hadith batches')
    parser.add_argument('--batch-dir', default='tmp/batches/hadith',
                        help='Directory containing hadith batch JSON files')
    parser.add_argument('--output', default='tmp/narrator_chains.json',
                        help='Output JSON file for extracted chains')
    parser.add_argument('--max-docs', type=int, default=500,
                        help='Maximum number of documents to extract chains from')
    parser.add_argument('--split', type=int, default=3,
                        help='Split output into N files for parallel processing')
    args = parser.parse_args()

    print(f"Extracting chains from {args.batch_dir}...")
    chains = extract_narrators_from_batches(args.batch_dir, args.max_docs)
    print(f"Extracted {len(chains)} chains with isnad patterns")

    if args.split > 1:
        chunk_size = len(chains) // args.split + 1
        for i in range(args.split):
            start = i * chunk_size
            end = min(start + chunk_size, len(chains))
            chunk = chains[start:end]
            out_path = args.output.replace('.json', f'_part{i+1}.json')
            with open(out_path, 'w', encoding='utf-8') as f:
                json.dump(chunk, f, ensure_ascii=False, indent=2)
            print(f"Wrote {len(chunk)} chains -> {out_path}")
    else:
        with open(args.output, 'w', encoding='utf-8') as f:
            json.dump(chains, f, ensure_ascii=False, indent=2)
        print(f"Wrote {len(chains)} chains -> {args.output}")


if __name__ == '__main__':
    main()
