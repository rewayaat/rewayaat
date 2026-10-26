# Which of these men does the book mean? — settling a relation row on a narrator's page

A narrator page lists the men the rijāl books name as his teachers and his pupils, and the
men the chain indexes stand him beside in an isnād. Every one of those rows is **a name as
some book printed it**, and the row becomes a link to a man's page only when that name
reaches exactly one man in this index.

Often it does not. «أحمد بن محمد بن يحيى» is four different men; «علي بن الحسن بن فضال»
is two. The matcher refuses to choose and the row stays bare. **You are being asked to
choose, or to say that it cannot be chosen.**

A bare row is a small loss. A row linked to the wrong man is a false claim about a
narrator, printed on his page, and it is the worse outcome by a long way. When you cannot
tell, say so — `unclear` is a correct answer and we would rather have it than a guess.

## What a task holds

Your batch is `{"people": {...}, "tasks": [...]}`. **`people` is a dictionary of profiles
keyed by person id** — every man any task in the batch mentions, written once. A task names
men by id and you look them up there.

| field | what it is |
|---|---|
| `row` | the row's id — copy it back exactly |
| `the_page` | the id of **the man whose page this row sits on.** Everything is relative to him |
| `name_as_printed` | the name the book printed, which is the thing to be identified |
| `named_by` | the book or books whose entry prints this row |
| `what_the_book_says` | the claim the row makes — teacher, pupil, or standing beside him in a chain |
| `candidates` | the men who answer to that name: each is a `person_id` into `people`, with the evidence for him |
| `candidates_left_out` | present only when the name is held by more men than were worth sending |

A profile in `people` carries: `name_ar`, `name_en`, `lineage_ar`
(his full nasab), `also_filed_as` (headings the books file him under), `died` (a hijri
year, or null when no book gives one), `kept_company_with_imams` (which Imams a book counts
him among the companions of — this is the best dating most men have), `city`, `standing`,
`entries_in` (which rijāl works hold an entry for him), `named_in_narrations`, and a few of
`his_teachers` and `his_pupils`. A field that is empty for a man is left out of his
profile rather than sent as null — `died` missing means no book we hold gives him a year.

Each entry in a task's `candidates` carries that man's id and two pieces of evidence the
name matching cannot see:

- **`narrations_standing_them_together`** — how many narrations in the corpus this database
  serves put this candidate and the page's man side by side in one isnād. This is a fact
  about the chains, not a claim any book made. A candidate with 51 and a rival with 0 is a
  strong signal, though not a proof: the corpus holds a fraction of what was transmitted,
  and 0 often means only that neither man's chains were resolved.
- **`his_own_entry_names_the_pages_man`** — whether that candidate's own entry, in some
  book, names the page's man back. The books attest these pairings from both ends, so this
  is the same claim arriving from the other side.

Where both point at the same candidate and the rivals have neither, the answer is usually
that candidate. Where they point at different men, **say so and weigh them** — that is
exactly the case nothing automatic can handle.

## How to decide

**Dating outranks everything.** A book cannot name a man who outlived its compiler, and a
companion of al-Ṣādiq (d. 148) is not standing in a chain with a companion of al-ʿAskarī
(d. 260) as his immediate teacher. `kept_company_with_imams` and `died` are the two handles
on this; where they are missing, the generation of a man's own teachers and pupils says
nearly as much. A candidate two layers out of place is wrong however exactly the name
matches, and however strong the other evidence looks.

**Then the shape of the claim.** «X narrated from Y» puts Y a layer above X; «stands before
him in an isnād» is the same relative order with a weaker warrant. Read which direction
`what_the_book_says` is claiming before you weigh anything else, because a candidate who
fits the opposite direction is a different man.

**Then the books.** A row printed by al-Najāshī is about a man al-Najāshī wrote up; a
candidate no book in `named_by` holds an entry for is the less likely reading. A candidate
whose own `his_teachers` or `his_pupils` already names the page's man is nearly settled.

**Then the name itself.** The fuller the agreement — the nasab, the nisba, the kunya — the
better, but a fuller name is not a different man: «سليم بن قيس» and «سليم بن قيس الهلالي»
are one. Watch for the reverse: a candidate whose `lineage_ar` *contradicts* the printed
name (a different father, a different grandfather) is out, whatever else fits.

**What you may not do.** Do not pick a man because he is famous, because his
`named_in_narrations` is the largest, or because he is the only one with a full profile.
Prominence is not evidence about which man a particular book meant, and it is the way this
goes wrong at scale: the well-known man collects every ambiguous row in the corpus.

## The answer

Return **one line of JSON per task**, nothing else — no prose around it, no markdown fence:

```
{"row": "<the row id, copied exactly>", "verdict": "settled", "person_id": "n000702", "why": "<one sentence>", "confidence": "high"}
{"row": "<the row id>", "verdict": "unclear", "person_id": null, "why": "<one sentence saying what would settle it>", "confidence": "low"}
```

- `verdict` is `settled` or `unclear`.
- `person_id` must be one of the candidate ids shown for that row, or null.
- `why` is one sentence naming **the evidence that decided it** — the dating, the direction,
  the book, the chains — not a restatement of the name.
- `confidence` is `high`, `medium` or `low`. Use `high` only where two independent things
  agree and nothing contradicts; a lone signal is `medium`.

Answer every task in your batch, in order, once each.
