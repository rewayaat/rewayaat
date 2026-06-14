#!/usr/bin/env python3
"""
Phase 2: Cross-Book Narrator Aggregation & Deduplication.

Merges all per-book narrator profile files into a single unified database
with full provenance on every field.

Usage:
    python3 scripts/merge_narrator_profiles.py --dry-run     # L1+L2 stats only, no writes
    python3 scripts/merge_narrator_profiles.py                # Full L1+L2 run
    python3 scripts/merge_narrator_profiles.py --layer3       # Also run L3 (Claude API)
    python3 scripts/merge_narrator_profiles.py --resume       # Resume from checkpoint
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

# --- Normalization (identical to parse_external_rijal.py) ---

def normalize_arabic(raw: str) -> str:
    if not raw:
        return ""
    return (
        re.sub(r"[\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06ED]", "", raw)
        .replace("\u0640", " ")
        .replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
        .replace("ى", "ي").replace("ة", "ه").replace("ؤ", "و").replace("ئ", "ي")
        .replace("(", "").replace(")", "")
        .strip()
    )


def normalize_english(raw: str) -> str:
    if not raw:
        return ""
    return (
        unicodedata.normalize("NFKD", raw)
        .replace("ʿ", "").replace("ʾ", "").replace("ʻ", "")
        .replace("'", "").replace("\u2019", "")
        .lower()
        .strip()
    )


# --- Book definitions ---

BOOK_ORDER = ["tusi", "duafa", "kashshi", "fihrist", "najashi", "ardabili", "khoei", "mamaqani"]

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


# --- Merge State ---

class MergeState:
    def __init__(self):
        self.profiles = []          # list of merged profile dicts
        self.name_index = defaultdict(list)  # normalized_arabic -> [merged_id]
        self.next_id = 0
        self.stats = {
            "total_input": 0,
            "layer1_exact_merges": 0,
            "layer1_alias_merges": 0,
            "layer1_new": 0,
            "layer2_context_merges": 0,
            "layer2_new": 0,
            "layer3_deferred": 0,
            "per_book": {},
        }

    def find_candidates(self, profile: dict) -> list[int]:
        """Find merged profile IDs matching this profile's names."""
        candidates = set()
        # Primary name
        norm = profile.get("normalized_arabic", "")
        if norm:
            candidates.update(self.name_index.get(norm, []))
        # Aliases
        for alias in profile.get("arabic_aliases", []):
            na = normalize_arabic(alias) if isinstance(alias, str) else normalize_arabic(alias.get("name", ""))
            if na:
                candidates.update(self.name_index.get(na, []))
        # Kunyah
        kunyah = profile.get("kunyah_arabic")
        if kunyah:
            nk = normalize_arabic(kunyah)
            if nk:
                candidates.update(self.name_index.get(nk, []))
        return sorted(candidates)

    def add_new_profile(self, profile: dict, book_slug: str):
        """Create a new merged profile from an incoming book profile."""
        mid = self.next_id
        self.next_id += 1

        pages = profile.get("source_pages", [])
        if isinstance(pages, (int, float)):
            pages = [int(pages)]

        merged = {
            "merged_id": mid,
            "primary_arabic_name": profile.get("primary_arabic_name", ""),
            "primary_english_name": profile.get("primary_english_name", ""),
            "primary_name_source": {"book": book_slug, "pages": pages},
            "arabic_aliases": [
                {"name": a, "source_book": book_slug, "source_pages": pages}
                for a in (profile.get("arabic_aliases") or [])
            ],
            "english_aliases": [
                {"name": a, "source_book": book_slug, "source_pages": pages}
                for a in (profile.get("english_aliases") or [])
            ],
            "kunyah_arabic": profile.get("kunyah_arabic"),
            "kunyah_arabic_sources": (
                [{"value": profile["kunyah_arabic"], "source_book": book_slug, "source_pages": pages}]
                if profile.get("kunyah_arabic") else []
            ),
            "kunyah_english": profile.get("kunyah_english"),
            "titles": [
                {"title": t, "source_book": book_slug, "source_pages": pages}
                for t in (profile.get("titles") or [])
            ],
            "normalized_arabic": profile.get("normalized_arabic", ""),
            "normalized_english": profile.get("normalized_english", ""),
            "source_assessments": [],
            "reliability_grades": [],
            "is_doubtful": profile.get("is_doubtful", False),
            "doubtful_reasons": [],
            "narrated_from": [
                {"name": n, "source_book": book_slug, "source_pages": pages}
                for n in (profile.get("narrated_from") or [])
            ],
            "narrated_to": [
                {"name": n, "source_book": book_slug, "source_pages": pages}
                for n in (profile.get("narrated_to") or [])
            ],
            "city_or_tribe_values": [],
            "generation_values": [],
            "death_year_hijri_values": [],
            "gender": profile.get("gender", "male"),
            "notes": [],
            "contributing_sources": [
                {"book": book_slug, "author": BOOK_META[book_slug]["author"], "pages": pages}
            ],
        }

        # Add provenance-tracked biographical fields
        if profile.get("city_or_tribe"):
            val = profile["city_or_tribe"]
            if isinstance(val, list):
                val = ", ".join(str(x) for x in val)
            merged["city_or_tribe_values"].append(
                {"value": str(val), "source_book": book_slug, "source_pages": pages}
            )
        if profile.get("generation"):
            val = profile["generation"]
            if isinstance(val, list):
                val = ", ".join(str(x) for x in val)
            merged["generation_values"].append(
                {"value": str(val), "source_book": book_slug, "source_pages": pages}
            )
        if profile.get("death_year_hijri"):
            val = profile["death_year_hijri"]
            if isinstance(val, list):
                val = ", ".join(str(x) for x in val)
            merged["death_year_hijri_values"].append(
                {"value": str(val), "source_book": book_slug, "source_pages": pages}
            )

        # Add source assessments
        for sa in (profile.get("source_assessments") or []):
            entry = {
                "source_name": sa.get("source_name", BOOK_META[book_slug]["name"]),
                "author": sa.get("author", BOOK_META[book_slug]["author"]),
                "assessment_ar": sa.get("assessment_ar"),
                "assessment_en": sa.get("assessment_en"),
                "source_book": book_slug,
                "source_pages": pages,
            }
            merged["source_assessments"].append(entry)

        # Add reliability grade
        if profile.get("reliability_grade"):
            merged["reliability_grades"].append(
                {"grade": profile["reliability_grade"], "source_book": book_slug, "source_pages": pages}
            )

        # Add doubtful reasons
        if profile.get("doubtful_reason"):
            merged["doubtful_reasons"].append(
                {"reason": profile["doubtful_reason"], "source_book": book_slug, "source_pages": pages}
            )

        # Add notes
        if profile.get("notes"):
            merged["notes"].append(
                {"text": profile["notes"], "source_book": book_slug, "source_pages": pages}
            )

        # Update inverted index
        self._index_names(merged)

        self.profiles.append(merged)
        return mid

    def merge_into(self, merged_id: int, profile: dict, book_slug: str, match_type: str):
        """Merge an incoming profile into an existing merged profile."""
        m = self.profiles[merged_id]
        pages = profile.get("source_pages", [])
        if isinstance(pages, (int, float)):
            pages = [int(pages)]

        # Add source assessments (always append)
        for sa in (profile.get("source_assessments") or []):
            entry = {
                "source_name": sa.get("source_name", BOOK_META[book_slug]["name"]),
                "author": sa.get("author", BOOK_META[book_slug]["author"]),
                "assessment_ar": sa.get("assessment_ar"),
                "assessment_en": sa.get("assessment_en"),
                "source_book": book_slug,
                "source_pages": pages,
            }
            m["source_assessments"].append(entry)

        # Add reliability grade
        if profile.get("reliability_grade"):
            m["reliability_grades"].append(
                {"grade": profile["reliability_grade"], "source_book": book_slug, "source_pages": pages}
            )

        # Union aliases (skip duplicates)
        existing_arabic_names = {a["name"] for a in m["arabic_aliases"]}
        existing_arabic_names.add(m["primary_arabic_name"])
        for a in (profile.get("arabic_aliases") or []):
            if a not in existing_arabic_names:
                m["arabic_aliases"].append({"name": a, "source_book": book_slug, "source_pages": pages})
                existing_arabic_names.add(a)

        existing_english_names = {a["name"] for a in m["english_aliases"]}
        existing_english_names.add(m.get("primary_english_name", ""))
        for a in (profile.get("english_aliases") or []):
            if a not in existing_english_names:
                m["english_aliases"].append({"name": a, "source_book": book_slug, "source_pages": pages})
                existing_english_names.add(a)

        # Union titles
        existing_titles = {t["title"] for t in m["titles"]}
        for t in (profile.get("titles") or []):
            if t not in existing_titles:
                m["titles"].append({"title": t, "source_book": book_slug, "source_pages": pages})
                existing_titles.add(t)

        # Add kunyah if new
        if profile.get("kunyah_arabic"):
            existing_kunyahs = {k["value"] for k in m["kunyah_arabic_sources"]}
            if profile["kunyah_arabic"] not in existing_kunyahs:
                m["kunyah_arabic_sources"].append(
                    {"value": profile["kunyah_arabic"], "source_book": book_slug, "source_pages": pages}
                )

        # Add biographical values (all sources preserved)
        if profile.get("city_or_tribe"):
            val = profile["city_or_tribe"]
            if isinstance(val, list):
                val = ", ".join(str(x) for x in val)
            m["city_or_tribe_values"].append(
                {"value": str(val), "source_book": book_slug, "source_pages": pages}
            )
        if profile.get("generation"):
            val = profile["generation"]
            if isinstance(val, list):
                val = ", ".join(str(x) for x in val)
            m["generation_values"].append(
                {"value": str(val), "source_book": book_slug, "source_pages": pages}
            )
        if profile.get("death_year_hijri"):
            val = profile["death_year_hijri"]
            if isinstance(val, list):
                val = ", ".join(str(x) for x in val)
            m["death_year_hijri_values"].append(
                {"value": str(val), "source_book": book_slug, "source_pages": pages}
            )

        # Union narrated_from/to
        existing_nf = {n["name"] for n in m["narrated_from"]}
        for n in (profile.get("narrated_from") or []):
            if n not in existing_nf:
                m["narrated_from"].append({"name": n, "source_book": book_slug, "source_pages": pages})
                existing_nf.add(n)

        existing_nt = {n["name"] for n in m["narrated_to"]}
        for n in (profile.get("narrated_to") or []):
            if n not in existing_nt:
                m["narrated_to"].append({"name": n, "source_book": book_slug, "source_pages": pages})
                existing_nt.add(n)

        # Doubtful
        if profile.get("is_doubtful"):
            m["is_doubtful"] = True
        if profile.get("doubtful_reason"):
            m["doubtful_reasons"].append(
                {"reason": profile["doubtful_reason"], "source_book": book_slug, "source_pages": pages}
            )

        # Notes
        if profile.get("notes"):
            m["notes"].append(
                {"text": profile["notes"], "source_book": book_slug, "source_pages": pages}
            )

        # Contributing sources
        m["contributing_sources"].append(
            {"book": book_slug, "author": BOOK_META[book_slug]["author"], "pages": pages}
        )

        # Update inverted index with any new names from this profile
        self._index_names_from_profile(profile, merged_id, book_slug)

    def _index_names(self, merged: dict):
        """Add a merged profile's names to the inverted index."""
        mid = merged["merged_id"]
        norm = merged.get("normalized_arabic", "")
        if norm:
            self.name_index[norm].append(mid)
        for a in merged.get("arabic_aliases", []):
            name = a["name"] if isinstance(a, dict) else a
            na = normalize_arabic(name)
            if na and mid not in self.name_index[na]:
                self.name_index[na].append(mid)
        for t in merged.get("titles", []):
            title = t["title"] if isinstance(t, dict) else t
            nt = normalize_arabic(title)
            if nt and mid not in self.name_index[nt]:
                self.name_index[nt].append(mid)

    def _index_names_from_profile(self, profile: dict, merged_id: int, book_slug: str):
        """Add names from an incoming profile to the index for an existing merged profile."""
        # Primary name (if not already indexed)
        norm = normalize_arabic(profile.get("primary_arabic_name", ""))
        if norm and merged_id not in self.name_index.get(norm, []):
            self.name_index[norm].append(merged_id)
        # Aliases
        for a in (profile.get("arabic_aliases") or []):
            na = normalize_arabic(a) if isinstance(a, str) else normalize_arabic(a.get("name", ""))
            if na and merged_id not in self.name_index.get(na, []):
                self.name_index[na].append(merged_id)
        # Titles
        for t in (profile.get("titles") or []):
            nt = normalize_arabic(t) if isinstance(t, str) else normalize_arabic(t.get("title", ""))
            if nt and merged_id not in self.name_index.get(nt, []):
                self.name_index[nt].append(merged_id)


