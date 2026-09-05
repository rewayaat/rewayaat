#!/usr/bin/env python3
"""ES retrieval for LLM-powered similar hadith discovery.

Handles multi-method candidate retrieval from Elasticsearch.
No LLM calls — sub-agents do the filtering.

Optimizations:
  - Bidirectional pair caching (opt 2)
  - Batch hadith per agent (opt 3)
  - Pre-filter to top 30 candidates (opt 4)
  - Resume from checkpoint (opt 5)
  - Auto-accept obvious wording matches >80% token overlap (opt 6)

Modes:
  --precompute N       Pre-compute candidates for N hadith → JSONL
  --build-cache        Auto-accept obvious pairs → pairs cache
  --prepare-batches    Create batch files for agents (skips cached)
  --merge FILE         Merge agent results into cache
  --id / --sample / --tag   Single/batch retrieval (original mode)
  --concepts           Concept-based retrieval
"""
import argparse
import fcntl
import json
import os
import sys
import time
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ES_URL = os.environ.get("ELASTICSEARCH_URL", "http://localhost:9200").rstrip("/")
ES_INDEX = os.environ.get("REWAYAAT_INDEX", "rewayaat_updated")
SOURCE_FIELDS = ["semantic_matn_source", "semantic_english_hint_source",
                 "topic_tags", "book", "chapter"]
MAX_PER_METHOD = int(os.environ.get("LLM_SIMILAR_MAX_PER_METHOD", "25"))
TIMEOUT = int(os.environ.get("LLM_SIMILAR_TIMEOUT", "30"))
MAX_RETRIES = 3
PREFILTER_TOP = 30
AUTO_ACCEPT_THRESHOLD = 0.80

# Per-method minimum BM25 score thresholds (based on historical analysis)
MIN_SCORE_BY_METHOD = {
    "bm25_arabic": 18,
    "bm25_english": 16,
    "topic_overlap": 0,
    "same_chapter": 0,
}

# --- ES utilities ---

def http_json(url, payload=None, timeout=TIMEOUT):
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload else None
    headers = {"Content-Type": "application/json"}
    for attempt in range(MAX_RETRIES):
        try:
            req = urllib.request.Request(url, data=body, headers=headers, method="POST")
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
            if attempt == MAX_RETRIES - 1:
                raise
            time.sleep(1 * (attempt + 1))


def http_delete(url, payload=None, timeout=TIMEOUT):
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload else None
    headers = {"Content-Type": "application/json"}
    req = urllib.request.Request(url, data=body, headers=headers, method="DELETE")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception:
        return {}


def es_get(doc_id):
    url = f"{ES_URL}/{ES_INDEX}/_doc/{urllib.parse.quote(doc_id, safe='')}"
    try:
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        if not data.get("found"):
            return None
        return {"id": data["_id"], **data["_source"]}
    except urllib.error.HTTPError:
        return None


def es_search(body):
    url = f"{ES_URL}/{ES_INDEX}/_search"
    data = http_json(url, body)
    hits = data.get("hits", {}).get("hits", [])
    return [(h["_id"], h.get("_score", 0), h.get("_source", {})) for h in hits]


# --- Retrieval methods ---

def candidates_bm25_arabic(source_id):
    hits = es_search({
        "size": MAX_PER_METHOD,
        "query": {
            "more_like_this": {
                "fields": ["semantic_matn_source"],
                "like": [{"_index": ES_INDEX, "_id": source_id}],
                "min_term_freq": 1, "min_doc_freq": 3,
                "max_query_terms": 25, "minimum_should_match": "30%"
            }
        },
        "_source": SOURCE_FIELDS
    })
    return [(hid, score, src, "bm25_arabic") for hid, score, src in hits]


def candidates_bm25_english(source_id):
    hits = es_search({
        "size": MAX_PER_METHOD,
        "query": {
            "more_like_this": {
                "fields": ["semantic_english_hint_source"],
                "like": [{"_index": ES_INDEX, "_id": source_id}],
                "min_term_freq": 1, "min_doc_freq": 2,
                "max_query_terms": 15, "minimum_should_match": "30%"
            }
        },
        "_source": SOURCE_FIELDS
    })
    return [(hid, score, src, "bm25_english") for hid, score, src in hits]


