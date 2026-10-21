#!/usr/bin/env python3
"""The reader check on Mishkat al-Anwar's edition pairs: cut the batches, merge the verdicts.

align_mishkat_openiti.py pairs every v1 narration with an edition narration. A reader (a
Sonnet agent, prompt in docs/pipelines/v1-restore-quality.md "Mishkat") then rules on
whether the v1 English translates that Arabic. Verdicts, and what each does to the pair:

  match              the English translates the Arabic             -> approved
  span               it translates only part of the edition text;  -> approved, Arabic
                     "arabic_span" is that part, copied exactly       narrowed to the span
  omission           one side lacks a clause; each still reads as  -> approved (kept, as
                     a complete narration                             for Ghurar and Mizan)
  english-truncated  the English stops mid-sentence, or a missing  -> rejected
                     clause changes the meaning
  mismatch           a different narration                         -> rejected

English fixes go through validate_fix like every other agent fix, into the text-fixes file.

    python3 scripts/ingest/review_mishkat_edition.py prepare --batches <converter output> \\
        --openiti <edition file> --out-dir <work>/mishkat
    python3 scripts/ingest/review_mishkat_edition.py merge --batches <converter output> \\
        --work-dir <work>/mishkat
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from align_mishkat_openiti import parse_edition, read_narrations, tidy  # noqa: E402
from convert_v1_books import validate_fix  # noqa: E402

EDITION_FILE = Path(__file__).with_name("v1_restore_mishkat_edition.json")
FIXES_FILE = Path(__file__).with_name("v1_restore_text_fixes.json")
BATCH = 96
APPROVE = {"match", "span", "omission"}
REJECT = {"english-truncated", "mismatch"}


def prepare(args) -> int:
    edition = json.loads(EDITION_FILE.read_text(encoding="utf-8"))
    units = {u["index"]: u for u in parse_edition(Path(args.openiti))}
    docs = read_narrations(Path(args.batches))
    rows = []
    for n, doc in enumerate(docs):
        record = edition["narrations"].get(doc["_id"])
        if not record:
            continue
        src = doc["_source"]
        row = {"id": doc["_id"], "number": src["number"], "chapter": src["chapter"],
               "kind": record["verdict"], "english": src["english"], "arabic": record["arabic"],
               "v1_arabic": src["arabic"],
               "prev_english": docs[n - 1]["_source"]["english"][:120] if n else "",
               "next_english": docs[n + 1]["_source"]["english"][:120] if n + 1 < len(docs) else ""}
        full = units[record["edition_index"]]["arabic"]
        if full != record["arabic"]:
            row["edition_full"] = full
        rows.append(row)
    out = Path(args.out_dir)
    (out / "out").mkdir(parents=True, exist_ok=True)
    for k, start in enumerate(range(0, len(rows), BATCH), start=1):
        with open(out / f"batch_{k:03d}.jsonl", "w", encoding="utf-8") as f:
            for row in rows[start:start + BATCH]:
                f.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(json.dumps({"narrations": len(rows), "batches": (len(rows) + BATCH - 1) // BATCH,
                      "kinds": dict(Counter(r["kind"] for r in rows))}))
    return 0


def merge(args) -> int:
    edition = json.loads(EDITION_FILE.read_text(encoding="utf-8"))
    fixes = json.loads(FIXES_FILE.read_text(encoding="utf-8"))
    work = Path(args.work_dir)
    inputs = {}
    for path in sorted(work.glob("batch_*.jsonl")):
        for line in path.open(encoding="utf-8"):
            row = json.loads(line)
            inputs[row["id"]] = row
    docs = read_narrations(Path(args.batches))
    english = {d["_id"]: d["_source"]["english"] for d in docs}
    headings = sorted({d["_source"][f] for d in docs for f in ("part", "chapter") if d["_source"].get(f)})

    verdicts, problems, english_fixes = Counter(), [], 0
    seen = set()
    for path in sorted((work / "out").glob("batch_*.jsonl")):
        for line in path.open(encoding="utf-8"):
            if not line.strip():
                continue
            ruling = json.loads(line)
            doc_id, verdict = ruling.get("id"), ruling.get("verdict")
            record = edition["narrations"].get(doc_id)
            if record is None or doc_id not in inputs:
                problems.append({"id": doc_id, "problem": "unknown id"})
                continue
            seen.add(doc_id)
            verdicts[verdict] += 1
            for key in ("approved", "rejected", "review_note"):
                record.pop(key, None)
            if verdict == "span":
                span = tidy(ruling.get("arabic_span") or "")
                full = inputs[doc_id].get("edition_full", inputs[doc_id]["arabic"])
                if not span or span.rstrip(".") not in full:
                    problems.append({"id": doc_id, "problem": "span is not part of the edition text",
                                     "span": ruling.get("arabic_span")})
                    continue
                record["arabic"] = span
            if verdict in APPROVE:
                record["approved"] = True
            elif verdict in REJECT:
                record["rejected"] = True
            else:
                problems.append({"id": doc_id, "problem": f"unknown verdict {verdict!r}"})
                continue
            if ruling.get("note"):
                record["review_note"] = ruling["note"]
            for fix in ruling.get("english_fixes") or []:
                try:
                    verdict_fix = validate_fix("english", english[doc_id], fix["old"], fix["new"], headings)
                    if verdict_fix == "review" and not args.approve_footnotes:
                        raise ValueError("a footnote removal needs --approve-footnotes")
                except (KeyError, ValueError) as e:
                    problems.append({"id": doc_id, "problem": f"english fix refused: {e}", "fix": fix})
                    continue
                entry = {"field": "english", "old": fix["old"], "new": fix["new"], "type": "mishkat-review"}
                if verdict_fix == "review":
                    entry.update(kind="footnote", approved=True)
                if entry not in fixes.setdefault(doc_id, []):
                    fixes[doc_id].append(entry)
                    english_fixes += 1
    missing = sorted(set(inputs) - seen)
    EDITION_FILE.write_text(json.dumps(edition, ensure_ascii=False, indent=1, sort_keys=True) + "\n",
                            encoding="utf-8")
    FIXES_FILE.write_text(json.dumps(fixes, ensure_ascii=False, indent=1, sort_keys=True) + "\n",
                          encoding="utf-8")
    (work / "merge-problems.json").write_text(json.dumps(problems, ensure_ascii=False, indent=1) + "\n",
                                              encoding="utf-8")
    print(json.dumps({"ruled": len(seen), "missing": len(missing), "verdicts": dict(verdicts),
                      "english_fixes": english_fixes, "problems": len(problems)}))
    return 1 if missing else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="mode", required=True)
    p = sub.add_parser("prepare")
    p.add_argument("--batches", required=True)
    p.add_argument("--openiti", required=True)
    p.add_argument("--out-dir", required=True)
    m = sub.add_parser("merge")
    m.add_argument("--batches", required=True)
    m.add_argument("--work-dir", required=True)
    m.add_argument("--approve-footnotes", action="store_true",
                   help="accept the translator-footnote removals; only after a reader has read "
                        "them (a first run without this lists them in merge-problems.json)")
    args = parser.parse_args()
    return prepare(args) if args.mode == "prepare" else merge(args)


if __name__ == "__main__":
    sys.exit(main())
