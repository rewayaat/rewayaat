#!/usr/bin/env python3
import json
import math
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path


ES_BASE_URL = os.environ.get("ELASTICSEARCH_URL", "http://localhost:9200").rstrip("/")
HADITH_INDEX = os.environ.get("REWAYAAT_INDEX", "rewayaat_thaqalayn")
QURAN_INDEX = os.environ.get("QURAN_VERSES_INDEX", "rewayaat_quran")
TAFSIR_INDEX = os.environ.get("TAFSIR_INDEX", "rewayaat_tafsir")
LIGHT_INDEX = os.environ.get("QURANIC_LIGHT_INDEX", "rewayaat_quranic_light_filtered")

REQUEST_TIMEOUT = int(os.environ.get("QURANIC_LIGHT_TIMEOUT_SECS", "180"))
MAX_RETRIES = int(os.environ.get("QURANIC_LIGHT_MAX_RETRIES", "4"))
RETRY_DELAY_SECS = float(os.environ.get("QURANIC_LIGHT_RETRY_DELAY_SECS", "2"))
SCROLL_KEEPALIVE = os.environ.get("QURANIC_LIGHT_SCROLL_KEEPALIVE", "5m")
SCROLL_BATCH_SIZE = int(os.environ.get("QURANIC_LIGHT_SCROLL_BATCH_SIZE", "100"))
BULK_BATCH_SIZE = int(os.environ.get("QURANIC_LIGHT_BULK_BATCH_SIZE", "50"))
CHECKPOINT_FILE = Path(os.environ.get("QURANIC_LIGHT_CHECKPOINT_FILE", "/tmp/quranic-light-checkpoint.json"))
TRIAL_EXPORT_FILE = os.environ.get("QURANIC_LIGHT_TRIAL_EXPORT_FILE", "").strip()

REBUILD_INDEX = os.environ.get("QURANIC_LIGHT_REBUILD_INDEX", "false").lower() == "true"
DRY_RUN = os.environ.get("QURANIC_LIGHT_DRY_RUN", "false").lower() == "true"
FORCE_REPROCESS = os.environ.get("QURANIC_LIGHT_FORCE_REPROCESS", "false").lower() == "true"

START_AT_ID = os.environ.get("QURANIC_LIGHT_START_AT_ID", "").strip()
LIMIT = int(os.environ.get("QURANIC_LIGHT_LIMIT", "0"))

TAG_QUERY_SIZE = int(os.environ.get("QURANIC_LIGHT_TAG_QUERY_SIZE", "60"))
ARABIC_QUERY_SIZE = int(os.environ.get("QURANIC_LIGHT_ARABIC_QUERY_SIZE", "25"))
VERSE_TERMS_QUERY_SIZE = int(os.environ.get("QURANIC_LIGHT_VERSE_TERMS_QUERY_SIZE", "30"))
TAFSIR_QUERY_SIZE = int(os.environ.get("QURANIC_LIGHT_TAFSIR_QUERY_SIZE", "40"))
TAFSIR_FETCH_PER_VERSE = int(os.environ.get("QURANIC_LIGHT_TAFSIR_FETCH_PER_VERSE", "4"))
MAX_CANDIDATES_PER_HADITH = int(os.environ.get("QURANIC_LIGHT_MAX_CANDIDATES", "20"))
MAX_SNIPPETS_PER_VERSE = int(os.environ.get("QURANIC_LIGHT_MAX_SNIPPETS_PER_VERSE", "3"))
SUMMARY_TEXT_MAX_CHARS = int(os.environ.get("QURANIC_LIGHT_SUMMARY_TEXT_MAX_CHARS", "600"))
MIN_CANDIDATES_PER_HADITH = int(os.environ.get("QURANIC_LIGHT_MIN_CANDIDATES", "3"))
MIN_COMBINED_SCORE = float(os.environ.get("QURANIC_LIGHT_MIN_COMBINED_SCORE", "3.0"))
MIN_SCORE_RATIO = float(os.environ.get("QURANIC_LIGHT_MIN_SCORE_RATIO", "0.45"))

TAG_WEIGHT = float(os.environ.get("QURANIC_LIGHT_TAG_WEIGHT", "3.5"))
ARABIC_WEIGHT = float(os.environ.get("QURANIC_LIGHT_ARABIC_WEIGHT", "5.0"))
VERSE_TERMS_WEIGHT = float(os.environ.get("QURANIC_LIGHT_VERSE_TERMS_WEIGHT", "2.0"))
TAFSIR_WEIGHT = float(os.environ.get("QURANIC_LIGHT_TAFSIR_WEIGHT", "3.0"))
TAG_NORM_POWER = float(os.environ.get("QURANIC_LIGHT_TAG_NORM_POWER", "1.35"))
ARABIC_SCORE_SATURATION = float(os.environ.get("QURANIC_LIGHT_ARABIC_SCORE_SATURATION", "12.0"))
VERSE_TERMS_SCORE_SATURATION = float(os.environ.get("QURANIC_LIGHT_VERSE_TERMS_SCORE_SATURATION", "24.0"))
TAFSIR_SCORE_SATURATION = float(os.environ.get("QURANIC_LIGHT_TAFSIR_SCORE_SATURATION", "60.0"))
TRIPLE_CONVERGENCE_MULTIPLIER = float(os.environ.get("QURANIC_LIGHT_TRIPLE_CONVERGENCE_MULTIPLIER", "1.5"))
VERSE_TAFSIR_CONVERGENCE_BONUS = float(os.environ.get("QURANIC_LIGHT_VERSE_TAFSIR_CONVERGENCE_BONUS", "1.35"))
TAG_ONLY_PENALTY = float(os.environ.get("QURANIC_LIGHT_TAG_ONLY_PENALTY", "0.30"))
TAG_ONLY_MIN_SHARED = int(os.environ.get("QURANIC_LIGHT_TAG_ONLY_MIN_SHARED", "3"))
VERSE_TERMS_TAFSIR_QUERY_SIZE = int(os.environ.get("QURANIC_LIGHT_VERSE_TERMS_TAFSIR_QUERY_SIZE", "20"))
VERSE_TERMS_TAFSIR_WEIGHT = float(os.environ.get("QURANIC_LIGHT_VERSE_TERMS_TAFSIR_WEIGHT", "2.5"))
VERSE_TERMS_TAFSIR_SATURATION = float(os.environ.get("QURANIC_LIGHT_VERSE_TERMS_TAFSIR_SATURATION", "30.0"))
DUPLICATE_VERSE_GAP = int(os.environ.get("QURANIC_LIGHT_DUPLICATE_VERSE_GAP", "2"))

