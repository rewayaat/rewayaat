# Narrator Extraction & Biography System - Status Document

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
- **No subjective judgments** - only aggregate what Rijal sources actually state. No synthesis, no picking "most authoritative" — merge everything from all sources, preserve every assessment and biographical detail with its exact provenance (book + page). When sources disagree, all views are shown side-by-side.
- **Per-source assessments** - each narrator has individual assessments from each Rijal work that mentions them, including the direct quotation (Arabic) and a summary (English)
- **Rijal-first approach** — Build a strong narrator collection from biographical dictionaries, then match against hadith. This avoids the fragile, lossy process of parsing isnads to discover narrators.
- **Full provenance** — Every piece of data in a merged narrator profile must be traceable to its exact source book and page. No synthesized or unattributed information. Users must be able to see exactly which Rijal work and which page every assessment, alias, biographical detail, and reliability grade comes from. When sources conflict (e.g. one book says "reliable" and another says "weak"), all variants are preserved with their respective attributions — no overall grade is computed.

### Rijal Sources

All sources are treated equally — no priority ranking. Every book's assessment and biographical data is preserved with full provenance.

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

**Approach**: Download pages in batches (5 pages per Claude call), Claude parses the real Arabic text into structured profiles with direct quotations. Each profile's `assessment_ar` is a verbatim quote from the source, not a paraphrase.

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
primary_arabic_name    → Full name as it appears in the first source that mentions this narrator
primary_english_name   → English transliteration from the first source that mentions this narrator
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

### Identity Resolution: Reconciling Names Across Books

Different Rijal sources (and hadith chains) refer to the same narrator in different ways. This is the hardest problem in the system. Examples:

- **Depth variation**: `الحسن بن علي بن أبي حمزة` vs `الحسن بن علي` vs `أبو محمد` (same person, shorter references)
- **Attribute vs lineage**: `البرقي` vs `أحمد بن محمد بن خالد` (nisbah vs full name)
- **Kunyah only**: `أبو جعفر` — dozens of narrators share this kunyah
- **Transliteration variation**: `al-Barqī` vs `al-Barqi` vs `al-Barki`
- **Genuinely different people**: multiple `محمد بن علي` who are NOT the same person

**Resolution strategy — layered matching, not pure string comparison:**

**Layer 1: Exact normalized match** (automatic, high confidence)
- Normalize both names (strip diacritics, normalize alef/ya/ta marbuta)
- If normalized names match exactly → same person
- Also check against all existing aliases for the profile

**Layer 2: Context-augmented matching** (semi-automatic, medium confidence)
- When a name alone is ambiguous (e.g. `محمد بن علي`), use additional context to disambiguate:
  - **Kunyah**: `أبو جعفر محمد بن علي` is different from `أبو القاسم محمد بن علي`
  - **Nisbah/laqab**: `البرقي` vs `القمي` vs `الكوفي`
  - **Teacher/student**: who they narrate from/to narrows identity
  - **Generation/death year**: if known from the profile
  - **Tribe/city**: additional disambiguator
- If name + context aligns with an existing profile → merge (add new aliases)
- If name matches but context conflicts → flag as ambiguous, keep separate

**Layer 3: LLM-assisted judgment** (for ambiguous cases)
- When layers 1-2 are inconclusive, use Claude to judge: "Are these two names the same person given these contexts?"
- Input: both name variants + surrounding biographical text from each source
- Output: same/different + reasoning
- This handles the genuinely hard cases that rules can't

**Layer 4: Manual review queue** (edge cases)
- Cases where even LLM judgment is uncertain get flagged for human review
- Should be rare if layers 1-3 work well

**Practical implication for compilation:**
- Phase 1 produces standalone per-book profiles — no cross-book matching, no alias sharing
- Phase 2 applies the resolution strategy above when merging all per-book files
- Each successful merge adds aliases from both sides to the unified profile
- The merged alias list is what gets used for Phase 5 (hadith matching)

---

## Pipeline

### Phase 1: Per-Book Extraction (independent, parallel)

Process each Rijal book independently into its own narrator profile file. No cross-book matching at this stage — each book produces a standalone set of profiles. This keeps extraction simple and lets us run books in parallel.

**1A: Kitab al-Du'afa (Ibn al-Ghada'iri) — In corpus**

**Status: COMPLETE (222 profiles)**

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

**Status: COMPLETE (41,854 profiles across 7 books)**

