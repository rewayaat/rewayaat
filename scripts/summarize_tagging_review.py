#!/usr/bin/env python3
import json
import sys
from collections import Counter
from pathlib import Path


def summarize(path_str):
    path = Path(path_str)
    rows = json.loads(path.read_text(encoding="utf-8"))
    verdicts = Counter()
    tag_changes = Counter()
    for row in rows:
        verdicts[row.get("verdict", "")] += 1
        old = tuple(row.get("existing_tags", []))
        new = tuple(row.get("recommended_tags", []))
        if old != new:
            tag_changes[(old, new)] += 1
    print(path)
    print("rows", len(rows))
    print("verdicts", dict(verdicts))
    print("top_changes")
    for (old, new), count in tag_changes.most_common(20):
        print(count, list(old), "->", list(new))


def main():
    for arg in sys.argv[1:]:
        summarize(arg)


if __name__ == "__main__":
    main()
