# Architecture

Rewayaat is a Shia hadith research platform: 32,519 narrations from 18 books, searchable
in Arabic and English, cross-linked to similar narrations and to the Quranic verses that
illuminate them. Spring Boot 3.3.2 on Java 17, with Elasticsearch as the only datastore —
there is no relational database.

```
                    ┌─────────────────────────────────────────────────┐
                    │              Elasticsearch 9.x                  │
                    │                                                 │
                    │  rewayaat_updated                32,519 hadith  │
                    │  rewayaat_quran                   6,236 verses  │
                    │  rewayaat_tafsir                14,380 commentaries │
                    │  rewayaat_quranic_light_filtered 22,640 hadith→verse │
                    │  rewayaat_users / _user_collections             │
                    └─────────────────────────────────────────────────┘
                                        ▲
                    ┌───────────────────┼───────────────────┐
                    │                   │                   │
              Ingestion            Spring Boot         Offline agents
            (scripts/ingest)     (:8002 / mgmt :8003)  (scripts/*, Claude)
```

Everything expensive is computed offline and stored in ES. **No LLM is called while
serving a request** — similar narrations and Quranic insights are pre-judged by agent
pipelines and read straight out of the index.

## Stack

| Layer | Technology |
|-------|-----------|
| Backend | Spring Boot 3.3.2, Java 17, Maven |
| Datastore | Elasticsearch 9.x (`localhost:9200` in dev) |
| Templating | Thymeleaf, server-rendered |
| Frontend | Vue 2.6, jQuery, Bootstrap 5.3, custom `manuscript.css` |
| Auth | Spring Security, cookie sessions, Resend for mail |
| Deploy | Docker → DigitalOcean Kubernetes, GitHub Actions, Argo CD |
| ML | sentence-transformers (`multilingual-e5-large` + LoRA), Claude sub-agents |

## Two Front Doors

The site serves the same corpus two ways, and the distinction runs through the whole
codebase.

**Server-rendered pages** (`controllers/`) return HTML with real `<a href>` links. They
exist so search engines can crawl and rank the corpus — before them, nothing stood
between the home page and 32,519 narration pages, and narrations were reachable only
from the XML sitemap.

**JSON API** (`controllers/rest/`) backs the Vue application: live search, the similar
panel, collections, editing.

The two surfaces share endpoints but not JavaScript. Server-rendered pages load the
small `hub-pages.js` rather than the 274 KB search bundle, so a reader stays signed in
and can still save a narration without the page paying for a search app it does not run.

## Package Structure

```
com.rewayaat/
├── controllers/          # Server-rendered pages (SEO surface)
│   └── rest/             # JSON API (Vue surface)
├── service/              # Business logic
├── core/                 # Query building, result shaping, text processing
│   └── data/             # Persisted models
├── tafsir/               # Tafsir parsing and indexing
│   └── extractors/       # 13 source-specific HTML extractors
├── loader/               # One-time corpus loaders, per book
├── tools/                # Runnable offline backfill / audit tools
└── config/               # Spring configuration
```

## Server-Rendered Pages

| Controller | Routes | Purpose |
|-----------|--------|---------|
| `HomeController` | `/`, `/edit`, `/auth/verify`, `/auth/reset` | Home page, rendered server-side; owns the canonical host |
| `BookPageController` | `/books`, `/books/{book}`, `/books/{book}/volume/{n}`, `/books/{book}/{chapter}` | The book → volume → chapter hierarchy |
| `HadithPageController` | `/hadith/{id}` | One page per narration, with related reading |
| `SitemapController` | `/sitemap.xml`, `/sitemap-static.xml`, `/sitemap-books.xml`, `/sitemap-hadith-{page}.xml` | Crawler sitemaps |
| `GlobalExceptionHandler` | — | Real 404s via container error dispatch, not redirects |

Supporting pieces: `BookCatalog` turns the corpus into a slug-addressable book/chapter
tree; `BookBlurbs` reuses the search UI's descriptions on book pages, matching the two
different spellings on a shared slug; `CrawlerDirectivesConfig` marks pages that must
never be indexed with a header rather than a robots.txt `Disallow` (a blocked URL can
still be indexed from a link); `StaticAssetConfig` fingerprints asset URLs so a cached
script can never be paired with newer markup.

## JSON API

| Controller | Base | Endpoints |
|-----------|------|-----------|
| `HadithController` | `/v1/narrations` | search (`GET`), fetch/update `/{id}`, `/similar`, `/quranic_insights`, `/page_for_id` |
| `BrowseController` | `/v1/browse` | `/books`, `/facets` |
| `CollectionController` | `/v1/collections` | CRUD, `/quick-save`, `/quick-save-bulk`, per-collection hadith |
| `AuthController` | `/v1/auth` | `/register`, `/verify`, `/login`, `/logout`, `/me`, `/reset/request`, `/reset/confirm` |
| `TermsController` | `/v1/terms` | `/top`, `/significant` |
| `FeedbackController` | `/v1/feedback` | User feedback submission |

## Services

