# Putting the Arabic data into production

The Arabic site is finished in the code and unfinished in the index. Every Arabic string
that is *written by us* — labels, buttons, emails, book introductions, tag names — ships
inside the build and needs no migration. Every Arabic string that belongs to a *narration*
— its chapter, its part, its section, its book name, its source, its notes — lives in
Elasticsearch as an `_ar` field beside the English one, and production has none of them.

This is what has to happen before `/ar` is worth linking to, in what order, and how to
tell it worked.

## Where things actually stand

Measured 2026-10-01, not recalled.

| | local | production |
|---|---|---|
| index behind the `rewayaat_hadith` alias | `rewayaat_hadith_20260909` | `rewayaat_hadith_20260909` |
| documents | 32,519 | 32,519 |
| `book_ar` | 32,519 | **0** |
| `chapter_ar` | 32,516 | **0** |
| `part_ar` | 32,519 | **0** |
| `section_ar` | 32,519 | **0** |
| `source_ar` | 32,519 | **0** |
| `notes` | 0 | 19 |
| `notes_ar` | 0 | **0** |
| index size on disk | 951 MB | 791 MB |

The size row is the cost of this migration: the same index with the `_ar` fields in it is
about **20% larger**. The production nodes sit at 8-16% of their disk, so there is room,
but it is not free.

**Production is being edited while this waits.** The notes were 15 on 2026-09-27 and are
19 now, and the unmatched chapter count moved with them (below). Somebody is editing
Al-Khiṣāl through `/edit`. That is the single most important operational fact here: a
mapping keyed on English drifts away from production every time production is edited, so
**the dry run is only valid for the apply that follows it immediately**.

Two rows in that table are the ones to read twice.

**Production has every Arabic field at zero.** Not partially loaded, not stale — absent.
Nothing has ever been written. A deploy today gives readers an Arabic site whose narration
metadata is entirely English.

**The 15 notes exist only in production.** Local has none, so nothing about them can be
tested here. They are all on Al-Khiṣāl (`Al-Khisal-Saduq:957` and fourteen others), and
they are not short labels: they are a translator's scholarly footnotes, several hundred
words each, citing Lane's Lexicon, Biḥār al-Anwār, Ṣaḥīḥ Muslim and manuscript variants.

## What each field does when its Arabic is missing

Two different behaviours, on purpose, and the difference decides how urgent each one is.

**Metadata falls back to English.** `HadithCardFactory.localised` returns the English when
the `_ar` twin is blank, so an Arabic page with an unmigrated index shows Arabic chrome
around English chapter and book names. Wrong, visibly unfinished, but every page works and
every link resolves.

**Notes do not fall back.** `HadithCardFactory.notesFor` returns the Arabic or nothing at
all. On the Arabic site those 15 notes are simply not rendered. That is deliberate: a
chapter name is a label and a note is three paragraphs of English prose under an Arabic
heading, which is worse than an absence on a page whose purpose is to be Arabic. It does
mean the Arabic reader of those 15 narrations is missing content the English reader has,
silently, which is a thing to decide about rather than discover.

## The migration

Every script takes `--es-host`, and the mappings they apply are committed, so this is
reproducible from a clean checkout. Nothing here copies data out of the local index.

### 0. Reach production

```bash
kubectl port-forward -n elastic-v2 svc/elasticsearch-v2 9201:9200
```

Everything below targets `--es-host http://localhost:9201`. Leaving that off writes to
your laptop and reports success, which is the failure mode this whole document exists to
prevent. Target the **alias** `rewayaat_hadith`, never a concrete index name and never
`rewayaat_updated`, which was the pre-v2 index and no longer exists in production.

### 1. Snapshot

There is a snapshot CronJob in `elastic-v2` (`es-v2-snapshot-*`). Confirm one has run
recently before writing 32,519 documents:

```bash
kubectl get pods -n elastic-v2 | grep snapshot
```

### 2. Dry run

```bash
python3 -m scripts.i18n.translate_tier1 \
    --es-host http://localhost:9201 --apply --dry-run
```

This scrolls the index, matches each document's English value against the committed
mapping, and writes a preview to `scripts/data/<field>_ar_updates.json` without touching
anything. Read the per-field line it prints: `Updates`, `Already has <field>_ar`, and
`No mapping`. A large `No mapping` count means the English in production does not match
the English the mapping was built from, and the run should stop there.

Run against production on 2026-10-01, all five fields came back clean and the run took
**19 minutes** over a port-forward. `chapter` reported 16 unmatched on the first pass;
those are mapped now and it reports none, re-checked with the applier's own matcher:

```
book:      18 translations in mapping — Updates: 32519, Already has: 0, No mapping: 0
source:    11 translations in mapping — Updates: 32519, Already has: 0, No mapping: 0
part:     145 translations in mapping — Updates: 32519, Already has: 0, No mapping: 0
section:  596 translations in mapping — Updates: 32519, Already has: 0, No mapping: 0
chapter: 7738 translations in mapping — Updates: 32519, Already has: 0, No mapping: 0
```

That is the shape to expect. Nineteen minutes is five scrolls of 32,519 documents and no
writes, so budget appreciably more for step 3; it checkpoints, so an interrupted run
resumes rather than restarting. The three are named at the bottom of this document and are
the same three the development index lacks, so production ends up with exactly the
coverage local has. Anything else wants investigating before step 3.

