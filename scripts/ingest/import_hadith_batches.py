#!/usr/bin/env python3
"""Import hadith JSONL batch exports into Elasticsearch.

Each input line must have the shape:
{"_id": "...", "_source": {...}}

The script creates the destination index if needed with a conservative mapping
for the core hadith fields used by the application and tagging tools.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import ssl
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple


DEFAULT_MAPPING = {
    "dynamic": True,
    "properties": {
        "book": {"type": "keyword"},
        "number": {"type": "keyword"},
        "part": {"type": "keyword"},
        "chapter": {
            "type": "text",
            "fields": {
                "keyword": {"type": "keyword", "ignore_above": 1024}
            },
        },
        "section": {"type": "keyword"},
        "volume": {"type": "keyword"},
        "source": {"type": "keyword"},
        "publisher": {"type": "keyword"},
        "edition": {"type": "keyword"},
        "arabic": {"type": "text"},
        "english": {"type": "text"},
        "notes": {"type": "text"},
        "tags": {"type": "keyword"},
        "topic_tags": {"type": "keyword"},
        "history": {"type": "text"},
        "related": {
            "type": "nested",
            "properties": {
                "url": {"type": "keyword"},
                "title": {"type": "text"},
            },
        },
        "gradings": {
            "type": "nested",
            "properties": {
                "grader": {"type": "keyword"},
                "grading": {"type": "keyword"},
            },
        },
    },
}


def _build_headers(
    api_key: Optional[str],
    user: Optional[str],
    password: Optional[str],
    extra: Optional[Dict[str, str]] = None,
) -> Dict[str, str]:
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
            with urllib.request.urlopen(req, context=context, timeout=120) as resp:
                raw = resp.read()
                if not raw:
                    return None
                charset = resp.headers.get_content_charset() or "utf-8"
                return json.loads(raw.decode(charset))
        except urllib.error.HTTPError as err:
            last_err = err
            if err.code in (429, 500, 502, 503, 504) and attempt < retries - 1:
                time.sleep(retry_sleep * (2 ** attempt))
                continue
            raise
        except urllib.error.URLError as err:
            last_err = err
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
) -> Dict[str, Any]:
    payload = "\n".join(ndjson_lines) + "\n"
    req_headers = {
        "Content-Type": "application/x-ndjson",
        "Accept": "application/json",
    }
    if headers:
        req_headers.update(headers)
    req = urllib.request.Request(
        url,
        data=payload.encode("utf-8"),
        method="POST",
        headers=req_headers,
    )
    with urllib.request.urlopen(req, context=context, timeout=300) as resp:
        raw = resp.read()
        charset = resp.headers.get_content_charset() or "utf-8"
        return json.loads(raw.decode(charset))


def _index_exists(es_url: str, index: str, headers: Dict[str, str], context: Optional[ssl.SSLContext]) -> bool:
    try:
        _request_json(f"{es_url}/{index}", headers=headers, context=context)
        return True
    except urllib.error.HTTPError as err:
        if err.code == 404:
            return False
        raise


def _create_index(es_url: str, index: str, headers: Dict[str, str], context: Optional[ssl.SSLContext]) -> None:
    body = {
        "settings": {
            "number_of_shards": 1,
            "number_of_replicas": 0,
        },
        "mappings": DEFAULT_MAPPING,
    }
    _request_json(f"{es_url}/{index}", method="PUT", body=body, headers=headers, context=context)


def _iter_batch_files(path: Path) -> List[Path]:
    if path.is_file():
        return [path]
    return sorted(p for p in path.glob("batch_*.jsonl") if p.is_file())


def _iter_docs(batch_file: Path) -> Iterable[Tuple[str, Dict[str, Any]]]:
    with batch_file.open("r", encoding="utf-8") as handle:
        for line_number, raw_line in enumerate(handle, start=1):
            line = raw_line.strip()
            if not line:
                continue
            payload = json.loads(line)
            doc_id = payload.get("_id")
            source = payload.get("_source", {})
            if not doc_id or not isinstance(source, dict):
                raise ValueError(f"{batch_file}:{line_number} missing _id/_source")
            source.pop("_id", None)
            yield str(doc_id), source


def _bulk_index(
    es_url: str,
    index: str,
    docs: Iterable[Tuple[str, Dict[str, Any]]],
    headers: Dict[str, str],
    context: Optional[ssl.SSLContext],
) -> Tuple[int, int]:
    lines: List[str] = []
    for doc_id, doc in docs:
        lines.append(json.dumps({"index": {"_index": index, "_id": doc_id}}))
        lines.append(json.dumps(doc, ensure_ascii=False))
    if not lines:
        return 0, 0
    response = _request_ndjson(f"{es_url}/_bulk", lines, headers=headers, context=context)
    success = 0
    failed = 0
    for item in response.get("items", []):
        result = item.get("index", {})
        if result.get("error"):
            failed += 1
        else:
            success += 1
    return success, failed


def main() -> int:
    parser = argparse.ArgumentParser(description="Import hadith batch exports into Elasticsearch")
    parser.add_argument("--es-url", required=True, help="Elasticsearch base URL, e.g. http://localhost:9200")
    parser.add_argument("--dest-index", required=True, help="Destination index name")
    parser.add_argument("--batch-path", default="batches", help="Directory or single batch_*.jsonl file")
    parser.add_argument("--chunk-size", type=int, default=500, help="Bulk indexing chunk size")
    parser.add_argument("--create-index", action="store_true", help="Create destination index if missing")
    parser.add_argument("--fail-if-exists", action="store_true", help="Stop if destination index already exists")
    parser.add_argument("--es-user", help="Elasticsearch username")
    parser.add_argument("--es-pass", help="Elasticsearch password")
    parser.add_argument("--es-api-key", help="Elasticsearch API key")
    parser.add_argument("--es-insecure", action="store_true", help="Disable TLS verification")
    args = parser.parse_args()

    if args.chunk_size <= 0:
        print("--chunk-size must be > 0", file=sys.stderr)
        return 2

    headers = _build_headers(args.es_api_key, args.es_user, args.es_pass)
    context = _make_ssl_context(args.es_insecure)
    batch_path = Path(args.batch_path)
    batch_files = _iter_batch_files(batch_path)
    if not batch_files:
        print(f"No batch files found under {batch_path}", file=sys.stderr)
        return 2

    exists = _index_exists(args.es_url, args.dest_index, headers, context)
    if exists and args.fail_if_exists:
        print(f"Destination index {args.dest_index} already exists", file=sys.stderr)
        return 2
    if not exists:
        if not args.create_index:
            print(
                f"Destination index {args.dest_index} does not exist. Re-run with --create-index.",
                file=sys.stderr,
            )
            return 2
        print(f"Creating index {args.dest_index}...")
        _create_index(args.es_url, args.dest_index, headers, context)

    total_ok = 0
    total_failed = 0

    for batch_file in batch_files:
        print(f"Importing {batch_file}...")
        chunk: List[Tuple[str, Dict[str, Any]]] = []
        for record in _iter_docs(batch_file):
            chunk.append(record)
            if len(chunk) >= args.chunk_size:
                ok, bad = _bulk_index(args.es_url, args.dest_index, chunk, headers, context)
                total_ok += ok
                total_failed += bad
                chunk = []
        if chunk:
            ok, bad = _bulk_index(args.es_url, args.dest_index, chunk, headers, context)
            total_ok += ok
            total_failed += bad

    print(f"Done. Indexed={total_ok} Failed={total_failed} Files={len(batch_files)}")
    return 0 if total_failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
