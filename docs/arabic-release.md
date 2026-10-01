# Releasing the Arabic site

`feature/arabic-seo` is 50 commits and 102 files against `master`. It adds a second
language at `/ar`, and on the way it rewrites the header, the stylesheet and most of the
browser code that the **English** site also runs. This is how it goes out, in what order,
and how to tell at each point whether to carry on.

`docs/arabic-data-migration.md` is the detail of the Elasticsearch half. This is the
order the two halves go in and why that order is not negotiable.

## The two facts that decide everything

**Merging is deploying.** `.github/workflows/ci-cd.yml` triggers on a push to `master`
filtered on `src/**`, which this branch touches heavily. There is no staging environment
and no manual gate: the merge commit builds an image, pushes it, and updates
`k8s/kustomization.yaml`, which Argo picks up. The window between "merged" and "live" is
a CI run.

**The data migration is backward-compatible; the code is not reversible.** Writing
`chapter_ar` and its siblings into production today changes nothing a reader sees,
because `HadithObject` carries `@JsonIgnoreProperties(ignoreUnknown = true)` on `master`
as well as here — the running code reads straight past them. Deploying the code, by
contrast, publishes about 3,900 Arabic URLs into `sitemap-books.xml`, and once Google has
them, withdrawing them is a 404 for every one.

So: **data first, code second.** In that order, there is no window in which the Arabic
site exists with English narration metadata on it. In the other order there is, and its
length is however long the apply takes.

## Before anything: three things to settle

### 1. The branch is one commit behind master

`1d8b3df ops: limit Meta's crawler, refuse two scraper networks, and give the JVM half
the container (#97)`. Rebase or merge it in and re-run the suite before anything else —
it touches the JVM heap setting, which is the sort of thing that only shows up under the
load a release brings.

### 2. CI runs 525 of the 590 tests

The workflow excludes `*IntegrationTest` because a runner has no Elasticsearch. That is
65 tests, and it is most of what covers the Arabic site end to end: the link sweep that
proves no Arabic page drops the reader onto the English one, the canonical checks, the
sitemap pairing, the hreflang reciprocity. **A green PR does not mean those passed.** Run
`mvn test` locally against a live Elasticsearch and read the 590, every time, before
merging.

This also caught a release blocker. `ArabicLinksStayArabicTest` was new on this branch,
extended `ElasticsearchTestSupport`, and did *not* end in `IntegrationTest` — so CI would
have run it, all five tests would have errored with `Connection refused`, and `verify`
failing would have blocked `deploy`. Verified by pointing the suite at a dead port. It is
renamed `ArabicLinksStayArabicIntegrationTest` now. **Any future test that boots the app
needs that suffix**, or the next release stalls on a failure that reproduces nowhere a
developer looks.

### 3. Two content decisions that are not the code's to make

**Sixteen chapter titles have no Arabic.** Fourteen are Al-Khiṣāl, reworded in production
after the mapping was keyed against it; two are corrupt English in the corpus. They fall
back to English on the Arabic page, by design. It was three of them on 2026-09-27 and is
sixteen now, so the number will be different again by the time anyone reads this —
production is being edited. Either re-reconcile right before the apply or accept the
fallback and fix later. Either is defensible; drifting into one without noticing is not.

**Nineteen narrations lose a footnote on the Arabic site.** `notes` exists on 19
Al-Khiṣāl narrations in production and nowhere else; `notes_ar` does not exist at all,
and `HadithCardFactory.notesFor` returns the Arabic or nothing rather than falling back.
They are a translator's scholarly footnotes, several hundred words each, citing Lane's
Lexicon and Biḥār — editorial work, not a lookup. Until somebody writes them, an Arabic
reader of those 19 sees less than an English one, silently.

## The sequence

### Phase 1 — data, while the old code is still running

Nothing a reader can see changes in this phase. It can be abandoned at any point and
re-run; it checkpoints per field.

```bash
kubectl port-forward -n elastic-v2 svc/elasticsearch-v2 9201:9200
kubectl get pods -n elastic-v2 | grep snapshot      # a recent one, before writing 32,519 docs
python3 -m scripts.i18n.translate_tier1 --es-host http://localhost:9201 --apply --dry-run
python3 -m scripts.i18n.translate_tier1 --es-host http://localhost:9201 --apply
```

