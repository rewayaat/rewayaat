#!/usr/bin/env python3
"""Manual hadith similarity judge for batch processing.

Since agent spawning is not available, this script manually processes
hadith similarity judgments based on Arabic wording overlap and conceptual similarity.
"""
import json
import re
import sys
from pathlib import Path
from difflib import SequenceMatcher
import argparse

def normalize_text(text):
    """Normalize Arabic text for comparison."""
    # Remove extra whitespace and normalize
    text = re.sub(r'\s+', ' ', text).strip()
    return text

def calculate_arabic_overlap(text1, text2):
    """Calculate Arabic wording overlap ratio."""
    norm1 = normalize_text(text1)
    norm2 = normalize_text(text2)

    # Simple word overlap
    words1 = set(norm1.split())
    words2 = set(norm2.split())

    if not words1 or not words2:
        return 0.0

    intersection = words1 & words2
    union = words1 | words2

    return len(intersection) / len(union)

def judge_similarity(source_arabic, source_english, candidate_arabic, candidate_english, candidate_tags):
    """
    Judge similarity between source hadith and candidate.

    Returns: (verdict, match_type, reason)
    - verdict: "similar", "rejected"
    - match_type: "wording", "conceptual", or ""
    - reason: string explaining the judgment
    """

    # Calculate Arabic overlap
    arabic_overlap = calculate_arabic_overlap(source_arabic, candidate_arabic)

    # Check for obvious wording matches (>80% overlap)
    if arabic_overlap >= 0.80:
        return (
            "similar",
            "wording",
            f"Significant Arabic wording overlap ({arabic_overlap:.1%})"
        )

    # Extract key concepts from both texts
    source_keywords = []
    candidate_keywords = []

    # Simple keyword extraction - look for important terms
    important_terms = [
        "zakat", "sadaqa", "charity", "spending", "loan", "debt", "wealth",
        "prayer", "salat", "fasting", "sawm", "hajj", "pilgrimage",
        "iman", "faith", "belief", "islam", "deen",
        "prophet", "rasul", "muhammad", "ali", "imam", "ahlu bayt",
        "hell", "fire", "paradise", "jannah", "jahannam",
        "quran", "book", "revelation", "verse", "ayat",
        "mercy", "forgiveness", "pardon", "punishment", "reward",
        "death", "life", "hereafter", "akhirah", "day of judgment"
    ]

    # Look for important terms in Arabic
    for term in important_terms:
        if term in source_arabic.lower() or term in source_english.lower():
            source_keywords.append(term)
        if term in candidate_arabic.lower() or term in candidate_english.lower():
            candidate_keywords.append(term)

    # Check for conceptual similarity
    shared_concepts = set(source_keywords) & set(candidate_keywords)

    # Check topic tag overlap
    tag_overlap = len(set(candidate_tags) if candidate_tags else [])

    # Judgment criteria
    if arabic_overlap >= 0.60:
        return (
            "similar",
            "wording",
            f"Moderate Arabic wording overlap ({arabic_overlap:.1%})"
        )
    elif shared_concepts and arabic_overlap >= 0.30:
        return (
            "similar",
            "conceptual",
            f"Shared core concepts: {', '.join(list(shared_concepts)[:3])}"
        )
    elif tag_overlap >= 2 and arabic_overlap >= 0.20:
        return (
            "similar",
            "conceptual",
            f"Shared topic tags: {tag_overlap} tags overlap"
        )
    else:
        return (
            "rejected",
            "",
            f"Low similarity - Arabic overlap: {arabic_overlap:.1%}, shared concepts: {len(shared_concepts)}, tags: {tag_overlap}"
        )

def process_batch_file(batch_path, output_path):
    """Process a batch file and output similarity judgments."""

    with open(batch_path, 'r', encoding='utf-8') as f:
        batch_data = json.load(f)

    results = []

    # Check if this is the new format (single source with candidates)
    if "source_id" in batch_data and "candidates" in batch_data:
        # New format: single source hadith with multiple candidates
        source_id = batch_data["source_id"]
        source_arabic = batch_data["source_arabic"]
        source_english = batch_data["source_english"]

        judgments = []

        # Process each candidate
        for candidate in batch_data.get("candidates", []):
            candidate_id = candidate["id"]
            candidate_arabic = candidate["arabic"]
            candidate_english = candidate["english"]
            candidate_tags = candidate.get("tags", [])

            verdict, match_type, reason = judge_similarity(
                source_arabic, source_english,
                candidate_arabic, candidate_english,
                candidate_tags
            )

            judgments.append({
                "id": candidate_id,
                "verdict": verdict,
                "match_type": match_type,
                "reason": reason,
                "confidence": 0.9 if verdict == "similar" else 0.5
            })

        results.append({
            "source_id": source_id,
            "judgments": judgments
        })
    else:
        # Old format: array of entries
        for entry in batch_data.get("entries", []):
            source_id = entry["source_id"]
        source_id = entry["source_id"]
        source_arabic = entry["source_arabic"]
        source_english = entry["source_english"]

        judgments = []

        # Process each candidate
        for candidate in entry.get("uncached_candidates", []):
            candidate_id = candidate["id"]
            candidate_arabic = candidate["arabic"]
            candidate_english = candidate["english"]
            candidate_tags = candidate.get("tags", [])

            verdict, match_type, reason = judge_similarity(
                source_arabic, source_english,
                candidate_arabic, candidate_english,
                candidate_tags
            )

            judgments.append({
                "id": candidate_id,
                "verdict": verdict,
                "match_type": match_type,
                "reason": reason,
                "confidence": 0.9 if verdict == "similar" else 0.5
            })

        # Add pre-cached similar judgments
        for cached in entry.get("pre_cached_similar", []):
            judgments.append({
                "id": cached["id"],
                "verdict": "similar",
                "match_type": cached.get("match_type", ""),
                "reason": cached.get("reason", "Pre-cached auto-accepted"),
                "confidence": 1.0
            })

        results.append({
            "source_id": source_id,
            "judgments": judgments
        })

    # Write results
    output_data = {
        "results": results,
        "metadata": {
            "batch_file": str(batch_path),
            "total_entries": len(results),
            "total_judgments": sum(len(r["judgments"]) for r in results),
            "processed_by": "manual_judge"
        }
    }

    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(output_data, f, ensure_ascii=False, indent=2)

    print(f"Processed {len(results)} entries, {sum(len(r['judgments']) for r in results)} judgments")
    print(f"Results saved to: {output_path}")

    # Summary statistics
    similar_count = sum(
        1 for r in results
        for j in r["judgments"]
        if j["verdict"] == "similar"
    )
    rejected_count = sum(
        1 for r in results
        for j in r["judgments"]
        if j["verdict"] == "rejected"
    )

    print(f"Similar: {similar_count}, Rejected: {rejected_count}")

def main():
    parser = argparse.ArgumentParser(description='Manually judge hadith similarity for a batch file')
    parser.add_argument('batch_file', help='Path to batch file')
    parser.add_argument('--output', '-o', help='Output file path')

    args = parser.parse_args()

    batch_path = Path(args.batch_file)
    if not batch_path.exists():
        print(f"Error: Batch file not found: {batch_path}")
        sys.exit(1)

    output_path = Path(args.output) if args.output else Path(f"tmp/results_batch_{batch_path.stem}.json")
    output_path.parent.mkdir(parents=True, exist_ok=True)

    process_batch_file(batch_path, output_path)

if __name__ == "__main__":
    main()