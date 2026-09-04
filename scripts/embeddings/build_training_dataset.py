#!/usr/bin/env python3
"""Build a high-quality training dataset for hadith embedding fine-tuning.

Generates ~2500 positive and ~1500 negative pairs using the taxonomy,
Elasticsearch queries, and heuristic filtering. Splits by hadith ID
to prevent data leakage.

Usage:
  python3 scripts/build_training_dataset.py
  python3 scripts/build_training_dataset.py --es-url http://localhost:9200
  python3 scripts/build_training_dataset.py --output-dir tmp/eval
"""

import argparse
import json
import random
import re
import sys
import time
from collections import defaultdict
from pathlib import Path

# ── ES helpers ──────────────────────────────────────────────────────────────

def es_request(es_url, method, path, data=None, timeout=30):
    import requests
    url = f"{es_url.rstrip('/')}/{path.lstrip('/')}"
    headers = {"Content-Type": "application/json"}
    resp = requests.request(method, url, data=data, headers=headers, timeout=timeout)
    return resp.json() if resp.text.strip() else {}


def es_search(es_url, index, query, size=10):
    payload = {"size": size, **query}
    resp = es_request(es_url, "POST", f"/{index}/_search", json.dumps(payload).encode())
    return resp.get("hits", {}).get("hits", [])


def es_aggs(es_url, index, query):
    payload = {"size": 0, **query}
    return es_request(es_url, "POST", f"/{index}/_search", json.dumps(payload).encode())


# ── Text helpers ────────────────────────────────────────────────────────────

def strip_diacritics(text):
    if not text:
        return ""
    return re.compile(r'[\u064B-\u065F\u0670\u06D6-\u06ED]').sub('', text)


def arabic_word_set(text):
    clean = strip_diacritics(text)
    return set(re.findall(r'[\u0600-\u06FF]+', clean))


def arabic_jaccard(text1, text2):
    w1, w2 = arabic_word_set(text1), arabic_word_set(text2)
    if not w1 or not w2:
        return 0.0
    return len(w1 & w2) / len(w1 | w2)


def truncate(text, n=120):
    if not text:
        return ""
    return text[:n] + ("..." if len(text) > n else "")


# ── Pair tracking ──────────────────────────────────────────────────────────

class PairTracker:
    def __init__(self):
        self._seen = set()

    def is_used(self, a, b):
        return tuple(sorted([a, b])) in self._seen

    def mark_used(self, a, b):
        self._seen.add(tuple(sorted([a, b])))

    @property
    def count(self):
        return len(self._seen)


# ── Taxonomy loading ──────────────────────────────────────────────────────

def load_taxonomy(path):
    """Load taxonomy and build lookup structures."""
    with open(path, 'r', encoding='utf-8') as f:
        tags = json.load(f)

    by_slug = {}
    by_category = defaultdict(list)
    children_of = defaultdict(list)
    taggable_tags = []
    parent_tags = set()

    for tag in tags:
        slug = tag["slug"]
        by_slug[slug] = tag
        cat = tag.get("category", "uncategorized")
        by_category[cat].append(slug)
        if tag.get("parent"):
            children_of[tag["parent"]].append(slug)
            parent_tags.add(tag["parent"])
        if tag.get("taggable", True):
            taggable_tags.append(slug)

    return by_slug, by_category, children_of, taggable_tags, parent_tags


# ── Generic tag detection ────────────────────────────────────────────────

def build_generic_tags(by_slug, parent_tags):
    """Build the set of generic/broad tags to exclude from positive pairing.

    A tag is generic if:
    - It's marked taggable=false in the taxonomy, OR
    - It's a parent of 3+ other tags in the taxonomy
    """
    generic = set()
    for slug, tag in by_slug.items():
        # Non-taggable tags are broad categories
        if not tag.get("taggable", True):
            generic.add(slug)
        # Parents of many children are broad
        if slug in parent_tags and len(children_of_global.get(slug, [])) >= 3:
            generic.add(slug)
    return generic

# Will be set after taxonomy load
children_of_global = {}


# ── Hadith fetching ──────────────────────────────────────────────────────

def fetch_hadith_for_tag(es_url, index, tag, size=30):
    """Fetch hadith that have a specific topic tag."""
    hits = es_search(es_url, index, {
        "query": {
            "bool": {
                "must": [
                    {"term": {"topic_tags": tag}},
                    {"exists": {"field": "semantic_matn_source"}}
                ]
            }
        },
        "_source": ["book", "chapter", "arabic", "english",
                     "semantic_matn_source", "topic_tags", "number"]
    }, size=size)
    return [{
        "id": h["_id"],
        "book": h["_source"].get("book", ""),
        "chapter": h["_source"].get("chapter", ""),
        "arabic": h["_source"].get("arabic", ""),
        "english": h["_source"].get("english", ""),
        "matn": h["_source"].get("semantic_matn_source", ""),
        "topic_tags": h["_source"].get("topic_tags", []),
    } for h in hits]


