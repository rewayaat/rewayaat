# Text quality for the restored v1 books

Sonnet agents review the narrations of Ghurar al-Ḥikam and Mīzān al-Ḥikma, restored from the v1 snapshot (`plans/2026-10-19-restore-v1-books-phase-1-5.md`). **Agents report and propose. They never write the data.**

Every proposal goes through `validate_fix` in `scripts/ingest/convert_v1_books.py` before it can reach the batches:
- **Arabic:** a fix is refused if it would change the text's letters (its skeleton). A glyph that becomes an honorific needs a reader's approval.
- **English:** a fix is refused if it would change a word.

Semantic problems (misaligned, truncated, merged) are never fixed by an agent. They go to a review list for a person to decide.

## Batches

```
/mnt/share/rewayaat-backup/tmp/v1-restore/quality-full/
  ghurar/batch_001..074.jsonl   150 narrations each   → ghurar/out/batch_NNN.jsonl
  mizan/batch_001..047.jsonl    100 narrations each   → mizan/out/batch_NNN.jsonl
  titles/titles_001..007.jsonl  300 title pairs each  → titles/out/titles_NNN.jsonl
```

Batches are cut from the cleaned converter output. The rule-based cleaners have already run (RLM marks, doubled vowels, pasted `(غرر الحكم: …)` citations, split `أ نّ`, English spacing). Resume = the first input without an output file. An empty output file means "reviewed, nothing found". Run 8 agents at a time, `model: "sonnet"`.

## Narration prompt

`{BOOK_CONTEXT}`, `{INPUT}`, `{OUTPUT}` and `{CHUNKS}` are filled per batch. Ghurar uses 3 chunks of 50. Mizan uses 2 chunks of 50.

> You are auditing data quality in hadith narrations restored from an old database export. You do NOT fix anything. You report, and for a narrow set of mechanical defects you propose an exact edit.
>
> {BOOK_CONTEXT}
>
> Input: `{INPUT}`. One JSON object per line, with fields: id, number, part, part_ar, chapter, chapter_ar, arabic, english, cited_from, prev_arabic_tail (last 80 characters of the previous narration's Arabic), next_arabic_head (first 80 characters of the next one's).
>
> Output: `{OUTPUT}`. One JSON object per line, one line per narration that has at least one issue; narrations without issues get no line. Create the file even when it stays empty. Shape:
> `{"id": "...", "issues": [{"type": "<type>", "field": "arabic|english|chapter|chapter_ar|part|part_ar|cited_from", "evidence": "<short quote>", "proposed": {"old": "<exact substring of the field>", "new": "<replacement>"} or null, "confidence": "high|medium|low"}]}`
>
> Issue types, only these:
> - misaligned: the English and the Arabic are not the same narration.
> - truncated-en / truncated-ar: that side stops mid-sentence or lacks a part the other side has (including a missing word that inverts the meaning, e.g. a missing «لا»). An ellipsis on both sides is the book's own abridgement, not truncation.
> - merged: a field holds more than one narration, or text belonging to the previous or next narration (judge with prev_arabic_tail / next_arabic_head).
> - heading-bleed: a chapter or section heading has been pasted into the text, or narration text into a title field.
> - ocr-arabic: Arabic words split by a space or run together, letters out of order, garbage characters, a diacritic stuck to the wrong letter or floating alone.
> - latin-in-arabic: Latin letters, digits or symbols inside the Arabic text.
> - title-mismatch: chapter and chapter_ar (or part and part_ar) do not mean the same thing.
> - citation-defect: cited_from is not a citation, or is garbled.
> - english-defect: a mechanical English defect only, i.e. double spaces, a space before punctuation, broken encoding such as "Im?m". NOT lost apostrophes, NOT style, NOT translation choices, NOT spelling of names.
> - other: anything else clearly wrong with the record; explain it in evidence.
>
> Do not report:
> - the speaker prefix (e.g. «الإمامُ عليٌّ (عَلَيهِ الّسَلامُ) :» / "Imam Ali (AS) said,");
> - a chapter title ending in an Arabic title in parentheses, e.g. "Generosity (الجود)" (deliberate: it tells apart two chapters with the same English name);
> - a missing chapter_ar.
>
> Rules for "proposed":
> - Propose an edit only for ocr-arabic, latin-in-arabic, heading-bleed (in arabic or english only) and english-defect. Every other type has "proposed": null.
> - "old" must be an exact substring of the field, as short as possible: the broken word or span, not the whole text. It must occur only once in the field. If the span repeats, include a neighbouring word to make it unique.
> - An Arabic edit may only rejoin or split words, fix spacing, move, remove or correct diacritics, and remove stray Latin or symbol characters. It must NEVER add, remove or replace an Arabic letter, except that a glyph standing for an honorific may become that honorific (e.g. "عليه السلام"). A fix beyond these limits gets "proposed": null and a description.
> - An English edit may only fix spacing, punctuation spacing or broken encoding, or remove a pasted heading. Never reword.
>
> Process: read the input in {CHUNKS} chunks of 50 lines (Read with offset/limit). After each chunk, append that chunk's result lines to the output file. Read both languages of every narration carefully. Do not use the web. Do not modify the input. Your final reply is one line of JSON: `{"reviewed": N, "with_issues": N, "by_type": {...}}`.

