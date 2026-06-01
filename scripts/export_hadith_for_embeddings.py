#!/usr/bin/env python3
"""Export all hadith with embedding text for Colab embedding generation."""
import json
import requests
from pathlib import Path


def build_embedding_text(source):
    """Build embedding text for hadith similarity matching.

    Includes:
      - Arabic matn (narrator-chain-free content) as the primary signal
      - English translation hint (also chain-free) for cross-lingual similarity
      - Topic tags for thematic grouping
    """
    matn = (source.get("semantic_matn_source") or "").strip()
    if not matn:
        return ""

    matn = matn.replace("<", " ").replace(">", " ")
    for h in ["عليه السلام", "عليهما السلام", "عليهم السلام",
              "صلّى الله عليه وآله", "صلى الله عليه وآله", "رحمه الله"]:
        matn = matn.replace(h, " ")
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
            for sep in [". ", "? ", "! "]:
                idx = english_hint.rfind(sep)
                if idx > 50:
                    english_hint = english_hint[:idx + 1]
                    break
            else:
                last_space = english_hint.rfind(" ")
                if last_space > 50:
                    english_hint = english_hint[:last_space]
        if english_hint:
            body += f" || {english_hint}"

    return body


def main():
    es_url = "http://localhost:9200"
    index = "rewayaat_updated"

    # Scroll through all documents
    print("Exporting all hadith from ES...")
    docs = {}
    scroll_id = None

    # Initial request
    resp = requests.post(
        f"{es_url}/{index}/_search?scroll=5m",
        json={
            "size": 1000,
            "query": {"exists": {"field": "semantic_matn_source"}},
            "_source": ["semantic_matn_source",
                        "semantic_english_hint_source", "topic_tags"]
        },
        headers={"Content-Type": "application/json"}, timeout=60)
    data = resp.json()

    while True:
        hits = data.get("hits", {}).get("hits", [])
        if not hits:
            break

        for h in hits:
            text = build_embedding_text(h["_source"])
            if text:
                docs[h["_id"]] = text

        print(f"  Fetched {len(docs)} hadith so far...")
        scroll_id = data.get("_scroll_id")

        resp = requests.post(
            f"{es_url}/_search/scroll",
            json={"scroll": "5m", "scroll_id": scroll_id},
            headers={"Content-Type": "application/json"}, timeout=60)
        data = resp.json()

    # Clear scroll
    if scroll_id:
        requests.delete(f"{es_url}/_search/scroll",
                       json={"scroll_id": scroll_id},
                       headers={"Content-Type": "application/json"})

    print(f"\nTotal hadith with text: {len(docs)}")

    out_path = Path("tmp/hadith_for_embeddings.json")
    out_path.write_text(json.dumps(docs, ensure_ascii=False), encoding="utf-8")
    print(f"Saved to {out_path} ({out_path.stat().st_size / 1e6:.1f}MB)")
    print("Upload this file to Colab when prompted.")


if __name__ == "__main__":
    main()
