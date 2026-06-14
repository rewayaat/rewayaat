#!/usr/bin/env python3
"""
Phase 2 Layer 3: Sub-agent resolution of deferred narrator profiles.

Reads the merge checkpoint, processes deferred ambiguous cases using Claude
sub-agents. Only merges when Claude is confident (high confidence).
Otherwise keeps profiles separate.

Usage:
    python3 scripts/merge_narrator_layer3.py                    # Full run
    python3 scripts/merge_narrator_layer3.py --max-batches 10   # Limited test run
    python3 scripts/merge_narrator_layer3.py --resume           # Resume from L3 checkpoint
"""

import json
import os
import sys
import re
import time
import unicodedata
import argparse
from collections import defaultdict
from datetime import datetime

# --- Normalization (identical to merge_narrator_profiles.py) ---

def normalize_arabic(raw: str) -> str:
    if not raw:
        return ""
    return (
        re.sub(r"[\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06ED]", "", str(raw))
        .replace("\u0640", " ")
        .replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
        .replace("ى", "ي").replace("ة", "ه").replace("ؤ", "و").replace("ئ", "ي")
        .replace("(", "").replace(")", "")
        .strip()
    )

BOOK_META = {
    "tusi":     {"name": "Rijal al-Tusi",              "author": "Muhammad ibn al-Hasan al-Tusi"},
    "duafa":    {"name": "Kitab al-Du'afa",            "author": "Ibn al-Ghada'iri"},
    "kashshi":  {"name": "Rijal al-Kashshi",           "author": "Muhammad ibn Umar al-Kashshi"},
    "fihrist":  {"name": "Fihrist al-Tusi",            "author": "Muhammad ibn al-Hasan al-Tusi"},
    "najashi":  {"name": "Rijal al-Najashi",           "author": "Ahmad ibn Ali al-Najashi"},
    "ardabili": {"name": "Jami' al-Ruwat",             "author": "Ardabili"},
    "khoei":    {"name": "Mu'jam Rijal al-Hadith",     "author": "Ayatollah Khoei"},
    "mamaqani": {"name": "Tanqih al-Maqal",            "author": "Abdullah Mamaqani"},
}

L3_CHECKPOINT = "tmp/narrators_layer3_checkpoint.json"
L3_REVIEW = "tmp/narrator_review_queue.jsonl"
MERGED_FILE = "tmp/narrators_merged.json"

BATCH_SIZE = 10  # pairs per Claude call


def build_pair_text(d: dict, profiles: list[dict]) -> str:
    """Build comparison text for one deferred profile."""
    ps = d["profile_summary"]
    top_candidates = d["candidates"][:3]

    candidates_text = ""
    for cid, score, reasons in top_candidates:
        mp = profiles[cid]
        sources = [s["book"] for s in mp.get("contributing_sources", [])]
        cities = [v["value"] for v in mp.get("city_or_tribe_values", [])]
        gens = [v["value"] for v in mp.get("generation_values", [])]
        kunyahs = [v["value"] for v in mp.get("kunyah_arabic_sources", [])]
        nf = [n["name"] for n in mp.get("narrated_from", [])[:5]]
        nt = [n["name"] for n in mp.get("narrated_to", [])[:5]]

        candidates_text += (
            f"  B{cid} (from {sources}): {mp['primary_arabic_name']} | "
            f"kunyah: {kunyahs} | city: {cities} | gen: {gens} | "
            f"teachers: {nf} | students: {nt} | "
            f"L2 score: {score} ({', '.join(reasons)})\n"
        )

    return (
        f"Pair {d['new_id']}:\n"
        f"A (new): {ps['name']} | kunyah: {ps['kunyah']} | "
        f"city: {ps['city']} | gen: {ps['generation']}\n"
        f"Candidates:\n{candidates_text}"
    )


def call_claude(client, pairs_text: str) -> list[dict]:
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
        '[{"pair_id": <new_id>, "same_person": true/false, '
        '"merge_with_merged_id": <id or null>, '
        '"confidence": "high"/"medium"/"low", '
        '"reasoning": "brief explanation"}]\n\n'
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


