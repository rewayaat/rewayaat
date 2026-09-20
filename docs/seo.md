# SEO

The site's job is to be found. Roughly half the code in `controllers/` exists for
crawlers rather than for the Vue application, and several of the decisions below look
arbitrary until you know what they are protecting against.

This document is the list of things that are **load-bearing**. Changing any of them
without meaning to will quietly cost traffic, and the damage takes weeks to show up in
Search Console.

## The target

The site competes with thaqalayn.com for "shia hadith" and the names of the individual
collections. Two things follow from that:

- The home page has to actually use the phrase. It once opened its title with "HDP", an
  acronym with no search volume, and never said "Shia" outside a `keywords` meta — which
  Google has ignored since 2009.
- Every book and chapter needs a URL of its own. A query like "al-kafi degrees of belief"
  has to have somewhere to land.

## Invariants

### 1. Unknown URLs must answer 404, never 500

`GlobalExceptionHandler` is scoped with
`@RestControllerAdvice(basePackages = "com.rewayaat.controllers.rest")`. **Do not remove
the selector.** Unscoped, its `@ExceptionHandler(Exception.class)` also catches the
`NoResourceFoundException` Spring raises for an unmapped path, and every 404 on the site
answers 500 with a JSON body instead.

This is the most expensive mistake available in this codebase. Crawlers read site-wide
5xx as an unhealthy origin and cut their crawl rate for the whole domain, so it throttles
every other page regardless of how good it is. `NotFoundStatusTest` pins the behaviour.

### 2. Canonicals are absolute and always on the canonical host

The canonical host is `hadith.academyofislam.com`, defined once as
`HomeController.BASE_URL`. The app also answers on **rewayaat.info**, which mirrors it.

A relative `<link rel="canonical" href="/">` resolves against whichever host served the
page, so the mirror declared *itself* canonical and the two domains competed for the same
rankings. Every canonical, `og:url` and JSON-LD `url` must be absolute and built from
`BASE_URL`.

`HomeControllerTest` pins this for the home page.

### 3. Search result pages are `noindex, follow`

`/?q=...` is thin, unbounded, and client-rendered — a crawler sees an empty shell. It
carries `noindex, follow` and a canonical pointing at the home page, so the links through
to narrations are still followed but the pages themselves never compete.

The same is true of anything the search interface produces, **including reading mode**.

### 4. Slugs are public URLs

`BookCatalog.slugify` decides what `/books/{book}` and `/books/{book}/{chapter}` answer
at. Changing it 404s every book and chapter page Google has indexed.

It strips transliteration diacritics deliberately, so "Man Lā Yaḥḍuruh al-Faqīh" answers
at `/books/man-la-yahduruh-al-faqih` — the spelling people type and link to. It also folds
the differing spellings in `book_blurbs.json` onto the index's spelling.
`BookCatalogSlugTest` pins the shape.

Repeated chapter titles within a book get a numeric suffix, assigned in the composite
aggregation's sort order so a rebuild produces the same URL.

### 5. Nothing may become an orphan

Before the book hubs, all 32,519 narration pages were reachable **only** from the XML
sitemap: a narration page's sole internal links were three copies of `/` and the footer.
A sitemap gets a page crawled; it does not pass authority.

The graph that now exists, and that changes here must preserve:

```
/  ──►  /books  ──►  /books/{book}  ──►  /books/{book}/volume/{n}
                                                   │
                                                   ▼
                                        /books/{book}/{chapter}
                                                   │
                                                   ▼
                                          /hadith/{id}  ◄──► /hadith/{id}
                                                            (similar narrations)
```

- The home page's book cards are server-rendered links, not divs with click handlers.
- Narration pages breadcrumb back up to chapter, volume and book.
- The pre-computed `llm_similar` pairs render as links, which cross book boundaries.

### 6. Volumes exist to keep link counts sane

Al-Kāfi has 2,693 chapters. Listing them on the book page produced a 637 KB document with
2,693 outbound links. Volumes are a level of their own so no page in the chain carries
more than a few hundred.

### 7. Filtered views never compete with the page they filter

A tag-filtered chapter (`?tag=wilayah`) is a slice of a page that is already indexed. It
carries `noindex, follow` and a canonical pointing at the unfiltered chapter, so the
facet stays useful to readers without spawning thin near-duplicates of every chapter
times every tag.

### 8. Content must be in the server's HTML

The home page body used to be fetched with `$("#welcome").load("/welcome.html")`, so the
served page was ~400 words of Vue template chrome with no prose and no links. It is a
Thymeleaf fragment now (`fragments/home-content.html`) and `rewayaat.js` binds the markup
already in the document rather than fetching it. `static/welcome.html` is gone, along with
the `noindex` it needed for answering 200 as a thin duplicate of the home page hero.

Note that `mvn` does not prune deleted resources from `target/classes`, so a dev server
started without `mvn clean` will keep serving a removed static file long after it is gone
from the source tree. If you are checking whether something still exists, check a clean
build, not a running dev server.

