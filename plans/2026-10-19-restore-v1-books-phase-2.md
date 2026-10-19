# Restore v1 Books — Phase 2: Application Code

**Goal:** The application serves the three restored books correctly in English and Arabic: catalogue titles, citations, publisher, MCP corpus scope, blurbs and summaries, and legacy v1-id redirects. Every change is inert while the books are absent from the index.

**Architecture:** Additive changes on `feature/restore-v1-books`. They are not merged until phase-5 Step "merge", after production holds the data (data before code, `docs/arabic-release.md`). The catalogue's Arabic titles come from the index first and the committed mapping second, which retires the English-keyed collision class for new books. The new field `cited_from` follows the `source`/`source_ar` pattern end to end.

**Working directory for all commands:** `/home/zir0/git/rewayaat-lost-books`

**Related plans:** [phase-1](2026-10-19-restore-v1-books-phase-1.md), phase-2 (this file), [phase-3](2026-10-19-restore-v1-books-phase-3.md), [phase-4](2026-10-19-restore-v1-books-phase-4.md), [phase-5](2026-10-19-restore-v1-books-phase-5.md)

**Depends on:** phase-1 (book names DEC-101, `src/main/resources/legacy/v1_restored_ids.json`).

**Maintainability expectations:**
- Reuse existing components: `HadithCardFactory.localised` / `addRow`, `localeField` (JS), `ArabicNames`, `BookBlurbs`, `CorpusScope`, `ElasticsearchTestSupport` / `MigrationPreservesSearchIntegrationTest` harness.
- Strings live only in `messages.properties` / `messages_ar.properties` (`docs/i18n.md`). New keys go in both.
- Match naming of the nearest peers (`source_ar` → `cited_from_ar`, `js.source` → `js.citedFrom`).
- No hardcoded counts outside `CorpusScope`.

**Discovery evidence:** [`discovery-receipt.md`](2026-10-19-restore-v1-books/discovery-receipt.md) R4–R6.

**Decision and evidence manifest:**

| ID | Decision | Files/symbols | Source evidence | Reuse target | Blast-radius query + classified siblings | Acceptance proof | Risk | Invalidation conditions |
|---|---|---|---|---|---|---|---|---|
| DEC-201 | `cited_from`/`cited_from_ar` = keyword + `.text`, same analyzers as `source`/`source_ar`. `publisher_ar` the same as `source_ar` | `scripts/search/v2_mapping.json:321-340` | `source` mapping (live) | `source`, `source_ar` | `grep -rn '"source_ar"' scripts/search src/main/java` → mapping + `SEARCHABLE_FIELDS` only | 2.1 integration test | high: index mapping | analyzer change |
| DEC-202 | `cited_from.text`, `cited_from_ar.text` in `SEARCHABLE_FIELDS`, `cited_from.text` in `METADATA_TEXT_FIELDS` | `QueryStringQueryResult.java:439-444,496-498` | `source.text` precedent | same lists | MCP `NarrationRepository.SEARCH_FIELDS:87` is a separate weighted list, left unchanged (matn-weighted by design, ADR-001) | 2.1 test | standard | search eval regression (phase-3) |
| DEC-203 | Card row "Cited from" after Source, server + Vue, via `localised`/`localeField`. The Vue publisher row is fixed to `localeField` + escaped | `HadithCardFactory.java:140-145`, `vue-components.js:217-243` | parity rule `HadithCardParityTest` | `source` row | `grep -n "publisher" src/main/resources/static/js/vue-components.js` → line 240 `html: this.narration.publisher` is unescaped and unlocalised. `publisher` is on 0 live docs (R6), so the bug is latent until phase-3 | 2.2 tests | standard | — |
| DEC-204 | `BookCatalog` reads `chapter_ar`/`part_ar` per composite bucket (terms sub-agg, size 1). `titleAr()` = indexed, else `ArabicNames` | `BookCatalog.java:203-245,412-447,487-524` | R5: 17 English titles collide with `chapter_ar_mapping.json` keys holding different Arabic. `docs/i18n.md` "The one place this is still duplicated" measured the sub-agg at 80 ms | `ArabicNames` fallback kept | callers of `titleAr()`: `grep -rn "titleAr()" src/main` | 2.3 parity probe: 0 rendered-title diffs on the 18 existing books | high: every Arabic hub page | diff > 0 on existing books |
| DEC-205 | `CorpusScope.BOOK_COUNT = 21`, scope sentence lists 21 books, `NARRATION_COUNT = 50119` replaces literal `32519` in `McpServerConfig.java:74,198` | `CorpusScope.java`, `McpServerConfig.java` | R6 + phase-1 counts (32,519 + 17,600) | `CorpusScope` constants | `grep -rn "32519\|32,519" src/main/java` → listed in 2.5 | `McpProtocolIntegrationTest:560-585` | high: MCP contract | phase-1 count change |
| DEC-206 | 301 `/?q=_id:<v1id>` → `/hadith/{newId}` for ids in `legacy/v1_restored_ids.json` only, via `View.RESPONSE_STATUS_ATTRIBUTE` | new `LegacyIdRedirects`, `HomeController.java:97-130` | user decision 2026-10-19. Memory `legacy-id-urls-are-dead` | Spring `RedirectView` honours the request attribute | `HomeController` constructed in `HomeControllerTest.java:25` only | 2.4 tests + phase-3 curl | standard | — |
| DEC-207 | Blurb entries get `slug` + new `book` names. Mishkat `blurb_ar` corrected: compiler ʿAlī b. al-Ḥasan al-Ṭabrisī, ten sections (OpenITI fihrist «عشرة أبواب»; v1 has 10 section labels, two spelled twice). Mizan `blurb_ar` says it is the abridged *Muntakhab*. Ghurar `blurb_ar` drops "alphabetical order" (this edition is topical) | `static/book_blurbs.json` entries 1, 3, 4 | the English blurbs (R4 + `book_blurbs.json`). v1 data: Mishkat 10 sections, Ghurar topic chapters | `TranslatedDataTest.everyBookHasABlurbKeyedToItsPage:87-125` | the skip branch `:110-113` becomes dead → delete | `TranslatedDataTest` | standard | — |
| DEC-208 | `book_summaries.json` gains 3 entries `{en, ar}` keyed by slug | `static/book_summaries.json` | `TranslatedDataTest:90` treats its keys as the list of pages | existing entries | `BookBlurbs.summaryForSlug` | `TranslatedDataTest` | standard | — |

