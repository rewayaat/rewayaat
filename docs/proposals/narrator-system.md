# Narrator Biography System — Proposal

> **Status: Phase 1 ran, Phase 2 has been rebuilt, Phases 3-5 were written and deleted.**
> Audited and rebuilt 2026-09-07. Nothing here is serving traffic yet. The section
> [Current State](#current-state-2026-09-07) records exactly what exists and what its quality
> is; tracked in [#88](https://github.com/rewayaat/rewayaat/issues/88).

## Goal

Build a narrator biography system for the Rewayaat Shia hadith database that:

1. **Builds narrator profiles from Rijal books first** — Rijal works are biographical dictionaries organized by narrator, with assessments already structured per-person. Starting here is far more reliable than trying to parse narrators from messy isnad chains.
2. **Stores** narrators in a dedicated Elasticsearch index (`rewayaat_narrators`) with aliases, kunyahs, titles, per-source assessments with direct quotations
3. **Skips** the 14 Infallibles (Imams and Prophets) - no biography pages for them
4. **Matches** built profiles against hadith chains later — once the narrator database exists, linking narrators to hadith is a straightforward matching step
5. **Provides** a dedicated narrator detail page (`/narrator/{id}`) where users can click narrator names
6. **Enables** searching hadiths by narrator across all name variants/aliases

### Key Design Decisions
- **No external LLM APIs** - Claude sub-agents should be used for biography enrichment work
- **No subjective judgments** - only aggregate and synthesize what Rijal sources actually state
- **Per-source assessments** - each narrator has individual assessments from each Rijal work that mentions them, including the direct quotation (Arabic) and a summary (English)
- **Rijal-first approach** — Build a strong narrator collection from biographical dictionaries, then match against hadith. This avoids the fragile, lossy process of parsing isnads to discover narrators.
- **Precision over coverage.** A merged profile that fuses two narrators is worse than two unmerged profiles, and far worse than a missing one. The system publishes reliability gradings attributed by name to named scholars; a wrong merge puts a fabricated attribution on a public page. Every stage below is specified to fail toward *separate* and *absent*, never toward *merged* and *asserted*.

### Rijal Sources (in priority order)

**In corpus (already in ES, can be processed programmatically):**
- Kitab al-Du'afa (Ibn al-Ghada'iri) — 226 entries, already structured as narrator biographies with assessments

**External — downloaded from actual source texts:**

All 7 external books are available as digitized Arabic text online. We download the real source pages and use Claude to parse entries — no reliance on Claude's memory for content.

| Book | Source | Pages | URL |
|------|--------|-------|-----|
| Mu'jam Rijal al-Hadith (Khoei) | usul.ai | 10,924 | `usul.ai/ar/t/mucjam-rijal` |
| Tanqih al-Maqal (Mamaqani) | eshia.ir | 34 vols | `ar.lib.eshia.ir/10510` |
| Rijal al-Kashshi | usul.ai | 94+ | `usul.ai/ar/t/rijal-al-kashshi-maa-taliqat-al-mirdamad` |
| Rijal al-Najashi | usul.ai | 461 | `usul.ai/ar/t/rijal-2` |
| Rijal al-Tusi | usul.ai | 417 | `usul.ai/ar/t/rijal-3` |
| Fihrist al-Tusi | usul.ai | 253 | `usul.ai/ar/t/fihrist-2` |
| Jami' al-Ruwat (Ardabili) | usul.ai | 1,210 | `usul.ai/ar/t/jami-al-ruwat-li-muhammad-ali-al-urdubili` |

**Approach**: Download pages in batches (5 pages per call), Claude parses the real Arabic text into structured profiles with verbatim Arabic quotations. Each profile's `assessment_ar` is a verbatim quote from the source, not a paraphrase.

---

## Data Design: Names, Aliases & Cross-Language Matching

This section addresses how narrator names are stored, normalized, and matched — especially the Arabic↔English bridging problem that affects both data compilation and the UI.

### The Problem

In hadith text, the same narrator appears in many forms:
- **Arabic**: `محمد بن علي بن الحسين بن موسى ابن بابويه القمي`
- **English transliteration (in corpus)**: `` Abu Ja`far Muhammad b. `Ali b. al-Husayn b. Musa b. Babuwayh al-Qummi ``
- **Rijal book Arabic**: may use different ordering, include/exclude lineage depth, add titles
- **Rijal book English**: may use different transliteration schemes (e.g. `ibn` vs `b.`, `al-Baghdadi` vs `al-Baghdādī`)

When a user clicks a narrator name in the English hadith view, we must reliably resolve it to the correct narrator profile (which may have been built primarily from Arabic Rijal sources).

### Name Storage Model

Each `NarratorDocument` must capture **every name variant** the narrator is known by:

```
primary_arabic_name    → Full name as it appears in the most authoritative source
primary_english_name   → Standard transliteration (pick one convention, apply consistently)
arabic_aliases[]       → Every variant found: shortened names, alternative spellings,
                          name with different lineage depth, laqab, nisbah variants
english_aliases[]      → Every English variant: different transliteration styles,
                          shortened forms (e.g. "al-Barqi" vs "al-Barqī"),
                          with/without kunyah, ibn/b. variants
kunyah_arabic          → e.g. أبو جعفر
kunyah_english         → e.g. Abu Jaʿfar (or Abu Ja`far — see transliteration note below)
titles[]               → e.g. القمي, الرازي (nisbahs/laqabs)
normalized_arabic      → Stripped diacritics, normalized alef/ya/ta marbuta (for matching)
normalized_english     → Stripped diacritics, lowercased, ayin/hamza removed (for matching)
```

**`normalized_arabic` and `normalized_english` are computed, never extracted.** They are
deterministic functions of the display names and must be produced by the pipeline's own
normalizer (`NarratorNameMatcher.normalizeArabic` / `normalizeEnglish`), so that the same
name always yields the same key. Asking the extractor to emit them makes the primary
matching key a model output, which is not reproducible and cannot be re-derived after the
fact.

### Name Classes Are Not Interchangeable

Three distinct classes of string are stored, and they must never be pooled:

| Class | Examples | Identifies a person? |
|---|---|---|
| **Names** — `primary_arabic_name`, `arabic_aliases[]` | `محمد بن سنان`, `محمد بن أورمة` | Yes, weakly for short forms |
| **Kunyahs** — `kunyah_arabic` | `أبو جعفر`, `أبو عبد الله` | No — hundreds of narrators share each |
| **Titles/nisbahs** — `titles[]` | `القمي`, `الكوفي`, `البجلي`, `الصفار` | No — these are places, tribes and trades |

Kunyahs and nisbahs are **disambiguators, not identifiers**. They belong in the context
score (below), never in the name index used to generate merge candidates. Indexing them as
names makes `أبو جعفر` a join key across the whole corpus.

### Diacritics Standardization

Arabic and English name fields each need two forms:

**Arabic:**
- **Display form** (`primary_arabic_name`, `arabic_aliases[]`): Preserve diacritics as found in the source. Different Rijal sources may or may not include tashkeel — store as-is from each source.
- **Normalized form** (`normalized_arabic`): Strip all tashkeel (U+064B–U+065F, U+0670, U+06D6–U+06ED), normalize alef variants (أ→ا, إ→ا, آ→ا), normalize ya/alif maqsura (ى→ي), ta marbuta (ة→ه). This is used for matching.

**English:**
- **Display form** (`primary_english_name`, `english_aliases[]`): Use a consistent transliteration convention. The existing corpus uses backtick notation (`` `Ali ``) for ʿayn — keep this as the display standard. Also accept and store IJMES, EI2, and DMG variants as aliases since external Rijal sources use different schemes.
- **Normalized form** (`normalized_english`): NFKD decomposition, strip combining marks, remove ʿ/ʾ/ʻ/apostrophes, lowercase. Used for matching.