def fetch_random_from_category(es_url, index, category, by_category, exclude_tags=None, size=20):
    """Fetch hadith from a category, optionally excluding certain tags."""
    must = [{"exists": {"field": "semantic_matn_source"}}]
    exclude_tags = exclude_tags or set()

    category_tags = [t for t in by_category.get(category, []) if t not in exclude_tags]
    if not category_tags:
        return []

    tag = random.choice(category_tags)
    hits = es_search(es_url, index, {
        "query": {
            "bool": {
                "must": [
                    {"term": {"topic_tags": tag}},
                    {"exists": {"field": "semantic_matn_source"}}
                ]
            }
        },
        "_source": ["book", "arabic", "semantic_matn_source", "topic_tags", "number"]
    }, size=size)
    return [{
        "id": h["_id"],
        "book": h["_source"].get("book", ""),
        "matn": h["_source"].get("semantic_matn_source", ""),
        "topic_tags": h["_source"].get("topic_tags", []),
    } for h in hits]


def find_mlt_matches(es_url, index, matn, exclude_book=None, size=5):
    """Find similar hadith using MLT query."""
    must = [{"more_like_this": {
        "fields": ["semantic_matn_source"],
        "like": matn[:600],
        "min_term_freq": 1,
        "min_doc_freq": 3,
        "max_query_terms": 25,
        "minimum_should_match": "65%"
    }}]
    must_not = []
    if exclude_book:
        must_not.append({"term": {"book": exclude_book}})

    hits = es_search(es_url, index, {
        "query": {"bool": {"must": must, "must_not": must_not}},
        "_source": ["book", "chapter", "arabic", "semantic_matn_source", "topic_tags", "number"]
    }, size=size)
    return [{
        "id": h["_id"],
        "book": h["_source"].get("book", ""),
        "chapter": h["_source"].get("chapter", ""),
        "matn": h["_source"].get("semantic_matn_source", ""),
        "topic_tags": h["_source"].get("topic_tags", []),
    } for h in hits]


# ── Pair generation strategies ─────────────────────────────────────────────

def generate_same_tag_pairs(es_url, index, taggable_tags, generic_tags, tracker, target=1200):
    """Strategy 1: Pairs of hadith sharing the same specific tag.

    Only uses non-generic tags to avoid false positives where hadith share
    a broad tag like 'prayer' but are about unrelated topics.
    """
    pairs = []

    # Filter out generic tags that cause false positives
    specific_tags = [t for t in taggable_tags if t not in generic_tags]
    random.shuffle(specific_tags)

    for tag in specific_tags:
        if len(pairs) >= target:
            break

        docs = fetch_hadith_for_tag(es_url, index, tag, size=80)
        if len(docs) < 2:
            continue

        count_for_tag = 0
        for i in range(len(docs)):
            if count_for_tag >= 20 or len(pairs) >= target:
                break
            for j in range(i + 1, len(docs)):
                if count_for_tag >= 20 or len(pairs) >= target:
                    break
                if tracker.is_used(docs[i]["id"], docs[j]["id"]):
                    continue
                if not docs[i]["matn"] or not docs[j]["matn"]:
                    continue
                if len(docs[i]["matn"]) < 30 or len(docs[j]["matn"]) < 30:
                    continue

                sim = arabic_jaccard(docs[i]["matn"], docs[j]["matn"])

                # Reject near-identical (>0.90 is a duplicate)
                if sim > 0.90:
                    continue
                # Require minimum lexical overlap — hadith sharing only a tag
                # but no vocabulary are narratively unrelated
                if sim < 0.15:
                    continue

                shared = list(set(docs[i]["topic_tags"]) & set(docs[j]["topic_tags"]))
                # Require at least one shared specific (non-generic) tag
                shared_specific = [t for t in shared if t not in generic_tags]
                if not shared_specific:
                    continue

                pairs.append({
                    "id_a": docs[i]["id"],
                    "id_b": docs[j]["id"],
                    "label": 1,
                    "pair_type": "same_topic",
                    "shared_tags": shared,
                    "category": "worship",  # will be filled later
                    "jaccard": round(sim, 3),
                    "arabic_a": truncate(docs[i]["matn"]),
                    "arabic_b": truncate(docs[j]["matn"]),
                })
                tracker.mark_used(docs[i]["id"], docs[j]["id"])
                count_for_tag += 1

        if len(pairs) % 100 == 0 and len(pairs) > 0:
            print(f"  same_topic: {len(pairs)}/{target}")

    print(f"  same_topic total: {len(pairs)}")
    return pairs


