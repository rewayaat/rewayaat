#!/usr/bin/env python3
import json
import sys
from pathlib import Path


def main():
    if len(sys.argv) != 4:
        raise SystemExit("usage: overwrite_batch_tags_from_review.py <batch.json> <review.json> <out.json>")

    batch_path = Path(sys.argv[1])
    review_path = Path(sys.argv[2])
    out_path = Path(sys.argv[3])

    batch = json.loads(batch_path.read_text(encoding="utf-8"))
    review = json.loads(review_path.read_text(encoding="utf-8"))
    review_by_id = {row["id"]: row for row in review}

    out = []
    for item in batch:
        row = review_by_id.get(item["id"])
        if row is None:
            raise SystemExit(f"missing review row for {item['id']}")
        source = dict(item.get("source", {}))
        source["topic_tags"] = row.get("recommended_tags", [])
        out.append({"id": item["id"], "source": source})

    out_path.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(out_path)


if __name__ == "__main__":
    main()