WORD_RE = re.compile(r"[A-Za-z']+")
ARABIC_TOKEN_RE = re.compile(r"[\u0621-\u064A]{2,}")
ENGLISH_STOPWORDS = {
    "about", "after", "again", "against", "all", "also", "among", "and", "are", "because",
    "been", "before", "being", "between", "both", "but", "came", "cannot", "could", "decrees",
    "did", "does", "during", "each", "for", "from", "had", "has", "have", "him", "his", "how",
    "into", "its", "just", "made", "make", "more", "most", "not", "only", "other", "our",
    "out", "over", "said", "say", "says", "should", "such", "than", "that", "the", "their",
    "them", "then", "there", "these", "they", "this", "those", "through", "unto", "upon", "was",
    "were", "what", "when", "which", "while", "who", "will", "with", "would", "your", "holy",
}
LOW_SIGNAL_HINT_TERMS = {
    "heard", "saying", "said", "once", "following", "narrated", "narrates", "narration",
    "allah", "imam", "abu", "ibn", "alayhim", "salam", "ridā", "rida", "muhammad", "ali",
    "husayn", "husain", "hasan", "hassan", "jafar", "sadiq", "baqir", "ibrahim", "ahmad",
    "umar", "umar", "umayr", "yahya", "abdullah", "abd", "talib", "prophet",
}
def http_json(method, url, payload=None, headers=None, timeout=REQUEST_TIMEOUT):
    body = None
    req_headers = {"Content-Type": "application/json"}
    if headers:
        req_headers.update(headers)
    if payload is not None:
        body = json.dumps(payload).encode("utf-8")

    last_exc = None
    for attempt in range(MAX_RETRIES):
        try:
            req = urllib.request.Request(url, data=body, headers=req_headers, method=method)
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                raw = resp.read().decode("utf-8")
                return resp.getcode(), json.loads(raw) if raw else {}
        except (urllib.error.URLError, ConnectionError) as exc:
            last_exc = exc
            if attempt == MAX_RETRIES - 1:
                raise
            time.sleep(RETRY_DELAY_SECS * (attempt + 1))
    raise last_exc


def http_ndjson(url, lines, timeout=REQUEST_TIMEOUT):
    body = ("\n".join(lines) + "\n").encode("utf-8")
    last_exc = None
    for attempt in range(MAX_RETRIES):
        try:
            req = urllib.request.Request(
                url,
                data=body,
                headers={"Content-Type": "application/x-ndjson"},
                method="POST",
            )
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                raw = resp.read().decode("utf-8")
                return resp.getcode(), json.loads(raw) if raw else {}
        except (urllib.error.URLError, ConnectionError) as exc:
            last_exc = exc
            if attempt == MAX_RETRIES - 1:
                raise
            time.sleep(RETRY_DELAY_SECS * (attempt + 1))
    raise last_exc


def es_request(method, path, payload=None):
    return http_json(method, f"{ES_BASE_URL}{path}", payload=payload)


def read_checkpoint():
    if not CHECKPOINT_FILE.exists():
        return {
            "processed_ids": [],
            "seen": 0,
            "indexed": 0,
            "started_at": now_iso(),
            "last_processed_id": "",
        }
    data = json.loads(CHECKPOINT_FILE.read_text(encoding="utf-8"))
    data.setdefault("processed_ids", [])
    data.setdefault("seen", 0)
    data.setdefault("indexed", 0)
    data.setdefault("started_at", now_iso())
    data.setdefault("last_processed_id", "")
    return data


def write_checkpoint(state):
    CHECKPOINT_FILE.write_text(json.dumps(state, indent=2, ensure_ascii=False), encoding="utf-8")


def now_iso():
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def ensure_index():
    if DRY_RUN:
        return
    if REBUILD_INDEX:
        try:
            es_request("DELETE", f"/{LIGHT_INDEX}")
            print(f"Deleted existing index {LIGHT_INDEX}")
        except urllib.error.HTTPError as exc:
            if exc.code != 404:
                raise

    try:
        es_request("HEAD", f"/{LIGHT_INDEX}")
        print(f"Index {LIGHT_INDEX} already exists")
        return
    except urllib.error.HTTPError as exc:
        if exc.code != 404:
            raise

    mapping = {
        "mappings": {
            "properties": {
                "hadith_id": {"type": "keyword"},
                "hadith_book": {"type": "keyword"},
                "hadith_number": {"type": "keyword"},
                "hadith_chapter": {"type": "text"},
                "hadith_section": {"type": "text"},
                "hadith_topic_tags": {"type": "keyword"},
                "hadith_significant_terms": {"type": "text"},
                "hadith_semantic_matn_source": {"type": "text"},
                "hadith_semantic_english_hint_source": {"type": "text"},
                "hadith_english": {"type": "text"},
                "candidate_count": {"type": "integer"},
                "top_verse_keys": {"type": "keyword"},
                "generated_at": {"type": "date"},
                "candidates": {
                    "type": "nested",
                    "properties": {
                        "rank": {"type": "integer"},
                        "verse_key": {"type": "keyword"},
                        "surah_number": {"type": "integer"},
                        "ayah_number": {"type": "integer"},
                        "surah_name_english": {"type": "keyword"},
                        "text_english": {"type": "text"},
                        "text_arabic": {"type": "text"},
                        "combined_score": {"type": "float"},
                        "signal_count": {"type": "integer"},
                        "shared_tags": {"type": "keyword"},
                        "signal_scores": {
                            "properties": {
                                "tag_overlap": {"type": "float"},
                                "arabic_citation": {"type": "float"},
                                "verse_terms": {"type": "float"},
                                "verse_terms_tafsir": {"type": "float"},
                                "tafsir_match": {"type": "float"},
                            }
                        },
                        "raw_scores": {
                            "properties": {
                                "tag_overlap_count": {"type": "integer"},
                                "arabic_citation_score": {"type": "float"},
                                "verse_terms_score": {"type": "float"},
                                "verse_terms_tafsir_score": {"type": "float"},
                                "tafsir_match_score": {"type": "float"},
                            }
                        },
                        "tafsir_snippets": {
                            "type": "nested",
                            "properties": {
                                "tafsir_slug": {"type": "keyword"},
                                "tafsir_name": {"type": "text"},
                                "commentary_score": {"type": "float"},
                                "commentary_text": {"type": "text"},
                                "source_url": {"type": "keyword"},
                                "section_title": {"type": "text"},
                            }
                        },
                    }
                },
            }
        }
    }
    es_request("PUT", f"/{LIGHT_INDEX}", mapping)
    print(f"Created index {LIGHT_INDEX}")


