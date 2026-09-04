#!/usr/bin/env python3
"""Apply all highlighting outputs (round 1 + round 2) to ES index.

Handles three output formats:
  Format A: hadith_id, tafsir_slug, highlights[{original, highlighted}]
  Format B: hadith_id, verse_key, tafsir_slug, commentary_text_highlighted
  Format C: hadith_id, snippets[{tafsir_slug, highlights[{original, highlighted}]}]

Only updates commentary_text_highlighted — never modifies commentary_text or other fields.
Skips snippets that already have <em> tags.

Usage:
    python3 scripts/quranic-insights/apply_highlights_to_prod.py
    python3 scripts/quranic-insights/apply_highlights_to_prod.py --dry-run
    ELASTICSEARCH_URL=http://host:port python3 scripts/quranic-insights/apply_highlights_to_prod.py
"""

import json
import os
import sys
from elasticsearch import Elasticsearch, helpers

ES_HOST = os.environ.get("ELASTICSEARCH_URL", "http://localhost:9200")
INDEX = "rewayaat_quranic_light_filtered"
DRY_RUN = "--dry-run" in sys.argv

ROUND1_DIR = "tmp/qlight-highlight-outputs"
ROUND2_DIR = "tmp/qlight-highlight-round2-outputs"


def load_highlights():
    """Load all highlight outputs into lookups."""
    # Key: (hadith_id, tafsir_slug) -> {original_text: highlighted_text}
    hl_lookup = {}
    # Key: (hadith_id, verse_key, tafsir_slug) -> full highlighted text
    full_hl_lookup = {}

    for input_dir in [ROUND1_DIR, ROUND2_DIR]:
        if not os.path.isdir(input_dir):
            print(f"  Skipping {input_dir} (not found)")
            continue

        file_count = 0
        for fn in sorted(os.listdir(input_dir)):
            if not fn.endswith(".jsonl"):
                continue
            file_count += 1
            with open(os.path.join(input_dir, fn), encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        d = json.loads(line)
                    except json.JSONDecodeError:
                        continue

                    hid = d.get("hadith_id", "")
                    if not hid:
                        continue

                    # Format B: full commentary_text_highlighted
                    if "commentary_text_highlighted" in d and "tafsir_slug" in d:
                        vk = d.get("verse_key", "")
                        slug = d["tafsir_slug"]
                        full_hl_lookup[(hid, vk, slug)] = d["commentary_text_highlighted"]

                    # Format A: hadith_id + tafsir_slug + highlights[]
                    elif "highlights" in d and "tafsir_slug" in d:
                        slug = d["tafsir_slug"]
                        key = (hid, slug)
                        if key not in hl_lookup:
                            hl_lookup[key] = {}
                        for h in d["highlights"]:
                            orig = h.get("original", "")
                            hl = h.get("highlighted", "")
                            if orig and hl and "<em>" in hl:
                                hl_lookup[key][orig] = hl

                    # Format C: hadith_id + snippets[]
                    elif "snippets" in d:
                        for snip in d.get("snippets", []):
                            slug = snip.get("tafsir_slug", "")
                            key = (hid, slug)
                            if key not in hl_lookup:
                                hl_lookup[key] = {}
                            for h in snip.get("highlights", []):
                                orig = h.get("original", "")
                                hl = h.get("highlighted", "")
                                if orig and hl and "<em>" in hl:
                                    hl_lookup[key][orig] = hl

        print(f"  Loaded from {input_dir}: {file_count} files")

    print(f"  Full-text lookup: {len(full_hl_lookup)} entries")
    print(f"  Fragment lookup: {len(hl_lookup)} entries")
    return hl_lookup, full_hl_lookup


def main():
    print(f"Apply Highlights to Production")
    print(f"  ES: {ES_HOST}")
    print(f"  Index: {INDEX}")
    print(f"  Dry run: {DRY_RUN}")

    es = Elasticsearch(ES_HOST)

    # Step 1: Load highlights
    print("\nStep 1: Loading highlight outputs...")
    hl_lookup, full_hl_lookup = load_highlights()

    # Step 2: Apply to ES
    print("\nStep 2: Scanning ES and applying highlights...")
    actions = []
    scanned = 0
    docs_updated = 0
    snippets_updated = 0
    already_hl = 0
    no_match = 0
    skipped_short = 0

    for hit in helpers.scan(es, index=INDEX, query={"query": {"match_all": {}}},
                            _source=["hadith_id", "candidates"], size=200, scroll="5m"):
        scanned += 1
        doc_id = hit["_id"]
        src = hit["_source"]
        hid = src.get("hadith_id", "")
        candidates = src.get("candidates", [])
        changed = False

        for c in candidates:
            vk = c.get("verse_key", "")
            for s in c.get("tafsir_snippets", []):
                ct = s.get("commentary_text", "")
                slug = s.get("tafsir_slug", "")
                existing_hl = s.get("commentary_text_highlighted", "")

                # Already highlighted
                if existing_hl and "<em>" in existing_hl:
                    already_hl += 1
                    continue

                if not ct or len(ct) < 30:
                    skipped_short += 1
                    continue

                new_hl = None

                # Try full-text lookup first (most reliable)
                key_full = (hid, vk, slug)
                if key_full in full_hl_lookup:
                    candidate_hl = full_hl_lookup[key_full]
                    if "<em>" in candidate_hl:
                        new_hl = candidate_hl

                # Try fragment lookup
                if new_hl is None:
                    key_frag = (hid, slug)
                    if key_frag in hl_lookup:
                        mappings = hl_lookup[key_frag]
                        new_hl = ct
                        applied = 0
                        # Sort by length descending to avoid partial replacements
                        for orig in sorted(mappings.keys(), key=len, reverse=True):
                            highlighted = mappings[orig]
                            if orig in new_hl and "<em>" not in orig:
                                new_hl = new_hl.replace(orig, highlighted, 1)
                                applied += 1
                        if applied == 0 or "<em>" not in new_hl:
                            new_hl = None

                if new_hl and "<em>" in new_hl:
                    s["commentary_text_highlighted"] = new_hl
                    changed = True
                    snippets_updated += 1
                else:
                    no_match += 1

        if changed:
            actions.append({
                "_op_type": "update",
                "_index": INDEX,
                "_id": doc_id,
                "doc": {"candidates": candidates},
            })
            docs_updated += 1

        if len(actions) >= 200:
            if not DRY_RUN:
                success, errors = helpers.bulk(es, actions, chunk_size=200, raise_on_error=False)
                failed = sum(1 for e in errors if e.get("update", {}).get("error"))
                print(f"  Bulk: {success} ok, {failed} errors ({scanned} scanned, {snippets_updated} snippets)")
            else:
                print(f"  [DRY RUN] Would update {len(actions)} docs ({scanned} scanned)")
            actions = []

    # Flush remaining
    if actions:
        if not DRY_RUN:
            success, errors = helpers.bulk(es, actions, chunk_size=200, raise_on_error=False)
            failed = sum(1 for e in errors if e.get("update", {}).get("error"))
            print(f"  Final bulk: {success} ok, {failed} errors")
        else:
            print(f"  [DRY RUN] Would update {len(actions)} docs")

    print(f"\nDone!")
    print(f"  Scanned: {scanned} docs")
    print(f"  Docs updated: {docs_updated}")
    print(f"  Snippets highlighted: {snippets_updated}")
    print(f"  Already highlighted (skipped): {already_hl}")
    print(f"  Too short (skipped): {skipped_short}")
    print(f"  No match found: {no_match}")


if __name__ == "__main__":
    main()