# --- Layer 2: Context Scoring ---

def context_score(profile: dict, merged: dict) -> tuple[int, list[str]]:
    """Score how well an incoming profile matches a merged profile based on context.
    Returns (score, list of match reasons)."""
    score = 0
    reasons = []

    # Kunyah match (+3)
    p_kunyah = profile.get("kunyah_arabic")
    m_kunyahs = {k["value"] for k in merged.get("kunyah_arabic_sources", [])}
    if p_kunyah and m_kunyahs:
        nk = normalize_arabic(p_kunyah)
        for mk in m_kunyahs:
            if normalize_arabic(mk) == nk:
                score += 3
                reasons.append("kunyah")
                break

    # Titles overlap (+1 per shared, max +2)
    p_titles = {normalize_arabic(t) for t in (profile.get("titles") or [])}
    m_titles = {normalize_arabic(t["title"]) for t in merged.get("titles", [])}
    shared = p_titles & m_titles
    if shared:
        title_pts = min(len(shared), 2)
        score += title_pts
        reasons.append(f"{title_pts} shared titles")

    # City/tribe match (+2)
    p_city = profile.get("city_or_tribe")
    if isinstance(p_city, list):
        p_city = ", ".join(str(x) for x in p_city) if p_city else None
    m_cities = {c["value"] for c in merged.get("city_or_tribe_values", [])}
    if p_city and m_cities:
        nc = normalize_arabic(str(p_city))
        for mc in m_cities:
            if normalize_arabic(mc) == nc:
                score += 2
                reasons.append("city")
                break

    # Generation match (+2)
    p_gen = profile.get("generation")
    if isinstance(p_gen, list):
        p_gen = ", ".join(str(x) for x in p_gen) if p_gen else None
    m_gens = {g["value"] for g in merged.get("generation_values", [])}
    if p_gen and m_gens:
        ng = normalize_arabic(str(p_gen))
        for mg in m_gens:
            if normalize_arabic(mg) == ng:
                score += 2
                reasons.append("generation")
                break

    # Teacher/student overlap (+1 per shared name, max +2)
    p_nf = {normalize_arabic(n) for n in (profile.get("narrated_from") or [])}
    m_nf = {normalize_arabic(n["name"]) for n in merged.get("narrated_from", [])}
    p_nt = {normalize_arabic(n) for n in (profile.get("narrated_to") or [])}
    m_nt = {normalize_arabic(n["name"]) for n in merged.get("narrated_to", [])}
    shared_people = (p_nf & m_nf) | (p_nt & m_nt)
    if shared_people:
        people_pts = min(len(shared_people), 2)
        score += people_pts
        reasons.append(f"{people_pts} shared teacher/student")

    return score, reasons