def merge_deferred_into(profiles: list[dict], new_id: int, target_id: int, book_slug: str):
    """Merge the deferred profile (new_id) into target merged profile (target_id)."""
    src = profiles[new_id]
    tgt = profiles[target_id]

    pages = src.get("source_pages", [])
    if isinstance(pages, (int, float)):
        pages = [int(pages)]

    # Move source assessments
    for sa in (src.get("source_assessments") or []):
        entry = {
            "source_name": sa.get("source_name", BOOK_META.get(book_slug, {}).get("name", "")),
            "author": sa.get("author", BOOK_META.get(book_slug, {}).get("author", "")),
            "assessment_ar": sa.get("assessment_ar"),
            "assessment_en": sa.get("assessment_en"),
            "source_book": book_slug,
            "source_pages": pages,
        }
        tgt["source_assessments"].append(entry)

    # Move reliability grades
    for g in src.get("reliability_grades", []):
        tgt["reliability_grades"].append(g)

    # Move aliases (dedup)
    existing_ar = {a["name"] for a in tgt.get("arabic_aliases", [])}
    for a in src.get("arabic_aliases", []):
        if a["name"] not in existing_ar:
            tgt["arabic_aliases"].append(a)
            existing_ar.add(a["name"])

    existing_en = {a["name"] for a in tgt.get("english_aliases", [])}
    for a in src.get("english_aliases", []):
        if a["name"] not in existing_en:
            tgt["english_aliases"].append(a)
            existing_en.add(a["name"])

    # Move titles
    existing_titles = {t["title"] for t in tgt.get("titles", [])}
    for t in src.get("titles", []):
        if t["title"] not in existing_titles:
            tgt["titles"].append(t)
            existing_titles.add(t["title"])

    # Move biographical values
    for field in ["city_or_tribe_values", "generation_values", "death_year_hijri_values"]:
        for v in src.get(field, []):
            tgt[field].append(v)

    # Move narrated_from/to
    existing_nf = {n["name"] for n in tgt.get("narrated_from", [])}
    for n in src.get("narrated_from", []):
        if n["name"] not in existing_nf:
            tgt["narrated_from"].append(n)
            existing_nf.add(n["name"])

    existing_nt = {n["name"] for n in tgt.get("narrated_to", [])}
    for n in src.get("narrated_to", []):
        if n["name"] not in existing_nt:
            tgt["narrated_to"].append(n)
            existing_nt.add(n["name"])

    # Move kunyah sources
    for k in src.get("kunyah_arabic_sources", []):
        tgt["kunyah_arabic_sources"].append(k)

    # Move doubtful reasons
    for r in src.get("doubtful_reasons", []):
        tgt["doubtful_reasons"].append(r)

    # Move notes
    for n in src.get("notes", []):
        tgt["notes"].append(n)

    # Move contributing sources
    for cs in src.get("contributing_sources", []):
        tgt["contributing_sources"].append(cs)

    # Mark source profile as merged (null it out)
    src["_merged_into"] = target_id