| Service | Role |
|---------|------|
| `HadithQueryService` | Core search — flexible and precise modes |
| `SimilarHadithService` | Reads the pre-computed `llm_similar` field, bulk-fetches the targets |
| `QuranicInsightsService` | Hadith → Quran verse connections with tafsir |
| `BookCatalog` | Slug-addressable book/volume/chapter structure |
| `AuthService` | Registration, verification, sessions, password reset |
| `UserCollectionService` | User-owned hadith collections |
| `HadithEditorAccessService` | Static allowlist (`admins.txt`) for edit access |

See [search.md](search.md) for how search, similar narrations and Quranic insights work,
and [seo.md](seo.md) for the invariants the crawler-facing pages depend on — several of
them look arbitrary until you know what they are protecting against.

## Elasticsearch Indices

### `rewayaat_updated` — narrations (32,519)

```
arabic, english                       # full text, narrator chains included
semantic_matn_source                  # Arabic with the isnad stripped
semantic_english_hint_source          # chain-free English hint
semantic_significant_terms_source     # TF-IDF extracted terms
semantic_vector: dense_vector(1024)   # cosine, HNSW      (32,516 docs)
topic_tags: keyword[]                 # from the 206-tag taxonomy (31,809 docs)
llm_similar: nested                   # pre-judged similar pairs (25,273 docs)
book, volume, part, chapter, section, number, source
gradings: nested                      # authenticity assessments
```

Document IDs are `bookId:hadithNumber`, e.g. `Al-Kafi-Volume-1-Kulayni:1048`.

### `rewayaat_quran` — verses (6,236)

Arabic, English translation, and topic tags per verse, tagged from the same taxonomy.

### `rewayaat_tafsir` — commentary (14,380)

Extracted from 13 sources (Al-Mizan, Enlightening Commentary, Pooya Yazdi, Divine Lights,
Al-Bayan, Hoda Al-Quran, Quranic Reflections and others). Each document carries
`verse_keys`, `section_title`, Arabic and English text, and `source_name`.

### `rewayaat_quranic_light_filtered` — hadith→verse links (22,640)

Pre-computed verse connections carrying 17,612 tafsir snippets, LLM-filtered down to
strong connections only. See [pipelines/quranic-insights.md](pipelines/quranic-insights.md).

### `rewayaat_users`, `rewayaat_user_collections`

Accounts and saved collections.

## Frontend

- **Templates** — `index.html` (search app), `hadith.html`, `books.html`, `book.html`,
  `volume.html`, `chapter.html`, `edit.html`; shared chrome in
  `fragments/site.html` and `fragments/home-content.html`
- **JS** — two bundles, deliberately not shared:
  - `rewayaat.js` (274 KB) is the search application. It boots the home page, runs a
    query on ready, and expects Vue, tom-select, swal and the search DOM to exist.
    Only `index.html` loads it.
  - `hub-pages.js` is what the server-rendered pages load instead, via
    `fragments/site.html`. It gives them auth state and save-to-collection by calling
    the same endpoints and reusing the same element ids and CSS classes, while staying
    small — pulling the search bundle into a static document would spend the crawl
    budget those pages exist to earn.

  Plus `vue-components.js` (shared components), `hadith-editor.js`, and `auth-page.js`
  (used by `static/signin.html`).
- **CSS** — `manuscript.css` carries the whole visual design on top of Bootstrap.
  It intentionally contains duplicate blocks: base styles, then an ornament pass that
  overrides them. Do not consolidate them.

## Topic Taxonomy

206 tags in `static/taxonomy.json`, across `worship`, `ethics`, `beliefs`, `society`,
`law`, `quran`, `prophets`, `history`, `spirituality`, `afterlife`. Tags form a
parent-child hierarchy; only `taggable` tags are applied to documents, broad category
nodes are not. `TopicTaxonomySupport` and the `TopicTag*` tools in `tools/` handle
auditing, gold-set sampling and scoring.

## Offline Tools

Runnable Java classes in `com.rewayaat.tools`, invoked with the built classpath (see
[data-pipeline.md](data-pipeline.md)):

| Tool | Purpose |
|------|---------|
| `SemanticMatnSourceBackfillTool` | Strip isnad chains → `semantic_matn_source` |
| `SemanticSignificantTermsBackfillTool` | TF-IDF term extraction |
| `TafsirExtractionTool` | Run the extractor set over cached HTML |
| `TafsirLanguageSplitBackfillTool` | Split mixed-language tafsir documents |
| `TafsirAuditTool` | Extraction quality audit |
| `QuranVerseEmbeddingTool`, `TafsirEmbeddingTool` | Embedding backfills |
| `TopicTaxonomyAuditTool`, `TopicTagsQaTool`, `TopicTagGoldSet*Tool` | Tag quality |
| `TagMigrationTool` | Taxonomy remapping |

## Design Decisions

**Pre-computed AI features.** Similar narrations and Quranic insights are judged by
agent pipelines offline and stored in ES. Response times stay fast and results stay
reproducible — the same query returns the same answer tomorrow.

**Chain-free semantic text.** Narrator chains are stripped before embedding and
similarity comparison. Two narrations of the same saying share a matn but rarely a
chain; leaving the isnad in makes every hadith from a prolific narrator look alike.

**Server-rendered HTML for anything a crawler should see.** The Vue app is layered on
top of real pages, not in place of them.

**Elasticsearch as the only store.** Accounts and collections live in ES alongside the
corpus. One datastore, one backup story.
