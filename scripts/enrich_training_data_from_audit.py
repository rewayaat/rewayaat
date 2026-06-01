#!/usr/bin/env python3
"""Enrich training data from a fresh quality audit of the similar hadith API.

Runs the similar API for ~170 hadith across 17 topic clusters, then extracts:
- Positive pairs: genuinely similar hadith (content overlap > 30%, similarity > 55%)
- Hard negative pairs: same tag but different concept (content overlap < 12%, topic overlap > 50%)

These hard negatives are especially valuable — the current training data has no
in-tag negatives, so the model never learns to distinguish within a tag.

Usage:
  python3 scripts/enrich_training_data_from_audit.py
  python3 scripts/enrich_training_data_from_audit.py --es-url http://localhost:9200
"""
import argparse
import json
import random
import re
import sys
import urllib.request
import urllib.parse
import urllib.error

ES = "http://localhost:9200"
API = "http://localhost:8002"
INDEX = "rewayaat_updated"

# Same clusters as quality_audit_similar.py
TAG_CLUSTERS = {
    "theology": ["tanzih", "shirk", "divine-decree", "names-of-god"],
    "prophets": ["prophet-muhammad", "ibrahim", "musa", "isa"],
    "imams": ["imam-ali", "imam-mahdi", "imam-husayn", "imam-ridha"],
    "worship_ritual": ["worship", "fasting", "wudu", "ghusl"],
    "hajj": ["ihram", "tawaf", "sacrifice", "umrah"],
    "ethics_character": ["taqwa", "patience", "asceticism", "generosity"],
    "social": ["marriage", "divorce", "brotherhood", "women"],
    "legal": ["trade", "penalties", "inheritance", "testimony-judgment"],
    "food_wealth": ["food-drink", "alcohol", "spending", "zakat"],
    "eschatology": ["death", "paradise", "hellfire", "day-of-judgement"],
    "occultation": ["occultation", "imam-mahdi", "reappearance-signs"],
    "wilayah": ["wilayah", "governance", "taqiyyah", "ahl-al-bayt"],
    "sins_vices": ["adultery", "arrogance", "lying", "hypocrisy"],
    "quran_knowledge": ["quran", "intellect", "wisdom", "revelation"],
    "warfare": ["warfare-jihad", "oppression", "enemies"],
    "ritual_purity": ["breastfeeding", "funeral-prayer", "menstruation", "ghusl"],
    "specific_narrow": ["miracle", "dream-interpretation", "christians-jews", "jinn"],
}

# Thresholds for classifying pairs
POS_MIN_CONTENT = 30.0   # content overlap %
POS_MIN_SIMILARITY = 55.0  # overall similarity %
NEG_MAX_CONTENT = 12.0   # content overlap %
NEG_MIN_TOPIC = 50.0     # topic overlap %


def es_search(body, es_url):
    data = json.dumps(body).encode()
    req = urllib.request.Request(f"{es_url}/{INDEX}/_search", data=data,
                                headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read())


def api_similar(hadith_id, per_page=10):
    url = f"{API}/v1/narrations/similar?id={urllib.parse.quote(hadith_id)}&per_page={per_page}"
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read())


def pick_hadith_for_tag(tag, count, es_url):
    body = {
        "size": count * 3,
        "query": {
            "bool": {
                "must": [
                    {"term": {"topic_tags": tag}},
                    {"exists": {"field": "semantic_vector"}}
                ]
            }
        },
        "_source": ["topic_tags", "book"],
        "sort": ["_doc"]
    }
    results = es_search(body, es_url)
    hits = results.get("hits", {}).get("hits", [])
    if not hits:
        return []
    random.shuffle(hits)
    return hits[:count]


def build_embedding_text(source, es_url=None):
    """Build embedding text WITHOUT topics (matching the new unified format)."""
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
    # NO topics — matching the new format
    return body


