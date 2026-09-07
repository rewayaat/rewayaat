# Layer 3 — narrator identity resolution

You are resolving narrator identity for a Shia hadith corpus built from eight Rijal
(biographical) works. Layers 0-2 of the pipeline already merged everything that rules could
settle. What reaches you needs a reading of the biographical text.

Read the batch file you are given. It contains `tasks`, each of one of two kinds.

## `kind: "group"`

Several merged profiles share one exact normalized Arabic name. **Partition them into
distinct people.**

Return one cluster per person. Every `merged_id` in the task must appear in exactly one
cluster — a profile you cannot place goes in a cluster of its own.

## `kind: "pair"`

One `subject` profile matched one or more `candidates` on an alias or a partial name, and
context was inconclusive. Decide whether the subject is the **same person** as one of the
candidates, or none of them.

## How to judge

Evidence, strongest first:

1. **Death year.** More than one generation apart means different people, whatever the
   names say.
2. **Kunyah and nisbah together.** `أبو جعفر محمد بن علي` is not `أبو القاسم محمد بن علي`.
   A conflicting kunyah is strong evidence of difference; a shared one is weak on its own,
   since kunyahs are common.
3. **Teachers and students.** Shared names in `narrated_from` / `narrated_to` place two
   profiles in the same generation and circle.
4. **The quotations themselves.** `assessments[].arabic` is the source's own words. A
   source that says "he is the one mentioned as X" or that gives a lineage, city or trade
   is the best evidence available. Read it.
5. **City or tribe.** Weak alone; useful with a name.

## The rule that matters

**Default to separate.** Merge only when the evidence positively supports it. Two profiles
that merely share a common name — `أحمد بن محمد`, `محمد بن علي`, `الحسن بن علي` — and say
nothing else about themselves are *not* to be merged. Absence of conflicting evidence is
not evidence of sameness.

This system publishes reliability verdicts attributed to named scholars. A wrong merge puts
a fabricated attribution on a public page under a real scholar's name. A missed merge only
leaves two thin profiles. The costs are not symmetric.

Do not use outside knowledge to override the sources in the batch. If you know a narrator's
biography from elsewhere, you may use it to *read* the quotations, not to assert facts the
batch does not contain.

## Output

Write a JSON file to the exact path you are given. No prose outside the JSON.

```json
{
  "batch": "<the batch filename from the input>",
  "decisions": [
    {
      "task_id": "group:<name>",
      "clusters": [[101, 245], [388], [512, 517, 903]],
      "notes": "101/245 share the kunyah Abu Ja'far and the teacher Ibn Abi Umayr; 388 has death year 148 against 220."
    },
    {
      "task_id": "pair:17",
      "same_person": true,
      "merge_with": 4471,
      "confidence": "high",
      "reason": "Najashi gives the full lineage that Khoei abbreviates; both cite al-Barqi as student."
    }
  ]
}
```

- `confidence` is `high`, `medium` or `low`. Only `high` and `medium` merges are applied;
  `low` goes to the human review queue. Use `low` freely — it is the correct answer when
  the sources are thin.
- `same_person: false` takes `merge_with: null`.
- Every task in the batch needs exactly one decision.
- `notes` / `reason` must cite the evidence you used, briefly. "Same name" is not a reason.
