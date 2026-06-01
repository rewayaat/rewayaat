# Narrator Extraction & Biography System - Status Document

## Goal

Build a narrator extraction and biography system for the Rewayaat Shia hadith database that:

1. **Extracts** individual narrator names from hadith chains (isnad)
2. **Sources** biographical data from classical Shia Rijal works (aggregation only, no subjective judgments)
3. **Stores** narrators in a dedicated Elasticsearch index (`rewayaat_narrators`) with aliases, kunyahs, titles, per-source assessments
4. **Skips** the 14 Infallibles (Imams and Prophets) - no biography pages for them
5. **Provides** a dedicated narrator detail page (`/narrator/{id}`) where users can click narrator names
6. **Enables** searching hadiths by narrator across all name variants/aliases
7. The narrator detail page queries the existing hadith database and displays results using the same UI as other pages

### Key Design Decisions
- **No external LLM APIs** - Claude sub-agents should be used for biography enrichment work
- **No subjective judgments** - only aggregate and synthesize what Rijal sources actually state
- **Per-source assessments** - each narrator has individual assessments from each Rijal work that mentions them
- **Rijal sources**: Mu'jam Rijal al-Hadith (Khoei), Tanqih al-Maqal (Mamaqani), Rijal al-Kashshi, Rijal al-Najashi, Rijal al-Tusi, Fihrist al-Tusi, Jami' al-Ruwat (Ardabili), Kitab al-Du'afa (Ibn al-Ghada'iri)

---

## What Was Completed

### 1. Data Model (DONE)
- **`NarratorDocument.java`** - Full POJO with 30+ fields: primary names (Arabic/English), aliases, kunyah, titles, scholarly names, imam/prophet flag, doubtful flag, reliability grade, per-source assessments, biography summaries, rijal sources, death/birth years, tribe/city, generation, gender, hadith count, normalized names, timestamps
- **`SourceAssessment.java`** - POJO for individual Rijal source assessments: sourceName, author, assessmentEn, assessmentAr
- **`ExtractedNarrator.java`** - Lightweight record for parsed narrators from chains

### 2. Elasticsearch Index Management (DONE)
- **`NarratorIndexManager.java`** - Full ES index CRUD for `rewayaat_narrators`
  - Index creation with complete mapping for all NarratorDocument fields
  - Bulk indexing with overwrite support
  - Follows the same pattern as TafsirIndexManager

### 3. Name Matching & Imam Registry (DONE)
- **`NarratorNameMatcher.java`** - Deduplication with normalized Arabic/English matching and Jaro-Winkler fuzzy similarity (0.85 threshold), supports aliases
- **`ImamProphetRegistry.java`** - Hardcoded registry of the 14 Infallibles with all known Arabic/English name variants, kunyah mappings, and honorific stripping

### 4. Backend API (DONE)
- **`NarratorService.java`** - ES lookup against `rewayaat_narrators` index, builds OR query across all name variants (Arabic/English, aliases, kunyahs)
- **`NarratorController.java`** - REST endpoints:
  - `GET /narrator/{id}` - Page endpoint (returns `narrator` Thymeleaf template)
  - `GET /v1/narrators/{id}` - JSON API: get narrator details
  - `GET /v1/narrators/{id}/narrations` - JSON API: search hadiths by narrator (paginated)