def fetch_doc_text(doc_id, es_url):
    """Fetch embedding text for a single hadith from ES."""
    req = urllib.request.Request(
        f"{es_url}/{INDEX}/_doc/{urllib.parse.quote(doc_id)}",
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as resp:
        data = json.loads(resp.read())
    if not data.get("found"):
        return None
    return build_embedding_text(data["_source"])


def main():
    parser = argparse.ArgumentParser(description="Enrich training data from quality audit")
    parser.add_argument("--es-url", default="http://localhost:9200")
    parser.add_argument("--api-url", default="http://localhost:8002")
    parser.add_argument("--per-tag", type=int, default=3, help="Hadith per tag to test")
    parser.add_argument("--output", default="tmp/audit_pairs.json")
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    sys.stdout.reconfigure(line_buffering=True)
    random.seed(args.seed)

    global ES, API
    ES = args.es_url
    API = args.api_url

    positive_pairs = []
    negative_pairs = []
    seen_pairs = set()

    def pair_key(a, b):
        return tuple(sorted([a, b]))

    # Flatten clusters
    test_tags = []
    for cluster_name, tags in TAG_CLUSTERS.items():
        for tag in tags:
            test_tags.append((cluster_name, tag))

    print(f"=== Quality Audit for Training Data Enrichment ===")
    print(f"Testing {len(test_tags)} tags, {args.per_tag} hadith each")
    print(f"Positive: content>{POS_MIN_CONTENT}% AND sim>{POS_MIN_SIMILARITY}%")
    print(f"Negative: content<{NEG_MAX_CONTENT}% AND topic>{NEG_MIN_TOPIC}%")
    print()

    for cluster_name, tag in test_tags:
        hadiths = pick_hadith_for_tag(tag, args.per_tag, args.es_url)
        if not hadiths:
            continue

        for hit in hadiths:
            hid = hit["_id"]
            try:
                api_result = api_similar(hid, per_page=10)
            except Exception as e:
                print(f"  Error querying {hid}: {e}")
                continue

            results = api_result.get("collection", [])
            if not results:
                continue

            # Fetch source text
            source_text = fetch_doc_text(hid, args.es_url)
            if not source_text:
                continue

            for r in results:
                rid = r.get("_id", "")
                if not rid:
                    continue

                key = pair_key(hid, rid)
                if key in seen_pairs:
                    continue
                seen_pairs.add(key)

                content = r.get("contentOverlapPercent", 0)
                similarity = r.get("similarityPercent", 0)
                topic = r.get("topicOverlapPercent", 0)
                shared_tags = r.get("sharedTopicTags", [])

                result_text = fetch_doc_text(rid, args.es_url)
                if not result_text:
                    continue

                # Classify: positive or negative
                if content >= POS_MIN_CONTENT and similarity >= POS_MIN_SIMILARITY:
                    positive_pairs.append({
                        "text_a": source_text,
                        "text_b": result_text,
                        "label": 1,
                        "pair_type": "audit_positive",
                        "source": f"{cluster_name}/{tag}",
                        "content_overlap": round(content, 1),
                        "similarity": round(similarity, 1),
                    })
                elif content < NEG_MAX_CONTENT and topic >= NEG_MIN_TOPIC:
                    negative_pairs.append({
                        "text_a": source_text,
                        "text_b": result_text,
                        "label": 0,
                        "pair_type": "audit_hard_negative",
                        "source": f"{cluster_name}/{tag}",
                        "content_overlap": round(content, 1),
                        "similarity": round(similarity, 1),
                        "topic_overlap": round(topic, 1),
                        "shared_tags": shared_tags,
                    })

        progress = len(positive_pairs) + len(negative_pairs)
        if progress % 20 == 0 and progress > 0:
            print(f"  Progress: {len(positive_pairs)} positives, {len(negative_pairs)} negatives")

    print(f"\n=== Results ===")
    print(f"Positive pairs (content>{POS_MIN_CONTENT}% AND sim>{POS_MIN_SIMILARITY}%): {len(positive_pairs)}")
    print(f"Hard negatives (content<{NEG_MAX_CONTENT}% AND topic>{NEG_MIN_TOPIC}%): {len(negative_pairs)}")

    # Split into train/test/val (80/10/10)
    def split_list(lst, train_frac=0.8, val_frac=0.1):
        random.shuffle(lst)
        n = len(lst)
        t_end = int(n * train_frac)
        v_end = int(n * (train_frac + val_frac))
        return lst[:t_end], lst[t_end:v_end], lst[v_end:]

    pos_train, pos_val, pos_test = split_list(positive_pairs)
    neg_train, neg_val, neg_test = split_list(negative_pairs)

    train = pos_train + neg_train
    val = pos_val + neg_val
    test = pos_test + neg_test
    random.shuffle(train)
    random.shuffle(val)
    random.shuffle(test)

    print(f"\nSplit: train={len(train)}, val={len(val)}, test={len(test)}")

    output = {
        "train": train,
        "val": val,
        "test": test,
        "metadata": {
            "positive_pairs": len(positive_pairs),
            "negative_pairs": len(negative_pairs),
            "pos_thresholds": {"min_content": POS_MIN_CONTENT, "min_similarity": POS_MIN_SIMILARITY},
            "neg_thresholds": {"max_content": NEG_MAX_CONTENT, "min_topic": NEG_MIN_TOPIC},
        }
    }

    from pathlib import Path
    out_path = Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\nSaved to {out_path} ({out_path.stat().st_size / 1e6:.1f}MB)")
    print(f"\nTo merge with existing training data, run:")
    print(f"  python3 scripts/merge_audit_pairs.py {args.output} tmp/colab_training_data.json")


if __name__ == "__main__":
    main()
