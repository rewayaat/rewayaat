#!/usr/bin/env python3
"""Write recovered chapter titles into the index.

Only the books whose recovery has been read and checked, and only ever as a partial
update of chapter_ar: nothing else on the document is touched, so semantic_vector and
llm_similar are untouched by construction.

Each title carries where it came from. chapter_ar_source records the OpenITI version it
was read out of, so a title recovered from a printed book is never indistinguishable
from one produced any other way.

    python3 scripts/i18n/load_chapter_titles.py --dry-run
    python3 scripts/i18n/load_chapter_titles.py --es-host http://localhost:9200
"""

import argparse
import json
import pathlib
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
CACHE = ROOT / "tmp" / "openiti"

# Book key -> the OpenITI version it was recovered from. Only books whose output was
# read at several ordinals and found correct appear here; the rest are left out
# deliberately and the reasons are in recover_chapter_titles.NEEDS_WORK.
VERIFIED = {
    "al-kafi": "0329IbnYacqubKulayni.Kafi.Shia001122Vols-ara1",
    "maani-al-akhbar": "0381IbnBabawayhSaduq.MacaniAkhbar.Shia001148-ara1",
    "al-tawhid": "0381IbnBabawayhSaduq.Tawhid.Shia001136-ara1",
    "al-khisal": "0381IbnBabawayhSaduq.Khisal.Shia001137-ara1",
    "man-la-yahduruh": "0381IbnBabawayhSaduq.ManLaYahduruhuFaqih.Shia001149Vols-ara1",
    "uyun-akhbar": "0381IbnBabawayhSaduq.CuyunAkhbarRida.Shia001142Vols-ara1",
    "al-amali-saduq": "0381IbnBabawayhSaduq.Amali.Shia001134-ara1",
}

# Titles found by locating each chapter's narrations in the printed text rather than by
# counting headings. Preferred wherever it exists: it cannot drift, because it never
# depends on a position.
SUFFIX = "_matn_matched.json"


def post(url, body=None, method="POST"):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(url, data=data, method=method,
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=120) as resp:
        return json.load(resp)


def docs_for_chapter(es_host, index, chapter):
    """Every document whose chapter is this one, by exact keyword match."""
    body = {"size": 10000, "_source": False,
            "query": {"term": {"chapter.keyword": chapter}}}
    result = post(f"{es_host}/{index}/_search", body)
    return [hit["_id"] for hit in result["hits"]["hits"]]


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--es-host", default="http://localhost:9200")
    parser.add_argument("--index", default="rewayaat_hadith")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    titles = {}
    for key, version in VERIFIED.items():
        path = CACHE / f"{key}{SUFFIX}"
        if not path.exists():
            print(f"  missing {path}; run match_chapters_by_matn.py --book {key}")
            continue
        for english, arabic in json.loads(path.read_text(encoding="utf-8")).items():
            if english and arabic:
                titles[english] = (arabic, version)
        print(f"  {key:<18} {len(json.loads(path.read_text(encoding='utf-8'))):>5} titles")

    print(f"\n  {len(titles)} distinct chapters to write")
    actions, touched = [], 0
    for english, (arabic, version) in titles.items():
        ids = docs_for_chapter(args.es_host, args.index, english)
        touched += len(ids)
        for doc_id in ids:
            actions.append({"_id": doc_id, "chapter_ar": arabic, "chapter_ar_source": version})

    print(f"  {touched} documents affected")
    if args.dry_run:
        preview = CACHE / "chapter_ar_updates.json"
        preview.write_text(json.dumps(actions[:50], ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"  dry run; first 50 written to {preview}")
        return 0

    sent = 0
    for start in range(0, len(actions), 500):
        chunk = actions[start:start + 500]
        lines = []
        for action in chunk:
            lines.append(json.dumps({"update": {"_id": action["_id"], "_index": args.index}}))
            lines.append(json.dumps({"doc": {"chapter_ar": action["chapter_ar"],
                                             "chapter_ar_source": action["chapter_ar_source"]}},
                                    ensure_ascii=False))
        payload = ("\n".join(lines) + "\n").encode()
        request = urllib.request.Request(f"{args.es_host}/_bulk", data=payload, method="POST",
                                         headers={"Content-Type": "application/x-ndjson"})
        with urllib.request.urlopen(request, timeout=180) as resp:
            result = json.load(resp)
        failed = [i for i in result.get("items", []) if i.get("update", {}).get("error")]
        sent += len(chunk) - len(failed)
        if failed:
            print(f"    {len(failed)} failures in this batch; first: {failed[0]}")
    post(f"{args.es_host}/{args.index}/_refresh", method="POST")
    print(f"  wrote {sent} documents")
    return 0


if __name__ == "__main__":
    sys.exit(main())