def start_scroll():
    payload = {
        "size": SCROLL_BATCH_SIZE,
        "sort": ["_doc"],
        "_source": [
            "book",
            "number",
            "chapter",
            "section",
            "english",
            "semantic_english_hint_source",
            "topic_tags",
            "semantic_significant_terms_source",
            "semantic_matn_source",
        ],
        "query": {"match_all": {}},
    }
    _, body = es_request("POST", f"/{HADITH_INDEX}/_search?scroll={SCROLL_KEEPALIVE}", payload)
    return body


def continue_scroll(scroll_id):
    _, body = es_request("POST", "/_search/scroll", {"scroll": SCROLL_KEEPALIVE, "scroll_id": scroll_id})
    return body


def clear_scroll(scroll_id):
    if not scroll_id:
        return
    try:
        es_request("DELETE", "/_search/scroll", {"scroll_id": [scroll_id]})
    except Exception:
        pass


def bulk_index(documents):
    if not documents or DRY_RUN:
        return 0
    lines = []
    for doc in documents:
        lines.append(json.dumps({"index": {"_index": LIGHT_INDEX, "_id": doc["hadith_id"]}}, ensure_ascii=False))
        lines.append(json.dumps(doc, ensure_ascii=False))
    _, body = http_ndjson(f"{ES_BASE_URL}/_bulk", lines)
    if body.get("errors"):
        raise RuntimeError(f"Bulk indexing failed: {json.dumps(body)[:1200]}")
    return len(documents)


def write_trial_export(documents):
    if not TRIAL_EXPORT_FILE:
        return
    export_path = Path(TRIAL_EXPORT_FILE)
    export_path.parent.mkdir(parents=True, exist_ok=True)
    export_path.write_text(json.dumps(documents, indent=2, ensure_ascii=False), encoding="utf-8")


def fetch_verses_by_keys(verse_keys):
    if not verse_keys:
        return {}
    payload = {
        "size": len(verse_keys),
        "_source": [
            "surah_number",
            "ayah_number",
            "surah_name_english",
            "text_english",
            "text_arabic",
            "topic_tags",
        ],
        "query": {"ids": {"values": sorted(set(verse_keys))}},
    }
    _, body = es_request("POST", f"/{QURAN_INDEX}/_search", payload)
    result = {}
    for hit in body.get("hits", {}).get("hits", []):
        result[hit["_id"]] = hit.get("_source", {})
    return result


def query_tag_overlap(tags):
    if not tags:
        return []
    unique_tags = sorted(set(tags))
    payload = {
        "size": TAG_QUERY_SIZE,
        "_source": ["topic_tags", "surah_number", "ayah_number", "surah_name_english", "text_english", "text_arabic"],
        "query": {
            "function_score": {
                "query": {"terms": {"topic_tags": unique_tags}},
                "functions": [
                    {"filter": {"term": {"topic_tags": tag}}, "weight": 1.0}
                    for tag in unique_tags
                ],
                "score_mode": "sum",
                "boost_mode": "replace",
            }
        },
    }
    _, body = es_request("POST", f"/{QURAN_INDEX}/_search", payload)
    return body.get("hits", {}).get("hits", [])


def query_arabic_citation(arabic_matn):
    arabic_matn = clean_text(arabic_matn)
    if len(arabic_matn) < 12:
        return []
    payload = {
        "size": ARABIC_QUERY_SIZE,
        "_source": ["topic_tags", "surah_number", "ayah_number", "surah_name_english", "text_english", "text_arabic"],
        "query": {
            "more_like_this": {
                "fields": ["text_arabic"],
                "like": arabic_matn[:2000],
                "min_term_freq": 1,
                "min_doc_freq": 1,
                "max_query_terms": 20,
                "minimum_should_match": "40%",
            }
        },
    }
    _, body = es_request("POST", f"/{QURAN_INDEX}/_search", payload)
    return body.get("hits", {}).get("hits", [])


def query_verse_terms(significant_terms):
    query_text = clean_text(significant_terms)
    if not query_text:
        return []
    payload = {
        "size": VERSE_TERMS_QUERY_SIZE,
        "_source": ["topic_tags", "surah_number", "ayah_number", "surah_name_english", "text_english", "text_arabic"],
        "query": {
            "bool": {
                "should": [
                    {
                        "match": {
                            "text_english": {
                                "query": query_text,
                                "minimum_should_match": "30%",
                                "boost": 2.0,
                            }
                        }
                    },
                    {
                        "match": {
                            "surah_name_english": {
                                "query": query_text,
                                "boost": 0.8,
                            }
                        }
                    }
                ],
                "minimum_should_match": 1,
            }
        },
    }
    _, body = es_request("POST", f"/{QURAN_INDEX}/_search", payload)
    return body.get("hits", {}).get("hits", [])


