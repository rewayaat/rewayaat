#!/usr/bin/env python3
"""Remove the Arabic label fields from the narrations index.

The companion to ``translate_tier1.py --apply``. That run is purely additive: it
adds ``chapter_ar``, ``section_ar``, ``part_ar``, ``source_ar`` and ``book_ar``
to documents that already exist and overwrites nothing else, so undoing it means
removing those five fields rather than restoring anything.

The field stays in the mapping. An Elasticsearch mapping cannot drop a field, and
leaving it costs nothing: ``SEARCHABLE_FIELDS`` names the ``_ar`` fields already
and a field with no values simply never matches, which is the state production is
in today.

Deliberately not a reindex. A reindex would rewrite whole documents, and
``semantic_vector`` -- 1024 dimensions on each of 32,519 narrations -- is excluded
from the read path that feeds most of this pipeline. Rewriting a document from a
filtered read is how the vectors would get dropped. ``_update_by_query`` mutates
in place and never touches a field the script does not name.

    python3 scripts/i18n/rollback_ar_fields.py --es-host http://localhost:9200 --dry-run
    python3 scripts/i18n/rollback_ar_fields.py --es-host http://localhost:9200
"""

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from scripts.i18n.translate_utils import get_es_client, resolve_concrete_index

AR_FIELDS = ["chapter_ar", "section_ar", "part_ar", "source_ar", "book_ar"]


def count_with_field(es, index, field):
    return es.count(index=index, body={"query": {"exists": {"field": field}}})["count"]


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--es-host", default="http://localhost:9200")
    parser.add_argument("--index", default="rewayaat_hadith")
    parser.add_argument("--fields", default=None,
                        help=f"Comma-separated subset (default: {','.join(AR_FIELDS)})")
    parser.add_argument("--dry-run", action="store_true",
                        help="Report what would be removed and write nothing")
    args = parser.parse_args()

    fields = args.fields.split(",") if args.fields else AR_FIELDS
    es = get_es_client(args.es_host)
    concrete = resolve_concrete_index(es, args.index)
    print(f"Index: {args.index}" + (f" -> {concrete}" if concrete != args.index else ""))

    for field in fields:
        before = count_with_field(es, args.index, field)
        if not before:
            print(f"  {field:<12} 0 documents - nothing to remove")
            continue
        if args.dry_run:
            print(f"  {field:<12} would remove from {before} documents")
            continue

        # ctx._source.remove is a no-op on a document that lacks the field, but the
        # query already restricts the scan to documents that carry it.
        result = es.update_by_query(
            index=args.index,
            body={
                "query": {"exists": {"field": field}},
                "script": {
                    "source": "ctx._source.remove(params.field)",
                    "lang": "painless",
                    "params": {"field": field},
                },
            },
            refresh=True,
            conflicts="proceed",
            wait_for_completion=True,
        )
        after = count_with_field(es, args.index, field)
        print(f"  {field:<12} removed from {result['updated']} documents "
              f"({before} -> {after} remaining, {len(result.get('failures', []))} failures)")

    return 0


if __name__ == "__main__":
    sys.exit(main())
