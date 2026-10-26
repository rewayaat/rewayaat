#!/usr/bin/env python3
"""Draw the attribute audit: does each extracted fact actually stand in its source's own words?

Every audit so far has measured identity — is this entry the same man as that one. None has
measured the facts the page prints about him. A narrator page states his grade, his teachers,
his students, his city, the Imams he is filed under and the year he died, and each of those
is an extraction from one passage of one book. Whether the passage says it has never been
asked.

The unit is one source record shown beside the Arabic it was read from, and the question is
put once per field:

  grade         the reliability verdict, and whose verdict it is. A book quoting another
                book's tawthiq is not that book's own position, and the page has been
                presenting both the same way.
  teachers      the names under narrated_from, up to SHOWN_NAMES of them
  students      the names under narrated_to
  city          city_or_tribe, which mixes cities, tribes and clientage in one field
  generation    the Imams the record files him under
  death_year    death_year_hijri

Records are stratified by book and by kind — a printed entry against a phase 1 mention, which
is a name read out of somebody else's passage. That is the fault line this audit exists to
measure: al-Hakam b. Hakim carries «thiqa» from two Khoei records whose whole text is «أقول:
هو متحد مع ما تقدم عليه», and a reader is shown it as Khoei's verdict.

Hidden among the records are two kinds of control, recorded in the key file and not in the
batches:

  planted   a field whose value has been replaced with one the text cannot support — a city
            named nowhere in it, a death year whose digits do not occur, a grade flipped to
            its opposite where the original word stands in the text. An auditor who passes
            these is not reading.
  verbatim  a grade whose Arabic word stands literally in the text. An auditor who fails
            these is flagging everything, which measures nothing either.

Nothing in a batch says which records are controls, which field was planted, or what the
original value was. The sample is drawn with a fixed seed so it can be drawn again exactly.

Reads  tmp/narrators_normalized/*.json, tmp/narrators_identity/people.json
Writes tmp/narrators_audit/attr/runs/attr-<count>-<sha16>/{batches,outputs,manifest.json}
       tmp/narrators_audit/attr/keys/attr-<count>-<sha16>.json   (the answer key, outside the run)

Usage:
    python3 scripts/narrators/attr_sample.py --dry-run
    python3 scripts/narrators/attr_sample.py
"""
import argparse
from collections import Counter, defaultdict
import glob
import hashlib
import json
import os
import random
import re
import sys

W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
sys.path.insert(0, f'{W}/scripts/narrators')
from build_people import is_entry, not_entries, relation_stubs  # noqa: E402
from identity import write_json_atomic  # noqa: E402
from l3_prepare import DEFAULT_BUDGET, pack  # noqa: E402
from narrator_schema import imam_generations, normalize_arabic  # noqa: E402

TMP = f'{W}/tmp'
SEED = 20261010
PER_STRATUM = 30
SHOWN_NAMES = 8
TEXT_CHARS = 3000
CONTROLS_PER_KIND = 30
PER_FIELD = 60

IMAM_NAMES = ["the Prophet", "Ali", "al-Hasan", "al-Husayn", "al-Sajjad", "al-Baqir",
              "al-Sadiq", "al-Kazim", "al-Rida", "al-Jawad", "al-Hadi", "al-Askari",
              "al-Mahdi"]

# Cities that occur nowhere near one another, for planting: a city the text cannot be
# read as naming. Checked against the text before it is used, so the list only has to
# offer candidates.
PLANT_CITIES = ["Samarqand", "Qayrawan", "Sijistan", "Tabaristan", "Andalus", "Khwarizm"]
PLANT_YEARS = [211, 284, 317, 349, 376, 398]
# The grade flip: a value whose opposite word is the one standing in the text.
FLIP = {"thiqa": ("daif", "weak (da'if)"), "hasan": ("daif", "weak (da'if)"),
        "daif": ("thiqa", "reliable (thiqa)"), "very_weak": ("thiqa", "reliable (thiqa)"),
        "kadhdhab": ("thiqa", "reliable (thiqa)")}
# The Arabic a grade is normally read from. Used only to find verbatim controls and to
# check that a flip is detectable — never to judge anything.
GRADE_ARABIC = {"thiqa": ["ثقه", "ثقة"], "daif": ["ضعيف"], "very_weak": ["ضعيف جدا"],
                "kadhdhab": ["كذاب"], "hasan": ["حسن"]}

FIELDS = ["grade", "teachers", "students", "city", "generation", "death_year"]