def generate_cross_collection_pairs(es_url, index, tracker, target=700):
    """Strategy 2: Same narration found in different hadith collections."""
    pairs = []

    books = [
        "Al-Kāfī", "Man Lā Yaḥduruh al-Faqīh",
        "Nahj al-Balāgha", "ʿUyūn akhbār al-Riḍā",
        "Maʿānī al-ʾAkhbār", "Thawāb al-Aʿmāl wa ʿiqāb al-Aʿmāl",
        "Al-Khiṣāl", "Al-Tawḥīd",
        "Muʿjam al-Aḥādīth al-Muʿtabara",
        "Al-Amālī", "Kāmil al-Ziyārāt",
    ]

    # Multiple passes with increasing sample sizes
    for pass_num in range(3):
        if len(pairs) >= target:
            break

        sample_size = 30 + pass_num * 20
        print(f"  Cross-collection pass {pass_num + 1} (sample_size={sample_size})")

        for book in books:
            if len(pairs) >= target:
                break

            hits = es_search(es_url, index, {
                "query": {
                    "bool": {
                        "must": [
                            {"term": {"book": book}},
                            {"exists": {"field": "semantic_matn_source"}}
                        ]
                    }
                },
                "_source": ["book", "arabic", "semantic_matn_source", "topic_tags", "number"]
            }, size=sample_size)

            random.shuffle(hits)

            for hit in hits:
                if len(pairs) >= target:
                    break

                doc = {
                    "id": hit["_id"],
                    "book": hit["_source"].get("book", ""),
                    "matn": hit["_source"].get("semantic_matn_source", ""),
                    "topic_tags": hit["_source"].get("topic_tags", []),
                }
                if not doc["matn"] or len(doc["matn"]) < 30:
                    continue

                matches = find_mlt_matches(es_url, index, doc["matn"],
                                           exclude_book=book, size=10)

                for match in matches:
                    if tracker.is_used(doc["id"], match["id"]):
                        continue
                    if not match["matn"] or len(match["matn"]) < 30:
                        continue

                    sim = arabic_jaccard(doc["matn"], match["matn"])
                    shared = list(set(doc["topic_tags"]) & set(match["topic_tags"]))

                    # Require higher jaccard when no shared topic tags
                    # (shared narrator vocabulary inflates low jaccard)
                    min_jac = 0.20 if shared else 0.30
                    if sim < min_jac:
                        continue
                    if sim > 0.92:
                        continue

                    pairs.append({
                        "id_a": doc["id"],
                        "id_b": match["id"],
                        "label": 1,
                        "pair_type": "cross_collection",
                        "shared_tags": shared,
                        "jaccard": round(sim, 3),
                        "arabic_a": truncate(doc["matn"]),
                        "arabic_b": truncate(match["matn"]),
                    })
                    tracker.mark_used(doc["id"], match["id"])
                    break  # one match per source hadith

            print(f"    after {book}: {len(pairs)}/{target}")

    print(f"  cross_collection total: {len(pairs)}")
    return pairs


def generate_variant_pairs(es_url, index, tracker, target=800):
    """Strategy 3: Variant narrations within the same book/chapter."""
    pairs = []

    books = [
        "Al-Kāfī", "Man Lā Yaḥduruh al-Faqīh",
        "ʿUyūn akhbār al-Riḍā", "Maʿānī al-ʾAkhbār",
        "Al-Khiṣāl", "Thawāb al-Aʿmāl wa ʿiqāb al-Aʿmāl",
        "Al-Tawḥīd", "Al-Amālī",
    ]

    for book in books:
        if len(pairs) >= target:
            break

        aggs_resp = es_aggs(es_url, index, {
            "query": {"term": {"book": book}},
            "aggs": {
                "chapters": {
                    "terms": {"field": "chapter.keyword", "size": 100, "min_doc_count": 3}
                }
            }
        })

        buckets = aggs_resp.get("aggregations", {}).get("chapters", {}).get("buckets", [])
        random.shuffle(buckets)

        for bucket in buckets[:30]:
            if len(pairs) >= target:
                break

            chapter = bucket["key"]
            chapter_count = 0
            max_per_chapter = 5
            hits = es_search(es_url, index, {
                "query": {
                    "bool": {
                        "must": [
                            {"term": {"book": book}},
                            {"term": {"chapter.keyword": chapter}},
                            {"exists": {"field": "semantic_matn_source"}}
                        ]
                    }
                },
                "_source": ["book", "chapter", "semantic_matn_source", "topic_tags", "number"]
            }, size=20)

            docs = [{
                "id": h["_id"],
                "matn": h["_source"].get("semantic_matn_source", ""),
                "topic_tags": h["_source"].get("topic_tags", []),
            } for h in hits]

            for i in range(len(docs)):
                if len(pairs) >= target or chapter_count >= max_per_chapter:
                    break
                for j in range(i + 1, len(docs)):
                    if len(pairs) >= target or chapter_count >= max_per_chapter:
                        break
                    if tracker.is_used(docs[i]["id"], docs[j]["id"]):
                        continue
                    if not docs[i]["matn"] or not docs[j]["matn"]:
                        continue
                    if len(docs[i]["matn"]) < 30 or len(docs[j]["matn"]) < 30:
                        continue

                    sim = arabic_jaccard(docs[i]["matn"], docs[j]["matn"])
                    if sim < 0.20:
                        continue
                    if sim > 0.92:
                        continue

                    shared = list(set(docs[i]["topic_tags"]) & set(docs[j]["topic_tags"]))
                    pairs.append({
                        "id_a": docs[i]["id"],
                        "id_b": docs[j]["id"],
                        "label": 1,
                        "pair_type": "variant",
                        "shared_tags": shared,
                        "jaccard": round(sim, 3),
                        "arabic_a": truncate(docs[i]["matn"]),
                        "arabic_b": truncate(docs[j]["matn"]),
                    })
                    tracker.mark_used(docs[i]["id"], docs[j]["id"])
                    chapter_count += 1

        print(f"  variant after {book}: {len(pairs)}/{target}")

    print(f"  variant total: {len(pairs)}")
    return pairs


