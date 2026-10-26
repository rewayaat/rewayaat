"""Bring the slugs stored in the chains up to date with the narrator index.

A chain span carries the man's id and his slug, and the page builds the href out of the
pair rather than looking the slug up per name — 105,174 spans, so the lookup belongs in the
build. The cost is that renaming a man leaves the stored slug stale: the link still reaches
him, because the controller redirects a non-canonical slug to the canonical one, but every
name in every isnād he stands in then costs a redirect, and the two indexes disagree about
what he is called. Rebuilding the whole chain load to fix a name is an hour's work for a
field that is one string; this walks the chains and restamps the slugs alone.
"""
import argparse
import json
import sys
import urllib.request

HADITH = "rewayaat_hadith"
NARRATORS = "rewayaat_narrators"
LINK_FIELDS = ("chain_links", "chain_links_en")
PAGE = 500


def post(host, path, body=None):
    request = urllib.request.Request(
        host + path, data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request) as response:
        return json.load(response)


def bulk(host, lines):
    payload = "".join(json.dumps(line, ensure_ascii=False) + "\n" for line in lines)
    request = urllib.request.Request(
        host + "/_bulk", data=payload.encode(),
        headers={"Content-Type": "application/x-ndjson"})
    with urllib.request.urlopen(request) as response:
        answer = json.load(response)
    if answer.get("errors"):
        first = next(item for item in answer["items"]
                     if list(item.values())[0].get("error"))
        sys.exit("bulk failed: " + json.dumps(first, ensure_ascii=False)[:400])


def slugs(host):
    """id → slug, straight from the index the pages are served from."""
    out, after = {}, None
    while True:
        body = {"size": 2000, "sort": ["person_id"],
                "_source": ["person_id", "slug"]}
        if after:
            body["search_after"] = after
        page = post(host, "/%s/_search?filter_path=hits.hits._source,hits.hits.sort"
                    % NARRATORS, body)
        hits = page.get("hits", {}).get("hits", [])
        if not hits:
            return out
        for hit in hits:
            source = hit["_source"]
            out[source["person_id"]] = source.get("slug") or ""
        after = hits[-1]["sort"]


def restamp(value, slug_of, counter):
    """Walk the links in place; anything carrying an id and a slug gets the current one."""
    if isinstance(value, list):
        for item in value:
            restamp(item, slug_of, counter)
        return
    if not isinstance(value, dict):
        return
    person = value.get("p")
    if person and "g" in value and value["g"]:
        # An unresolved mention stores an empty slug on purpose; leave it empty.
        current = slug_of.get(person)
        if current is not None and current != value["g"]:
            value["g"] = current
            counter[0] += 1
    for item in value.values():
        restamp(item, slug_of, counter)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--es-host", default="http://localhost:9200")
    parser.add_argument("--apply", action="store_true",
                        help="write the corrected links back; otherwise only count them")
    args = parser.parse_args()
    host = args.es_host.rstrip("/")

    slug_of = slugs(host)
    print("%d narrators" % len(slug_of))

    body = {"size": 200, "sort": ["_doc"], "_source": list(LINK_FIELDS),
            "query": {"bool": {"should": [{"exists": {"field": f}} for f in LINK_FIELDS],
                               "minimum_should_match": 1}}}
    scroll = post(host, "/%s/_search?scroll=5m&filter_path=_scroll_id,hits.hits._id,"
                  "hits.hits._source" % HADITH, body)
    scroll_id = scroll.get("_scroll_id")
    docs = spans = touched = 0
    pending = []
    try:
        while True:
            hits = scroll.get("hits", {}).get("hits", [])
            if not hits:
                break
            for hit in hits:
                docs += 1
                changed = {}
                for field in LINK_FIELDS:
                    links = hit["_source"].get(field)
                    if not links:
                        continue
                    counter = [0]
                    restamp(links, slug_of, counter)
                    if counter[0]:
                        changed[field] = links
                        spans += counter[0]
                if changed:
                    touched += 1
                    pending += [{"update": {"_index": HADITH, "_id": hit["_id"]}},
                                {"doc": changed}]
                    if args.apply and len(pending) >= PAGE * 2:
                        bulk(host, pending)
                        pending = []
                    elif not args.apply:
                        pending = []
            scroll = post(host, "/_search/scroll?filter_path=_scroll_id,hits.hits._id,"
                          "hits.hits._source", {"scroll": "5m", "scroll_id": scroll_id})
            scroll_id = scroll.get("_scroll_id") or scroll_id
        if args.apply and pending:
            bulk(host, pending)
    finally:
        if scroll_id:
            try:
                urllib.request.urlopen(urllib.request.Request(
                    host + "/_search/scroll", method="DELETE",
                    data=json.dumps({"scroll_id": [scroll_id]}).encode(),
                    headers={"Content-Type": "application/json"}))
            except Exception:
                pass
    print("%d narrations read, %d carry a stale slug, %d spans restamped%s"
          % (docs, touched, spans, "" if args.apply else " (dry run)"))


if __name__ == "__main__":
    main()
