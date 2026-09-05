#!/usr/bin/env python3
"""Import hadith from the Thaqalayn API into a new Elasticsearch index.

Creates the destination index using the mapping/settings from an existing
source index (default: rewayaat), then bulk indexes transformed documents.

Requires only the Python standard library.
"""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import json
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Dict, Iterable, List, Optional, Tuple


def _utc_today() -> str:
    return dt.datetime.utcnow().strftime("%Y-%m-%d")


def _build_headers(api_key: Optional[str], user: Optional[str], password: Optional[str], extra: Optional[Dict[str, str]] = None) -> Dict[str, str]:
    headers: Dict[str, str] = {}
    if api_key:
        headers["Authorization"] = f"ApiKey {api_key}"
    elif user is not None and password is not None:
        token = base64.b64encode(f"{user}:{password}".encode("utf-8")).decode("ascii")
        headers["Authorization"] = f"Basic {token}"
    if extra:
        headers.update(extra)
    return headers


def _make_ssl_context(insecure: bool) -> Optional[ssl.SSLContext]:
    if not insecure:
        return None
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return ctx


def _request_json(
    url: str,
    method: str = "GET",
    body: Optional[Dict[str, Any]] = None,
    headers: Optional[Dict[str, str]] = None,
    context: Optional[ssl.SSLContext] = None,
    retries: int = 3,
    retry_sleep: float = 1.0,
) -> Any:
    data = None
    req_headers = {"Accept": "application/json"}
    if headers:
        req_headers.update(headers)
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        req_headers["Content-Type"] = "application/json"
    last_err = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, data=data, method=method, headers=req_headers)
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


def _request_ndjson(
    url: str,
    ndjson_lines: Iterable[str],
    headers: Optional[Dict[str, str]] = None,
    context: Optional[ssl.SSLContext] = None,
    retries: int = 3,
    retry_sleep: float = 1.0,
) -> Any:
    payload = "\n".join(ndjson_lines) + "\n"
    req_headers = {"Content-Type": "application/x-ndjson", "Accept": "application/json"}
    if headers:
        req_headers.update(headers)
    last_err = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, data=payload.encode("utf-8"), method="POST", headers=req_headers)
            with urllib.request.urlopen(req, context=context, timeout=120) as resp:
                raw = resp.read()
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


def _filter_index_settings(index_settings: Dict[str, Any]) -> Dict[str, Any]:
    # Keep only safe, user-configurable settings
    allow = {}
    if "analysis" in index_settings:
        allow["analysis"] = index_settings["analysis"]
    for key in ("number_of_shards", "number_of_replicas", "refresh_interval", "max_result_window"):
        if key in index_settings:
            allow[key] = index_settings[key]
    return allow


def _to_str(value: Any) -> Optional[str]:
    if value is None:
        return None
    return str(value)


def _append_note(lines: List[str], label: str, value: Optional[str]) -> None:
    if value is None:
        return
    trimmed = str(value).strip()
    if not trimmed:
        return
    lines.append(f"{label}: {trimmed}")


