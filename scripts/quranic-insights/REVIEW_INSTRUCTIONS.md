# Reviewing hadith → Qur'an links

You are reviewing which Qur'anic verses a reader should be shown beside a narration on a
hadith website, and writing the one line that tells them why. Readers are Muslims and
students of hadith, reading in English or Arabic. A link they cannot see the point of
costs them trust in every other link, so **a narration with no link is better than a
narration with a weak one.**

## Input

One JSON object per line:

```json
{"hadith_id": "...", "book": "...", "chapter": "...", "english": "...",
 "candidates": [
   {"verse_key": "2:228", "verse_en": "...",
    "current_link": true,                 // shown on the site today
    "quoted_ar": "...",                   // the narration's Arabic contains these words of the verse
    "same_words_also_in": ["..."],        // ...which also occur, word for word, in these verses
    "cited_as": "(2:228)"}                // the English cites the verse explicitly
 ]}
```

Any of `current_link`, `quoted_ar`, `cited_as` may be absent.

## What to decide, per narration

**1. Every candidate gets `keep: true` or `keep: false`.** Keep a verse only if a reader
would say "yes, that is the verse behind this narration". In order of strength:

- **quoted**: the narration quotes or recites the verse (`quoted_ar` / `cited_as`).
  Keep it, unless the "quote" is a stock phrase that only happens to be Qur'anic
  wording — a du'a formula, "inna lillah", a tahlil inside a supplication — and the
  narration is not drawing on the verse. If `same_words_also_in` lists other verses,
  keep the one the narration means (context, the verse it goes on to explain, the
  English citation); keep two only if it genuinely invokes both. If an English citation
  and the Arabic quote disagree by one verse, the Arabic quote is usually right.
- **explains**: the narration interprets, applies or comments on that verse, even
  without quoting it.
- **ruling**: a legal narration whose ruling rests on, details or applies this verse.
  Prefer the **precise** verse. A widow's waiting period is 2:234, not 2:228; li'an is
  24:6-9; a bequest is 2:180; the ihram hunting penalty is 5:95. A general verse (65:1
  for any divorce, 4:11 for any inheritance, 22:29 for anything in Hajj) is kept only
  when it actually states what the narration is about and no more precise verse does.
- **theme**: the narration and the verse make the same specific point (both say haste
  leads to regret; both say the eye is the gateway to desire). Not merely the same
  topic: "both are about prayer" or "both mention fasting" is a drop.

Drop when the only link is a shared word (testify / bear witness, night / night prayer,
groups / Paradise), when the verse is about something else that happens to touch the
subject, or when you have to argue for it.

**2. Add a verse the candidates miss — at most two — only when the narration quotes,
explains, or rules on a specific verse that is not listed.** Do not add theme links.
Before adding, confirm the verse number with the lookup tool:

```
python3 scripts/quranic-insights/verse.py 2:234
python3 scripts/quranic-insights/verse.py --find "four months and ten"
```

An added verse must carry `check_ar`: its opening three or four Arabic words, copied
from the tool's output. A script compares them with the Qur'an text and throws the
addition away if they do not match.

**3. Every kept link gets a reason**, in English and Arabic:

- `reason_en`: one sentence, at most 30 words, saying what connects **this** narration to
  **this** verse. Name the specific thing: "The Imam quotes this verse to prove the
  widow's waiting period is four months and ten days, even when the divorce was
  revocable." Not "This verse is about divorce", not "This verse relates to the
  hadith", not a summary of the verse alone.
- No honorifics beyond what the narration uses; refer to "the Imam", "the Prophet",
  "Imam Ali". Do not praise the narration or the verse. Do not state rulings that the
  narration does not state.
- Credit the words to whoever says them. Al-Ṣadūq and al-Kulaynī often add their own
  explanation after the narration ("This distribution aligns with the verse…"); that is
  "al-Saduq", not "the Imam". A narrator's question is the narrator's.
- Lead with the specific point. Do not open every reason with "Both…"; "The Imam counts
  haste as the cause of error, as the verse calls man ever hasty" reads better than
  "Both link haste to error".
- Where the narration explains many verses at once (a list of the opening letters, say),
  each reason still names what is said about *that* verse, not "the Imam gives a meaning
  for it".
- If you drop a cited verse because the citation is mis-numbered (the English says 6:1
  for «Yā Sīn»), add the verse it should have been.
- `reason_ar`: the same sentence in clear Modern Standard Arabic, faithful to
  `reason_en`, not a new sentence. Use الإمام، النبي (ص)، الإمام علي (ع) as appropriate.
- `kind`: one of `quoted`, `explains`, `ruling`, `theme`.

## Output

Write one JSON object per input line, same order, to the output file you are given:

```json
{"hadith_id": "...", "links": [
  {"verse_key": "2:228", "keep": false},
  {"verse_key": "2:234", "keep": true, "added": true, "check_ar": "وَٱلَّذِينَ يُتَوَفَّوْنَ مِنكُمْ",
   "kind": "ruling", "reason_en": "...", "reason_ar": "..."}
]}
```

- Every input candidate appears exactly once in `links`.
- `added`, `check_ar` only on verses you added.
- Dropped links carry only `verse_key` and `keep: false`.
- Valid JSONL: one object per line, no comments, no trailing text.

When done, run the validator and fix anything it reports:

```
python3 scripts/quranic-insights/validate_review.py <input file> <output file>
```