The rule: if a crawler that does not run scripts cannot see it, it does not count.

### 9. `og:image` is generated per narration, and must stay cacheable

`/hadith/{id}/card.png` draws a 1200×630 card of the narration itself (`ShareCardController`,
`ShareCardRenderer`). Every narration page used to advertise the same
`/img/share-card.png`, so a WhatsApp forward or a tweet of any of 32,519 different
narrations previewed one logo.

Two things about it are load-bearing:

- **The response carries an `ETag` and `Cache-Control: public, max-age=31536000, immutable`.**
  Drawing text is not free and crawlers hit these hard. The ETag is a hash of the card's
  own text and is also the in-memory cache key, so editing a narration invalidates both at
  once — keying the cache by id would have served the stale image forever, which is what
  `immutable` makes unrecoverable.
- **Nothing is pre-generated.** 32,519 PNGs is a lot of storage for images most of which
  are never requested.

The renderer loads all four faces from `static/fonts/` with `Font.createFont` and never
names a logical font ("Serif", "SansSerif"). The deployment image is an `eclipse-temurin`
JRE with no font packages installed; a logical name there resolves to whatever fontconfig
can find, which may be nothing. Verified by rendering with `FONTCONFIG_FILE` pointing at an
empty config (`fc-list` reports 0 fonts) and getting byte-identical output.

`?theme=light` renders the same card on the site's cream surfaces, for the Friday
newsletter — MailPoet templates are white and a navy block reads as a foreign object
dropped into one. **`og:image` stays on the dark default**: navy is more striking in a
feed, and the parameterless URL is the one already shared. The theme is part of the ETag
and of the cache key, so the two cannot be served for each other.

`/books/{book}/card.png` does the same for a book hub. Pages that generate a card set
`shareImageUrl`; everything else falls back to the site card in `fragments/site.html`.

`ShareCardIntegrationTest` pins the meta tags, the 304 and the language variants.

### 10. A chapter holding one narration is not a second page

Over half the chapters — 4,059 of 7,716 — contain exactly one narration. The chapter page
and the narration page then carry the same text: across five random pairs, 89–94% of the
chapter's vocabulary appeared on the narration page, with identical contiguous runs of 144
to 273 words. Both were self-canonical and both sat in a sitemap, so the two halves of
roughly four thousand pairs competed with each other, and Search Console reported 34,504
pages as "Discovered — currently not indexed".

The narration wins the pair. `chapterPage` canonicalises to `/hadith/{id}` when the chapter
holds exactly one, and `booksSitemap` omits those chapters, because a sitemap may only
advertise canonical URLs. The chapter page still answers 200, still carries its links, and
is still followed — invariant 5 is untouched. Only the duplicate signal goes away.

**Both halves ask one question.** `BookCatalog.Chapter.holdsSingleNarration()` is the only
place the rule is decided: the sitemap excludes by it and the canonical is gated on it. The
canonical also checks that the page really loaded exactly one narration, so if the index
and the catalog ever briefly disagree, the chapter stays self-canonical and merely unlisted
— the harmless way round. The harmful way round cannot happen: a chapter listed in the
sitemap whose canonical points somewhere else. `IndexingSignalsTest` pins that both sites
use the predicate.

### 11. Facet links are `nofollow`, not merely `noindex`

`noindex, follow` stops a `?tag=` view being indexed. It does not stop it being crawled.
About 19,600 of them exist — 2.5 per chapter across 7,839 chapters — and Google had already
crawled 1,731, spending on pages that can never be indexed the budget meant for the
narrations. The pills in `chapter.html` and `fragments/hadith-card.html` therefore carry
`rel="nofollow"` as well as the directive.

### 12. A page that must not be indexed has to be crawlable

`/edit` and `/signin.html` answer 200 with about seventy words each, and were `Disallow`ed
in robots.txt. Search Console reported both as "Indexed, though blocked by robots.txt": the
block stopped Google fetching them, so it never read a noindex, while the links from every
card's action rail were enough to index the URLs anyway. They are in
`CrawlerDirectivesConfig.NOINDEX_PATHS` now and deliberately absent from robots.txt.

The rule `/error/*` already followed: **`Disallow` hides the instruction, not the page.**

### 13. A part wrapping one chapter is not a third page

The same shape as invariant 10, one level up. Eighteen of the 166 `/part/` pages hold a
single chapter that carries the part's own title, so the part page is a heading, a blurb
and one link to a page saying the same thing — about a hundred words, most of it chrome.
`/books/al-kafi/part/the-book-of-intelligence-and-ignorance` and
`/books/al-kafi/the-book-of-intelligence-and-ignorance` were both self-canonical and both
in the sitemap.

`BookCatalog.Part.holdsSingleChapter()` is the only place the rule is decided, and
`booksSitemap` excludes by exactly the predicate `partPage` canonicalises on.

