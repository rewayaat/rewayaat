#!/usr/bin/env python3
"""
Apply all L3 merge decisions to tmp/narrators_merged.json.
Merges deferred profiles into their target profiles based on decisions.
"""

import json
import sys
import os
from datetime import datetime

MERGED_FILE = "tmp/narrators_merged.json"
DECISIONS_FILE = "tmp/narrators_l3_decisions.json"
CHECKPOINT_FILE = "tmp/narrators_merge_checkpoint.json"


def load_checkpoint():
    """Load deferred cases to map pair_id -> book_slug."""
    if not os.path.exists(CHECKPOINT_FILE):
        return {}
    with open(CHECKPOINT_FILE, "r", encoding="utf-8") as f:
        cp = json.load(f)
    # Map new_id -> deferred entry (for book_slug)
    return {d["new_id"]: d for d in cp.get("deferred", [])}


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


def merge_deferred_into(profiles, new_id, target_id, book_slug):
    """Merge profile new_id into target_id."""
    if new_id >= len(profiles) or target_id >= len(profiles):
        print(f"  SKIP: id out of range ({new_id} or {target_id})")
        return False

    src = profiles[new_id]
    tgt = profiles[target_id]

    if src is None or tgt is None:
        print(f"  SKIP: profile already merged or null ({new_id} -> {target_id})")
        return False

    if src.get("_merged_into") is not None:
        print(f"  SKIP: {new_id} already merged into {src['_merged_into']}")
        return False

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
        tgt.setdefault("source_assessments", []).append(entry)

    # Move reliability grades
    for g in src.get("reliability_grades", []):
        tgt.setdefault("reliability_grades", []).append(g)

    # Move aliases (dedup)
    existing_ar = {a["name"] for a in tgt.get("arabic_aliases", [])}
    for a in src.get("arabic_aliases", []):
        if a["name"] not in existing_ar:
            tgt.setdefault("arabic_aliases", []).append(a)
            existing_ar.add(a["name"])

    existing_en = {a["name"] for a in tgt.get("english_aliases", [])}
    for a in src.get("english_aliases", []):
        if a["name"] not in existing_en:
            tgt.setdefault("english_aliases", []).append(a)
            existing_en.add(a["name"])

    # Move titles
    existing_titles = {t["title"] for t in tgt.get("titles", [])}
    for t in src.get("titles", []):
        if t["title"] not in existing_titles:
            tgt.setdefault("titles", []).append(t)
            existing_titles.add(t["title"])

    # Move biographical values
    for field in ["city_or_tribe_values", "generation_values", "death_year_hijri_values"]:
        for v in src.get(field, []):
            tgt.setdefault(field, []).append(v)

    # Move narrated_from/to
    existing_nf = {n["name"] for n in tgt.get("narrated_from", [])}
    for n in src.get("narrated_from", []):
        if n["name"] not in existing_nf:
            tgt.setdefault("narrated_from", []).append(n)
            existing_nf.add(n["name"])

    existing_nt = {n["name"] for n in tgt.get("narrated_to", [])}
    for n in src.get("narrated_to", []):
        if n["name"] not in existing_nt:
            tgt.setdefault("narrated_to", []).append(n)
            existing_nt.add(n["name"])

    # Move kunyah sources
    for k in src.get("kunyah_arabic_sources", []):
        tgt.setdefault("kunyah_arabic_sources", []).append(k)

    # Move doubtful reasons
    for r in src.get("doubtful_reasons", []):
        tgt.setdefault("doubtful_reasons", []).append(r)

    # Move notes
    for n in src.get("notes", []):
        tgt.setdefault("notes", []).append(n)

    # Move contributing sources
    for cs in src.get("contributing_sources", []):
        tgt.setdefault("contributing_sources", []).append(cs)

    # Mark source profile as merged
    src["_merged_into"] = target_id
    return True


def main():
    print("Loading merged profiles...")
    with open(MERGED_FILE, "r", encoding="utf-8") as f:
        profiles = json.load(f)
    print(f"  {len(profiles)} profiles loaded")

    print("Loading L3 decisions...")
    with open(DECISIONS_FILE, "r", encoding="utf-8") as f:
        decisions = json.load(f)
    print(f"  {len(decisions)} decisions loaded")

    # Load checkpoint for book_slug mapping
    deferred_map = load_checkpoint()

    # Also try to get book_slug from profile's contributing_sources
    def get_book_slug(pair_id):
        if pair_id in deferred_map:
            d = deferred_map[pair_id]
            # Check if book info is stored
            if "book" in d:
                return d["book"]
        # Try from profile itself
        if pair_id < len(profiles) and profiles[pair_id]:
            cs = profiles[pair_id].get("contributing_sources", [])
            if cs:
                return cs[0].get("book", "unknown")
        return "unknown"

    # Filter to high-confidence merges only
    merges = [d for d in decisions
              if d.get("merge_with_id") is not None
              and d.get("confidence") == "high"]
    print(f"  {len(merges)} high-confidence merges to apply")

    # Apply merges
    applied = 0
    skipped = 0
    for d in merges:
        new_id = d["pair_id"]
        target_id = d["merge_with_id"]
        book_slug = get_book_slug(new_id)

        if merge_deferred_into(profiles, new_id, target_id, book_slug):
            applied += 1
        else:
            skipped += 1

    print(f"\nMerges applied: {applied}")
    print(f"Merges skipped: {skipped}")

    # Remove merged profiles
    final_profiles = [p for p in profiles if p is not None and not p.get("_merged_into")]
    for p in final_profiles:
        p.pop("_merged_into", None)

    removed = len(profiles) - len(final_profiles)
    print(f"Profiles removed (merged): {removed}")
    print(f"Final profile count: {len(final_profiles)}")

    # Write final file
    backup_path = MERGED_FILE + ".pre_l3_backup"
    if not os.path.exists(backup_path):
        print(f"\nBacking up original to {backup_path}...")
        os.rename(MERGED_FILE, backup_path)
        # Re-read from backup to write
        with open(backup_path, "r", encoding="utf-8") as f:
            pass  # already have profiles in memory

    print(f"Writing {len(final_profiles)} final profiles to {MERGED_FILE}...")
    with open(MERGED_FILE, "w", encoding="utf-8") as f:
        json.dump(final_profiles, f, ensure_ascii=False, indent=2)

    # Write stats
    stats = {
        "total_decisions": len(decisions),
        "merges_applied": applied,
        "merges_skipped": skipped,
        "profiles_before": len(profiles),
        "profiles_after": len(final_profiles),
        "profiles_removed": removed,
        "completed_at": datetime.now().isoformat(),
    }
    with open("tmp/narrators_l3_final_stats.json", "w", encoding="utf-8") as f:
        json.dump(stats, f, ensure_ascii=False, indent=2)

    print(f"\nDone! {len(final_profiles)} final merged profiles.")
    print(f"Stats saved to tmp/narrators_l3_final_stats.json")


if __name__ == "__main__":
    main()