`{BOOK_CONTEXT}`, Ghurar:
> Book: Ghurar al-Ḥikam (sayings of Imam Ali compiled by al-Āmidī; English by Ansariyan Publications). Each entry is one short saying, usually starting "Imam Ali (AS) said," / «الإمامُ عليٌّ (عَلَيهِ الّسَلامُ) :». No chains of narrators. Chapters are topics; there are no parts.

`{BOOK_CONTEXT}`, Mizan:
> Book: Mīzān al-Ḥikma (the abridged "Scale of Wisdom" by Muhammadi Rayshahri; English by ICAS Press). A topical compilation: each narration is quoted from an earlier book named in cited_from (e.g. "Al-Kafi, volume:2, p. 491, number:9"). Narrations start with the speaker, e.g. «الإمامُ الصّادقُ (عَلَيهِ الّسَلامُ):» / "Imam al-Sadiq (AS) said,". No chains of narrators. part = topic, chapter = sub-topic.

## Title prompt

> Input: `{INPUT}`. Each line is `{"book", "part", "part_ar", "chapter", "chapter_ar"}` from Ghurar al-Ḥikam (topic chapters, no parts) or Mīzān al-Ḥikma (part = topic, chapter = sub-topic; the Arabic is Rayshahrī's own, fully vowelled). Check that chapter and chapter_ar mean the same thing, and the same for part and part_ar. A chapter ending in "(Arabic)" is deliberate. Judge only the text before the parenthesis.
>
> Output: `{OUTPUT}`. Create it even if empty. One line per pair that does NOT match: `{"book", "field": "chapter|part", "english", "arabic", "verdict": "mismatch|partial", "suggested_arabic": "<a short Arabic title in the book's style, or null when unsure>", "note": "<one sentence>"}`. "partial" means one side says noticeably more or less than the other. Translation nuance is not a mismatch. Report each distinct part pair once, even though parts repeat across lines. Do not use the web. Final reply: one line `{"checked": N, "mismatch": N, "partial": N}`.

## After the agents

`scripts/ingest/merge_v1_quality.py`:
- Each proposal goes through `validate_fix`.
  - auto + high/medium confidence → `scripts/ingest/v1_restore_text_fixes.json`
  - review (glyph → honorific) → listed for approval
  - refused or low confidence → listed with the reason
- Semantic issues and title verdicts → `review.md`, for the person who decides keep, exclude or hand-fix.

## Mishkāt: checking the edition pairs

Mishkāt's v1 Arabic is replaced by the OpenITI edition's (`scripts/ingest/align_mishkat_openiti.py`). An agent then checks that each v1 English translates the edition Arabic it was paired with. `scripts/ingest/review_mishkat_edition.py prepare` cuts 20 batches of 96 into `quality-full/mishkat/`, and `merge` applies the verdicts (the script's docstring lists what each verdict does). One line per narration is required, so a batch with a missing line can be resumed by finding the ids.

> You check pairs of an English translation and an Arabic hadith text from *Mishkāt al-Anwār fī Ghurar al-Akhbār* (al-Ṭabrisī). The English is the published translation ("The Lamp Niche for the Best Traditions"). The Arabic comes from a clean edition, and was paired with the English by machine. Your job is to rule on each pair. You do NOT rewrite the translation.
>
> Input: `{INPUT}`. One JSON object per line: id, number, chapter, kind (how the machine paired it: auto, weak, positional, shared, span), english, arabic (the edition text proposed for this English), v1_arabic (the damaged Arabic the English was originally stored with — a hint only), prev_english and next_english (the neighbours' English, to spot an English that belongs to a neighbour), and, on some lines, edition_full (the whole edition passage that `arabic` was cut from, or that holds more than one saying).
>
> Output: `{OUTPUT}`. Exactly one JSON line per input line, same order:
> `{"id": "...", "verdict": "match|span|omission|english-truncated|mismatch", "arabic_span": "<only for span>", "english_fixes": [{"old": "...", "new": "..."}], "note": "<one short sentence, only when not match>"}`
>
> Verdicts:
> - match — the English translates the Arabic. A free translation, a speaker named differently ("God's Prophet" for «وقال (صلى الله عليه وآله)»), a dropped chain of narrators or a dropped book name ("من كتاب المحاسن") is still a match.
> - span — the English translates only part of the Arabic (or of edition_full, when given): another saying sits before or after it. Put that part in arabic_span, copied character for character from `arabic` or `edition_full`, as whole sentences.
> - omission — one side lacks a clause or sentence the other has, yet each still reads as a complete narration and the meaning is not inverted.
> - english-truncated — the English stops mid-sentence, or lacks words so that its meaning differs from the Arabic (e.g. a missing negation).
> - mismatch — the English is a different narration (often the neighbour's).
>
> english_fixes: only a chapter or section heading pasted into the English (e.g. "… Khadijeh cry. On Faith, Islam, and Related Issues Khadijeh agreed …") — "old" is the exact span including the heading, "new" the same span without it. Also a glued footnote digit ("Faith1" → "Faith") or doubled spaces. Never reword, never fix spelling or style. Usually the list is empty.
>
> Process: read the input in 3 chunks of 32 lines (Read with offset/limit). After each chunk, append its 32 result lines to the output file. You must rule on ALL lines. Do not use the web. Do not modify the input. Final reply: one line of JSON `{"ruled": N, "by_verdict": {...}}`.