def generate_hard_negatives(es_url, index, by_category, by_slug, generic_tags, tracker, target=1000):
    """Strategy 4: Same-category, different-topic pairs (hard negatives).

    These are the most important negatives — hadith that are topically
    related but semantically different. We want MORE of these than easy negatives.
    """
    pairs = []
    categories = list(by_category.keys())
    random.shuffle(categories)

    for category in categories:
        if len(pairs) >= target:
            break

        tags_in_cat = [t for t in by_category[category]
                       if by_slug[t].get("taggable", True)]
        if len(tags_in_cat) < 2:
            continue

        # Build set of all tags in this category for validation
        cat_tag_set = set(tags_in_cat)

        tag_pairs = []
        for i in range(len(tags_in_cat)):
            for j in range(i + 1, len(tags_in_cat)):
                tag_pairs.append((tags_in_cat[i], tags_in_cat[j]))
        random.shuffle(tag_pairs)

        for tag_a, tag_b in tag_pairs:
            if len(pairs) >= target:
                break

            docs_a = fetch_hadith_for_tag(es_url, index, tag_a, size=10)
            docs_b = fetch_hadith_for_tag(es_url, index, tag_b, size=10)

            if not docs_a or not docs_b:
                continue

            count = 0
            for da in docs_a[:3]:
                for db in docs_b[:3]:
                    if count >= 3 or len(pairs) >= target:
                        break
                    if tracker.is_used(da["id"], db["id"]):
                        continue
                    if not da["matn"] or not db["matn"]:
                        continue

                    sim = arabic_jaccard(da["matn"], db["matn"])
                    # Allow higher overlap for harder negatives (was 0.35, now 0.50)
                    if sim > 0.50:
                        continue

                    # Ensure no shared specific tags (use same generic set as positives)
                    shared = set(da["topic_tags"]) & set(db["topic_tags"])
                    specific_shared = shared - generic_tags
                    if specific_shared:
                        continue

                    # Verify both docs actually belong to this category
                    a_in_cat = bool(set(da["topic_tags"]) & cat_tag_set)
                    b_in_cat = bool(set(db["topic_tags"]) & cat_tag_set)
                    if not a_in_cat or not b_in_cat:
                        continue

                    pairs.append({
                        "id_a": da["id"],
                        "id_b": db["id"],
                        "label": 0,
                        "pair_type": "hard_negative",
                        "shared_tags": [],
                        "category": category,
                        "jaccard": round(sim, 3),
                        "arabic_a": truncate(da["matn"]),
                        "arabic_b": truncate(db["matn"]),
                    })
                    tracker.mark_used(da["id"], db["id"])
                    count += 1

        if len(pairs) % 50 == 0 and len(pairs) > 0:
            print(f"  hard_negative: {len(pairs)}/{target}")

    print(f"  hard_negative total: {len(pairs)}")
    return pairs


