"""Check the answered batches of Layer 3 runs before recording: every task answered once, clusters exactly the offered ids, merge_with an offered id, verify verdicts valid.

Usage:
    python3 scripts/narrators/validate_run.py tmp/narrators_l3/runs/<run>...
"""
import glob, json, os, sys
from collections import Counter
for run in sys.argv[1:]:
    c, problems = Counter(), []
    for bpath in sorted(glob.glob(os.path.join(run, "batches", "*.json"))):
        opath = os.path.join(run, "outputs", os.path.basename(bpath))
        if not os.path.exists(opath):
            c["unanswered batches"] += 1; continue
        tasks = {t["task_id"]: t for t in json.load(open(bpath))["tasks"]}
        try:
            out = json.load(open(opath))
        except Exception as e:
            problems.append(f"{opath}: unreadable {e}"); continue
        seen = Counter(a.get("task_id") for a in out.get("decisions", []))
        for tid in tasks:
            if seen[tid] == 0: problems.append(f"{os.path.basename(opath)}: {tid} unanswered")
            elif seen[tid] > 1: problems.append(f"{os.path.basename(opath)}: {tid} answered {seen[tid]}x")
        for a in out.get("decisions", []):
            t = tasks.get(a.get("task_id"))
            if t is None:
                problems.append(f"{os.path.basename(opath)}: unknown {a.get('task_id')}"); continue
            k = t["kind"]; c[k] += 1
            if k == "group":
                offered = sorted(p["merged_id"] for p in t["profiles"])
                placed = sorted(m for cl in a.get("clusters", []) for m in cl)
                if placed != offered: problems.append(f"{os.path.basename(opath)}: {t['task_id']} partition mismatch")
                c["group joins"] += sum(len(cl) - 1 for cl in a.get("clusters", []))
            elif k == "pair":
                if a.get("same_person"):
                    if a.get("merge_with") not in [x["merged_id"] for x in t["candidates"]]:
                        problems.append(f"{os.path.basename(opath)}: {t['task_id']} merge_with not offered")
                    c[f"pair same {a.get('confidence')}"] += 1
                else: c["pair none"] += 1
            elif k == "verify":
                v = a.get("verdict")
                if v not in ("same", "different", "cannot_tell"): problems.append(f"{t['task_id']}: verdict {v!r}")
                c[f"verify {v} {a.get('confidence') if v != 'cannot_tell' else ''}".strip()] += 1
    print(os.path.basename(run.rstrip('/')), dict(sorted(c.items())))
    print("  problems:", len(problems), problems[:10])
