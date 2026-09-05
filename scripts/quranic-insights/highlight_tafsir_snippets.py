#!/usr/bin/env python3
"""Highlight relevant portions of tafsir snippets using Claude API.

For each hadith-verse-snippet combination, uses Claude to identify the most relevant
portions of the commentary text that explain how the verse connects to the hadith's topic,
and wraps them in <em> tags.

Usage:
    python3 scripts/quranic-insights/highlight_tafsir_snippets.py [--start N] [--end N] [--resume] [--workers N]
"""

import json
import os
import sys
import time
import argparse
import threading
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed
import anthropic

INPUT_FILE = Path("tmp/quranic-snippet-matches/highlight-batches/highlight_batch_0.json")
OUTPUT_FILE = Path("tmp/quranic-snippet-matches/highlighted/highlighted_batch_0.jsonl")

client = anthropic.Anthropic(
    base_url=os.environ.get("ANTHROPIC_BASE_URL"),
    api_key=os.environ.get("ANTHROPIC_AUTH_TOKEN"),
)

MODEL = "claude-sonnet-4-20250514"  # or try "claude-3-5-sonnet-20241022"

# Process 1 item at a time for accuracy - each has up to 5 snippets with full commentary
SINGLE_ITEM_PROMPT = """You are an expert in Quranic tafsir (commentary) and hadith analysis.

A hadith (Islamic tradition) quotes/references a Quranic verse. Below is the verse as quoted in the hadith, the verse in English, and tafsir commentary snippets about this verse.

For EACH tafsir snippet, identify the portion(s) of the commentary_text that directly explain or relate to how this verse connects to the hadith's usage of it. Wrap those relevant portions in <em> tags.

RULES:
1. Do NOT rewrite or change any text - ONLY add <em> and </em> tags around existing text
2. Highlight parts that help a reader quickly grasp "THIS is how the verse connects to this hadith"
3. If the entire snippet is relevant, wrap the whole thing in <em> tags
4. If only some sentences/phrases are relevant, highlight just those
5. If nothing is particularly relevant to this specific hadith-verse connection, return the text unchanged
6. Focus on thematic and theological connections

CONTEXT:
- Hadith ID: {hadith_id}
- Verse: {verse_key}
- Extracted Arabic (verse as quoted in hadith): {extracted_arabic}
- Verse in English: {verse_text_english}

{snippets_block}

RESPOND with a JSON array where each element has "tafsir_slug" and "commentary_text_highlighted".
Return ONLY the JSON array, no other text."""

lock = threading.Lock()


