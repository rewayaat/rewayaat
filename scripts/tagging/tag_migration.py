#!/usr/bin/env python3
"""
Tag Migration Script - Phase 2
Remaps 48 tags, strips 15 tags from syn_v1 index
"""

import json
import sys
import time
from urllib.parse import quote

try:
    import requests
except ImportError:
    print("Installing requests...")
    import subprocess
    subprocess.check_call([sys.executable, "-m", "pip", "install", "requests"])
    import requests

ES_HOST = "localhost"
ES_PORT = "9200"
INDEX = "rewayaat_thaqalayn_20260320_syn_v1"
BASE_URL = f"http://{ES_HOST}:{ES_PORT}"
DRY_RUN = True  # Set to False when ready

# Tags to REMAP: old tag -> new tag
REMAP_TAGS = {
    "obligatory-prayer": "prayer",
    "pilgrimage": "hajj",
    "health-hygiene": "purification",
    "prayer-etiquette": "prayer",
    "voluntary-prayer": "prayer",
    "halal-haram": "halal",
    "funeral-prayer": "funeral-rites",
    "divine-knowledge": "tawhid",
    "kaabah": "hajj",
    "guardianship": "rights",
    "bad-company": "enjoining-good",
    "liability": "penalties",
    "ramy-al-jamarat": "hajj",
    "beauty": "gratitude",
    "aqiqah": "sacrifice",
    "naming": "children",
    "major-sins": "repentance",
    "good-company": "brotherhood",
    "travel-preparation": "travel-prayer",
    "music": "obscenity",
    "funeral-procession": "funeral-rites",
    "prayer-clothing": "prayer",
    "compensatory-prayer": "prayer",
    "circumcision": "purification",
    "li-ante": "testimony-judgment",
    "family-care": "parents",
    "hair-grooming": "dress-adornment",
    "good-temper": "patience",
    "found-property": "rights",
    "laziness": "heedlessness",
    "dogs": "food-drink",
    "respect-elderly": "parents",
    "ghayrah": "chastity",
    "dream-interpretation": "wisdom",
    "images": "halal",
    "water-rights": "rights",
    "animal-welfare": "mercy",
    "crescent-sighting": "fasting",
    "wealth-management": "livelihood",
    "martyrdom": "warfare-jihad",
    "wet-nursing": "children",
    "salat-jaafar": "prayer",
    "jizya": "rights",
    "jizyah": "rights",
    "abrogation": "guidance-misguidance",
    "amulet": "unseen",
    "homosexuality": "obscenity",
    "honoring": "humility",
    "analogy": "halal",
    "foolishness": "intellect",
    "istinjа": "purification",  # istinja with latin 'a'
    "istinja": "purification",  # istinja with arabic 'a'
    "racism": "equality",
    "duha-prayer": "prayer",
    "ulul-azm": "prophethood",
    "prophets": "prophethood",
    "eclipse-prayer": "prayer",
}

# Tags to STRIP (remove without replacement)
STRIP_TAGS = {
    "belief", "good-deeds", "etiquette", "evil-behavior",
    "wicked-behavior", "humor", "manhood"
}


def scan_afffected_hadith():
    """Find all hadith with tags to remap or strip"""
    all_tags = list(REMAP_TAGS.keys()) + list(STRIP_TAGS)

    query = {
        "size": 0,
        "query": {
            "bool": {
                "should": [
                    {"term": {"topic_tags": tag}} for tag in all_tags
                ]
            }
        }
    }

    resp = requests.post(f"{BASE_URL}/{quote(INDEX)}/_search", json=query)
    resp.raise_for_status()
    data = resp.json()

    total = data["hits"]["total"]["value"]
    return total