# --- Process a single book ---

def process_book(state: MergeState, book_slug: str, profiles: list[dict], dry_run: bool = False):
    """Process all profiles from one book through L1 → L2."""
    book_stats = {"input": len(profiles), "l1_merge": 0, "l1_alias_merge": 0, "l1_new": 0,
                  "l2_merge": 0, "l2_new": 0, "deferred": 0}

    deferred = []  # profiles that need L3

    for i, profile in enumerate(profiles):
        if not profile.get("primary_arabic_name"):
            continue

        candidates = state.find_candidates(profile)

        # Layer 1: exact match
        if len(candidates) == 0:
            # No match — new profile
            state.add_new_profile(profile, book_slug)
            book_stats["l1_new"] += 1

        elif len(candidates) == 1:
            # Unique match — merge
            state.merge_into(candidates[0], profile, book_slug, "layer1")
            book_stats["l1_merge"] += 1

        else:
            # Multiple candidates — Layer 2 context scoring
            scores = []
            for cid in candidates:
                s, reasons = context_score(profile, state.profiles[cid])
                scores.append((cid, s, reasons))

            scores.sort(key=lambda x: x[1], reverse=True)
            best_id, best_score, best_reasons = scores[0]
            second_score = scores[1][1] if len(scores) > 1 else 0

            if best_score >= 3 and second_score < 2:
                # Clear winner
                state.merge_into(best_id, profile, book_slug, "layer2")
                book_stats["l2_merge"] += 1
            elif best_score >= 2 and second_score == 0:
                # Only candidate with any context match
                state.merge_into(best_id, profile, book_slug, "layer2")
                book_stats["l2_merge"] += 1
            elif best_score == 0:
                # No context match — likely a new person with a common name
                state.add_new_profile(profile, book_slug)
                book_stats["l2_new"] += 1
            else:
                # Ambiguous — defer to L3
                new_id = state.add_new_profile(profile, book_slug)
                deferred.append({
                    "new_id": new_id,
                    "candidates": [(cid, s, r) for cid, s, r in scores],
                    "profile_summary": {
                        "name": profile.get("primary_arabic_name"),
                        "kunyah": profile.get("kunyah_arabic"),
                        "city": profile.get("city_or_tribe"),
                        "generation": profile.get("generation"),
                    }
                })
                book_stats["deferred"] += 1

        if (i + 1) % 500 == 0:
            print(f"  Processed {i + 1}/{len(profiles)} profiles...")

    print(f"  {book_slug}: {book_stats['l1_merge']} L1 merges, {book_stats['l1_new']} new, "
          f"{book_stats['l2_merge']} L2 merges, {book_stats['l2_new']} L2 new, "
          f"{book_stats['deferred']} deferred")

    state.stats["per_book"][book_slug] = book_stats
    return deferred


