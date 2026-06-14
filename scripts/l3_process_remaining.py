#!/usr/bin/env python3
"""
Process remaining L3 narrator merge batches directly via Anthropic API.
Reads batch files from tmp/l3_sub/, calls Claude to judge each pair,
appends decisions to tmp/narrators_l3_decisions.json.
"""

import json
import glob
import os
import sys
import time
import random
from datetime import datetime

DECISIONS_FILE = "tmp/narrators_l3_decisions.json"
BATCH_DIR = "tmp/l3_sub"
BATCH_SIZE = 10  # pairs per API call


def build_pair_text(pair: dict) -> str:
    """Build comparison text for one pair."""
    candidates_text = ""
    for c in pair.get("candidates", [])[:3]:
        candidates_text += (
            f"  B{c['id']} (from {c.get('sources', [])}): {c['name']} | "
            f"kunyah: {c.get('kunyah', 'N/A')} | city: {c.get('cities', [])} | "
            f"gen: {c.get('gens', [])} | "
            f"teachers: {c.get('teachers', [])[:5]} | "
            f"students: {c.get('students', [])[:5]} | "
            f"L2 score: {c.get('l2_score', 0)} ({', '.join(c.get('l2_reasons', []))})\n"
        )

    return (
        f"Pair {pair['pair_id']}:\n"
        f"A (new): {pair['name']} | kunyah: {pair.get('kunyah', 'N/A')} | "
        f"city: {pair.get('city', 'N/A')} | gen: {pair.get('generation', 'N/A')}\n"
        f"Candidates:\n{candidates_text}"
    )


def call_claude(client, pairs_text: str) -> list:
    """Send batch to Claude and parse judgments."""
    prompt = (
        "You are an expert in Shia Hadith narrator biography (Ilm al-Rijal).\n\n"
        "For each pair, judge whether Profile A is the SAME person as one of the candidates (B), "
        "or a DIFFERENT person.\n\n"
        "IMPORTANT RULES:\n"
        "- Default to DIFFERENT unless evidence clearly supports same person.\n"
        "- A shared kunyah alone does NOT confirm same person (e.g. 'أبو جعفر' is extremely common).\n"
        "- Overlapping teacher/student networks are the strongest evidence.\n"
        "- Same city + same generation is moderate evidence.\n"
        "- 'محمد بن علي' can be many different people — be conservative.\n\n"
        'Respond with ONLY a JSON array:\n'
        '[{"pair_id": <int>, "merge_with_id": <id or null>, '
        '"confidence": "high"/"medium"/"low", '
        '"reasoning": "brief explanation"}]\n\n'
        "Only set merge_with_id when confidence is high. "
        "For medium/low confidence or different person, set merge_with_id to null.\n\n"
        + pairs_text
    )

    response = client.messages.create(
        model="claude-sonnet-4-20250514",
        max_tokens=4000,
        messages=[{"role": "user", "content": prompt}]
    )

    text = response.content[0].text
    start = text.find("[")
    end = text.rfind("]") + 1
    if start >= 0 and end > start:
        return json.loads(text[start:end])
    return []


def save_decisions(decisions):
    """Save decisions to file."""
    with open(DECISIONS_FILE, "w", encoding="utf-8") as f:
        json.dump(decisions, f, ensure_ascii=False)


