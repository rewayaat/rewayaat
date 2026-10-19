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
import unicodedata
from collections import Counter, defaultdict
from functools import lru_cache
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
REVIEW_SOURCE = "rewayaat-review"

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
    return english.strip().rstrip(" -–/:|*").strip(), arabic.strip()


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


DIACRITICS = re.compile(r"[\u064B-\u0652\u0670]")
TATWEEL = "\u0640"
RLM = "\u200f"
HONORIFICS = ["عليه السلام", "عليهم السلام", "عليها السلام", "صلى الله عليه وآله",
              "صلى الله عليه وآله وسلم"]
# What a font's honorific glyph turned into when the PDF was extracted: Latin letters,
# digits, a slash, or a و followed by a colon.
GLYPH = re.compile(r"[A-Za-z0-9/^]+|و:")
# Characters an encoding failure left where a transliterated vowel was: Im?m, ImÁm.
BROKEN_ENCODING = re.compile(r"[?ÁÃÂ\ufffd]")
MIZAN_GHURAR_CITATION = re.compile(r"\s*\(غرر الح[كک]م\s*:[^()]*\)")
FOOTNOTE_DIGIT = re.compile(r"([A-Za-z”’)])\d{1,3}\b")
HEADING_NUMBER = re.compile(r"^(section|chapter)\s+\d+\s*-\s*", re.IGNORECASE)
UNVOCALISED_BOOKS = {"Mishkāt al-Anwār"}
FOOTNOTED_BOOKS = {"Mishkāt al-Anwār", "Mīzān al-Ḥikma"}
HEADING_BLEED_BOOKS = {"Mishkāt al-Anwār"}
NARRATION_END = re.compile(r"[.!?”\"’)\]]$")
CITING_BOOKS = {"Mīzān al-Ḥikma"}
TITLE_FIELDS = {"chapter", "chapter_ar", "part", "part_ar"}


def skeleton(text: str | None) -> str:
    """The Arabic letters of a text and nothing else: what no repair may change."""
    bare = DIACRITICS.sub("", (text or "").replace(TATWEEL, ""))
    return re.sub(r"[^\u0621-\u064A]", "", bare)


def clean_arabic(text: str, book: str) -> str:
    """Repairs what a rule can repair in an Arabic body, and proves it changed no letter."""
    if book in CITING_BOOKS:
        # A citation the export pasted into the text. The English omits it and
        # cited_from carries it; it is not part of the narration.
        text = MIZAN_GHURAR_CITATION.sub("", text)
    # A case vowel the export pushed behind an RLM, onto the next word: «اللّه \u200fُبعَبدٍ».
    out = re.sub(r" ?" + RLM + r"([\u064B-\u0652]+)(?=[\u0621-\u064A])", r"\1 ", text)
    out = re.sub(r" ?" + RLM + r"([\u064B-\u0652]+)", r"\1", out)
    out = re.sub(r"([\u064B-\u0652])\1+", r"\1", out)
    out = re.sub(RLM + r"(?=[\u0621-\u064A])", " ", out)
    out = re.sub(r"(?<=\S) +(?= )", "", out.replace(RLM, "").replace("\u200e", ""))
    out = re.sub(r"(?<=[\u0621-\u064A\u064B-\u0652])" + TATWEEL + r"(?=[\u0621-\u064A])", "", out)
    if book in UNVOCALISED_BOOKS:
        out = DIACRITICS.sub("", out)
    out = re.sub(r"(\S) ء(?=[\s،.:؛]|$)", r"\1ء", out)
    out = re.sub(r"(^|\s)أ (ن)", r"\1أ\2", out)
    out = unicodedata.normalize("NFC", re.sub(r"[ \t]+", " ", out).strip())
    if skeleton(out) != skeleton(text):
        raise ValueError(f"clean_arabic changed the skeleton of: {text[:60]}")
    return out


def _normalise_words(text: str) -> list[str]:
    return re.sub(r"[^a-z0-9 ]", " ", text.lower()).split()


def _heading_words(headings) -> list[list[str]]:
    out = []
    for heading in headings:
        words = _normalise_words(HEADING_NUMBER.sub("", heading or ""))
        if len(words) >= 3:
            out.append(words)
    return out