`chapter` needed that investigating. It came back with 272 unmatched — now 3 — which
turned out to be two separate things. Most of it was capitalisation, now handled by a casefold pass in
the applier. The rest was real: **production and development carry different English for
19 Al-Khiṣāl chapter titles** — production says "A believer does not posses Intellect
until he has ten qualities", development says "A Believer without Ten Characteristics Is
Not Intelligent". Same narration, same Arabic, two English renderings, and the mapping had
only ever seen one of them.

No script caused that. Nothing in `scripts/i18n/` writes an English field; `bulk()` writes
`{field}_ar` and nothing else. The two indexes are separate clusters that took different
chapter-title passes at some point, and the development copy is the one the mapping was
built from. The 19 production spellings are now keys in
`chapter_ar_mapping.json` too, resolved by document id — whatever a narration's English
says, it has one Arabic title — so the mapping matches both indexes.

The lesson generalises past this field: **a mapping keyed on English is only as good as
the English it was keyed against, and the index it will run on is not the one it was built
from.** Dry-run every field against production, and read `No mapping` as a question rather
than a rounding error.

### 3. Apply

```bash
python3 -m scripts.i18n.translate_tier1 \
    --es-host http://localhost:9201 --apply
```

It covers `book`, `chapter`, `section`, `part`, `publisher`, `edition` and `source`. It
adds each `_ar` field to the index mapping as it goes, writes in batches of 500, and
checkpoints to `scripts/data/<field>_ar_checkpoint.json`, so an interrupted run resumes
rather than restarting.

`publisher` and `edition` have no mapping file; the run says so and skips them. Neither
field is populated in either index today.

### 4. Verify through the public API, not the index

An index count proves a write landed somewhere. It does not prove a reader sees it.

```bash
curl -s https://hadith.academyofislam.com/ar/books/al-kafi | grep -c 'dir="rtl"'
curl -s https://hadith.academyofislam.com/ar/books/al-kafi/part/the-book-on-virtue-of-knowledge \
  | grep -oE '<h1[^>]*>[^<]*</h1>'
```

The second should print an Arabic chapter title. If it prints English, the write went to
the wrong place or the deploy has not rolled out yet — check both, in that order.

### Rolling back

```bash
python3 -m scripts.i18n.rollback_ar_fields \
    --es-host http://localhost:9201 --fields chapter_ar --dry-run
```

It removes `_ar` fields from every document that carries them. The English is never
touched, so a rollback returns the Arabic site to the fallback behaviour described above
rather than breaking it.

## What this migration does not cover

**`notes_ar` — the 15 Al-Khiṣāl footnotes.** There is no mapping file and no script,
because translating them is editorial work, not a lookup. They are long, they cite
sources by volume and page, and several turn on the wording of an Arabic lexicon entry.
Until somebody writes them, those 15 narrations show no note on the Arabic site. Nothing
in the build fails; `TranslatedDataTest` guards the files in the repo, and this is in the
index.

**`llm_similar.reason_ar` — deliberately dropped.** 43,112 reasons were translated in 719
chunks and never loaded. They are no longer needed: the related-hadith panel does not show
the match reason on the Arabic site in either of its two implementations
(`hub-pages.js` gates on `arabic`, `index.html` on `th:if="${!isArabic}"`), because the
reason is written in English by the model that judged the pair and reads as a machine
note. The translated chunks are still under
`scripts/data/llm_similar_reason_batches/` if that decision is ever reversed.

**`gradings`.** The field does not exist in production. Nothing to translate.

**Every narration has an Arabic chapter title**, as of 2026-10-01. Sixteen did not, in
fourteen distinct spellings, and they are mapped now; `docs/arabic-release.md` records
where each Arabic came from and which three were written by hand.

The number is less interesting than the movement. It was three on 2026-09-27 and sixteen
four days later, and the notes went from 15 to 19 over the same days, all on Al-Khiṣāl.
Production is being edited through `/edit`, so **a mapping keyed on English drifts away
from production continuously**. Expect a handful of unmatched titles again by the time
anyone runs this; the fallback to English is designed for exactly that, so a few delay
nothing. What would be a mistake is reading a stale `No mapping: 0` from this document
instead of running the dry run.

The current list is reproduced by aggregating distinct `chapter.keyword` values out of
production and subtracting the mapping's keys, exact and casefolded, which is the same
match the applier makes.

**Chapter titles by scrape.** `load_thaqalayn_titles.py` rebuilds the Arabic titles by
fetching thaqalayn.net, and it needs `scripts/data/thaqalayn_chapter_titles.json` (2 MB),
which is not committed. Do not reach for it during a migration. The committed
`chapter_ar_mapping.json` holds 7,705 pairs — the same facts, already reconciled — and
step 3 applies them without the network.

## Why the mappings are duplicated at all

`src/main/resources/i18n/*_ar_mapping.json` restates what the documents' own `_ar` fields
hold. Both copies exist because `BookCatalog` names a chapter from a composite aggregation
that never asked for the Arabic. `docs/i18n.md` sets out how to remove the duplication —
a sub-aggregation, measured at 80 ms — and why it has not been done in passing. Until
then the mapping files are what makes this migration reproducible from the repo, which is
the one thing they are unambiguously good for.
