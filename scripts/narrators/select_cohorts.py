#!/usr/bin/env python3
"""The two named cohorts the accuracy audit judges beside its random sample.

A random sample measures the corpus. It does not measure the part of the corpus anyone
will actually read: a reader who looks up a narrator looks up Zurara, Sahl b. Ziyad, Ibn
Abi 'Umayr. Those men also carry the hardest identity problems — the ones the books write
most about are the ones with the most name forms, the most entries and the most homonyms —
so they are both the likeliest to be wrong and the costliest to get wrong.

  discussed   The hundred men rijali argument actually runs on. The Ashab al-Ijma' first,
              because Kashshi's three groups of six are the spine of the discipline; then
              the men whose reliability is a standing dispute; then, to fill the hundred,
              whoever the eight books write most about, which is the same question measured
              rather than recalled.

  impossible  Every person the books file under Imams no one life could reach — see
              measure_lifetimes.py. These are not a sample of anything: they are a census
              of the people we can already prove are wrong, or whose generation statements
              are. Judging them says which.

Neither cohort is a random draw, so neither belongs in the weighted gate; audit_score.py
reports them on their own. The point of auditing them is not the headline figure, it is the
list of people to go and mend.

Writes tmp/narrators_audit/cohorts/{discussed,impossible}.txt — one person id per line.
"""
import argparse
import bisect
import json
import os
import sys

W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
sys.path.insert(0, f'{W}/scripts/narrators')
from narrator_schema import imam_generations, normalize_arabic  # noqa: E402

PEOPLE = f'{W}/tmp/narrators_identity/people.json'
CHRONOLOGY = f'{W}/src/main/resources/data/chronology.json'
OUT = f'{W}/tmp/narrators_audit/cohorts'
LONG_LIFE = 90

# Kashshi's three groups of six: the companions the discipline agreed to accept whatever
# their chains, which is why every argument about them is an argument about the method.
ASHAB_AL_IJMA = [
    "زرارة بن أعين", "معروف بن خربوذ", "بريد بن معاوية العجلي", "أبو بصير الأسدي",
    "الفضيل بن يسار", "محمد بن مسلم", "جميل بن دراج", "عبد الله بن مسكان",
    "عبد الله بن بكير", "حماد بن عيسى", "حماد بن عثمان", "أبان بن عثمان",
    "يونس بن عبد الرحمن", "صفوان بن يحيى", "محمد بن أبي عمير", "عبد الله بن المغيرة",
    "الحسن بن محبوب", "أحمد بن محمد بن أبي نصر البزنطي",
]

# The standing disputes: men accused of ghuluww, men one book trusts and another damns, and
# the shuyukh so much of al-Kafi rests on that a verdict on them is a verdict on the book.
DISPUTED = [
    "سهل بن زياد", "محمد بن سنان", "المفضل بن عمر", "علي بن أبي حمزة البطائني",
    "محمد بن أبي زينب", "معلى بن خنيس", "جابر بن يزيد الجعفي", "محمد بن عيسى بن عبيد",
    "أحمد بن محمد بن خالد البرقي", "إبراهيم بن هاشم", "محمد بن الحسن الصفار",
    "الحسين بن سعيد", "أحمد بن محمد بن عيسى", "علي بن إبراهيم بن هاشم",
    "محمد بن يحيى العطار", "الفضل بن شاذان", "سالم بن مكرم", "عمر بن حنظلة",
    "داود بن كثير الرقي", "يونس بن ظبيان", "محمد بن الفضيل", "زياد بن مروان القندي",
    "عثمان بن عيسى", "الحسن بن علي بن فضال", "علي بن الحسن بن فضال", "أبان بن تغلب",
    "هشام بن الحكم", "هشام بن سالم", "محمد بن علي بن النعمان", "بكير بن أعين",
    "حمران بن أعين", "عبيد بن زرارة", "إسحاق بن عمار", "معاوية بن عمار",
    "عبد الله بن سنان", "حريز بن عبد الله", "عبد الله بن أبي يعفور",
    "يحيى بن القاسم", "سماعة بن مهران", "منصور بن حازم", "علي بن مهزيار",
    "محمد بن الحسين بن أبي الخطاب", "سعد بن عبد الله", "عبد الله بن جعفر الحميري",
    "محمد بن أحمد بن يحيى", "إسماعيل بن أبي زياد السكوني", "طلحة بن زيد",
    "غياث بن إبراهيم", "وهب بن وهب", "عمرو بن شمر", "المنخل بن جميل",
    "محمد بن جمهور", "جعفر بن محمد بن مالك", "محمد بن أورمة", "زكريا بن آدم",
    "الحسن بن علي الوشاء", "أيوب بن نوح", "الحسين بن سعيد الأهوازي",
    "عبد الرحمن بن الحجاج", "أبان بن تغلب بن رباح",
]

TARGET = 100


def load(path):
    with open(path) as handle:
        return json.load(handle)


def forms_of(person):
    """Every Arabic name any book heads this man under, normalised."""
    found = set()
    for field in ("primary_names", "arabic_aliases"):
        for item in person.get(field) or []:
            value = item.get("value") if isinstance(item, dict) else item
            if value:
                found.add(normalize_arabic(value))
    if person.get("primary_arabic_name"):
        found.add(normalize_arabic(person["primary_arabic_name"]))
    return found


def index_forms(people):
    """Every name form in the corpus, sorted, each with the people who answer to it.

    Built once. Resolving 78 canonical names against 32,000 people by scanning was twenty
    million normalisations and some minutes; a sorted index makes each lookup a bisect.
    """
    found = {}
    for person in people:
        if person.get("holds_entry") is False:
            continue
        for form in forms_of(person):
            found.setdefault(form, []).append(person)
    return sorted(found), found