def clean_english(text: str, book: str, headings) -> str:
    """Spacing, glued footnote digits and pasted headings; never a word of the translation."""
    out = text
    if book in FOOTNOTED_BOOKS:
        out = FOOTNOTE_DIGIT.sub(r"\1", out)
    out = re.sub(r"[ \t]{2,}", " ", out)
    out = re.sub(r" +([,.;:!?])", r"\1", out).strip()
    if book not in HEADING_BLEED_BOOKS:
        return out
    known = _heading_set(tuple(headings))
    if not known:
        return out
    # A heading the PDF extraction ran into the text sits after the narration has
    # ended, and carries no full stop of its own. A sentence that merely ends in a
    # heading's words ("…the adornment of the human being.") is neither.
    words = out.split(" ")
    longest = max(len(h) for h in known) + 3
    for k in range(min(len(words) - 1, longest), 0, -1):
        tail, rest = " ".join(words[-k:]), " ".join(words[:-k])
        if (tuple(_normalise_words(tail)) in known and NARRATION_END.search(rest)
                and not NARRATION_END.search(tail)):
            return rest.strip()
    return out


@lru_cache(maxsize=8)
def _heading_set(headings: tuple) -> frozenset:
    return frozenset(tuple(words) for words in _heading_words(headings))


def _english_tokens(text: str) -> list[str]:
    return re.findall(r"[^\W_]+|[^\w\s]", text)


def _removed_span_is_heading(old: str, new: str, headings) -> bool:
    if new and new not in old:
        return False
    removed = skeleton(old.replace(new, "", 1) if new else old)
    for heading in headings:
        h = skeleton(heading)
        words = (heading or "").split()
        prefixes = [skeleton(" ".join(words[:i])) for i in range(3, len(words) + 1)]
        if removed and (removed == h or removed in prefixes or (removed in h and len(removed) >= 12)):
            return True
    return False


# The only speakers a reader may correct a narration's opening attribution to, and the only
# shape that attribution may have. A speaker fix touches the attribution and nothing after it.
SPEAKERS_AR = {"الإمامُ عليٌّ (عَلَيهِ الّسَلامُ)"}
SPEAKERS_EN = {"Imam Ali (AS)"}
SPEAKER_PREFIX_AR = re.compile(r"^\s*(رسول\s*الل[ّ]?ه|الإمام\s+\S+)\s*\([^)]*\)\s*$")
SPEAKER_PREFIX_EN = re.compile(r"^(The Prophet|The Messenger of Allah|Imam [\w\-’' ]+?)\s*\((SAWA|AS|SAW)\)$")


def _validate_speaker(field: str, original: str, old: str, new: str) -> str:
    if not original.lstrip().startswith(old):
        raise ValueError("a speaker fix must replace the opening attribution")
    if field == "arabic" and SPEAKER_PREFIX_AR.match(DIACRITICS.sub("", old)) and new in SPEAKERS_AR:
        return "review"
    if field == "english" and SPEAKER_PREFIX_EN.match(old) and new in SPEAKERS_EN:
        return "review"
    raise ValueError(f"not a speaker attribution: {old[:40]!r} -> {new[:40]!r}")


def validate_fix(field: str, original: str, old: str, new: str, headings=(), kind: str = "text") -> str:
    """'auto' when a fix provably changes no word, 'review' when it needs a reader; raises otherwise."""
    if field in TITLE_FIELDS:
        raise ValueError("titles are corrected through the title overrides, not text fixes")
    if field not in ("arabic", "english"):
        raise ValueError(f"no fixes for field {field}")
    if field == "arabic":
        # Agents type vowel marks in canonical order; the export did not always store them so.
        original, old, new = (unicodedata.normalize("NFC", t) for t in (original, old, new))
    if not old or original.count(old) != 1:
        raise ValueError(f"'old' must occur exactly once in {field}")
    if kind == "speaker":
        return _validate_speaker(field, original, old, new)
    if field == "arabic":
        if skeleton(new) == skeleton(old):
            return "auto"
        if _removed_span_is_heading(old, new, headings):
            return "auto"
        if GLYPH.search(old):
            bare_old = skeleton(GLYPH.sub("", old))
            for honorific in HONORIFICS:
                if honorific in new and skeleton(new.replace(honorific, "", 1)) == bare_old:
                    return "review"
        raise ValueError(f"arabic fix changes the skeleton: {old[:40]!r} -> {new[:40]!r}")
    if _english_tokens(old) == _english_tokens(new):
        return "auto"
    if not new.strip() and _heading_words(headings) and _normalise_words(old) in _heading_words(headings):
        return "auto"
    old_tokens, new_tokens = _english_tokens(old), _english_tokens(new)
    if BROKEN_ENCODING.search(old) and len(old.split()) == len(new.split()):
        pattern = "^" + BROKEN_ENCODING.sub(".?", re.escape(old).replace(r"\?", "?")) + "$"
        if re.match(pattern.replace("?.?", ".?"), new, re.IGNORECASE):
            return "review"
    raise ValueError(f"english fix changes the words: {old[:40]!r} -> {new[:40]!r}")


