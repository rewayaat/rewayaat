# Releasing the Arabic site

`feature/arabic-seo` is 50-odd commits and 100-odd files against `master`. It adds a
second language at `/ar`, and on the way it rewrites the header, the stylesheet and most
of the browser code that the **English** site also runs.

Every step below can be undone. That is the organising principle rather than a section
at the end, because one step — being indexed — cannot be undone cheaply, so the release
is arranged to reach that step last, alone, and by an explicit switch.

`docs/arabic-data-migration.md` is the detail of the Elasticsearch half.

## The shape of it

| # | Step | Undo | What the undo costs |
|---|------|------|---------------------|
| 0 | Rebase onto `master` | `git reset` | Nothing |
| 1 | Load `_ar` into the production index | `rollback_ar_fields.py` | Minutes. The English is never touched |
| 2 | Merge, which deploys, with `ARABIC_INDEXABLE=false` | Revert the merge, or repoint the image tag in `k8s/kustomization.yaml` | One CI run, or one sync. Nothing was advertised, so nothing is lost |
| 3 | Verify on the live host | — | Read-only |
| 4 | `ARABIC_INDEXABLE=true` | Set it back to `false` | **Free until Google crawls, then expensive.** The only step that decays |

Steps 1 and 2 are independent and commute, but the order matters for what a reader sees
in between — see below.

## Why the data goes first

**Merging is deploying.** `.github/workflows/ci-cd.yml` triggers on a push to `master`
filtered on `src/**`, which this branch touches heavily. There is no staging environment
and no manual gate: the merge commit builds an image, pushes it, and updates
`k8s/kustomization.yaml`, which Argo picks up.

**Writing `_ar` into production renders nothing today, but it is not inert.** Nothing
*displays* the new fields: `HadithObject` carries
`@JsonIgnoreProperties(ignoreUnknown = true)` on `master` as well as here, so the running
code reads straight past them. But `QueryStringQueryResult.SEARCHABLE_FIELDS` names
`book_ar`, `chapter_ar.text`, `part_ar.text`, `section_ar.text` and `source_ar.text`, and
that file is **byte-identical on master** — the fields are empty today and they are
searched today. Filling them changes what search can match, against the code already
running, the moment the data lands.

That is the one place this release can affect the live English site before anything is
deployed, so it has a gate of its own; see below.

Do it the other way and there is a window — however long the load takes — in which the
Arabic site exists with English chapter, part and section names on every page. The switch
in step 2 means that window would not be *indexed*, so it is survivable; it is still an
avoidable hour of a half-finished site being live.

## The switch

`ARABIC_INDEXABLE` is the one thing standing between "deployed" and "irreversible". With
it `false`:

- every `/ar` response carries `X-Robots-Tag: noindex` — a header rather than a
  `robots.txt` `Disallow`, because a blocked URL still gets indexed from links and a
  crawler that cannot fetch the page never reads the directive telling it to stay away.
  `robots.txt` says this in its own comments; it was learned from `/edit` and
  `/signin.html` turning up in Search Console as "Indexed, though blocked by robots.txt".
- the sitemaps list only the English half of each pair, with no `hreflang` annotation,
  because an alternate naming a noindex URL is a pair Google discards rather than follows.
- the Arabic site is otherwise completely live: it answers, in Arabic, and can be walked
  in production.

It lives in `k8s/deployment.yaml`, so flipping it is a sync rather than a build — the
pattern `OPENAI_APPS_CHALLENGE` already uses. The default in `application.yaml` is
`true`, which is the steady state; production holds the `false` for the length of the
release and drops it afterwards.

`ArabicIndexingGateIntegrationTest` covers both positions, because a switch verified in
one position is a constant. It also covers the part that is easy to get wrong: the
noindex filter has to run *before* the filter that forwards `/ar/books` to the English
handler. Both match the same patterns and both defaulted to the lowest precedence, so
the header was set or not depending on registration order. Removing the explicit
ordering makes two of those tests fail, which is how the ordering is known to matter.