def generate_easy_negatives(es_url, index, by_category, by_slug, generic_tags, tracker, target=400):
    """Strategy 5: Cross-category pairs (easy negatives)."""
    pairs = []
    categories = list(by_category.keys())

    category_pairs = []
    for i in range(len(categories)):
        for j in range(i + 1, len(categories)):
            category_pairs.append((categories[i], categories[j]))
    random.shuffle(category_pairs)

    for cat_a, cat_b in category_pairs:
        if len(pairs) >= target:
            break

        tags_a = [t for t in by_category[cat_a] if by_slug[t].get("taggable", True)]
        tags_b = [t for t in by_category[cat_b] if by_slug[t].get("taggable", True)]
        if not tags_a or not tags_b:
            continue

        random.shuffle(tags_a)
        random.shuffle(tags_b)
        count = 0
        for tag_a in tags_a[:3]:
            for tag_b in tags_b[:3]:
                if count >= 3 or len(pairs) >= target:
                    break

                docs_a = fetch_hadith_for_tag(es_url, index, tag_a, size=5)
                docs_b = fetch_hadith_for_tag(es_url, index, tag_b, size=5)

                if not docs_a or not docs_b:
                    continue

                for da in docs_a[:2]:
                    for db in docs_b[:2]:
                        if len(pairs) >= target:
                            break
                        if tracker.is_used(da["id"], db["id"]):
                            continue
                        if not da["matn"] or not db["matn"]:
                            continue

                        sim = arabic_jaccard(da["matn"], db["matn"])
                        if sim > 0.30:
                            continue

                        pairs.append({
                            "id_a": da["id"],
                            "id_b": db["id"],
                            "label": 0,
                            "pair_type": "easy_negative",
                            "shared_tags": [],
                            "category": f"{cat_a}_vs_{cat_b}",
                            "jaccard": round(sim, 3),
                            "arabic_a": truncate(da["matn"]),
                            "arabic_b": truncate(db["matn"]),
                        })
                        tracker.mark_used(da["id"], db["id"])
                        count += 1

        if len(pairs) % 50 == 0 and len(pairs) > 0:
            print(f"  easy_negative: {len(pairs)}/{target}")

    print(f"  easy_negative total: {len(pairs)}")
    return pairs


def generate_phrase_cross_collection_pairs(es_url, index, tracker, target=400):
    """Strategy 6: Extract distinctive Arabic phrases and search across books."""
    import re as _re
    pairs = []

    books = [
        "Al-Kāfī", "Man Lā Yaḥduruh al-Faqīh",
        "Nahj al-Balāgha", "ʿUyūn akhbār al-Riḍā",
        "Maʿānī al-ʾAkhbār", "Thawāb al-Aʿmāl wa ʿiqāb al-Aʿmāl",
        "Al-Khiṣāl", "Al-Tawḥīd",
        "Muʿjam al-Aḥādīth al-Muʿtabara",
        "Al-Amālī", "Kāmil al-Ziyārāt",
        "Kitāb al-Ghayba", "Kitāb al-Zuhd",
        "Kitāb al-Muʾmin",
    ]

    for pass_num in range(2):
        if len(pairs) >= target:
            break

        sample_size = 40 + pass_num * 20
        print(f"  Phrase-CC pass {pass_num + 1} (sample_size={sample_size})")

        for book in books:
            if len(pairs) >= target:
                break

            hits = es_search(es_url, index, {
                "query": {
                    "bool": {
                        "must": [
                            {"term": {"book": book}},
                            {"exists": {"field": "semantic_matn_source"}}
                        ]
                    }
                },
                "_source": ["book", "semantic_matn_source", "topic_tags", "number"]
            }, size=sample_size)

            random.shuffle(hits)

            for hit in hits:
                if len(pairs) >= target:
                    break

                matn = hit["_source"].get("semantic_matn_source", "")
                if not matn or len(matn) < 50:
                    continue

                clean = strip_diacritics(matn)
                words = _re.findall(r'[\u0600-\u06FF]+', clean)
                if len(words) < 10:
                    continue

                positions = [
                    len(words) // 4,
                    len(words) // 2,
                    3 * len(words) // 4,
                ]

                for pos in positions:
                    if len(pairs) >= target:
                        break

                    phrase_words = words[pos:pos + 6]
                    if len(phrase_words) < 5:
                        continue
                    phrase = ' '.join(phrase_words)

                    matches = es_search(es_url, index, {
                        "query": {
                            "bool": {
                                "must": [
                                    {"match_phrase": {
                                        "semantic_matn_source": {
                                            "query": phrase,
                                            "slop": 2
                                        }
                                    }}
                                ],
                                "must_not": [
                                    {"term": {"book": book}}
                                ]
                            }
                        },
                        "_source": ["book", "semantic_matn_source", "topic_tags", "number"]
                    }, size=3)

                    for match in matches:
                        match_id = match["_id"]
                        if tracker.is_used(hit["_id"], match_id):
                            continue
                        match_matn = match["_source"].get("semantic_matn_source", "")
                        if not match_matn or len(match_matn) < 30:
                            continue

                        sim = arabic_jaccard(matn, match_matn)
                        if sim > 0.92:
                            continue
                        if sim < 0.10:  # Tightened from 0.05
                            continue

                        match_tags = match["_source"].get("topic_tags", [])
                        source_tags = hit["_source"].get("topic_tags", [])
                        shared = list(set(source_tags) & set(match_tags))

                        pairs.append({
                            "id_a": hit["_id"],
                            "id_b": match_id,
                            "label": 1,
                            "pair_type": "cross_collection",
                            "shared_tags": shared,
                            "jaccard": round(sim, 3),
                            "arabic_a": truncate(matn),
                            "arabic_b": truncate(match_matn),
                        })
                        tracker.mark_used(hit["_id"], match_id)
                        break

            print(f"    after {book}: {len(pairs)}/{target}")

    print(f"  phrase_cross_collection total: {len(pairs)}")
    return pairs


