# The rijāl tab

A specification. The reader is a scholar of hadith who wants to judge an isnād, and the
question he is asking is not «what does this narration say» but «who carried it, and what
is each of them worth». Today he can read the chain in the text and tap a name for a card.
This gives him the chain as a chain: every link in order, who each man is, where the chain
is strong and where it is open.

## Where it goes

A fourth panel in the hadith card's sidecar rail, **second, immediately after DETAILS** and
before RELATED. The rail already carries DETAILS / RELATED / TAFSIR as
`.hadith-sidecar__rail-btn` buttons with `data-hub-panel="metadata|similar|quran"`, switched
by `hub-pages.js`; this adds `data-hub-panel="rijal"` with the label `#{sidecar.rijal}` and
the icon `fa fa-link`. The badge carries the number of men the chain names.

It appears on every page that renders `fragments/hadith-card.html` — the hadith page, a
chapter, a narrator's narrations — on both locales, and is omitted for a narration whose
document carries no `chain_people`.

## What it is built from

`chain_people` is already on every hadith document: every name, Imam, collective phrase and
relative in the isnād **in the order the chain says them**, linked or not. Each item:

```
{"s": 3, "e": 25, "t": "مُحَمَّدُ بْنُ يَحْيَى", "p": "n034836",
 "g": "muhammad-ibn-yahya-al-attar", "k": "name", "o": "convention"}
```

