# Rewayaat Architecture

## Overview

Rewayaat is a hadith research platform combining traditional Islamic texts with modern search, semantic analysis, and AI-powered features. Built on Spring Boot 3.3.2 with Elasticsearch 9.x as the primary data store.

```
                    ┌──────────────────────────────────────────────┐
                    │           Elasticsearch 9.x                  │
                    │                                              │
                    │  rewayaat_updated          (32K hadith)      │
                    │  rewayaat_quran            (6.2K verses)     │
                    │  rewayaat_tafsir           (tafsir docs)     │
                    │  rewayaat_quranic_light_filtered (conn.)     │
                    └──────────────────────────────────────────────┘
                                      ▲
                    ┌─────────────────┼─────────────────────┐
                    │                 │                     │
              Ingestion          Search & API          Offline ML
              (Python)         (Spring Boot 8002)    (Python + Colab)
```

## Stack

| Layer | Technology |
|-------|-----------|
| Backend | Spring Boot 3.3.2, Java 17, Maven |
| Search | Elasticsearch 9.x (local, port 9200) |
| Frontend | Thymeleaf templates, Vue.js, UIkit CSS |
| Auth | Spring Security, cookie-based sessions |
| Deployment | Docker, Kubernetes (DigitalOcean), GitHub Actions |
| ML | sentence-transformers (multilingual-e5-large), Claude sub-agents |

## Package Structure

```
com.rewayaat/
├── controllers/rest/    # REST API endpoints
├── service/             # Business logic
├── core/                # Data models, ES client utilities
├── tafsir/              # Tafsir extraction and indexing
├── loader/              # Data ingestion utilities
├── tools/               # Backfill and batch processing tools
└── config/              # Spring configuration
```

## Key Services

| Service | Role |
|---------|------|
| `HadithQueryService` | Core search with flexible/precise modes |
| `SimilarHadithService` | LLM pre-computed similar hadith lookup |
| `QuranicInsightsService` | Hadith-to-Quran verse connections |
| `NarratorService` | Narrator chain processing |
| `AuthService` | Authentication and user management |
| `UserCollectionService` | User hadith collections |

## REST Controllers

| Controller | Base Path | Purpose |
|-----------|-----------|---------|
| `HadithController` | `/v1/narrations` | Search, view, edit, similar, quranic insights |
| `BrowseController` | `/v1/browse` | Faceted browsing by book/chapter/volume |
| `TermsController` | `/v1/terms` | Indexing and term management |
| `NarratorController` | `/v1/narrators` | Narrator operations |
| `AuthController` | `/auth` | Login, register, session management |
| `CollectionController` | `/v1/collections` | User hadith collections |

## Elasticsearch Indices

### `rewayaat_updated` — Primary hadith index (32,519 docs)

```
arabic: text                          # Full Arabic with narrator chains
english: text                         # Full English with chains
semantic_matn_source: text            # Chain-free Arabic (matn only)
semantic_english_hint_source: text    # Chain-free English hint (120 chars)
semantic_significant_terms_source: text  # TF-IDF extracted terms
semantic_vector: dense_vector(1024)   # Embedding vectors (cosine)
topic_tags: keyword[]                 # Tag slugs from taxonomy
llm_similar: nested                   # Pre-computed LLM-judged similar pairs
book, chapter, volume, part, section  # Hierarchical metadata
gradings: nested                      # Authenticity assessments
```

### `rewayaat_quran` — Quran verses (6,200 docs)

Arabic text, English translation, and topic tags per verse.

### `rewayaat_tafsir` — Tafsir commentary

Extracted from 13+ sources (Al-Mizan, Enlightening Commentary, Pooya Yazdi, etc.). Each doc has verse keys, section title, Arabic/English text, and source name.

### `rewayaat_quranic_light_filtered` — Hadith-Quran connections

Pre-computed connections between hadith and relevant Quran verses, filtered by LLM judgment to keep only strong connections.

## Data Model (HadithObject)

Key fields persisted in ES:

- **Text**: `arabic`, `english` (with chains), `semantic_matn_source`, `semantic_english_hint_source`
- **Metadata**: `book`, `chapter`, `number`, `volume`, `part`, `section`, `source`
- **Classification**: `topic_tags` (from 206-tag taxonomy), `gradings`
- **Search**: `semantic_vector`, `semantic_significant_terms_source`
- **AI features**: `llm_similar` (pre-computed similar pairs)

## Frontend

- **Templates**: Thymeleaf (`index.html`, `hadith.html`, `edit.html`)
- **Framework**: Vue.js for reactive components (search, hadith cards, similar panel)
- **CSS**: UIkit base with custom manuscript styling (`manuscript.css`)
- **JS**: `rewayaat.js` (main app), `vue-components.js` (reusable components)
- **Features**: Multi-term search, flexible/precise modes, similar hadith panel, Quranic insights panel, user collections

## Topic Taxonomy

206 tags across 10 categories defined in `static/taxonomy.json`:

`worship`, `ethics`, `beliefs`, `society`, `law`, `quran`, `prophets`, `history`, `spirituality`, `afterlife`

Tags have a parent-child hierarchy. Only `taggable` tags are applied to documents (broad categories excluded).

## Key Design Decisions

1. **Pre-computed AI features** — Similar hadith and Quranic insights are pre-judged by LLM agents and stored in ES. No real-time LLM calls during search. This ensures fast response times and reproducible results.

2. **Chain-free semantic text** — Narrator chains (`isnad`) are stripped before embedding and semantic search. This prevents chain-variant noise in similarity comparisons.

3. **Hybrid search architecture** — Primary search combines BM25 text search with embedding-based kNN, fused via Reciprocal Rank Fusion. No single retrieval method dominates.

4. **Thymeleaf + Vue.js** — Server-rendered pages with client-side Vue.js for interactive features (search, similar panel, collections). No SPA framework overhead.
