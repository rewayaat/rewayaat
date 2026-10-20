#!/usr/bin/env python3
"""Undo the v1-books restore on a cluster, from the receipts taken before it.

    python3 rollback_prod.py --es-url http://localhost:9201            # dry run: counts only
    python3 rollback_prod.py --es-url http://localhost:9201 --live     # do it

What the restore wrote, and how each is undone:
  1. The 17,519 narrations of Ghurar, Mizan and Mishkat in rewayaat_hadith: deleted.
  2. Their Qur'anic docs in rewayaat_quranic_light_filtered: deleted.
  3. llm_similar on the existing narrations: put back exactly as it was, from
     receipts/prod-llm_similar-before.jsonl; removed from any narration that had none.
  4. The catalogue is told to rebuild (rewayaat_meta/catalog updatedAt).
The fields the restore added to the mapping stay; Elasticsearch cannot drop a field,
and with no document using them they change nothing.
"""
import argparse
import json
from datetime import datetime, timezone
from pathlib import Path

from elasticsearch import Elasticsearch, helpers

BOOKS = ["Ghurar al-Ḥikam", "Mīzān al-Ḥikma", "Mishkāt al-Anwār"]
ID_PREFIXES = ["Ghurar-al-Hikam-Amidi:", "Mizan-al-Hikma-Rayshahri:", "Mishkat-al-Anwar-Tabrisi:"]
RECEIPTS = Path("/mnt/share/rewayaat-backup/tmp/v1-restore/receipts")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--es-url", required=True)
    ap.add_argument("--receipts", default=str(RECEIPTS))
    ap.add_argument("--live", action="store_true")
    args = ap.parse_args()
    es = Elasticsearch(args.es_url, request_timeout=300)
    live = args.live
    print(f"{'LIVE' if live else 'DRY RUN'} against {args.es_url}")

    books = {"terms": {"book": BOOKS}}
    n = es.count(index="rewayaat_hadith", query=books)["count"]
    print(f"1. restored narrations to delete: {n}")
    if live and n:
        r = es.delete_by_query(index="rewayaat_hadith", query=books, refresh=True, conflicts="proceed")
        print(f"   deleted {r['deleted']}, failures {len(r['failures'])}")

    qdocs = {"bool": {"should": [{"prefix": {"hadith_id": p}} for p in ID_PREFIXES], "minimum_should_match": 1}}
    n = es.count(index="rewayaat_quranic_light_filtered", query=qdocs)["count"]
    print(f"2. restored Qur'anic docs to delete: {n}")
    if live and n:
        r = es.delete_by_query(index="rewayaat_quranic_light_filtered", query=qdocs, refresh=True,
                               conflicts="proceed")
        print(f"   deleted {r['deleted']}, failures {len(r['failures'])}")

    before = {}
    with open(Path(args.receipts) / "prod-llm_similar-before.jsonl", encoding="utf-8") as f:
        for line in f:
            row = json.loads(line)
            before[row["_id"]] = row["llm_similar"]
    actions, seen = [], set()
    for hit in helpers.scan(es, index="rewayaat_hadith", _source=["llm_similar"], size=2000, scroll="10m"):
        doc_id, now = hit["_id"], hit["_source"].get("llm_similar")
        seen.add(doc_id)
        if doc_id in before:
            if now != before[doc_id]:
                actions.append({"_op_type": "update", "_index": hit["_index"], "_id": doc_id,
                                "doc": {"llm_similar": before[doc_id]}})
        elif now:
            actions.append({"_op_type": "update", "_index": hit["_index"], "_id": doc_id,
                            "script": {"source": "ctx._source.remove('llm_similar')"}})
    missing = [i for i in before if i not in seen]
    restore = sum(1 for a in actions if "doc" in a)
    print(f"3. llm_similar: {restore} to put back, {len(actions) - restore} to remove, "
          f"{len(missing)} backed-up ids no longer in the index")
    if live and actions:
        ok, errors = helpers.bulk(es, actions, chunk_size=500, raise_on_error=False)
        print(f"   updated {ok}, errors {len(errors)}")
        es.indices.refresh(index="rewayaat_hadith")

    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")
    print(f"4. catalogue signal -> {stamp}")
    if live:
        es.index(index="rewayaat_meta", id="catalog", document={"updatedAt": stamp}, refresh=True)


if __name__ == "__main__":
    main()
