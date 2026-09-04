#!/usr/bin/env python3
"""Import embeddings from Colab-generated npz file into Elasticsearch."""
import json
import sys
import requests
import numpy as np
from pathlib import Path


def main():
    if len(sys.argv) < 2:
        print("Usage: python3 scripts/embeddings/import_embeddings_to_es.py hadith_embeddings.npz")
        sys.exit(1)

    npz_path = sys.argv[1]
    es_url = "http://localhost:9200"
    index = "rewayaat_updated"

    print(f"Loading embeddings from {npz_path}...")
    data = np.load(npz_path, allow_pickle=True)
    ids = data["ids"]
    embeddings = data["embeddings"]
    print(f"Loaded {len(ids)} embeddings, dim={embeddings.shape[1]}")

    # Bulk update in chunks
    CHUNK_SIZE = 500
    updated = 0
    errors = 0

    for start in range(0, len(ids), CHUNK_SIZE):
        chunk_ids = ids[start:start + CHUNK_SIZE]
        chunk_embs = embeddings[start:start + CHUNK_SIZE]

        bulk_body = ""
        for hid, emb in zip(chunk_ids, chunk_embs):
            bulk_body += json.dumps({"update": {"_id": str(hid)}}) + "\n"
            bulk_body += json.dumps({"doc": {"semantic_vector": emb.tolist()}}) + "\n"

        resp = requests.post(
            f"{es_url}/{index}/_bulk",
            data=bulk_body,
            headers={"Content-Type": "application/json"},
            timeout=120)

        result = resp.json()
        for item in result.get("items", []):
            action = item.get("update", {})
            if action.get("status") in (200, 201):
                updated += 1
            else:
                errors += 1
                if errors <= 3:
                    print(f"  Error for {action.get('_id')}: {action.get('error')}")

        print(f"  Progress: {min(start + CHUNK_SIZE, len(ids))}/{len(ids)} "
              f"({updated} updated, {errors} errors)")

    print(f"\nDone! {updated} documents updated, {errors} errors")


if __name__ == "__main__":
    main()