## Before anything

### The branch is behind master

`1d8b3df ops: limit Meta's crawler, refuse two scraper networks, and give the JVM half
the container (#97)`. Rebase and re-run the suite — it changes the JVM heap, which is the
sort of thing that only shows up under the load a release brings.

### What the tests do and do not cover

The suite is 599 now and CI runs all of it (below). Two limits are worth knowing before
leaning on it.

**Most integration tests run against a toy mapping.** `ElasticsearchTestSupport` creates
its index from a dynamic template that turns every string into `text` with fielddata and
a `standard` search analyzer. No `arabic_norm`, no `english_fold`, no `.text` sub-fields
on the metadata. That is fine for the tests that use it — they check routing, shape and
links — and it is worthless for any question about matching or ranking, because on that
mapping `chapter_ar.text` does not exist and the clause silently never fires.
`MigrationPreservesSearchIntegrationTest` builds its index from
`scripts/search/v2_mapping.json`, the mapping production runs, for exactly that reason.

**Seven documents is not 32,519.** That test can show the migration does not change
*which* narrations an English query matches. It cannot speak to a real term
distribution, a real relevance curve, or the tuning the ranking boost was swept against.
The before-and-after snapshot in Phase 1 is what covers that, and it is a gate rather
than an optional extra.

### CI now runs the integration tests

It did not. The workflow excluded `*IntegrationTest` because a runner has no
Elasticsearch, and that was 65 of the 590 tests — all of the end-to-end cover for the
Arabic site: the sweep that proves no Arabic page drops the reader onto the English one,
the canonical checks, the sitemap pairing, the hreflang reciprocity. A green build said
nothing about any of it.

`ElasticsearchTestSupport` already knew how to start a container; nothing used it. CI
passes `-Dtestcontainers.enabled=true` now and runs all 590. The image is pinned to
**9.0.2, the version the cluster runs** — it was 9.2.4 while only a laptop ever started
it, and a laptop has its own Elasticsearch anyway. Now that a green build depends on it,
a version difference between the container and the cluster is a difference between a
green build and the site.

`HodaAlQuranQualityCheck` stays excluded. It scrapes hodaalquran.com to compare
extraction against the live site, so it fails on their bad day rather than ours.

One release blocker came out of looking at this. `ArabicLinksStayArabicTest` was new on
this branch, extended `ElasticsearchTestSupport`, and did not end in `IntegrationTest` —
so CI would have run it with no Elasticsearch, all five would have errored with
`Connection refused`, and `verify` failing would have blocked `deploy`. Confirmed by
pointing the suite at a dead port. It is renamed, and with the container in CI the name
now only decides whether a test can run on a laptop without Docker.

## The sequence

### Phase 1 — data, while the old code is still running

Nothing a reader can see *rendered* changes, but search is a different matter — see
above, and take the snapshot. Abandon and re-run it freely; it checkpoints per field.

```bash
kubectl port-forward -n elastic-v2 svc/elasticsearch-v2 9201:9200
kubectl get pods -n elastic-v2 | grep snapshot      # a recent one, before writing 32,519 docs
python3 -m scripts.i18n.translate_tier1 --es-host http://localhost:9201 --apply --dry-run
python3 -m scripts.i18n.translate_tier1 --es-host http://localhost:9201 --apply
```

Read the per-field `No mapping` count as a question, not a rounding error. As of
2026-10-01 every field reports **0**, including `chapter` — but production is edited
continuously, so the dry run is only valid for the apply that follows it immediately.

The dry run across all five fields took **19 minutes**; it is read-only scrolls, and the
apply adds bulk writes on top. The index grows about 20%.

**Snapshot search either side of the apply.** This is the gate, not a nicety: the Arabic
fields are already in `SEARCHABLE_FIELDS`, so this write is the one step that can move
English search on the live site.