def query_verse_terms_via_tafsir(significant_terms):
    """Match significant terms against tafsir commentary, attribute hits back to verses."""
    query_text = clean_text(significant_terms)
    if not query_text:
        return []
    payload = {
        "size": VERSE_TERMS_TAFSIR_QUERY_SIZE,
        "_source": ["verse_key", "verse_keys", "verseKey", "verseKeys", "commentary_text_english", "commentaryTextEnglish"],
        "query": {
            "match": {
                "commentary_text_english": {
                    "query": query_text,
                    "minimum_should_match": "30%",
                }
            }
        },
    }
    _, body = es_request("POST", f"/{TAFSIR_INDEX}/_search", payload)
    return body.get("hits", {}).get("hits", [])


def fetch_tafsir_for_ranked_verses(verse_keys, query_text, hadith_tags, term_weights=None, arabic_matn=""):
    if not verse_keys:
        return {}
    payload = {
        "size": max(len(verse_keys) * TAFSIR_FETCH_PER_VERSE, TAFSIR_QUERY_SIZE),
        "query": {
            "bool": {
                "filter": [
                    {
                        "bool": {
                            "should": [
                                {"terms": {"verse_key": verse_keys}},
                                {"terms": {"verse_keys": verse_keys}},
                                {"terms": {"verseKey.keyword": verse_keys}},
                                {"terms": {"verseKeys.keyword": verse_keys}},
                            ],
                            "minimum_should_match": 1,
                        }
                    }
                ]
            }
        },
    }
    _, body = es_request("POST", f"/{TAFSIR_INDEX}/_search", payload)
    by_verse = {}
    target_keys = set(verse_keys)
    for hit in body.get("hits", {}).get("hits", []):
        source = hit.get("_source", {})
        commentary_text = source.get("commentary_text") or source.get("commentaryText", "")
        commentary_text_english = source.get("commentary_text_english") or source.get("commentaryTextEnglish", "")
        commentary_text_arabic = source.get("commentary_text_arabic") or source.get("commentaryTextArabic", "")
        snippet = {
            "tafsir_slug": source.get("tafsir_slug") or source.get("tafsirSlug", ""),
            "tafsir_name": source.get("tafsir_name") or source.get("tafsirName", ""),
            "commentary_score": score_commentary_relevance(
                commentary_text_english or commentary_text,
                query_text,
                hadith_tags,
                term_weights,
                commentary_text_arabic or commentary_text,
                arabic_matn,
            ),
            "commentary_text": extract_snippet(
                commentary_text,
                query_text,
            ),
            "source_url": source.get("source_url") or source.get("sourceUrl", ""),
            "section_title": source.get("section_title") or source.get("sectionTitle", ""),
        }
        for verse_key in verse_keys_from_tafsir_source(source):
            if verse_key not in target_keys:
                continue
            by_verse.setdefault(verse_key, []).append(snippet)

    for verse_key, snippets in by_verse.items():
        snippets.sort(key=lambda item: item["commentary_score"], reverse=True)
        by_verse[verse_key] = snippets[:MAX_SNIPPETS_PER_VERSE]
    return by_verse


def query_tafsir(query_text, heading, arabic_matn=""):
    should = []
    query_text = clean_text(query_text)
    heading = clean_text(heading)
    arabic_matn = clean_text(arabic_matn)
    if query_text:
        should.append({
            "match": {
                "commentary_text_english": {
                    "query": query_text,
                    "minimum_should_match": "30%",
                    "boost": 2.0,
                }
            }
        })
        should.append({
            "match": {
                "commentary_text": {
                    "query": query_text,
                    "minimum_should_match": "30%",
                    "boost": 0.6,
                }
            }
        })
    if heading:
        should.append({
            "match": {
                "section_title": {
                    "query": heading,
                    "minimum_should_match": "50%",
                    "boost": 1.0,
                }
            }
        })
    if len(arabic_matn) >= 12:
        should.append({
            "more_like_this": {
                "fields": ["commentary_text_arabic", "commentary_text"],
                "like": arabic_matn[:2000],
                "min_term_freq": 1,
                "min_doc_freq": 1,
                "max_query_terms": 20,
                "minimum_should_match": "30%",
                "boost": 1.4,
            }
        })
    if not should:
        return []

    payload = {
        "size": TAFSIR_QUERY_SIZE,
        "_source": True,
        "query": {
            "bool": {
                "should": should,
                "minimum_should_match": 1,
            }
        },
    }
    _, body = es_request("POST", f"/{TAFSIR_INDEX}/_search", payload)
    return body.get("hits", {}).get("hits", [])


def clean_text(text):
    return " ".join((text or "").split())


def contains_latin(text):
    return bool(re.search(r"[A-Za-z]", text or ""))


def short_text(text, max_chars=SUMMARY_TEXT_MAX_CHARS):
    text = clean_text(text)
    if len(text) <= max_chars:
        return text
    return text[: max_chars - 3].rstrip() + "..."


def heading_text(source):
    return clean_text(" ".join([source.get("chapter", ""), source.get("section", "")]))


def useful_heading_text(source):
    heading = heading_text(source)
    lowered = heading.lower()
    noisy_patterns = [
        "assembly",
        "majlis",
        "session",
        "ah.",
        "rabi",
        "jumada",
        "safar",
        "shaban",
        "ramadan",
        "dhu",
    ]
    if any(pattern in lowered for pattern in noisy_patterns):
        return ""
    return heading


def extract_quoted_or_dialogue_text(text):
    text = clean_text(text)
    if not text:
        return ""
    quote_chars = ["“", "\"", "‘", "'"]
    for char in quote_chars:
        if char in text:
            parts = text.split(char)
            if len(parts) >= 2:
                candidate = max(parts[1:], key=len).strip()
                if len(candidate) >= 20:
                    return candidate
    return text


