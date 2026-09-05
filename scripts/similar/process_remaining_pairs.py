#!/usr/bin/env python3
"""Process remaining unjudged hadith pairs for LLM similar hadith discovery."""
import json
import os
from pathlib import Path

# Load precomputed data
precomputed_file = Path('tmp/precomputed.jsonl')
precomputed = []
for line in precomputed_file.open('r', encoding='utf-8'):
    precomputed.append(json.loads(line.strip()))

# Load cache
cache_file = Path('tmp/pairs_cache.json')
if cache_file.exists():
    with cache_file.open('r', encoding='utf-8') as f:
        cache = json.load(f)
else:
    cache = {}

# Track processed pairs
processed_pairs = set()
for pair_id in cache.keys():
    processed_pairs.add(pair_id)

# Find unprocessed pairs
unprocessed_pairs = []
total_pairs = 0

for data in precomputed:
    source_id = data['source_id']
    for candidate in data['candidates']:
        candidate_id = candidate['id']
        pair_id = f"{source_id}||{candidate_id}"

        if pair_id not in processed_pairs:
            unprocessed_pairs.append({
                'source_id': source_id,
                'candidate_id': candidate_id,
                'source_arabic': data.get('source_arabic', ''),
                'candidate_arabic': candidate.get('arabic', ''),
                'source_tags': data.get('source_tags', []),
                'candidate_tags': candidate.get('tags', []),
                'score': candidate.get('score', 0)
            })
            total_pairs += 1

print(f"Total pairs across all hadith: {total_pairs}")
print(f"Pairs in cache: {len(cache)}")
print(f"Unprocessed pairs: {len(unprocessed_pairs)}")
print(f"Percentage processed: {len(cache)/total_pairs*100:.1f}%")

# Create batches for remaining pairs
batch_size = 10  # Hadith per batch (not pairs)
output_dir = Path('tmp/batches')
output_dir.mkdir(exist_ok=True)

# Group by source hadith
source_to_pairs = {}
for pair in unprocessed_pairs:
    source_id = pair['source_id']
    if source_id not in source_to_pairs:
        source_to_pairs[source_id] = []
    source_to_pairs[source_id].append(pair)

# Create batches
batches = []
current_batch = []
current_batch_size = 0

for source_id, pairs in source_to_pairs.items():
    if current_batch_size + len(pairs) <= batch_size:
        current_batch.extend(pairs)
        current_batch_size += len(pairs)
    else:
        if current_batch:
            batches.append(current_batch)
        current_batch = pairs
        current_batch_size = len(pairs)

if current_batch:
    batches.append(current_batch)

print(f"\nCreated {len(batches)} batches for {len(unprocessed_pairs)} remaining pairs")

# Save batch files
for i, batch in enumerate(batches[:100]):  # Process first 100 batches
    batch_file = output_dir / f'batch_remaining_{i:03d}.txt'

    with batch_file.open('w', encoding='utf-8') as f:
        # Add header
        f.write(f"# Batch {i+1}: {len(batch)} pairs\n")
        f.write("# Format: source_id,candidate_id,source_arabic,candidate_arabic,source_tags,candidate_tags,score\n\n")

        for pair in batch:
            tags_str = ' '.join(pair['source_tags'])
            candidate_tags_str = ' '.join(pair['candidate_tags'])
            f.write(f"{pair['source_id']},{pair['candidate_id']},")
            f.write(f'"{pair["source_arabic"]}",')
            f.write(f'"{pair["candidate_arabic"]}",')
            f.write(f"{tags_str},{candidate_tags_str},{pair['score']}\n")

    print(f"Created {batch_file} with {len(batch)} pairs")

print(f"\nReady to process {len(batches)} batches with {len(unprocessed_pairs)} total pairs")