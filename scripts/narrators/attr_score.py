#!/usr/bin/env python3
"""Score the attribute audit: how much of what a narrator page states is in its sources.

The gate the identity audits answer to is about people. This one is about facts, and it is
reported per field, because the fields fail differently and a single headline figure would
hide that. A wrong death year is in the hero sentence; a wrong city is a line in the
ledger; a wrong grade is the whole reason anybody reads the page.

  supported     the passage states it
  partly        some values stand in the passage, some do not (the page overstates)
  absent        the passage says nothing about it (the page asserts what it cannot cite)
  contradicted  the passage says otherwise (the page is wrong)
  cannot_tell   the passage cannot be read

Precision counts `supported` against everything the auditor could judge. `partly` is counted
as a failure of the field and its unsupported values are listed, because a teacher list with
one invented name in it is a wrong teacher list. `cannot_tell` is excluded from the
denominator and reported beside it: a large share means the audit could not see, which is a
finding about the extraction's inputs rather than its output.

Two cuts matter beyond the fields:

  entry vs mention   a printed entry the page quotes, against a name read out of somebody
                     else's passage. The second carries the host entry's facts, which is the
                     suspected fault line and the reason the strata are built this way.
  grade_whose        of the grades that do stand in the passage, how many are the book's own
                     verdict and how many are it quoting another book. The page presents both
                     as that book's position, and the difference is the thing a rijali reader
                     would most want kept straight.

Controls are scored first and separately. Below CONTROL_PASS on either kind the auditors are
not trusted and the figures are not reported as measurements, whatever they say — an auditor
who waves through planted errors measures nothing, and so does one who flags everything.

Reads  tmp/narrators_audit/attr/runs/<run>/{batches,outputs}/*.json
       tmp/narrators_audit/attr/keys/<run>.json
Writes tmp/narrators_audit/attr/runs/<run>/score.json

Usage:
    python3 scripts/narrators/attr_score.py tmp/narrators_audit/attr/runs/<run>
"""
import argparse
from collections import Counter, defaultdict
import glob
import json
import os
import re
import sys

W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
sys.path.insert(0, f'{W}/scripts/narrators')
from identity import write_json_atomic  # noqa: E402
from index_people import city_ar  # noqa: E402
from narrator_schema import normalize_arabic  # noqa: E402

CONTROL_PASS = 0.90
FIELDS = ["grade", "teachers", "students", "city", "generation", "death_year"]
JUDGED = {"supported", "partly", "absent", "contradicted"}
FLAGGED = {"partly", "absent", "contradicted"}


def load(path):
    with open(path) as handle:
        return json.load(handle)


def posed_of(run_dir):
    """What each task actually showed the auditor: its passage and the facts it asserted."""
    shown = {}
    for path in sorted(glob.glob(os.path.join(run_dir, "batches", "*.json"))):
        for task in load(path).get("tasks", []):
            shown[task["task_id"]] = task
    return shown


def structural(task, field):
    """True when the fact could not have been in the passage shown, by the book's design.

    Rijal al-Tusi files a man by printing his name under a chapter heading — «أصحاب أبي عبد
    الله جعفر بن محمد الصادق عليهما السلام» — and the entry itself is often the bare name:
    «زيد بن معقل.» The generation is extracted from that heading, which is the book's own
    words and is traceable to it, but it is not in the passage the page quotes and so not in
    the passage an auditor was shown. Asked «does this text say he is a companion of
    al-Sadiq», the only honest answer is no, and three auditors gave it.

    That is a finding about the page, which asserts something its own quotation does not
    carry, and it is not a finding about the fact, which the parse places correctly: the 13
    headings fall in 25 contiguous runs over 6,552 records, with four single-record
    anomalies. Counting these as factual errors would measure this sampler, so they are
    counted apart.
    """
    if field != "generation":
        return False
    said = ((task.get("asserts") or {}).get("generation") or {}).get("said") or ""
    text = task.get("text_ar") or ""
    if not said:
        return False
    return normalize_arabic(said) not in normalize_arabic(text)


def stem(arabic):
    """Enough of an Arabic place name to find its nisbah: «الكوفة» -> «كوف», for «الكوفي»."""
    word = re.sub(r"^(ال)", "", normalize_arabic(arabic or "")).strip()
    return re.sub(r"(ه|ة|ا|ى|ي)$", "", word)