def build_english_query_text(source, max_chars=900):
    parts = []
    english_hint = extract_quoted_or_dialogue_text(source.get("semantic_english_hint_source", ""))
    if english_hint:
        parts.append(english_hint)

    significant_terms = clean_text(source.get("semantic_significant_terms_source", ""))
    if significant_terms and contains_latin(significant_terms):
        parts.append(significant_terms)

    english_sources = [useful_heading_text(source)]
    if not english_hint:
        english_sources.insert(0, extract_quoted_or_dialogue_text(source.get("english", "")))
    topic_tags = source.get("topic_tags") or []
    if topic_tags:
        english_sources.append(" ".join(tag.replace("-", " ").replace("_", " ") for tag in topic_tags))
    seen_terms = set()
    extracted_terms = []
    for text in english_sources:
        for token in WORD_RE.findall(text.lower()):
            if len(token) <= 3 or token in ENGLISH_STOPWORDS:
                continue
            if token not in seen_terms:
                seen_terms.add(token)
                extracted_terms.append(token)
            if len(extracted_terms) >= 24:
                break
        if len(extracted_terms) >= 24:
            break
    if extracted_terms:
        parts.append(" ".join(extracted_terms))

    query = clean_text(" ".join(parts))
    return short_text(query, max_chars)


def build_english_search_terms(source, max_terms=18):
    texts = []
    english_hint = extract_quoted_or_dialogue_text(source.get("semantic_english_hint_source", ""))
    if english_hint:
        texts.append(english_hint)
    else:
        texts.append(extract_quoted_or_dialogue_text(source.get("english", "")))
    texts.append(useful_heading_text(source))
    topic_tags = source.get("topic_tags") or []
    if topic_tags:
        texts.append(" ".join(tag.replace("-", " ").replace("_", " ") for tag in topic_tags))

    seen = set()
    terms = []
    for text in texts:
        for token in WORD_RE.findall((text or "").lower()):
            if len(token) <= 3 or token in ENGLISH_STOPWORDS or token in LOW_SIGNAL_HINT_TERMS:
                continue
            if token in seen:
                continue
            seen.add(token)
            terms.append(token)
            if len(terms) >= max_terms:
                return " ".join(terms)
    return " ".join(terms)


def normalized(value, max_value):
    if value <= 0 or max_value <= 0:
        return 0.0
    return value / max_value


def saturating_normalized(value, saturation):
    if value <= 0 or saturation <= 0:
        return 0.0
    return min(value / saturation, 1.0)


def extract_terms(text):
    return {token.lower() for token in WORD_RE.findall((text or "").lower()) if len(token) > 2}


def extract_weightable_terms(text):
    return [
        token.lower()
        for token in WORD_RE.findall((text or "").lower())
        if len(token) > 2 and token.lower() not in ENGLISH_STOPWORDS and token.lower() not in LOW_SIGNAL_HINT_TERMS
    ]


def compute_term_weights(query_text, candidate_texts):
    query_terms = extract_weightable_terms(query_text)
    if not query_terms:
        return {}
    unique_query_terms = []
    seen = set()
    for term in query_terms:
        if term not in seen:
            seen.add(term)
            unique_query_terms.append(term)

    doc_freq = Counter()
    normalized_docs = []
    for text in candidate_texts:
        terms = set(extract_weightable_terms(text))
        normalized_docs.append(terms)
        for term in unique_query_terms:
            if term in terms:
                doc_freq[term] += 1

    total_docs = max(len(candidate_texts), 1)
    weights = {}
    for index, term in enumerate(unique_query_terms):
        specificity = math.log(1.0 + total_docs / (1.0 + doc_freq.get(term, 0)))
        length_bonus = min(len(term), 10) / 10.0
        position_bonus = max(0.0, 1.25 - (index * 0.04))
        weights[term] = round(specificity * (1.0 + length_bonus) * position_bonus, 6)
    return weights


def extract_arabic_terms(text):
    return {token for token in ARABIC_TOKEN_RE.findall(text or "") if len(token) >= 3}


def arabic_overlap_count(left_text, right_text):
    left = extract_arabic_terms(left_text)
    right = extract_arabic_terms(right_text)
    if not left or not right:
        return 0
    generic = {
        "الله", "القرآن", "قرآن", "قال", "قالوا", "كان", "كانت", "هذا", "هذه",
        "الى", "على", "في", "عن", "اذا", "عند", "من", "هو", "هي", "أبا", "ابي", "أبي",
    }
    return len((left - generic).intersection(right - generic))


def weighted_overlap_score(text, term_weights):
    if not term_weights:
        return 0.0
    terms = set(extract_weightable_terms(text))
    return round(sum(weight for term, weight in term_weights.items() if term in terms), 6)


def cohesion_bonus(text, term_weights, max_window=80):
    if not term_weights:
        return 0.0
    lowered = (text or "").lower()
    positions = []
    for term, weight in term_weights.items():
        idx = lowered.find(term)
        if idx >= 0:
            positions.append((idx, idx + len(term), weight))
    if len(positions) < 2:
        return 0.0
    positions.sort(key=lambda item: item[0])
    best = 0.0
    for start in range(len(positions)):
        span_weight = positions[start][2]
        span_end = positions[start][1]
        for end in range(start + 1, len(positions)):
            window = positions[end][1] - positions[start][0]
            if window > max_window:
                break
            span_weight += positions[end][2]
            span_end = positions[end][1]
            density = span_weight / max(window, 12)
            best = max(best, density * 10.0)
    return round(best, 6)


def score_commentary_relevance(commentary_text_english, query_text, hadith_tags, term_weights=None, commentary_text_arabic="", arabic_matn=""):
    score = weighted_overlap_score(commentary_text_english, term_weights or {})
    score += cohesion_bonus(commentary_text_english, term_weights or {})
    arabic_overlap = arabic_overlap_count(arabic_matn, commentary_text_arabic)
    if arabic_overlap > 0:
        score += min(arabic_overlap, 8) * 1.25
    lower = (commentary_text_english or "").lower()
    for tag in hadith_tags or []:
        tokens = [token for token in re.split(r"[-_]", tag.lower()) if token]
        if tokens and all(token in lower for token in tokens):
            score += 0.75
    word_count = len((commentary_text_english or "").split())
    if word_count > 0 and score > 0:
        score /= math.sqrt(word_count)
    return round(score, 6)


