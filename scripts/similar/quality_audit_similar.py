#!/usr/bin/env python3
"""
Systematic quality audit for similar-hadith retrieval.
Picks random hadith from diverse topic tags, calls the similar API,
and outputs structured quality data for analysis.
"""
import json
import random
import sys
import urllib.request
import urllib.error

ES = "http://localhost:9200"
API = "http://localhost:8002"
INDEX = "rewayaat_updated"

# Diverse tags covering the full taxonomy breadth
TAG_CLUSTERS = {
    "theology": ["tawhid", "tanzih", "shirk", "divine-decree", "names-of-god", "divine-knowledge"],
    "prophets": ["prophet-muhammad", "ibrahim", "musa", "isa", "nuh", "dawud", "adam"],
    "imams": ["imam-ali", "imam-mahdi", "imam-husayn", "imam-ridha", "imam-ja-far",
              "imam-kazim", "imam-baqir", "imam-zayn-al-abidin", "imam-askari", "imam-hasan",
              "fatimah", "twelve-imams"],
    "worship_ritual": ["worship", "fasting", "wudu", "ghusl", "congregational-prayer",
                       "friday-prayer", "night-prayer", "prayer-etiquette", "call-to-prayer",
                       "prostration", "travel-prayer", "mosque", "tayammum", "itikaf"],
    "hajj": ["ihram", "tawaf", "sai", "sacrifice", "umrah"],
    "ethics_character": ["taqwa", "patience", "asceticism", "repentance", "trust-in-god",
                         "generosity", "gratitude", "contentment", "humility", "sincerity",
                         "truthfulness", "good-temper", "modesty", "kindness", "courage"],
    "social": ["marriage", "divorce", "brotherhood", "women", "children", "parents",
               "relatives", "neighbors", "orphans", "slavery-captives"],
    "legal": ["trade", "penalties", "inheritance", "testimony-judgment", "rights",
              "jurisprudence", "usury", "blood-money", "debt-loans", "liability"],
    "food_wealth": ["food-drink", "alcohol", "hunting-slaughter", "spending", "zakat",
                    "wealth-materialism", "khums", "taxation", "gambling"],
    "eschatology": ["death", "paradise", "hellfire", "day-of-judgement", "resurrection",
                    "intercession", "martyrdom", "burial"],
    "occultation": ["occultation", "imam-mahdi", "reappearance-signs"],
    "wilayah": ["wilayah", "governance", "ahl-al-bayt", "taqiyyah"],
    "sins_vices": ["disbelief", "backbiting-slander", "lying", "envy", "arrogance",
                   "anger", "greed", "hypocrisy", "major-sins", "adultery",
                   "satan-iblis", "heedlessness"],
    "quran_knowledge": ["quran", "intellect", "wisdom", "revelation", "abrogation", "unseen"],
    "warfare": ["warfare-jihad", "oppression", "enemies"],
    "ritual_purity": ["water-purity", "menstruation", "breastfeeding", "touching-deceased",
                      "washing-deceased", "funeral-prayer"],
    "specific_narrow": ["christians-jews", "jinn", "miracle", "dream-interpretation",
                        "animal-welfare", "temporary-marriage", "sexual-ethics",
                        "previous-nations", "parable"],
}

def es_get(path):
    req = urllib.request.Request(f"{ES}{path}", headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.loads(resp.read())

def es_search(body):
    data = json.dumps(body).encode()
    req = urllib.request.Request(f"{ES}/{INDEX}/_search", data=data,
                                headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read())

def api_similar(hadith_id):
    url = f"{API}/v1/narrations/similar?id={urllib.parse.quote(hadith_id)}&per_page=10"
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read())

def truncate(text, max_len=80):
    if not text:
        return ""
    text = text.strip().replace("\n", " ")
    if len(text) > max_len:
        return text[:max_len] + "..."
    return text

import urllib.parse

def pick_hadith_for_tag(tag, count=3):
    """Pick random hadith for a given tag that have semantic vectors."""
    body = {
        "size": count * 5,
        "query": {
            "bool": {
                "must": [
                    {"term": {"topic_tags": tag}},
                    {"exists": {"field": "semantic_vector"}}
                ]
            }
        },
        "_source": ["arabic", "topic_tags", "book", "english"],
        "sort": ["_doc"]
    }
    results = es_search(body)
    hits = results.get("hits", {}).get("hits", [])
    if not hits:
        return []
    random.shuffle(hits)
    return hits[:count]

