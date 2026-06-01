#!/usr/bin/env python3
import json

def analyze_conceptual_pairs():
    """Analyze the conceptual similarity pairs in more detail"""

    # Read the results
    with open('/home/zir0/git/rewayaat/tmp/results_batch_remaining_036.json', 'r', encoding='utf-8') as f:
        results = json.load(f)

    # Read original batch to get full text
    with open('/home/zir0/git/rewayaat/tmp/batches/batch_remaining_036.txt', 'r', encoding='utf-8') as f:
        batch_content = f.read()

    # Find conceptual similarity pairs
    conceptual_pairs = []
    for result in results:
        if result['verdict'] == 'SIMILAR_CONCEPTUAL':
            conceptual_pairs.append(result)

    print(f"Found {len(conceptual_pairs)} conceptual similarity pairs")
    print("\nDetailed analysis:")

    # Get full text for each conceptual pair
    for pair in conceptual_pairs:
        source_id = pair['source_id']
        candidate_id = pair['candidate_id']

        # Find the original entry
        lines = batch_content.split('\n')
        for line in lines:
            if source_id in line and candidate_id in line:
                parts = line.split(',')
                if len(parts) >= 5:
                    source_arabic = parts[2].strip('"')
                    candidate_arabic = parts[3].strip('"')
                    source_tags = parts[4].strip('"').split(',') if parts[4] else []
                    candidate_tags = parts[5].strip('"').split(',') if parts[5] else []

                    print(f"\nPair: {source_id} -> {candidate_id}")
                    print(f"Source tags: {source_tags}")
                    print(f"Candidate tags: {candidate_tags}")
                    print(f"Arabic overlap: {pair['arabic_overlap']:.1f}%")
                    print(f"\nSource text (sample): {source_arabic[:200]}...")
                    print(f"Candidate text (sample): {candidate_arabic[:200]}...")
                    print("-" * 80)
                    break

    # Manual review needed for these pairs
    print("\nRECOMMENDATION: These pairs require manual review as they were classified as SIMILAR_CONCEPTUAL")
    print("The system detected core concept similarity but tag overlap was low.")
    print("A human judge should verify if they truly represent the same core concept.")

if __name__ == '__main__':
    analyze_conceptual_pairs()