# --- Layer 3: LLM batch (optional) ---

def run_layer3(state: MergeState, deferred: list[dict], batch_size: int = 10, max_batches: int = None):
    """Use Claude API to resolve deferred ambiguous profiles."""
    try:
        import anthropic
    except ImportError:
        print("ERROR: anthropic package required for Layer 3. pip install anthropic")
        return

    client = anthropic.Anthropic()
    batches_processed = 0
    llm_merges = 0
    llm_new = 0

    for i in range(0, len(deferred), batch_size):
        if max_batches and batches_processed >= max_batches:
            break

        batch = deferred[i:i + batch_size]
        pairs_text = []

        for j, d in enumerate(batch):
            ps = d["profile_summary"]
            top_candidates = d["candidates"][:3]
            candidates_text = ""
            for cid, score, reasons in top_candidates:
                mp = state.profiles[cid]
                candidates_text += (f"  Profile B (merged_id {cid}, sources: {[s['book'] for s in mp['contributing_sources']]}): "
                                   f"{mp['primary_arabic_name']} | kunyah: {mp.get('kunyah_arabic')} | "
                                   f"city: {[v['value'] for v in mp.get('city_or_tribe_values', [])]} | "
                                   f"generation: {[v['value'] for v in mp.get('generation_values', [])]} | "
                                   f"L2 score: {score} ({', '.join(reasons)})\n")

            pairs_text.append(
                f"Pair {j}:\n"
                f"Profile A (new, from book): {ps['name']} | kunyah: {ps['kunyah']} | "
                f"city: {ps['city']} | generation: {ps['generation']}\n"
                f"Candidates:\n{candidates_text}"
            )

        prompt = (
            "You are an expert in Shia Hadith narrator biography (Ilm al-Rijal).\n\n"
            "For each pair, judge whether Profile A is the SAME person as one of the candidates (Profile B), "
            "or is a DIFFERENT person. Default to 'different' unless evidence clearly supports same person.\n\n"
            'Respond with a JSON array: [{"pair_id": 0, "same_person": true/false, '
            '"merge_with_merged_id": null_or_id, "confidence": "high"/"medium"/"low", "reasoning": "..."}]\n\n'
            + "\n".join(pairs_text)
        )

        try:
            response = client.messages.create(
                model="claude-sonnet-4-20250514",
                max_tokens=2000,
                messages=[{"role": "user", "content": prompt}]
            )
            text = response.content[0].text
            # Extract JSON from response
            start = text.find("[")
            end = text.rfind("]") + 1
            if start >= 0 and end > start:
                judgments = json.loads(text[start:end])
                for j in judgments:
                    if j.get("same_person") and j.get("merge_with_merged_id") is not None:
                        llm_merges += 1
                    else:
                        llm_new += 1
            batches_processed += 1
            print(f"  L3 batch {batches_processed}: {len(batch)} pairs processed")
        except Exception as e:
            print(f"  L3 batch error: {e}")

        time.sleep(1)  # rate limit

    print(f"  L3 complete: {llm_merges} merges, {llm_new} new, {batches_processed} batches")