def score_verse_relevance(verse_text, query_text, hadith_tags, term_weights=None):
    score = weighted_overlap_score(verse_text, term_weights or {})
    score += cohesion_bonus(verse_text, term_weights or {})
    lower = (verse_text or "").lower()
    for tag in hadith_tags or []:
        tokens = [token for token in re.split(r"[-_]", tag.lower()) if token]
        matched = sum(1 for token in tokens if token in lower)
        score += matched * 0.6
    return round(score, 6)


VERSE_REF_RE = re.compile(r"\{?\d+:\d+\}?")
SNIPPET_MAX_CHARS = 1500


def extract_snippet(commentary_text, query_text):
    commentary_text = clean_text(commentary_text)
    return commentary_text


def verse_keys_from_tafsir_source(source):
    keys = source.get("verse_keys") or source.get("verseKeys") or []
    if not keys and (source.get("verse_key") or source.get("verseKey")):
        keys = [source.get("verse_key") or source.get("verseKey")]
    return [key for key in keys if key]


def merge_candidates(source, verse_hits_by_signal):
    candidates = {}
    hadith_tags = source.get("topic_tags") or []
    max_possible_overlap = max(len(set(hadith_tags)), 1)

    def ensure_candidate(verse_key):
        if verse_key not in candidates:
            candidates[verse_key] = {
                "verse_key": verse_key,
                "shared_tags": [],
                "tafsir_snippets": [],
                "raw_scores": {
                    "tag_overlap_count": 0,
                    "arabic_citation_score": 0.0,
                    "verse_terms_score": 0.0,
                    "verse_terms_tafsir_score": 0.0,
                    "tafsir_match_score": 0.0,
                },
            }
        return candidates[verse_key]

    for hit in verse_hits_by_signal.get("tag_overlap", []):
        verse_key = hit["_id"]
        candidate = ensure_candidate(verse_key)
        verse_tags = hit.get("_source", {}).get("topic_tags", [])
        shared = sorted(set(verse_tags).intersection(hadith_tags))
        count = len(shared)
        if count > candidate["raw_scores"]["tag_overlap_count"]:
            candidate["raw_scores"]["tag_overlap_count"] = count
            candidate["shared_tags"] = shared

    for hit in verse_hits_by_signal.get("arabic_citation", []):
        verse_key = hit["_id"]
        candidate = ensure_candidate(verse_key)
        candidate["raw_scores"]["arabic_citation_score"] = max(
            candidate["raw_scores"]["arabic_citation_score"], float(hit.get("_score") or 0.0)
        )

    for hit in verse_hits_by_signal.get("verse_terms", []):
        verse_key = hit["_id"]
        candidate = ensure_candidate(verse_key)
        candidate["raw_scores"]["verse_terms_score"] = max(
            candidate["raw_scores"]["verse_terms_score"], float(hit.get("_score") or 0.0)
        )

    for hit in verse_hits_by_signal.get("verse_terms_tafsir", []):
        source = hit.get("_source", {})
        for verse_key in verse_keys_from_tafsir_source(source):
            candidate = ensure_candidate(verse_key)
            candidate["raw_scores"]["verse_terms_tafsir_score"] = max(
                candidate["raw_scores"]["verse_terms_tafsir_score"], float(hit.get("_score") or 0.0)
            )

    if not candidates:
        return []
    return rescore_candidates(list(candidates.values()), source, verse_hits_by_signal, max_possible_overlap)

def rescore_candidates(candidates, source, verse_hits_by_signal, max_possible_overlap):
    if not candidates:
        return []
    arabic_hits_by_key = {hit["_id"]: hit for hit in verse_hits_by_signal.get("arabic_citation", [])}

    for candidate in candidates:
        arabic_hit = arabic_hits_by_key.get(candidate["verse_key"])
        arabic_overlap = arabic_overlap_count(
            source.get("semantic_matn_source", ""),
            arabic_hit.get("_source", {}).get("text_arabic", "") if arabic_hit else "",
        )
        gated_arabic_score = candidate["raw_scores"]["arabic_citation_score"] if arabic_overlap >= 2 else 0.0
        candidate["raw_scores"]["arabic_citation_score"] = round(gated_arabic_score, 6)

    for candidate in candidates:
        raw = candidate["raw_scores"]
        tag_norm = normalized(raw["tag_overlap_count"], max_possible_overlap) ** TAG_NORM_POWER
        arabic_norm = saturating_normalized(raw["arabic_citation_score"], ARABIC_SCORE_SATURATION)
        verse_terms_norm = saturating_normalized(raw["verse_terms_score"], VERSE_TERMS_SCORE_SATURATION)
        verse_terms_tafsir_norm = saturating_normalized(raw["verse_terms_tafsir_score"], VERSE_TERMS_TAFSIR_SATURATION)
        tafsir_norm = saturating_normalized(raw["tafsir_match_score"], TAFSIR_SCORE_SATURATION)
        signal_count = sum(1 for value in [
            raw["tag_overlap_count"] > 0,
            raw["arabic_citation_score"] > 0,
            raw["verse_terms_score"] > 0,
            raw["verse_terms_tafsir_score"] > 0,
            raw["tafsir_match_score"] > 0,
        ] if value)

        combined_score = (
            tag_norm * TAG_WEIGHT
            + arabic_norm * ARABIC_WEIGHT
            + verse_terms_norm * VERSE_TERMS_WEIGHT
            + verse_terms_tafsir_norm * VERSE_TERMS_TAFSIR_WEIGHT
            + tafsir_norm * TAFSIR_WEIGHT
        )

        # Tag-only hard gate: if only tags match with few shared tags, penalize heavily
        non_tag_signals = [raw["arabic_citation_score"], raw["verse_terms_score"],
                           raw["verse_terms_tafsir_score"], raw["tafsir_match_score"]]
        is_tag_only = raw["tag_overlap_count"] > 0 and all(s == 0 for s in non_tag_signals)
        if is_tag_only:
            combined_score *= TAG_ONLY_PENALTY
            if raw["tag_overlap_count"] < TAG_ONLY_MIN_SHARED:
                combined_score = 0.0

        # Convergence bonuses
        if raw["verse_terms_score"] > 0 and raw["tafsir_match_score"] > 0:
            combined_score *= VERSE_TAFSIR_CONVERGENCE_BONUS
        if raw["verse_terms_tafsir_score"] > 0 and raw["tafsir_match_score"] > 0:
            combined_score *= VERSE_TAFSIR_CONVERGENCE_BONUS
        if signal_count >= 3:
            combined_score *= TRIPLE_CONVERGENCE_MULTIPLIER

        candidate["signal_scores"] = {
            "tag_overlap": round(tag_norm * TAG_WEIGHT, 6),
            "arabic_citation": round(arabic_norm * ARABIC_WEIGHT, 6),
            "verse_terms": round(verse_terms_norm * VERSE_TERMS_WEIGHT, 6),
            "verse_terms_tafsir": round(verse_terms_tafsir_norm * VERSE_TERMS_TAFSIR_WEIGHT, 6),
            "tafsir_match": round(tafsir_norm * TAFSIR_WEIGHT, 6),
        }
        candidate["signal_count"] = signal_count
        candidate["combined_score"] = round(combined_score, 6)

    candidates.sort(key=lambda item: (item["combined_score"], item["signal_count"]), reverse=True)
    if not candidates:
        return []
    top_score = candidates[0]["combined_score"]
    filtered = []
    for index, candidate in enumerate(candidates):
        if index < MIN_CANDIDATES_PER_HADITH:
            filtered.append(candidate)
            continue
        if candidate["combined_score"] < MIN_COMBINED_SCORE:
            continue
        if top_score > 0 and candidate["combined_score"] < top_score * MIN_SCORE_RATIO:
            continue
        filtered.append(candidate)
        if len(filtered) >= MAX_CANDIDATES_PER_HADITH:
            break
    filtered = filtered[:MAX_CANDIDATES_PER_HADITH]
    for index, candidate in enumerate(filtered, start=1):
        candidate["rank"] = index
    return filtered


