#!/usr/bin/env python3
import json
import os
import random
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TAXONOMY_JSON = ROOT / "src/main/resources/static/taxonomy.json"

ES_BASE_URL = os.environ.get("ELASTICSEARCH_URL", "http://127.0.0.1:9200").rstrip("/")
HADITH_INDEX = os.environ.get("REWAYAAT_INDEX", "rewayaat_updated")
QURAN_INDEX = os.environ.get("QURAN_VERSES_INDEX", "rewayaat_quran")
AGENT_URL = os.environ.get(
    "TOPIC_TAGS_AI_AGENT_URL",
    os.environ.get("SUMMARY_AI_AGENT_URL", ""),
).strip()
AGENT_KEY = os.environ.get(
    "TOPIC_TAGS_AI_AGENT_KEY",
    os.environ.get("SUMMARY_AI_AGENT_KEY", ""),
).strip()
OUT_DIR = Path(os.environ.get("TAGGING_AUDIT_OUTPUT_DIR", "/mnt/share/rewayaat-tagging-audit"))
SAMPLE_SIZE = int(os.environ.get("TAGGING_AUDIT_SAMPLE_SIZE", "100"))
HADITH_BATCH_SIZE = int(os.environ.get("TAGGING_AUDIT_HADITH_BATCH_SIZE", "5"))
QURAN_BATCH_SIZE = int(os.environ.get("TAGGING_AUDIT_QURAN_BATCH_SIZE", "10"))
TIMEOUT = int(os.environ.get("TAGGING_AUDIT_TIMEOUT_SECS", "120"))
RETRY_DELAY_SECS = float(os.environ.get("TAGGING_AUDIT_RETRY_DELAY_SECS", "2"))
MAX_RETRIES = int(os.environ.get("TAGGING_AUDIT_MAX_RETRIES", "4"))
RANDOM_SEED = int(os.environ.get("TAGGING_AUDIT_RANDOM_SEED", "20260517"))


def http_json(method, url, payload=None, headers=None, timeout=TIMEOUT):
    body = None
    req_headers = {"Content-Type": "application/json"}
    if headers:
        req_headers.update(headers)
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    last_exc = None
    for attempt in range(MAX_RETRIES):
        try:
            req = urllib.request.Request(url, data=body, headers=req_headers, method=method)
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                raw = resp.read().decode("utf-8")
                return resp.getcode(), json.loads(raw) if raw else {}
        except (urllib.error.URLError, ConnectionError, TimeoutError) as exc:
            last_exc = exc
            if attempt == MAX_RETRIES - 1:
                raise
            time.sleep(RETRY_DELAY_SECS * (attempt + 1))
    raise last_exc


def es_request(method, path, payload=None):
    return http_json(method, f"{ES_BASE_URL}{path}", payload=payload)


def load_taxonomy():
    taxonomy = json.loads(TAXONOMY_JSON.read_text(encoding="utf-8"))
    hadith = []
    quran = []
    for entry in taxonomy:
        slug = (entry.get("slug") or "").strip()
        if not slug:
            continue
        parts = [slug, entry.get("en", ""), f"category={entry.get('category', '')}"]
        if entry.get("parent"):
            parts.append(f"parent={entry['parent']}")
        if entry.get("description"):
            parts.append(entry["description"])
        line = " | ".join(part for part in parts if part)
        hadith.append(line)
        if entry.get("type") != "secondary":
            quran.append(line)
    return hadith, quran


def scroll_sample(index, source_fields, sample_size):
    query = {
        "size": 500,
        "sort": ["_doc"],
        "_source": source_fields,
        "query": {"match_all": {}},
    }
    _, data = es_request("POST", f"/{urllib.parse.quote(index)}/_search?scroll=5m", query)
    scroll_id = data.get("_scroll_id")
    seen = 0
    sample = []
    try:
        while True:
            hits = data.get("hits", {}).get("hits", [])
            if not hits:
                break
            for hit in hits:
                seen += 1
                item = {"_id": hit["_id"], "_source": hit.get("_source", {})}
                if len(sample) < sample_size:
                    sample.append(item)
                else:
                    replacement = random.randrange(seen)
                    if replacement < sample_size:
                        sample[replacement] = item
            _, data = es_request("POST", "/_search/scroll", {"scroll": "5m", "scroll_id": scroll_id})
            scroll_id = data.get("_scroll_id", scroll_id)
    finally:
        if scroll_id:
            try:
                es_request("DELETE", "/_search/scroll", {"scroll_id": [scroll_id]})
            except Exception:
                pass
    return sample


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


def call_agent(payload):
    _, response = http_json(
        "POST",
        AGENT_URL,
        payload,
        headers={"Authorization": f"Bearer {AGENT_KEY}"},
    )
    return response["choices"][0]["message"]["content"]