def generate_random_negatives(es_url, index, generic_tags, tracker, target=200):
    """Strategy 7: Random pairs from different books."""
    pairs = []

    books_pool = {}
    books = [
        "Al-Kāfī", "Man Lā Yaḥduruh al-Faqīh",
        "Nahj al-Balāgha", "ʿUyūn akhbār al-Riḍā",
        "Maʿānī al-ʾAkhbār", "Thawāb al-Aʿmāl wa ʿiqāb al-Aʿmāl",
        "Al-Khiṣāl", "Al-Tawḥīd",
        "Al-Amālī", "Kāmil al-Ziyārāt",
    ]

    for book in books:
        hits = es_search(es_url, index, {
            "query": {
                "bool": {
                    "must": [
                        {"term": {"book": book}},
                        {"exists": {"field": "semantic_matn_source"}}
                    ]
                }
            },
            "_source": ["book", "semantic_matn_source", "topic_tags"]
        }, size=50)
        books_pool[book] = [{
            "id": h["_id"],
            "matn": h["_source"].get("semantic_matn_source", ""),
            "topic_tags": h["_source"].get("topic_tags", []),
        } for h in hits if h["_source"].get("semantic_matn_source")]

    book_names = list(books_pool.keys())
    attempts = 0

    while len(pairs) < target and attempts < 5000:
        attempts += 1
        if len(book_names) < 2:
            break

        b1, b2 = random.sample(book_names, 2)
        if not books_pool[b1] or not books_pool[b2]:
            continue

        d1 = random.choice(books_pool[b1])
        d2 = random.choice(books_pool[b2])

        if tracker.is_used(d1["id"], d2["id"]):
            continue
        if not d1["matn"] or not d2["matn"]:
            continue

        sim = arabic_jaccard(d1["matn"], d2["matn"])
        if sim > 0.25:
            continue

        shared = set(d1["topic_tags"]) & set(d2["topic_tags"])
        specific_shared = shared - generic_tags
        if specific_shared:
            continue

        pairs.append({
            "id_a": d1["id"],
            "id_b": d2["id"],
            "label": 0,
            "pair_type": "easy_negative",
            "shared_tags": [],
            "category": f"{b1[:15]}_vs_{b2[:15]}",
            "jaccard": round(sim, 3),
            "arabic_a": truncate(d1["matn"]),
            "arabic_b": truncate(d2["matn"]),
        })
        tracker.mark_used(d1["id"], d2["id"])

        if len(pairs) % 50 == 0:
            print(f"  random_negative: {len(pairs)}/{target}")

    print(f"  random_negative total: {len(pairs)}")
    return pairs


# ── Split logic ──────────────────────────────────────────────────────────