def main():
    parser = argparse.ArgumentParser(description="Layer 3: sub-agent narrator merge")
    parser.add_argument("--resume", action="store_true", help="Resume from L3 checkpoint")
    parser.add_argument("--max-batches", type=int, default=None, help="Max batches to process")
    parser.add_argument("--batch-size", type=int, default=BATCH_SIZE, help="Pairs per batch")
    args = parser.parse_args()

    import anthropic
    client = anthropic.Anthropic()

    # Load the merged profiles
    print("Loading merged profiles...")
    with open(MERGED_FILE, "r", encoding="utf-8") as f:
        profiles = json.load(f)
    print(f"  {len(profiles)} profiles loaded")

    # Load deferred cases from the merge checkpoint
    cp_path = "tmp/narrators_merge_checkpoint.json"
    if not os.path.exists(cp_path):
        print("ERROR: No merge checkpoint found. Run merge_narrator_profiles.py first.")
        sys.exit(1)

    print("Loading deferred cases...")
    with open(cp_path, "r", encoding="utf-8") as f:
        cp = json.load(f)
    deferred = cp.get("deferred", [])
    print(f"  {len(deferred)} deferred cases")

    # L3 checkpoint
    l3_start = 0
    stats = {"batches": 0, "merges": 0, "kept_separate": 0, "low_confidence": 0}
    merge_decisions = []  # lightweight: just {new_id, target_id, book_slug}

    if args.resume and os.path.exists(L3_CHECKPOINT):
        with open(L3_CHECKPOINT, "r", encoding="utf-8") as f:
            l3cp = json.load(f)
        l3_start = l3cp.get("next_offset", 0)
        stats = l3cp.get("stats", stats)
        # Replay previous merge decisions on the profiles in memory
        for decision in l3cp.get("merge_decisions", []):
            new_id = decision["new_id"]
            target_id = decision["target_id"]
            book_slug = decision.get("book_slug", "unknown")
            if new_id < len(profiles) and target_id < len(profiles):
                merge_deferred_into(profiles, new_id, target_id, book_slug)
        print(f"  Resuming from offset {l3_start} ({len(l3cp.get('merge_decisions', []))} prior merges replayed)")

    # Process in batches
    total = len(deferred)
    review_entries = []

    for offset in range(l3_start, total, args.batch_size):
        if args.max_batches and stats["batches"] >= args.max_batches:
            break

        batch = deferred[offset:offset + args.batch_size]
        pairs_text = "\n".join(build_pair_text(d, profiles) for d in batch)

        print(f"\nBatch {stats['batches'] + 1}: pairs {offset}-{offset + len(batch) - 1} of {total}")

        try:
            judgments = call_claude(client, pairs_text)
        except Exception as e:
            print(f"  API error: {e}")
            time.sleep(5)
            continue

        if not judgments:
            print("  No valid response, skipping batch")
            continue

        # Process judgments
        jmap = {int(j.get("pair_id", 0)): j for j in judgments}

        for d in batch:
            new_id = d["new_id"]
            j = jmap.get(new_id)

            if j is None:
                print(f"  #{new_id}: no judgment returned, keeping separate")
                stats["kept_separate"] += 1
                continue

            confidence = j.get("confidence", "low")
            same = j.get("same_person", False)
            merge_id = j.get("merge_with_merged_id")
            if merge_id is not None:
                # Handle "B122" or "122" format
                merge_id = int(re.sub(r'[^0-9]', '', str(merge_id)))
            reason = j.get("reasoning", "")

            if same and confidence == "high" and merge_id is not None:
                # High confidence merge
                merge_id = int(merge_id)
                book_slug = profiles[new_id].get("contributing_sources", [{}])[0].get("book", "unknown")
                merge_deferred_into(profiles, new_id, merge_id, book_slug)
                merge_decisions.append({"new_id": new_id, "target_id": merge_id, "book_slug": book_slug})
                print(f"  #{new_id} → merged into #{merge_id} (HIGH confidence): {reason[:80]}")
                stats["merges"] += 1
            elif same and confidence == "medium" and merge_id is not None:
                merge_id = int(merge_id)
                # Medium confidence — keep separate but add to review queue
                review_entries.append({
                    "new_id": new_id,
                    "merge_candidate": merge_id,
                    "confidence": confidence,
                    "reasoning": reason,
                    "name": d["profile_summary"]["name"],
                })
                stats["kept_separate"] += 1
                print(f"  #{new_id}: medium confidence merge with #{merge_id} — added to review")
            else:
                # Different person or low confidence — keep separate
                if confidence == "low":
                    stats["low_confidence"] += 1
                    review_entries.append({
                        "new_id": new_id,
                        "merge_candidate": merge_id,
                        "confidence": confidence,
                        "reasoning": reason,
                        "name": d["profile_summary"]["name"],
                    })
                else:
                    stats["kept_separate"] += 1
                print(f"  #{new_id}: kept separate ({confidence}): {reason[:80]}")

        stats["batches"] += 1

        # Save L3 checkpoint every 5 batches (lightweight — decisions only, no profiles)
        if stats["batches"] % 5 == 0:
            print(f"  Saving checkpoint (batches: {stats['batches']}, merges: {stats['merges']})...")
            with open(L3_CHECKPOINT, "w", encoding="utf-8") as f:
                json.dump({
                    "next_offset": offset + args.batch_size,
                    "stats": stats,
                    "merge_decisions": merge_decisions,
                }, f, ensure_ascii=False)

            # Append review entries
            if review_entries:
                with open(L3_REVIEW, "a", encoding="utf-8") as f:
                    for entry in review_entries:
                        f.write(json.dumps(entry, ensure_ascii=False) + "\n")
                review_entries = []

        time.sleep(1)  # rate limit

    # Final write
    print(f"\n{'='*50}")
    print("L3 COMPLETE")
    print(f"{'='*50}")
    print(f"Batches processed: {stats['batches']}")
    print(f"High-confidence merges: {stats['merges']}")
    print(f"Kept separate: {stats['kept_separate']}")
    print(f"Low confidence (review): {stats['low_confidence']}")

    # Remove merged profiles and reindex
    final_profiles = [p for p in profiles if not p.get("_merged_into")]
    for p in final_profiles:
        p.pop("_merged_into", None)

    print(f"\nWriting {len(final_profiles)} profiles to {MERGED_FILE}...")
    with open(MERGED_FILE, "w", encoding="utf-8") as f:
        json.dump(final_profiles, f, ensure_ascii=False, indent=2)

    # Write remaining review entries
    if review_entries:
        with open(L3_REVIEW, "a", encoding="utf-8") as f:
            for entry in review_entries:
                f.write(json.dumps(entry, ensure_ascii=False) + "\n")

    # Write stats
    stats["final_profiles"] = len(final_profiles)
    stats["completed_at"] = datetime.now().isoformat()
    with open("tmp/narrators_merge_stats.json", "w", encoding="utf-8") as f:
        json.dump(stats, f, ensure_ascii=False, indent=2)

    # Clean up checkpoints
    for cp in [L3_CHECKPOINT, "tmp/narrators_merge_checkpoint.json"]:
        if os.path.exists(cp):
            os.remove(cp)

    print(f"\nDone! {len(final_profiles)} final merged profiles.")


if __name__ == "__main__":
    main()