Each book is processed the same way:
1. Download actual Arabic text page-by-page from usul.ai (or eshia.ir for Mamaqani)
2. Batch pages (5 per call) and send to Claude for structured extraction
3. Claude parses the real source text into narrator profiles with verbatim Arabic quotations
4. No reliance on Claude's memory for content — all data comes from the actual book text

**Scripts:**
- `scripts/parse_duafa_narrators.py` — Phase 1A (in-corpus Du'afa)
- `scripts/parse_external_rijal.py` — Phases 1B-G (download + parse from source)

**Outputs:** `tmp/narrators_book_{slug}.json` for each book

### Phase 2: Cross-Book Aggregation & Deduplication

**Status: COMPLETE (29,305 merged profiles from 41,854 source profiles)**

Merge all per-book profile files into a single unified narrator database. This is where the identity resolution strategy (see above) is applied.

**Layer 1 (Exact normalized match):** Completed — merged profiles with identical normalized names automatically.
**Layer 2 (Context-augmented match):** Completed — used kunyah, nisbah, teacher/student networks, generation to disambiguate common names.
**Layer 3 (LLM-assisted judgment):** Completed — 3,976 ambiguous pairs judged by Claude, 1,502 high-confidence merges applied. 0 self-merges, 0 circular refs, 0 unresolved chains.

**Provenance Requirements:**

Every field in a merged profile must be traceable to its source book and page. The merged profile is not a new composition — it is an aggregation with clear attribution:

- **Per-field provenance**: Each biographical field (kunyah, city, death year, generation, etc.) tracks which book and page it came from (`{"book": "najashi", "page": 196}`)
- **Per-assessment provenance**: Every `source_assessment` preserves the book name, author, verbatim Arabic quotation, English summary, and source page. Assessments are never synthesized into an overall grade
- **Per-alias provenance**: Each alias records which book and page it was found in
- **Conflicting grades preserved**: When sources disagree (e.g. Du'afa says "weak" but Kashshi says "reliable"), both grades are kept with their respective source attributions as a `reliability_grades[]` list
- **Contributing sources list**: Every merged profile has `contributing_sources[]` showing which books contributed and their page numbers
- **Conflicting fields preserved**: When sources give different values for the same field (e.g. different death years), all variants are kept with their respective sources

**Merged Profile Schema (provenance-aware):**
```
merged_id                       → unique identifier
primary_arabic_name             → name from first source mentioning this narrator
primary_english_name            → English transliteration from first source
primary_name_source             → {"book": "...", "page": ...}
arabic_aliases[]                → [{"name": "...", "source_book": "...", "source_page": ...}]
english_aliases[]               → [{"name": "...", "source_book": "...", "source_page": ...}]
kunyah_arabic                   → value from first source that mentions it
kunyah_arabic_sources[]         → all sources mentioning this kunyah [{"book", "page", "value"}]
kunyah_english                  → English transliteration
titles[]                        → [{"title": "...", "source_book": "...", "source_page": ...}]
normalized_arabic               → for matching
normalized_english              → for matching
source_assessments[]            → verbatim per-source assessments with book/page
reliability_grades[]            → [{"grade": "...", "source_book": "...", "source_page": ...}]
is_doubtful                     → true if any source flags doubt
doubtful_reasons[]              → [{"reason": "...", "source_book": "...", "source_page": ...}]
narrated_from[]                 → [{"name": "...", "source_book": "...", "source_page": ...}]
narrated_to[]                   → [{"name": "...", "source_book": "...", "source_page": ...}]
city_or_tribe_values[]          → all values from all sources [{"value": "...", "source_book": "...", "source_page": ...}]
generation_values[]             → all values from all sources [{"value": "...", "source_book": "...", "source_page": ...}]
death_year_hijri_values[]       → all values from all sources [{"value": "...", "source_book": "...", "source_page": ...}]
gender                          → male/female
notes[]                         → [{"text": "...", "source_book": "...", "source_page": ...}]
contributing_sources[]          → [{"book": "...", "author": "...", "pages": [...]}]
```

Note: Fields like `city_or_tribe_values[]`, `generation_values[]`, and `death_year_hijri_values[]` store ALL values from ALL sources. No single "authoritative" value is chosen — different books may give different information, and all of it is preserved. Simple fields like `kunyah_arabic` have a convenience accessor (value from first source) plus a `*_sources[]` array capturing all mentions.

**Processing order** (smallest → largest): tusi → duafa → kashshi → fihrist → najashi → ardabili → khoei → mamaqani

Steps:
1. **Load all** `tmp/narrators_book_*.json` files
2. **Layer 1 — Exact normalized matching**: For each profile, check if an identical (normalized) profile already exists in the merged set. If yes, merge: combine aliases (with provenance), add `source_assessment`, keep the richer biography
3. **Layer 2 — Context-augmented matching**: For remaining unmatched profiles, use kunyah + nisbah + teacher/student context to disambiguate common names
4. **Layer 3 — Claude API batch**: For profiles that layers 1-2 couldn't resolve, batch them for Claude to judge (given name variants + biographical context from each source, are these the same person?)
5. **Flag uncertain cases** for manual review (`tmp/narrator_review_queue.jsonl`)
6. **Write output** to `tmp/narrators_merged.json`

**Script:** `scripts/merge_narrator_profiles.py`

### Phase 3: Import to Elasticsearch

1. Create import script (`scripts/import_narrators_to_es.py`)
2. Bulk index all merged narrator documents into `rewayaat_narrators` index
3. Verify index health and document counts

### Phase 4: Frontend - Narrator Detail Page

**`src/main/resources/templates/narrator.html`** - NOT YET CREATED

Thymeleaf + Vue.js page that:
- Loads narrator data via `/v1/narrators/{id}` API
- Displays narrator biography, names, aliases, kunyah, titles
- Shows per-source Rijal assessments in a structured format (source name, direct Arabic quote, English summary)
- Lists hadiths narrated by this person via `/v1/narrators/{id}/narrations`
- Uses the same search result UI as the main page (Bootstrap 5 + Bootswatch Materia)
- Follows existing patterns from `index.html` and `edit.html`

### Phase 5: Match Narrators to Hadith (Later)

Once the narrator database is built, link narrators to hadith:

1. Parse isnad chains from hadith entries in ES
2. Match narrator names against the `rewayaat_narrators` index
3. Add `narrator_ids` field to hadith documents for cross-referencing
4. Make narrator names clickable in hadith search results (link to `/narrator/{id}`)
5. Vue directive to annotate chain text with narrator links
6. CSS styling for narrator links in `manuscript.css`

### Future Enhancements (inspired by existing Rijal software)

Based on a review of the **Derayat al-Nur 2** software (Noor Center, Iran), the **Rawaat al-Hadith al-Jami'** database (Wali-e-Asr Institute), and the **Jawame' al-Kalam** software:

- **Narrator relationship query** — Allow users to query "what is the relationship between narrator X and narrator Y?" using the `narrated_from`/`narrated_to` data. Derayat al-Nur's "Relationship of Narrators" section is one of its most-used features. Our data model already captures teacher/student links — the UI should make this queryable.

- **Contemporaneity validation** — Use birth/death years and locations to verify whether two narrators in a chain could have actually met. The Rawaat database specifically built a "Wafiyat-e-Rawat" (death records of narrators) module for this — it's critical for isnad evaluation. Our profiles currently have `death_year_hijri` but should also capture `birth_year_hijri` when available from sources.

- **Name recording disambiguation** — The Rawaat database researchers found that similar-sounding names (e.g. "Babul" vs "Babel" — two different cities) lead to incorrectly merged narrators in 30% of cases they reviewed. Our Phase 2 merge must be conservative — when in doubt, keep separate rather than risk a false merge.

- **Distorted title detection** — Derayat al-Nur identifies corrupted names in isnad chains and suggests corrections. This would be part of Phase 5 chain parsing.

- **Chain-level assessment** — Not our responsibility. Hadith grading (sahih, hasan, da'if) will rely on established books, not our software. We present the raw narrator data and let users and published works handle the grading.

---

## Next Steps (Recommended Order)

1. ~~**Phase 1A: Parse Kitab al-Du'afa**~~ — DONE (222 profiles)
2. ~~**Phase 1B-G: Process external Rijal books**~~ — DONE (41,854 profiles across 7 books)
3. ~~**Phase 2: Aggregate & deduplicate**~~ — DONE. 29,305 merged profiles (from 41,854 source entries). Layers 1-3 all complete.
4. **Phase 3: Import to ES** — Bulk index into `rewayaat_narrators`
5. **Phase 4: Create narrator.html** — Thymeleaf template for the narrator detail page
6. **Phase 5: Match narrators to hadith chains** and add clickable links in search results
