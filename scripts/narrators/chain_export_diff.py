#!/usr/bin/env python3
"""What a change to the segmenter does to every chain in the corpus.

The matn/isnād split is the one piece of this system every hadith card depends on, and the
chain links are offsets into what it produces: move a boundary and a link moves with it. So
a change to `HadithDisplaySegmenter` is not something to judge by the narration that
prompted it. This re-exports all 32,519 chains and diffs them against the file the pipeline
is currently built on, and says exactly how many gained a chain, lost one, or had one
reshaped.

    mvn -o -q dependency:build-classpath -Dmdep.outputFile=/tmp/rewayaat-narr.cp
    REWAYAAT_INDEX=rewayaat_hadith CHAIN_EXPORT_OUT=/tmp/chains_rerun.jsonl \
      java -cp "target/classes:$(cat /tmp/rewayaat-narr.cp)" com.rewayaat.tools.ChainExportTool
    python3 scripts/narrators/chain_export_diff.py /tmp/chains_rerun.jsonl

With no change in place it prints 32,519 identical, which is the control worth running
first: it says the baseline on disk is the baseline the code produces.
"""
import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = os.path.normpath(os.path.join(HERE, '..', '..', 'tmp', 'narrators_chains',
                                     'chains_served.jsonl'))


def read(path):
    chains = {}
    with open(path) as handle:
        for line in handle:
            row = json.loads(line)
            chains[row["id"]] = (row.get("chain_ar") or "", row.get("chain_en") or "")
    return chains


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("rerun", help="the newly exported chains")
    parser.add_argument("--base", default=BASE, help="the chains the pipeline is built on")
    parser.add_argument("--show", type=int, default=6, help="print this many changed chains")
    args = parser.parse_args()

    base, rerun = read(args.base), read(args.rerun)
    print(f"  {len(base):,} baseline, {len(rerun):,} re-exported")
    missing = set(base) - set(rerun)
    if missing:
        print(f"  {len(missing):,} narrations the re-export does not hold")

    counts = {"identical": 0, "gained Arabic": 0, "lost Arabic": 0, "Arabic reshaped": 0,
              "gained English": 0, "lost English": 0, "English reshaped": 0}
    shown = []
    for hadith, (was_ar, was_en) in base.items():
        now_ar, now_en = rerun.get(hadith, ("", ""))
        if (was_ar, was_en) == (now_ar, now_en):
            counts["identical"] += 1
            continue
        for was, now, language in ((was_ar, now_ar, "Arabic"), (was_en, now_en, "English")):
            if was == now:
                continue
            if not was.strip() and now.strip():
                counts[f"gained {language}"] += 1
            elif was.strip() and not now.strip():
                counts[f"lost {language}"] += 1
            else:
                counts[f"{language} reshaped"] += 1
            if len(shown) < args.show:
                shown.append((hadith, language, was[:90], now[:90]))

    for label, count in counts.items():
        if count:
            print(f"  {count:>7,}  {label}")
    for hadith, language, was, now in shown:
        print(f"\n  {hadith}  ({language})")
        print(f"    was: «{was}»")
        print(f"    now: «{now}»")
    # A change that loses chains is a regression whatever it gains, so say so loudly.
    lost = counts["lost Arabic"] + counts["lost English"]
    if lost:
        print(f"\n  {lost:,} narrations lost a chain they had. Read those before shipping.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
