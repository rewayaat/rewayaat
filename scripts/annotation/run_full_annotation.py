#!/usr/bin/env python3
"""Run the full annotation pipeline by spawning Claude Code sub-agents.

This script is a helper that prints the exact commands to run for each wave
of batch processing. The actual agent spawning is done by the Claude Code
session using the Agent tool.

Usage:
    python3 scripts/annotation/run_full_annotation.py              # show next 2 batches to process
    python3 scripts/annotation/run_full_annotation.py --mark-done batch_0001
    python3 scripts/annotation/run_full_annotation.py --mark-failed batch_0001
    python3 scripts/annotation/run_full_annotation.py --status
    python3 scripts/annotation/run_full_annotation.py --wave 5      # show next N batches (default 2)
"""

import argparse
import json
import os
import sys
from pathlib import Path

BATCH_DIR = Path("tmp/hadith-annotation/batches")
RESULTS_DIR = Path("tmp/hadith-annotation/results")
PROGRESS_FILE = Path("tmp/hadith-annotation/_progress.json")


def get_progress():
    if PROGRESS_FILE.exists():
        with open(PROGRESS_FILE) as f:
            return json.load(f)
    return {"processed": [], "failed": [], "total_done": 0}


def save_progress(progress):
    tmp = str(PROGRESS_FILE) + ".tmp"
    with open(tmp, "w") as f:
        json.dump(progress, f, indent=2)
    os.rename(tmp, str(PROGRESS_FILE))


def get_all_batches():
    return sorted(BATCH_DIR.glob("batch_*.jsonl"))


def get_next_unprocessed(n=2):
    """Return next N unprocessed batches."""
    progress = get_progress()
    done = set(progress["processed"] + progress["failed"])
    unprocessed = [b for b in get_all_batches() if b.name not in done]
    return unprocessed[:n]


def mark_done(batch_name):
    progress = get_progress()
    if batch_name not in progress["processed"]:
        progress["processed"].append(batch_name)
    if batch_name in progress.get("failed", []):
        progress["failed"].remove(batch_name)
    # Count hadith
    batch_file = BATCH_DIR / batch_name
    if batch_file.exists():
        count = sum(1 for _ in open(batch_file))
        progress["total_done"] = progress.get("total_done", 0) + count
    save_progress(progress)
    remaining = len(get_all_batches()) - len(progress["processed"]) - len(progress.get("failed", []))
    print(f"Done: {batch_name} | Total processed: {len(progress['processed'])} | Remaining: {remaining}")


def mark_failed(batch_name):
    progress = get_progress()
    if batch_name not in progress.get("failed", []):
        progress["failed"].append(batch_name)
    save_progress(progress)
    print(f"Failed: {batch_name}")


def show_status():
    all_batches = get_all_batches()
    progress = get_progress()
    processed = len(progress["processed"])
    failed = len(progress.get("failed", []))
    remaining = len(all_batches) - processed - failed

    # Check results dir too
    result_files = list(RESULTS_DIR.glob("batch_*_results.json"))

    print(f"=== Annotation Pipeline Status ===")
    print(f"Total batches: {len(all_batches)}")
    print(f"Processed (in progress file): {processed}")
    print(f"Result files on disk: {len(result_files)}")
    print(f"Failed: {failed}")
    print(f"Remaining: {remaining}")
    print(f"Hadith done: ~{progress.get('total_done', 0)}")
    if progress.get("failed"):
        print(f"Failed batches: {progress['failed'][:10]}")

    # Estimate time
    if processed > 0:
        rate = processed  # batches per session so far
        print(f"\nAt 2 agents/wave, ~{remaining // 2} waves remaining")


def print_wave(n=2):
    """Print the next N batches to process."""
    unprocessed = get_next_unprocessed(n)
    if not unprocessed:
        print("All batches processed!")
        return

    print(f"Next {len(unprocessed)} batches to process:")
    for b in unprocessed:
        count = sum(1 for _ in open(b))
        print(f"  {b.name} ({count} hadith)")
    print(f"\nTo mark done after processing:")
    for b in unprocessed:
        print(f"  python3 scripts/annotation/run_full_annotation.py --mark-done {b.name}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--status", action="store_true")
    parser.add_argument("--mark-done", metavar="BATCH")
    parser.add_argument("--mark-failed", metavar="BATCH")
    parser.add_argument("--wave", type=int, default=2, help="Number of batches per wave")
    args = parser.parse_args()

    RESULTS_DIR.mkdir(parents=True, exist_ok=True)

    if args.status:
        show_status()
    elif args.mark_done:
        mark_done(args.mark_done)
    elif args.mark_failed:
        mark_failed(args.mark_failed)
    else:
        print_wave(args.wave)