def process_item(item, max_retries=3):
    """Process all snippets for one hadith-verse item."""
    # Build snippets block
    snippets_block_parts = []
    for i, snippet in enumerate(item['tafsir_snippets']):
        snippets_block_parts.append(
            f"TAFSIR SNIPPET {i+1} (slug: {snippet['tafsir_slug']}):\n"
            f"{snippet['commentary_text']}"
        )
    snippets_block = "\n\n".join(snippets_block_parts)

    prompt = SINGLE_ITEM_PROMPT.format(
        hadith_id=item['hadith_id'],
        verse_key=item['verse_key'],
        extracted_arabic=item['extracted_arabic'],
        verse_text_english=item['verse_text_english'],
        snippets_block=snippets_block,
    )

    for attempt in range(max_retries):
        try:
            response = client.messages.create(
                model=MODEL,
                max_tokens=8000,
                temperature=0,
                messages=[{"role": "user", "content": prompt}],
            )

            text = response.content[0].text.strip()

            # Try to extract JSON array from response
            # Sometimes Claude wraps in markdown code blocks
            if "```json" in text:
                text = text.split("```json")[1].split("```")[0].strip()
            elif "```" in text:
                text = text.split("```")[1].split("```")[0].strip()

            results = json.loads(text)

            if isinstance(results, list) and len(results) == len(item['tafsir_snippets']):
                return results
            else:
                print(f"  Warning: Expected {len(item['tafsir_snippets'])} results, got {len(results) if isinstance(results, list) else 'non-list'} for {item['hadith_id']}:{item['verse_key']} (attempt {attempt+1})")
                if attempt < max_retries - 1:
                    time.sleep(2)
                    continue

        except json.JSONDecodeError as e:
            print(f"  JSON parse error for {item['hadith_id']}:{item['verse_key']} (attempt {attempt+1}): {str(e)[:100]}")
            if attempt < max_retries - 1:
                time.sleep(2)
                continue
        except Exception as e:
            print(f"  API error for {item['hadith_id']}:{item['verse_key']} (attempt {attempt+1}): {str(e)[:100]}")
            if attempt < max_retries - 1:
                time.sleep(5)
                continue
            raise

    # Fallback: return unhighlighted text
    print(f"  FALLBACK: returning unhighlighted for {item['hadith_id']}:{item['verse_key']}")
    return [{"tafsir_slug": s['tafsir_slug'], "commentary_text_highlighted": s['commentary_text']}
            for s in item['tafsir_snippets']]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--start", type=int, default=0, help="Start index")
    parser.add_argument("--end", type=int, default=-1, help="End index (exclusive)")
    parser.add_argument("--resume", action="store_true", help="Resume from checkpoint")
    parser.add_argument("--workers", type=int, default=6, help="Parallel API calls")
    args = parser.parse_args()

    # Load input data
    print(f"Loading {INPUT_FILE}...")
    with open(INPUT_FILE) as f:
        data = json.load(f)

    print(f"Loaded {len(data)} items")
    total_snippets = sum(len(item['tafsir_snippets']) for item in data)
    print(f"Total tafsir snippets: {total_snippets}")

    # Determine range
    start = args.start
    end = len(data) if args.end == -1 else args.end
    work_items = data[start:end]
    print(f"Processing items {start} to {end} ({len(work_items)} items)")

    # Resume support
    completed_keys = set()
    existing_results = []
    if args.resume and OUTPUT_FILE.exists():
        with open(OUTPUT_FILE) as f:
            for line in f:
                line = line.strip()
                if line:
                    obj = json.loads(line)
                    existing_results.append(obj)
                    completed_keys.add((obj['hadith_id'], obj['verse_key'], obj['tafsir_slug']))
        print(f"Resuming: {len(completed_keys)} snippets already done")

        # Filter work items to only those with incomplete snippets
        filtered = []
        for item in work_items:
            incomplete_snippets = []
            for snippet in item['tafsir_snippets']:
                key = (item['hadith_id'], item['verse_key'], snippet['tafsir_slug'])
                if key not in completed_keys:
                    incomplete_snippets.append(snippet)
            if incomplete_snippets:
                # Create a copy with only incomplete snippets
                item_copy = dict(item)
                item_copy['tafsir_snippets'] = incomplete_snippets
                filtered.append(item_copy)
        work_items = filtered
        print(f"Remaining: {len(work_items)} items with incomplete snippets")

    if not work_items:
        print("Nothing to process!")
        return

    # Process in parallel
    completed_count = len(existing_results)
    total_to_process = sum(len(item['tafsir_snippets']) for item in work_items)
    print(f"Processing {total_to_process} snippets with {args.workers} workers...")

    results = list(existing_results)
    processed = 0
    errors = 0

    with open(OUTPUT_FILE, "a" if (args.resume and existing_results) else "w") as outf:
        # If not resuming or file was empty, write from scratch
        if not (args.resume and existing_results):
            outf.truncate(0)

        with ThreadPoolExecutor(max_workers=args.workers) as executor:
            futures = {}
            for item in work_items:
                future = executor.submit(process_item, item)
                futures[future] = item

            for future in as_completed(futures):
                item = futures[future]
                try:
                    snippet_results = future.result()
                    for sr in snippet_results:
                        result = {
                            'hadith_id': item['hadith_id'],
                            'verse_key': item['verse_key'],
                            'tafsir_slug': sr['tafsir_slug'],
                            'commentary_text_highlighted': sr['commentary_text_highlighted'],
                        }
                        with lock:
                            outf.write(json.dumps(result, ensure_ascii=False) + "\n")
                            outf.flush()
                            processed += 1
                            results.append(result)
                except Exception as e:
                    errors += len(item['tafsir_snippets'])
                    print(f"  FAILED {item['hadith_id']}:{item['verse_key']}: {str(e)[:100]}")
                    # Write fallback
                    for snippet in item['tafsir_snippets']:
                        result = {
                            'hadith_id': item['hadith_id'],
                            'verse_key': item['verse_key'],
                            'tafsir_slug': snippet['tafsir_slug'],
                            'commentary_text_highlighted': snippet['commentary_text'],
                        }
                        with lock:
                            outf.write(json.dumps(result, ensure_ascii=False) + "\n")
                            outf.flush()
                            processed += 1

                if processed % 50 == 0:
                    print(f"  Progress: {processed}/{total_to_process} snippets ({errors} errors)")

    # Final count
    total_output = 0
    with open(OUTPUT_FILE) as f:
        for line in f:
            if line.strip():
                total_output += 1

    print(f"\nDone! Wrote {total_output} results to {OUTPUT_FILE}")
    print(f"  Processed: {processed}, Errors: {errors}")


if __name__ == "__main__":
    main()
