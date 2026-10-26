# Does the passage say this? — auditing the facts printed on a narrator's page

Every narrator page states facts beside the man's name: where he lived, when he died, which
Imam's companions he is counted among. Each was read out of one entry in one rijāl book. Seven
audits have measured whether we have the right man; **not one has asked whether these facts
are in the books at all.** That is your question, and it is the only one.

Your batch holds `tasks`. Each is one statement:

- `field` — what kind of claim it is: `city_or_tribe`, `death_years`, `generations`,
  `reliability_grades`, `narrated_from`, `narrated_to`, `titles` or `kunyahs_arabic`
- `value` — what the page prints
- `narrator_ar` / `narrator_en` — the man it is printed about
- `book`, `pages` — where it was read from
- `heading_ar` — the entry's own heading
- `passage_ar` — the entry's own words
- `filed_under`, `entry_generation` — **the chapter the entry stands in, which for a
  generation is the evidence.** Rijāl al-Ṭūsī files a man by whose companion he was and then
  often writes nothing but his name: «سليم بن قيس الهلالي.» is a complete entry. The chapter
  is the book's statement about him, so a generation that agrees with it is `supported` even
  though the words are not repeated in the entry.

## The answers

| verdict | when |
|---|---|
| `supported` | the passage says this, or says something that plainly means it |
| `contradicted` | the passage says something else — a different city, a different year, a different Imam |
| `not_in_passage` | the passage simply does not say; it is silent on this |

`not_in_passage` is not a failure to decide. It is the most important verdict here: a fact we
print that its own passage does not contain is the defect this audit exists to find.

## How to judge

- **Judge the passage, not the man.** You may know that Zurāra was a Kufan. The question is
  whether *this* entry says so. If it does not, the answer is `not_in_passage` however true
  the claim is.
- **A city may be stated as a nisbah.** «الكوفي» in the heading supports Kufa; «البجلي» is a
  tribe and supports Bajila, not a city. Both count as `supported` for `city_or_tribe`, which
  is one field holding two kinds of thing — say in your note which kind you saw.
- **A generation is usually the chapter a man is filed under**, «أصحاب أبي عبد الله» and the
  like, and the passage may carry it as «روى عن أبي عبد الله» or «عده الشيخ في أصحاب…». Any
  of those support it. A man merely *mentioned* with an Imam is not filed under him.
- **A grade must be said of this man, in these words or plainly meant.** `thiqa` needs «ثقة»
  or an equal — «لا بأس به» is not it, and «ثقة» said of his father or his teacher is not his.
  A grade the passage gives on somebody else's authority («قال النجاشي: ثقة») still counts:
  the entry is reporting it. `daif` needs «ضعيف» or «كذاب», «غال», «مخلط» and the like;
  `majhul` needs the passage to say he is unknown, not merely to say little about him. **An
  entry that says nothing about his reliability is `not_in_passage`, however long it is.**
- **When a relation is wrong, begin your note with one of these words**, because the remedy
  differs and we cannot tell these apart from here:
  - `tariq` — the name belongs to the author's own chain of transmission *to the man's book*,
    not to the man's teachers. Al-Najāshī writes «أخبرنا عدة من أصحابنا عن أحمد بن محمد بن
    يحيى قال حدثنا عبد الله بن جعفر», and that is his route to the book, not a pupil of the
    subject. This is the shape that matters most.
  - `chain` — the name stands in a chain quoted inside the entry, further up or down than the
    man himself, so no transmission between the two is stated.
  - `other-relation` — the entry names him as a brother, a son, a rival, the subject of an
    anecdote: a person, but not a teacher or a pupil.
  - `reversed` — the passage has the direction the other way round.
  - `absent` — the name is not in the passage at all.
- **A teacher or a student must be named as one.** `narrated_from` says this man learned from
  that one: «روى عن», «أخبرنا», or a chain in the entry where he stands directly after him.
  `narrated_to` is the reverse. A man merely named in the same entry — a brother, a rival, the
  subject of a story — is `not_in_passage`. If the passage has them the other way round, that
  is `contradicted`, and it is the error worth catching.
- **A title or a kunya must be his.** «أبو عبد الله» in the heading is his kunya; the same
  words later in the entry may be the Imam he narrates from, which is not the same claim.
- **A death year must be the man's own.** A passage that gives the year his teacher died, or
  the year a book was written, does not support his death year: that is `contradicted` if it
  is being read as his, and `not_in_passage` if the passage gives no year for him at all.
- **The passage is cut at 1,400 characters.** If the entry plainly continues past the cut and
  the answer might be in what is missing, say `not_in_passage` and note that it was cut.

## Rules

- Answer every task. Do not skip and do not invent task ids.
- Do not hedge. `not_in_passage` where the passage *is* silent, not where you are unsure.
- `note` is one short clause and the only thing a reviewer can check you against: quote the
  words in the passage that decide it, or say that none do.
- Some values in your batch have been replaced on purpose, and some tasks appear twice.
  Nothing marks them. Judge each one as it comes.
- Judge each one by reading it. Do not write a script that decides by string matching; you may
  use a script to print the tasks and to assemble your answers once you have made them.

## Output

Write a JSON file to the exact path you are given. No prose outside the JSON.

```json
{
  "batch": "<the batch filename>",
  "judgements": [
    {"task_id": "field:0007", "verdict": "supported",
     "note": "heading reads «زرارة بن أعين ... الكوفي», which is the city printed"},
    {"task_id": "field:0019", "verdict": "not_in_passage",
     "note": "the entry grades him and names two teachers; it gives no year and no place"},
    {"task_id": "field:0024", "verdict": "contradicted",
     "note": "passage says «مات سنة ثمان وأربعين ومائة», 148, where the page prints 150"}
  ]
}
```