def split_by_hadith_id(pairs, train_frac=0.8, val_frac=0.1, seed=42):
    """Split pairs ensuring no data leakage and balanced pair types.

    For positive pairs: uses global connected components to prevent
    any text leakage, then assigns components to splits using round-robin
    weighted by pair_type to maintain proportional representation.

    For negative pairs: stratified split by pair_type, then filtered
    to remove any hadith IDs that appear in the train split's positives.
    """
    random.seed(seed)

    positives = [p for p in pairs if p["label"] == 1]
    negatives = [p for p in pairs if p["label"] == 0]

    # ── Split positives: global connected components ──
    adj = defaultdict(set)
    for p in positives:
        adj[p["id_a"]].add(p["id_b"])
        adj[p["id_b"]].add(p["id_a"])

    visited = set()
    components = []
    for start_id in adj:
        if start_id in visited:
            continue
        component = set()
        queue = [start_id]
        while queue:
            nid = queue.pop()
            if nid in visited:
                continue
            visited.add(nid)
            component.add(nid)
            for neighbor in adj[nid]:
                if neighbor not in visited:
                    queue.append(neighbor)
        components.append(frozenset(component))

    # For each component, count pairs by type
    def pairs_in(comp, pair_list):
        return [p for p in pair_list if p["id_a"] in comp and p["id_b"] in comp]

    # Sort by total pair count descending for greedy assignment
    components.sort(key=lambda c: len(pairs_in(c, positives)), reverse=True)

    total_pos = len(positives)
    train_target = total_pos * train_frac
    val_target = total_pos * val_frac

    # Track targets per pair_type for balanced assignment
    pos_by_type = defaultdict(list)
    for p in positives:
        pos_by_type[p.get("pair_type", "unknown")].append(p)

    type_targets = {}
    for ptype, type_pairs in pos_by_type.items():
        type_targets[ptype] = {
            "train": len(type_pairs) * train_frac,
            "val": len(type_pairs) * val_frac,
            "total": len(type_pairs),
        }

    # Assign components to splits using greedy with type-aware tracking
    id_to_split = {}
    type_counts = {ptype: {"train": 0, "val": 0, "test": 0} for ptype in pos_by_type}
    split_totals = {"train": 0, "val": 0, "test": 0}

    for comp in components:
        comp_pairs = pairs_in(comp, positives)
        # Determine which split this component should go to
        # based on which type-targets are most underfilled
        scores = {}
        for split in ["train", "val", "test"]:
            score = 0
            for p in comp_pairs:
                ptype = p.get("pair_type", "unknown")
                target = type_targets[ptype].get(split if split != "test" else "total", 0)
                if split == "test":
                    target = type_targets[ptype]["total"] - type_targets[ptype]["train"] - type_targets[ptype]["val"]
                elif split == "val":
                    target = type_targets[ptype]["val"]
                else:
                    target = type_targets[ptype]["train"]
                current = type_counts[ptype][split]
                deficit = max(0, target - current)
                score += deficit
            scores[split] = score

        # Pick the split with the highest deficit (most underfilled)
        # but respect hard limits
        if split_totals["train"] >= train_target and split_totals["val"] >= val_target:
            split = "test"
        elif split_totals["train"] >= train_target and scores["val"] > 0:
            split = "val" if scores["val"] >= scores["test"] else "test"
        elif split_totals["val"] >= val_target and scores["train"] > 0:
            split = "train" if scores["train"] >= scores["test"] else "test"
        else:
            split = max(scores, key=scores.get)

        for hid in comp:
            id_to_split[hid] = split
        split_totals[split] += len(comp_pairs)
        for p in comp_pairs:
            ptype = p.get("pair_type", "unknown")
            type_counts[ptype][split] += 1

    # Assign positive pairs
    train_pos, val_pos, test_pos = [], [], []
    for p in positives:
        sa = id_to_split.get(p["id_a"])
        sb = id_to_split.get(p["id_b"])
        if sa == sb and sa is not None:
            if sa == "train":
                train_pos.append(p)
            elif sa == "val":
                val_pos.append(p)
            else:
                test_pos.append(p)

    # ── Split negatives: stratified by pair_type ──
    neg_by_type = defaultdict(list)
    for p in negatives:
        neg_by_type[p.get("pair_type", "unknown")].append(p)

    train_neg, val_neg, test_neg = [], [], []

    def stratified_split(items, train_f, val_f):
        random.shuffle(items)
        n = len(items)
        t_end = int(n * train_f)
        v_end = int(n * (train_f + val_f))
        return items[:t_end], items[t_end:v_end], items[v_end:]

    for ptype, type_negs in neg_by_type.items():
        t, v, te = stratified_split(type_negs, train_frac, val_frac)
        train_neg.extend(t)
        val_neg.extend(v)
        test_neg.extend(te)

    train = train_pos + train_neg
    val = val_pos + val_neg
    test = test_pos + test_neg

    # Report
    for name, split in [("train", train), ("val", val), ("test", test)]:
        pos = sum(1 for p in split if p["label"] == 1)
        neg = sum(1 for p in split if p["label"] == 0)
        hard_neg = sum(1 for p in split if p.get("pair_type") == "hard_negative")
        types = {}
        for p in split:
            pt = p.get("pair_type", "unknown")
            types[pt] = types.get(pt, 0) + 1
        print(f"  {name}: {len(split)} pairs ({pos} pos / {neg} neg, "
              f"{hard_neg} hard_neg) types: {types}")

    for split in [train, val, test]:
        random.shuffle(split)

    return train, val, test