def book_for(v1_book: str | None) -> dict | None:
    for spec in BOOKS:
        if (v1_book or "").startswith(spec["v1_prefix"]):
            return spec
    return None


def clean_citation(text: str | None) -> str:
    """Stray brackets and punctuation out of a Mizan citation; the words are left as printed."""
    out = re.sub(r"[\[\]]", "", clean(text))
    out = re.sub(r",\s*\.", ",", out)
    out = re.sub(r"\s+,", ",", out)
    return re.sub(r"\s+", " ", out).strip(" ,.")


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


def apply_repairs(docs, repairs):
    """Moves English (and its citation) to the narration it translates, and re-files a narration
    under a label its book already uses. Neither writes a word: both only move existing text."""
    by_id = {d["id"]: d for d in docs}
    moves = repairs.get("english_moves", {})
    taken = Counter(m["english_from"] for m in moves.values() if m.get("english_from"))
    if any(n > 1 for n in taken.values()):
        raise ValueError(f"an English assigned twice: {[k for k, n in taken.items() if n > 1]}")
    snapshot = {i: (d["english"], d["cited_from"]) for i, d in by_id.items()}
    for target, move in moves.items():
        if target not in by_id:
            continue
        for field, key, slot in (("english", "english_from", 0), ("cited_from", "cited_from_from", 1)):
            source = move.get(key)
            if not source:
                continue
            if source not in snapshot or by_id[source]["spec"]["book"] != by_id[target]["spec"]["book"]:
                raise ValueError(f"{target}: {key} {source} is not a narration of the same book")
            by_id[target][field] = snapshot[source][slot]

    labels = {}
    for d in docs:
        labels.setdefault((d["spec"]["book"], d["part"], d["chapter"]), (d["part_ar"], d["chapter_ar"]))
    for target, label in repairs.get("relabels", {}).items():
        if target not in by_id:
            continue
        doc = by_id[target]
        key = (doc["spec"]["book"], label.get("part") or "", label["chapter"])
        if key not in labels:
            raise ValueError(f"{target}: relabel to a label the book does not use: {key[1:]}")
        doc["part"], doc["chapter"] = key[1], key[2]
        doc["part_ar"], doc["chapter_ar"] = labels[key]

    # Titles a reader corrected after checking the narrations under them. Titles are
    # metadata, so this may change letters; it may not touch the narration text.
    for target, fields in repairs.get("retitles", {}).items():
        if target not in by_id:
            continue
        if set(fields) - {"part", "part_ar", "chapter", "chapter_ar"}:
            raise ValueError(f"{target}: a retitle may only set part, part_ar, chapter or chapter_ar")
        for field, value in fields.items():
            by_id[target][field] = value or ""
        if "chapter_ar" in fields:
            by_id[target]["chapter_ar_source"] = REVIEW_SOURCE if fields["chapter_ar"] else ""


