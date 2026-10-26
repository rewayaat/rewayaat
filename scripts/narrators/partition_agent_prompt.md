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

**Which of these entries are not this man?**

Start from the anchor, `U1`. The rest were assembled with him for reasons, and most of them
belong: a famous narrator carries dozens of entries, many of them bare sub-entries in Khoei
that say little more than his name. **An entry stays with the anchor unless the text gives
you a positive reason to take it away.**

This is the opposite of the instinct that serves when sorting a pile of same-named
strangers. Here the men are already assembled, and treating "this entry says nothing either
way" as grounds to separate shatters one man into forty. A bare name alongside the anchor's
name is weak evidence *for* him, not evidence against.

Three places to put an entry:

- the **anchor's cluster**, where most belong
- **another cluster**, when the text positively shows a different man — and say which
- **`unplaced`**, when an entry is too bare to judge and you are not willing to call it
  either way. Use this freely. It is the honest answer, and it is far more useful than a
  singleton cluster that asserts a man who may not exist.

## How to judge

Take an entry away from the anchor only on positive evidence:

- a contradicting father or lineage, a kunyah that clashes, a nisbah of another tribe
- generations apart — a Companion of the Prophet against a companion of al-Ṣādiq. Each
  entry's `generation` names the Imams it files him under; several Imams apart with nothing
  else shared is two men.
- a verdict or quotation plainly about somebody else
- the source saying it is another man: «وليس بـ», «غير», «مشترك بين»

Reasons an entry stays, even a thin one:

- a shared teacher or student, city, kunyah, nisbah or death year
- one entry quoting or citing the other's book and page
- a source saying so: «هو», «وهو الذي», «تقدم», «مضى بعنوان»
- nothing at all said against him, and the name fits

Different books head one man differently — a short name in one, a full lineage or an added
nisbah in another — and that is **not** a conflict. Brothers, fathers and sons, and
consecutive homonyms do share names and nisbahs, so where the text *does* distinguish them,
distinguish them.

Do not use outside knowledge to overrule the sources. You may use it to *read* a quotation,
never to assert what it does not contain.

## Output

Write a JSON file to the exact path you are given, no prose outside the JSON.

```json
{
  "batch": "<the batch filename>",
  "partitions": [
    {"task_id": "partition:n000220",
     "clusters": [["U1","U2","U4","U6"], ["U3","U5"]],
     "unplaced": ["U7"],
     "confidence": "high",
     "reason": "U3 is al-Bazanṭī, d. 221, whom Māmaqānī heads separately, and U5 shares his
                kunyah and his student; the rest carry the anchor's lineage or say nothing
                against it. U7 is a bare name with no teacher, student or generation."}
  ]
}
```

- One answer for every `task_id` in the batch.
- Every entry label appears exactly once, in a cluster or in `unplaced`. No invented labels.
- The anchor's cluster comes first. Expect it to hold most of the entries; if it does not,
  read again and ask whether you have separated men or merely separated silence.
- `confidence` is `high`, `medium` or `low`.
- `reason` must cite the evidence you actually used, naming the labels. "Same name" is not a
  reason, and a reason reused word for word across tasks is not a reason either.
- Judge each task by reading it. Do not write a script that clusters by a rule; you may use
  a script to print the entries and to assemble your answers once you have made them.
- Other agents work beside you. Name any helper file after your batch.