def resolve(wanted, order, by_form):
    """The person a canonical name names: the best-documented holder of that name form.

    A name is matched whole, or as the opening of a longer heading — «زرارة بن أعين» finds
    «زرارة بن أعين بن سنسن الشيباني» — because the books head one man at several lengths and
    the short form is the one a scholar says out loud. Where several people answer to it the
    one with the most sources is taken: a man the books wrote eight entries about is the one
    meant, not his single-mention namesake.
    """
    target = normalize_arabic(wanted)
    hits = list(by_form.get(target, ()))
    start = bisect.bisect_left(order, target + " ")
    for form in order[start:]:
        if not form.startswith(target + " "):
            break
        hits.extend(by_form[form])
    if not hits:
        return None
    # The heading a book prints beats an alias that merely appears on the record. Zurara's
    # person carries «محمد بن مسلم» among its aliases — harvested from a chain note, one of
    # them reading «محمد بن مسلم مضمرة», which is a form of narration and not a name — and on
    # source count alone Zurara answered to Muhammad b. Muslim and the real man was dropped
    # from the cohort. Whoever the books actually head under the name comes first.
    def rank(person):
        heading = normalize_arabic(person.get("primary_arabic_name") or "")
        named = heading == target or heading.startswith(target + " ")
        return (1 if named else 0, len(person["source_keys"]))

    return max(hits, key=rank)


def impossible_people(people, years, limit=LONG_LIFE):
    out = []
    for person in people:
        got = set()
        for statement in person.get("generations") or []:
            value = statement.get("value") if isinstance(statement, dict) else statement
            if value:
                got |= imam_generations(value)
        got = sorted(o for o in got if o in years)
        if len(got) < 2:
            continue
        if years[got[-1]][0] - years[got[0]][1] > limit:
            out.append(person)
    out.sort(key=lambda p: -len(p["source_keys"]))
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--people", default=PEOPLE)
    parser.add_argument("--out-dir", default=OUT)
    parser.add_argument("--target", type=int, default=TARGET)
    parser.add_argument("--dry-run", action="store_true", help="report, write nothing")
    args = parser.parse_args()

    people = load(args.people)
    years = {e["order"]: (e["fromAh"], e["toAh"])
             for e in load(CHRONOLOGY)["eras"]}

    order, by_form = index_forms(people)
    chosen, seen, missed, collided = [], set(), [], []
    for label, names in (("ashab al-ijma", ASHAB_AL_IJMA), ("disputed", DISPUTED)):
        found = 0
        for name in names:
            person = resolve(name, order, by_form)
            if person is None:
                missed.append((label, name))
                continue
            if person["person_id"] in seen:
                # Two canonical names reaching one person. Sometimes right — «الحسين بن سعيد»
                # and «الحسين بن سعيد الأهوازي» are one man — and sometimes an over-merge
                # caught in the act, so it is printed rather than silently skipped.
                collided.append((label, name, person))
                continue
            seen.add(person["person_id"])
            chosen.append((label, name, person))
            found += 1
        print(f"  {found:3d}/{len(names)}  {label}")

    # The rest of the hundred: whoever the eight books write most about. «Most discussed»
    # named by hand and «most discussed» measured should mostly agree, and where they do not
    # the measured half is the half that cannot be forgotten.
    by_entries = sorted((p for p in people if p.get("holds_entry") is not False),
                        key=lambda p: -len(p["source_keys"]))
    added = 0
    for person in by_entries:
        if len(chosen) >= args.target:
            break
        if person["person_id"] in seen:
            continue
        seen.add(person["person_id"])
        chosen.append(("most written about", person.get("primary_arabic_name") or "", person))
        added += 1
    print(f"  {added:3d}      most written about (to reach {args.target})")

    if collided:
        print(f"\n{len(collided)} canonical names reached a person another name already took —"
              f" one man under two names, or two men wrongly joined:")
        for label, name, person in collided:
            taken = next(n for _l, n, p in chosen if p["person_id"] == person["person_id"])
            print(f"    «{name}» -> {person['person_id']}, already taken by «{taken}»")

    if missed:
        print(f"\n{len(missed)} canonical names matched nobody — check the spelling or the"
              f" extraction:")
        for label, name in missed:
            print(f"    {label}: {name}")

    impossible = impossible_people(people, years)
    print(f"\ndiscussed  {len(chosen)} people")
    print(f"impossible {len(impossible)} people")
    print("\nthe discussed cohort, largest first:")
    for label, name, person in sorted(chosen, key=lambda c: -len(c[2]["source_keys"]))[:12]:
        print(f"    {len(person['source_keys']):3d} src  {person['person_id']}  "
              f"{(person.get('primary_arabic_name') or '')[:38]:38s}  [{label}]")

    if args.dry_run:
        return
    os.makedirs(args.out_dir, exist_ok=True)
    for name, rows in (("discussed", [p for _l, _n, p in chosen]), ("impossible", impossible)):
        path = os.path.join(args.out_dir, f"{name}.txt")
        with open(path, "w") as handle:
            handle.write("".join(f"{p['person_id']}\n" for p in rows))
        print(f"wrote {len(rows):4d} -> {path}")
    with open(os.path.join(args.out_dir, "discussed-why.json"), "w") as handle:
        json.dump([{"person_id": p["person_id"], "why": label, "asked_for": name,
                    "sources": len(p["source_keys"]),
                    "name": p.get("primary_arabic_name")} for label, name, p in chosen],
                  handle, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
