#!/usr/bin/env python3
"""Orchestrate annotation agent pipeline.

Reads batch files from tmp/hadith-annotation/batches/, constructs agent prompts,
and prepares work for Claude Code sub-agents. Tracks progress in _progress.json.

Usage:
    python3 scripts/run_annotation_agents.py --status
    python3 scripts/run_annotation_agents.py --prepare-next 6   # show next 6 batches to process
    python3 scripts/run_annotation_agents.py --mark-done batch_0001
    python3 scripts/run_annotation_agents.py --mark-failed batch_0001
"""

import argparse
import json
import os
import sys
from pathlib import Path

BATCH_DIR = Path("tmp/hadith-annotation/batches")
RESULTS_DIR = Path("tmp/hadith-annotation/results")
PROGRESS_FILE = Path("tmp/hadith-annotation/_progress.json")

AGENT_PROMPT_TEMPLATE = """You are annotating hadith (Islamic narrations) with four types of annotations. Process each hadith below.

## Annotation Types

### 1. God References
Wrap ALL names and titles referring to God. For pronouns, be VERY careful — only wrap when the pronoun clearly refers to God Himself.
- Names/titles: use `<span class="god-ref" data-type="name">Name</span>` — e.g., Allah, God, the Almighty, the Most High, the Exalted, Lord (only when referring to God, not human masters)
- Pronouns: use `<span class="god-ref" data-type="pronoun">He</span>` — ONLY when the pronoun directly refers to God (e.g., "Allah, He is the Most High" — He = God). Do NOT wrap pronouns in passive/impersonal constructions about God's actions (e.g., "he will be rewarded" — he = the person, not God). When in doubt, do NOT wrap.
- Arabic: same tags for names of God (الله, الرحمن, etc.)
- Arabic: for detached pronouns ONLY when clearly referring to God (هو، هي، هم، نحن، أنتَ) — do NOT wrap attached pronouns
- COMMON MISTAKES TO AVOID:
  - "he will be rewarded" → "he" is the person, NOT God
  - "he said" about a narrator or Imam → NOT God
  - "his messenger" → "his" could be God, but the phrase is a formula — use judgment
  - Passive voice like "it was revealed" → no pronoun to wrap

### 2. Quranic Verse References
Detect Quranic verse references in the text — explicit quotes, explicit mentions of surahs/verses, and strong allusions/paraphrases.
- Wrap with: `<span class="quran-ref" data-surah="N" data-ayah="N" data-ref-type="quote|mention|allusion">referenced text</span>`
- `quote`: direct Quranic quotation in the hadith
- `mention`: explicit mention of a verse/surah by name or reference
- `allusion`: clear paraphrase of a specific Quranic passage
- You MUST identify the surah and ayah number. If you cannot identify them, do NOT annotate.

### 3. Translation Verification
Compare the Arabic original with the English translation. Flag ONLY major errors:
- Missing clauses or sentences
- Wrong meaning (not just different wording)
- Significant factual errors
- Do NOT flag: stylistic preferences, minor phrasing differences, honorific translations
- Store in translation_suggestions array with: original, suggested, reason

### 4. Footnotes
Identify any term, name, place, event, or concept that a reader learning about hadith would benefit from having explained.
- Wrap the exact word/phrase: `<span class="fn-word" data-id="N">term</span>`
- Number footnotes starting from 1 per hadith
- Store in footnotes array: {id, term, note}
- Keep notes concise (1-3 sentences)
- ONLY add footnotes when you are 100% certain of the explanation
- Good candidates: Islamic/Arabic terminology, historical figures, places, events, sects, legal concepts, cultural practices

## Output Format

Return a JSON array with one object per hadith. Each object:
```json
{{
  "hadith_id": "the_id",
  "english_annotated": "English text with inline god-ref, quran-ref, fn-word spans",
  "arabic_annotated": "Arabic text with inline god-ref, quran-ref, fn-word spans",
  "footnotes": [{{"id": 1, "term": "word", "note": "explanation"}}],
  "translation_suggestions": [{{"original": "...", "suggested": "...", "reason": "..."}}]
}}
```

IMPORTANT RULES:
- Preserve the original text exactly — only add HTML span tags around existing words, do not modify any text
- If a hadith has no annotations of a type, return the original text without changes for that field
- If no footnotes or translation suggestions, use empty arrays
- Return ONLY the JSON array, no other text

## Hadith Batch

{hadith_json}"""


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


def get_unprocessed_batches():
    progress = get_progress()
    done = set(progress["processed"] + progress["failed"])
    return [b for b in get_all_batches() if b.name not in done]


