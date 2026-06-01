#!/usr/bin/env python3
"""Backfill missing hadith metadata from the Thaqalayn API into Elasticsearch.

Fetches volume, part, section, source, gradings, and related URL from the API
and bulk-updates matching documents in the rewayaat_updated index.

Requires only the Python standard library.
"""

import json
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

ES_URL = "http://localhost:9200"
INDEX = "rewayaat_updated"
API_BASE = "https://www.thaqalayn-api.net/api/v2"
CHUNK_SIZE = 500
SLEEP_BETWEEN_BOOKS = 0.5


# ── HTTP helpers ──────────────────────────────────────────────────

def _request_json(url: str, headers: Optional[dict] = None,
                  context: Optional[ssl.SSLContext] = None,
                  retries: int = 3, retry_sleep: float = 1.0) -> Any:
    req_headers = {"Accept": "application/json"}
    if headers:
        req_headers.update(headers)
    last_err = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers=req_headers)
            with urllib.request.urlopen(req, context=context, timeout=60) as resp:
                raw = resp.read()
                if not raw:
                    return None
                charset = resp.headers.get_content_charset() or "utf-8"
                return json.loads(raw.decode(charset))
        except urllib.error.HTTPError as e:
            last_err = e
            if e.code in (429, 500, 502, 503, 504) and attempt < retries - 1:
                time.sleep(retry_sleep * (2 ** attempt))
                continue
            raise
        except urllib.error.URLError as e:
            last_err = e
            if attempt < retries - 1:
                time.sleep(retry_sleep * (2 ** attempt))
                continue
            raise
    if last_err:
        raise last_err
    return None


def _bulk_update(lines: List[str]) -> dict:
    """Send bulk update request to ES."""
    payload = "\n".join(lines) + "\n"
    req = urllib.request.Request(
        f"{ES_URL}/_bulk",
        data=payload.encode("utf-8"),
        method="POST",
        headers={"Content-Type": "application/x-ndjson", "Accept": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=300) as resp:
        return json.loads(resp.read().decode("utf-8"))


# ── API fetching ──────────────────────────────────────────────────

def _extract_data(payload: Any) -> List[dict]:
    if isinstance(payload, list):
        return payload
    if isinstance(payload, dict) and "data" in payload:
        return payload["data"]
    raise ValueError(f"Unexpected API response shape: {type(payload)}")


def fetch_books() -> List[dict]:
    print("Fetching book list from Thaqalayn API...")
    payload = _request_json(f"{API_BASE}/allbooks")
    books = _extract_data(payload)
    print(f"  Found {len(books)} books")
    return books


def fetch_hadiths(book_id: str) -> List[dict]:
    payload = _request_json(f"{API_BASE}/{urllib.parse.quote(book_id)}")
    return _extract_data(payload)


# ── Metadata extraction ──────────────────────────────────────────

def _to_str(value: Any) -> Optional[str]:
    if value is None:
        return None
    s = str(value).strip()
    return s if s else None


def build_metadata(h: dict) -> dict:
    """Extract metadata fields from an API hadith that are missing in our index."""
    meta: Dict[str, Any] = {}

    volume = _to_str(h.get("volume"))
    if volume:
        meta["volume"] = volume

    part = _to_str(h.get("category"))
    if part:
        meta["part"] = part

    section = _to_str(h.get("chapterInCategoryId") or h.get("categoryId"))
    if section:
        meta["section"] = section

    source = _to_str(h.get("author"))
    if source:
        meta["source"] = source

    gradings = []
    for key, grader in (
        ("majlisiGrading", "Allamah Majlisi"),
        ("mohseniGrading", "Ayatullah Mohseni"),
        ("behbudiGrading", "Ayatullah Behbudi"),
    ):
        val = _to_str(h.get(key))
        if val:
            gradings.append({"grader": grader, "grading": val})
    if gradings:
        meta["gradings"] = gradings

    url = _to_str(h.get("URL"))
    if url:
        meta["related"] = [{"url": url, "title": "Thaqalayn Source"}]

    return meta


# ── Main ──────────────────────────────────────────────────────────

def main() -> int:
    books = fetch_books()

    total_api = 0
    total_matched = 0
    total_updated = 0
    total_noop = 0
    total_errors = 0

    for idx, book in enumerate(books, start=1):
        book_id = book.get("bookId")
        if not book_id:
            continue

        print(f"[{idx}/{len(books)}] Fetching {book_id}...", end=" ", flush=True)
        hadiths = fetch_hadiths(book_id)
        print(f"{len(hadiths)} hadiths")

        # Build metadata lookup and bulk lines
        lines = []
        for h in hadiths:
            hid = h.get("id") or h.get("_id") or h.get("number")
            if not hid:
                continue
            doc_id = f"{book_id}:{hid}"
            meta = build_metadata(h)
            if not meta:
                continue
            lines.append(json.dumps({"update": {"_index": INDEX, "_id": doc_id}}))
            lines.append(json.dumps({"doc": meta}))

        total_api += len(hadiths)

        if not lines:
            continue

        # Send in chunks
        for i in range(0, len(lines), CHUNK_SIZE * 2):
            chunk = lines[i:i + CHUNK_SIZE * 2]
            resp = _bulk_update(chunk)
            for item in resp.get("items", []):
                r = item.get("update", {})
                if r.get("error"):
                    total_errors += 1
                    if total_errors <= 5:
                        print(f"  ERROR {r.get('_id')}: {r['error']}")
                elif r.get("result") == "noop":
                    total_noop += 1
                else:
                    total_updated += 1
                if r.get("status") == 200:
                    total_matched += 1

        if SLEEP_BETWEEN_BOOKS:
            time.sleep(SLEEP_BETWEEN_BOOKS)

    print(f"\nDone.")
    print(f"  API hadiths:     {total_api}")
    print(f"  Matched in ES:   {total_matched}")
    print(f"  Updated:         {total_updated}")
    print(f"  Noop (same):     {total_noop}")
    print(f"  Errors:          {total_errors}")
    return 0 if total_errors == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