def audit_hadith(hadith_id, source_tags, source_text_snippet):
    """Run similar API and return quality metrics."""
    try:
        data = api_similar(hadith_id)
    except Exception as e:
        return {"error": str(e), "id": hadith_id}

    results = data.get("collection", data.get("results", data.get("hadithes", [])))
    total = data.get("totalHits", data.get("total", len(results)))

    quality_notes = []
    result_details = []

    for r in results[:10]:
        r_tags = r.get("topic_tags", [])
        r_text = truncate(r.get("arabic", ""), 80)
        r_en = truncate(r.get("english", ""), 80)
        sim = r.get("similarityPercent", 0)
        sem = r.get("semanticSimilarityPercent", 0)
        content = r.get("contentOverlapPercent", 0)
        topic = r.get("topicOverlapPercent", 0)
        syntactic = r.get("syntacticSimilarityPercent", 0)
        shared_tags = r.get("sharedTopicTags", [])
        shared_tag_count = r.get("sharedTopicTagCount", 0)
        shared_dist = r.get("sharedDistinctiveTokenCount", 0)

        result_details.append({
            "id": r.get("_id", ""),
            "sim": round(sim, 1),
            "sem": round(sem, 1),
            "content": round(content, 1),
            "topic": round(topic, 1),
            "syntactic": round(syntactic, 1),
            "tags": r_tags,
            "shared_tags": shared_tags,
            "shared_tag_count": shared_tag_count,
            "shared_dist_tokens": shared_dist,
            "text": r_text,
            "english": r_en,
        })

    return {
        "id": hadith_id,
        "source_tags": source_tags,
        "source_text": truncate(source_text_snippet, 80),
        "total": total,
        "results": result_details,
    }