def show_status():
    all_batches = get_all_batches()
    progress = get_progress()
    processed = len(progress["processed"])
    failed = len(progress["failed"])
    remaining = len(all_batches) - processed - failed

    # Count results
    result_files = list(RESULTS_DIR.glob("batch_*_results.json"))
    total_hadith = 0
    total_fns = 0
    total_ts = 0
    total_god_refs = 0
    total_quran_refs = 0
    for rf in result_files:
        try:
            with open(rf) as f:
                data = json.load(f)
            if not isinstance(data, list):
                data = [data]
            for item in data:
                total_hadith += 1
                total_fns += len(item.get("footnotes", []))
                total_ts += len(item.get("translation_suggestions", []))
                ea = item.get("english_annotated", "")
                total_god_refs += ea.count('class="god-ref')
                total_quran_refs += ea.count('class="quran-ref')
        except Exception:
            pass

    print("=== Hadith Annotation Pipeline Status ===")
    print(f"  Total batches: {len(all_batches)}")
    print(f"  Processed: {processed}")
    print(f"  Failed: {failed}")
    print(f"  Remaining: {remaining}")
    print(f"  Result files: {len(result_files)}")
    print(f"  Hadith annotated: {total_hadith}")
    print(f"  God references: {total_god_refs}")
    print(f"  Quranic references: {total_quran_refs}")
    print(f"  Footnotes: {total_fns}")
    print(f"  Translation suggestions: {total_ts}")


def prepare_next(n=6):
    """Print the agent prompt for the next n unprocessed batches."""
    unprocessed = get_unprocessed_batches()[:n]
    if not unprocessed:
        print("All batches processed!")
        return

    print(f"Next {len(unprocessed)} batches to process:")
    for b in unprocessed:
        count = sum(1 for _ in open(b))
        print(f"  {b.name} ({count} hadith)")

    print(f"\nTo process these, use Claude Code Agent tool with the prompts below.\n")

    for b in unprocessed:
        records = []
        with open(b) as f:
            for line in f:
                records.append(json.loads(line))

        hadith_json = json.dumps(records, ensure_ascii=False, indent=2)
        prompt = AGENT_PROMPT_TEMPLATE.format(hadith_json=hadith_json)

        print(f"--- BATCH {b.name} ---")
        print(f"Prompt length: {len(prompt)} chars")
        print(f"Save prompt to: {RESULTS_DIR / b.name.replace('.jsonl', '_prompt.txt')}")
        print()

        # Save prompt to file for reference
        prompt_file = RESULTS_DIR / b.name.replace(".jsonl", "_prompt.txt")
        with open(prompt_file, "w") as f:
            f.write(prompt)


def mark_done(batch_name):
    progress = get_progress()
    if batch_name not in progress["processed"]:
        progress["processed"].append(batch_name)
    if batch_name in progress["failed"]:
        progress["failed"].remove(batch_name)

    # Count hadith in this batch
    batch_file = BATCH_DIR / batch_name
    if batch_file.exists():
        count = sum(1 for _ in open(batch_file))
        progress["total_done"] = progress.get("total_done", 0) + count

    save_checkpoint(progress)
    print(f"Marked {batch_name} as done. Total processed: {len(progress['processed'])}")


def mark_failed(batch_name):
    progress = get_progress()
    if batch_name not in progress["failed"]:
        progress["failed"].append(batch_name)
    save_checkpoint(progress)
    print(f"Marked {batch_name} as failed.")


def save_checkpoint(progress):
    tmp = str(PROGRESS_FILE) + ".tmp"
    with open(tmp, "w") as f:
        json.dump(progress, f, indent=2)
    os.rename(tmp, str(PROGRESS_FILE))


def main():
    parser = argparse.ArgumentParser(description="Orchestrate annotation agents")
    parser.add_argument("--status", action="store_true", help="Show pipeline status")
    parser.add_argument("--prepare-next", type=int, metavar="N",
                        help="Prepare prompts for next N batches")
    parser.add_argument("--mark-done", metavar="BATCH", help="Mark a batch as done")
    parser.add_argument("--mark-failed", metavar="BATCH", help="Mark a batch as failed")
    args = parser.parse_args()

    RESULTS_DIR.mkdir(parents=True, exist_ok=True)

    if args.status:
        show_status()
    elif args.prepare_next:
        prepare_next(args.prepare_next)
    elif args.mark_done:
        mark_done(args.mark_done)
    elif args.mark_failed:
        mark_failed(args.mark_failed)
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