# --- Checkpoint ---

CHECKPOINT_FILE = "tmp/narrators_merge_checkpoint.json"
OUTPUT_FILE = "tmp/narrators_merged.json"
STATS_FILE = "tmp/narrators_merge_stats.json"
REVIEW_FILE = "tmp/narrator_review_queue.jsonl"


def save_checkpoint(state: MergeState, books_done: list[str], deferred: list[dict]):
    """Save full state to checkpoint file."""
    print(f"  Saving checkpoint ({len(state.profiles)} profiles)...")
    with open(CHECKPOINT_FILE, "w", encoding="utf-8") as f:
        json.dump({
            "books_done": books_done,
            "deferred": deferred,
            "state": {
                "profiles": state.profiles,
                "next_id": state.next_id,
                "stats": state.stats,
            },
        }, f, ensure_ascii=False, indent=2)


def load_checkpoint() -> tuple[MergeState, list[str], list[dict]]:
    """Load state from checkpoint file."""
    print("Loading checkpoint...")
    with open(CHECKPOINT_FILE, "r", encoding="utf-8") as f:
        data = json.load(f)

    state = MergeState()
    state.profiles = data["state"]["profiles"]
    state.next_id = data["state"]["next_id"]
    state.stats = data["state"]["stats"]

    # Rebuild inverted index from profiles
    for p in state.profiles:
        state._index_names(p)

    books_done = data.get("books_done", [])
    deferred = data.get("deferred", [])
    print(f"  Restored {len(state.profiles)} profiles, index has {len(state.name_index)} names")
    return state, books_done, deferred