def in_heading(task, field):
    """True when the value stands in the entry's own heading but not in its body.

    Five auditors reported the same thing unprompted: a nisbah or a tribe that appears only
    in `heading_ar` — «الحسن بن محبوب الزراد الكوفي» — was marked absent, because the prompt
    told them that only `text_ar` counts. That instruction is right for a grade, which has to
    be asserted somewhere, and wrong for a city, which the books most often state by heading a
    man «الكوفي» and never mentioning Kufa again.

    The page prints the heading above the passage, so the reader can see this evidence. It is
    not the error the verdict reads as, and it is not a clean `supported` either: the fact
    rests on a nisbah, which names a tribe as readily as a town. Counted apart, and listed,
    so the mixing of cities with tribes and clientages can be dealt with as the one problem
    it is rather than as noise spread over a precision figure.
    """
    asserts = (task.get("asserts") or {}).get(field) or {}
    values = (asserts.get("values") or asserts.get("names")
              or ([asserts["value"]] if asserts.get("value") else []))
    heading = normalize_arabic(task.get("heading_ar") or "")
    body = normalize_arabic(task.get("text_ar") or "")
    if not heading or not values:
        return False
    for value in values:
        if not isinstance(value, str):
            continue
        arabic = city_ar(value) if field == "city" else value
        needle = stem(arabic) if field == "city" else normalize_arabic(value)
        if len(needle) < 3:
            continue
        if needle in heading and needle not in body:
            return True
    return False


def answers_of(run_dir):
    """Every judgement, by task id. A task answered twice is a dropped batch rerun."""
    found, counts = {}, Counter()
    paths = sorted(glob.glob(os.path.join(run_dir, "outputs", "*.json")))
    for path in paths:
        try:
            body = load(path)
        except (json.JSONDecodeError, OSError) as problem:
            counts[f"unreadable output: {os.path.basename(path)} ({problem.__class__.__name__})"] += 1
            continue
        for item in body.get("judgements") or []:
            task = item.get("task_id")
            if not task:
                counts["judgement with no task_id"] += 1
                continue
            if task in found:
                counts["task answered more than once"] += 1
            found[task] = item.get("fields") or {}
    return found, counts, len(paths)