def process_hadith_scroll():
    """Scroll through all hadith and collect updates"""
    all_tags = list(REMAP_TAGS.keys()) + list(STRIP_TAGS)

    query = {
        "size": 100,
        "_source": ["topic_tags"],
        "query": {
            "bool": {
                "should": [
                    {"term": {"topic_tags": tag}} for tag in all_tags
                ]
            }
        }
    }

    # Start scroll
    resp = requests.post(f"{BASE_URL}/{quote(INDEX)}/_search?scroll=5m", json=query)
    resp.raise_for_status()
    data = resp.json()

    scroll_id = data.get("_scroll_id")
    updates = []
    zero_tag_ids = []

    while True:
        hits = data["hits"]["hits"]
        if not hits:
            break

        for hit in hits:
            doc_id = hit["_id"]
            current_tags = hit.get("_source", {}).get("topic_tags", [])

            # Process tags
            new_tags = []
            for tag in current_tags:
                if tag in REMAP_TAGS:
                    new_tags.append(REMAP_TAGS[tag])
                elif tag not in STRIP_TAGS:
                    new_tags.append(tag)

            # Remove duplicates
            new_tags = list(dict.fromkeys(new_tags))

            if current_tags != new_tags:
                updates.append({
                    "id": doc_id,
                    "old_tags": current_tags,
                    "new_tags": new_tags
                })
                if not new_tags:
                    zero_tag_ids.append(doc_id)

        # Continue scroll
        resp = requests.post(f"{BASE_URL}/_search/scroll", json={
            "scroll": "5m",
            "scroll_id": scroll_id
        })
        resp.raise_for_status()
        data = resp.json()
        scroll_id = data.get("_scroll_id", scroll_id)

    # Clear scroll
    requests.delete(f"{BASE_URL}/_search/scroll", json={"scroll_id": scroll_id})

    return updates, zero_tag_ids


def apply_updates(updates):
    """Apply bulk updates to Elasticsearch"""
    if DRY_RUN:
        print(f"DRY RUN: Would apply {len(updates)} updates")
        return len(updates)

    bulk_body = []
    for update in updates:
        # Update operation
        bulk_body.append(json.dumps({
            "update": {
                "_index": INDEX,
                "_id": update["id"]
            }
        }))
        # Document
        bulk_body.append(json.dumps({
            "doc": {"topic_tags": update["new_tags"]},
            "doc_as_upsert": False
        }))

    resp = requests.post(f"{BASE_URL}/_bulk", data="\n".join(bulk_body) + "\n",
                        headers={"Content-Type": "application/x-ndjson"})
    resp.raise_for_status()

    data = resp.json()
    if data.get("errors"):
        print("WARNING: Some updates had errors")
        for item in data["items"]:
            if item.get("update", {}).get("error"):
                print(f"  Error: {item['update']['error']}")
    else:
        print(f"Successfully applied {len(updates)} updates")

    return len(updates)


def write_retag_queue(zero_tag_ids):
    """Write hadith IDs that need re-tagging"""
    queue = {
        "timestamp": int(time.time() * 1000),
        "index": INDEX,
        "hadith_ids": zero_tag_ids
    }

    path = "/tmp/tag_migration_retag_queue.json"
    with open(path, "w") as f:
        json.dump(queue, f, indent=2)

    print(f"Re-tag queue written to: {path}")
    print(f"Hadith needing re-tag: {len(zero_tag_ids)}")


def main():
    global DRY_RUN

    if "--wet" in sys.argv:
        DRY_RUN = False
        print("!!! WET RUN - Will modify data !!!")
    else:
        print("DRY RUN - No changes will be made")

    print(f"Index: {INDEX}")
    print(f"ES: {BASE_URL}")
    print(f"Remap tags: {len(REMAP_TAGS)}")
    print(f"Strip tags: {len(STRIP_TAGS)}")
    print()

    # Step 1: Scan
    print("Step 1: Scanning for affected hadith...")
    total = scan_afffected_hadith()
    print(f"Found {total} affected hadith")
    print()

    # Step 2: Process
    print("Step 2: Processing hadith (this may take a while)...")
    updates, zero_tag_ids = process_hadith_scroll()
    print(f"Updates to apply: {len(updates)}")
    print(f"Hadith with zero tags: {len(zero_tag_ids)}")
    print()

    # Show sample updates
    if updates:
        print("Sample updates (first 5):")
        for u in updates[:5]:
            print(f"  {u['id']}: {u['old_tags']} -> {u['new_tags']}")
        print()

    # Step 3: Apply
    if DRY_RUN:
        print("Step 3: DRY RUN - skipping updates")
        print("Run with --wet to apply changes")
    else:
        print("Step 3: Applying updates...")
        applied = apply_updates(updates)
        print(f"Applied {applied} updates")

    # Step 4: Write re-tag queue
    if zero_tag_ids:
        write_retag_queue(zero_tag_ids)

    print()
    print("=== Summary ===")
    print(f"Total affected: {total}")
    print(f"Updates needed: {len(updates)}")
    print(f"Zero-tag hadith: {len(zero_tag_ids)}")
    print(f"Mode: {'DRY RUN' if DRY_RUN else 'WET RUN'}")


if __name__ == "__main__":
    main()