def _build_doc(h: Dict[str, Any], book: Dict[str, Any]) -> Dict[str, Any]:
    book_id = h.get("bookId") or book.get("bookId")
    book_name = h.get("book") or book.get("bookName") or book.get("book")
    author = h.get("author") or book.get("author")
    language = book.get("language")

    doc: Dict[str, Any] = {}
    doc["book"] = book_name
    doc["source"] = author
    doc["number"] = _to_str(h.get("id") or h.get("number") or h.get("_id"))
    doc["part"] = h.get("category")
    doc["chapter"] = h.get("chapter")
    doc["section"] = _to_str(h.get("chapterInCategoryId") or h.get("categoryId"))
    doc["volume"] = _to_str(h.get("volume"))
    doc["arabic"] = h.get("arabicText")
    doc["english"] = h.get("englishText")

    tags: List[str] = ["source:thaqalayn"]
    if book_id:
        tags.append(f"bookId:{book_id}")
    if language:
        tags.append(f"language:{language}")
    if h.get("category"):
        tags.append(f"category:{h.get('category')}")
    if h.get("categoryId") is not None:
        tags.append(f"categoryId:{h.get('categoryId')}")
    if h.get("chapterInCategoryId") is not None:
        tags.append(f"chapterInCategoryId:{h.get('chapterInCategoryId')}")
    doc["tags"] = tags

    # Notes intentionally omitted per request.

    gradings: List[Dict[str, Any]] = []
    for key, grader in (
        ("majlisiGrading", "Allamah Majlisi"),
        ("mohseniGrading", "Ayatullah Mohseni"),
        ("behbudiGrading", "Ayatullah Behbudi"),
    ):
        val = h.get(key)
        if val:
            gradings.append({"grader": grader, "grading": val})
    if gradings:
        doc["gradings"] = gradings

    url = h.get("URL")
    if url:
        doc["related"] = [{"url": url, "title": "Thaqalayn Source"}]

    history: List[str] = [f"Imported from Thaqalayn API on {_utc_today()}."]
    if book_id:
        history.append(f"Thaqalayn bookId={book_id}.")
    if h.get("id") is not None:
        history.append(f"Thaqalayn id={h.get('id')}.")
    doc["history"] = history

    # Remove empty fields so we do not create extra mappings
    return {k: v for k, v in doc.items() if v not in (None, "", [], {})}


def _extract_data(payload: Any) -> List[Dict[str, Any]]:
    if isinstance(payload, list):
        return payload
    if isinstance(payload, dict):
        if "data" in payload and isinstance(payload["data"], list):
            return payload["data"]
    raise ValueError("Unexpected API response shape; expected list or {data: [...]}")


def _index_exists(es_url: str, index: str, headers: Dict[str, str], context: Optional[ssl.SSLContext]) -> bool:
    try:
        _request_json(f"{es_url}/{index}", headers=headers, context=context)
        return True
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return False
        raise


def _delete_index(es_url: str, index: str, headers: Dict[str, str], context: Optional[ssl.SSLContext]) -> None:
    _request_json(f"{es_url}/{index}", method="DELETE", headers=headers, context=context)


def _create_index(es_url: str, src_index: str, dest_index: str, headers: Dict[str, str], context: Optional[ssl.SSLContext]) -> None:
    mapping_resp = _request_json(f"{es_url}/{src_index}/_mapping", headers=headers, context=context)
    settings_resp = _request_json(f"{es_url}/{src_index}/_settings", headers=headers, context=context)

    if src_index not in mapping_resp or src_index not in settings_resp:
        raise ValueError(f"Source index {src_index} not found in mapping/settings response")

    mappings = mapping_resp[src_index].get("mappings", {})
    settings_index = settings_resp[src_index].get("settings", {}).get("index", {})
    settings = _filter_index_settings(settings_index)

    body = {"settings": settings, "mappings": mappings}
    _request_json(f"{es_url}/{dest_index}", method="PUT", body=body, headers=headers, context=context)


def _iter_books(api_base: str, context: Optional[ssl.SSLContext]) -> List[Dict[str, Any]]:
    payload = _request_json(f"{api_base}/allbooks", context=context)
    return _extract_data(payload)


def _iter_hadiths(api_base: str, book_id: str, context: Optional[ssl.SSLContext]) -> List[Dict[str, Any]]:
    payload = _request_json(f"{api_base}/{urllib.parse.quote(book_id)}", context=context)
    return _extract_data(payload)