def main():
    import anthropic
    client = anthropic.Anthropic()

    # Load existing decisions
    with open(DECISIONS_FILE, "r", encoding="utf-8") as f:
        decisions = json.load(f)
    decided_ids = {d["pair_id"] for d in decisions}
    print(f"Loaded {len(decisions)} existing decisions ({len(decided_ids)} unique pairs)")

    # Find remaining batch files and undecided pairs
    all_batches = sorted(glob.glob(f"{BATCH_DIR}/batch_*.json"))
    remaining = []
    for path in all_batches:
        with open(path) as f:
            batch = json.load(f)
        undecided = [p for p in batch if p["pair_id"] not in decided_ids]
        if undecided:
            remaining.append((path, undecided))

    total_pairs = sum(len(u) for _, u in remaining)
    print(f"{len(remaining)} batch files with {total_pairs} undecided pairs remaining")
    print()

    stats = {"processed": 0, "merges": 0, "kept_separate": 0, "errors": 0}

    # Process in sub-batches of BATCH_SIZE pairs
    all_undecided = []
    for path, pairs in remaining:
        for p in pairs:
            all_undecided.append((path, p))

    for i in range(0, len(all_undecided), BATCH_SIZE):
        chunk = all_undecided[i:i + BATCH_SIZE]
        batch_num = i // BATCH_SIZE + 1
        total_batches = (len(all_undecided) + BATCH_SIZE - 1) // BATCH_SIZE

        pairs_text = "\n".join(build_pair_text(p) for _, p in chunk)

        print(f"Batch {batch_num}/{total_batches}: pairs {chunk[0][1]['pair_id']}-{chunk[-1][1]['pair_id']} ({len(chunk)} pairs)")

        max_retries = 3
        for attempt in range(max_retries):
            try:
                judgments = call_claude(client, pairs_text)
                break
            except Exception as e:
                if attempt < max_retries - 1:
                    wait = 10 * (attempt + 1) + random.uniform(0, 5)
                    print(f"  API error (attempt {attempt+1}): {e}")
                    print(f"  Retrying in {wait:.0f}s...")
                    time.sleep(wait)
                else:
                    print(f"  FAILED after {max_retries} attempts: {e}")
                    judgments = []
                    stats["errors"] += 1

        if not judgments:
            print("  No valid response, skipping")
            continue

        # Process judgments
        jmap = {int(j.get("pair_id", 0)): j for j in judgments}

        batch_merges = 0
        for _, pair in chunk:
            pid = pair["pair_id"]
            j = jmap.get(pid)

            if j is None:
                # No judgment — keep separate
                decisions.append({"pair_id": pid, "merge_with_id": None, "confidence": "no_response"})
                stats["kept_separate"] += 1
                continue

            merge_id = j.get("merge_with_id")
            if merge_id is not None:
                merge_id = int(merge_id) if isinstance(merge_id, (int, float)) else int(str(merge_id).replace("B", ""))
            confidence = j.get("confidence", "low")

            if merge_id is not None and confidence == "high":
                decisions.append({"pair_id": pid, "merge_with_id": merge_id, "confidence": confidence})
                batch_merges += 1
                stats["merges"] += 1
            else:
                decisions.append({"pair_id": pid, "merge_with_id": None, "confidence": confidence})
                stats["kept_separate"] += 1

        stats["processed"] += len(chunk)
        print(f"  {batch_merges} merges, {len(chunk) - batch_merges} kept separate")

        # Save every 5 batches
        if batch_num % 5 == 0:
            save_decisions(decisions)
            total_merges = sum(1 for d in decisions if d.get("merge_with_id") is not None)
            print(f"  [Checkpoint] {len(decisions)} total decisions, {total_merges} merges. Saved.")

        # Rate limit
        time.sleep(1)

    # Final save
    save_decisions(decisions)
    total_merges = sum(1 for d in decisions if d.get("merge_with_id") is not None)

    print(f"\n{'='*50}")
    print("L3 BATCH PROCESSING COMPLETE")
    print(f"{'='*50}")
    print(f"Pairs processed this run: {stats['processed']}")
    print(f"  Merges: {stats['merges']}")
    print(f"  Kept separate: {stats['kept_separate']}")
    print(f"  Errors: {stats['errors']}")
    print(f"\nTotal decisions: {len(decisions)}")
    print(f"Total merges: {total_merges}")
    print(f"Saved to {DECISIONS_FILE}")


if __name__ == "__main__":
    main()
