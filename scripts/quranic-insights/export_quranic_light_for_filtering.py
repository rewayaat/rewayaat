#!/usr/bin/env python3
"""Export quranic light documents into JSONL batch files for Claude-based filtering."""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


ES_BASE_URL = os.environ.get("ELASTICSEARCH_URL", "http://localhost:9200").rstrip("/")
SOURCE_INDEX = os.environ.get("QLFILTER_SOURCE_INDEX", "rewayaat_quranic_light_filtered")
OUTPUT_DIR = Path(os.environ.get("QLFILTER_EXPORT_DIR", "tmp/qlight-batches"))
BATCH_SIZE = int(os.environ.get("QLFILTER_EXPORT_BATCH_SIZE", "20"))
SCROLL_KEEPALIVE = os.environ.get("QLFILTER_SCROLL_KEEPALIVE", "5m")
LIMIT = int(os.environ.get("QLFILTER_LIMIT", "0"))
SNIPPET_TEXT_MAX = int(os.environ.get("QLFILTER_SNIPPET_TEXT_MAX", "600"))
HADITH_TEXT_MAX = int(os.environ.get("QLFILTER_HADITH_TEXT_MAX", "1200"))
MAX_CANDIDATES = int(os.environ.get("QLFILTER_MAX_CANDIDATES", "10"))
MAX_SNIPPETS = int(os.environ.get("QLFILTER_MAX_SNIPPETS", "3"))
REQUEST_TIMEOUT = int(os.environ.get("QLFILTER_TIMEOUT_SECS", "120"))


def http_json(method, url, payload=None, timeout=REQUEST_TIMEOUT):
    body = None
    headers = {"Content-Type": "application/json"}
    if payload is not None:
        body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read().decode("utf-8")
        return json.loads(raw) if raw else {}


def es_request(method, path, payload=None):
    return http_json(method, f"{ES_BASE_URL}{path}", payload=payload)


def clean_text(text):
    return " ".join((text or "").split())


def short_text(text, max_chars):
    text = clean_text(text)
    if len(text) <= max_chars:
        return text
    return text[: max_chars - 3].rstrip() + "..."


def start_scroll():
    payload = {
        "size": 100,
        "sort": ["_doc"],
        "_source": [
            "hadith_id",
            "hadith_english",
            "hadith_book",
            "hadith_number",
            "hadith_chapter",
            "hadith_section",
            "candidate_count",
            "candidates",
        ],
        "query": {"match_all": {}},
    }
    return es_request("POST", f"/{SOURCE_INDEX}/_search?scroll={SCROLL_KEEPALIVE}", payload)


def continue_scroll(scroll_id):
    return es_request("POST", "/_search/scroll", {"scroll": SCROLL_KEEPALIVE, "scroll_id": scroll_id})


def clear_scroll(scroll_id):
    if not scroll_id:
        return
    try:
        es_request("DELETE", "/_search/scroll", {"scroll_id": [scroll_id]})
    except Exception:
        pass


def export_document(source):
    """Export a single quranic light document for filtering."""
    candidates = []
    for raw in (source.get("candidates") or [])[:MAX_CANDIDATES]:
        snippets = []
        for snippet in (raw.get("tafsir_snippets") or [])[:MAX_SNIPPETS]:
            snippets.append({
                "tafsir_name": snippet.get("tafsir_name", ""),
                "commentary_text": short_text(snippet.get("commentary_text", ""), SNIPPET_TEXT_MAX),
                "commentary_score": snippet.get("commentary_score", 0.0),
                "section_title": snippet.get("section_title", ""),
            })
        candidates.append({
            "verse_key": raw.get("verse_key", ""),
            "surah_name_english": raw.get("surah_name_english", ""),
            "text_english": short_text(raw.get("text_english", ""), 300),
            "text_arabic": short_text(raw.get("text_arabic", ""), 300),
            "combined_score": raw.get("combined_score", 0.0),
            "signal_count": raw.get("signal_count", 0),
            "signal_scores": raw.get("signal_scores", {}),
            "shared_tags": raw.get("shared_tags", []),
            "tafsir_snippets": snippets,
        })
    return {
        "hadith_id": source.get("hadith_id", ""),
        "hadith_english": short_text(source.get("hadith_english", ""), HADITH_TEXT_MAX),
        "hadith_book": source.get("hadith_book", ""),
        "hadith_number": source.get("hadith_number", ""),
        "hadith_chapter": source.get("hadith_chapter", ""),
        "hadith_section": source.get("hadith_section", ""),
        "candidate_count": source.get("candidate_count", len(candidates)),
        "candidates": candidates,
    }


def main():
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    print(f"Exporting from {SOURCE_INDEX} to {OUTPUT_DIR} (batch size {BATCH_SIZE})")

    scroll_id = None
    batch_num = 0
    batch_docs = []
    total_exported = 0

    try:
        page = start_scroll()
        scroll_id = page.get("_scroll_id", "")

        while True:
            hits = page.get("hits", {}).get("hits", [])
            if not hits:
                break

            for hit in hits:
                source = hit.get("_source", {})
                if not source.get("candidates"):
                    continue

                doc = export_document(source)
                batch_docs.append(doc)
                total_exported += 1

                if len(batch_docs) >= BATCH_SIZE:
                    batch_num += 1
                    path = OUTPUT_DIR / f"batch_{batch_num:03d}.jsonl"
                    with open(path, "w", encoding="utf-8") as f:
                        for d in batch_docs:
                            f.write(json.dumps(d, ensure_ascii=False) + "\n")
                    print(f"Wrote {path} ({len(batch_docs)} hadiths)")
                    batch_docs = []

                if LIMIT and total_exported >= LIMIT:
                    break

            if LIMIT and total_exported >= LIMIT:
                break

            page = continue_scroll(scroll_id)
            scroll_id = page.get("_scroll_id", scroll_id)

        if batch_docs:
            batch_num += 1
            path = OUTPUT_DIR / f"batch_{batch_num:03d}.jsonl"
            with open(path, "w", encoding="utf-8") as f:
                for d in batch_docs:
                    f.write(json.dumps(d, ensure_ascii=False) + "\n")
            print(f"Wrote {path} ({len(batch_docs)} hadiths)")

    finally:
        clear_scroll(scroll_id)

    print(f"Done: exported {total_exported} hadiths in {batch_num} batches")


if __name__ == "__main__":
    main()
