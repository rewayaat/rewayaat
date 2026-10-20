#!/usr/bin/env python3
"""Convert the books the v2 rebuild lost from the v1 snapshot dump into import batches.

The v1 site (Elasticsearch 7.10.2) held Ghurar al-Hikam, Mizan al-Hikma and Mishkat
al-Anwar. The v2 corpus was rebuilt from Thaqalayn, which has none of them, so the only
copy left is the v1 `rewayaat` index in the snapshot repository s3://rewayaat-data.
docs/data-pipeline.md ("Restoring the v1 books") says how to restore and dump it.

Writes, under --out-dir:
  batch_NNNNN.jsonl   {"_id", "_source"} lines, the shape import_hadith_batches.py reads
  manifest.json       counts per book and where the data came from
and, under --report-dir:
  v1_ids.json         {v1 id: new id} for every imported narration
  anomalies.json      titles the split rules could not settle (blocking) and titles that
                      were disambiguated or have no Arabic (not blocking)
  excluded.json       v1 ids left out, with the reason

Exits 1 while a blocking anomaly is not settled by --overrides.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

ARABIC = re.compile(r"[؀-ۿݐ-ݿﭐ-﷿ﹰ-﻿]")
LATIN = re.compile(r"[A-Za-z]")
INVISIBLE = re.compile(r"[﻿​‎‏]")
LINE_BREAK = re.compile(r"<br\s*/?>", re.IGNORECASE)
SMALL_WORDS = {"a", "an", "and", "as", "at", "but", "by", "for", "from", "in", "into",
               "nor", "of", "on", "or", "the", "to", "with"}
# The longest English title in the three books that is a title and not narration text
# pasted into the chapter field. Measured: see the plan's discovery receipt.
TITLE_MAX_CHARS = 100
BATCH_SIZE = 200
VOLUME = "1"
ARABIC_SOURCE = "rewayaat-v1"

BOOKS = [
    {
        "v1_prefix": "Ghurar Al-Hikam",
        "book": "Ghurar al-Ḥikam",
        "book_ar": "غرر الحكم ودرر الكلم",
        "id_prefix": "Ghurar-al-Hikam-Amidi",
        "source": "ʿAbd al-Wāḥid b. Muḥammad al-Tamīmī al-Āmidī",
        "source_ar": "عبد الواحد بن محمد التميمي الآمدي",
        "publisher": "Ansariyan Publications",
        "publisher_ar": "أنصاريان",
        "part_field": None,
        "cites": False,
    },
    {
        "v1_prefix": "Mizan Al-Hikmah",
        "book": "Mīzān al-Ḥikma",
        "book_ar": "ميزان الحكمة",
        "id_prefix": "Mizan-al-Hikma-Rayshahri",
        "source": "Muḥammad Muḥammadī Rayshahrī",
        "source_ar": "محمد المحمدي الريشهري",
        "publisher": "ICAS Press",
        "publisher_ar": "ICAS Press",
        "part_field": "section",
        "cites": True,
    },
    {
        "v1_prefix": "Mishkat Al-Anwar",
        "book": "Mishkāt al-Anwār",
        "book_ar": "مشكاة الأنوار في غرر الأخبار",
        "id_prefix": "Mishkat-al-Anwar-Tabrisi",
        "source": "ʿAlī b. al-Ḥasan al-Ṭabrisī",
        "source_ar": "علي بن الحسن الطبرسي",
        "publisher": None,
        "publisher_ar": None,
        "part_field": "section",
        "cites": False,
    },
]

BLOCKING = {"no-english", "arabic-in-english", "latin-in-arabic", "too-long"}


def clean(text: str | None) -> str:
    return re.sub(r"\s+", " ", INVISIBLE.sub("", text or "")).strip()


# Source titles in Mizan's v1 citations whose transliteration was mangled (ū became "U",
# ʿ became "Ae", ṭā became "O?"). Corrected spellings approved by the user 2026-10-21;
# only the title changes, never the page or number after it.
CITED_TITLE_FIXES = [
    ("AeIlal al-Sharaia", "ʿIlal al-Sharāʾiʿ"), ("AeIlal al-Share ia", "ʿIlal al-Sharāʾiʿ"),
    ("MaO?lib al-SaUl", "Maṭālib al-Saʾūl"), ("Sunan Abi DawUd", "Sunan Abī Dāwūd"),
    ("Ibn Shahr ashUb", "Ibn Shahr Āshūb"), ("Kashf al-MaIajja", "Kashf al-Maḥajja"),
    ("Irshad al-QulUb", "Irshād al-Qulūb"),
]


def cited_from(text: str | None) -> str:
    out = clean(text)
    for wrong, right in CITED_TITLE_FIXES:
        out = out.replace(wrong, right)
    return out


def split_bilingual(raw: str | None) -> tuple[str, str]:
    """The (english, arabic) halves of a v1 title such as 'THIS WORLD الدنيا'."""
    text = clean(raw)
    head, sep, tail = text.partition("/")
    if sep and ARABIC.search(tail) and not ARABIC.search(head):
        english, arabic = head, tail
    elif ARABIC.match(text):
        latin = LATIN.search(text)
        english, arabic = (text[latin.start():], text[:latin.start()]) if latin else ("", text)
    else:
        found = ARABIC.search(text)
        english, arabic = (text[:found.start()], text[found.start():]) if found else (text, "")
    return english.strip().rstrip(" -–/:").strip(), arabic.strip()


def _capitalise(part: str) -> str:
    for i, ch in enumerate(part):
        if ch.isalpha():
            return part[:i] + ch.upper() + part[i + 1:]
    return part


def title_case(english: str) -> str:
    """Title-cases an ALL-CAPS title; leaves a title already in mixed case alone."""
    if english != english.upper():
        return english
    out = []
    for i, word in enumerate(english.lower().split(" ")):
        bare = word.strip("()[]\"'‘’“”,:;")
        out.append(word if i > 0 and bare in SMALL_WORDS
                   else "-".join(_capitalise(p) for p in word.split("-")))
    return " ".join(out)


def title_problems(english: str, arabic: str) -> list[str]:
    problems = []
    if not english:
        problems.append("no-english")
    if not arabic:
        problems.append("no-arabic")
    if ARABIC.search(english):
        problems.append("arabic-in-english")
    if LATIN.search(arabic):
        problems.append("latin-in-arabic")
    if len(english) > TITLE_MAX_CHARS:
        problems.append("too-long")
    return problems


def book_for(v1_book: str | None) -> dict | None:
    for spec in BOOKS:
        if (v1_book or "").startswith(spec["v1_prefix"]):
            return spec
    return None


def body_text(text: str | None) -> str:
    return LINE_BREAK.sub("\n", text or "").strip()


def resolve_title(spec, field, raw, overrides, anomalies):
    """(english, arabic, inherit) for one v1 title, applying an override when one exists."""
    key = clean(raw)
    override = overrides.get(field, {}).get(key)
    if override is not None:
        if override.get("inherit") == "previous":
            return "", "", True
        return override["english"], override.get("arabic", ""), False
    english, arabic = split_bilingual(raw)
    english = title_case(english)
    for problem in title_problems(english, arabic):
        anomalies[(spec["book"], field, key)].add(problem)
    return english, arabic, False


def convert(rows, overrides):
    picked = [(book_for(r.get("book")), r) for r in rows]
    picked = [(spec, r) for spec, r in picked if spec]

    numbers = Counter((spec["book"], str(r.get("number"))) for spec, r in picked)
    excluded, kept = [], []
    for spec, r in picked:
        fragment = numbers[(spec["book"], str(r.get("number")))] > 1 and not clean(r.get("arabic"))
        if fragment:
            excluded.append({"v1_id": r["_id"], "book": spec["book"], "reason": "list-fragment",
                             "english": clean(r.get("english"))})
        else:
            kept.append((spec, r))

    anomalies = defaultdict(set)
    docs = []
    for spec, r in sorted(kept, key=lambda p: (p[0]["book"], int(p[1]["number"]))):
        part_en = part_ar = ""
        if spec["part_field"]:
            part_en, part_ar, _ = resolve_title(spec, "part", r.get(spec["part_field"]),
                                                overrides, anomalies)
        chapter_en, chapter_ar, inherit = resolve_title(spec, "chapter", r.get("chapter"),
                                                        overrides, anomalies)
        docs.append({"spec": spec, "v1": r, "part": part_en, "part_ar": part_ar,
                     "chapter": chapter_en, "chapter_ar": chapter_ar, "inherit": inherit})

    previous = {}
    for doc in docs:
        key = (doc["spec"]["book"], doc["part"])
        if doc["inherit"]:
            if key not in previous:
                anomalies[(doc["spec"]["book"], "chapter", clean(doc["v1"].get("chapter")))].add(
                    "inherit-without-predecessor")
                continue
            doc["chapter"], doc["chapter_ar"] = previous[key]
        else:
            previous[key] = (doc["chapter"], doc["chapter_ar"])

    arabic_by_title = defaultdict(set)
    for doc in docs:
        arabic_by_title[(doc["spec"]["book"], doc["part"], doc["chapter"].casefold())].add(
            doc["chapter_ar"])
    disambiguated = []
    for doc in docs:
        variants = arabic_by_title[(doc["spec"]["book"], doc["part"], doc["chapter"].casefold())]
        if len(variants) > 1 and doc["chapter_ar"]:
            title = f"{doc['chapter']} ({doc['chapter_ar']})"
            disambiguated.append({"book": doc["spec"]["book"], "from": doc["chapter"], "to": title})
            doc["chapter"] = title

    out, v1_ids = [], {}
    for doc in docs:
        spec, r = doc["spec"], doc["v1"]
        new_id = f"{spec['id_prefix']}:{r['number']}"
        if new_id in v1_ids.values():
            raise ValueError(f"duplicate id {new_id}")
        v1_ids[r["_id"]] = new_id
        source = {
            "book": spec["book"], "book_ar": spec["book_ar"],
            "number": str(r["number"]), "volume": VOLUME,
            "chapter": doc["chapter"],
            "arabic": body_text(r.get("arabic")), "english": body_text(r.get("english")),
            "source": spec["source"], "source_ar": spec["source_ar"],
        }
        if doc["chapter_ar"]:
            source["chapter_ar"] = doc["chapter_ar"]
            source["chapter_ar_source"] = ARABIC_SOURCE
        if doc["part"]:
            source["part"] = doc["part"]
            if doc["part_ar"]:
                source["part_ar"] = doc["part_ar"]
        if spec["publisher"]:
            source["publisher"] = spec["publisher"]
            source["publisher_ar"] = spec["publisher_ar"]
        if spec["cites"] and clean(r.get("source")):
            source["cited_from"] = cited_from(r.get("source"))
        out.append({"_id": new_id, "_source": source})

    blocking = {k: sorted(v & BLOCKING) for k, v in anomalies.items() if v & BLOCKING}
    report = {
        "blocking": [{"book": b, "field": f, "raw": raw, "problems": p}
                     for (b, f, raw), p in sorted(blocking.items())],
        "untranslated": sorted({(b, f, raw) for (b, f, raw), v in anomalies.items()
                                if "no-arabic" in v and not v & BLOCKING}),
        "disambiguated": disambiguated,
    }
    return out, v1_ids, excluded, report


def write_outputs(docs, v1_ids, excluded, report, out_dir: Path, report_dir: Path):
    out_dir.mkdir(parents=True, exist_ok=True)
    report_dir.mkdir(parents=True, exist_ok=True)
    for old in out_dir.glob("batch_*.jsonl"):
        old.unlink()
    for n, start in enumerate(range(0, len(docs), BATCH_SIZE), start=1):
        with open(out_dir / f"batch_{n:05d}.jsonl", "w", encoding="utf-8") as f:
            for doc in docs[start:start + BATCH_SIZE]:
                f.write(json.dumps(doc, ensure_ascii=False) + "\n")
    manifest = {
        "source": "s3://rewayaat-data, snapshot 2025-02-20, index rewayaat (ES 7.10.2)",
        "documents": len(docs),
        "batches": (len(docs) + BATCH_SIZE - 1) // BATCH_SIZE,
        "batch_size": BATCH_SIZE,
        "books": dict(Counter(d["_source"]["book"] for d in docs)),
    }
    (out_dir / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    for name, payload in (("v1_ids.json", v1_ids), ("excluded.json", excluded),
                          ("anomalies.json", report)):
        (report_dir / name).write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dump", required=True, help="v1 dump, one JSON document per line")
    parser.add_argument("--overrides", required=True, help="title overrides JSON")
    parser.add_argument("--out-dir", required=True, help="where the batch files go")
    parser.add_argument("--report-dir", required=True, help="where the id map and reports go")
    args = parser.parse_args()

    rows = [json.loads(line) for line in open(args.dump, encoding="utf-8") if line.strip()]
    overrides = json.loads(Path(args.overrides).read_text(encoding="utf-8"))
    docs, v1_ids, excluded, report = convert(rows, overrides)
    write_outputs(docs, v1_ids, excluded, report, Path(args.out_dir), Path(args.report_dir))
    print(json.dumps({"documents": len(docs), "excluded": len(excluded),
                      "blocking": len(report["blocking"]),
                      "untranslated": len(report["untranslated"]),
                      "disambiguated": len(report["disambiguated"])}))
    return 1 if report["blocking"] else 0


if __name__ == "__main__":
    sys.exit(main())