```bash
python3 -m scripts.i18n.search_snapshot --out before.json      # BEFORE the apply
# ... translate_tier1 --apply ...
python3 -m scripts.i18n.search_snapshot --out after.json
python3 -m scripts.i18n.search_snapshot --compare before.json after.json
```

It issues the same `GET /v1/narrations` the website does and writes nothing. A snapshot
taken against production on 2026-10-01, before any of this ran, is committed at
`scripts/data/search-snapshots/prod-before-arabic-load.json` — it can only be taken
before, so it was.

What must come back unchanged: **which narrations each English query returns, and how
many**. What is expected to change: the Arabic queries, which is the point, and the
*order* of results that score within a hair of each other.

That last one is measured rather than waved away. Against the production mapping, adding
the Arabic metadata left the relative order of two documents alone and moved both their
absolute scores — 0.18513/0.18232 to 0.10698/0.10536 for `prayer`. Through the site's own
query, which adds boosts and a metadata ranking lift, a pair that close can swap.
`MigrationPreservesSearchIntegrationTest` demonstrates both: the matching set and the
counts hold, and a phrase query over two documents with an identical `part.text` swaps
them. A tie breaking differently is not relevance moving. **A changed set is**, and that
is what `--compare` fails on.

**Undo:**

```bash
python3 -m scripts.i18n.rollback_ar_fields \
    --es-host http://localhost:9201 --fields chapter_ar,book_ar,part_ar,section_ar,source_ar --dry-run
```

It resolves the alias to the concrete index and removes the `_ar` fields from every
document that carries them; the English is never touched. Dry-run verified against the
development index, where it correctly found 32,516 `chapter_ar` and 32,519 `book_ar`.

One part of step 1 is **not** reversible, and it does not matter: the `_ar` entries added
to the index *mapping* stay. Elasticsearch cannot drop a field from a mapping without a
reindex. An empty mapped field costs nothing and the next reindex clears it.

#### Phase 1 was run on 2026-10-01 and it is done

Fresh snapshot `rewayaat-2026.10.01-wl7huvlhsiaujtp54bocba` taken first: SUCCESS, 18/18
shards, includes `rewayaat_hadith_20260909`. Then the apply, 22:28 to about 23:30, five
fields, **32,519 updated and 0 errors each, `No mapping: 0` on every field**. Cluster
green throughout and the site answered in about a second the whole way.

The before and after snapshots are committed under `scripts/data/search-snapshots/`.

**The gate failed, and the gate was wrong.** It reported eight of twenty English queries
whose first page changed. Every English *count* was identical — 4,210 to 4,210 for
`prayer`, 8,360 to 8,360 for `hassan`, 22 to 22 for `"pledge of allegiance"` — so not one
narration was gained or lost. The churn was the first twenty of a tie:

| query | distinct scores in top 40 | first page |
|---|---|---|
| `"the book of prayer"` | **1** — 927 narrations scored identically | churned |
| `commerce`, `mercy`, `hassan` | 4–6 | churned |
| `zakat` | 30 | **identical** |
| `"pledge of allegiance"` | 19 of 22 | **identical** |

Churn where tied, none where separated, in every case. That is the behaviour
`MigrationPreservesSearchIntegrationTest` documented *before* the migration ran, which is
the only reason this reads as a mis-specified check rather than as a convenient
explanation found afterwards. The gate is the count now; the set is reported and
`--scores` answers the follow-up.

The Arabic half did what it was for: `صلاة` 673 → 993, `الزكاة` 349 → 902, `الخصال` 51 →
1,332, `الكافي` 221 → capped at 10,000.

### Phase 2 — merge, which deploys, with the switch off

Confirm `k8s/deployment.yaml` still has `ARABIC_INDEXABLE: "false"`, then merge.

```bash
kubectl rollout status -n rewayaat-v2 deploy/rewayaat-v2
```

The HPA is pinned at 2 pods, so this is a two-pod rolling replacement behind a PDB.