def main():
    random.seed(42)
    all_audits = []

    # Flatten clusters into a diverse sample
    test_tags = []
    for cluster_name, tags in TAG_CLUSTERS.items():
        # Pick 1-2 tags per cluster
        sample_size = min(2, len(tags))
        chosen = random.sample(tags, sample_size)
        for tag in chosen:
            test_tags.append((cluster_name, tag))

    print(f"=== SIMILAR HADITH QUALITY AUDIT ===")
    print(f"Testing {len(test_tags)} tags across {len(TAG_CLUSTERS)} clusters")
    print(f"Parameters: min_percent=45%, content_overlap_floor=12%, topic_dampening_enabled")
    print()

    for cluster_name, tag in test_tags:
        hadiths = pick_hadith_for_tag(tag, count=2)
        if not hadiths:
            print(f"  [{cluster_name}] {tag}: NO HITS")
            continue

        for hit in hadiths:
            hid = hit["_id"]
            src = hit.get("_source", {})
            tags = src.get("topic_tags", [])
            text = src.get("arabic", "")
            en = src.get("english", "")

            result = audit_hadith(hid, tags, text)
            result["cluster"] = cluster_name
            result["tag"] = tag
            result["source_english"] = truncate(en, 120)
            all_audits.append(result)

            # Print summary
            total = result.get("total", 0)
            n_results = len(result.get("results", []))
            print(f"[{cluster_name:20s}] tag={tag:25s} | {hid}")
            print(f"  Source tags: {tags}")
            print(f"  Source EN: {result.get('source_english', 'N/A')}")
            print(f"  Results: {total} total, {n_results} returned")

            for i, r in enumerate(result.get("results", [])):
                quality_flag = ""
                if r["content"] < 10 and r["topic"] > 50:
                    quality_flag = " ⚠️ TAG-NOISE"
                elif r["content"] > 30:
                    quality_flag = " ✓ GOOD"
                elif r["sim"] < 50:
                    quality_flag = " ⚡ WEAK"
                print(f"    {i+1}. sim={r['sim']:5.1f}% sem={r['sem']:5.1f}% cont={r['content']:5.1f}% "
                      f"topic={r['topic']:5.1f}% synt={r['syntactic']:5.1f}% "
                      f"tags={r['shared_tag_count']} dist={r['shared_dist_tokens']} "
                      f"{quality_flag}")
                print(f"       EN: {r['english']}")
                print(f"       Shared tags: {r['shared_tags']}")
            print()

    # Summary statistics
    print("=" * 80)
    print("QUALITY SUMMARY")
    print("=" * 80)

    total_tested = len(all_audits)
    total_results = sum(a.get("total", 0) for a in all_audits)
    avg_results = total_results / max(1, total_tested)

    tag_noise_count = 0
    good_content_count = 0
    zero_results = 0
    weak_results = 0

    low_content_results = []
    high_tag_noise_results = []

    for a in all_audits:
        if a.get("total", 0) == 0:
            zero_results += 1
            continue
        for r in a.get("results", []):
            if r["content"] < 10 and r["topic"] > 50:
                tag_noise_count += 1
                high_tag_noise_results.append({
                    "source_id": a["id"],
                    "source_tags": a["source_tags"],
                    "result_id": r["id"],
                    "sim": r["sim"],
                    "content": r["content"],
                    "topic": r["topic"],
                    "shared_tags": r["shared_tags"],
                })
            elif r["content"] > 30:
                good_content_count += 1
            if r["sim"] < 50:
                weak_results += 1
            if r["content"] < 12 and r["content"] > 0:
                low_content_results.append({
                    "source_id": a["id"],
                    "result_id": r["id"],
                    "content": r["content"],
                    "sim": r["sim"],
                    "topic": r["topic"],
                    "syntactic": r["syntactic"],
                })

    all_sim_scores = []
    all_content_scores = []
    for a in all_audits:
        for r in a.get("results", []):
            all_sim_scores.append(r["sim"])
            all_content_scores.append(r["content"])

    print(f"Total hadith tested: {total_tested}")
    print(f"Zero-result hadith: {zero_results}")
    print(f"Average results per hadith: {avg_results:.1f}")
    if all_sim_scores:
        print(f"Similarity range: {min(all_sim_scores):.1f}% - {max(all_sim_scores):.1f}%")
        print(f"Average similarity: {sum(all_sim_scores)/len(all_sim_scores):.1f}%")
    if all_content_scores:
        print(f"Content overlap range: {min(all_content_scores):.1f}% - {max(all_content_scores):.1f}%")
        print(f"Average content overlap: {sum(all_content_scores)/len(all_content_scores):.1f}%")
    print(f"Tag-noise results (low content, high topic): {tag_noise_count}")
    print(f"Good content results (>30%): {good_content_count}")
    print(f"Weak results (<50% sim): {weak_results}")

    if high_tag_noise_results:
        print("\nTAG NOISE INSTANCES:")
        for n in high_tag_noise_results[:10]:
            print(f"  {n['source_id']} -> {n['result_id']} "
                  f"sim={n['sim']:.1f}% content={n['content']:.1f}% topic={n['topic']:.1f}% "
                  f"shared_tags={n['shared_tags']}")

    if low_content_results:
        print(f"\nLOW CONTENT (<12%) RESULTS: {len(low_content_results)}")
        content_vals = [c["content"] for c in low_content_results]
        print(f"  Content range: {min(content_vals):.1f}% - {max(content_vals):.1f}%")
        print(f"  Examples:")
        for c in low_content_results[:5]:
            print(f"    {c['source_id']} -> {c['result_id']} "
                  f"content={c['content']:.1f}% sim={c['sim']:.1f}% topic={c['topic']:.1f}%")

    # Cluster-level analysis
    print("\nCLUSTER-LEVEL ANALYSIS:")
    cluster_stats = {}
    for a in all_audits:
        cluster = a.get("cluster", "unknown")
        if cluster not in cluster_stats:
            cluster_stats[cluster] = {"tested": 0, "total_results": 0,
                                       "avg_sim": [], "avg_content": [], "tag_noise": 0}
        cluster_stats[cluster]["tested"] += 1
        cluster_stats[cluster]["total_results"] += a.get("total", 0)
        for r in a.get("results", []):
            cluster_stats[cluster]["avg_sim"].append(r["sim"])
            cluster_stats[cluster]["avg_content"].append(r["content"])
            if r["content"] < 10 and r["topic"] > 50:
                cluster_stats[cluster]["tag_noise"] += 1

    for cluster, stats in sorted(cluster_stats.items()):
        avg_sim = sum(stats["avg_sim"]) / max(1, len(stats["avg_sim"]))
        avg_cont = sum(stats["avg_content"]) / max(1, len(stats["avg_content"]))
        avg_res = stats["total_results"] / max(1, stats["tested"])
        noise_flag = " ⚠️" if stats["tag_noise"] > 0 else ""
        print(f"  {cluster:20s}: tested={stats['tested']} avg_results={avg_res:.1f} "
              f"avg_sim={avg_sim:.1f}% avg_content={avg_cont:.1f}% "
              f"tag_noise={stats['tag_noise']}{noise_flag}")

if __name__ == "__main__":
    main()
