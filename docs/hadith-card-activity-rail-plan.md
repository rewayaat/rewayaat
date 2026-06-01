# Hadith Card Activity Rail Implementation Plan

## Goal

Replace the current hadith metadata column with a VSCode-style activity rail plus side panel on each hadith card.

Behavior requirements:

- Each card opens with `metadata` selected by default.
- The rail always shows 3 options:
  - `metadata`
  - `similar hadith`
  - `Quranic insights`
- `similar hadith` and `Quranic insights` always show a numeric badge.
- If a count is `0`, the icon remains visible, the badge shows `0`, and the rail item is disabled.
- Similar hadith and Quranic insights panels use:
  - a scrollable list of all matched items
  - a selected-item detail view

## Files To Change

### Frontend

- `src/main/resources/templates/index.html`
- `src/main/resources/static/js/rewayaat.js`
- `src/main/resources/static/js/vue-components.js`
- `src/main/resources/static/css/manuscript.css`

### Backend

- `src/main/java/com/rewayaat/controllers/rest/HadithController.java`
- `src/main/java/com/rewayaat/service/SimilarHadithService.java`
- `src/main/java/com/rewayaat/service/QuranicInsightsService.java`

### Tests

- `src/test/java/com/rewayaat/controllers/rest/HadithControllerTest.java`
- `src/test/java/com/rewayaat/integration/HadithApiIntegrationTest.java`

## Frontend Implementation

### 1. Replace metadata aside with a sidecar shell

In `index.html`, replace the current metadata-only aside with:

- a narrow left rail
- a panel immediately to the right of the rail
- the main hadith content area unchanged on the right

Desktop structure:

- `aside.hadith-sidecar`
  - `div.hadith-sidecar__rail`
  - `div.hadith-sidecar__panel`
- `section.hadith-card__main`

### 2. Default-open metadata panel

Add a transient UI field per narration:

- `sidecarActiveTab: "metadata"`

This should be initialized in narration decoration code and never persisted.

### 3. Keep metadata renderer, but move it into the panel body

Keep using the existing `hadith-details` component for metadata content.

Adjust the component root so it has a stable class:

- `class="hadith-details"`

### 4. Remove inline similar/Quran panels from the main column

Delete the current inline expanded sections below the hadith content and re-render their content inside the side panel.

This removes the split interaction model and ensures one consistent panel area controlled by the rail.

### 5. Unify rail state handling

Add root methods in `rewayaat.js`:

- `setNarrationSidecarTab(narration, tab)`
- `isNarrationSidecarTabActive(narration, tab)`
- `isNarrationSidecarTabDisabled(narration, tab)`
- `narrationSidecarTitle(narration)`

Use these methods instead of the current open/close behavior for similar and Quran views.

### 6. Similar hadith panel layout

Render a 2-part panel:

- left/top: scrollable list of all similar hadith
- right/bottom: selected hadith detail

Keep using existing selection state:

- `similarActiveIndex`

Keep existing detail affordances where useful:

- jump-to-source links
- why-matched chips
- Arabic term highlighting

### 7. Quranic insights panel layout

Render a matching 2-part panel:

- left/top: scrollable list of all Quranic matches
- right/bottom: selected verse + tafsir snippets

Keep using existing selection state:

- `quranicInsightsActiveIndex`

### 8. Badge behavior

For `similar` and `quran` rail items:

- while count is loading: show a muted loading badge
- when count resolves:
  - show numeric badge
  - disable the button if count is `0`

Metadata rail item has no numeric badge.

### 9. Mobile behavior

Do not keep the desktop vertical rail on narrow screens.

At mobile breakpoint:

- sidecar moves above hadith text
- rail becomes a horizontal icon strip
- panel sits directly below the strip
- hadith content stays below the panel

Mobile order:

1. Card header and actions
2. Horizontal rail
3. Active sidecar panel
4. Hadith English/Arabic content
5. Topic tags

Mobile panel layout:

- `metadata`: stacked rows
- `similar`: scrollable list first, selected detail second
- `quran`: scrollable list first, selected verse and tafsir second

## Backend Implementation

### 1. Similar hadith endpoint must support full panel lists

Current blockers:

- controller limits `per_page`
- service trims result set size

Implementation direction:

- add request param `all=true` to `/v1/narrations/similar`
- when `all=true`, return the full display list in one response
- keep normal paginated behavior for existing callers

### 2. Quranic insights endpoint must support full panel lists

Current blocker:

- service limits candidate payload size using `max-candidates`

Implementation direction:

- add request param `all=true` to `/v1/narrations/quranic_insights`
- when `all=true`, return all candidates
- preserve existing count-only behavior

### 3. Keep count prefetch behavior, but separate it from full panel loading

Counts remain lightweight:

- similar uses count-only request pattern
- Quran uses `count_only=true`

Panel opening should fetch the full payload only when needed.

### 4. Fix existing Quran panel bug

Remove or replace the dangling `ensureActiveQuranicInsightSummary(...)` call in `rewayaat.js`, because that method does not currently exist.

## Testing Checklist

### Backend

- verify similar endpoint still clamps legacy requests correctly
- verify similar endpoint returns full collection when `all=true`
- verify Quranic insights endpoint returns all candidates when `all=true`
- verify count-only behavior still works

### Frontend

- metadata is active by default on every loaded narration
- similar/Quran badges show `0` and disabled state correctly
- similar/Quran panel opens only when count is greater than zero
- list selection updates detail pane
- mobile layout stacks correctly without rail/content overlap

## Suggested Rollout Order

1. Add doc
2. Add new transient narration UI state
3. Replace aside markup with rail + panel
4. Move similar/Quran UI into side panel
5. Add mobile CSS
6. Extend backend endpoints for `all=true`
7. Update fetch logic to request full lists for active panels
8. Fix Quran bug
9. Run tests
