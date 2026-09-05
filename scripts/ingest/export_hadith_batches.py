#!/usr/bin/env python3
"""Export hadith from Elasticsearch into JSONL batches for review.

Each batch file contains one JSON object per line:
{"_id": "...", "_source": {...}}
"""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import json
import os
import ssl
import sys
import time
import urllib.error
import urllib.request
from typing import Any, Dict, Iterable, List, Optional


def _utc_now() -> str:
    return dt.datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ")


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
            with urllib.request.urlopen(req, context=context, timeout=120) as resp:
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


def _parse_fields(fields: Optional[str]) -> Optional[List[str]]:
    if not fields:
        return None
    parsed = [f.strip() for f in fields.split(",") if f.strip()]
    return parsed or None


def _load_query(query: Optional[str], query_file: Optional[str]) -> Dict[str, Any]:
    if query and query_file:
        raise ValueError("Use only one of --query or --query-file")
    if query_file:
        with open(query_file, "r", encoding="utf-8") as f:
            return json.load(f)
    if query:
        return json.loads(query)
    return {"match_all": {}}


def _ensure_out_dir(path: str, force: bool) -> None:
    os.makedirs(path, exist_ok=True)
    existing = [p for p in os.listdir(path) if not p.startswith(".")]
    if existing and not force:
        raise ValueError(f"Output directory {path} is not empty. Use --force to overwrite.")


def _write_batch(path: str, hits: Iterable[Dict[str, Any]]) -> int:
    count = 0
    with open(path, "w", encoding="utf-8") as f:
        for hit in hits:
            line = {"_id": hit.get("_id"), "_source": hit.get("_source", {})}
            f.write(json.dumps(line, ensure_ascii=False))
            f.write("\n")
            count += 1
    return count


def _clear_scroll(es_url: str, scroll_id: str, headers: Dict[str, str], context: Optional[ssl.SSLContext]) -> None:
    try:
        _request_json(f"{es_url}/_search/scroll", method="DELETE", body={"scroll_id": [scroll_id]}, headers=headers, context=context)
    except Exception:
        # best-effort cleanup
        pass


def main() -> int:
    parser = argparse.ArgumentParser(description="Export hadith into JSONL batches for review")
    parser.add_argument("--es-url", required=True, help="Elasticsearch base URL, e.g. http://localhost:9200")
    parser.add_argument("--index", required=True, help="Index to export")
    parser.add_argument("--out-dir", required=True, help="Output directory for batch files")
    parser.add_argument("--batch-size", type=int, default=200, help="Documents per batch file")
    parser.add_argument("--fields", help="Comma-separated _source fields to include")
    parser.add_argument("--query", help="JSON query string (Elasticsearch Query DSL)")
    parser.add_argument("--query-file", help="Path to JSON file containing Query DSL")
    parser.add_argument("--es-user", help="Elasticsearch username")
    parser.add_argument("--es-pass", help="Elasticsearch password")
    parser.add_argument("--es-api-key", help="Elasticsearch API key")
    parser.add_argument("--es-insecure", action="store_true", help="Disable TLS verification for Elasticsearch")
    parser.add_argument("--force", action="store_true", help="Overwrite files in out-dir if it is not empty")
    parser.add_argument("--scroll", default="5m", help="Scroll keepalive, e.g. 2m, 5m")
    args = parser.parse_args()

    if args.batch_size <= 0:
        print("--batch-size must be > 0", file=sys.stderr)
        return 2

    _ensure_out_dir(args.out_dir, args.force)

    headers = _build_headers(args.es_api_key, args.es_user, args.es_pass)
    context = _make_ssl_context(args.es_insecure)

    fields = _parse_fields(args.fields)
    query = _load_query(args.query, args.query_file)

    search_body: Dict[str, Any] = {
        "size": args.batch_size,
        "sort": ["_doc"],
        "query": query,
    }
    if fields is not None:
        search_body["_source"] = fields

    search_url = f"{args.es_url}/{args.index}/_search?scroll={args.scroll}"
    resp = _request_json(search_url, method="POST", body=search_body, headers=headers, context=context)

    scroll_id = resp.get("_scroll_id") if isinstance(resp, dict) else None
    if scroll_id is None:
        print("Did not receive _scroll_id from Elasticsearch", file=sys.stderr)
        return 2

    total = 0
    batch_num = 0
    try:
        while True:
            hits = resp.get("hits", {}).get("hits", []) if isinstance(resp, dict) else []
            if not hits:
                break
            batch_num += 1
            batch_path = os.path.join(args.out_dir, f"batch_{batch_num:05d}.jsonl")
            wrote = _write_batch(batch_path, hits)
            total += wrote
            print(f"Wrote {wrote} docs -> {batch_path}")

            scroll_body = {"scroll": args.scroll, "scroll_id": scroll_id}
            resp = _request_json(f"{args.es_url}/_search/scroll", method="POST", body=scroll_body, headers=headers, context=context)
            scroll_id = resp.get("_scroll_id") if isinstance(resp, dict) else scroll_id

    finally:
        if scroll_id:
            _clear_scroll(args.es_url, scroll_id, headers, context)

    manifest = {
        "index": args.index,
        "exported_at": _utc_now(),
        "batch_size": args.batch_size,
        "fields": fields,
        "query": query,
        "batches": batch_num,
        "documents": total,
    }
    manifest_path = os.path.join(args.out_dir, "manifest.json")
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    print(f"Done. Exported {total} docs across {batch_num} batches.")
    print(f"Manifest: {manifest_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
