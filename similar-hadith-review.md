# Similar Hadith System — Deep Review & Improvement Recommendations

## Executive Summary

Your similar hadith pipeline is genuinely well-engineered — it uses a hybrid retrieval strategy (semantic kNN + lexical BM25), fuses results with RRF, and applies multi-signal scoring (semantic, syntactic, content overlap, significant terms). That's a sophisticated architecture.

After a thorough code review, I've identified **6 high-impact improvements** that can take the quality from "good" to "excellent." They range from quick wins (tuning existing signals) to a structural change (embedding model upgrade). I've ordered them by expected impact.

---

## Current Architecture at a Glance

```
Source Hadith
    │
    ├─► Matn Extraction (HadithSemanticText / HadithDisplaySegmenter)
    │       Strips isnad, honorifics, HTML; extracts content-only matn
    │
    ├─► Semantic kNN (Elasticsearch dense_vector)
    │       Model: multilingual-e5-small (384 dims)
    │       Query: "query: {matn} || key_terms: {significant_terms}"
    │       Pool: 220 hits, min similarity 0.78
    │
    ├─► Lexical BM25 (multi_match on arabic + semantic_text)
    │       Boosted significant_terms_field match
    │       Pool: 220 hits, 55% min_should_match
    │
    ├─► RRF Fusion (0.85 semantic / 0.15 lexical, k=60)
    │
    ├─► Multi-Signal Scoring per candidate:
    │       • semanticPercent (calibrated cosine → 0-100)
    │       • syntacticPercent (token Jaccard 65% + char 3-gram Jaccard 35%)
    │       • contentOverlapPercent (distinctive token recall)
    │       • significantTermOverlapPercent
    │       → retrievalPercent = 0.64 semantic + 0.12 syntactic + 0.24 content
    │
    ├─► Eligibility Gate (requires semantic hit + distinctive token overlap)
    │
    ├─► Display Threshold (≥70% retrievalPercent)
    │
    └─► Deduplication (exact normalized matn match)
```

---

## Improvement #1: Upgrade the Embedding Model to `multilingual-e5-large`

**Impact: Very High — this is the single biggest lever**

You're currently using **`.multilingual-e5-small`** (384 dimensions). This is a lightweight general-purpose multilingual model. For Arabic hadith text — which is domain-specific, classical, and semantically dense — this model is a significant bottleneck. It was trained primarily on modern web text across 100+ languages, meaning Arabic classical text is underrepresented in its training distribution.

**The problem in practice:** e5-small will produce similar vectors for two hadiths that share vocabulary (surface similarity) but may miss deep semantic parallels between hadiths that express the same meaning with different words. Conversely, it may score two hadiths as similar because they share common Islamic vocabulary even when their actual meanings diverge.

**Upgrade to `intfloat/multilingual-e5-large`** (1024 dims). This is the same model family, so your existing `"query: ..."` / `"passage: ..."` prefix format works as-is. It's a drop-in upgrade — change the `MODEL_ID` env var in `setup_semantic_similarity.sh` and reindex. The larger model is dramatically better at capturing nuanced semantic distinctions in non-English text, with roughly 3x the representational capacity.

**Implementation considerations:**

- Your ES mapping has `"dims": 384` hardcoded — update to `"dims": 1024` and reindex
- The `SEMANTIC_MIN_SIMILARITY` threshold of 0.78 is calibrated for e5-small's score distribution; e5-large produces more spread-out similarity scores, so lower this to roughly **0.70-0.74**
- The `SEMANTIC_SCORE_EXPONENT` of 1.5 in `calibrateBoundedScore` over-compresses the middle range where most of the interesting ranking decisions happen with the larger model; reduce to roughly **1.15-1.25** to preserve granularity between "somewhat similar" and "very similar"
- You already have the `SemanticMatnSourceBackfillTool` and the Painless ingest pipeline, so re-embedding is a known workflow

---

## Improvement #2: Add English as a Lightweight Semantic Hint

**Impact: Medium-High (with appropriate caution)**

Your `HadithObject` has both `arabic` and `english` fields, but the entire similar hadith pipeline operates exclusively on Arabic text. Since `multilingual-e5-large` is trained to align Arabic and English in the same vector space, a small amount of English context can help disambiguate cases where two hadiths express the same meaning with very different Arabic phrasing.

**Important caveat:** The English translations may not always be high quality. This means English should be a **minor supplementary signal, not a co-equal one.** The Arabic matn must remain the dominant input, and the English should be short enough that even a mediocre translation helps more than it hurts.

**Concrete changes:**

In `SemanticMatnSourceBackfillTool` and the Painless pipeline, append a short English snippet after the Arabic matn but before the significant terms:

```
passage: {arabic_matn} || en_hint: {english_first_120_chars} || key_terms: {significant_terms}
```

Key constraints to keep English from dominating:

- **Cap at 100-120 characters** — just enough to anchor the topic, not enough to steer the embedding away from the Arabic meaning if the translation is loose
- **Arabic should consume ~75-80% of the token budget**, English ~10-12%, significant terms the rest
- **Apply the same isnad-stripping logic** to the English text before including it — your `SemanticTextNormalizer` already handles English narration chains
- **In `HadithSemanticText.toQueryText()`**, include the same short English hint so query and passage embeddings are constructed symmetrically

**Why this helps despite imperfect translations:** Even a rough translation like "the obligatory prayers are seventeen" provides enough topical signal for the embedding model to better align two hadiths about the same ruling. The Arabic content still dominates the vector, but the English nudges it in the right direction. If a particular translation is very poor, the 100-char cap limits how much damage it can do.

---

## Improvement #3: Topic Tags as a Retrieval and Filtering Signal

**Impact: High**

You've already built an entire `TopicTagsBackfillTool` with a frozen taxonomy and AI-assisted tagging. The `HadithObject` has a `topic_tags` field. But the similar hadith pipeline doesn't use topic tags at all.

**How to integrate topic tags:**

**A. As a pre-filter or boost in lexical retrieval:**
In `lexicalHits()`, add a `should` clause that boosts candidates sharing topic tags with the source hadith:
```java
.should(sh -> sh.terms(t -> t
    .field("topic_tags")
    .terms(sourceTopicTags.stream().map(FieldValue::of).toList())
    .boost(3.0f)))
```

**B. As a scoring signal in candidate evaluation:**
Add a `topicOverlapPercent` signal alongside your existing `syntacticPercent`, `contentOverlapPercent`, etc. Compute it as the Jaccard similarity of the source and candidate topic tag sets. Blend it into `retrievalPercent` with a modest weight (e.g., 0.08-0.12).

**C. As an eligibility gate tiebreaker:**
When a candidate is borderline on the existing distinctive-token eligibility gates, sharing 2+ topic tags could serve as a "rescue" signal that keeps it in the pool.

**Why this matters:** Topic tags capture high-level thematic similarity that neither token overlap nor embedding similarity reliably detect. A hadith about the obligation of prayer and a hadith about the reward of prayer might share few distinctive tokens and have moderate embedding similarity, but they share the topic tag `prayer/salah`. This is especially valuable for short hadiths where the statistical signals are weak.

---

## Improvement #4: Smarter Deduplication — Near-Duplicate vs. True-Duplicate

**Impact: Medium-High**

Your current deduplication in `prepareDisplayCandidates` uses exact normalized matn match:

```java
String dedupKey = candidate.normalizedMatn();
if (!dedupKey.isBlank() && !seenMatn.add(dedupKey)) {
    continue;  // skip exact duplicate
}
```

This catches only *byte-identical* normalized matns. It misses near-duplicates — hadiths that differ by one or two words but are essentially the same narration from different chains. These near-duplicates clutter results and push genuinely distinct similar hadiths below the display threshold or page fold.

**Recommended approach:** Use a syntactic similarity threshold instead of exact match.

You already compute syntactic similarity via `SimilarHadithRanking.syntacticSimilarityPercent`. Extend this into a dedup check:

```java
// Instead of exact match, use high-threshold syntactic similarity
boolean isNearDuplicate = false;
for (String existingMatn : seenMatns) {
    if (SimilarHadithRanking.syntacticSimilarityPercent(
            candidate.normalizedMatn(), existingMatn) > 92.0) {
        isNearDuplicate = true;
        break;
    }
}
if (isNearDuplicate) continue;
seenMatns.add(dedupKey);
```

The 92% threshold keeps hadiths that have meaningfully different content while collapsing hadiths that differ only in minor wording variants (e.g., "فقال له" vs "قال له", or an extra "و" conjunction).

**Performance note:** For your max 40 display candidates drawn from 280, the pairwise comparison count is negligible. But if you want to avoid the O(n²) cost, compute a MinHash signature (4-8 hash functions over 3-gram shingles) at scoring time and bucket by signature match.

---

## Improvement #5: Bidirectional Token Recall (F1 Score)

**Impact: Medium**

Your `distinctiveTokenRecallPercent` computes recall *from the source hadith's perspective* — what fraction of the source's distinctive tokens appear in the candidate. This is good. But you don't compute the reverse direction, and this creates a systematic bias.

**Why both directions matter:**

- **Source → Candidate recall (what you have):** "Does the candidate contain the key concepts from the source?" High recall means the candidate covers the source's topics.

