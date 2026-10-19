#!/usr/bin/env python3
"""Check the tagging agents' JSON-lines outputs and write them in import_tags_to_es.py's shape.

Every output must hold exactly its input's ids, in order, and only taggable slugs. A batch
that fails is reported and left out, so it can be rerun; nothing partial reaches the index.
docs/pipelines/topic-tagging.md describes the pass.

    python3 scripts/tagging/collect_tag_outputs.py --work-dir <work> [--out-dir <work>/output]
"""
import argparse
import json
import sys
from pathlib import Path

TAXONOMY = Path(__file__).resolve().parents[2] / "src/main/resources/static/taxonomy.json"


def taggable_slugs():
    return {t["slug"] for t in json.loads(TAXONOMY.read_text(encoding="utf-8"))
            if t.get("taggable", True) is not False}


def check(input_path: Path, output_path: Path, slugs: set) -> tuple[list, str | None]:
    ids = [json.loads(line)["id"] for line in input_path.open(encoding="utf-8") if line.strip()]
    if not output_path.exists():
        return [], "missing"
    rows = [json.loads(line) for line in output_path.open(encoding="utf-8") if line.strip()]
    if [r.get("id") for r in rows] != ids:
        return [], f"ids differ from the input ({len(rows)} lines for {len(ids)})"
    unknown = sorted({t for r in rows for t in r.get("tags", []) if t not in slugs})
    if unknown:
        return [], f"unknown or untaggable slugs {unknown}"
    return [{"id": r["id"], "tags": list(dict.fromkeys(r.get("tags", [])))} for r in rows], None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--work-dir", required=True, help="holds input250/ and output250/")
    parser.add_argument("--out-dir", help="default: <work-dir>/output")
    args = parser.parse_args()
    work = Path(args.work_dir)
    out = Path(args.out_dir or work / "output")
    out.mkdir(parents=True, exist_ok=True)
    slugs = taggable_slugs()
    good, bad, tagged, total = 0, {}, 0, 0
    for input_path in sorted((work / "input250").glob("batch_*.jsonl")):
        rows, problem = check(input_path, work / "output250" / input_path.name, slugs)
        if problem:
            bad[input_path.name] = problem
            continue
        good += 1
        total += len(rows)
        tagged += sum(1 for r in rows if r["tags"])
        name = "tags_" + input_path.stem + ".json"
        (out / name).write_text(json.dumps({"assignments": rows}, ensure_ascii=False) + "\n",
                                encoding="utf-8")
    print(json.dumps({"batches_ok": good, "narrations": total, "tagged": tagged, "problems": bad},
                     ensure_ascii=False, indent=1))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
