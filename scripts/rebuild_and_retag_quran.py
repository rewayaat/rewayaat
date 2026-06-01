#!/usr/bin/env python3
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
QURAN_JSON = ROOT / "src/main/resources/static/quran.json"
TAXONOMY_JSON = ROOT / "src/main/resources/static/taxonomy.json"

ES_BASE_URL = os.environ.get("ELASTICSEARCH_URL", "http://localhost:9200").rstrip("/")
INDEX_NAME = os.environ.get("QURAN_VERSES_INDEX", "rewayaat_quran")
AGENT_URL = os.environ.get(
    "QURAN_TAGGING_AI_AGENT_URL",
    "https://rercls6rqu77j57ntpfvsicy.agents.do-ai.run/api/v1/chat/completions",
).strip()
AGENT_KEY = os.environ.get("QURAN_TAGGING_AI_AGENT_KEY", "").strip()
CHECKPOINT_FILE = Path(os.environ.get("QURAN_TAGGING_CHECKPOINT_FILE", "/tmp/quran-tagging-checkpoint.json"))
REQUEST_TIMEOUT = int(os.environ.get("QURAN_TAGGING_TIMEOUT_SECS", "180"))
RETRY_DELAY_SECS = float(os.environ.get("QURAN_TAGGING_RETRY_DELAY_SECS", "3"))
MAX_RETRIES = int(os.environ.get("QURAN_TAGGING_MAX_RETRIES", "4"))
BATCH_SIZE = int(os.environ.get("QURAN_TAGGING_BATCH_SIZE", "50"))
START_SURAH = int(os.environ.get("QURAN_TAGGING_START_SURAH", "1"))
END_SURAH = int(os.environ.get("QURAN_TAGGING_END_SURAH", "114"))
REBUILD_INDEX = os.environ.get("QURAN_REBUILD_INDEX", "true").lower() == "true"
VERIFY_INDEX = os.environ.get("QURAN_VERIFY_INDEX", "true").lower() == "true"
AUDIT_FILE = os.environ.get("QURAN_TAGGING_AUDIT_FILE", "").strip()
MUQATTAAT_VERSE_IDS = {
    "2:1", "3:1", "7:1", "10:1", "11:1", "12:1", "13:1", "14:1", "15:1",
    "19:1", "20:1", "26:1", "27:1", "28:1", "29:1", "30:1", "31:1", "32:1",
    "36:1", "38:1", "40:1", "41:1", "42:1", "42:2", "43:1", "44:1", "45:1",
    "46:1", "50:1", "68:1",
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


def es_request(method, path, payload=None):
    return http_json(method, f"{ES_BASE_URL}{path}", payload=payload)


def es_refresh():
    es_request("POST", f"/{INDEX_NAME}/_refresh")


def load_quran():
    data = json.loads(QURAN_JSON.read_text(encoding="utf-8"))
    verses = []
    ayah_index = 0
    for surah in data["surahs"]:
        for ayah in surah["ayahs"]:
            ayah_index += 1
            hizb_quarter = ayah.get("hizbQuarter") or 0
            hizb_number = ((hizb_quarter - 1) // 4) + 1 if hizb_quarter else None
            verses.append(
                {
                    "_id": f"{surah['number']}:{ayah['numberInSurah']}",
                    "_source": {
                        "surah_number": surah["number"],
                        "ayah_number": ayah["numberInSurah"],
                        "ayah_index": ayah_index,
                        "text_arabic": ayah.get("text", ""),
                        "text_english": ayah.get("text_english", ""),
                        "surah_name_arabic": surah.get("name", ""),
                        "surah_name_english": surah.get("englishName", ""),
                        "surah_name_english_transliteration": surah.get("englishNameTranslation", ""),
                        "juz_number": ayah.get("juz"),
                        "hizb_number": hizb_number,
                        "page_number": ayah.get("page"),
                        "revelation_type": surah.get("revelationType", ""),
                        "topic_tags": [],
                    },
                }
            )
    return verses


def load_primary_taxonomy():
    taxonomy = json.loads(TAXONOMY_JSON.read_text(encoding="utf-8"))
    # The taxonomy file only marks one tag explicitly as secondary and leaves many
    # valid Quran-facing tags untyped. Treat everything except explicit secondary
    # entries as eligible for the Quran tagger.
    primary = [entry for entry in taxonomy if entry.get("type") != "secondary"]
    allowed = {entry["slug"] for entry in primary}
    compact = []
    for entry in primary:
        parts = [
            entry["slug"],
            entry.get("en", ""),
            f"category={entry.get('category', '')}",
        ]
        if entry.get("parent"):
            parts.append(f"parent={entry['parent']}")
        if entry.get("description"):
            parts.append(entry["description"])
        compact.append(" | ".join(part for part in parts if part))
    return primary, allowed, compact


def recreate_index():
    try:
        es_request("DELETE", f"/{INDEX_NAME}")
        print(f"Deleted existing index {INDEX_NAME}")
    except urllib.error.HTTPError as exc:
        if exc.code != 404:
            raise
        print(f"Index {INDEX_NAME} does not exist yet")

    mapping = {
        "mappings": {
            "properties": {
                "surah_number": {"type": "integer"},
                "ayah_number": {"type": "integer"},
                "ayah_index": {"type": "integer"},
                "text_arabic": {"type": "text", "analyzer": "standard", "fielddata": True},
                "text_english": {"type": "text", "analyzer": "standard", "fielddata": True},
                "surah_name_arabic": {"type": "keyword"},
                "surah_name_english": {"type": "keyword"},
                "surah_name_english_transliteration": {"type": "text"},
                "juz_number": {"type": "integer"},
                "hizb_number": {"type": "integer"},
                "page_number": {"type": "integer"},
                "revelation_type": {"type": "keyword"},
                "topic_tags": {"type": "keyword"},
            }
        }
    }
    es_request("PUT", f"/{INDEX_NAME}", mapping)
    print(f"Created index {INDEX_NAME}")


def bulk_index(verses):
    lines = []
    for verse in verses:
        lines.append(json.dumps({"index": {"_index": INDEX_NAME, "_id": verse["_id"]}}, ensure_ascii=False))
        lines.append(json.dumps(verse["_source"], ensure_ascii=False))
    data = ("\n".join(lines) + "\n").encode("utf-8")
    req = urllib.request.Request(
        f"{ES_BASE_URL}/_bulk",
        data=data,
        headers={"Content-Type": "application/x-ndjson"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    if body.get("errors"):
        raise RuntimeError(f"Bulk indexing failed: {json.dumps(body)[:1000]}")
    es_refresh()
    print(f"Indexed {len(verses)} verses into {INDEX_NAME}")


def save_checkpoint(state):
    CHECKPOINT_FILE.write_text(json.dumps(state, indent=2), encoding="utf-8")


def load_checkpoint():
    if CHECKPOINT_FILE.exists():
        data = json.loads(CHECKPOINT_FILE.read_text(encoding="utf-8"))
        data.setdefault("llm_batch_count", 0)
        data.setdefault("llm_single_retry_count", 0)
        data.setdefault("llm_single_retry_verse_ids", [])
        return data
    return {
        "processed_surahs": [],
        "verses_changed": 0,
        "verses_processed": 0,
        "llm_batch_count": 0,
        "llm_single_retry_count": 0,
        "llm_single_retry_verse_ids": [],
    }


def append_audit_record(verse_id, source, tags):
    if not AUDIT_FILE:
        return
    line = json.dumps({
        "id": verse_id,
        "source": source,
        "tags": tags,
    }, ensure_ascii=False)
    with open(AUDIT_FILE, "a", encoding="utf-8") as handle:
        handle.write(line + "\n")


def strip_code_fence(text):
    text = text.strip()
    if text.startswith("```"):
        lines = text.splitlines()
        if lines and lines[0].startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        text = "\n".join(lines).strip()
    return text


def call_agent(batch, compact_taxonomy, allowed_slugs):
    payload = {
        "task": "quran_verse_tagging",
        "instructions": (
            "Your task is to classify Quranic verses (ayat) into controlled taxonomy slugs.\n\n"
            "CLASSIFICATION RULES:\n"
            "1. Assign ONLY PRIMARY tags (conceptual/theological themes). NEVER assign secondary tags (biographical/historical).\n"
            "2. Assign all tags that genuinely apply where the verse substantively addresses that theme.\n"
            "3. Most verses will have 2-4 primary tags; complex narrative verses may have 5-7 tags.\n"
            "4. Every verse must receive at least one primary tag.\n"
            "5. Choose the most specific child tag when the verse clearly supports it; otherwise choose the narrowest defensible parent.\n"
            "6. Do not add both a parent and its child—the system adds ancestors automatically.\n"
            "7. Do not invent slugs—only use tags from the provided taxonomy.\n\n"
            "QURAN-SPECIFIC GUIDELINES:\n"
            "- Prophet tags (musa, ibrahim, isa, nuh, etc.): Use when the verse narrates stories or lessons about specific prophets\n"
            "- Theological tags (tawhid, signs-of-god, creation, unseen): Use for verses about God's attributes, creation, or metaphysical concepts\n"
            "- Ethical tags (taqwa, justice, charity, patience): Use for verses prescribing moral behavior or character traits\n"
            "- Social/legal tags (family, warfare-jihad, halal, equality): Use for verses with social rulings or guidance\n"
            "- Worship tags (prayer, fasting, hajj, zakat): Use for verses about ritual worship\n"
            "- Narrative tags (previous-nations, pharaoh): Use for verses containing historical stories\n\n"
            "AVOID generic umbrella tags unless the verse is explicitly about that umbrella topic:\n"
            "- Do NOT use faith merely because the verse mentions belief\n"
            "- Do NOT use knowledge merely because the verse mentions knowing or learning\n"
            "- Do NOT use quran for verses that are self-referential (about the Quran itself)\n\n"
            "For well-known verses with established Shia tafsir:\n"
            "- 2:255 (Ayat al-Kursi): tawhid, signs-of-god, knowledge, throne-of-god\n"
            "- 5:55 (Wilayah verse): wilayah, imamate, leadership\n"
            "- 33:33 (Tathir verse): ahl-al-bayt, purity, imamate (as contextual tafsir, not direct content)\n"
            "- 112 (Surah Al-Ikhlas): tawhid, oneness-of-god\n\n"
            'RESPONSE FORMAT:\nReturn ONLY valid JSON in this exact format:\n{"documents":[{"id":"verse-id","tags":["slug-1","slug-2"]}]}'
        ),
        "taxonomy": compact_taxonomy,
        "documents": [
            {
                "id": verse["_id"],
                "reference": f"{verse['_source']['surah_name_english']} {verse['_source']['ayah_number']}",
                "english": verse["_source"]["text_english"][:500],
                "arabic": verse["_source"]["text_arabic"][:300],
            }
            for verse in batch
        ],
    }
    request_body = {
        "messages": [
            {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
        ],
        "temperature": 0.1,
        "max_completion_tokens": min(16000, 800 + len(batch) * 180),
        "stream": False,
    }
    headers = {"Authorization": f"Bearer {AGENT_KEY}"}
    for attempt in range(MAX_RETRIES):
        try:
            _, response = http_json("POST", AGENT_URL, payload=request_body, headers=headers)
            content = response["choices"][0]["message"]["content"]
            parsed = json.loads(strip_code_fence(content))
            return parsed
        except Exception as exc:
            if attempt == MAX_RETRIES - 1:
                raise
            print(f"Agent call failed (attempt {attempt + 1}/{MAX_RETRIES}): {exc}", file=sys.stderr)
            time.sleep(RETRY_DELAY_SECS * (attempt + 1))


def normalize_assignments(result, allowed_slugs):
    assignments = {}
    for document in result.get("documents", []):
        doc_id = document.get("id", "")
        raw_tags = document.get("tags", [])
        filtered = []
        for tag in raw_tags:
            if tag in allowed_slugs and tag not in filtered:
                filtered.append(tag)
        if filtered:
            assignments[doc_id] = filtered
    return assignments


def classify_single_verse(verse, compact_taxonomy, allowed_slugs):
    payload = {
        "task": "quran_verse_tagging",
        "instructions": (
            "Classify this single Quran verse into controlled taxonomy slugs.\n"
            "You MUST return at least one valid tag from the taxonomy for this verse.\n"
            "Assign ONLY PRIMARY tags. Do not return an empty tags array.\n"
            'Return ONLY valid JSON: {"documents":[{"id":"verse-id","tags":["slug-1"]}]}'
        ),
        "taxonomy": compact_taxonomy,
        "documents": [
            {
                "id": verse["_id"],
                "reference": f"{verse['_source']['surah_name_english']} {verse['_source']['ayah_number']}",
                "english": verse["_source"]["text_english"][:500],
                "arabic": verse["_source"]["text_arabic"][:300],
            }
        ],
    }
    request_body = {
        "messages": [
            {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
        ],
        "temperature": 0.0,
        "max_completion_tokens": 500,
        "stream": False,
    }
    headers = {"Authorization": f"Bearer {AGENT_KEY}"}
    for attempt in range(MAX_RETRIES):
        try:
            _, response = http_json("POST", AGENT_URL, payload=request_body, headers=headers)
            content = response["choices"][0]["message"]["content"]
            parsed = json.loads(strip_code_fence(content))
            tags = normalize_assignments(parsed, allowed_slugs).get(verse["_id"], [])
            if tags:
                return tags
        except Exception:
            pass
        time.sleep(RETRY_DELAY_SECS * (attempt + 1))
    return []


def bulk_update_tags(updates):
    lines = []
    for verse_id, tags in updates:
        lines.append(json.dumps({"update": {"_index": INDEX_NAME, "_id": verse_id}}, ensure_ascii=False))
        lines.append(json.dumps({"doc": {"topic_tags": tags}}, ensure_ascii=False))
    data = ("\n".join(lines) + "\n").encode("utf-8")
    body = None
    last_exc = None
    for attempt in range(MAX_RETRIES):
        try:
            req = urllib.request.Request(
                f"{ES_BASE_URL}/_bulk",
                data=data,
                headers={"Content-Type": "application/x-ndjson"},
                method="POST",
            )
            with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT) as resp:
                body = json.loads(resp.read().decode("utf-8"))
            break
        except (urllib.error.URLError, ConnectionError) as exc:
            last_exc = exc
            if attempt == MAX_RETRIES - 1:
                raise
            time.sleep(RETRY_DELAY_SECS * (attempt + 1))
    if body is None:
        raise last_exc
    if body.get("errors"):
        raise RuntimeError(f"Bulk tag update failed: {json.dumps(body)[:1000]}")


def retag_verses(verses, compact_taxonomy, allowed_slugs):
    checkpoint = load_checkpoint()
    processed_surahs = set(checkpoint["processed_surahs"])
    grouped = {}
    for verse in verses:
        grouped.setdefault(verse["_source"]["surah_number"], []).append(verse)

    for surah_number in range(START_SURAH, END_SURAH + 1):
        if surah_number in processed_surahs:
            print(f"Skipping surah {surah_number} from checkpoint")
            continue
        surah_verses = grouped[surah_number]
        changed = 0
        print(f"Tagging surah {surah_number} with {len(surah_verses)} verses")
        for start in range(0, len(surah_verses), BATCH_SIZE):
            batch = surah_verses[start : start + BATCH_SIZE]
            result = call_agent(batch, compact_taxonomy, allowed_slugs)
            assignments = normalize_assignments(result, allowed_slugs)

            updates = []
            for verse in batch:
                verse_id = verse["_id"]
                source = "llm_batch"
                tags = assignments.get(verse_id)
                if not tags:
                    tags = classify_single_verse(verse, compact_taxonomy, allowed_slugs)
                    source = "llm_single_retry"
                if not tags:
                    raise RuntimeError(f"Missing tag assignment for {verse_id}")
                verse["_source"]["topic_tags"] = tags
                updates.append((verse_id, tags))
                changed += 1
                if source == "llm_batch":
                    checkpoint["llm_batch_count"] += 1
                elif source == "llm_single_retry":
                    checkpoint["llm_single_retry_count"] += 1
                    if verse_id not in checkpoint["llm_single_retry_verse_ids"]:
                        checkpoint["llm_single_retry_verse_ids"].append(verse_id)
                append_audit_record(verse_id, source, tags)
            bulk_update_tags(updates)
            print(
                f"  Updated verses {batch[0]['_id']} to {batch[-1]['_id']} "
                f"({start + 1}-{start + len(batch)} of {len(surah_verses)})"
            )

        processed_surahs.add(surah_number)
        checkpoint["processed_surahs"] = sorted(processed_surahs)
        checkpoint["verses_processed"] += len(surah_verses)
        checkpoint["verses_changed"] += changed
        save_checkpoint(checkpoint)
        print(f"Completed surah {surah_number}: {changed} verses retagged")

    if CHECKPOINT_FILE.exists():
        CHECKPOINT_FILE.unlink()
    print(
        f"Retagging complete. Surahs={len(processed_surahs)} "
        f"Verses={checkpoint['verses_processed']} Changed={checkpoint['verses_changed']} "
        f"LlmBatch={checkpoint['llm_batch_count']} "
        f"LlmSingleRetry={checkpoint['llm_single_retry_count']}"
    )


def verify_counts(expected_verses):
    es_refresh()
    _, count_body = es_request("GET", f"/{INDEX_NAME}/_count")
    actual_count = count_body["count"]
    if actual_count != expected_verses:
        raise RuntimeError(f"Verse count mismatch: expected {expected_verses}, got {actual_count}")
    _, search_body = es_request(
        "POST",
        f"/{INDEX_NAME}/_search",
        {"size": 3, "query": {"exists": {"field": "topic_tags"}}, "sort": ["ayah_index"]},
    )
    print(f"Verified {actual_count} verses in {INDEX_NAME}")
    for hit in search_body["hits"]["hits"]:
        src = hit["_source"]
        print(f"  {hit['_id']}: {src.get('topic_tags', [])}")


def main():
    if not AGENT_KEY:
        raise SystemExit("QURAN_TAGGING_AI_AGENT_KEY is required")

    primary_taxonomy, allowed_slugs, compact_taxonomy = load_primary_taxonomy()
    print(f"Loaded taxonomy: {len(primary_taxonomy)} primary tags")

    verses = load_quran()
    print(f"Loaded Quran source data: {len(verses)} verses")

    if REBUILD_INDEX:
        recreate_index()
        bulk_index(verses)
    retag_verses(verses, compact_taxonomy, allowed_slugs)
    if VERIFY_INDEX:
        verify_counts(len(verses))


if __name__ == "__main__":
    main()