**The canonical goes where the chapter's own canonical goes**, not to the chapter URL. If
that one chapter also holds a single narration, the chapter has already folded into the
narration (invariant 10), and pointing the part at the chapter would declare a canonical
that is not itself canonical — `part → chapter → narration`. Both routes call
`BookPageController.canonicalPathFor`, so the chain is always one hop. That costs the part
page one extra Elasticsearch query, on at most eighteen rarely-requested pages.

`IndexingSignalsTest` pins both sides and the chain.

## Page inventory

| Surface | Indexable | Rendered by |
|---------|-----------|-------------|
| `/` | yes | Server (Thymeleaf), then hydrated |
| `/?q=...` | **no** — `noindex, follow` | Browser |
| `/books` | yes | Server |
| `/books/{book}` | yes | Server |
| `/books/{book}/volume/{n}` | yes | Server |
| `/books/{book}/part/{part}` | yes — **unless it holds one chapter**, then canonical to that chapter (or to its narration) | Server |
| `/books/{book}/{chapter}` | yes — **unless it holds one narration**, then canonical to `/hadith/{id}` | Server |
| `/books/{book}/{chapter}?tag=` | **no** — `noindex, follow`, and the links are `rel="nofollow"` | Server |
| `/hadith/{id}` | yes | Server |
| `/edit`, `/signin.html` | no | — (`X-Robots-Tag: noindex`; crawlable on purpose, see invariant 12) |
| `/error/*` | no | — (`X-Robots-Tag: noindex`) |

## Sitemaps

`/sitemap.xml` is an index over:

| Sitemap | Contents |
|---------|----------|
| `/sitemap-static.xml` | `/`, `/books`, `/updates.html`, `/search_tips.html` |
| `/sitemap-books.xml` | ~3,900 URLs — `/books`, 18 books, 30 volumes, the 148 parts holding more than one chapter, and the ~3,700 chapters holding more than one narration |
| `/sitemap-hadith-{1..4}.xml` | 32,519 narrations, 10,000 per page |

Two things learned the hard way, both pinned by `SitemapIntegrationTest`:

- **No `<lastmod>`.** Nothing in the index records when a narration was edited, and the
  code once emitted `LocalDate.now()` — telling crawlers all 32,519 pages changed today,
  every day. Search engines discount a lastmod they can see is unreliable.
- **A failure returns 5xx, not an empty `urlset`.** A well-formed empty sitemap tells a
  crawler the pages are gone; a 5xx tells it to retry and keep what it has.

The hadith sitemap is served from one cached scan of the id list. Paging straight from
Elasticsearch meant page N re-walked pages 1..N-1, and `/sitemap-hadith-4.xml` took 24
seconds — long enough that Google gave up on it.

## Structured data

| Page | Types |
|------|-------|
| `/` | `WebSite` with `SearchAction` (sitelinks search box), `Organization` |
| `/books` | `ItemList`, `BreadcrumbList` |
| `/books/{book}`, `/volume/{n}` | `Book`, `BreadcrumbList` |
| `/books/{book}/{chapter}` | `ItemList`, `BreadcrumbList` |
| `/hadith/{id}` | `ScholarlyArticle`, `BreadcrumbList` |

Every `BreadcrumbList` is built from the same list the visible trail renders from, so the
two cannot disagree — a mismatch is exactly what Google flags.

## The one fragile coupling

`BookCatalog.readingModeUrl` builds a query string that `rewayaat.js` has to parse. It
mirrors `buildQueryFromFilters` and `buildSortFields` in that file:

```
/?q=book:"Al-Kāfi" volume:"2" section:"20" chapter:"Degrees of Belief"
  &page=1
  &sort_fields=volume:asc,part:asc,section:asc,chapter:asc,number:asc
  &mode=read&match_mode=flexible&entry=browse
```

**If the query grammar in `rewayaat.js` changes, this has to follow.** Nothing fails
loudly if it drifts — the link just lands on the wrong results. There is no test covering
the pair; if you touch either side, click a chapter's read icon and confirm the reading
scope bar names the right chapter.

## Checking a change

Before merging anything that touches these pages:

```bash
# Unknown paths still 404, and the API envelope still works
mvn test -Dtest=NotFoundStatusTest

# Canonical host, target phrase, noindex on search pages
mvn test -Dtest=HomeControllerTest

# Slugs unchanged
mvn test -Dtest=BookCatalogSlugTest

# Nothing 500s, and the hubs still resolve
for u in / /books /books/al-kafi /books/al-kafi/volume/1 /sitemap-books.xml; do
  echo "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:8002$u")  $u"
done
```

What a crawler actually sees, which is the check that catches content moving back into
JavaScript:

```bash
curl -s https://hadith.academyofislam.com/ \
  | python3 -c "import re,sys; h=sys.stdin.read(); \
      t=re.sub(r'<(script|style).*?</\1>','',h,flags=re.S); \
      print(len(re.sub(r'<[^>]+>',' ',t).split()), 'words')"
```
