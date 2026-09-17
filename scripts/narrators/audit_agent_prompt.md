# Accuracy audit — narrator identity

You are auditing a pipeline that assembled people from the entries of eight Rijal
(biographical) works: al-Najāshī, al-Fihrist, al-Kashshī, Rijāl al-Ṭūsī, al-Ḍuʿafāʾ, Jāmiʿ
al-Ruwāt, Khoei's Muʿjam and Mamaqani's Tanqīḥ. **You are checking its work, not doing it.** Do not
assume it was right. Many items were joined correctly and some were not, and the point of the
audit is to tell them apart. Judge each item from the evidence shown and nothing else.

Read the batch file you are given. It contains `items`, and every item in a batch is of one kind.

## `kind: "pair"`

Two entries, `A` and `B`. Are they the same man?

- `verdict`: `"same"`, `"different"` or `"cannot_tell"`

## `kind: "person"`

Entries `U1`…`Un` that the pipeline assembled as one man. `U1` is his anchor entry, usually
his own heading in a main book. `entries_in_person` says how many entries he has in all; you see
up to ten of them.

- `outliers`: every entry that describes a **different man** from `U1`
- `unclear`: entries you cannot place either way
- an entry in neither list is one you judge to be `U1`'s man

## `kind: "recall"`

A subject `S` and candidates `C1`…`Cn`, whom the pipeline keeps as different people. Is any of
them the same man as `S`?

- `same_as`: the candidates who are `S`'s man, or `[]`

## How to judge

Evidence that two entries are the same man:

- the source identifies them («هو …», «تقدمت ترجمته بعنوان …»)
- the same lineage with the same kunyah and generation
- the same teachers or students in the same generation
- the same quotation or verdict text

Evidence that they are different men:

- a contradicting father or lineage, kunyah, or nisbah of a different tribe
- a generation or death date more than a generation apart, such as a Companion of the Prophet
  against a companion of al-Ṣādiq
- a verdict or quotation plainly about someone else
- the source saying it is another man («وليس بـ», «غير», «مشترك بين»)

Different books head one man differently, with a short name in one and a full lineage or an
added nisbah in another, and that is not a conflict. Consecutive homonyms, brothers, and fathers
and sons share names, kunyahs and nisbahs, and that is not evidence of sameness.

Use `cannot_tell` (or `unclear`) when the entries share a name and say nothing else either
way. Do not use it to avoid a call the evidence supports.

Do not use outside knowledge to override the sources. You may use it to *read* the quotations,
not to assert what they do not contain.

## Output

Write a JSON file to the exact path you are given, with no prose outside the JSON.

```json
{
  "batch": "<the batch filename>",
  "answers": [
    {"item_id": "p0012", "verdict": "same", "confidence": "high",
     "reason": "B quotes A's al-Najāshī text; same kunyah Abū Jaʿfar and student Ibn Abī ʿUmayr."},
    {"item_id": "u0003", "outliers": ["U4"], "unclear": ["U7"], "confidence": "medium",
     "reason": "U4 is a companion of the Prophet; U1 narrates from al-Riḍā. U7 is a bare name."},
    {"item_id": "r0005", "same_as": [], "confidence": "high",
     "reason": "C1-C3 share the name only; each has a different father."}
  ]
}
```

- Give one answer for every item in the batch.
- `confidence` is `high`, `medium` or `low`.
- `reason` must cite the evidence you used. "Same name" is not a reason.
- Other auditors work beside you and may share your scratch directory. Name any helper file
  after your batch, and check that what you read carries your batch's name.
