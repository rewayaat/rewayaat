#!/usr/bin/env python3
"""
Create a synonym-enabled Elasticsearch clone of an existing index and optionally reindex data into it.

The new index adds a query-time synonym_graph analyzer and applies it as `search_analyzer`
to all `text` fields while preserving the existing index-time analyzer behavior.
"""

from __future__ import annotations

import argparse
import json
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional


def _build_headers(api_key: Optional[str], user: Optional[str], password: Optional[str]) -> Dict[str, str]:
    headers = {"Content-Type": "application/json"}
    if api_key:
        headers["Authorization"] = f"ApiKey {api_key}"
    elif user and password:
        import base64
        token = base64.b64encode(f"{user}:{password}".encode("utf-8")).decode("ascii")
        headers["Authorization"] = f"Basic {token}"
    return headers


def _make_ssl_context(insecure: bool) -> Optional[ssl.SSLContext]:
    if not insecure:
        return None
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    return context


def _request_json(
    url: str,
    *,
    method: str = "GET",
    body: Optional[Dict[str, Any]] = None,
    headers: Dict[str, str],
    context: Optional[ssl.SSLContext],
) -> Any:
    payload = None
    if body is not None:
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, method=method, headers=headers, data=payload)
    with urllib.request.urlopen(req, context=context, timeout=300) as resp:
        raw = resp.read()
        charset = resp.headers.get_content_charset() or "utf-8"
        return json.loads(raw.decode(charset))


def _index_exists(es_url: str, index: str, headers: Dict[str, str], context: Optional[ssl.SSLContext]) -> bool:
    try:
        _request_json(f"{es_url}/{urllib.parse.quote(index)}", headers=headers, context=context)
        return True
    except urllib.error.HTTPError as err:
        if err.code == 404:
            return False
        raise


def _delete_index(es_url: str, index: str, headers: Dict[str, str], context: Optional[ssl.SSLContext]) -> None:
    _request_json(f"{es_url}/{urllib.parse.quote(index)}", method="DELETE", headers=headers, context=context)


def _load_synonyms(path: Path) -> List[str]:
    synonyms: List[str] = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        synonyms.append(line)
    return synonyms


def _filter_index_settings(index_settings: Dict[str, Any]) -> Dict[str, Any]:
    allowed: Dict[str, Any] = {}
    for key in ("number_of_shards", "number_of_replicas", "refresh_interval"):
        if key in index_settings:
            allowed[key] = index_settings[key]
    return allowed


def _apply_search_analyzer(properties: Dict[str, Any], analyzer_name: str) -> None:
    for field in properties.values():
        if not isinstance(field, dict):
            continue
        if field.get("type") == "text":
            field["search_analyzer"] = analyzer_name
        nested = field.get("properties")
        if isinstance(nested, dict):
            _apply_search_analyzer(nested, analyzer_name)


def _build_index_body(
    settings_index: Dict[str, Any],
    mappings: Dict[str, Any],
    synonyms: List[str],
) -> Dict[str, Any]:
    settings = _filter_index_settings(settings_index)
    settings["analysis"] = {
        "filter": {
            "rewayaat_synonyms": {
                "type": "synonym_graph",
                "synonyms": synonyms,
            }
        },
        "analyzer": {
            "rewayaat_synonym_search": {
                "tokenizer": "standard",
                "filter": ["lowercase", "asciifolding", "rewayaat_synonyms"],
            }
        },
    }

    mappings_copy = json.loads(json.dumps(mappings))
    props = mappings_copy.get("properties")
    if isinstance(props, dict):
        _apply_search_analyzer(props, "rewayaat_synonym_search")

    return {"settings": settings, "mappings": mappings_copy}


def _reindex(es_url: str, src_index: str, dest_index: str, headers: Dict[str, str], context: Optional[ssl.SSLContext]) -> Any:
    body = {
        "source": {"index": src_index},
        "dest": {"index": dest_index},
    }
    return _request_json(
        f"{es_url}/_reindex?wait_for_completion=true&refresh=true",
        method="POST",
        body=body,
        headers=headers,
        context=context,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Create a synonym-aware clone of an Elasticsearch index")
    parser.add_argument("--es-url", required=True)
    parser.add_argument("--src-index", required=True)
    parser.add_argument("--dest-index", required=True)
    parser.add_argument("--synonyms-file", default="src/main/resources/synonyms.txt")
    parser.add_argument("--reindex", action="store_true", help="Copy documents from source to destination")
    parser.add_argument("--replace", action="store_true", help="Delete destination index if it already exists")
    parser.add_argument("--es-user")
    parser.add_argument("--es-pass")
    parser.add_argument("--es-api-key")
    parser.add_argument("--es-insecure", action="store_true")
    args = parser.parse_args()

    headers = _build_headers(args.es_api_key, args.es_user, args.es_pass)
    context = _make_ssl_context(args.es_insecure)
    src = urllib.parse.quote(args.src_index)
    dest = urllib.parse.quote(args.dest_index)

    mapping_resp = _request_json(f"{args.es_url}/{src}/_mapping", headers=headers, context=context)
    settings_resp = _request_json(f"{args.es_url}/{src}/_settings", headers=headers, context=context)
    mappings = mapping_resp[args.src_index]["mappings"]
    settings_index = settings_resp[args.src_index]["settings"]["index"]
    synonyms = _load_synonyms(Path(args.synonyms_file))

    if _index_exists(args.es_url, args.dest_index, headers, context):
        if not args.replace:
            print(f"Destination index {args.dest_index} already exists. Use --replace to rebuild it.", file=sys.stderr)
            return 2
        _delete_index(args.es_url, args.dest_index, headers, context)

    body = _build_index_body(settings_index, mappings, synonyms)
    _request_json(f"{args.es_url}/{dest}", method="PUT", body=body, headers=headers, context=context)
    print(f"Created {args.dest_index} with {len(synonyms)} synonym rules.")

    if args.reindex:
        result = _reindex(args.es_url, args.src_index, args.dest_index, headers, context)
        print(json.dumps(result, ensure_ascii=False, indent=2))

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