### 5. Chain Data Extraction (PARTIALLY DONE)
- **`scripts/extract_chains_for_narrators.py`** - Python script to extract chain portions from hadith batch JSON files
- **`tmp/narrator_chains_part1.json`** - 51 chain entries (mostly Kitab al-Duafa)
- **`tmp/narrator_chains_part2.json`** - 51 chain entries (Du'afa + Mumin + Ma'ani al-Akhbar)
- **`tmp/narrator_chains_part3.json`** - 48 chain entries (Ma'ani al-Akhbar + Man La Yahduruh al-Faqih)
- **150 total chain entries extracted** from batch data

---

## What Remains

### Phase 1: Narrator Data Compilation (CRITICAL - BLOCKING)

**Status: NOT STARTED (no output files exist)**

Multiple attempts to use Claude sub-agents failed (429 rate limits, ECONNRESET errors across two sessions). The approach needs to be:

1. **Process all 150 chain entries** from the 3 JSON files in `tmp/`
2. **Extract unique narrator names** (deduplicating with NarratorNameMatcher logic)
3. **Filter out** Imams/Prophets using ImamProphetRegistry
4. **Compile biographical data** for each narrator from classical Shia Rijal sources:
   - Kitab al-Du'afa (Ibn al-Ghada'iri) - many entries already have direct assessments
   - Mu'jam Rijal al-Hadith (Ayatollah Khoei)
   - Tanqih al-Maqal (Mamaqani)
   - Rijal al-Kashshi
   - Rijal al-Najashi
   - Rijal al-Tusi / Fihrist al-Tusi
   - Jami' al-Ruwat (Ardabili)
5. **Write output** to `tmp/narrators_extracted.json` in NarratorDocument JSON format

**Key narrators identified from chain data (~60-80 unique, non-Imam narrators)**

### Phase 2: Import to Elasticsearch

1. **Create import script** (`scripts/import_narrators_to_es.py` or Java tool)
2. **Bulk index** all narrator documents into `rewayaat_narrators` index
3. Verify index health and document counts

### Phase 3: Frontend - Narrator Detail Page

**`src/main/resources/templates/narrator.html`** - NOT YET CREATED

This should be a Thymeleaf + Vue.js page that:
- Loads narrator data via `/v1/narrators/{id}` API
- Displays narrator biography, names, aliases, kunyah, titles
- Shows per-source Rijal assessments in a structured format
- Lists hadiths narrated by this person via `/v1/narrators/{id}/narrations`
- Uses the same search result UI as the main page (Bootstrap 5 + Bootswatch Materia)
- Follows existing patterns from `index.html` and `edit.html`

### Phase 4: Frontend Integration (Optional Enhancement)

These are enhancements for later - not part of the initial deliverable:

- Make narrator names clickable in hadith search results (link to `/narrator/{id}`)
- Vue directive to annotate chain text with narrator links
- `narrator_ids` field in hadith documents for cross-referencing
- CSS styling for narrator links in `manuscript.css`

---

## File Inventory

### Completed Files (on v2.0 branch, untracked)
| File | Status |
|------|--------|
| `src/main/java/com/rewayaat/controllers/rest/NarratorController.java` | DONE |
| `src/main/java/com/rewayaat/service/NarratorService.java` | DONE |
| `src/main/java/com/rewayaat/core/data/NarratorDocument.java` | DONE |
| `src/main/java/com/rewayaat/core/data/SourceAssessment.java` | DONE |
| `src/main/java/com/rewayaat/core/data/ExtractedNarrator.java` | DONE |
| `src/main/java/com/rewayaat/core/NarratorNameMatcher.java` | DONE |
| `src/main/java/com/rewayaat/core/ImamProphetRegistry.java` | DONE |
| `src/main/java/com/rewayaat/tafsir/NarratorIndexManager.java` | DONE |
| `scripts/extract_chains_for_narrators.py` | DONE |
| `tmp/narrator_chains_part1.json` | DONE (51 entries) |
| `tmp/narrator_chains_part2.json` | DONE (51 entries) |
| `tmp/narrator_chains_part3.json` | DONE (48 entries) |

### Files Not Yet Created
| File | Purpose |
|------|---------|
| `tmp/narrators_extracted.json` | Compiled narrator data (Phase 1 output) |
| `src/main/resources/templates/narrator.html` | Narrator detail page (Phase 3) |
| `scripts/import_narrators_to_es.py` | ES import script (Phase 2) |

---

## Next Steps (Recommended Order)

1. **Compile narrator data** - Process the 150 chain entries directly (no sub-agents) and write `narrators_extracted.json`
2. **Create narrator.html** - Thymeleaf template for the narrator detail page
3. **Start Elasticsearch** and import narrators
4. **Test end-to-end** - Navigate to `/narrator/{id}`, verify biography display and hadith search
5. *(Later)* Integrate narrator links into the main search results UI