### Alias Collection Strategy

Every name variant discovered at **any stage** must be added to the aliases:

**From Rijal sources (Phases 1–2):**
- The narrator's entry heading (usually full name)
- How they're referred to in other narrators' entries (often shortened)
- Kunyah alone (some sources list by kunyah)
- Laqab/nisbah alone (e.g. "البرقي" / "al-Barqī")
- Any alternative names explicitly mentioned in the biographical text

**From hadith chains (Phase 5):**
- How the name appears in isnads across different books (different books use different conventions)
- Shortened forms common in chains (e.g. "عن أبيه" = "from his father" when the father is known)

Aliases are **provenanced**: every alias carries the book and page it came from, so a bad
alias can be traced to its source and a merge can be undone.

### Arabic↔English Bridging

The key challenge: a user reading an English hadith clicks `` `Ali b. Ahmad al-Daqqaq `` — how do we find the narrator profile built from Arabic sources?

**Solution: Paired name collection during compilation.**

Every `NarratorDocument` must have a `primary_english_name` and `english_aliases[]` populated. During compilation:
1. Kitab al-Du'afa entries have **both** Arabic and English text — extract names from both sides and pair them
2. External Rijal sources (Arabic-only) — Claude sub-agents must generate the English transliteration when creating the profile
3. Hadith matching (Phase 5) will discover additional English variants — these get added to `english_aliases[]`

**At UI click time:**
1. User clicks an English narrator name in hadith text
2. Backend normalizes the clicked text using `NarratorNameMatcher.normalizeEnglish()`
3. Searches `rewayaat_narrators` across `normalized_english` and all `english_aliases` (normalized)
4. Falls back to fuzzy Jaro-Winkler match (0.85 threshold) if no exact match
5. Returns the narrator profile (which includes Arabic names, assessments, etc.)

This works because the compilation phase ensured every Arabic-named profile also has English name variants recorded. The reverse also works for Arabic hadith view.

### What This Means for the Compilation Pipeline

When processing any Rijal source (Phase 1 or 2), each narrator entry MUST capture:
1. **Arabic name** (as-is from source) + all Arabic variants
2. **English name** (transliterated from source, or taken from corpus English text if available) + all English variants
3. **Kunyah** in both Arabic and English (if mentioned)
4. **Nisbah/laqab** in both Arabic and English (if mentioned) — add to `titles[]` AND to aliases
5. **Cross-check**: the Arabic and English names must refer to the same person. If processing a bilingual source (like our corpus), verify the names align.

### Extraction Output Contract

The extractor is an LLM, so its output is validated, not trusted. Every profile is checked
against a schema before it is written to the per-book file:

- **Closed key set.** Unknown keys are a hard error on the batch, not a silently dropped
  field. Near-miss keys (`is_doubtual`, `kunyah_ar`, `city_or_ribe`) are the signature of an
  unvalidated pipeline and cost real data.
- **`reliability_grade` is a controlled vocabulary**, not free text. One token per grade,
  with the Arabic term as the canonical value and the English gloss rendered at display
  time:

  | Value | Arabic | Notes |
  |---|---|---|
  | `thiqa` | ثقة | reliable |
  | `saduq` | صدوق | truthful |
  | `hasan` | حسن | good |
  | `majhul` | مجهول | unknown — the source has an entry but no assessment |
  | `daif` | ضعيف | weak |
  | `very_weak` | — | explicit intensifiers (جدا, جداً) |
  | `kadhdhab` | كذاب | liar/fabricator |
  | `ghali` | غالي | extremist |
  | `waqifi` / `fatahi` / `zaydi` | — | sectarian affiliation, not a reliability verdict |
  | `mukhtalaf_fih` | مختلف فيه | sources disagree |
  | `not_assessed` | — | the source mentions the person but issues no verdict |

  `not_assessed` and `majhul` are different facts and must not collapse. A grade meaning
  "an assessment exists" carries no information and is not a permitted value.
- **Latin characters in an Arabic field, or Arabic characters in a key name, fail the batch.**
  These indicate a corrupted generation, and the batch is re-run rather than repaired.
- **Disambiguation pages are not narrators.** Both Khoei and Mamaqani head a page listing
  everyone called حفص; extracted naively it becomes one profile whose 89 "aliases" are 89
  people. A one-token name carrying eight or more aliases is quarantined, never merged.
- **Verbatim quotation check.** `assessment_ar` must be a substring of the downloaded page
  text after whitespace normalization. This is the only defence against a paraphrase being
  published as a quotation attributed to a named scholar.

### Identity Resolution: Reconciling Names Across Books

Different Rijal sources (and hadith chains) refer to the same narrator in different ways. This is the hardest problem in the system. Examples:

- **Depth variation**: `الحسن بن علي بن أبي حمزة` vs `الحسن بن علي` vs `أبو محمد` (same person, shorter references)
- **Attribute vs lineage**: `البرقي` vs `أحمد بن محمد بن خالد` (nisbah vs full name)
- **Kunyah only**: `أبو جعفر` — dozens of narrators share this kunyah
- **Transliteration variation**: `al-Barqī` vs `al-Barqi` vs `al-Barki`
- **Genuinely different people**: multiple `محمد بن علي` who are NOT the same person

**Resolution strategy — layered matching, not pure string comparison:**

**Layer 0: Intra-book consolidation** (automatic, runs before any cross-book work)

A single narrator's entry in a large Rijal work spans many pages, and page-batched
extraction emits one profile per batch. Those fragments are the same entry, and they are
recognisable because they are adjacent *in the file* and contiguous *in pages*. Collapse
them before the book meets any other book.

Layer 0 must not go further than that. Not every repeat of a name inside one book is a
fragment: Khoei and Mamaqani were extracted per **mention**, so a prolific narrator named
inside someone else's entry got his own profile. سهل بن زياد appears at page spans 381,
671, 3961 and 7011 of a book that heads him once. Those are the same person, but that is a
conclusion for the name-matching layers to reach on the evidence, not something Layer 0 may
assume from a shared name.

The distinction matters for the invariants too: per-book uniqueness of a primary name only
holds for books extracted one-profile-per-headed-entry.

**Layer 1: Exact normalized match** (automatic, high confidence)
- Normalize both names (strip diacritics, normalize alef/ya/ta marbuta)
- Candidates are generated from the **name index only** — primary names and aliases.
  Kunyahs and titles are never index keys (see [Name Classes](#name-classes-are-not-interchangeable)).
- A unique candidate is **not** sufficient to merge. Uniqueness means "one profile happens
  to hold this string", not "this is the same person". A merge additionally requires either
  a full-name match (both sides at least three name tokens deep) or a Layer 2 context score
  above the floor. A short-form-only match (`أحمد بن محمد`) never merges on its own.
- **The index is append-only for names the merged profile actually owns.** Aliases absorbed
  from a merge are indexed, which is correct — but combined with an unguarded unique-candidate
  merge it produces single-linkage chaining: A absorbs B's aliases, C matches one of those
  aliases, C merges into A, and the cluster grows without any two members ever having been
  compared. The guards below exist specifically to break that chain.

**Layer 2: Context-augmented matching** (semi-automatic, medium confidence)
- When a name alone is ambiguous (e.g. `محمد بن علي`), use additional context to disambiguate:
  - **Kunyah**: `أبو جعفر محمد بن علي` is different from `أبو القاسم محمد بن علي`
  - **Nisbah/laqab**: `البرقي` vs `القمي` vs `الكوفي`
  - **Teacher/student**: who they narrate from/to narrows identity
  - **Generation/death year**: if known from the profile
  - **Tribe/city**: additional disambiguator
- If name + context aligns with an existing profile → merge (add new aliases)
- If name matches but context conflicts → flag as ambiguous, keep separate
- **Conflicting death years are disqualifying**, not merely low-scoring. Two profiles with
  death years more than one generation apart are different people regardless of name match.

**Layer 3: LLM-assisted judgment** (for ambiguous cases)
- When layers 1-2 are inconclusive, use Claude to judge: "Are these two names the same person given these contexts?"
- Input: both name variants + surrounding biographical text from each source
- Output: same/different + reasoning
- Run via Claude sub-agents, consistent with the no-external-API decision above.
- **Layer 3 is not optional.** Deferred cases that are never judged do not stay neutral —
  they were provisionally added as new profiles, so an unjudged backlog silently ships as
  duplicate narrators. A run is not complete until the queue is empty.
- **Large same-name groups are one task, not many pairs.** Where a name is held by more
  than a handful of profiles, pairwise questions are the wrong shape: kunyah is absent on
  82-92% of same-name profiles, so most pairs carry no evidence either way and the ranking
  between candidates is ranking noise. أحمد بن محمد spans 96 profiles with five kunyahs and
  six nisbahs (several people); محمد بن سنان spans 64 with one kunyah (one person). Neither
  is separable by rule, and both are answerable as a single question: *partition these
  profiles into people*. That is 519 tasks rather than 11,149 pairwise judgments.

**Layer 4: Manual review queue** (edge cases)
- Cases where even LLM judgment is uncertain get flagged for human review
- Should be rare if layers 1-3 work well

**Merge invariants.** These are checked after every merge and after the run as a whole; a
violation stops the pipeline rather than being recorded as a statistic:

- A merged profile holds at most **one entry per source book per page range**. Absorbing 88
  profiles from one book means Layer 0 did not run.
- A merged profile's aliases must not contain the primary name of a *different* merged
  profile from the same book. Within one book, two headed entries are two people.
- Cluster size is capped. A profile that would exceed the cap diverts the incoming profile
  to Layer 4 instead of growing. Genuinely famous narrators appear in 8 books, not 50.
- Every merge records the layer, the matched key and the context score, so the decision can
  be audited and reversed without re-running the pipeline.

**Practical implication for compilation:**
- Phase 1 produces standalone per-book profiles — no cross-book matching, no alias sharing
- Phase 2 applies the resolution strategy above when merging all per-book files
- Each successful merge adds aliases from both sides to the unified profile
- The merged alias list is what gets used for Phase 5 (hadith matching)

**Why the merged alias list is load-bearing.** `NarratorService.searchHadithsByNarrator`
builds its query from every name variant on the profile. Aliases are therefore not
decoration — each one is a search term fired at the hadith corpus. A profile carrying a
foreign narrator's name as an alias will return that narrator's narrations under the wrong
biography. Merge precision is a correctness property of the narrations feature, not just of
the biography page.

---

## Pipeline

### Phase 1: Per-Book Extraction (independent, parallel)

Process each Rijal book independently into its own narrator profile file. No cross-book matching at this stage — each book produces a standalone set of profiles. This keeps extraction simple and lets us run books in parallel.

**1A: Kitab al-Du'afa (Ibn al-Ghada'iri) — In corpus**

226 entries in the Rewayaat ES index. Each entry is one narrator biography with name, lineage, kunyah, and assessment. The `chapter` field is the narrator's name. Both Arabic and English text available.

Steps:
1. Extract all 226 entries from batch files for book `Kitāb al-Ḍuʿafāʾ`
2. Parse each entry into a narrator profile:
   - Narrator name (Arabic + English from paired text)
   - Kunyah, titles, nisbahs
   - All aliases mentioned in the entry (~19% of entries have explicit aliases: "known as", "called", laqabs, nicknames)
   - Direct Arabic quotation + English summary as a `SourceAssessment`
   - Reliability grade from explicit keywords (ضعيف/weak, كذاب/liar, غاليا/ghali, واقف/waqifi, etc.)
   - Doubtful flag + reason
3. Skip Imams/Prophets (14 Infallibles)
4. Skip introduction/preamble entries (entry 1 is not a narrator)
5. Write output to `tmp/narrators_book_duafa.json`

**Expected output**: ~220 narrator profiles

**1B-G: External Rijal Books — Download & Parse**

Each book is processed the same way:
1. Download actual Arabic text page-by-page from usul.ai (or eshia.ir for Mamaqani)
2. Batch pages (5 per call) and send to Claude for structured extraction
3. Claude parses the real source text into narrator profiles with verbatim Arabic quotations
4. No reliance on Claude's memory for content — all data comes from the actual book text
5. Validate every profile against the [output contract](#extraction-output-contract) before writing

**Outputs:** `tmp/narrators_book_{slug}.json` for each book

**Batch sizing is per-book, not global.** Dense pages of bare names (Rijal al-Tusi, Jami'
al-Ruwat) overflow the output budget at five pages per call and the model truncates the
list rather than erroring. A book whose yield is implausibly low for its page count has
silently truncated and must be re-run at a smaller batch size — page-count coverage alone
does not prove extraction succeeded.

### Phase 2: Cross-Book Aggregation & Deduplication

Merge all per-book profile files into a single unified narrator database. This is where the identity resolution strategy (see above) is applied.

Steps:
1. **Load all** `tmp/narrators_book_*.json` files
2. **Pass 0 — Intra-book consolidation**: collapse page-batch fragments of the same entry
3. **Pass 1 — Exact normalized matching**: name index only, with the full-name-or-context guard
4. **Pass 2 — Context-augmented matching**: kunyah + nisbah + teacher/student + death year
5. **Pass 3 — Claude sub-agent batch**: resolve every deferred pair; the queue must drain
6. **Flag uncertain cases** for manual review
7. **Assert the merge invariants**; a violation fails the run
8. **Write output** to `tmp/narrators_merged.json`

### Phase 3: Import to Elasticsearch

1. `NarratorIndexManager.createIndexIfNotExists()` builds `rewayaat_narrators` (index name
   overridable via `NARRATOR_INDEX`)
2. Bulk index all merged narrator documents via `indexDocuments` / `indexDocumentsWithOverwrite`
3. Verify index health and document counts

### Phase 4: Frontend - Narrator Detail Page

**`src/main/resources/templates/narrator.html`**

Thymeleaf + Vue.js page that:
- Loads narrator data via `/v1/narrators/{id}` API
- Displays narrator biography, names, aliases, kunyah, titles
- Shows per-source Rijal assessments in a structured format (source name, direct Arabic quote, English summary)
- Lists hadiths narrated by this person via `/v1/narrators/{id}/narrations`
- Uses the same search result UI as the main page (Bootstrap 5 + Bootswatch Materia)
- Follows existing patterns from `index.html` and `edit.html`

Served by `NarratorController`, which exposes the `/narrator/{id}` page route alongside the
two JSON endpoints.

### Phase 5: Match Narrators to Hadith

Once the narrator database is built, link narrators to hadith. This is cheaper than it
looks, because the corpus already separates chain from matn:

- `semantic_matn_source` holds the Arabic with the isnad stripped, on 32,516 of 32,519
  hadith. The chain is the difference between `arabic` and the matn — no new isnad parser
  is needed to obtain chain text.
- `HadithDisplaySegmenter` already splits chain from content in Java for display.

Two levels, in order:

**5A — Name-variant search (no new data).** `NarratorService.searchHadithsByNarrator` runs a
query string built from the profile's name variants against the hadith index. This ships
with Phase 4 and requires no backfill. Its precision is exactly the precision of the merged
alias list.

**5B — Materialized links (later).** Match narrator names against chain text, write a
`narrator_ids` field onto hadith documents, make narrator names clickable in search results
via a Vue directive, and style the links in `manuscript.css`. This buys exhaustiveness and
speed over 5A, and is only worth doing once merge precision is established.

---

## Current State (2026-09-07)

### What exists

**Data** — under `tmp/` (symlinked to `/mnt/share/rewayaat-backup/tmp/`):

| File | Contents |
|------|----------|
| `tmp/narrators_book_{slug}.json` | Phase 1 extraction, as produced — 42,076 profiles |
| `tmp/narrators_normalized/{slug}.json` | Contract-normalized — 42,046 profiles |
| `tmp/narrators_merge/merged.json` | **Current** — 28,463 merged profiles |
| `tmp/narrators_merge/name_group_tasks.json` | 519 partition tasks covering 6,417 profiles |
| `tmp/narrators_merge/deferred.json` | 3,095 pairwise deferrals |
| `tmp/narrators_merge/quarantine.json` | 7 disambiguation pages held out |
| `tmp/narrators_merge/violations.json` | 66 invariant violations |
| `tmp/narrators_merged.json` | **Superseded** — the 2026-06 merge, 29,305 profiles; do not index |

**Code** — the Phase 1-2 pipeline is rebuilt in the tree:

| Script | Does |
|---|---|
| `scripts/narrators/narrator_schema.py` | normalizers, reliability vocabulary, Infallible registry |
| `scripts/narrators/normalize_extraction.py` | the output contract, applied retroactively |
| `scripts/narrators/merge_narrator_profiles.py` | Layers 0-2, invariants, Layer 3 task generation |
| `scripts/narrators/audit_narrator_quality.py` | per-book completeness audit |

Phases 3-5 remain deleted, all recoverable from git:

| Component | Commit | Notes |
|---|---|---|
| `parse_duafa_narrators.py`, `parse_external_rijal.py` | `681d7f3` | Phase 1 |
| `merge_narrator_profiles.py`, `merge_narrator_layer3.py` | `681d7f3` | Phase 2 |
| `NarratorIndexManager`, `NarratorService`, `NarratorController` | `9b6adb6^` | Phases 3-5A |
| `NarratorDocument`, `SourceAssessment`, `NarratorNameMatcher`, `ImamProphetRegistry` | `9b6adb6^` | model + matching |
| `extract_chains_for_narrators.py` | `0f5a853` | superseded by `semantic_matn_source` |

`scripts/narrators/audit_narrator_quality.py` is the only piece still in the tree, and it
still runs against the per-book data.

`narrator.html` was never written. There is no `rewayaat_narrators` index. Nothing is
wired into the running app.

The Java was deleted on 2026-06-09 — a day before the merge finished and five days before
Layer 3 ran. Phases 3, 4 and 5A are therefore closer to done than the phase numbering
suggests: the backend is a revert, and the gap is one Thymeleaf template.

### Quality audit

The per-book extraction is broadly sound. **The merge is not, and the merged file must not
be indexed as it stands.**

**Layer 3 never finished.** 11,149 pairs were deferred; 3,976 were judged (36%). The other
7,173 were provisionally added as new profiles and left there, so the merged file carries
several thousand unresolved duplicates.

**The merge over-clusters through title and kunyah keys.** `_index_names` indexed titles and
nisbahs into the same inverted index as names, `find_candidates` also probed by kunyah, and
a single candidate merged unconditionally with no similarity or context check. The result is
textbook single-linkage chaining:

| Cluster size | Merged profiles | Source profiles absorbed |
|---|---|---|
| ≥2 sources | 5,505 | 18,274 (43.4%) |
| ≥5 sources | 683 | 6,675 (**15.9%**) |
| ≥10 sources | 180 | 3,579 (8.5%) |
| ≥20 sources | 43 | 1,847 (4.4%) |
| largest | 1 | 195 |

The largest, `محمد بن سنان`, carries 129 aliases including `محمد بن أورمة`,
`أحمد بن هلال العبرتائي`, `مؤمن الطاق` and `محمد بن الحسن بن شمون` — ten of its aliases are
the primary names of *other* Du'afa entries. Another fuses `أحمد بن محمد بن عيسى الأشعري`,
`محمد بن يحيى العطار` and `الصفار` into one person. Sampled clusters of 5-9 sources are
mostly legitimate spelling variants; the damage concentrates in the ~180 largest.

Small bilingual sources were hit hardest, because they were processed first and seeded the
index: 57.7% of Du'afa and 43.5% of Kashshi profiles landed in a ≥5-source cluster, against
18.4% for Khoei and 8.4% for Mamaqani.

**The merge was simultaneously too conservative across books.** Only 3,438 of 29,305
profiles (11.7%) drew on more than one book, and 23,800 had exactly one assessment. Khoei's
Mu'jam alone should cover nearly every narrator in Najashi, Tusi and Kashshi. Aggressive
chaining on nisbahs coexisted with near-absent genuine cross-book linkage.

**Khoei and Mamaqani were extracted per mention, not per entry.** 21,938 Khoei profiles
across 14,795 distinct normalized names. The original reading — that these were page-batch
fragments — is wrong: سهل بن زياد appears at page spans 381, 671, 3961 and 7011 of a book
that heads him once, so most repeats are mentions inside other narrators' entries. Only 780
Khoei profiles and 293 Mamaqani profiles are true batch fragments. The rest are real
same-person mentions that the name-matching layers must resolve on evidence, and they are
what fed the deferred queue.

**Two books are effectively missing.** Rijal al-Tusi yielded 123 profiles from 417 pages
against roughly 8,000 entries (92 batch errors, documented at the time as needing a re-run
at `--batch-size 2`, never re-run). Jami' al-Ruwat yielded 1,796 from 1,210 pages, an order
of magnitude short, from the same truncation failure.

**The output contract was not enforced.** 289 invented keys across 42,076 profiles (0.7%):
`is_doubtual`, `is_doubtous`, `is_doubtious`, `is_doubtualble`, `doubtual_reason`,
`doubtous_reason`, `kunyah_ar`, `kunyah_English`, `city_or_ribe`, `assessment_arabic`,
`assessment_english`, `narrated_from_extra`, and `death_year_hijري` — an identifier with
Arabic letters spliced into it. Small in count, but every one was invisible to the merge, so
that data was dropped. Seven Arabic names contain Latin fragments (`محمد بن يحيى العطARN`).

**`reliability_grade` was free text: 88 distinct values.** `unknown (majhul)` (17,107) beside
bare `unknown` (1,349); `reliable (thiqa)` beside `reliable`; `ghali` beside `ghālī`. And
`assessed` — 10,190 occurrences, roughly a quarter of all grades — which asserts only that
an assessment exists. Now 12 canonical grades on one axis and 8 doctrinal flags on another.

**The best disambiguator is missing.** `death_year_hijri` is filled on 2.8% of Khoei profiles
and 9.6% of Mamaqani, and kunyah is absent on 82-92% of same-name profiles. Layer 2 was
running on almost nothing, which is why the large name groups are a Layer 3 problem rather
than a scoring problem.

**Layer 4 never ran.** 35 entries in the old review queue.

**Layer 3 used the Anthropic API directly**, against the no-external-LLM-APIs decision.

### Result of the rebuild

Steps 1-3 below are done. Rebuilt from the same per-book extraction, nothing re-downloaded:

| | 2026-06 merge | Rebuilt |
|---|---|---|
| Merged profiles | 29,305 | 28,463 |
| Largest cluster | 195 source profiles | 18 |
| Clusters of 20+ | 43 | 0 |
| Absorbed into clusters of 5+ | 15.9% | 11.3% |
| Most aliases on one profile | 129 | 34 |
| Profiles drawing on >1 book | 11.7% | 16.5% |
| Unresolved, for Layer 3 | 11,149 pairwise (36% judged) | 519 partition tasks + 3,095 pairs |
| Invariant violations | not checked | 66 |

Less over-merging and more genuine cross-book merging at the same time, which is the
combination that matters: the old merge was chaining on nisbahs while failing to connect
the same narrator across sources.

The 66 remaining violations are all `alias_is_foreign_primary_name` on headed-entry books —
real signal, and small enough to inspect individually. They are the natural input to
Layer 4.

### Remediation order

Nothing here requires re-downloading a page except step 5.

1. ~~**Enforce the output contract retroactively.**~~ Done — `normalize_extraction.py`,
   runs clean under `--strict`.
2. ~~**Implement Layer 0.**~~ Done — 1,086 batch fragments collapsed. Smaller than expected,
   because most Khoei and Mamaqani repeats are mentions rather than fragments.
3. ~~**Rewrite the merge.**~~ Done — see the table above.
4. **Drain Layer 3**, via sub-agents: 519 name-group partition tasks, then the 3,095
   pairwise deferrals.
5. **Re-run Tusi and Ardabili extraction** at a smaller batch size. Rijal al-Tusi at 123
   profiles is a hole the system cannot ship around.
6. **Phase 3** — restore `NarratorIndexManager` and import.
7. **Phase 4** — restore `NarratorController` / `NarratorService`, write `narrator.html`.
8. **Phase 5A** ships with Phase 4. **Phase 5B** after precision is established.

An alternative shortest path to something demonstrable: build Phases 3-5A against
**Najashi + Fihrist + Du'afa + Kashshi only** — 2,472 profiles, the cleanest extraction and
the most-cited narrators — and fold Khoei, Mamaqani, Tusi and Ardabili in once the merge is
fixed.

### Phase 1 extraction record

| Book | Slug | Scope | Profiles | Pages | Assessment |
|------|------|-------|----------|-------|------------|
| Kitab al-Du'afa | `duafa` | 224 entries | 222 | 224/224 | clean; 2 entries errored |
| Rijal al-Kashshi | `kashshi` | 94 pages | 209 | 94/94 | clean; 1 error |
| Fihrist al-Tusi | `fihrist` | 253 pages | 731 | 253/253 | clean; 5 errors |
| Rijal al-Najashi | `najashi` | 461 pages | 1,310 | 461/461 | clean |
| Rijal al-Tusi | `tusi` | 417 pages | 123 | 412/417 | **truncated** — 92 errors, needs re-run at `--batch-size 2` |
| Jami' al-Ruwat | `ardabili` | 1,210 pages | 1,796 | 1,210/1,210 | **truncated** — yield an order of magnitude short |
| Mu'jam Rijal al-Hadith | `khoei` | 10,924 pages | 21,938 | 10,924/10,924 | per-**mention**, not per-entry; ~15,700 real entries |
| Tanqih al-Maqal | `mamaqani` | 34 vols | 15,747 | 15,873 batches | per-**mention**; volume coverage unverified |

Total before merging: 42,076.

Operational lessons worth keeping:

- Process one book at a time; parallel runs corrupted shared checkpoints.
- Checkpoint every batch — page downloads fail intermittently and the run must survive it.
- Dense pages of bare names truncate on output; drop the batch size rather than the page.
- Page-count coverage is not extraction coverage. Compare yield against the book's known
  entry count before declaring a book done.