The dry run took **19 minutes** on 2026-10-01 and is five read-only scrolls; the apply
does the same scrolls and writes in batches of 500, so budget appreciably more. Read the
per-field `No mapping` count as a question, not a rounding error — that is what caught
the drift above.

**Gate:** the counts in `docs/arabic-data-migration.md` are what production reports back,
and the index has grown by roughly 20% with disk to spare. Nothing about the English site
has changed; confirm that by loading it.

### Phase 2 — merge, which deploys

Merge to `master`. CI builds, pushes and repoints Argo. Watch the rollout rather than
assuming it.

```bash
kubectl rollout status -n rewayaat-v2 deploy/rewayaat-v2
```

The HPA is pinned at 2 pods, so this is a two-pod rolling replacement behind a PDB.

### Phase 3 — verify against the live host, not against localhost

A merged fix can be unpushed and a rolling deploy takes time, so every check here names
the real host and is worth repeating once the rollout reports complete.

```bash
# the Arabic site exists and is Arabic
curl -s https://hadith.academyofislam.com/ar/books/al-kafi | grep -c 'dir="rtl"'

# the data landed where a reader can see it: this must print an Arabic chapter title
curl -s https://hadith.academyofislam.com/ar/books/al-kafi/part/the-book-on-virtue-of-knowledge \
  | grep -oE '<h1[^>]*>[^<]*</h1>'

# three URLs that were static files and are now controllers, at the same paths
for u in /search_tips.html /updates.html /signin.html; do
  curl -s -o /dev/null -w "%{http_code} $u\n" "https://hadith.academyofislam.com$u"; done

# both halves of a pair, and the pair declared on both
curl -s https://hadith.academyofislam.com/ | grep -c 'rel="alternate"'      # 3
curl -s https://hadith.academyofislam.com/ar/ | grep -c 'rel="alternate"'   # 3

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

### Phase 4 — let it be found

There is no throttle between the deploy and Google. `robots.txt` names
`/sitemap.xml`, the sitemap index names `sitemap-books.xml`, and that file advertises
3,912 Arabic URLs the moment the code is live. Phase 1 is what makes that safe.

If a brake is wanted anyway — to verify in production for a day with nothing indexable —
the only one available is a temporary `Disallow: /ar/` in `robots.txt`, added in the same
release and removed in a follow-up. It costs a Search Console warning about sitemap URLs
being blocked, and it must actually be removed; a forgotten Disallow is indistinguishable
from a deliberate one.

Then watch, over days rather than minutes: Search Console for coverage of `/ar`, hreflang
pairing and any spike in duplicates; the ingress rate limit (`limit-rpm: 50`) in case a
crawler discovers 4,000 new URLs faster than that allows; and the Prometheus rules that
already alert on CPU, memory and traffic pressure.

## Rolling back

**The data.** `scripts/i18n/rollback_ar_fields.py --es-host … --fields chapter_ar
--dry-run` removes `_ar` fields and never touches the English. With the new code running,
a rollback returns the Arabic site to its English-metadata fallback rather than breaking
it.

**The code.** Revert the merge on `master`; CI builds and deploys the revert the same way
it deployed the change. For an immediate stop without waiting for a build, repoint
`k8s/kustomization.yaml` at the previous image tag.

**Where it stops being cheap.** A code rollback in the first hours costs nothing. After
Google has crawled the sitemap, it turns roughly 3,900 indexed Arabic URLs into 404s, and
getting them back is slower than losing them. If `/ar` has to come out after that, the
honest version is a `410` or a redirect to the English twin, not a silent withdrawal —
which is more work than fixing most things that would prompt it.

## What this does not cover

**`notes_ar`** — the 19 footnotes, above. No script, because translating them is
editorial.

**`llm_similar.reason_ar`** — 43,112 translated reasons, deliberately dropped. The
related-hadith panel does not show the match reason on the Arabic site in either
implementation, because the reason is written in English by the model that judged the
pair and reads as a machine note. The chunks are still under
`scripts/data/llm_similar_reason_batches/` if that is ever reversed.

**`search_tips.html` has Arabic prose and English examples.** A field filter is keyed on
the English field name *and* the English value — `book:"الكافي"` matches nothing, checked
against the running index — so the Arabic page teaches the English syntax and says why.
Making Arabic values work is a query-parser change, not part of this release.