def attach_tafsir_signal(candidates, tafsir_hits, query_text, hadith_tags, term_weights=None, arabic_matn=""):
    candidate_by_key = {candidate["verse_key"]: candidate for candidate in candidates}
    for hit in tafsir_hits:
        source = hit.get("_source", {})
        commentary_text = source.get("commentary_text") or source.get("commentaryText", "")
        commentary_text_english = source.get("commentary_text_english") or source.get("commentaryTextEnglish", "") or commentary_text
        commentary_text_arabic = source.get("commentary_text_arabic") or source.get("commentaryTextArabic", "") or commentary_text
        local_score = score_commentary_relevance(
            commentary_text_english,
            query_text,
            hadith_tags,
            term_weights,
            commentary_text_arabic,
            arabic_matn,
        )
        hit_score = float(hit.get("_score") or 0.0)
        score = max(local_score, hit_score)
        snippet = {
            "tafsir_slug": source.get("tafsir_slug") or source.get("tafsirSlug", ""),
            "tafsir_name": source.get("tafsir_name") or source.get("tafsirName", ""),
            "commentary_score": round(score, 6),
            "commentary_text": extract_snippet(commentary_text, query_text),
            "source_url": source.get("source_url") or source.get("sourceUrl", ""),
            "section_title": source.get("section_title") or source.get("sectionTitle", ""),
        }
        for verse_key in verse_keys_from_tafsir_source(source):
            candidate = candidate_by_key.get(verse_key)
            if candidate is None:
                continue
            candidate["tafsir_snippets"].append(snippet)
            candidate["raw_scores"]["tafsir_match_score"] = max(candidate["raw_scores"]["tafsir_match_score"], score)

    for candidate in candidates:
        candidate["tafsir_snippets"] = sorted(
            candidate["tafsir_snippets"],
            key=lambda item: item["commentary_score"],
            reverse=True,
        )[:MAX_SNIPPETS_PER_VERSE]
        candidate["raw_scores"]["tafsir_match_score"] = round(candidate["raw_scores"]["tafsir_match_score"], 6)


def deduplicate_adjacent_verses(candidates):
    """Collapse consecutive verse sequences (e.g. 2:255, 2:256, 2:257) into the best-scoring one."""
    if not candidates or DUPLICATE_VERSE_GAP <= 0:
        return candidates
    kept = []
    for candidate in candidates:
        vk = candidate.get("verse_key", "")
        parts = vk.split(":")
        if len(parts) != 2:
            kept.append(candidate)
            continue
        try:
            surah, ayah = int(parts[0]), int(parts[1])
        except ValueError:
            kept.append(candidate)
            continue
        is_adjacent = False
        for prev in kept:
            prev_vk = prev.get("verse_key", "")
            prev_parts = prev_vk.split(":")
            if len(prev_parts) != 2:
                continue
            try:
                prev_surah, prev_ayah = int(prev_parts[0]), int(prev_parts[1])
            except ValueError:
                continue
            if surah == prev_surah and abs(ayah - prev_ayah) <= DUPLICATE_VERSE_GAP:
                is_adjacent = True
                break
        if is_adjacent:
            # Already have a nearby verse -- skip if this one is weaker
            continue
        kept.append(candidate)
    return kept