# --- Main ---

def main():
    parser = argparse.ArgumentParser(description="Merge narrator profiles from all Rijal books")
    parser.add_argument("--dry-run", action="store_true", help="L1+L2 stats only, no writes")
    parser.add_argument("--resume", action="store_true", help="Resume from checkpoint")
    parser.add_argument("--layer3", action="store_true", help="Run Layer 3 (Claude API) on deferred cases")
    parser.add_argument("--layer3-max-batches", type=int, default=None, help="Max L3 batches")
    parser.add_argument("--output", default=OUTPUT_FILE, help="Output file path")
    parser.add_argument("--stats-file", default=STATS_FILE, help="Stats output path")
    args = parser.parse_args()

    if args.dry_run:
        print("=== DRY RUN (no writes) ===\n")

    all_deferred = []

    if args.resume and os.path.exists(CHECKPOINT_FILE):
        state, books_done, all_deferred = load_checkpoint()
    else:
        state = MergeState()
        books_done = []

    # Process each book in order
    for book_slug in BOOK_ORDER:
        if book_slug in books_done:
            print(f"Skipping {book_slug} (already processed)")
            continue

        path = f"tmp/narrators_book_{book_slug}.json"
        if not os.path.exists(path):
            print(f"Skipping {book_slug} (file not found: {path})")
            continue

        print(f"\n{'='*50}")
        print(f"Processing: {book_slug} ({BOOK_META[book_slug]['name']})")
        print(f"{'='*50}")

        with open(path, "r", encoding="utf-8") as f:
            profiles = json.load(f)

        print(f"  Loaded {len(profiles)} profiles")
        state.stats["total_input"] += len(profiles)

        deferred = process_book(state, book_slug, profiles, dry_run=args.dry_run)
        all_deferred.extend(deferred)

        books_done.append(book_slug)

        if not args.dry_run:
            save_checkpoint(state, books_done, all_deferred)

    # Print summary
    print(f"\n{'='*50}")
    print("SUMMARY")
    print(f"{'='*50}")
    print(f"Total input profiles: {state.stats['total_input']:,}")
    print(f"Merged profiles: {len(state.profiles):,}")
    print(f"Deferred (L3 candidates): {len(all_deferred):,}")

    for book, bs in state.stats.get("per_book", {}).items():
        total = bs["input"]
        merged = bs["l1_merge"] + bs["l1_alias_merge"] + bs["l2_merge"]
        print(f"  {book:>10}: {total:>6} in → {merged} merged, {bs['l1_new'] + bs['l2_new']} new, {bs['deferred']} deferred")

    # Layer 3
    if args.layer3 and all_deferred and not args.dry_run:
        print(f"\nRunning Layer 3 on {len(all_deferred)} deferred profiles...")
        run_layer3(state, all_deferred, max_batches=args.layer3_max_batches)

    # Write outputs
    if not args.dry_run:
        print(f"\nWriting {len(state.profiles)} profiles to {args.output}...")
        with open(args.output, "w", encoding="utf-8") as f:
            json.dump(state.profiles, f, ensure_ascii=False, indent=2)

        state.stats["merged_profiles"] = len(state.profiles)
        state.stats["completed_at"] = datetime.now().isoformat()
        with open(args.stats_file, "w", encoding="utf-8") as f:
            json.dump(state.stats, f, ensure_ascii=False, indent=2)

        # Keep checkpoint for L3 processing (don't delete)
        print(f"  Checkpoint kept at {CHECKPOINT_FILE} for L3 processing")

        print(f"\nDone! {len(state.profiles)} merged profiles written to {args.output}")


if __name__ == "__main__":
    main()