**Execution policy:** One commit per slice. The branch is not pushed until phase-5. Full `mvn -B test` runs once at the end of phase-2 against local ES. Testcontainers is used when local ES is busy: `-Dtestcontainers.enabled=true`, the CI command `.github/workflows/ci-cd.yml:58`.

---

## Phase 2.1 — Mapping and search fields

**Objective:** A document with `cited_from` is found by searching its citation, and the mapping file declares the three new fields.

**Estimated budget:** 45 min.

**Files changed:**
- Modify: `scripts/search/v2_mapping.json` (after `"source_ar"`, line ~331)
- Modify: `src/main/java/com/rewayaat/core/QueryStringQueryResult.java:439-444, 496-498`
- Test: `src/test/java/com/rewayaat/integration/CitedFromSearchIntegrationTest.java` (create)

### Steps

- [ ] **Step 1 (TDD): Write the failing test**

  Create `CitedFromSearchIntegrationTest extends ElasticsearchTestSupport`. Copy `MigrationPreservesSearchIntegrationTest`'s index build from `PRODUCTION_MAPPING` (`:70`, `:93-104`) and its `index(id, json)` (`:328`) and `search(query)` (`:360`) helpers. Seed:
  ```json
  {"book":"Mīzān al-Ḥikma","number":"1","volume":"1","chapter":"Banes","english":"Imam Ali (AS) said, the bane of knowledge is forgetfulness.","arabic":"آفة العلم النسيان","source":"Muḥammad Muḥammadī Rayshahrī","cited_from":"Kanz al-Ummal, number:29331","cited_from_ar":"كنز العمال، ح 29331"}
  ```
  Assertions:
  - `search("ummal")` → `totalResultSetSize == 1`
  - `search("العمال")` → `1`
  - the mapping of the built index has `cited_from.fields.text`, `cited_from_ar.fields.text` and `publisher_ar` (`client.indices().getMapping`)

- [ ] **Step 2 (TDD): Run — confirm red**

  Run: `cd /home/zir0/git/rewayaat-lost-books && mvn -B -q test -Dtest=CitedFromSearchIntegrationTest`
  Expected: FAIL on the mapping assertion (no `cited_from` in `v2_mapping.json`).

- [ ] **Step 3 (Exact): Mapping**

  In `scripts/search/v2_mapping.json` `mappings.properties`, add beside `source_ar`:
  ```json
  "cited_from": {"type": "keyword", "fields": {"text": {"type": "text", "analyzer": "english_fold", "search_analyzer": "english_fold_search"}}},
  "cited_from_ar": {"type": "keyword", "fields": {"text": {"type": "text", "analyzer": "arabic_norm"}}},
  "publisher_ar": {"type": "keyword", "fields": {"text": {"type": "text", "analyzer": "arabic_norm"}}},
  ```

