# Does the passage say it? — auditing extracted facts against the source's own words

A pipeline read eight Rijāl works — al-Najāshī, al-Fihrist, al-Kashshī, Rijāl al-Ṭūsī,
al-Ḍuʿafāʾ, Jāmiʿ al-Ruwāt, Khoei's *Muʿjam*, Māmaqānī's *Tanqīḥ* — and extracted facts about
each narrator: his reliability grade, his teachers, his students, his city, the Imams he is
filed under, the year he died. Those facts are printed on a public page as what the book says.

**You are checking each fact against the Arabic passage it was read from.** Nothing else.

Your batch holds `tasks`. Each task is one record:

- `book`, `pages`, `heading_ar` — where it came from and the name it was filed under
- `text_ar` — **the passage. This is the whole evidence.**
- `asserts` — the facts extracted from it, one entry per field

## The question, per field

For every field in `asserts`, say whether `text_ar` supports it:

| verdict | when |
|---|---|
| `supported` | the passage states it, in words or unmistakably by implication |
| `partly` | some of the values are in the passage and some are not — **list which are not** |
| `absent` | the passage simply does not address this. It says nothing either way |
| `contradicted` | the passage says something incompatible with it |
| `cannot_tell` | the passage is too damaged, truncated or garbled to read |

`absent` and `cannot_tell` are different and the difference matters. A clear passage that
happens not to mention his city is `absent` — you *can* tell, and the answer is that the fact
is not there. Reserve `cannot_tell` for text you genuinely cannot read: machine-mangled
Arabic, a sentence cut mid-word, encoding damage. If you can read the passage, you can answer.

Do not hedge. A wrong `cannot_tell` is as damaging to this measurement as a wrong
`supported` — it hides a defect instead of reporting one. Equally, do not flag a fact merely
because the passage is brief: brevity is not error.

## What each field means

**`grade`** — `value` is our vocabulary, `said` is how it is phrased. Judge the *substance*:
does the passage grade this man that way? «ثقة» supports `thiqa`; «ضعيف» supports `daif`;
«كان ثقة في الحديث إلا أن أصحابنا قالوا كان يروي عن الضعفاء» supports a mixed verdict.
A passage that merely narrates, or says «روى عنه فلان», or says «أقول: هو متحد مع ما تقدم» —
"I say: he is the same as the one before" — grades nobody: that is `absent`.

Then, and only when the grade is `supported` or `partly`, answer **`grade_whose`**:

- `this_book` — the author is giving his own verdict
- `quoted` — he is reporting someone else's, and **name whom** in `grade_from`:
  «قال النجاشي: ثقة», «ذكر ذلك أبو العباس», «وثّقه في الوجيزة». This is not a defect in the
  grade; it is a fact about it that the page currently does not distinguish, and we need it.
- `unclear` — the passage states the grade with no sign of whose it is

**`teachers`** / **`students`** — the names under `narrated_from` / `narrated_to`. Supported
when the passage places that man on that side of the relation: «روى عن X» makes X a teacher,
«روى عنه Y» makes Y a student. Watch the direction — it is the commonest way to be wrong —
and watch for names that are a chain convention rather than a person («أحدهما عليهما السلام»,
«بعض أصحابنا», «عدة من أصحابنا»): those are `contradicted`, because they are not people.
`held` says how many names the record holds; you are shown at most eight. Judge the ones shown.

Two in five of these names are stored as Latin transliteration rather than Arabic — "Safwan
ibn Yahya" for «صفوان بن يحيى», "Abu 'Abdullah" for «أبي عبد الله». Match them to the Arabic
of the passage on the sense of the name, not its spelling; transliteration is inconsistent and
a plausible rendering counts as the same name. Judge a transliterated name exactly as strictly
as an Arabic one — if the man it names is not in the passage, say so.

**`city`** — a city, a tribe, or a clientage, from one field that mixes all three. Supported
when the passage names it in any of those senses: «كوفي» supports Kufa, «الشيباني» supports
Shayban, «مولى» supports a client status. Say `absent` if the passage names none of them.

**`generation`** — `said` is the book's own words, `imams` is our reading of which Imams they
name. Judge whether the passage files him under those Imams. «من أصحاب الصادق عليه السلام»
supports al-Sadiq. A passage naming an Imam he narrated *from* also files him there.

**`death_year`** — a hijrī year. Supported only if the passage gives that year of death.
A year the passage gives for something else — a year he was heard from, a birth — is
`contradicted`, and say so in the note.

## Rules

- **Only `text_ar` counts.** You may know from elsewhere that al-Najāshī called this man
  thiqa; if *this* passage does not, the answer is `absent`. The page cites this passage, so
  this passage is what must carry the claim. Use your knowledge to *read* the Arabic, never
  to supply what it omits.
- The passage may be truncated at 3,000 characters. If a fact would plainly have stood in the
  part you cannot see, that is `cannot_tell` for that field — but do not assume it.
- `note` is where you say what the passage actually says. One short clause. It is the only
  thing a reviewer can check your verdict against, so make it specific: quote the words.
- Answer every task and every field in it. Do not skip and do not invent fields.

## Output

Write a JSON file to the exact path you are given. No prose outside the JSON.

```json
{
  "batch": "<the batch filename>",
  "judgements": [
    {"task_id": "attr:0137",
     "fields": {
       "grade": {"verdict": "supported", "grade_whose": "quoted",
                 "grade_from": "al-Najashi",
                 "note": "«و قال النجاشي: حكم بن حكيم أبو خلاد الصيرفي كوفي مولى، ثقة»"},
       "city": {"verdict": "supported", "note": "«كوفي» and «مولى» both stand in the passage"},
       "teachers": {"verdict": "partly", "unsupported": ["سليمان بن داود المنقري"],
                    "note": "the passage names only «أبي عبد الله عليه السلام»"},
       "death_year": {"verdict": "absent", "note": "no year of any kind in the passage"}
     }}
  ]
}
```

- One object per `task_id`, with one verdict per field that task asserts.
- `unsupported` is required when the verdict is `partly`, and lists the values that fail.
- `grade_whose` only on a `grade` field judged `supported` or `partly`.
- Judge each task by reading it. Do not write a script that decides by string matching; you
  may use a script to print the tasks and to assemble your answers once you have made them.
- Other agents work beside you. Name any helper file after your batch.