def control_score(key_rows, answers):
    """Did the auditors catch the planted errors, and pass the verbatim ones?"""
    out = {}
    by_key = {r["source_key"]: r for r in key_rows if r.get("control")}
    task_of = {r["source_key"]: r["task_id"] for r in key_rows if r.get("task_id")}
    for kind in ("planted", "verbatim"):
        right = wrong = missing = 0
        misses = []
        for source_key, control in by_key.items():
            if control["control"] != kind:
                continue
            task = task_of.get(source_key)
            fields = answers.get(task)
            if fields is None:
                missing += 1
                continue
            got = (fields.get(control["field"]) or {}).get("verdict")
            if kind == "planted":
                # flagged any way at all is a pass: the auditor saw the value does not stand
                ok = got in FLAGGED
            else:
                ok = got == "supported"
            if ok:
                right += 1
            else:
                wrong += 1
                misses.append({"task_id": task, "source_key": source_key,
                               "field": control["field"], "said": got,
                               "expected": control["expect"],
                               "note": (fields.get(control["field"]) or {}).get("note", "")})
        n = right + wrong
        out[kind] = {"audited": n, "right": right, "wrong": wrong, "unanswered": missing,
                     "rate": round(right / n, 4) if n else 0.0,
                     "passed": bool(n) and right / n >= CONTROL_PASS,
                     "misses": misses}
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("run_dir")
    parser.add_argument("--keys-dir", default=os.path.join(W, "tmp", "narrators_audit", "attr", "keys"))
    args = parser.parse_args()

    run = os.path.basename(args.run_dir.rstrip("/"))
    key_rows = load(os.path.join(args.keys_dir, f"{run}.json"))
    answers, problems, batches_in = answers_of(args.run_dir)
    posed = posed_of(args.run_dir)
    measured = [r for r in key_rows if r.get("task_id")]
    controls = {r["source_key"] for r in key_rows if r.get("control")}

    print(f"{run}")
    print(f"  {batches_in} output files, {len(answers):,} tasks answered of {len(measured):,} posed")
    for label, n in problems.most_common():
        print(f"  !! {n}  {label}")

    scores = control_score(key_rows, answers)
    print("\ncontrols")
    for kind in ("planted", "verbatim"):
        s = scores[kind]
        print(f"  {kind:9s} {s['right']:3d}/{s['audited']:<3d} {s['rate']:>7.1%}"
              f"  {'PASS' if s['passed'] else 'FAIL'}"
              + (f"   ({s['unanswered']} unanswered)" if s["unanswered"] else ""))
    trusted = scores["planted"]["passed"] and scores["verbatim"]["passed"]

    # The measurement, with the controls held out of it.
    by_field = defaultdict(Counter)
    by_kind = defaultdict(lambda: defaultdict(Counter))
    by_book = defaultdict(lambda: defaultdict(Counter))
    whose = Counter()
    from_structure = defaultdict(Counter)
    from_heading = defaultdict(Counter)
    unanswered = Counter()
    failures = []
    for row in measured:
        if row["source_key"] in controls:
            continue
        fields = answers.get(row["task_id"])
        if fields is None:
            unanswered["tasks"] += 1
            unanswered["questions"] += len(row["fields"])
            continue
        for field in row["fields"]:
            got = fields.get(field) or {}
            verdict = got.get("verdict")
            if verdict not in JUDGED | {"cannot_tell"}:
                by_field[field]["no answer"] += 1
                continue
            task = posed.get(row["task_id"]) or {}
            if structural(task, field):
                from_structure[row["book"]][verdict] += 1
                continue
            if verdict in FLAGGED and in_heading(task, field):
                from_heading[field][verdict] += 1
                continue
            by_field[field][verdict] += 1
            by_kind[row["kind"]][field][verdict] += 1
            by_book[row["book"]][field][verdict] += 1
            if field == "grade" and verdict in ("supported", "partly"):
                whose[got.get("grade_whose") or "unstated"] += 1
            if verdict in FLAGGED:
                failures.append({"task_id": row["task_id"], "source_key": row["source_key"],
                                 "person_id": row["person_id"], "book": row["book"],
                                 "kind": row["kind"], "field": field, "verdict": verdict,
                                 "unsupported": got.get("unsupported"),
                                 "note": got.get("note", "")})

    def precision(counter):
        judged = sum(counter[v] for v in JUDGED)
        return (counter["supported"] / judged) if judged else None, judged

    def line(label, counter, width=14):
        rate, judged = precision(counter)
        shown = f"{rate:>7.1%}" if rate is not None else "      -"
        return (f"  {label:{width}s}{shown}  of {judged:4d} judged"
                f"   partly {counter['partly']:3d}  absent {counter['absent']:3d}"
                f"  contra {counter['contradicted']:3d}  unreadable {counter['cannot_tell']:3d}")

    print("\nby field — the share of facts the passage actually states")
    for field in FIELDS:
        if sum(by_field[field].values()):
            print(line(field, by_field[field]))
    overall = Counter()
    for field in FIELDS:
        overall.update(by_field[field])
    print(line("ALL", overall))

    print("\nby record kind")
    for kind in ("entry", "mention"):
        rolled = Counter()
        for field in FIELDS:
            rolled.update(by_kind[kind][field])
        if sum(rolled.values()):
            print(line(kind, rolled))

    print("\nby book")
    for book in sorted(by_book):
        rolled = Counter()
        for field in FIELDS:
            rolled.update(by_book[book][field])
        print(line(book, rolled))

    if from_structure:
        rolled = Counter()
        for book in from_structure:
            rolled.update(from_structure[book])
        print("\ncounted apart: the generation taken from the book's section heading, which is"
              "\nits own words but stands outside the passage the page quotes")
        for book in sorted(from_structure):
            counter = from_structure[book]
            print(f"  {book:14s}{sum(counter.values()):4d} questions   "
                  + "  ".join(f"{v} {k}" for k, v in counter.most_common()))
        print(f"  {'in all':14s}{sum(rolled.values()):4d} questions   "
              f"{rolled['absent']} of them absent from the quoted passage")

    if from_heading:
        rolled = Counter()
        for field in from_heading:
            rolled.update(from_heading[field])
        print("\ncounted apart: stated in the entry's heading, which the page prints above the"
              "\npassage, but not in the passage itself")
        for field in sorted(from_heading):
            counter = from_heading[field]
            print(f"  {field:14s}{sum(counter.values()):4d} questions   "
                  + "  ".join(f"{v} {k}" for k, v in counter.most_common()))
        print(f"  {'in all':14s}{sum(rolled.values()):4d} questions")

    print("\nwhose verdict the grade is, where the passage does state one")
    total = sum(whose.values())
    for label, n in whose.most_common():
        print(f"  {label:12s}{n:5d}  {n / total:6.1%}" if total else f"  {label}: {n}")

    if unanswered["tasks"]:
        print(f"\n!! {unanswered['tasks']} tasks unanswered, {unanswered['questions']} questions lost")

    report = {
        "run": run, "trusted": trusted, "controls": scores,
        "answered": len(answers), "posed": len(measured),
        "by_field": {f: dict(by_field[f]) for f in FIELDS if sum(by_field[f].values())},
        "by_kind": {k: {f: dict(v) for f, v in by_kind[k].items()} for k in by_kind},
        "by_book": {b: {f: dict(v) for f, v in by_book[b].items()} for b in by_book},
        "precision": {f: precision(by_field[f])[0] for f in FIELDS
                      if sum(by_field[f].values())},
        "grade_whose": dict(whose),
        "from_section_heading": {b: dict(c) for b, c in from_structure.items()},
        "from_entry_heading": {f: dict(c) for f, c in from_heading.items()},
        "unanswered": dict(unanswered),
        "failures": failures,
        "problems": dict(problems),
    }
    write_json_atomic(os.path.join(args.run_dir, "score.json"), report, indent=1)
    print(f"\n{len(failures)} findings -> {os.path.join(args.run_dir, 'score.json')}")
    if not trusted:
        print("\nCONTROLS FAILED — these figures are not a measurement. Re-run the auditors.")


if __name__ == "__main__":
    main()