**Undo:** revert the merge — CI deploys the revert the same way it deployed the change —
or, for an immediate stop without waiting for a build, repoint `k8s/kustomization.yaml`
at the previous image tag. Because nothing was advertised, a revert here leaves no trace
in anyone's index.

### Phase 3 — verify on the live host

A merged fix can be unpushed and a rolling deploy takes time, so every check names the
real host and is worth repeating once the rollout reports complete.

```bash
# the Arabic site exists, is Arabic, and is held back
curl -sI https://hadith.academyofislam.com/ar/books/al-kafi | grep -i x-robots-tag   # noindex
curl -s  https://hadith.academyofislam.com/ar/books/al-kafi | grep -c 'dir="rtl"'

# the data landed where a reader can see it: this must print an Arabic chapter title
curl -s https://hadith.academyofislam.com/ar/books/al-kafi/part/the-book-on-virtue-of-knowledge \
  | grep -oE '<h1[^>]*>[^<]*</h1>'

# the switch is doing its other half
curl -s https://hadith.academyofislam.com/sitemap-static.xml | grep -c '/ar/'        # 0

# three URLs that were static files and are now controllers, at the same paths
for u in /search_tips.html /updates.html /signin.html; do
  curl -s -o /dev/null -w "%{http_code} $u\n" "https://hadith.academyofislam.com$u"; done

# a page with no Arabic version still 404s rather than rendering English under /ar
curl -s -o /dev/null -w '%{http_code}\n' https://hadith.academyofislam.com/ar/swagger-ui.html
```

**The English site is the thing most likely to be broken here, and the thing nobody will
think to check.** This branch rewrites `manuscript.css` (+1,363 lines), `rewayaat.js`
(+821), the shared header fragments and `index.html`. The 590 tests are almost all
server-side; there is no visual regression suite. Walk these by hand on the live host, at
a phone width and a desktop one:

- the home page: type a query, pick a suggestion, press Enter, get results
- a results page: the header search field, the refine facets, a tag filter
- a narration: related hadith opens *below* the narration rather than navigating away
- the share-as-image modal, and a PDF export from a collection
- sign in, and the profile chip in the corner
- `/books`, a book, a volume, a part, a chapter
- `/updates.html`: the permalinks, and the connector videos

Take as long as this needs. Nothing is decaying yet.

#### A deploy lands in two steps, and they are not atomic

Worth knowing before the next one. CI pushes the image, then pushes a second commit
updating the tag in `k8s/kustomization.yaml`. Argo polls, so it can — and on 2026-10-01
did — sync the merge commit in the gap between those two pushes. The result was one
rollout applying the new `deployment.yaml` onto pods still running the *old* image,
followed minutes later by a second rollout once the tag commit was seen.

Harmless here: the new manifest added `ARABIC_INDEXABLE`, which old code ignores, and
`/ar` simply 404ed until the real image landed. It would not be harmless for a change
where a manifest and the code that reads it have to arrive together. If that ever comes
up, watch the image tag rather than `kubectl rollout status`, which reports success for
the first rollout as readily as the second.

### Phase 4 — the switch, and the point of no return

Set `ARABIC_INDEXABLE` to `"true"` in `k8s/deployment.yaml`, commit, let Argo sync.

There is no further throttle. `robots.txt` names `/sitemap.xml`, the index names
`sitemap-books.xml`, and that file starts advertising 3,912 Arabic URLs on the next
crawl.

**Undo:** set it back to `false`. That is free for as long as Google has not crawled the
sitemap, and the window is hours rather than minutes. After that, withdrawing `/ar` means
roughly 3,900 indexed URLs going dark, and the honest version is a `410` or a redirect to
each English twin rather than a silent 404 — which is more work than fixing most things
that would prompt it.

Then watch, over days: Search Console for `/ar` coverage, hreflang pairing and any spike
in duplicates; the ingress rate limit (`limit-rpm: 50`) in case a crawler finds 4,000 new
URLs faster than that allows; and the Prometheus rules that already alert on CPU, memory
and traffic pressure.