def candidates_topic_overlap(source_id, source_tags):
    if not source_tags or len(source_tags) < 2:
        return []
    should = [{"term": {"topic_tags": tag}} for tag in source_tags]
    hits = es_search({
        "size": MAX_PER_METHOD,
        "query": {"bool": {"should": should, "minimum_should_match": 2,
                           "must_not": [{"ids": {"values": [source_id]}}]}},
        "_source": SOURCE_FIELDS
    })
    return [(hid, score, src, "topic_overlap") for hid, score, src in hits]


def candidates_same_chapter(source_id, book, chapter):
    must = []
    if book:
        must.append({"term": {"book": book}})
    if chapter:
        must.append({"term": {"chapter": chapter}})
    if not must:
        return []
    hits = es_search({
        "size": min(MAX_PER_METHOD, 30),
        "query": {"bool": {"must": must, "must_not": [{"ids": {"values": [source_id]}}]}},
        "_source": SOURCE_FIELDS, "sort": ["_doc"]
    })
    return [(hid, score, src, "same_chapter") for hid, score, src in hits]


def candidates_by_concepts(concepts_english, concepts_arabic):
    results = []
    seen = set()
    if concepts_english:
        query_text = " ".join(concepts_english)
        hits = es_search({
            "size": 30,
            "query": {"multi_match": {"query": query_text,
                       "fields": ["semantic_english_hint_source^2", "semantic_matn_source"],
                       "type": "cross_fields", "minimum_should_match": "30%"}},
            "_source": SOURCE_FIELDS
        })
        for hid, score, src in hits:
            if hid not in seen:
                seen.add(hid)
                results.append((hid, score, src, "concept_english"))
    if concepts_arabic:
        query_text = " ".join(concepts_arabic)
        hits = es_search({
            "size": 20,
            "query": {"match": {"semantic_matn_source": {
                        "query": query_text, "minimum_should_match": "50%"}}},
            "_source": SOURCE_FIELDS
        })
        for hid, score, src in hits:
            if hid not in seen:
                seen.add(hid)
                results.append((hid, score, src, "concept_arabic"))
    return results


# --- Token overlap for auto-accept (opt 6) ---

HONORIFICS = ["عليه السلام", "عليهما السلام", "عليهم السلام",
              "صلّى الله عليه وآله", "صلى الله عليه وآله", "رحمه الله"]
ARABIC_STOP = {"في", "من", "على", "إلى", "عن", "مع", "هذا", "هذه",
               "ذلك", "التي", "الذي", "الذين", "هو", "هي", "هم", "أنا",
               "كان", "كانت", "يكون", "تكون", "أن", "إن", "ما", "لا",
               "لم", "لن", "قد", "كل", "بعض", "أي", "إلا", "حتى", "أو",
               "ثم", "قال", "عنه", "عنها", "عنهم", "بين", "فإن"}


def tokenize_arabic(text):
    if not text:
        return set()
    for h in HONORIFICS:
        text = text.replace(h, " ")
    text = text.replace("<", " ").replace(">", " ")
    tokens = set(text.split())
    return {t for t in tokens if len(t) > 2 and t not in ARABIC_STOP}


def jaccard(set_a, set_b):
    if not set_a or not set_b:
        return 0.0
    return len(set_a & set_b) / len(set_a | set_b)


# --- Cache utilities (opt 2) ---

def canonical_pair(a, b):
    return "||".join(sorted([a, b]))


def load_cache(path):
    p = Path(path)
    if not p.exists():
        return {}
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError) as e:
        print(f"WARNING: Cache file corrupted ({e}), starting fresh", file=sys.stderr)
        # Try to recover from backup
        backup = Path(str(path) + ".bak")
        if backup.exists():
            try:
                cache = json.loads(backup.read_text(encoding="utf-8"))
                print(f"  Recovered {len(cache)} pairs from backup", file=sys.stderr)
                return cache
            except Exception:
                pass
        return {}