- **Candidate → Source recall (what's missing):** "Does the source contain the key concepts from the candidate?" Low reverse recall means the candidate talks about a lot of things the source doesn't mention — it's tangentially related at best.

**The failure mode:** A short hadith with 3 distinctive tokens ("prayer", "seventeen", "obligation") will get 100% forward recall against a long hadith that mentions prayer, seventeen, and obligation among 20 other topics. But that long hadith is really about something else — the reverse recall would be only 15%. Currently this inflates `contentOverlapPercent` and can pass eligibility gates it shouldn't.

**The F1-style harmonic mean of both directions** is a much stronger similarity signal:

```java
public static double bidirectionalTokenOverlapPercent(String source, String target) {
    double forwardRecall = distinctiveTokenRecallPercent(source, target);
    double reverseRecall = distinctiveTokenRecallPercent(target, source);
    if (forwardRecall + reverseRecall <= 0) return 0d;
    return round(clampPercent(2.0 * forwardRecall * reverseRecall
            / (forwardRecall + reverseRecall)), 2);
}
```

Use this in place of the current `contentOverlapPercent` assignment (line 259 of `SimilarHadithService`). This single change will suppress a lot of false positives where a long general hadith about multiple topics matches a short specific one.

---

## Improvement #6: Stopword List Refinement for Classical Arabic

**Impact: Medium**

Your `DISTINCTIVE_TOKEN_STOPWORDS` set in `SimilarHadithRanking` has ~130 entries. This is a solid starting point, but I noticed several gaps for classical hadith Arabic:

**Missing common hadith stopwords that should be filtered:**
- Narration-chain terms that leak through matn extraction: `روي`, `روى`, `يروي`, `سند`, `اسناد`
- Common generic verbs: `اراد`, `اردت`, `يريد`, `كانوا`, `كنت`, `كنا`, `يكن`
- Common hadith temporal/connective words: `فلما`, `حتى`, `فاذا`, `ولكن`, `لكن`, `اذ`, `اما`
- Generic referential terms: `شيء`, `شيئا`, `احد`, `احدكم`, `بعض`, `بعضهم`, `كل`, `جميع`
- Common descriptor words too generic to be distinctive: `كبير`, `صغير`, `كثير`, `قليل`

**Words that probably shouldn't be stopwords:**
- `يوم` (day) is currently a stopword, but in hadith context it can be highly distinctive — "يوم القيامه" (Day of Judgment), "يوم عرفه" (Day of Arafah), "يوم الجمعه" (Friday) are key semantic markers. Consider removing `يوم` and instead ensuring that bigrams like these are captured by significant terms.

**Recommendation:** Run a document-frequency analysis across your corpus using `SemanticSignificantTermsBackfillTool`'s existing `documentFrequency` map. Any token appearing in >40% of documents is a candidate for the stopword list. Any token in your current stopword list appearing in <5% of documents might be a distinctive term incorrectly filtered.

---

## Additional Tuning for e5-large

Once you have `multilingual-e5-large` running, the retrieval scoring weights should shift to trust the semantic signal more. With e5-small, diluting the semantic score with syntactic and content overlap made sense because the embeddings weren't very reliable. With e5-large, the semantic score becomes your most accurate signal.

**Current weights:** `0.64 semantic + 0.12 syntactic + 0.24 support`
**Suggested for e5-large:** `0.76 semantic + 0.08 syntactic + 0.16 support`

The syntactic and support signals still serve as a sanity check, but they shouldn't pull the ranking away from what the much-improved embedding model says. Tune these by env var (`SIMILAR_RETRIEVAL_SYNTACTIC_WEIGHT` and `SIMILAR_RETRIEVAL_CONTENT_WEIGHT`) after observing the new score distribution.

---

## Summary — Prioritized Roadmap

| Priority | Improvement | Effort | Impact |
|----------|------------|--------|--------|
| 1 | Upgrade embedding model to `multilingual-e5-large` (1024 dims) | Medium (reindex) | Very High |
| 2 | Add English as a lightweight semantic hint (100-120 chars) | Low (modify backfill + pipeline) | Medium-High |
| 3 | Integrate topic tags into retrieval + scoring | Low-Medium | High |
| 4 | Near-duplicate deduplication (92% syntactic threshold) | Low | Medium-High |
| 5 | Bidirectional token recall (F1 score) | Low | Medium |
| 6 | Refine stopword list with corpus frequency data | Low | Medium |

Improvements #2, #4, #5, and #6 are all low-effort changes to existing code. Improvement #1 (model upgrade) is the highest-impact but requires reindexing — and since you're reindexing anyway, improvement #2 comes along for free. Improvement #3 is high-value because you've already done the hard work of building the topic taxonomy.
