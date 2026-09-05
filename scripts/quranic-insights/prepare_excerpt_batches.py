#!/usr/bin/env python3
"""Prepare excerpt input batches by merging verdicts with original batch data.

For each batch, filters down to only the candidates/snippets that were kept in
the first-pass verdicts. Outputs a lighter file for the excerpt-highlight agents.
"""
import json
import os
from pathlib import Path

BATCHES_DIR = Path("tmp/qlight-batches")
VERDICTS_DIR = Path("tmp/qlight-verdicts")
OUTPUT_DIR = Path("tmp/qlight-excerpt-inputs")
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)


def load_verdict(batch_num):
    path = VERDICTS_DIR / f"batch_{batch_num:03d}.jsonl"
    if not path.exists():
        return None
    verdicts = {}
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            doc = json.loads(line)
            verdicts[doc["hadith_id"]] = doc
    return verdicts


def main():
    # Collect all batch numbers
    batch_files = sorted(BATCHES_DIR.glob("batch_*.jsonl"))
    total_batches = len(batch_files)
    batches_with_kept = 0
    total_hadith_with_kept = 0
    total_snippets_to_process = 0

    for batch_path in batch_files:
        batch_num_str = batch_path.stem.replace("batch_", "")
        batch_num = int(batch_num_str)

        verdicts = load_verdict(batch_num)
        if verdicts is None:
            continue

        # Read original batch
        hadiths = []
        with open(batch_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                hadiths.append(json.loads(line))

        # Filter to only kept candidates/snippets
        output_hadiths = []
        for hadith in hadiths:
            hid = hadith["hadith_id"]
            if hid not in verdicts:
                continue
            verdict = verdicts[hid]
            kept = verdict.get("candidates_to_keep", [])
            if not kept:
                continue

            # Build lookup: verse_key -> snippet_indices_to_keep
            kept_map = {}
            for c in kept:
                vk = c["verse_key"]
                kept_map[vk] = c.get("snippet_indices_to_keep")  # None = keep all

            # Filter candidates
            filtered_candidates = []
            for candidate in hadith.get("candidates", []):
                vk = candidate.get("verse_key", "")
                if vk not in kept_map:
                    continue
                snippet_indices = kept_map[vk]
                snippets = candidate.get("tafsir_snippets", [])
                if snippet_indices is not None:
                    snippets = [s for i, s in enumerate(snippets) if i in snippet_indices]
                else:
                    snippets = snippets  # keep all

                total_snippets_to_process += len(snippets)

                filtered_candidates.append({
                    "verse_key": vk,
                    "surah_name_english": candidate.get("surah_name_english", ""),
                    "text_english": candidate.get("text_english", ""),
                    "tafsir_snippets": snippets,
                })

            if filtered_candidates:
                output_hadiths.append({
                    "hadith_id": hid,
                    "hadith_english": hadith.get("hadith_english", ""),
                    "hadith_book": hadith.get("hadith_book", ""),
                    "candidates": filtered_candidates,
                })
                total_hadith_with_kept += 1

        if output_hadiths:
            out_path = OUTPUT_DIR / f"batch_{batch_num:03d}.jsonl"
            with open(out_path, "w", encoding="utf-8") as f:
                for h in output_hadiths:
                    f.write(json.dumps(h, ensure_ascii=False) + "\n")
            batches_with_kept += 1

    print(f"Total batches: {total_batches}")
    print(f"Batches with kept snippets: {batches_with_kept}")
    print(f"Hadiths with kept candidates: {total_hadith_with_kept}")
    print(f"Total snippets to process: {total_snippets_to_process}")


if __name__ == "__main__":
    main()