def parse_completion(text):
    return json.loads(strip_code_fence(text))


def write_json(path, payload):
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def hadith_instructions():
    return (
        "You classify Shia hadith into controlled taxonomy slugs. "
        "You will receive id, book, chapter, section, english, arabic_matn, and taxonomy. "
        "The english field is only a short semantic hint, not the full narration, so rely on arabic_matn first and use english only as support. "
        "Tag only themes the hadith substantively addresses. "
        "Do not tag based on passing mentions, chains, incidental names, weak associations, or taxonomy-adjacent guesses. "
        "Prefer fewer correct tags over many weak tags. "
        "Most hadith should receive 1-5 direct tags; use more only when the hadith clearly spans multiple major themes. "
        "The taxonomy contains only directly taggable slugs. Parent and ancestor tags are added by the system later. "
        "Prefer the most specific supported direct tag. "
        "Do not output parent or ancestor rollups. "
        "Use chapter and section headings as supporting context, especially for terse legal narrations, but do not tag from heading alone when the body clearly points elsewhere. "
        "If the entry is only transmission metadata, rijal evaluation, bibliographic boilerplate, or chain material without substantive hadith content, return an empty tags array. "
        "Do not use quran just because a verse is quoted or referenced. "
        "Do not use knowledge just because the hadith teaches something, includes a chain, or is a transmission-chain notice. "
        "Do not use good-character if a more specific ethical tag fits. "
        "Do not use faith, halal, or similar umbrella tags unless they are the explicit central subject of the hadith. "
        "Avoid broad umbrella tags such as knowledge, faith, good-character, family, leadership, livelihood, and halal unless the hadith is truly about that umbrella topic. "
        "Do not infer a specific Imam from kunyah, title, or weak contextual clues alone. "
        "Use person tags only when the hadith is materially about that figure, their words, their role, their example, or an event centered on them. "
        "Do not assign ahl-al-bayt by default to every Imam narration. "
        "Do not assign leadership unless governance, authority, rule, rights, or public authority are actually central. "
        "Do not assign legal or ritual tags unless the hadith is actually discussing that legal or ritual matter. "
        "Do not choose the nearest available devotional or legal tag when the exact fit is missing; prefer an empty tags array over a near miss. "
        "When a secondary theme is explicit in the body, include it along with the primary legal or ritual tag, for example taqiyyah in an oath narration or wilayah in an authority narration. "
        "When clearly supported, prefer specific Shia tags such as imamate, wilayah, ghadir, imam-ali, imam-husayn, karbala, ziyarat, occultation, imam-mahdi, and reappearance-signs over generic doctrinal tags. "
        "Use evidence in this order: arabic_matn, then english. "
        "IMPORTANT: You MUST ONLY USE TAGS FROM THE PROVIDED TAXONOMY. Do NOT suggest, propose, or invent new tags. "
        "If no existing tag is a reasonable fit, return an empty tags array for that document; quality matters more than coverage. "
        "Return only valid JSON with this schema: {\"documents\":[{\"id\":\"doc-id\",\"tags\":[\"slug-1\",\"slug-2\"]}]}. "
        "Do not output prose, markdown, explanations, or code fences."
    )


def quran_instructions():
    return (
        "You are an expert Quranic verse classification system specializing in Islamic theological taxonomy. "
        "Your task is to classify Quranic verses (ayat) into controlled taxonomy slugs. "
        "Assign ONLY PRIMARY tags (conceptual/theological themes). NEVER assign secondary tags. "
        "Assign all tags that genuinely apply where the verse substantively addresses that theme. "
        "Most verses will have 2-4 primary tags; complex narrative verses may have 5-7 tags. "
        "Every verse must receive at least one primary tag. "
        "Choose the most specific child tag when the verse clearly supports it; otherwise choose the narrowest defensible parent. "
        "Do not add both a parent and its child. The system adds ancestors automatically. "
        "Do not invent slugs. Only use tags from the provided taxonomy. "
        "Prefer precise tags over broad tags, and avoid taxonomy-adjacent guesses. "
        "Use direct verse content first. Do not import distant tafsir associations unless the verse is conventionally and strongly identified with that theme. "
        "Prophet tags should be used only when the verse materially narrates, addresses, or draws a lesson about that specific prophet. "
        "Never substitute one prophet for another. For example, do not use isa for Isaac. If a named prophet appears but that prophet does not exist as a taxonomy slug, prefer prophethood or another fitting non-person tag rather than the wrong prophet. "
        "Do not use person tags solely from pronouns or ambiguous references. "
        "Do NOT use faith merely because the verse mentions belief. "
        "Do NOT use knowledge merely because the verse mentions knowing or learning. "
        "Do NOT use quran for verses that are self-referential about the Quran itself. "
        "Do NOT use tawhid on ordinary legal or exhortative verses merely because Allah is mentioned. "
        "Do NOT use signs-of-god for isolated letters, brief oaths, or verses with no explicit evidentiary content. "
        "Return ONLY valid JSON in this exact format: {\"documents\":[{\"id\":\"verse-id\",\"tags\":[\"slug-1\",\"slug-2\"]}]}"
    )


