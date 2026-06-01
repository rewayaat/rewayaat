#!/usr/bin/env python3
"""Export training data with pre-built embedding text for use in Colab."""
import json
import sys
import requests
from pathlib import Path


def build_embedding_text(source, include_topics=False):
    """Build embedding text from hadith fields.

    Must match the format used by export_hadith_for_embeddings.py so
    training and inference text are aligned.
    """
    matn = (source.get("semantic_matn_source") or "").strip()
    if not matn:
        return ""

    matn = matn.replace("<", " ").replace(">", " ")
    for honorific in ["عليه السلام", "عليهما السلام", "عليهم السلام",
                      "صلّى الله عليه وآله", "صلى الله عليه وآله", "رحمه الله"]:
        matn = matn.replace(honorific, " ")

    while "  " in matn:
        matn = matn.replace("  ", " ")
    matn = matn.strip()
    while matn and matn[0] in " -:;,.،":
        matn = matn[1:].strip()
    if len(matn) > 3800:
        matn = matn[:3800].strip()
    if not matn:
        return ""

    body = matn

    english_hint = (source.get("semantic_english_hint_source") or "").strip()
    if english_hint:
        # Truncate at sentence boundary within 300 chars
        if len(english_hint) > 300:
            english_hint = english_hint[:300]
            # Find last sentence boundary
            for sep in [". ", "? ", "! "]:
                idx = english_hint.rfind(sep)
                if idx > 50:
                    english_hint = english_hint[:idx + 1]
                    break
            else:
                # Fallback: last space
                last_space = english_hint.rfind(" ")
                if last_space > 50:
                    english_hint = english_hint[:last_space]
        if english_hint:
            body += f" || {english_hint}"

    topic_tags = source.get("topic_tags") or []
    if include_topics and topic_tags:
        readable_tags = [t.replace("-", " ") for t in topic_tags[:8]]
        body += f" || topics: {', '.join(readable_tags)}"

    return body


def main():
    es_url = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:9200"
    index = sys.argv[2] if len(sys.argv) > 2 else "rewayaat_updated"
    data_dir = Path("tmp/eval")

    output = {"train": [], "test": [], "val": []}

    for split in ["train", "test", "val"]:
        split_path = data_dir / f"{split}.json"
        if not split_path.exists():
            print(f"  {split}.json not found, skipping")
            continue

        data = json.loads(split_path.read_text(encoding="utf-8"))
        pairs = data.get("pairs", [])
        print(f"\n{split}: {len(pairs)} pairs")

        # Collect all unique hadith IDs
        all_ids = set()
        for p in pairs:
            all_ids.add(p["id_a"])
            all_ids.add(p["id_b"])

        # Fetch from ES
        docs = {}
        id_list = list(all_ids)
        for i in range(0, len(id_list), 500):
            chunk = id_list[i:i+500]
            payload = {
                "size": len(chunk),
                "query": {"ids": {"values": chunk}},
                "_source": ["semantic_matn_source",
                            "semantic_english_hint_source", "topic_tags"]
            }
            resp = requests.post(
                f"{es_url}/{index}/_search",
                json=payload, headers={"Content-Type": "application/json"}, timeout=60)
            for hit in resp.json().get("hits", {}).get("hits", []):
                docs[hit["_id"]] = hit["_source"]

        # Build export pairs with text
        exported = []
        skipped = 0
        for p in pairs:
            text_a = build_embedding_text(docs.get(p["id_a"], {}))
            text_b = build_embedding_text(docs.get(p["id_b"], {}))
            if not text_a or not text_b:
                skipped += 1
                continue
            exported.append({
                "text_a": text_a,
                "text_b": text_b,
                "label": p["label"],
                "pair_type": p.get("pair_type", "unknown"),
            })

        output[split] = exported
        print(f"  Exported {len(exported)} pairs ({skipped} skipped)")

    out_path = Path("tmp/colab_training_data.json")
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\nSaved to {out_path} ({out_path.stat().st_size / 1e6:.1f}MB)")
    print("Upload this file to Colab when prompted.")


if __name__ == "__main__":
    main()
