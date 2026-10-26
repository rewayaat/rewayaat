#!/usr/bin/env python3
"""Score the stage 5 accuracy audit against its hidden key.

  join precision   per method: same / answered (strict: `cannot_tell` counts as unverified)
                   and same / (same + different) (lenient), each with a Wilson 95% interval
  controls         the share of positive controls read as same and negative controls as
                   different; below CONTROL_PASS the auditors are not trusted
  people precision per size band: people with no outlier entry / people audited, and overall,
                   weighted by each band's share of the population
  remaining splits per group: people found to have a double among their candidates

Two precisions are reported, because a person is assembled from more records than the page
quotes. `is_entry` decides which of a man's records are entries: a main book's heading, or a
Khoei profile marked as a printed entry. Everything else attached to him is a chain record —
Khoei's index stubs, mostly, whose whole text is «and so-and-so narrated from him» and whose
heading was lifted out of that clause, so it describes a man the entry is not about.

  entries     the records quoted on the page, each with its book and page. A stray here is a
              verdict shown under the wrong man, which is the failure the project exists to
              prevent, so the gate is taken on this.
  attached    every record joined to him. A stray here is never quoted, but it still brings
              its teachers and students, so it shows in the chains tab and in the ledger's
              counts. It is a real fault and a milder one.

The distinction is not academic. On the 11+ band the two read 98.2% and 81.8% from the same
answers: fourteen of fifteen flagged records were ones no reader can see. Reporting only the
second understated the pages by sixteen points; reporting only the first would hide a fault
that is visibly there in the counts.

The gate (docs/proposals/narrator-system.md, "The accuracy audit"): entry precision of at
least GATE in the hardest size band, with both kinds of control passed.

The gate used to run on the population-weighted figure, and that figure cannot fail. Two
thirds of the people hold two or three entries, those score 99-100%, and they carry the
average whatever happens to anyone else: 97.3% over the whole corpus and 97.1% over people
no auditor had seen, while the band a reader actually reads sat at 86-87.5% in both. An
average that cannot move is not a gate. It is reported still, because the corpus-wide number
is the honest thing to publish, but what has to clear the bar is GATE_BAND — a man with
fifteen entries has fifteen chances to absorb a namesake, and he is the man someone looks up.

Writes <run>/report.json and <run>/errors.json (every join judged different, every outlier
entry, every double found), for review; the audit itself changes no identity.

Usage:
    python3 scripts/narrators/audit_score.py tmp/narrators_audit/runs/audit-<fp>
"""

import argparse
from collections import Counter, defaultdict
import glob
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from identity import write_json_atomic  # noqa: E402

GATE = 0.95
# The band the gate is taken on. Error tracks entry count, not fame: 87.5% among the hundred
# most-discussed and 86.0% among people nobody has heard of, measured a day apart.
GATE_BAND = "11+"
CONTROL_PASS = 0.90


def wilson(successes, n, z=1.96):
    if n == 0:
        return (None, None)
    p = successes / n
    centre = (p + z * z / (2 * n)) / (1 + z * z / n)
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / (1 + z * z / n)
    return (round(centre - half, 3), round(centre + half, 3))


