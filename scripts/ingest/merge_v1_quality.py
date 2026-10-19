#!/usr/bin/env python3
"""Merge the text-quality agents' findings on the restored v1 books.

Every proposed edit goes through validate_fix (convert_v1_books.py), which refuses anything
that would change an Arabic letter or an English word. Accepted edits are written to the
text-fixes file the converter applies. Everything that needs a person, such as glyphs that
become honorifics, misaligned or truncated records, and title verdicts, goes to a review
file instead. docs/pipelines/v1-restore-quality.md describes the agents.

    python3 scripts/ingest/merge_v1_quality.py \
        --quality-dir /mnt/share/rewayaat-backup/tmp/v1-restore/quality-full
"""
from __future__ import annotations

import argparse
import glob
import json
import sys
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from convert_v1_books import validate_fix  # noqa: E402

TEXT_FIELDS = {"arabic", "english"}
ACCEPTED_CONFIDENCE = {"high", "medium"}
DECISION_TYPES = {"misaligned", "truncated-en", "truncated-ar", "merged", "citation-defect", "other"}


def load_docs(batches: Path) -> dict:
    docs = {}
    for path in sorted(batches.glob("batch_*.jsonl")):
        for line in path.open(encoding="utf-8"):
            doc = json.loads(line)
            docs[doc["_id"]] = doc["_source"]
    return docs


def headings_by_book(docs: dict) -> dict:
    out = defaultdict(lambda: {"arabic": set(), "english": set()})
    for src in docs.values():
        out[src["book"]]["arabic"].update(t for t in (src.get("part_ar"), src.get("chapter_ar")) if t)
        out[src["book"]]["english"].update(t for t in (src.get("part"), src.get("chapter")) if t)
    return {book: {f: sorted(v) for f, v in h.items()} for book, h in out.items()}


def read_findings(quality_dir: Path, book_dirs) -> list[dict]:
    rows = []
    for book_dir in book_dirs:
        for path in sorted(glob.glob(str(quality_dir / book_dir / "out" / "batch_*.jsonl"))):
            for n, line in enumerate(open(path, encoding="utf-8"), start=1):
                if line.strip():
                    try:
                        rows.append(json.loads(line))
                    except json.JSONDecodeError:
                        rows.append({"id": f"{path}:{n}", "issues": [{"type": "unreadable-output"}]})
    return rows


def merge(docs, findings):
    headings = headings_by_book(docs)
    working = {i: {"arabic": unicodedata.normalize("NFC", s["arabic"]), "english": s["english"]}
               for i, s in docs.items()}
    fixes, review, refused, decisions = defaultdict(list), [], [], []
    for row in findings:
        doc_id = row.get("id")
        src = docs.get(doc_id)
        for issue in row.get("issues", []):
            kind, field, proposed = issue.get("type"), issue.get("field"), issue.get("proposed")
            entry = {"id": doc_id, "type": kind, "field": field, "evidence": issue.get("evidence", ""),
                     "confidence": issue.get("confidence")}
            if src is None:
                refused.append({**entry, "reason": "unknown id"})
                continue
            if proposed and field in TEXT_FIELDS:
                old, new = proposed.get("old", ""), proposed.get("new", "")
                try:
                    verdict = validate_fix(field, working[doc_id][field], old, new,
                                           headings[src["book"]][field])
                except ValueError as e:
                    refused.append({**entry, "old": old, "new": new, "reason": str(e)})
                    continue
                fix = {"field": field, "old": old, "new": new, "type": kind}
                if verdict == "review":
                    review.append({**entry, **fix})
                elif issue.get("confidence") in ACCEPTED_CONFIDENCE:
                    fixes[doc_id].append(fix)
                    if field == "arabic":
                        old, new = unicodedata.normalize("NFC", old), unicodedata.normalize("NFC", new)
                    working[doc_id][field] = working[doc_id][field].replace(old, new, 1)
                else:
                    refused.append({**entry, "old": old, "new": new, "reason": "low confidence"})
            elif kind in DECISION_TYPES or not proposed:
                decisions.append({**entry, "english": src["english"][:160], "arabic": src["arabic"][:160]})
            else:
                decisions.append({**entry, "note": "proposal on a title field; see the title review"})
    return dict(fixes), review, refused, decisions


def read_titles(quality_dir: Path) -> list[dict]:
    out = []
    for path in sorted(glob.glob(str(quality_dir / "titles" / "out" / "titles_*.jsonl"))):
        out += [json.loads(line) for line in open(path, encoding="utf-8") if line.strip()]
    return out


def write_review(path: Path, fixes, review, refused, decisions, titles):
    counts = Counter(d["type"] for d in decisions)
    lines = [
        "# Restored v1 books — quality review", "",
        f"- fixes accepted: {sum(len(v) for v in fixes.values())} on {len(fixes)} narrations",
        f"- glyph → honorific, needs approval: {len(review)}",
        f"- proposals refused: {len(refused)}",
        f"- records needing a decision: {len(decisions)} {dict(counts)}",
        f"- title pairs flagged: {len(titles)}", "",
    ]
    sections = (("Needs a decision", decisions), ("Glyph to honorific", review),
                ("Proposed but refused", refused), ("Title pairs", titles))
    for title, rows in sections:
        lines += [f"## {title}", ""]
        lines += ["- " + json.dumps(r, ensure_ascii=False) for r in rows]
        lines.append("")
    path.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--quality-dir", required=True)
    parser.add_argument("--batches", default="batches/v1-restored")
    parser.add_argument("--fixes-out", default=str(Path(__file__).with_name("v1_restore_text_fixes.json")))
    args = parser.parse_args()

    quality_dir = Path(args.quality_dir)
    docs = load_docs(Path(args.batches))
    fixes, review, refused, decisions = merge(docs, read_findings(quality_dir, ["ghurar", "mizan"]))
    titles = read_titles(quality_dir)
    Path(args.fixes_out).write_text(json.dumps(fixes, ensure_ascii=False, indent=1, sort_keys=True) + "\n",
                                    encoding="utf-8")
    (quality_dir / "review.json").write_text(json.dumps(
        {"review": review, "refused": refused, "decisions": decisions, "titles": titles},
        ensure_ascii=False, indent=1), encoding="utf-8")
    write_review(quality_dir / "review.md", fixes, review, refused, decisions, titles)
    print(json.dumps({"fixes": sum(len(v) for v in fixes.values()), "review": len(review),
                      "refused": len(refused), "decisions": len(decisions), "titles": len(titles)}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
