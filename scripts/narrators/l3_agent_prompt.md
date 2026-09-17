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

A group with `"method": "crossform_group"` is a cross-form reconciliation task. Its members
are **people**, each already assembled from one or more sources, and each holding its own
entry in one of the main Rijal works (al-Najashi, al-Fihrist, al-Kashshi, Rijal al-Tusi,
al-Du'afa', Jami' al-Ruwat). They meet here because they carry the same name form
(`shared_forms`) — sometimes whole, sometimes as the opening of a longer lineage or a name with
a nisbah added. Different books head the same man differently: al-Najashi's `الحسين بن سعيد`
and al-Fihrist's `الحسين بن سعيد بن حماد بن سعيد بن مهران الاهوازي` are one man. So are two men
who happen to share a grandfather's name, until the evidence says otherwise. The rules are the
ones below, unchanged: a longer lineage, a nisbah or a kunyah that the other entry lacks is not
a conflict, but one that contradicts it is. Two entries agreeing on the teachers, students,
books, generation or the verdict the source quotes are the evidence to merge on.

## `kind: "pair"`

One `subject` profile matched one or more `candidates` on an alias or a partial name, and
context was inconclusive. Decide whether the subject is the **same person** as one of the
candidates, or none of them.

A pair with `"method": "attach_pair"` is an attach task. The subject is a person drawn only from
Khoei's Muʿjam or Mamaqani's Tanqīḥ, which name men inside other men's entries, and holds no
entry of his own in a main Rijal work. The candidates are people who do hold one, carry the same
name form, and share a kunyah or nisbah with him. Brothers, fathers and sons often share all of
that, and so do two men of one family and city a generation apart. Decide on the teachers,
students, generation and the source's own words. When the subject describes itself as someone's
brother or son, or points to a different entry, that settles it.

## `kind: "split"`

One person as the pipeline assembled him. `profiles` lists his source **entries**, one per
entry in a Rijal work, each with its own `source_keys`, name, teachers, students, verdict and
quotation. Earlier rules and agents joined them, and some may describe a different man.
**Partition the entries into the men they describe.** Return clusters as in a group task, and
place every `merged_id` in exactly one cluster.

The default here runs the other way from a group task, because these entries were joined once
on some evidence. Move an entry out only when there is positive evidence that it is a different
man:

- a contradicting father or lineage, kunyah, generation or death date
- a verdict or quotation that plainly concerns someone else
- the source's own statement that this is another man (`وليس بـ`, `غير`, `مشترك بين`)

An entry that says too little to tell stays where it is. Splitting it off on no evidence
creates a new, false person.

An entry that itself mixes two men, with a heading for one and a quotation about another, goes
in a cluster of its own and is listed in `mixed`.

An entry marked `source_calls_title_shared` is one whose source says the name is shared by
several men: Khoei's «هو مشترك بين جماعة، والتمييز إنما هو بالراوي والمروي عنه», or «مشترك بين
الثقة وغيره». Such an entry heads the name as it occurs in chains, and its teachers, students and
verdicts belong to several men. It goes in a cluster of its own. The exception is when the
source goes on to say which man the name means here, and that man is the rest of the person.
Such an entry is not `mixed`; re-extracting it would not divide it.

A split with `"method": "entry_split"` shows a single entry page by page. The pipeline joined
adjacent pages of one book as one entry. Khoei and Mamaqani head consecutive entries for men of
the same name, so a run of «الحارث بن عمرو» pages may be several men. Partition the pages the
same way, using the same rule: move a page out only on positive evidence. A page that continues
the one before it, such as a verdict, a book list or a chain with no heading of its own, stays
with it.

Give each split a `confidence` of `high`, `medium` or `low`. Only high and medium are applied,
and a low answer goes to human review.

```json
{"task_id": "split:n000802", "clusters": [[4101, 4102, 4105], [4103, 4104]], "mixed": [],
 "confidence": "high",
 "notes": "4103/4104 are the Companion (companion of the Prophet, cursed by 'Ali); 4101/4102/4105 al-Najashi's thiqa al-Sayrafi, companion of al-Sadiq."}
```

## `kind: "verify"`

Two entries, `A` and `B`, that a rule joined on a matching name and little else. An audit found
about one such join in ten to be wrong. **Are they the same man?**

- `verdict`: `"same"`, `"different"` or `"cannot_tell"`, with `confidence` and `reason`
- `different` separates them, so give it only on positive evidence of two men
- `same` confirms the join, so give it only on positive evidence of one man
- `cannot_tell` leaves the join as the rule made it, and is the right answer when the entries
  share a name and say nothing else

```json
{"task_id": "verify:d-…", "verdict": "different", "confidence": "high",
 "reason": "A is al-Juhanī, a mawlā of Juhayna (Rijāl al-Shaykh 160); B is al-Hilālī (161)."}
```

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

Names that are **only a kunyah** — `أبي بصير`, `أبي عبيدة`, `أبي يعفور` — are shared by several
men by construction; that is why they come to you instead of being merged by rule. Treat such a
group like any other partition: join two of its profiles only on evidence in the quotations —
a full name the source gives, a teacher or student in common, a matching death year — never on
the kunyah itself. A thin profile that is only the kunyah and a passing mention belongs in a
cluster of its own.

This system publishes reliability verdicts attributed to named scholars. A wrong merge puts
a fabricated attribution on a public page under a real scholar's name. A missed merge only
leaves two thin profiles. The costs are not symmetric.

Do not use outside knowledge to override the sources in the batch. If you know a narrator's
biography from elsewhere, you may use it to *read* the quotations, not to assert facts the
batch does not contain.

## Output

Write a JSON file to the exact path you are given. No prose outside the JSON.

Other agents run beside you on other batches and may share your scratch directory. Name any
helper file after your batch (`show_group_0015.py`, not `show.py`), and check that what you read
carries your batch's name before deciding on it.

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