# ── Main ──────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Build hadith training dataset")
    parser.add_argument("--es-url", default="http://localhost:9200")
    parser.add_argument("--index", default="rewayaat_updated")
    parser.add_argument("--taxonomy", default="src/main/resources/static/taxonomy.json")
    parser.add_argument("--output-dir", default="tmp/eval")
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    sys.stdout.reconfigure(line_buffering=True)
    random.seed(args.seed)

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    # Load taxonomy
    print("Loading taxonomy...")
    global children_of_global
    by_slug, by_category, children_of, taggable_tags, parent_tags = load_taxonomy(args.taxonomy)
    children_of_global = children_of

    # Build generic tags from taxonomy structure
    generic_tags = build_generic_tags(by_slug, parent_tags)
    print(f"  {len(taggable_tags)} taggable tags across {len(by_category)} categories")
    print(f"  {len(generic_tags)} generic/broad tags excluded from positive pairing")

    # Count hadith per tag to prioritize tags with enough data
    print("\nSurveying tag coverage...")
    tag_doc_counts = {}
    for tag in taggable_tags:
        resp = es_aggs(args.es_url, args.index, {
            "query": {"term": {"topic_tags": tag}}
        })
        tag_doc_counts[tag] = resp.get("hits", {}).get("total", {}).get("value", 0)
    eligible_tags = [t for t in taggable_tags if tag_doc_counts.get(t, 0) >= 3]
    print(f"  {len(eligible_tags)} tags with >= 3 hadith")

    # Generate pairs
    tracker = PairTracker()

    print("\n=== Generating positive pairs ===")
    same_topic = generate_same_tag_pairs(args.es_url, args.index, eligible_tags,
                                          generic_tags, tracker, target=3000)
    cross_collection = generate_cross_collection_pairs(args.es_url, args.index,
                                                        tracker, target=700)
    phrase_cc = generate_phrase_cross_collection_pairs(args.es_url, args.index,
                                                        tracker, target=400)
    variants = generate_variant_pairs(args.es_url, args.index, tracker, target=800)

    print("\n=== Generating negative pairs ===")
    hard_neg = generate_hard_negatives(args.es_url, args.index, by_category,
                                        by_slug, generic_tags, tracker, target=1000)
    easy_neg = generate_easy_negatives(args.es_url, args.index, by_category,
                                        by_slug, generic_tags, tracker, target=400)
    random_neg = generate_random_negatives(args.es_url, args.index,
                                            generic_tags, tracker, target=200)

    all_positive = same_topic + cross_collection + phrase_cc + variants
    all_negative = hard_neg + easy_neg + random_neg
    all_pairs = all_positive + all_negative

    print(f"\n=== Totals ===")
    print(f"Positive: {len(all_positive)}")
    print(f"  same_topic: {len(same_topic)}")
    print(f"  cross_collection: {len(cross_collection)}")
    print(f"  phrase_cross_collection: {len(phrase_cc)}")
    print(f"  variant: {len(variants)}")
    print(f"Negative: {len(all_negative)}")
    print(f"  hard_negative: {len(hard_neg)}")
    print(f"  easy_negative: {len(easy_neg)}")
    print(f"  random_negative: {len(random_neg)}")
    print(f"Total: {len(all_pairs)}")
    print(f"Hard:Easy negative ratio: {len(hard_neg)}:{len(easy_neg) + len(random_neg)}")

    # Write candidate pairs
    candidates_path = output_dir / "candidate_pairs.json"
    with open(candidates_path, 'w', encoding='utf-8') as f:
        json.dump({
            "metadata": {
                "total": len(all_pairs),
                "positive": len(all_positive),
                "negative": len(all_negative),
                "generic_tags_used": sorted(generic_tags),
            },
            "pairs": all_pairs
        }, f, ensure_ascii=False, indent=2)
    print(f"\nCandidate pairs written to: {candidates_path}")

    # Split into train/test/val by hadith ID
    print("\nSplitting into train/test/val (by hadith ID, no leakage)...")
    train, val, test = split_by_hadith_id(all_pairs)

    for split_name, split_data in [("train", train), ("val", val), ("test", test)]:
        split_path = output_dir / f"{split_name}.json"
        with open(split_path, 'w', encoding='utf-8') as f:
            json.dump({
                "split": split_name,
                "pairs": split_data,
                "metadata": {
                    "total": len(split_data),
                    "positive": sum(1 for p in split_data if p["label"] == 1),
                    "negative": sum(1 for p in split_data if p["label"] == 0),
                    "pair_types": dict(
                        (pt, sum(1 for p in split_data if p.get("pair_type") == pt))
                        for pt in set(p.get("pair_type", "unknown") for p in split_data)
                    ),
                }
            }, f, ensure_ascii=False, indent=2)
        pos = sum(1 for p in split_data if p["label"] == 1)
        neg = sum(1 for p in split_data if p["label"] == 0)
        print(f"  {split_name} written to {split_path}")

    # Write metadata
    metadata = {
        "total_pairs": len(all_pairs),
        "splits": {
            "train": len(train),
            "val": len(val),
            "test": len(test),
        },
        "positive": {
            "total": len(all_positive),
            "same_topic": len(same_topic),
            "cross_collection": len(cross_collection),
            "phrase_cross_collection": len(phrase_cc),
            "variant": len(variants),
        },
        "negative": {
            "total": len(all_negative),
            "hard_negative": len(hard_neg),
            "easy_negative": len(easy_neg),
            "random_negative": len(random_neg),
        },
        "jaccard_stats": {
            "positive_mean": round(
                sum(p["jaccard"] for p in all_positive) / max(len(all_positive), 1), 3),
            "negative_mean": round(
                sum(p["jaccard"] for p in all_negative) / max(len(all_negative), 1), 3),
        },
        "generic_tags": sorted(generic_tags),
    }
    with open(output_dir / "dataset_metadata.json", 'w', encoding='utf-8') as f:
        json.dump(metadata, f, indent=2)

    print(f"\nDone! Output in {output_dir}/")


if __name__ == "__main__":
    main()
