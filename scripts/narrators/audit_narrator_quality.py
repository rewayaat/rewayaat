#!/usr/bin/env python3
"""Quality audit of extracted narrator profiles across all books.

For each book, samples 50 profiles and reports:
- Field completeness rates
- Sample profiles for manual review
- Data quality issues (empty names, missing assessments, etc.)

Usage:
    python3 scripts/narrators/audit_narrator_quality.py [--sample-size N] [--verbose]
"""

import json
import random
import sys
import os
from collections import Counter

TMP_DIR = os.path.join(os.path.dirname(__file__), '..', '..', 'tmp')

BOOKS = {
    'duafa':    'narrators_book_duafa.json',
    'kashshi':  'narrators_book_kashshi.json',
    'fihrist':  'narrators_book_fihrist.json',
    'najashi':  'narrators_book_najashi.json',
    'tusi':     'narrators_book_tusi.json',
    'ardabili': 'narrators_book_ardabili.json',
    'khoei':    'narrators_book_khoei.json',
    'mamaqani': 'narrators_book_mamaqani.json',
}

FIELDS = [
    'primary_arabic_name',
    'primary_english_name',
    'arabic_aliases',
    'english_aliases',
    'kunyah_arabic',
    'kunyah_english',
    'titles',
    'reliability_grade',
    'is_doubtful',
    'doubtful_reason',
    'narrated_from',
    'narrated_to',
    'city_or_tribe',
    'generation',
    'death_year_hijri',
    'gender',
    'notes',
    'normalized_arabic',
    'normalized_english',
    'source_assessments',
    'rijal_sources',
    'source_pages',
]

ARRAY_FIELDS = {'arabic_aliases', 'english_aliases', 'titles', 'narrated_from',
                'narrated_to', 'source_assessments', 'rijal_sources', 'source_pages'}


def non_empty(val):
    """Check if a value is non-empty."""
    if val is None:
        return False
    if isinstance(val, str):
        return val.strip() != ''
    if isinstance(val, (list, tuple)):
        return len(val) > 0
    if isinstance(val, bool):
        return val  # is_doubtful=True is meaningful
    if isinstance(val, (int, float)):
        return True
    return True


def field_completeness(profiles, field):
    """Return % of profiles where field is non-empty."""
    filled = sum(1 for p in profiles if non_empty(p.get(field)))
    return filled, len(profiles), filled / len(profiles) * 100 if profiles else 0


