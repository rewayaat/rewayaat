#!/usr/bin/env python3
"""List the Layer 3 batches still needing a sub-agent, and print their prompts.

Layer 3 runs on Claude sub-agents rather than the Anthropic API, per the system's
no-external-LLM-APIs decision. There is no way to spawn those from a script, so this
prints ready-to-dispatch prompts and tracks which batches already have an answer — making
the run resumable across sessions and safe to interrupt.

Usage:
    python3 scripts/narrators/l3_dispatch.py                  # progress summary
    python3 scripts/narrators/l3_dispatch.py --next 8         # prompts for 8 pending batches
    python3 scripts/narrators/l3_dispatch.py --next 8 --kind group
"""

import argparse
import json
import os

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TMP = os.path.join(REPO, "tmp")
PROMPT_DOC = os.path.join(REPO, "scripts", "narrators", "l3_agent_prompt.md")

GROUP_BRIEF = (
    "This batch has {tasks} group task(s) covering {profiles} profiles. Read the Arabic "
    "quotations rather than partitioning on names alone.\n\n"
    "Every merged_id in each task must appear in exactly one cluster of your partition. "
    "Default to separate: a profile that shares only a common name with the others, and "
    "says nothing else about itself, belongs in its own cluster. Cite the evidence you "
    "used in `notes`.\n\n"
    "Report back two lines: clusters produced per task, and the strongest merge you made "
    "with its evidence."
)

PAIR_BRIEF = (
    "This batch has {tasks} pair task(s). For each, decide whether the `subject` is the "
    "same person as one of the `candidates`, or none of them.\n\n"
    "Default to separate. `merge_with` must be a merged_id the task actually offered as a "
    "candidate. Use confidence `low` freely — it is the correct answer when the sources "
    "are thin, and low-confidence decisions go to a human review queue rather than being "
    "applied. Cite the evidence in `reason`.\n\n"
    "Report back two lines: how many you merged versus kept separate, and the confidence "
    "distribution."
)

TEMPLATE = """You are resolving narrator identity for the Rewayaat Shia hadith corpus.

1. Read the instructions at {prompt_doc} — follow them exactly.
2. Read the batch file {batch_path}
3. Write your decisions to {output_path}

{brief}

Write only the JSON file."""


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--l3-dir", default=os.path.join(TMP, "narrators_l3"))
    parser.add_argument("--next", type=int, default=0,
                        help="print prompts for this many pending batches")
    parser.add_argument("--kind", choices=["group", "pair"],
                        help="restrict to one kind of task")
    args = parser.parse_args()

    l3_dir = os.path.realpath(args.l3_dir)
    with open(os.path.join(l3_dir, "manifest.json")) as handle:
        manifest = json.load(handle)

    batch_dir = os.path.join(l3_dir, "batches")
    output_dir = os.path.join(l3_dir, "outputs")
    entries = [b for b in manifest["batches"]
               if not args.kind or b["kind"] == args.kind]
    pending = [b for b in entries
               if not os.path.exists(os.path.join(output_dir, b["batch"]))]
    done = len(entries) - len(pending)

    for kind in ("group", "pair"):
        of_kind = [b for b in manifest["batches"] if b["kind"] == kind]
        answered = sum(1 for b in of_kind
                       if os.path.exists(os.path.join(output_dir, b["batch"])))
        if of_kind:
            print(f"{kind:6s} {answered:3d}/{len(of_kind):3d} batches answered, "
                  f"{sum(b['tasks'] for b in of_kind)} tasks")
    print(f"\n{done}/{len(entries)} selected batches answered, {len(pending)} pending")

    if not args.next:
        return
    print("\n" + "=" * 78)
    for batch in pending[:args.next]:
        brief = (GROUP_BRIEF if batch["kind"] == "group" else PAIR_BRIEF).format(
            tasks=batch["tasks"], profiles=batch["profiles"])
        print("\n--- " + batch["batch"] + " ---\n")
        print(TEMPLATE.format(
            prompt_doc=PROMPT_DOC,
            batch_path=os.path.join(batch_dir, batch["batch"]),
            output_path=os.path.join(output_dir, batch["batch"]),
            brief=brief,
        ))


if __name__ == "__main__":
    main()