def run_hadith_audit(taxonomy):
    out_path = OUT_DIR / "hadith-sample-results-v3.json"
    prompt_path = OUT_DIR / "hadith-prompt-v3.txt"
    prompt_path.write_text(hadith_instructions() + "\n", encoding="utf-8")
    sample = scroll_sample(
        HADITH_INDEX,
        ["book", "chapter", "section", "semantic_english_hint_source", "semantic_matn_source"],
        SAMPLE_SIZE,
    )
    results = []
    write_json(out_path, results)
    for i in range(0, len(sample), HADITH_BATCH_SIZE):
        batch = sample[i:i + HADITH_BATCH_SIZE]
        docs = []
        for item in batch:
            src = item["_source"]
            docs.append(
                {
                    "id": item["_id"],
                    "book": src.get("book", ""),
                    "chapter": src.get("chapter", ""),
                    "section": src.get("section", ""),
                    "english": (src.get("semantic_english_hint_source") or "")[:2200],
                    "arabic_matn": (src.get("semantic_matn_source") or "")[:2200],
                }
            )
        payload = {
            "messages": [
                {
                    "role": "user",
                    "content": json.dumps(
                        {
                            "task": "topic_tag_classification",
                            "instructions": hadith_instructions(),
                            "taxonomy": taxonomy,
                            "documents": docs,
                        },
                        ensure_ascii=False,
                    ),
                }
            ],
            "stream": False,
            "temperature": 0,
            "max_completion_tokens": min(16000, 900 + len(docs) * 220),
            "retrieval_method": "none",
        }
        parsed = parse_completion(call_agent(payload))
        by_id = {doc["id"]: doc.get("tags", []) for doc in parsed.get("documents", [])}
        for item in batch:
            results.append({"id": item["_id"], "source": item["_source"], "tags": by_id.get(item["_id"], [])})
        write_json(out_path, results)
        print(f"hadith {i + len(batch)} of {len(sample)}", flush=True)


def run_quran_audit(taxonomy):
    out_path = OUT_DIR / "quran-sample-results-v3.json"
    prompt_path = OUT_DIR / "quran-prompt-v3.txt"
    prompt_path.write_text(quran_instructions() + "\n", encoding="utf-8")
    sample = scroll_sample(
        QURAN_INDEX,
        ["surah_name_english", "ayah_number", "text_english", "text_arabic"],
        SAMPLE_SIZE,
    )
    results = []
    write_json(out_path, results)
    for i in range(0, len(sample), QURAN_BATCH_SIZE):
        batch = sample[i:i + QURAN_BATCH_SIZE]
        docs = []
        for item in batch:
            src = item["_source"]
            docs.append(
                {
                    "id": item["_id"],
                    "reference": f"{src.get('surah_name_english', '')} {src.get('ayah_number', '')}",
                    "english": (src.get("text_english") or "")[:500],
                    "arabic": (src.get("text_arabic") or "")[:300],
                }
            )
        payload = {
            "messages": [
                {
                    "role": "user",
                    "content": json.dumps(
                        {
                            "task": "quran_verse_tagging",
                            "instructions": quran_instructions(),
                            "taxonomy": taxonomy,
                            "documents": docs,
                        },
                        ensure_ascii=False,
                    ),
                }
            ],
            "stream": False,
            "temperature": 0,
            "max_completion_tokens": min(16000, 900 + len(docs) * 220),
            "retrieval_method": "none",
        }
        parsed = parse_completion(call_agent(payload))
        by_id = {doc["id"]: doc.get("tags", []) for doc in parsed.get("documents", [])}
        for item in batch:
            results.append({"id": item["_id"], "source": item["_source"], "tags": by_id.get(item["_id"], [])})
        write_json(out_path, results)
        print(f"quran {i + len(batch)} of {len(sample)}", flush=True)


def main():
    if not AGENT_URL or not AGENT_KEY:
        raise SystemExit("TOPIC_TAGS_AI_AGENT_URL and TOPIC_TAGS_AI_AGENT_KEY are required")
    random.seed(RANDOM_SEED)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    hadith_taxonomy, quran_taxonomy = load_taxonomy()
    run_hadith_audit(hadith_taxonomy)
    run_quran_audit(quran_taxonomy)
    print(f"wrote {OUT_DIR}")


if __name__ == "__main__":
    main()