def load(path):
    with open(path) as handle:
        return json.load(handle)


def text_of(profile):
    """The longest Arabic passage held for this record — what it was read from."""
    best = ""
    for item in profile.get("source_assessments") or []:
        value = (item.get("assessment_ar") or "").strip()
        if len(value) > len(best):
            best = value
    return best


def extracted(profile):
    """The facts this record asserts, as the page would print them, field by field."""
    out = {}
    raw = profile.get("reliability_grade_raw")
    grade = profile.get("reliability_grade")
    if grade and grade not in ("not_assessed", "unknown") and raw:
        out["grade"] = {"value": grade, "said": raw}
    for field, key in (("teachers", "narrated_from"), ("students", "narrated_to")):
        names = [n for n in (profile.get(key) or []) if isinstance(n, str) and n.strip()]
        if names:
            out[field] = {"names": names[:SHOWN_NAMES], "held": len(names)}
    cities = [c for c in (profile.get("city_or_tribe") or []) if isinstance(c, str) and c.strip()]
    if cities:
        out["city"] = {"values": cities}
    if profile.get("generation"):
        imams = sorted(imam_generations(profile["generation"]))
        out["generation"] = {"said": profile["generation"],
                             "imams": [IMAM_NAMES[i] for i in imams if i < len(IMAM_NAMES)]}
    if profile.get("death_year_hijri"):
        out["death_year"] = {"value": profile["death_year_hijri"]}
    return out


# Controls are allocated across the fields deliberately rather than taken as they fall. A
# first draw put all thirty planted controls on city, teachers and death_year, leaving the
# grade — the field with the most questions and by far the most consequence — with positive
# controls only. An auditor can pass thirty verbatim graded passages while waving through
# every invented grade, and nothing in the figures would show it.
PLANT_ORDER = ["grade", "city", "teachers", "generation", "students", "death_year"]
# Weighted towards the grade, which carries the most questions and the most consequence, and
# which is also the hardest field to plant into — so it needs the most chances, not the fewest.
QUOTA = {"grade": 9, "city": 5, "teachers": 5, "students": 4, "generation": 4, "death_year": 3}


def plant(record, text, rng, prefer=None, only=None):
    """Replace one field with a value the text cannot support. Returns what was changed."""
    facts = record["asserts"]
    order = [f for f in PLANT_ORDER if f in facts and (only is None or f in only)]
    rng.shuffle(order)
    if prefer in order:
        order.remove(prefer)
        order.insert(0, prefer)
    folded = normalize_arabic(text)
    for field in order:
        if field == "city":
            for city in rng.sample(PLANT_CITIES, len(PLANT_CITIES)):
                if city.lower() not in text.lower():
                    was = facts["city"]["values"]
                    facts["city"] = {"values": [city]}
                    return {"field": "city", "was": was, "now": [city]}
        elif field == "death_year":
            for year in rng.sample(PLANT_YEARS, len(PLANT_YEARS)):
                if str(year) not in text and str(year) != str(facts["death_year"]["value"]):
                    was = facts["death_year"]["value"]
                    facts["death_year"] = {"value": year}
                    return {"field": "death_year", "was": was, "now": year}
        elif field == "grade":
            grade = facts["grade"]["value"]
            # only where the original word really stands in the text, so the flip is
            # something an auditor can see rather than guess
            if grade in FLIP and any(w in folded for w in
                                     (normalize_arabic(a) for a in GRADE_ARABIC.get(grade, ()))):
                now, said = FLIP[grade]
                was = dict(facts["grade"])
                facts["grade"] = {"value": now, "said": said}
                return {"field": "grade", "was": was, "now": {"value": now, "said": said}}
        elif field in ("teachers", "students"):
            # a name of the right shape that the passage does not contain
            for candidate in ("سليمان بن داود المنقري", "عمرو بن عثمان الخزاز",
                              "موسى بن القاسم البجلي"):
                if normalize_arabic(candidate).split()[0] not in folded:
                    was = list(facts[field]["names"])
                    facts[field] = {"names": was + [candidate],
                                    "held": facts[field]["held"] + 1}
                    return {"field": field, "was": was, "now": was + [candidate],
                            "added": candidate}
        elif field == "generation":
            # the Prophet's own companions against a companion of al-Hadi: six Imams apart,
            # and the passage names one of them
            was = dict(facts["generation"])
            far = ("the Prophet", "Ali") if "al-Hadi" not in was["imams"] else ("al-Hadi",)
            if set(far) - set(was["imams"]):
                facts["generation"] = {"said": was["said"], "imams": list(far)}
                return {"field": "generation", "was": was,
                        "now": facts["generation"]}
    return None