def share(successes, n):
    return {"n": n, "rate": round(successes / n, 3) if n else None,
            "interval_95": wilson(successes, n)}


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("run_dir")
    parser.add_argument("--keys-dir", default=None)
    args = parser.parse_args()
    name = os.path.basename(os.path.normpath(args.run_dir))
    keys_dir = args.keys_dir or os.path.join(os.path.dirname(os.path.dirname(
        os.path.normpath(args.run_dir))), "keys")
    with open(os.path.join(keys_dir, f"{name}.json")) as handle:
        key_file = json.load(handle)
    key = key_file["items"]

    offered, answers, problems = {}, {}, []
    for path in sorted(glob.glob(os.path.join(args.run_dir, "batches", "*.json"))):
        with open(path) as handle:
            for item in json.load(handle)["items"]:
                offered[item["item_id"]] = item
    for path in sorted(glob.glob(os.path.join(args.run_dir, "outputs", "*.json"))):
        with open(path) as handle:
            for answer in json.load(handle).get("answers", []):
                item_id = answer.get("item_id")
                if item_id not in offered:
                    problems.append(f"{os.path.basename(path)}: unknown item {item_id}")
                elif item_id in answers:
                    problems.append(f"{os.path.basename(path)}: {item_id} answered twice")
                else:
                    answers[item_id] = answer
    missing = sorted(set(offered) - set(answers))
    if missing:
        problems.append(f"{len(missing)} items unanswered, e.g. {missing[:5]}")

    strata, controls = defaultdict(Counter), defaultdict(Counter)
    bands, groups = defaultdict(Counter), defaultdict(Counter)
    # which flagged records a reader would actually see quoted
    import sys as _sys
    _sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from build_people import is_entry, not_entries, load_sources  # noqa: E402
    _tmp = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                         "..", "..", "tmp"))
    _raw = load_sources(os.path.join(_tmp, "narrators_normalized"))
    _profiles = {k: (v[1] if isinstance(v, tuple) else v) for k, v in _raw.items()}
    _headless = not_entries(_tmp)

    def quoted(keys):
        return any(is_entry(k, _profiles.get(k), _headless) for k in (keys or ()))
    errors = []
    for item_id, meta in key.items():
        if meta["kind"] == "recall" and meta.get("no_candidates"):
            groups[meta["group"]]["audited"] += 1
            groups[meta["group"]]["no candidates"] += 1
            continue
        answer = answers.get(item_id)
        if answer is None:
            continue
        if meta["kind"] == "pair":
            verdict = answer.get("verdict")
            if verdict not in ("same", "different", "cannot_tell"):
                problems.append(f"{item_id}: verdict {verdict!r}")
                continue
            target = controls[meta["control"]] if meta.get("control") else strata[meta["stratum"]]
            target[verdict] += 1
            if verdict == "different" and not meta.get("control"):
                errors.append({"kind": "join judged different", "item_id": item_id,
                               "stratum": meta["stratum"], "decision_id": meta["decision_id"],
                               "person_id": meta["person_id"], "A": meta["A"], "B": meta["B"],
                               "confidence": answer.get("confidence"),
                               "reason": answer.get("reason")})
        elif meta["kind"] == "person":
            outliers = [u for u in answer.get("outliers", []) if u in meta["units"]]
            unclear = [u for u in answer.get("unclear", []) if u in meta["units"]]
            band = bands[meta["band"]]
            band["audited"] += 1
            band["clean" if not outliers else "with outlier"] += 1
            # the same answer, counted again over the records the page actually quotes
            shown = [u for u in outliers if quoted(meta["units"][u])]
            band["clean of a quoted entry" if not shown else "with a quoted outlier"] += 1
            if unclear and not outliers:
                band["clean but unclear entries"] += 1
            for unit in outliers:
                errors.append({"kind": "outlier entry", "item_id": item_id,
                               "person_id": meta["person_id"], "entry": meta["units"][unit],
                               "confidence": answer.get("confidence"),
                               "reason": answer.get("reason")})
        elif meta["kind"] == "recall":
            same_as = [c for c in answer.get("same_as", []) if c in meta["candidates"]]
            group = groups[meta["group"]]
            group["audited"] += 1
            group["has a double" if same_as else "no double"] += 1
            for c in same_as:
                errors.append({"kind": "double found", "item_id": item_id,
                               "person_id": meta["person_id"], "double": meta["candidates"][c],
                               "confidence": answer.get("confidence"),
                               "reason": answer.get("reason")})

    report = {"run": name, "problems": problems, "join_precision": {}, "controls": {},
              "people_precision": {}, "remaining_splits": {}}
    for stratum, c in strata.items():
        n = sum(c.values())
        decided = c["same"] + c["different"]
        report["join_precision"][stratum] = {
            "counts": dict(c), "strict": share(c["same"], n), "lenient": share(c["same"], decided)}
    # an audit that carried no controls has not shown its auditors can be trusted
    passed = {"positive", "negative"} <= set(controls)
    for kind, c in controls.items():
        n = sum(c.values())
        right = c["same"] if kind == "positive" else c["different"]
        report["controls"][kind] = {"counts": dict(c), **share(right, n)}
        passed = passed and n > 0 and right / n >= CONTROL_PASS
    population = key_file["person_band_population"]
    # A cohort is a census of a group chosen for being hard — the men rijali argument runs
    # on, the men whose Imams span more than a life. Folding either into the weighted figure
    # would be reporting a deliberately adversarial sample as if it were the corpus, in both
    # directions: it would drag the headline down, and it would hide how bad the cohort is
    # behind the 32,000 people it was not drawn from. They are scored on their own.
    cohorts = set(key_file.get("cohorts") or ())
    sampled = {b: n for b, n in population.items() if b not in cohorts}
    total_population = sum(sampled.values())
    weighted = 0.0
    report["cohort_precision"] = {}
    for band, c in bands.items():
        where = report["cohort_precision"] if band in cohorts else report["people_precision"]
        where[band] = {"counts": dict(c), **share(c["clean"], c["audited"]),
                       "entries": share(c["clean of a quoted entry"], c["audited"]),
                       "population": population.get(band)}
        if c["audited"] and band not in cohorts:
            weighted += population[band] / total_population * c["clean"] / c["audited"]
    report["people_precision"]["weighted"] = round(weighted, 3)
    for group, c in groups.items():
        report["remaining_splits"][group] = {"counts": dict(c),
                                             **share(c["has a double"], c["audited"])}
    report["controls_passed"] = passed
    # a run drawn from one stratum has no people in it, so the gate is not what it measures
    hardest = bands.get(GATE_BAND, Counter())
    measured = bool(hardest["audited"])
    # on the entries quoted, not on every record attached: a stray chain stub is a fault but
    # it is not a verdict shown under the wrong man, and only the latter can fail publication
    band_precision = (hardest["clean of a quoted entry"] / hardest["audited"]) if measured else 0.0
    attached_precision = (hardest["clean"] / hardest["audited"]) if measured else 0.0
    report["gate"] = {"threshold": GATE, "band": GATE_BAND,
                      "band_precision": round(band_precision, 3),
                      "band_audited": hardest["audited"],
                      "band_attached_precision": round(attached_precision, 3),
                      "people_precision": round(weighted, 3),
                      "people_measured": measured,
                      "met": measured and passed and band_precision >= GATE}

    write_json_atomic(os.path.join(args.run_dir, "report.json"), report, indent=1)
    write_json_atomic(os.path.join(args.run_dir, "errors.json"), errors, indent=1)

    print(f"problems: {len(problems)}" + ("".join(f"\n  {p}" for p in problems[:10])))
    if report["cohort_precision"]:
        print("\ncohorts (judged whole, and deliberately the hard ones — not in the gate):")
        for name in sorted(report["cohort_precision"]):
            row = report["cohort_precision"][name]
            print(f"  {name:14s} {row['counts'].get('clean', 0):4d} clean of "
                  f"{row['counts'].get('audited', 0):4d} = {row.get('share')}")

    print("\njoin precision (strict = cannot_tell unverified; lenient = left out):")
    for stratum in sorted(report["join_precision"]):
        r = report["join_precision"][stratum]
        print(f"  {stratum:18s} strict {r['strict']['rate']} {r['strict']['interval_95']}  "
              f"lenient {r['lenient']['rate']} {r['lenient']['interval_95']}  {r['counts']}")
    print("\ncontrols:")
    for kind, r in report["controls"].items():
        print(f"  {kind:9s} {r['rate']} {r['interval_95']} {r['counts']}")
    print(f"  passed: {passed} (each at least {CONTROL_PASS})")
    print("\npeople precision (entries quoted on the page | every record attached):")
    for band in ("2-3", "4-10", "11+"):
        r = report["people_precision"].get(band)
        if r:
            print(f"  {band:5s} entries {r['entries']['rate']} {r['entries']['interval_95']}"
                  f"   attached {r['rate']} {r['interval_95']}   of {r['population']}")
            continue
        if r:
            print(f"  {band:5s} {r['rate']} {r['interval_95']} {r['counts']} of {r['population']}")
    print(f"  weighted by population: {report['people_precision']['weighted']}")
    print("\nremaining splits:")
    for group, r in report["remaining_splits"].items():
        print(f"  {group:10s} {r['rate']} {r['interval_95']} {r['counts']}")
    if measured:
        print(f"\nGATE ({GATE_BAND} band precision >= {GATE}, controls passed): "
              f"{'MET' if report['gate']['met'] else 'NOT MET'}"
              f"  [{GATE_BAND} entries {report['gate']['band_precision']} of "
              f"{report['gate']['band_audited']} audited; every record attached "
              f"{report['gate']['band_attached_precision']}]")
    else:
        print(f"\nGATE: not measured — this run drew nobody in the {GATE_BAND} band")
    print(f"{len(errors)} errors -> {os.path.join(args.run_dir, 'errors.json')}")


if __name__ == "__main__":
    main()
