#!/usr/bin/env python3
"""Merge excerpt input+output batches and update commentary_text in ES with <em> tags."""

import json
import glob
import sys
from collections import defaultdict
from elasticsearch import Elasticsearch, helpers

ES_HOST = "http://localhost:9200"
INDEX = "rewayaat_quranic_light_filtered"
INPUTS_DIR = "tmp/qlight-excerpt-inputs"
OUTPUTS_DIR = "tmp/qlight-excerpts"


def load_all_batches():
    """Load all input and output batches, return dict keyed by hadith_id."""
    # Load inputs
    inputs = {}  # hadith_id -> list of candidates (with full metadata)
    input_files = sorted(glob.glob(f"{INPUTS_DIR}/batch_*.jsonl"))
    skipped_input = 0
    print(f"Loading {len(input_files)} input batches...")
    for f in input_files:
        with open(f) as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    d = json.loads(line)
                except json.JSONDecodeError:
                    skipped_input += 1
                    continue
                hid = d["hadith_id"]
                inputs[hid] = d.get("candidates", [])

    # Load outputs
    outputs = {}  # hadith_id -> list of {verse_key, snippets[{index, highlight_excerpt}]}
    output_files = sorted(glob.glob(f"{OUTPUTS_DIR}/batch_*.jsonl"))
    skipped_output = 0
    print(f"Loading {len(output_files)} output batches...")
    for f in output_files:
        with open(f) as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    d = json.loads(line)
                except json.JSONDecodeError:
                    skipped_output += 1
                    continue
                hid = d["hadith_id"]
                outputs[hid] = d.get("candidates", [])

    if skipped_input:
        print(f"  Skipped {skipped_input} bad input lines")
    if skipped_output:
        print(f"  Skipped {skipped_output} bad output lines")
    print(f"Loaded {len(inputs)} hadith inputs, {len(outputs)} hadith outputs")
    return inputs, outputs


def merge_hadith(input_candidates, output_candidates):
    """Merge output <em> tags into input commentary_text. Returns updated candidates list."""
    # Build lookup: verse_key -> list of {index, highlight_excerpt}
    out_by_verse = {}
    for oc in output_candidates:
        vk = oc.get("verse_key", "")
        snippets = oc.get("snippets", [])
        out_by_verse[vk] = {s["index"]: s["highlight_excerpt"] for s in snippets}

    updated = []
    for ic in input_candidates:
        vk = ic.get("verse_key", "")
        out_snippets = out_by_verse.get(vk, {})
        new_snippets = []
        for idx, ts in enumerate(ic.get("tafsir_snippets", [])):
            if idx in out_snippets:
                ts = dict(ts)  # copy
                ts["commentary_text"] = out_snippets[idx]
            new_snippets.append(ts)
        ic = dict(ic)
        ic["tafsir_snippets"] = new_snippets
        updated.append(ic)
    return updated


def build_updates(inputs, outputs):
    """Build ES bulk update actions for all hadith with excerpt data."""
    # For each hadith, we need to update the candidates array's tafsir_snippets
    # We'll read the full doc from ES, merge, and update
    es = Elasticsearch(ES_HOST)

    # First, collect all hadith_ids that have output data
    hadith_ids = list(outputs.keys())
    print(f"Preparing updates for {len(hadith_ids)} hadith...")

    # Build merged data
    merged = {}
    for hid in hadith_ids:
        if hid in inputs:
            merged[hid] = merge_hadith(inputs[hid], outputs[hid])

    # Now fetch from ES and update
    actions = []
    batch_size = 500
    total = len(merged)
    processed = 0
    updated = 0

    for i in range(0, total, batch_size):
        batch_ids = list(merged.keys())[i:i + batch_size]

        # Multi-get
        try:
            docs = es.mget(index=INDEX, body={"ids": batch_ids})
        except Exception as e:
            print(f"Error fetching batch {i}: {e}")
            continue

        for doc in docs.get("docs", []):
            processed += 1
            if not doc.get("found"):
                continue

            hid = doc["_id"]
            if hid not in merged:
                continue

            source = doc["_source"]
            es_candidates = source.get("candidates", [])
            excerpt_candidates = merged[hid]

            # Build lookup: verse_key -> {snippet_index: commentary_text_with_em}
            excerpt_by_verse = {}
            for ec in excerpt_candidates:
                vk = ec.get("verse_key", "")
                snippet_map = {}
                for idx, s in enumerate(ec.get("tafsir_snippets", [])):
                    snippet_map[idx] = s.get("commentary_text", "")
                excerpt_by_verse[vk] = snippet_map

            # Update ES candidates in place
            changed = False
            for es_c in es_candidates:
                vk = es_c.get("verse_key", "")
                if vk not in excerpt_by_verse:
                    continue
                snippet_map = excerpt_by_verse[vk]
                ts_list = es_c.get("tafsir_snippets", [])
                for idx, ts in enumerate(ts_list):
                    if idx in snippet_map and snippet_map[idx]:
                        ts["commentary_text"] = snippet_map[idx]
                        changed = True

            if changed:
                actions.append({
                    "_op_type": "update",
                    "_index": INDEX,
                    "_id": hid,
                    "doc": {"candidates": es_candidates}
                })
                updated += 1

        if processed % 5000 < batch_size:
            print(f"  Processed {processed}/{total}, {updated} to update...")

    return actions, updated


def main():
    dry_run = "--dry-run" in sys.argv

    inputs, outputs = load_all_batches()

    actions, updated = build_updates(inputs, outputs)
    print(f"\nTotal updates to apply: {updated}")

    if dry_run:
        print("DRY RUN - not applying updates")
        if actions:
            print(f"Sample action (first): id={actions[0]['_id']}")
        return

    if not actions:
        print("No updates to apply")
        return

    # Apply in bulk
    es = Elasticsearch(ES_HOST)
    print(f"Bulk updating {len(actions)} documents...")
    success, errors = helpers.bulk(es, actions, chunk_size=500, raise_on_error=False)

    failed = sum(1 for e in errors if e.get("update", {}).get("error"))
    print(f"Done: {success} succeeded, {failed} failed out of {len(actions)}")

    if failed > 0:
        print("Sample errors:")
        for e in errors[:5]:
            if e.get("update", {}).get("error"):
                print(f"  {e['update']['_id']}: {e['update']['error']}")


if __name__ == "__main__":
    main()