def _bulk_index(
    es_url: str,
    dest_index: str,
    docs: Iterable[Tuple[str, Dict[str, Any]]],
    headers: Dict[str, str],
    context: Optional[ssl.SSLContext],
) -> Tuple[int, int]:
    success = 0
    failed = 0
    lines: List[str] = []
    for doc_id, doc in docs:
        lines.append(json.dumps({"index": {"_index": dest_index, "_id": doc_id}}))
        lines.append(json.dumps(doc, ensure_ascii=False))
    if not lines:
        return 0, 0
    resp = _request_ndjson(f"{es_url}/_bulk", lines, headers=headers, context=context)
    if not isinstance(resp, dict):
        raise ValueError("Unexpected bulk response")
    items = resp.get("items", [])
    for item in items:
        result = item.get("index", {})
        if result.get("error"):
            failed += 1
        else:
            success += 1
    return success, failed


def main() -> int:
    parser = argparse.ArgumentParser(description="Import Thaqalayn hadith into a new Elasticsearch index")
    parser.add_argument("--es-url", required=True, help="Elasticsearch base URL, e.g. http://localhost:9200")
    parser.add_argument("--src-index", default="rewayaat", help="Source index to copy mappings/settings from")
    parser.add_argument("--dest-index", required=True, help="Destination index for Thaqalayn data")
    parser.add_argument("--es-user", help="Elasticsearch username")
    parser.add_argument("--es-pass", help="Elasticsearch password")
    parser.add_argument("--es-api-key", help="Elasticsearch API key")
    parser.add_argument("--es-insecure", action="store_true", help="Disable TLS verification for Elasticsearch")
    parser.add_argument("--api-base", default="https://www.thaqalayn-api.net/api/v2", help="Thaqalayn API base URL")
    parser.add_argument("--books", help="Comma-separated bookIds to import; default is all")
    parser.add_argument("--keep-index", action="store_true", help="Keep destination index if it already exists")
    parser.add_argument("--batch-size", type=int, default=500, help="Bulk index batch size")
    parser.add_argument("--sleep", type=float, default=0.0, help="Sleep between book fetches (seconds)")
    args = parser.parse_args()

    es_headers = _build_headers(args.es_api_key, args.es_user, args.es_pass)
    es_context = _make_ssl_context(args.es_insecure)

    if _index_exists(args.es_url, args.dest_index, es_headers, es_context):
        if args.keep_index:
            print(f"Destination index {args.dest_index} already exists. Rerun without --keep-index to replace it.")
            return 2
        print(f"Deleting existing index {args.dest_index}...")
        _delete_index(args.es_url, args.dest_index, es_headers, es_context)

    print(f"Creating index {args.dest_index} from {args.src_index}...")
    _create_index(args.es_url, args.src_index, args.dest_index, es_headers, es_context)

    api_context = None  # keep default TLS verification for Thaqalayn

    books = _iter_books(args.api_base, api_context)
    if args.books:
        wanted = {b.strip() for b in args.books.split(",") if b.strip()}
        books = [b for b in books if b.get("bookId") in wanted]
        print(f"Filtered to {len(books)} books: {', '.join(sorted(wanted))}")

    total_success = 0
    total_failed = 0

    for idx, book in enumerate(books, start=1):
        book_id = book.get("bookId")
        if not book_id:
            continue
        print(f"[{idx}/{len(books)}] Fetching {book_id}...")
        hadiths = _iter_hadiths(args.api_base, book_id, api_context)

        batch: List[Tuple[str, Dict[str, Any]]] = []
        for h in hadiths:
            doc = _build_doc(h, book)
            doc_id = f"{book_id}:{h.get('id') or h.get('_id') or h.get('number') or _utc_today()}"
            batch.append((doc_id, doc))
            if len(batch) >= args.batch_size:
                ok, bad = _bulk_index(args.es_url, args.dest_index, batch, es_headers, es_context)
                total_success += ok
                total_failed += bad
                batch = []
        if batch:
            ok, bad = _bulk_index(args.es_url, args.dest_index, batch, es_headers, es_context)
            total_success += ok
            total_failed += bad
        if args.sleep:
            time.sleep(args.sleep)

    print(f"Done. Indexed={total_success}, Failed={total_failed}")
    return 0 if total_failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