def build_output_document(hit):
    hadith_id = hit["_id"]
    source = hit.get("_source", {})
    english_query_text = build_english_query_text(source)
    english_search_terms = build_english_search_terms(source)

    verse_hits_by_signal = {
        "tag_overlap": query_tag_overlap(source.get("topic_tags") or []),
        "arabic_citation": query_arabic_citation(source.get("semantic_matn_source", "")),
        "verse_terms": query_verse_terms(english_search_terms),
        "verse_terms_tafsir": query_verse_terms_via_tafsir(english_search_terms),
    }
    candidate_texts = []
    for hits in verse_hits_by_signal.values():
        for verse_hit in hits:
            verse_source = verse_hit.get("_source", {})
            candidate_texts.append(verse_source.get("text_english", ""))
    arabic_matn = source.get("semantic_matn_source", "")
    tafsir_hits = query_tafsir(english_query_text, heading_text(source), arabic_matn)
    for tafsir_hit in tafsir_hits:
        tafsir_source = tafsir_hit.get("_source", {})
        candidate_texts.append(
            tafsir_source.get("commentary_text_english")
            or tafsir_source.get("commentaryTextEnglish", "")
            or tafsir_source.get("commentary_text")
            or tafsir_source.get("commentaryText", "")
        )
    term_weights = compute_term_weights(english_search_terms, candidate_texts)
    max_possible_overlap = max(len(set(source.get("topic_tags") or [])), 1)

    candidates = merge_candidates(source, verse_hits_by_signal)
    attach_tafsir_signal(candidates, tafsir_hits, english_query_text, source.get("topic_tags") or [], term_weights, arabic_matn)
    tafsir_by_verse = fetch_tafsir_for_ranked_verses(
        [candidate["verse_key"] for candidate in candidates],
        english_query_text,
        source.get("topic_tags") or [],
        term_weights,
        arabic_matn,
    )
    for candidate in candidates:
        if candidate["tafsir_snippets"]:
            continue
        snippets = tafsir_by_verse.get(candidate["verse_key"], [])
        if not snippets:
            continue
        candidate["tafsir_snippets"] = snippets
        candidate["raw_scores"]["tafsir_match_score"] = round(
            max((snippet["commentary_score"] for snippet in snippets), default=0.0),
            6,
        )
    candidates = rescore_candidates(candidates, source, verse_hits_by_signal, max_possible_overlap)

    verse_lookup = fetch_verses_by_keys([candidate["verse_key"] for candidate in candidates])
    for candidate in candidates:
        verse_source = verse_lookup.get(candidate["verse_key"], {})
        candidate["surah_number"] = verse_source.get("surah_number")
        candidate["ayah_number"] = verse_source.get("ayah_number")
        candidate["surah_name_english"] = verse_source.get("surah_name_english", "")
        candidate["text_english"] = short_text(verse_source.get("text_english", ""))
        candidate["text_arabic"] = short_text(verse_source.get("text_arabic", ""))
        local_verse_score = score_verse_relevance(
            verse_source.get("text_english", ""),
            english_search_terms,
            source.get("topic_tags") or [],
            term_weights,
        )
        candidate["raw_scores"]["verse_terms_score"] = round(
            max(candidate["raw_scores"]["verse_terms_score"], local_verse_score),
            6,
        )
    candidates = rescore_candidates(candidates, source, verse_hits_by_signal, max_possible_overlap)

    # Deduplicate adjacent verses: keep the best from each consecutive run
    candidates = deduplicate_adjacent_verses(candidates)

    if not candidates:
        return None

    return {
        "hadith_id": hadith_id,
        "hadith_book": source.get("book", ""),
        "hadith_number": source.get("number", ""),
        "hadith_chapter": source.get("chapter", ""),
        "hadith_section": source.get("section", ""),
        "hadith_topic_tags": source.get("topic_tags") or [],
        "hadith_significant_terms": source.get("semantic_significant_terms_source", ""),
        "hadith_semantic_matn_source": short_text(source.get("semantic_matn_source", ""), 1200),
        "hadith_semantic_english_hint_source": short_text(source.get("semantic_english_hint_source", ""), 600),
        "hadith_search_terms": english_search_terms,
        "hadith_english": short_text(source.get("english", ""), 1200),
        "candidate_count": len(candidates),
        "top_verse_keys": [candidate["verse_key"] for candidate in candidates],
        "generated_at": now_iso(),
        "candidates": candidates,
    }


def should_skip(hit_id, processed_ids):
    if FORCE_REPROCESS:
        return False
    if START_AT_ID and hit_id < START_AT_ID:
        return True
    return hit_id in processed_ids


def main():
    ensure_index()

    checkpoint = read_checkpoint()
    print(
        "Starting Quranic Light build:",
        f"seen={checkpoint['seen']}",
        f"indexed={checkpoint['indexed']}",
        f"dry_run={DRY_RUN}",
    )

    scroll_id = None
    pending = []
    trial_documents = []
    processed_ids = set(checkpoint["processed_ids"])
    produced = 0

    try:
        page = start_scroll()
        scroll_id = page.get("_scroll_id", "")

        while True:
            hits = page.get("hits", {}).get("hits", [])
            if not hits:
                break

            for hit in hits:
                hadith_id = hit.get("_id", "")
                if not hadith_id or should_skip(hadith_id, processed_ids):
                    continue

                doc = build_output_document(hit)
                processed_ids.add(hadith_id)
                checkpoint["seen"] += 1
                checkpoint["last_processed_id"] = hadith_id

                if doc is None:
                    continue

                pending.append(doc)
                if DRY_RUN and TRIAL_EXPORT_FILE:
                    trial_documents.append(doc)
                produced += 1

                if len(pending) >= BULK_BATCH_SIZE:
                    checkpoint["indexed"] += bulk_index(pending)
                    pending.clear()
                    checkpoint["processed_ids"] = sorted(processed_ids)
                    write_checkpoint(checkpoint)
                    print(
                        f"Progress: seen={checkpoint['seen']} indexed={checkpoint['indexed']} "
                        f"last={checkpoint['last_processed_id']}"
                    )

                if LIMIT and produced >= LIMIT:
                    break

            if LIMIT and produced >= LIMIT:
                break

            page = continue_scroll(scroll_id)
            scroll_id = page.get("_scroll_id", scroll_id)

        if pending:
            checkpoint["indexed"] += bulk_index(pending)

        if DRY_RUN and TRIAL_EXPORT_FILE:
            write_trial_export(trial_documents)

        checkpoint["processed_ids"] = sorted(processed_ids)
        write_checkpoint(checkpoint)
        print(
            f"Completed Quranic Light build: seen={checkpoint['seen']} indexed={checkpoint['indexed']} "
            f"last={checkpoint['last_processed_id']}"
        )
    finally:
        clear_scroll(scroll_id)


if __name__ == "__main__":
    main()