## Done

The whole sequence ran on 2026-10-01 and 2026-10-02.

| Phase | Outcome |
|---|---|
| 1 — data | 32,519 × 5 fields, 0 errors, `No mapping: 0`; no English query changed its result count |
| 2 — deploy | PR #100, merged held back; 605 tests green with Elasticsearch in CI for the first time |
| 3 — verify | automated checks passed; the English walk was done by the site's owner |
| 4 — the switch | `ef4f0d3`, Argo synced; 3,914 Arabic URLs advertised, 0 dangling alternates |

Two bugs were found by a person reading the pages, not by the suite, and both are fixed:
the feedback toast built its element as `var t` and so invoked a `<div>` every time it
called the `t()` helper — broken on every page in both languages — and account mail
pointed at the `rewayaat.info` mirror with an English link for Arabic readers. Both now
have tests; the first has a guard against the whole class of shadowing.

One thing reported as a mobile bug was not one. The search-mode dropdown measures 37px
off-screen at 390px and 107px at 320px, but the control that opens it is
`display: none` below 768px and it lives inside a shell with `overflow: hidden` — an
unreachable element parked somewhere harmless, with the same rule on master. A fix was
written and reverted rather than ship dead CSS. The real observation is a product one:
**a reader on a phone cannot choose Precise or Flexible at all** and always gets the
default. That predates this release.

## Decided

**The nineteen footnotes stay English-only, which means absent.** `notes` exists on 19
Al-Khiṣāl narrations in production and nowhere else; `notes_ar` does not exist, and
`HadithCardFactory.notesFor` returns the Arabic or nothing rather than falling back. An
Arabic reader of those 19 sees no note where an English reader sees several hundred words
of translator's commentary. That is accepted: they are scholarly footnotes citing Lane's
Lexicon and Biḥār by volume and page, and an English wall of text under an Arabic heading
is worse than an absence on a page whose purpose is to be Arabic. Nothing blocks on it and
nothing is waiting to be written.

**All 32,519 narrations have an Arabic chapter title.** Sixteen did not, in fourteen
distinct spellings; they are mapped now. Eleven came out of the development index by
document id — the same chapters, already translated, keyed there under the English
production has since reworded. Three had no Arabic anywhere and were written from the
narration's own matn: two Man Lā Yaḥḍuruh bab titles whose English is corrupt in the
corpus, and one Al-Khiṣāl. Those three are the only hand-written entries in
`chapter_ar_mapping.json` and are worth a reviewer's eye:

| id | Arabic written here |
|---|---|
| `Man-La-Yahduruh-al-Faqih-Volume-2-Saduq:71` | باب الحق المعلوم والماعون |
| `Man-La-Yahduruh-al-Faqih-Volume-4-Saduq:398` | باب أم الولد تقتل سيدها خطأ أو عمدا |
| `Al-Khisal-Saduq:976` | أيد الله العقل بعشرة أشياء |

They will drift again. Production is edited through `/edit` — the notes went from 15 to
19 and the unmatched chapters from 3 to 16 in four days — so re-run the dry run
immediately before the apply and expect a handful more. The fallback to English is
designed for exactly that, so a few unmapped titles delay nothing.

## Not covered

**`llm_similar.reason_ar`** — 43,112 translated reasons, deliberately dropped. The
related-hadith panel does not show the match reason on the Arabic site in either
implementation, because the reason is written in English by the model that judged the
pair and reads as a machine note. The chunks are under
`scripts/data/llm_similar_reason_batches/` if that is ever reversed.

**`search_tips.html` has Arabic prose and English examples.** A field filter is keyed on
the English field name *and* the English value — `book:"الكافي"` matches nothing, checked
against the running index — so the Arabic page teaches the English syntax and says why.
Making Arabic values work is a query-parser change, not part of this release.