def save_cache(cache, path):
    """Atomic write: write to temp file then rename to prevent corruption."""
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    data = json.dumps(cache, ensure_ascii=False)
    # Write to temp file in same directory, then atomic rename
    fd, tmp_path = tempfile.mkstemp(dir=p.parent, suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(data)
        os.rename(tmp_path, str(p))
    except Exception:
        os.unlink(tmp_path)
        raise


def load_processed(path):
    p = Path(path)
    if p.exists():
        return {line for line in p.read_text(encoding="utf-8").strip().splitlines() if line.strip()}
    return set()


def save_processed(processed, path):
    Path(path).write_text("\n".join(sorted(processed)) + "\n", encoding="utf-8")


# --- Single hadith processing ---

def process_one(hadith_id):
    source = es_get(hadith_id)
    if not source:
        return {"source_id": hadith_id, "error": "not found", "candidates": []}

    source_tags = source.get("topic_tags", [])
    source_book = source.get("book", "")
    source_chapter = source.get("chapter", "")

    all_candidates = {}
    method_errors = {}

    retrievers = [
        ("bm25_arabic", lambda: candidates_bm25_arabic(hadith_id)),
        ("bm25_english", lambda: candidates_bm25_english(hadith_id)),
        ("topic_overlap", lambda: candidates_topic_overlap(hadith_id, source_tags)),
    ]

    for method_name, fn in retrievers:
        try:
            for hid, score, src, method in fn():
                if hid not in all_candidates:
                    all_candidates[hid] = (score, src, method)
        except Exception as e:
            method_errors[method_name] = str(e)
            print(f"    WARNING: {method_name} failed for {hadith_id}: {e}", file=sys.stderr)

    candidates = []
    for hid, (score, src, method) in all_candidates.items():
        min_score = MIN_SCORE_BY_METHOD.get(method, 0)
        if score < min_score:
            continue
        candidates.append({
            "id": hid,
            "arabic": src.get("semantic_matn_source", ""),
            "english": src.get("semantic_english_hint_source", ""),
            "tags": src.get("topic_tags", []),
            "book": src.get("book", ""),
            "chapter": src.get("chapter", ""),
            "score": round(score, 2),
            "method": method
        })

    candidates.sort(key=lambda c: c["score"], reverse=True)

    method_counts = {}
    for c in candidates:
        method_counts[c["method"]] = method_counts.get(c["method"], 0) + 1

    return {
        "source_id": hadith_id,
        "source_arabic": source.get("semantic_matn_source", ""),
        "source_english": source.get("semantic_english_hint_source", ""),
        "source_tags": source_tags,
        "source_book": source_book,
        "source_chapter": source_chapter,
        "total_candidates": len(candidates),
        "method_counts": method_counts,
        "method_errors": method_errors if method_errors else None,
        "candidates": candidates
    }


def process_concepts(concepts_str, arabic_terms_str):
    concepts_english = [c.strip() for c in concepts_str.split(",") if c.strip()] if concepts_str else []
    concepts_arabic = arabic_terms_str.split() if arabic_terms_str else []

    if not concepts_english and not concepts_arabic:
        return {"error": "no concepts provided", "candidates": []}

    try:
        results = candidates_by_concepts(concepts_english, concepts_arabic)
    except Exception as e:
        print(f"    WARNING: concept retrieval failed: {e}", file=sys.stderr)
        return {"error": str(e), "concepts_english": concepts_english,
                "concepts_arabic": concepts_arabic, "candidates": []}

    candidates = []
    for hid, score, src, method in results:
        candidates.append({
            "id": hid,
            "arabic": src.get("semantic_matn_source", ""),
            "english": src.get("semantic_english_hint_source", ""),
            "tags": src.get("topic_tags", []),
            "book": src.get("book", ""),
            "chapter": src.get("chapter", ""),
            "score": round(score, 2),
            "method": method
        })
    candidates.sort(key=lambda c: c["score"], reverse=True)
    return {
        "concepts_english": concepts_english,
        "concepts_arabic": concepts_arabic,
        "total_candidates": len(candidates),
        "candidates": candidates
    }


# --- Optimized pipeline modes ---

def scroll_all_ids(limit=None):
    """Get all hadith IDs via scroll API."""
    ids = []
    resp = http_json(f"{ES_URL}/{ES_INDEX}/_search?scroll=5m", {
        "size": 1000, "query": {"exists": {"field": "semantic_matn_source"}},
        "_source": [], "sort": ["_doc"]
    })
    scroll_id = resp.get("_scroll_id")
    try:
        while True:
            hits = resp.get("hits", {}).get("hits", [])
            if not hits:
                break
            ids.extend(h["_id"] for h in hits)
            if limit and len(ids) >= limit:
                ids = ids[:limit]
                break
            scroll_id = resp.get("_scroll_id")
            resp = http_json(f"{ES_URL}/_search/scroll", {"scroll": "5m", "scroll_id": scroll_id})
    finally:
        if scroll_id:
            http_delete(f"{ES_URL}/_search/scroll", {"scroll_id": scroll_id})
    return ids


def precompute_mode(args):
    """Pre-compute candidates for all hadith → JSONL with top 30 candidates."""
    limit = args.precompute if args.precompute > 0 else None
    ids = scroll_all_ids(limit)
    print(f"Pre-computing candidates for {len(ids)} hadith...")

    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)

    # Resume: skip IDs already in the JSONL
    done_ids = set()
    if out.exists():
        with open(out, "r", encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    try:
                        done_ids.add(json.loads(line)["source_id"])
                    except (json.JSONDecodeError, KeyError):
                        pass
    if done_ids:
        print(f"  Resuming: {len(done_ids)} already done, {len(ids) - len(done_ids)} remaining")

    t0 = time.time()
    errors = 0
    with open(out, "a" if done_ids else "w", encoding="utf-8") as f:
        for i, hid in enumerate(ids):
            if hid in done_ids:
                continue
            try:
                result = process_one(hid)
            except Exception as e:
                print(f"  ERROR: {hid}: {e}", file=sys.stderr)
                errors += 1
                continue
            # Pre-filter: top 30 (opt 4)
            candidates = result.get("candidates", [])[:PREFILTER_TOP]
            entry = {
                "source_id": hid,
                "source_arabic": result.get("source_arabic", ""),
                "source_english": result.get("source_english", ""),
                "source_tags": result.get("source_tags", []),
                "candidates": candidates,
                "total_original": result.get("total_candidates", 0),
                "method_counts": result.get("method_counts", {}),
            }
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")

            if (i + 1) % 50 == 0:
                elapsed = time.time() - t0
                done = i + 1 - len(done_ids)
                remaining = len(ids) - i - 1
                rate = done / max(elapsed, 1)
                eta = remaining / max(rate, 1)
                print(f"  {done}/{len(ids) - len(done_ids)} ({rate:.0f}/s, ETA {eta:.0f}s) "
                      f"[{out.stat().st_size / 1e6:.1f}MB]")

    elapsed = time.time() - t0
    print(f"\nDone: {len(ids)} hadith in {elapsed:.0f}s, {errors} errors")
    print(f"Saved to {out} ({out.stat().st_size / 1e6:.1f}MB)")


def build_cache_mode(args):
    """Auto-accept obvious wording matches >80% token overlap (opt 6)."""
    precomputed_path = args.build_cache
    cache_path = args.cache

    cache = load_cache(cache_path)
    print(f"Existing cache: {len(cache)} pairs")

    # Load precomputed entries (tolerate corrupted lines)
    entries = []
    bad_lines = 0
    with open(precomputed_path, "r", encoding="utf-8") as f:
        for line_num, line in enumerate(f, 1):
            if line.strip():
                try:
                    entries.append(json.loads(line))
                except json.JSONDecodeError:
                    bad_lines += 1
    if bad_lines:
        print(f"WARNING: Skipped {bad_lines} corrupted lines in JSONL", file=sys.stderr)

    # Build token sets
    token_sets = {}
    for entry in entries:
        token_sets[entry["source_id"]] = tokenize_arabic(entry.get("source_arabic", ""))

    # Auto-accept
    auto_accepted = 0
    auto_rejected = 0
    for entry in entries:
        src_id = entry["source_id"]
        src_tokens = token_sets.get(src_id, set())
        if not src_tokens:
            continue

        for cand in entry.get("candidates", []):
            cand_id = cand["id"]
            pair_key = canonical_pair(src_id, cand_id)
            if pair_key in cache:
                continue

            cand_tokens = tokenize_arabic(cand.get("arabic", ""))
            sim = jaccard(src_tokens, cand_tokens)

            if sim >= AUTO_ACCEPT_THRESHOLD:
                cache[pair_key] = {
                    "verdict": "similar", "match_type": "wording",
                    "reason": f"Auto: {sim:.0%} token overlap", "source": "auto"
                }
                auto_accepted += 1

    save_cache(cache, cache_path)
    print(f"Auto-accepted: {auto_accepted} pairs (>={AUTO_ACCEPT_THRESHOLD:.0%} overlap)")
    print(f"Cache now: {len(cache)} pairs")


def prepare_batches_mode(args):
    """Create batch files for agents, skipping cached pairs (opt 2, 3, 5)."""
    precomputed_path = args.precomputed
    cache_path = args.cache
    processed_path = args.processed
    batch_size = args.batch_size
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    cache = load_cache(cache_path)
    processed = load_processed(processed_path)

    # Load precomputed (tolerate corrupted lines)
    entries = []
    with open(precomputed_path, "r", encoding="utf-8") as f:
        for line in f:
            if line.strip():
                try:
                    entries.append(json.loads(line))
                except json.JSONDecodeError:
                    pass

    # Sort by connectivity: hadith appearing most as candidates go first (opt 2)
    candidate_counts = {}
    for entry in entries:
        for cand in entry.get("candidates", []):
            candidate_counts[cand["id"]] = candidate_counts.get(cand["id"], 0) + 1
    entries.sort(key=lambda e: candidate_counts.get(e["source_id"], 0), reverse=True)

    # Filter to unprocessed (opt 5)
    unprocessed = [e for e in entries if e["source_id"] not in processed]
    print(f"Total: {len(entries)}, Processed: {len(entries) - len(unprocessed)}, "
          f"Remaining: {len(unprocessed)}")

    # Create batches
    batch_num = 0
    total_uncached = 0
    total_pre_cached = 0

    for i in range(0, len(unprocessed), batch_size):
        batch = unprocessed[i:i + batch_size]
        batch_entries = []

        for entry in batch:
            src_id = entry["source_id"]
            pre_cached = []
            uncached = []

            # Truncate candidates to top-N per method (matches MAX_PER_METHOD)
            # and apply per-method score thresholds
            candidates = entry.get("candidates", [])
            per_method_count = {}
            filtered_candidates = []
            for cand in candidates:
                method = cand.get("method", "unknown")
                score = cand.get("score", 0)
                min_score = MIN_SCORE_BY_METHOD.get(method, 0)
                if score < min_score:
                    continue
                per_method_count[method] = per_method_count.get(method, 0) + 1
                if per_method_count[method] <= MAX_PER_METHOD:
                    filtered_candidates.append(cand)

            for cand in filtered_candidates:
                cand_id = cand["id"]
                pair_key = canonical_pair(src_id, cand_id)

                if pair_key in cache:
                    if cache[pair_key]["verdict"] == "similar":
                        pre_cached.append({
                            "id": cand_id,
                            "match_type": cache[pair_key].get("match_type", ""),
                            "reason": cache[pair_key].get("reason", "")
                        })
                else:
                    uncached.append({
                        "id": cand_id,
                        "arabic": cand.get("arabic", "")[:600],
                        "english": cand.get("english", "")[:300],
                        "tags": cand.get("tags", []),
                        "score": cand.get("score", 0),
                    })

            total_uncached += len(uncached)
            total_pre_cached += len(pre_cached)

            # Skip entries with nothing to judge (all cached or no candidates)
            if not uncached:
                continue

            batch_entries.append({
                "source_id": src_id,
                "source_arabic": entry.get("source_arabic", "")[:800],
                "source_english": entry.get("source_english", "")[:400],
                "source_tags": entry.get("source_tags", []),
                "pre_cached_similar": pre_cached,
                "uncached_candidates": uncached,
                "stats": {
                    "original": entry.get("total_original", 0),
                    "pre_cached": len(pre_cached),
                    "uncached": len(uncached)
                }
            })

        if not batch_entries:
            continue

        batch_num += 1
        batch_file = {
            "batch_id": batch_num,
            "total_hadith": len(batch_entries),
            "entries": batch_entries
        }
        batch_path = output_dir / f"batch_{batch_num:03d}.json"
        batch_path.write_text(
            json.dumps(batch_file, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"Created {batch_num} batches in {output_dir}/")
    print(f"Pre-cached: {total_pre_cached} pairs (skipped), Uncached: {total_uncached} (need judgment)")


def merge_mode(args):
    """Merge agent results into cache and mark processed (opt 5).
    Uses file locking to prevent concurrent merge corruption.
    """
    cache_path = args.cache
    processed_path = args.processed

    # Lock the cache file to prevent concurrent merges
    lock_path = str(cache_path) + ".lock"
    lock_fd = open(lock_path, "w")
    try:
        fcntl.flock(lock_fd, fcntl.LOCK_EX)
    except Exception:
        print("WARNING: Could not acquire cache lock, proceeding without locking",
              file=sys.stderr)

    try:
        cache = load_cache(cache_path)
        processed = load_processed(processed_path)
        new_pairs = 0
        skipped_verdicts = 0
        skipped_files = 0

        for results_file in args.merge:
            try:
                data = json.loads(Path(results_file).read_text(encoding="utf-8"))
            except (json.JSONDecodeError, UnicodeDecodeError) as e:
                print(f"WARNING: Skipping malformed file {results_file}: {e}",
                      file=sys.stderr)
                skipped_files += 1
                continue

            results = data if isinstance(data, list) else data.get("results", [data])
            if isinstance(data, dict) and "source_id" in data and "judgments" in data:
                results = [data]

            for result in results:
                # Support "pair" format: {"pair": "src||cand", "verdict": "..."}
                pair_key_raw = result.get("pair", "")
                if pair_key_raw and "||" in pair_key_raw:
                    parts = pair_key_raw.split("||", 1)
                    src_id = parts[0]
                    cand_id = parts[1]
                    if not cand_id:
                        continue
                    processed.add(src_id)
                    raw_verdict = result.get("verdict", "")
                    if raw_verdict in ("similar", "wording_similar", "conceptually_similar"):
                        verdict = raw_verdict if raw_verdict != "similar" else "similar"
                        match_type = "wording" if raw_verdict == "wording_similar" else ("conceptual" if raw_verdict == "conceptually_similar" else "")
                    elif raw_verdict == "rejected":
                        verdict = "rejected"
                        match_type = ""
                    else:
                        skipped_verdicts += 1
                        continue
                    pk = canonical_pair(src_id, cand_id)
                    if pk not in cache:
                        cache[pk] = {
                            "verdict": verdict,
                            "reason": result.get("reason", ""),
                            "match_type": match_type,
                            "confidence": result.get("confidence", 0),
                            "source": "agent"
                        }
                        new_pairs += 1
                    continue

                src_id = result.get("source_id", "")
                if not src_id:
                    continue
                processed.add(src_id)

                # Support both old format (judgments/id) and new agent format (candidate_id)
                judgments = result.get("judgments", [])
                if not judgments and "verdict" in result:
                    # Single-result format from agents
                    judgments = [result]
                if not judgments:
                    continue

                for j in judgments:
                    cand_id = j.get("id", "") or j.get("candidate_id", "")
                    if not cand_id:
                        continue
                    raw_verdict = j.get("verdict", "")
                    # Normalize verdict: SIMILAR_WORDING/SIMILAR_CONCEPTUAL -> similar, NOT_SIMILAR -> rejected
                    if raw_verdict.startswith("SIMILAR"):
                        verdict = "similar"
                        match_type = "wording" if "WORDING" in raw_verdict else "conceptual"
                    elif raw_verdict in ("similar", "rejected"):
                        verdict = raw_verdict
                        match_type = j.get("match_type", "")
                    else:
                        skipped_verdicts += 1
                        continue
                    pair_key = canonical_pair(src_id, cand_id)
                    if pair_key not in cache:
                        cache[pair_key] = {
                            "verdict": verdict,
                            "reason": j.get("reasoning", j.get("reason", "")),
                            "match_type": match_type,
                            "confidence": j.get("confidence", 0),
                            "source": "agent"
                        }
                        new_pairs += 1

        # Backup existing cache before overwriting
        if Path(cache_path).exists():
            import shutil
            shutil.copy2(cache_path, str(cache_path) + ".bak")

        save_cache(cache, cache_path)
        save_processed(processed, processed_path)
        print(f"Merged: {new_pairs} new pairs, {skipped_verdicts} invalid verdicts, "
              f"{skipped_files} bad files")
        print(f"Cache: {len(cache)} pairs, Processed: {len(processed)} hadith")
    finally:
        try:
            fcntl.flock(lock_fd, fcntl.LOCK_UN)
        except Exception:
            pass
        lock_fd.close()


def stats_mode(args):
    """Show cache and processing stats."""
    cache = load_cache(args.cache)
    processed = load_processed(args.processed)

    similar = sum(1 for v in cache.values() if v.get("verdict") == "similar")
    rejected = sum(1 for v in cache.values() if v.get("verdict") == "rejected")
    auto = sum(1 for v in cache.values() if v.get("source") == "auto")
    agent = sum(1 for v in cache.values() if v.get("source") == "agent")
    wording = sum(1 for v in cache.values() if v.get("match_type") == "wording")
    conceptual = sum(1 for v in cache.values() if v.get("match_type") == "conceptual")

    print(f"=== Pipeline Stats ===")
    print(f"Processed hadith: {len(processed)}")
    print(f"Cached pairs: {len(cache)}")
    print(f"  Similar: {similar} (wording: {wording}, conceptual: {conceptual})")
    print(f"  Rejected: {rejected}")
    print(f"  Source: auto={auto}, agent={agent}")


# --- CLI ---

def main():
    parser = argparse.ArgumentParser(description="ES retrieval for similar hadith")
    parser.add_argument("--es-url", default=None)

    # Original modes
    parser.add_argument("--id", help="Single hadith by ID")
    parser.add_argument("--sample", type=int, help="N random hadith")
    parser.add_argument("--tag", help="Hadith with this topic tag")
    parser.add_argument("--per-tag", type=int, default=5)
    parser.add_argument("--concepts", help="Comma-separated English concepts")
    parser.add_argument("--arabic-terms", help="Space-separated Arabic terms")
    parser.add_argument("--output", default=None,
                        help="Output file (default: tmp/precomputed.jsonl for --precompute)")

    # Optimized pipeline modes
    parser.add_argument("--precompute", type=int, help="Pre-compute N hadith → JSONL")
    parser.add_argument("--build-cache", metavar="JSONL", help="Auto-accept obvious pairs")
    parser.add_argument("--prepare-batches", action="store_true", help="Create agent batch files")
    parser.add_argument("--precomputed", default="tmp/precomputed.jsonl", help="Precomputed JSONL path")
    parser.add_argument("--merge", nargs="+", metavar="FILE", help="Merge agent results")
    parser.add_argument("--stats", action="store_true", help="Show pipeline stats")

    # Shared config
    parser.add_argument("--cache", default="tmp/pairs_cache.json", help="Pairs cache file")
    parser.add_argument("--processed", default="tmp/processed_hadith.txt", help="Processed hadith list")
    parser.add_argument("--batch-size", type=int, default=5, help="Hadith per batch")
    parser.add_argument("--output-dir", default="tmp/batches", help="Batch output directory")

    args = parser.parse_args()

    global ES_URL
    if args.es_url:
        ES_URL = args.es_url.rstrip("/")

    sys.stdout.reconfigure(line_buffering=True)

    # Pipeline modes
    if args.precompute is not None:
        if not args.output:
            args.output = "tmp/precomputed.jsonl"
        precompute_mode(args)
        return
    if args.build_cache:
        build_cache_mode(args)
        return
    if args.prepare_batches:
        prepare_batches_mode(args)
        return
    if args.merge:
        merge_mode(args)
        return
    if args.stats:
        stats_mode(args)
        return

    # Original modes
    if args.concepts or args.arabic_terms:
        result = process_concepts(args.concepts, args.arabic_terms)
        print(f"Concept search: {len(result.get('candidates', []))} candidates")
        if args.output:
            out = Path(args.output)
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
            print(f"Saved to {out}")
        else:
            print(json.dumps(result, ensure_ascii=False, indent=2))
        return

    hadith_ids = []
    if args.id:
        hadith_ids = [args.id]
    elif args.tag:
        hits = es_search({
            "size": args.per_tag,
            "query": {"bool": {"must": [{"term": {"topic_tags": args.tag}},
                                        {"exists": {"field": "semantic_matn_source"}}]}},
            "_source": [], "sort": ["_doc"]
        })
        hadith_ids = [hid for hid, _, _ in hits]
    elif args.sample:
        hits = es_search({
            "size": args.sample,
            "query": {"exists": {"field": "semantic_matn_source"}},
            "_source": [], "sort": ["_doc"]
        })
        hadith_ids = [hid for hid, _, _ in hits]

    if not hadith_ids:
        print("No hadith to process. Use --id, --sample, --tag, --concepts, "
              "--precompute, --build-cache, --prepare-batches, or --merge")
        sys.exit(1)

    print(f"Processing {len(hadith_ids)} hadith...")
    results = []
    for i, hid in enumerate(hadith_ids):
        t0 = time.time()
        result = process_one(hid)
        elapsed = time.time() - t0
        n = result.get("total_candidates", 0)
        methods = result.get("method_counts", {})
        print(f"  [{i+1}/{len(hadith_ids)}] {hid}: {n} candidates ({methods}) [{elapsed:.1f}s]")
        results.append(result)

    if args.output:
        out = Path(args.output)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\nSaved to {out} ({out.stat().st_size / 1e6:.1f}MB)")
    else:
        print(json.dumps(results, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