def audit_book(slug, filepath, sample_size=50, verbose=False):
    """Audit a single book file."""
    if not os.path.exists(filepath):
        print(f"\n{'='*80}")
        print(f"BOOK: {slug} — FILE NOT FOUND")
        return None

    with open(filepath) as f:
        profiles = json.load(f)

    print(f"\n{'='*80}")
    print(f"BOOK: {slug}")
    print(f"Total profiles: {len(profiles)}")

    if not profiles:
        print("  (empty)")
        return None

    # Schema check
    all_keys = set()
    for p in profiles:
        all_keys.update(p.keys())
    expected = set(FIELDS)
    extra = all_keys - expected - {'_id', 'source_volume'}
    missing = expected - all_keys
    if extra:
        print(f"  Extra fields: {extra}")
    if missing:
        print(f"  Missing fields: {missing}")

    # Field completeness for ALL profiles
    print(f"\n  Field Completeness (all {len(profiles)} profiles):")
    print(f"  {'Field':<25} {'Filled':>8} {'Total':>8} {'%':>7}")
    print(f"  {'-'*25} {'-'*8} {'-'*8} {'-'*7}")
    for field in FIELDS:
        filled, total, pct = field_completeness(profiles, field)
        marker = "  ***" if pct < 10 and field not in ('doubtful_reason', 'death_year_hijri', 'kunyah_arabic', 'kunyah_english') else ""
        print(f"  {field:<25} {filled:>8} {total:>8} {pct:>6.1f}%{marker}")

    # Reliability grade distribution
    grades = Counter(p.get('reliability_grade', 'NONE') for p in profiles)
    print(f"\n  Reliability Grades:")
    for grade, count in grades.most_common():
        print(f"    {grade or '(empty)':<20} {count:>6}")

    # Source assessments quality
    sa_stats = {'has_arabic_quote': 0, 'has_english_summary': 0, 'has_source_name': 0, 'total_assessments': 0}
    for p in profiles:
        for sa in (p.get('source_assessments') or []):
            sa_stats['total_assessments'] += 1
            if non_empty(sa.get('assessment_ar')):
                sa_stats['has_arabic_quote'] += 1
            if non_empty(sa.get('assessment_en')):
                sa_stats['has_english_summary'] += 1
            if non_empty(sa.get('source_name')):
                sa_stats['has_source_name'] += 1
    print(f"\n  Source Assessments:")
    print(f"    Total assessment entries: {sa_stats['total_assessments']}")
    if sa_stats['total_assessments'] > 0:
        print(f"    With Arabic quote:  {sa_stats['has_arabic_quote']:>6} ({sa_stats['has_arabic_quote']/sa_stats['total_assessments']*100:.1f}%)")
        print(f"    With English summary: {sa_stats['has_english_summary']:>6} ({sa_stats['has_english_summary']/sa_stats['total_assessments']*100:.1f}%)")
        print(f"    With source name:   {sa_stats['has_source_name']:>6} ({sa_stats['has_source_name']/sa_stats['total_assessments']*100:.1f}%)")

    # Alias statistics
    ar_alias_counts = [len(p.get('arabic_aliases') or []) for p in profiles]
    en_alias_counts = [len(p.get('english_aliases') or []) for p in profiles]
    print(f"\n  Alias Statistics:")
    print(f"    Arabic aliases — min: {min(ar_alias_counts)}, max: {max(ar_alias_counts)}, "
          f"avg: {sum(ar_alias_counts)/len(ar_alias_counts):.1f}, "
          f"profiles with 0: {sum(1 for c in ar_alias_counts if c == 0)}")
    print(f"    English aliases — min: {min(en_alias_counts)}, max: {max(en_alias_counts)}, "
          f"avg: {sum(en_alias_counts)/len(en_alias_counts):.1f}, "
          f"profiles with 0: {sum(1 for c in en_alias_counts if c == 0)}")

    # Data quality issues
    issues = []
    for i, p in enumerate(profiles):
        if not non_empty(p.get('primary_arabic_name')):
            issues.append(f"  Profile {i}: missing primary_arabic_name")
        if not non_empty(p.get('primary_english_name')):
            issues.append(f"  Profile {i}: missing primary_english_name")
        if not non_empty(p.get('normalized_arabic')):
            issues.append(f"  Profile {i}: missing normalized_arabic")
        if not non_empty(p.get('normalized_english')):
            issues.append(f"  Profile {i}: missing normalized_english")
        if not (p.get('source_assessments') or p.get('rijal_sources')):
            issues.append(f"  Profile {i}: no source_assessments AND no rijal_sources")
        sa = p.get('source_assessments') or []
        for j, s in enumerate(sa):
            if not non_empty(s.get('assessment_ar')) and not non_empty(s.get('assessment_en')):
                issues.append(f"  Profile {i}, assessment {j}: both assessment_ar and assessment_en empty")

    if issues:
        print(f"\n  Data Quality Issues ({len(issues)} total):")
        for issue in issues[:30]:
            print(issue)
        if len(issues) > 30:
            print(f"  ... and {len(issues) - 30} more")
    else:
        print(f"\n  Data Quality Issues: None found")

    # Sample profiles for manual review
    n_sample = min(sample_size, len(profiles))
    sample_indices = sorted(random.sample(range(len(profiles)), n_sample))
    sample_profiles = [profiles[i] for i in sample_indices]

    sample_file = os.path.join(TMP_DIR, f'audit_sample_{slug}.json')
    with open(sample_file, 'w', encoding='utf-8') as f:
        json.dump(sample_profiles, f, ensure_ascii=False, indent=2)
    print(f"\n  Sample of {n_sample} profiles written to: {sample_file}")

    # Show 5 representative profiles
    print(f"\n  Representative Profiles (5 of {n_sample} sampled):")
    for p in sample_profiles[:5]:
        print(f"\n    ---")
        print(f"    Arabic:    {p.get('primary_arabic_name', '(empty)')}")
        print(f"    English:   {p.get('primary_english_name', '(empty)')}")
        print(f"    Kunyah:    {p.get('kunyah_arabic', '')} / {p.get('kunyah_english', '')}")
        aliases_ar = p.get('arabic_aliases') or []
        aliases_en = p.get('english_aliases') or []
        print(f"    Aliases:   {len(aliases_ar)} AR, {len(aliases_en)} EN")
        if aliases_ar[:3]:
            print(f"               AR samples: {aliases_ar[:3]}")
        if aliases_en[:3]:
            print(f"               EN samples: {aliases_en[:3]}")
        print(f"    Grade:     {p.get('reliability_grade', '(none)')}")
        print(f"    Doubtful:  {p.get('is_doubtful', False)}")
        titles = p.get('titles') or []
        print(f"    Titles:    {titles}")
        sa = p.get('source_assessments') or []
        print(f"    Assessments: {len(sa)}")
        for s in sa[:2]:
            ar_quote = (s.get('assessment_ar') or '')[:80]
            en_sum = (s.get('assessment_en') or '')[:80]
            print(f"      [{s.get('source_name','?')}] AR: {ar_quote}...")
            print(f"                      EN: {en_sum}...")

    return {
        'slug': slug,
        'total': len(profiles),
        'issues': len(issues),
        'sample_file': sample_file,
    }


def main():
    sample_size = 50
    verbose = False

    for arg in sys.argv[1:]:
        if arg.startswith('--sample-size='):
            sample_size = int(arg.split('=')[1])
        elif arg == '--verbose':
            verbose = True
        elif arg.startswith('--sample-size'):
            # handle --sample-size N
            pass

    # Handle --sample-size as separate arg
    args = sys.argv[1:]
    for i, arg in enumerate(args):
        if arg == '--sample-size' and i + 1 < len(args):
            sample_size = int(args[i + 1])

    print("NARRATOR EXTRACTION QUALITY AUDIT")
    print(f"Sample size per book: {sample_size}")
    random.seed(42)  # Reproducible samples

    summaries = []
    for slug, filename in BOOKS.items():
        filepath = os.path.join(TMP_DIR, filename)
        result = audit_book(slug, filepath, sample_size, verbose)
        if result:
            summaries.append(result)

    # Cross-book summary
    print(f"\n{'='*80}")
    print("CROSS-BOOK SUMMARY")
    print(f"{'Book':<15} {'Profiles':>10} {'Issues':>10} {'Sample File'}")
    print(f"{'-'*15} {'-'*10} {'-'*10} {'-'*30}")
    for s in summaries:
        print(f"{s['slug']:<15} {s['total']:>10} {s['issues']:>10} {s['sample_file']}")

    total_profiles = sum(s['total'] for s in summaries)
    total_issues = sum(s['issues'] for s in summaries)
    print(f"\nTotal profiles across all books: {total_profiles}")
    print(f"Total data quality issues: {total_issues}")


if __name__ == '__main__':
    main()
