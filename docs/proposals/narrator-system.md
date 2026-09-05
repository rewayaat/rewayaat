# Narrator Biography System — Proposal

> **Not implemented. Design preserved for a future revival.**
>
> Phases 1 and 2 were built and run; the extraction and merge code was then deleted in
> commit `9b6adb6` ("remove unused narrator pipeline code and stale docs"). Phases 3-5
> (ES import, narrator page, hadith-chain matching) were never started. There is no
> `rewayaat_narrators` index, no `NarratorService`, and no `/v1/narrators` endpoint —
> the architecture doc used to claim otherwise.
>
> **The extracted data survives** under `tmp/` (symlinked to `/mnt/share/rewayaat-backup/tmp/`):
>
> | File | Contents |
> |------|----------|
> | `tmp/narrators_merged.json` | 29,305 merged profiles after identity-resolution layers 1-3 (126 MB) |
> | `tmp/narrators_merged.json.pre_l3_backup` | Same, before the LLM merge layer |
> | `tmp/narrators_l3_decisions.json` | 3,976 LLM pair judgments, 1,502 high-confidence merges |
> | `tmp/narrators_book_{slug}.json` | Per-book extraction output for all 8 Rijal sources |
> | `tmp/narrator_review_queue.jsonl` | Flagged profiles awaiting manual review |
>
> `scripts/narrators/audit_narrator_quality.py` still runs against that data.
>
> Reviving this means rewriting the Phase 1-2 scripts (the design below specifies them
> fully) or working directly from `tmp/narrators_merged.json`, which already has Phase 2
> output — so Phase 3 could start immediately.

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

**Status: NOT STARTED**

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

**Status: NOT STARTED (parser tested, Najashi verified working)**

Each book is processed the same way:
1. Download actual Arabic text page-by-page from usul.ai (or eshia.ir for Mamaqani)
2. Batch pages (5 per call) and send to Claude for structured extraction
3. Claude parses the real source text into narrator profiles with verbatim Arabic quotations
4. No reliance on Claude's memory for content — all data comes from the actual book text

**Scripts:**
- `parse_duafa_narrators.py` (deleted) — Phase 1A (in-corpus Du'afa)
- `parse_external_rijal.py` (deleted) — Phases 1B-G (download + parse from source)

**Outputs:** `tmp/narrators_book_{slug}.json` for each book

### Phase 2: Cross-Book Aggregation & Deduplication

**Status: NOT STARTED**

Merge all per-book profile files into a single unified narrator database. This is where the identity resolution strategy (see above) is applied.

Steps:
1. **Load all** `tmp/narrators_book_*.json` files
2. **Pass 1 — Exact normalized matching**: For each profile, check if an identical (normalized) profile already exists in the merged set. If yes, merge: combine aliases, add `SourceAssessment`, keep the richer biography
3. **Pass 2 — Context-augmented matching**: For remaining unmatched profiles, use kunyah + nisbah + teacher/student context to disambiguate common names
4. **Pass 3 — Claude sub-agent batch**: For profiles that layers 1-2 couldn't resolve, batch them for Claude to judge (given name variants + biographical context from each source, are these the same person?)
5. **Flag uncertain cases** for manual review
6. **Write output** to `tmp/narrators_merged.json`

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

---

## Next Steps (Recommended Order)

1. **Phase 1A: Parse Kitab al-Du'afa** — Script reads the 226 entries, extracts narrator profiles, writes `tmp/narrators_book_duafa.json`
2. **Phase 1B-G: Process external Rijal books** — Claude sub-agents extract profiles from each book independently
3. **Phase 2: Aggregate & deduplicate** — Merge all per-book files into unified narrator database
4. **Phase 3: Import to ES** — Bulk index into `rewayaat_narrators`
5. **Phase 4: Create narrator.html** — Thymeleaf template for the narrator detail page
6. **Phase 5: Match narrators to hadith chains** and add clickable links in search results

---

## Appendix: Phase 1 Extraction Record

The per-book extraction is finished; this is what each source yielded. Both driver
scripts (`parse_duafa_narrators.py` for the in-corpus Kitab al-Du'afa, and
`parse_external_rijal.py` for the books pulled from usul.ai/eshia.ir) have since been
deleted. Their outputs remain as `tmp/narrators_book_{slug}.json`, with per-batch
checkpoints in `tmp/narrators_book_{slug}_checkpoint.json`.

| Book | Slug | Scope | Profiles | Pages | Notes |
|------|------|-------|----------|-------|-------|
| Kitab al-Du'afa | `duafa` | 224 entries | 222 | 224/224 | 2 entries errored |
| Rijal al-Kashshi | `kashshi` | 94 pages | 209 | 94/94 | 1 error |
| Fihrist al-Tusi | `fihrist` | 253 pages | 731 | 253/253 | 5 errors |
| Rijal al-Najashi | `najashi` | 461 pages | 1,310 | 461/461 | clean |
| Rijal al-Tusi | `tusi` | 417 pages | 123 | 412/417 | 92 errors — dense bare-name lists hit token truncation; needs a re-run at `--batch-size 2` |
| Jami' al-Ruwat | `ardabili` | 1,210 pages | — | 1,210/1,210 | |
| Mu'jam Rijal al-Hadith | `khoei` | 10,924 pages | — | 10,924/10,924 | largest source |
| Tanqih al-Maqal | `mamaqani` | 34 vols | — | incomplete | eshia.ir only; never finished |

Total across all books before merging: 41,854 profiles. After Phase 2 identity
resolution: 29,305.

Lessons worth keeping if this is rebuilt:

- Process one book at a time; parallel runs corrupted shared checkpoints.
- Checkpoint every batch — page downloads fail intermittently and the run must survive it.
- Dense pages of bare names truncate on output; drop the batch size rather than the page.
