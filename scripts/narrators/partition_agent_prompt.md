# Partition a large narrator — every entry, one reading

A pipeline assembled entries from eight Rijāl works — al-Najāshī, al-Fihrist, al-Kashshī,
Rijāl al-Ṭūsī, al-Ḍuʿafāʾ, Jāmiʿ al-Ruwāt, Khoei's *Muʿjam*, Māmaqānī's *Tanqīḥ* — into
people. **You are checking its work on one man at a time, and you are shown all of his
entries, not a sample.** That is the point of this pass: earlier audits saw ten entries of a
man who held ninety and could not place what they saw.

Your batch holds `tasks`. Each task is one assembled person:

- `person_id`, `entries_in_person`, and `entries` — every entry, labelled `U1`, `U2`, …
- `U1` is the anchor, usually his own heading in a main book.

## The question

**How many men are these?** Put every entry into exactly one cluster. One cluster means the
assembly was right and this is one man.

## How to judge

Men sharing a name are different people unless the text gives a positive reason to join
them. **Absence of conflict is not evidence of sameness** — two bare entries reading only
«محمد بن علي» tell you nothing, and belong apart.

Evidence they are the same man:

- one entry quoting or citing the other's book and page
- a shared full lineage, kunyah with nisbah, teacher *and* student, city, or death year
- a source saying so: «هو», «وهو الذي», «تقدم», «مضى بعنوان»

Evidence they are different men:

- a contradicting father or lineage, a kunyah that clashes, a nisbah of another tribe
- generations apart — a Companion of the Prophet against a companion of al-Ṣādiq. Each
  entry's `generation` names the Imams it files him under; several Imams apart with nothing
  else shared is two men.
- a verdict or quotation plainly about somebody else
- the source saying it is another man: «وليس بـ», «غير», «مشترك بين»

Different books head one man differently — a short name in one, a full lineage or an added
nisbah in another — and that is **not** a conflict. Brothers, fathers and sons, and
consecutive homonyms share names, kunyahs and nisbahs, and that is **not** evidence of
sameness.

Do not use outside knowledge to overrule the sources. You may use it to *read* a quotation,
never to assert what it does not contain.

## Output

Write a JSON file to the exact path you are given, no prose outside the JSON.

```json
{
  "batch": "<the batch filename>",
  "partitions": [
    {"task_id": "partition:n000220",
     "clusters": [["U1","U2","U4"], ["U3"], ["U5","U6"]],
     "confidence": "high",
     "reason": "U3 is al-Bazanṭī, d. 221, whom Māmaqānī heads separately; U5/U6 share the
                kunyah Abū Jaʿfar and the student Ibn Abī ʿUmayr with each other but not
                with U1."}
  ]
}
```

- One answer for every `task_id` in the batch.
- Every entry label appears in exactly one cluster, and no label is invented.
- `confidence` is `high`, `medium` or `low`.
- `reason` must cite the evidence you actually used, naming the labels. "Same name" is not a
  reason, and a reason reused word for word across tasks is not a reason either.
- Judge each task by reading it. Do not write a script that clusters by a rule; you may use
  a script to print the entries and to assemble your answers once you have made them.
- Other agents work beside you. Name any helper file after your batch.