- [ ] **Step 4 (Exact): Search fields**

  `QueryStringQueryResult.java:439-444`:
  ```java
  private static final List<String> SEARCHABLE_FIELDS = List.of(
          "english", "arabic", "chapter", "notes",
          "book.text", "volume.text", "part.text", "section.text",
          "source.text", "publisher.text", "cited_from.text",
          "book_ar", "chapter_ar.text", "part_ar.text", "section_ar.text", "source_ar.text",
          "cited_from_ar.text",
          "topic_tags");
  ```
  `:496-498`:
  ```java
  private static final List<String> METADATA_TEXT_FIELDS =
          List.of("book.text", "volume.text", "part.text", "section.text",
                  "source.text", "publisher.text", "cited_from.text");
  ```

- [ ] **Step 5 (TDD): Run — green.** Same command. Expected: PASS.

- [ ] **Step 6: Commit** — `git add scripts/search/v2_mapping.json src/main/java/com/rewayaat/core/QueryStringQueryResult.java src/test/java/com/rewayaat/integration/CitedFromSearchIntegrationTest.java && git commit -m "feat(search): a citation field for narrations quoted from other books"`

### Definition of Done
- [REQUIRED] `CitedFromSearchIntegrationTest` green
- [REQUIRED] `mvn -B -q test -Dtest='MigrationPreservesSearchIntegrationTest,QueryStringQueryResultTest'` green (no regression in the lists' other users)

**Cleanliness self-check:** [ ] TDD red→green · [ ] no new analyzer · [ ] naming = `source_ar` peer

---

## Phase 2.2 — Card rows: Cited from, Publisher

**Objective:** A narration card shows "Cited from" (Arabic on `/ar`, English fallback) on server-rendered and Vue cards, and the Vue Publisher row is localised and escaped.

**Estimated budget:** 60 min.

**Files changed:**
- Modify: `src/main/java/com/rewayaat/service/HadithCardFactory.java:140-145`
- Modify: `src/main/resources/static/js/vue-components.js:217-243`
- Modify: `src/main/resources/messages.properties` (after `js.source`, line 207), `messages_ar.properties` (after line 198)
- Modify: `src/main/resources/static/js/rewayaat.js:1998-2020` (editor scalar fields + known keys)
- Modify: `src/main/java/com/rewayaat/mcp/tools/FetchTool.java:114-121` (metadata field list)
- Test: `src/test/java/com/rewayaat/integration/HadithApiIntegrationTest.java` (add a case) and `src/test/java/com/rewayaat/controllers/HadithCardParityTest.java` (add a case)

### Steps

- [ ] **Step 1 (TDD): Failing tests**
  - `HadithApiIntegrationTest`: seed the Phase 2.1 Mizan doc with `publisher: "ICAS Press"`, `publisher_ar: "ICAS Press"`. GET `/hadith/{id}` → body contains `Kanz al-Ummal, number:29331`. GET `/ar/hadith/{id}` → body contains `كنز العمال، ح 29331`.
  - Same test: an edit round trip (the existing update path in the test class) that changes `english` leaves `cited_from` and `cited_from_ar` present (`client.get` after the update).
  - `HadithCardParityTest`: a new `@Test bothCardsRenderTheCitationRow()` reads `vue-components.js` and asserts it contains `key: 'cited_from'` and `localeField(this.narration, 'cited_from')`. A second assertion: the publisher item uses `localeField(this.narration, 'publisher')`. Follow the file's source-reading style (`:321-366`).

- [ ] **Step 2 (TDD): Run — red.** `mvn -B -q test -Dtest='HadithApiIntegrationTest,HadithCardParityTest'` → FAIL: body lacks the citation, JS lacks the key.

- [ ] **Step 3 (Exact): Messages**

  `messages.properties`: `js.citedFrom=Cited from` · `messages_ar.properties`: `js.citedFrom=نقلاً عن`

- [ ] **Step 4 (Exact): Server card** — `HadithCardFactory.java`, insert after the `js.source` row (line 141):
  ```java
          addRow(rows, "fa fa-quote-right", label("js.citedFrom", locale),
                  localised(source, "cited_from", str(source.get("cited_from")), locale), null, locale);
  ```

- [ ] **Step 5 (Exact): Vue card** — `vue-components.js`, after the `source` item (line 224), insert:
  ```js
                        if (this.isRealValue(this.narration.cited_from)) {
                            items.push({
                                key: 'cited_from',
                                icon: 'fa fa-quote-right',
                                label: t('js.citedFrom', 'Cited from'),
                                html: localeField(this.narration, 'cited_from'),
                                clickable: false
                            });
                        }
  ```
  Then replace `html: this.narration.publisher,` (line 240) with `html: localeField(this.narration, 'publisher'),`. `localeField` already escapes and localises in `rewayaat.js:111-125`, which is the path the `source` row takes. Verify that escaping during the step: `sed -n 111,130p src/main/resources/static/js/rewayaat.js`. If it does not escape, wrap the value in `this.escapeHtml(...)`, as the `edition` row does at line 230.

- [ ] **Step 6 (Intent): Editor + MCP**
  - `rewayaat.js:2003`: add `{ key: 'cited_from', label: t('js.citedFrom', 'Cited from') },` after `publisher`. Add `'cited_from',` to `hadithEditorKnownKeys` after `'publisher'`.
  - `FetchTool.java:116`: `List.of("book", "volume", "part", "section", "chapter", "number", "source", "cited_from")`. `source` is added too, so a model can attribute a narration to its compiler.

- [ ] **Step 7 (TDD): Run — green.** Same command → PASS. Then `mvn -B -q test -Dtest='MessageCatalogueTest,McpProtocolIntegrationTest'` → PASS.

- [ ] **Step 8: Commit** — `git commit -am "feat(card): show where a narration is cited from, in both languages"`

### Definition of Done
- [REQUIRED] the four tests above green
- [REQUIRED] `grep -n "html: this.narration.publisher" src/main/resources/static/js/vue-components.js` → 0

**Cleanliness self-check:** [ ] TDD red→green · [ ] no new helper (reuses `localised`, `localeField`, `addRow`) · [ ] key in both bundles

---

## Phase 2.3 — Catalogue Arabic from the index

**Objective:** A chapter or part shows the Arabic title its own narrations carry. Existing books render exactly as today.

**Estimated budget:** 75 min.

**Problem:** `Chapter.titleAr()` (`BookCatalog.java:243-245`) looks up `ArabicNames.chapter(title)`, a global English → Arabic map (`ArabicNames.java`). R5 found 17 restored English titles that equal keys holding different Arabic (`GREED` → existing `باب الطمع`, restored `الحِرص`). The new books would render the wrong Arabic heading on `/ar`.

**Files changed:**
- Modify: `src/main/java/com/rewayaat/service/BookCatalog.java:113-114` (Part creation), `:203-215` (Part), `:240-250` (Chapter), `:440-444` (bucket read), `:487-496` (sub-aggs)
- Test: `src/test/java/com/rewayaat/integration/CatalogArabicTitlesIntegrationTest.java` (create)

### Steps

- [ ] **Step 1 (TDD): Failing test** — extends `ElasticsearchTestSupport`, mapping from `scripts/search/v2_mapping.json` (as 2.1). Seed:
  - doc A: `book "Ghurar al-Ḥikam", volume "1", chapter "Greed", chapter_ar "الحِرص"`
  - doc B: `book "Al-Kāfi", volume "1", part "The Book of Faith and Disbelief", part_ar "كتاب الإيمان والكفر", chapter "Greed"` with **no** `chapter_ar`
  Build `new BookCatalog()` and call `books()` (`:278`). It loads through the private `ensureLoaded` (`:356`) → `load` (`:398`). Read the chapters off the returned `Book`s.
  Assert:
  - the Ghurar chapter `Greed` → `titleAr() == "الحِرص"`
  - the Kāfi chapter `Greed` → `titleAr() == ArabicNames.chapter("Greed")` (the mapping fallback)
  - the Kāfi part → `titleAr() == "كتاب الإيمان والكفر"`

- [ ] **Step 2 (TDD): Run — red.** `mvn -B -q test -Dtest=CatalogArabicTitlesIntegrationTest` → FAIL: Ghurar `Greed` returns the mapping's `باب الطمع` (or null).

- [ ] **Step 3 (Structured): Implement**
  - Sub-aggregations on the composite at `:490`:
    ```java
    .aggregations("chapters", a -> a
            .composite(c -> {
                c.size(COMPOSITE_PAGE_SIZE).sources(compositeSources());
                if (cursor != null) {
                    c.after(cursor);
                }
                return c;
            })
            .aggregations("chapter_ar", s -> s.terms(t -> t.field("chapter_ar").size(1)))
            .aggregations("part_ar", s -> s.terms(t -> t.field("part_ar").size(1))))
    ```
  - Read helper (private static): `topTerm(CompositeBucket bucket, String name)` returns the first `sterms()` bucket key, or `""`.
  - `Chapter` record: append components `String titleArIndexed, String partArIndexed`. Add a secondary constructor with the old 8-argument signature that delegates with `null, null`. It keeps `IndexingSignalsTest.java:43,229` and `ShareImageCoverageTest.java:146` compiling unchanged.
  - `Chapter.titleAr()`: `return titleArIndexed == null || titleArIndexed.isBlank() ? ArabicNames.chapter(title) : titleArIndexed;`
  - `Part` record: append `String titleArIndexed`, with a secondary constructor with the old 6-argument signature (`IndexingSignalsTest.java:38`). `Part.titleAr()` follows the same rule with `ArabicNames.part(title)`.
  - `Book.parts()` `:113`: pass `chapter.partArIndexed()`.
  - Bucket loop `:442`: pass `topTerm(bucket, "chapter_ar"), topTerm(bucket, "part_ar")`.
  - Forbidden: adding `chapter_ar` as a composite **source**. It splits a chapter wherever Arabic is missing (`ArabicNames.java` javadoc). Also forbidden: deleting `ArabicNames` or the mapping files; that is a separate change (`docs/i18n.md`).

- [ ] **Step 4 (TDD): Run — green.** Same command → PASS.

- [ ] **Step 5 (Exact): Parity probe on the 18 existing books (local ES, before phase-3 import)**

  Add a temporary JUnit test `CatalogArabicParityProbe` (not committed). It builds the catalogue against `localhost:9200` alias `rewayaat_hadith`. For every chapter and part it compares the new `titleAr()` with `ArabicNames.chapter(title)` / `ArabicNames.part(title)`, and prints every difference.
  Run: `cd /home/zir0/git/rewayaat-lost-books && REWAYAAT_INDEX=rewayaat_hadith mvn -B -q test -Dtest=CatalogArabicParityProbe`
  Expected: `0 differences`. A difference means the index and the mapping disagree on an existing book. **Stop and report each one.** Do not pick a winner silently. Delete the probe file after it runs.

- [ ] **Step 6: Commit** — `git add src/main/java/com/rewayaat/service/BookCatalog.java src/test/java/com/rewayaat/integration/CatalogArabicTitlesIntegrationTest.java && git commit -m "fix(books): take a chapter's Arabic title from its own narrations"`

### Definition of Done
- [REQUIRED] test green. [REQUIRED] probe `0 differences` (receipt recorded in the commit message body)
- [REQUIRED] `mvn -B -q test -Dtest='IndexingSignalsTest,ShareImageCoverageTest,BookCatalogSlugTest'` green
- [ADVISORY] catalogue build time in the startup log (`Book catalog built: … in Nms`) stays within +150 ms of the pre-change value on local ES

**Cleanliness self-check:** [ ] TDD red→green · [ ] old constructors kept, not duplicated logic · [ ] probe deleted

---

## Phase 2.4 — Legacy v1-id redirect

**Objective:** `/?q=_id:<restored v1 id>` answers 301 to that narration's page. Every other query behaves as today.

**Estimated budget:** 40 min.

**Files changed:**
- Create: `src/main/java/com/rewayaat/controllers/LegacyIdRedirects.java`
- Modify: `src/main/java/com/rewayaat/controllers/HomeController.java:33-37, 97-105`
- Test: `src/test/java/com/rewayaat/controllers/LegacyIdRedirectsTest.java` (create), `HomeControllerTest.java:25` (constructor) + a new case

### Steps

- [ ] **Step 1 (TDD): Failing tests (Exact)**
  ```java
  package com.rewayaat.controllers;

  import org.junit.jupiter.api.Test;
  import java.util.Map;
  import java.util.Optional;
  import static org.junit.jupiter.api.Assertions.*;

  class LegacyIdRedirectsTest {

      private final LegacyIdRedirects redirects =
              new LegacyIdRedirects(Map.of("30611", "Ghurar-al-Hikam-Amidi:417"));

      @Test
      void aRestoredV1IdMovesToItsNarrationPage() {
          assertEquals(Optional.of("/hadith/Ghurar-al-Hikam-Amidi:417"), redirects.target("_id:30611"));
      }

      /** An id with no exact entry is never guessed at: a wrong target is worse than a dead link. */
      @Test
      void anUnmappedV1IdIsLeftAlone() {
          assertEquals(Optional.empty(), redirects.target("_id:AXMd1nBSoW8eiOSs8d1H"));
      }

      @Test
      void anOrdinarySearchIsNotARedirect() {
          assertEquals(Optional.empty(), redirects.target("patience _id:30611"));
          assertEquals(Optional.empty(), redirects.target(""));
          assertEquals(Optional.empty(), redirects.target(null));
      }

      @Test
      void theShippedMapCarriesTheSearchConsoleUrl() {
          assertTrue(new LegacyIdRedirects().target("_id:30611").orElse("")
                  .startsWith("/hadith/Ghurar-al-Hikam-Amidi:"));
      }
  }
  ```
  `HomeControllerTest`: change `controller()` to `new HomeController(new BookCatalog(), messages, new LegacyIdRedirects(Map.of("30611", "Ghurar-al-Hikam-Amidi:417")))`, and add:
  ```java
  @Test
  void home_movesARestoredV1IdPermanently() {
      MockHttpServletRequest request = new MockHttpServletRequest();
      String view = controller().home("_id:30611", 1, null, request, new ExtendedModelMap());
      assertEquals("redirect:/hadith/Ghurar-al-Hikam-Amidi:417", view);
      assertEquals(HttpStatus.MOVED_PERMANENTLY, request.getAttribute(View.RESPONSE_STATUS_ATTRIBUTE));
  }
  ```

- [ ] **Step 2 (TDD): Run — red.** `mvn -B -q test -Dtest='LegacyIdRedirectsTest,HomeControllerTest'` → compilation failure `cannot find symbol LegacyIdRedirects`.

- [ ] **Step 3 (Exact): Implement**
  ```java
  package com.rewayaat.controllers;

  import com.fasterxml.jackson.core.type.TypeReference;
  import com.fasterxml.jackson.databind.ObjectMapper;
  import org.springframework.core.io.ClassPathResource;
  import org.springframework.stereotype.Component;

  import java.io.IOException;
  import java.io.InputStream;
  import java.io.UncheckedIOException;
  import java.util.Map;
  import java.util.Optional;
  import java.util.regex.Matcher;
  import java.util.regex.Pattern;

  /**
   * Where the v1 narration URLs of the three restored books now live.
   *
   * <p>Before {@code /hadith/{id}} a narration's address was {@code /?q=_id:30611}, and
   * Search Console still holds some of them. Ghurar al-Ḥikam, Mīzān al-Ḥikma and Mishkāt
   * al-Anwār came back from the v1 snapshot with their old ids, so for those - and only
   * those - the old address has an exact new one. Every other v1 id stays unmapped: a
   * redirect to the wrong narration is worse than a dead link.
   */
  @Component
  public class LegacyIdRedirects {

      private static final String RESOURCE = "legacy/v1_restored_ids.json";
      private static final Pattern LEGACY_QUERY = Pattern.compile("^_id:(\\S+)$");

      private final Map<String, String> targets;

      public LegacyIdRedirects() {
          this(load());
      }

      LegacyIdRedirects(Map<String, String> targets) {
          this.targets = Map.copyOf(targets);
      }

      /** The narration page a legacy {@code _id:} query now lives at, if it has one. */
      public Optional<String> target(String query) {
          if (query == null) {
              return Optional.empty();
          }
          Matcher matcher = LEGACY_QUERY.matcher(query.trim());
          if (!matcher.matches()) {
              return Optional.empty();
          }
          return Optional.ofNullable(targets.get(matcher.group(1))).map(id -> "/hadith/" + id);
      }

      private static Map<String, String> load() {
          try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
              return new ObjectMapper().readValue(in, new TypeReference<Map<String, String>>() { });
          } catch (IOException e) {
              throw new UncheckedIOException("Could not read " + RESOURCE, e);
          }
      }
  }
  ```
  `HomeController`: add a field `private final LegacyIdRedirects legacyIds;` and a third constructor parameter. As the first statements of `home(...)`:
  ```java
          Optional<String> moved = legacyIds.target(query);
          if (moved.isPresent()) {
              // A permanent move: the old address is a bookmark or a crawler's memory of a
              // narration that now has its own page, and a 302 would keep it alive.
              if (request != null) {
                  request.setAttribute(View.RESPONSE_STATUS_ATTRIBUTE, HttpStatus.MOVED_PERMANENTLY);
              }
              return "redirect:" + moved.get();
          }
  ```
  Imports: `java.util.Optional`, `org.springframework.http.HttpStatus`, `org.springframework.web.servlet.View`.
  Throwing on a missing resource is deliberate: the file ships in the jar, so its absence is a build defect, not a runtime condition.

- [ ] **Step 4 (TDD): Run — green.** Same command → PASS.

- [ ] **Step 5: Commit** — `git add src/main/java/com/rewayaat/controllers/LegacyIdRedirects.java src/main/java/com/rewayaat/controllers/HomeController.java src/test/java/com/rewayaat/controllers/LegacyIdRedirectsTest.java src/test/java/com/rewayaat/controllers/HomeControllerTest.java && git commit -m "feat(seo): move restored v1 narration URLs to their pages"`

### Definition of Done
- [REQUIRED] tests green. [REQUIRED] (phase-3, live) `curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' 'http://localhost:8002/?q=_id:30611'` → `301 http://localhost:8002/hadith/Ghurar-al-Hikam-Amidi:<n>`
- [REQUIRED] `curl -s -o /dev/null -w '%{http_code}\n' 'http://localhost:8002/?q=_id:AXMd1nBSoW8eiOSs8d1H'` → `200` (unchanged)

**Cleanliness self-check:** [ ] TDD red→green · [ ] no ES lookup added to the home route (memory: the rejected 410 option cost one) · [ ] map loaded once

---

## Phase 2.5 — Corpus scope, names, blurbs, summaries

**Objective:** Every committed list of books knows the three books in both languages, and `TranslatedDataTest` proves it.

**Estimated budget:** 75 min (includes writing 6 short summaries).

**Files changed:**
- Modify: `src/main/java/com/rewayaat/mcp/CorpusScope.java` (count, sentence, new `NARRATION_COUNT`)
- Modify: `src/main/java/com/rewayaat/mcp/McpServerConfig.java:74,198`
- Modify: `src/main/resources/i18n/book_names_mapping.json`, `src/main/resources/i18n/source_ar_mapping.json`
- Modify: `src/main/resources/static/book_blurbs.json` (entries "MIZAN AL-HIKMAH", "Ghurar Al-Hikam", "Mishkat Al-Anwar")
- Modify: `src/main/resources/static/book_summaries.json`
- Modify: `src/main/java/com/rewayaat/controllers/BookBlurbs.java:172-174` (javadoc), `src/test/java/com/rewayaat/i18n/TranslatedDataTest.java:90,108-113`
- Modify: `src/main/java/com/rewayaat/service/ArabicNames.java:19` (javadoc counts)

### Steps

- [ ] **Step 1 (TDD): Failing test** — in `TranslatedDataTest.everyBookHasABlurbKeyedToItsPage`, replace the blank-slug `continue` (`:110-113`) with `wrong.add("(no slug) " + entry.path("book").asText())`. Every blurb must now name its page. Add to `McpProtocolIntegrationTest` a check that the `initialize` instructions contain `"Ghurar al-Ḥikam"` and `String.valueOf(CorpusScope.NARRATION_COUNT)`.
  Run: `mvn -B -q test -Dtest='TranslatedDataTest,McpProtocolIntegrationTest'` → FAIL: 3 blurbs have no slug, the instructions lack Ghurar.

- [ ] **Step 2 (Exact): CorpusScope**
  ```java
      /** Number of books indexed. Asserted against the live index by the MCP tool tests. */
      public static final int BOOK_COUNT = 21;

      /** Number of narrations indexed, for the descriptions that state the corpus size. */
      public static final int NARRATION_COUNT = 50119;

      public static final String SCOPE_SENTENCE =
              "This searches a closed corpus of " + BOOK_COUNT + " Shia hadith books "
              + "(Al-Kāfi, Ghurar al-Ḥikam, Man Lā Yaḥḍuruh al-Faqīh, Mīzān al-Ḥikma, "
              + "Nahj al-Balāgha, Mishkāt al-Anwār, Al-Amālī, Al-Khiṣāl, "
              + "Kitāb al-Ghayba, Thawāb al-Aʿmāl, ʿUyūn akhbār al-Riḍā, Maʿānī al-ʾAkhbār, "
              + "Kāmil al-Ziyārāt, Al-Tawḥīd, Muʿjam al-Aḥādīth al-Muʿtabara, Kitāb al-Zuhd, "
              + "Kitāb al-Ḍuʿafāʾ, Kitāb al-Muʾmin, Ṣifāt al-Shīʿa, Risālat al-Ḥuqūq, "
              + "Faḍaʾil al-Shīʿa). A narration that is absent is absent FROM THESE BOOKS; "
              + "that is not evidence it does not exist elsewhere in the tradition, and must "
              + "not be reported as such.";
  ```
  The order is narration count, as `BookCatalog.java:461` sorts.
  `McpServerConfig.java:74`: `.formatted(CorpusScope.BOOK_COUNT, CorpusScope.NARRATION_COUNT, CorpusScope.SCOPE_SENTENCE);`
  `:198`: `.description("Search and read " + String.format("%,d", CorpusScope.NARRATION_COUNT) + " Shia narrations from " + CorpusScope.BOOK_COUNT + " books, in Arabic "`

- [ ] **Step 3 (Exact): Name maps**
  `book_names_mapping.json` add: `"Ghurar al-Ḥikam": {"ar": "غرر الحكم ودرر الكلم"}`, `"Mīzān al-Ḥikma": {"ar": "ميزان الحكمة"}`, `"Mishkāt al-Anwār": {"ar": "مشكاة الأنوار في غرر الأخبار"}`. Match each existing entry's shape: open the file and copy the key set of the `"Al-Kāfi"` entry.
  `source_ar_mapping.json` add the three `source` → `source_ar` pairs from `BOOKS` in `scripts/ingest/convert_v1_books.py`.

- [ ] **Step 4 (Structured): Blurbs** — the three entries:
  - `book` → `"Mīzān al-Ḥikma"`, `"Ghurar al-Ḥikam"`, `"Mishkāt al-Anwār"`. `slug` → `"mizan-al-hikma"`, `"ghurar-al-hikam"`, `"mishkat-al-anwar"`.
  - `blurb_ar` corrections (DEC-207), each a sentence-level edit:
    - Mishkāt: «من تأليف الشيخ أبي علي الفضل بن الحسن الطبرسي … صاحب التفسير الشهير «مجمع البيان»» → «جمعه علي بن الحسن الطبرسي، حفيد الفضل بن الحسن الطبرسي صاحب «مجمع البيان»». «خمسة عشر باباً» → «عشرة أبواب».
    - Mīzān: state that this is «منتخب ميزان الحكمة», the abridgement, translated by ICAS Press, and that the full work is ten volumes.
    - Ghurar: remove the claim that this edition is arranged by the Arabic alphabet. It is arranged by topic.
  - Check each correction against the English `blurb` of the same entry. Do not add facts that the English blurb does not state.
  - Move the three entries into catalogue order: after `Al-Kāfi` comes Ghurar, then after `Man Lā Yaḥḍuruh al-Faqīh` comes Mīzān, then after `Nahj al-Balāgha` comes Mishkāt. Cosmetic only, so the diff is easier to read.

- [ ] **Step 5 (Structured): Book summaries** — add `"ghurar-al-hikam"`, `"mizan-al-hikma"`, `"mishkat-al-anwar"` with `{"en": …, "ar": …}`:
  - Shape and length match the `"kitab-al-ghayba"` entry (`book_summaries.json:24`). The facts come only from that book's blurb and from this corpus: narration count, chapter count from R5, translator or publisher.
  - Mīzān must say it is a modern topical compilation whose narrations are quoted from earlier books, including Sunni ones (English blurb point 4), each with a "Cited from" line.
  - Ghurar must say that its sayings carry no chain of narrators.
  - Copy rules: memory `blog-content-teaches-hadith-not-site` and `platform-name-hadith-database`. Never "Rewayaat". Run the `humanizer` skill over the English.

- [ ] **Step 6 (Intent): Comments**
  - `BookBlurbs.java:172-174`: delete the "fallback stays for the three entries…" paragraph. The fallback code stays: it serves blurbs added later without a slug (`:50`).
  - `TranslatedDataTest.java:90`: "covers all eighteen books" → "covers every book".
  - `ArabicNames.java:19`: "18 books" → "21 books". Leave the other counts in that javadoc unchanged.

- [ ] **Step 7 (TDD): Run — green.** `mvn -B -q test -Dtest='TranslatedDataTest,BookBlurbsTest,McpProtocolIntegrationTest,MessageCatalogueTest'` → PASS.

- [ ] **Step 8: Commit** — `git commit -am "feat(books): name, introduce and scope the three restored books"`

### Definition of Done
- [REQUIRED] tests green
- [REQUIRED] `grep -rn "32519" src/main/java` → 0 · `grep -n "BOOK_COUNT = 21" src/main/java/com/rewayaat/mcp/CorpusScope.java` → 1

**Cleanliness self-check:** [ ] TDD red→green · [ ] no count outside `CorpusScope` · [ ] summaries pass humanizer

---

## Phase 2 milestone gate

```bash
cd /home/zir0/git/rewayaat-lost-books && mvn -B test -Dtest='!HodaAlQuranQualityCheck' -DfailIfNoSpecifiedTests=false
```
Expected: BUILD SUCCESS, the same test count as `origin/master` + the new tests. Run once, after every slice is green.

## Maintainability summary
- `cited_from` reuses the `source`/`source_ar` mapping, card row, `localeField` and the editor field list.
- Titles: `ArabicNames` stays as the fallback. The index becomes the primary source per bucket.
- Redirect: Spring `RedirectView` + `View.RESPONSE_STATUS_ATTRIBUTE`. No new filter, no ES call.

## NTH notes
- `FindSimilarTool.java:21,65` and `VersesForHadithTool.java:20,66` state measured counts (25,273 / 22,635 of 32,519). They are updated in phase-5 with re-measured values, not here.
- `ArabicNames` and the four `*_ar_mapping.json` files can go entirely once DEC-204 holds for parts and sections too (`docs/i18n.md`).
- `NarrationRepository.SUMMARY_FIELDS:53-55` omits `source`. A summary row cannot name the compiler.

## Risks
- DEC-204 parity probe finds differences on existing books → stop; the user decides index versus mapping.
- `McpProtocolIntegrationTest` runs against local ES, which still holds 18 books until phase-3. Any assertion of `BOOK_COUNT` against a live aggregation fails until phase-3 Step "import". Run that class again after phase-3.