- `t` the words as the isnād writes them, `s`/`e` their offsets in `arabicChain`
- `p` the person, absent where no man was identified; `g` his slug
- `k` the kind: `name`, `imam`, `collective` («بعض أصحابنا»), `relative` («أبيه»)
- `o` how he was resolved: `one` (one candidate), `convention` (a book's own usage),
  `settled` (agents), `collective`, or absent

Nothing in the panel is computed from the narration text at render time: the resolver did
that work offline and this reads it.

## The two layers, and why they are split

**Rendered by the server** (invariant 8, and so the panel is instant and works without
script): the chain itself — every link in order, the words as written, a link to the man's
page where there is one, and the kind of each link. A crawler and a reader without script
get the full chain.

**Fetched when the tab is first opened**: what the books say of each man — his grade word,
the book that says it, his dates, his city. One request for the whole chain:

```
GET /v1/narrators/briefs?ids=n034836,n000966,n000770
→ {"ok": true, "narrators": {"n034836": { …the preview projection… }}}
```

The projection is exactly the one `/v1/narrators/{id}/preview` already returns — add a
`briefs` method to `NarratorController` that loops the same field list, capped at 40 ids,
and share the projection as one private helper rather than writing it twice. The browser
caches by id in the same map `narrator-preview.js` already keeps, so opening the tab warms
the preview cards and tapping a name in the text afterwards costs nothing.

A chapter page renders twenty cards; none of them fetch anything until a reader opens a
panel, and each panel fetches once.

## The chain, drawn

A vertical chain down the panel, one link per man, read top to bottom in the direction the
isnād runs — from the compiler's own shaykh down to the Imam. Each link is a list item in
an ordered list; the visual chain is CSS over honest markup.

```
┌──────────────────────────────┐
│  ◆  مُحَمَّدُ بْنُ يَحْيَى           │  ← the words the isnād uses
│     محمد بن يحيى العطار القمي  │  ← who he is (display name)
│     ثقة · رجال النجاشي         │  ← the book's own word, and whose
│     الصادق · الكاظم · ت ٢٥٤    │  ← era and date, where the books give them
└──────────────────────────────┘
          ╎ link
┌──────────────────────────────┐
│  ◇  مُحَمَّدِ بْنِ عُثْمَانَ          │
│     لم يُعرف                   │  ← named, but no book holds an entry
└──────────────────────────────┘
          ╎ link
┌──────────────────────────────┐
│  ⬡  بَعْضِ مَشَايِخِهِ              │  ← a collective: not a man, an omission
└──────────────────────────────┘
          ╎
┌──────────────────────────────┐
│  ★  أَبِي عَبْدِ الله               │
│     الإمام الصادق عليه السلام    │
└──────────────────────────────┘
```

Four kinds of link, each visibly different:

| kind | mark | treatment |
|---|---|---|
| identified man | filled gold diamond | full card, grade coloured by leaning |
| named, unidentified | hollow diamond | the words, and «no book holds an entry under this name» |
| collective phrase | hexagon, dashed connector | the phrase, and what it means: a step the compiler did not name |
| Imam | star, gold | his title; no grading, ever |

The connector between two links is a drawn line; where the step is a collective or an
unidentified man the connector is **dashed**, because that is exactly where the chain is
open. A reader should be able to see the weak joint without reading a word.

Colour by standing is allowed **here** and nowhere else. In the hadith text the names stay
plain — a chain must read as the compiler wrote it — but this panel is apparatus, and the
reader opened it to be told.

## The animation

The chain assembles once, when the panel is first opened, and never again on that card.

1. Links fade and rise 6px into place, top to bottom, 60ms apart, 260ms each.
2. Each connector draws downward from the link above it (`stroke-dashoffset`, or a scaleY
   on a 2px rule) as the link beneath lands.
3. When the last link has landed, a single soft gold glow travels the whole chain once,
   top to bottom, over about 900ms — the one flourish, and it does not repeat.
4. Hovering a link raises it 1px and brightens its connectors; nothing else moves.

Total under 1.6s for a chain of eight. Under `@media (prefers-reduced-motion: reduce)`
everything above is skipped: the chain is simply there, with no transition and no glow.
The animation must not delay the text: the links are in the DOM and readable from the first
frame, with the motion applied over them.

## The summary line

Above the chain, one sentence that is a count, not a verdict:

> **8 links** · 5 identified · 1 not found · 1 unnamed group · ends at al-Ṣādiq

It never says «ṣaḥīḥ», «weak», or any grade of the chain as a whole. This site reports what
the books say of men; grading a chain is the reader's work, and the counts are what he needs
to start it. (`#{rijal.summary.*}` keys, Arabic digits through the same `t()`/`localeDigits`
path the rest of the client uses.)

## Both languages

The Arabic site shows the isnād words, the display name, the grade word and the books in
Arabic only — no Latin anywhere, including no transliterated name under the Arabic one. The
English site shows the isnād words in Arabic (they are the text) with the Latin reading of
the man's name beneath, the grade as «ثقة — trustworthy», and English book titles.

Every string goes in `messages.properties` and `messages_ar.properties`. Nothing is written
inline in a template or a script.

## Files

| file | change |
|---|---|
| `fragments/hadith-card.html` | the rail button and the `is-rijal` panel, server-rendered chain |
| `HadithDisplaySegmenter` or the card factory | expose `chain_people` on the card view model, in order, with slug and kind |
| `NarratorController` | `GET /v1/narrators/briefs`, sharing the preview projection |
| `static/js/rijal-chain.js` | fetch on first open, fill the links, run the assembly once |
| `static/js/narrator-preview.js` | export its brief cache so the two share it |
| `static/css/manuscript.css` | a new block at the end; **do not touch or consolidate existing blocks** |
| `messages*.properties` | `sidecar.rijal`, `rijal.*` in both files |

## Acceptance

1. `/hadith/Al-Kafi-Volume-4-Kulayni:308` shows RIJĀL second in the rail, badge 5.
2. Its panel lists seven links in isnād order: five named men, one unidentified
   («مُحَمَّدِ بْنِ عُثْمَانَ ا»), one collective («بَعْضِ مَشَايِخِهِ»), ending at al-Ṣādiq.
3. With JavaScript disabled the same seven links and their page links are in the HTML.
4. One `briefs` request on first open; none on the second.
5. The Arabic page has no Latin character anywhere in the panel.
6. `prefers-reduced-motion: reduce` renders the chain with no animation.
7. A click on a link in the panel opens the preview card already built, without a refetch.
8. A chapter page with twenty cards issues no narrator request until a panel is opened.
