#!/usr/bin/env python3
"""Process batch files one at a time using Claude sub-agents to identify Quranic verse references.

This script orchestrates the sub-agent pipeline:
1. Finds unprocessed batch files
2. For each batch, spawns a Claude sub-agent to identify Quranic references
3. Saves results alongside the batch files
4. Tracks progress

Usage:
    python3 scripts/annotation/run_quranic_ref_agents.py                 # Process all unprocessed batches
    python3 scripts/annotation/run_quranic_ref_agents.py --start 0 --end 10  # Process specific range
    python3 scripts/annotation/run_quranic_ref_agents.py --status         # Show progress
"""

import json
import os
import sys
import time
from pathlib import Path


BATCHES_DIR = Path("tmp/quranic-snippet-matches/batches")
RESULTS_DIR = Path("tmp/quranic-snippet-matches")
PROGRESS_FILE = RESULTS_DIR / "_progress.json"


def get_progress():
    if PROGRESS_FILE.exists():
        with open(PROGRESS_FILE) as f:
            return json.load(f)
    return {"processed": [], "failed": [], "total_refs_found": 0}


def save_progress(progress):
    with open(PROGRESS_FILE, "w") as f:
        json.dump(progress, f, indent=2)


def get_unprocessed_batches():
    all_batches = sorted(BATCHES_DIR.glob("batch_*.json"))
    progress = get_progress()
    processed_set = set(progress["processed"])
    return [b for b in all_batches if b.name not in processed_set]


def count_results():
    results = list(RESULTS_DIR.glob("batch_*_results.json"))
    total_refs = 0
    total_hadith_with_refs = 0
    for r in results:
        with open(r) as f:
            data = json.load(f)
            for h in data:
                if h.get("has_quranic_reference"):
                    total_hadith_with_refs += 1
                    total_refs += len(h.get("references", []))
    return len(results), total_hadith_with_refs, total_refs


def show_status():
    all_batches = sorted(BATCHES_DIR.glob("batch_*.json"))
    progress = get_progress()
    processed = len(progress["processed"])
    failed = len(progress["failed"])
    total = len(all_batches) - 1  # exclude _summary.json if it matches
    result_files, hadith_with_refs, total_refs = count_results()

    print(f"=== Quranic Reference Extraction Status ===")
    print(f"  Total batches: {len(all_batches)}")
    print(f"  Processed: {processed}")
    print(f"  Failed: {failed}")
    print(f"  Remaining: {len(all_batches) - processed}")
    print(f"  Result files: {result_files}")
    print(f"  Hadith with Quranic refs: {hadith_with_refs}")
    print(f"  Total Quranic references: {total_refs}")


if __name__ == "__main__":
    if "--status" in sys.argv:
        show_status()
        sys.exit(0)

    start = 0
    end = None
    if "--start" in sys.argv:
        start = int(sys.argv[sys.argv.index("--start") + 1])
    if "--end" in sys.argv:
        end = int(sys.argv[sys.argv.index("--end") + 1])

    unprocessed = get_unprocessed_batches()

    # Filter by range if specified
    if end is not None:
        unprocessed = [b for b in unprocessed
                       if start <= int(b.stem.split("_")[1]) < end]

    print(f"Batches to process: {len(unprocessed)}")
    print(f"Run: python3 scripts/annotation/run_quranic_ref_agents.py --status")
    print(f"\nTo process batches, use Claude Code sub-agents (Agent tool).")
    print(f"Each agent should read a batch file and output results.")