def convert(rows, overrides, fixes=None, exclusions=None, hold_back=(), title_fixes=None,
            repairs=None):
    fixes, exclusions, title_fixes, repairs = fixes or {}, exclusions or {}, title_fixes or {}, repairs or {}
    picked = [(book_for(r.get("book")), r) for r in rows]
    picked = [(spec, r) for spec, r in picked if spec]
    # Excluded records are dropped only after the repairs: one may still hold the English
    # that belongs to a neighbour. Held-back books leave here, whole.
    held = [(spec, r) for spec, r in picked if spec["book"] in hold_back]
    picked = [p for p in picked if p not in held]

    numbers = Counter((spec["book"], str(r.get("number"))) for spec, r in picked)
    excluded = [{"v1_id": r["_id"], "book": spec["book"], "reason": "held-back",
                 "english": clean(r.get("english"))} for spec, r in held]
    kept = []
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

    for doc in docs:
        for field in ("chapter", "chapter_ar", "part", "part_ar"):
            fixed = title_fixes.get(field, {}).get(doc[field])
            if fixed is None:
                continue
            if field.endswith("_ar") and skeleton(fixed) != skeleton(doc[field]):
                raise ValueError(f"title fix changes the skeleton: {doc[field]!r} -> {fixed!r}")
            doc[field] = fixed

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

    headings = defaultdict(lambda: {"en": set(), "ar": set()})
    for doc in docs:
        book = doc["spec"]["book"]
        headings[book]["en"].update(t for t in (doc["part"], doc["chapter"]) if t)
        headings[book]["ar"].update(t for t in (doc["part_ar"], doc["chapter_ar"]) if t)

    english_headings = {book: tuple(sorted(h["en"])) for book, h in headings.items()}
    for doc in docs:
        book = doc["spec"]["book"]
        doc["id"] = f"{doc['spec']['id_prefix']}:{doc['v1']['number']}"
        doc["arabic"] = clean_arabic(body_text(doc["v1"].get("arabic")), book)
        doc["english"] = clean_english(body_text(doc["v1"].get("english")), book, english_headings[book])
        doc["cited_from"] = clean_citation(doc["v1"].get("source")) if doc["spec"]["cites"] else ""
        for fix in fixes.get(doc["id"], []):
            field = fix["field"]
            known = sorted(headings[book]["ar" if field == "arabic" else "en"])
            verdict = validate_fix(field, doc[field], fix["old"], fix["new"], known,
                                   fix.get("kind", "text"))
            if verdict == "review" and not fix.get("approved"):
                raise ValueError(f"{doc['id']}: fix needs review approval: {fix['old'][:40]!r}")
            old, new = fix["old"], fix["new"]
            if field == "arabic":
                old, new = unicodedata.normalize("NFC", old), unicodedata.normalize("NFC", new)
            doc[field] = re.sub(r"[ \t]+", " ", doc[field].replace(old, new, 1)).strip()
    apply_repairs(docs, repairs)
    for doc in [d for d in docs if d["v1"]["_id"] in exclusions]:
        excluded.append({"v1_id": doc["v1"]["_id"], "book": doc["spec"]["book"],
                         "reason": exclusions[doc["v1"]["_id"]], "english": doc["english"]})
    docs = [d for d in docs if d["v1"]["_id"] not in exclusions]

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
            "arabic": doc["arabic"], "english": doc["english"],
            "source": spec["source"], "source_ar": spec["source_ar"],
        }
        if doc["chapter_ar"]:
            source["chapter_ar"] = doc["chapter_ar"]
            source["chapter_ar_source"] = doc.get("chapter_ar_source") or ARABIC_SOURCE
        if doc["part"]:
            source["part"] = doc["part"]
            if doc["part_ar"]:
                source["part_ar"] = doc["part_ar"]
        if spec["publisher"]:
            source["publisher"] = spec["publisher"]
            source["publisher_ar"] = spec["publisher_ar"]
        if spec["cites"] and doc["cited_from"]:
            source["cited_from"] = doc["cited_from"]
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
    parser.add_argument("--fixes", default=str(Path(__file__).with_name("v1_restore_text_fixes.json")),
                        help="validated text fixes, keyed by new id")
    parser.add_argument("--exclusions", default=str(Path(__file__).with_name("v1_restore_exclusions.json")),
                        help="v1 ids to leave out, with the reason")
    parser.add_argument("--title-fixes", default=str(Path(__file__).with_name("v1_restore_title_fixes.json")),
                        help="whole-title spacing and mark repairs, by field")
    parser.add_argument("--repairs", default=str(Path(__file__).with_name("v1_restore_repairs.json")),
                        help="English moved to its own narration, and narrations re-filed under existing labels")
    parser.add_argument("--hold-back", action="append", default=[],
                        help="a book (new name) to leave out whole; repeatable")
    args = parser.parse_args()

    rows = [json.loads(line) for line in open(args.dump, encoding="utf-8") if line.strip()]
    overrides = json.loads(Path(args.overrides).read_text(encoding="utf-8"))
    fixes = json.loads(Path(args.fixes).read_text(encoding="utf-8"))
    exclusions = json.loads(Path(args.exclusions).read_text(encoding="utf-8"))
    title_fixes = json.loads(Path(args.title_fixes).read_text(encoding="utf-8"))
    repairs = json.loads(Path(args.repairs).read_text(encoding="utf-8"))
    docs, v1_ids, excluded, report = convert(rows, overrides, fixes, exclusions, set(args.hold_back),
                                             title_fixes, repairs)
    write_outputs(docs, v1_ids, excluded, report, Path(args.out_dir), Path(args.report_dir))
    print(json.dumps({"documents": len(docs), "excluded": len(excluded),
                      "blocking": len(report["blocking"]),
                      "untranslated": len(report["untranslated"]),
                      "disambiguated": len(report["disambiguated"])}))
    return 1 if report["blocking"] else 0


if __name__ == "__main__":
    sys.exit(main())