def verbatim(record, text):
    """True when the grade's own Arabic word stands in the text: an auditor must pass it."""
    facts = record["asserts"]
    if "grade" not in facts:
        return False
    folded = normalize_arabic(text)
    words = GRADE_ARABIC.get(facts["grade"]["value"], ())
    return any(normalize_arabic(w) in folded for w in words)


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--normalized-dir", default=os.path.join(TMP, "narrators_normalized"))
    parser.add_argument("--out-dir", default=os.path.join(TMP, "narrators_audit", "attr"))
    parser.add_argument("--per-stratum", type=int, default=PER_STRATUM)
    parser.add_argument("--per-field", type=int, default=PER_FIELD,
                        help="top a field up to this many records when the draw by book is thin")
    parser.add_argument("--controls", type=int, default=CONTROLS_PER_KIND,
                        help="planted controls, and the same number of verbatim ones")
    parser.add_argument("--budget", type=int, default=34000,
                        help="characters per batch; one agent reads one batch")
    parser.add_argument("--dry-run", action="store_true", help="count, write nothing")
    args = parser.parse_args()

    rng = random.Random(SEED)
    headless, stubs = not_entries(TMP), relation_stubs(TMP)
    people = load(os.path.join(TMP, "narrators_identity", "people.json"))
    person_of = {k: p["person_id"] for p in people for k in p["source_keys"]}

    pool, counts = defaultdict(list), Counter()
    for path in sorted(glob.glob(os.path.join(args.normalized_dir, "*.json"))):
        book = os.path.basename(path)[:-5]
        rows = load(path)
        if not isinstance(rows, list):
            continue
        for profile in rows:
            if not isinstance(profile, dict) or "source_index" not in profile:
                continue
            key = f"{book}:{profile['source_index']}"
            text = text_of(profile)
            facts = extracted(profile)
            if not facts:
                continue
            counts["records carrying a fact"] += 1
            if not text or len(text) < 20:
                counts["skipped: no text to judge against"] += 1
                continue
            if key not in person_of:
                counts["skipped: attached to nobody"] += 1
                continue
            kind = "entry" if (key not in stubs and is_entry(key, profile, headless)) else "mention"
            pool[f"{book}/{kind}"].append({
                "source_key": key, "book": book, "kind": kind,
                "person_id": person_of[key],
                "heading_ar": profile.get("primary_arabic_name"),
                "pages": profile.get("source_pages") or [],
                "text_ar": text[:TEXT_CHARS],
                "asserts": facts,
            })

    for label, n in counts.most_common():
        print(f"  {n:7,}  {label}")
    print(f"\n{len(pool)} strata:")
    for stratum in sorted(pool, key=lambda s: -len(pool[s])):
        print(f"    {len(pool[stratum]):6,}  {stratum}")

    drawn, key_rows = [], []
    for stratum in sorted(pool):
        rows = sorted(pool[stratum], key=lambda r: r["source_key"])
        take = rng.sample(rows, min(args.per_stratum, len(rows)))
        for row in take:
            drawn.append(dict(row, stratum=stratum))

    # A stratified draw measures the books, not the fields. Death years are carried by 2,186
    # records in a corpus of 47,673, so a draw by book asked about one 24 times — and a death
    # year is printed in the hero sentence, where a wrong one is as visible as a wrong grade.
    # Thin fields are topped up to --per-field, drawn across the whole corpus.
    seen = {r["source_key"] for r in drawn}
    for field in FIELDS:
        have = sum(1 for r in drawn if field in r["asserts"])
        if have >= args.per_field:
            continue
        rows = sorted((r for rows in pool.values() for r in rows
                       if field in r["asserts"] and r["source_key"] not in seen),
                      key=lambda r: r["source_key"])
        extra = rng.sample(rows, min(args.per_field - have, len(rows)))
        for row in extra:
            seen.add(row["source_key"])
            drawn.append(dict(row, stratum=f"thin/{field}"))
        if extra:
            print(f"    +{len(extra)} drawn to bring {field} to {have + len(extra)}")

    # Controls come from records not already drawn, so no record is both measured and a test.
    taken = {r["source_key"] for r in drawn}
    spare = sorted((r for rows in pool.values() for r in rows if r["source_key"] not in taken),
                   key=lambda r: r["source_key"])
    rng.shuffle(spare)

    planted_n = verbatim_n = 0
    planted_by = Counter()
    for row in spare:
        if planted_n >= args.controls and verbatim_n >= args.controls:
            break
        candidate = json.loads(json.dumps(row))
        # Planting is tried first. Both control kinds want a record whose grade word stands
        # in its text — the verbatim control to confirm the auditor passes it, the planted
        # one to flip it — and taking verbatim first consumed every such record, so no
        # planted control ever landed on the grade at all.
        if planted_n < args.controls:
            # Demand-driven, not round-robin. A grade can only be flipped where its own word
            # stands in the passage, which few records offer, so a fixed rotation spent its
            # grade slots on records that could not take one and the field ended with a
            # single control. Each record is offered to whichever field is furthest from its
            # quota and can actually be planted.
            prefer = max(QUOTA, key=lambda f: (QUOTA[f] - planted_by[f],
                                               -PLANT_ORDER.index(f)))
            change = plant(candidate, candidate["text_ar"], rng, prefer=prefer,
                           only=[f for f in PLANT_ORDER if planted_by[f] < QUOTA[f]])
            if change:
                drawn.append(dict(candidate, stratum="control/planted"))
                key_rows.append({"source_key": candidate["source_key"], "control": "planted",
                                 "field": change["field"], "expect": "flagged", **change})
                planted_n += 1
                planted_by[change["field"]] += 1
                continue
        if verbatim_n < args.controls and verbatim(candidate, candidate["text_ar"]):
            drawn.append(dict(candidate, stratum="control/verbatim"))
            key_rows.append({"source_key": candidate["source_key"], "control": "verbatim",
                             "field": "grade", "expect": "supported",
                             "grade": candidate["asserts"]["grade"]})
            verbatim_n += 1

    rng.shuffle(drawn)
    tasks = []
    for n, row in enumerate(drawn):
        task_id = f"attr:{n:04d}"
        tasks.append({
            "task_id": task_id,
            "heading_ar": row["heading_ar"],
            "book": row["book"],
            "pages": row["pages"],
            "text_ar": row["text_ar"],
            "asserts": row["asserts"],
        })
        key_rows.append({"task_id": task_id, "source_key": row["source_key"],
                         "stratum": row["stratum"], "book": row["book"], "kind": row["kind"],
                         "person_id": row["person_id"],
                         "fields": sorted(row["asserts"])})

    fields = Counter(f for r in drawn for f in r["asserts"])
    print(f"\n{len(tasks)} records drawn")
    print(f"    {planted_n} planted controls, {verbatim_n} verbatim controls")
    print("  questions by field:")
    for field in FIELDS:
        print(f"    {fields[field]:6,}  {field}")
    print(f"  total questions: {sum(fields.values()):,}")

    batches = pack(tasks, args.budget)
    print(f"\n{len(tasks)} tasks -> {len(batches)} batches "
          f"(largest {max(len(json.dumps(b, ensure_ascii=False)) for b in batches):,} chars)")
    if args.dry_run:
        return

    fingerprint = hashlib.sha256(
        json.dumps([t["task_id"] for t in tasks], sort_keys=True).encode()).hexdigest()[:16]
    name = f"attr-{len(tasks)}-{fingerprint}"
    run = os.path.join(args.out_dir, "runs", name)
    os.makedirs(os.path.join(run, "batches"), exist_ok=True)
    os.makedirs(os.path.join(run, "outputs"), exist_ok=True)
    os.makedirs(os.path.join(args.out_dir, "keys"), exist_ok=True)
    for n, batch in enumerate(batches):
        write_json_atomic(os.path.join(run, "batches", f"attr_{n:02d}.json"),
                          {"batch": f"attr_{n:02d}.json", "tasks": batch}, indent=1)
    write_json_atomic(os.path.join(run, "manifest.json"),
                      {"run": name, "seed": SEED, "tasks": len(tasks), "batches": len(batches),
                       "per_stratum": args.per_stratum,
                       "strata": {s: len(pool[s]) for s in sorted(pool)},
                       "questions": dict(fields)}, indent=1)
    write_json_atomic(os.path.join(args.out_dir, "keys", f"{name}.json"), key_rows, indent=1)
    print(f"\nwrote {len(batches)} batches -> {os.path.join(run, 'batches')}")
    print(f"key (not in the run)  -> {os.path.join(args.out_dir, 'keys', name + '.json')}")


if __name__ == "__main__":
    main()
