# Is this the man the chain means? — auditing links from an isnād to a narrator's page

Every name in every isnād on this site is to become a link to that man's page. A resolver has
made those links: it matched each name in the chain against the men the rijāl books file as
entries, and where several men hold the name it either chose one on evidence or refused to
choose. **You are checking its answers against the chain.**

Your batch holds `tasks`. Each task is one narration's chain:

- `book` — the book the narration stands in
- `chain_ar` — **the isnād, exactly as the site prints it. This is what the link sits in.**
- `links` — one entry per name the resolver had something to say about

Each link carries `we_say`, which is one of three things, and your question depends on which:

| `we_say` | what the resolver claims | your question |
|---|---|---|
| `one` | exactly one man in the books holds this name, so there was nothing to decide | is `person` the man this chain means by this name? |
| `chosen` | several men hold it, and `because` names the evidence that picked this one — a book showing him beside the man next to him in this chain, or the practice of the book the chain stands in | is `person` the man this chain means, and does `because` bear it? |
| `several` | several men hold it and the books do not choose between them, so no link is made | is the name really undecidable here, or does this chain settle it? |

A link also shows `person` (the man we picked, under the longest name a book gives him),
`also` (other names he goes by) and `in_books` (which rijāl works hold an entry for him).
For `several` you are shown the `candidates` instead.

## The answers

For `one` and `chosen`:

| verdict | when |
|---|---|
| `right` | that is the man this chain means |
| `wrong` | it is a different man — and if you know who, put his name in `who` |
| `cannot_tell` | the chain is too damaged or the name too bare to say |

For `several`:

| verdict | when |
|---|---|
| `ambiguous` | the refusal is correct: this name in this chain could be more than one of them |
| `settled` | the chain does settle it — name the man in `who` and say what settles it |
| `cannot_tell` | the chain is too damaged to say |

## How to read a chain

- **Direction.** In «X عن Y», X received from Y: the man *after* a name is his teacher and the
  man *before* him is his student. Getting this backwards is the commonest way to be wrong,
  and a `because` that has it backwards makes the link wrong even if the man is right.
- **Layer.** A chain is a column of time. If the man we picked died two generations before the
  man receiving from him was born, the link is wrong however well the name matches. Death
  years are not shown; use what you know.
- **The book's conventions matter.** A compiler writes the men he received from in his own
  shorthand, and a bare name standing at the head of one author's chains is regularly one
  particular man even though several men of the books hold that name. Where a convention like
  that settles a `several`, say so, and name the man — it is exactly what we want to learn.
- **A claim resting on that practice is the one to test.** Some `because` lines say only that
  the book in question reads this name as one man. That is a real kind of evidence and the
  rijālī literature is full of it, but it is the weakest kind we use, and it is the kind this
  audit most needs judged. Two questions, not one: is that in fact how this book reads the
  name, and is this chain one of the places it holds? If the convention is real but this chain
  is an exception to it, the link is `wrong` — name the man you mean.
- **Shortened names.** A chain writes «صفوان» for «صفوان بن يحيى» and «ابن أبي عمير» for
  «محمد بن أبي عمير». A short form is not a wrong link; judge the man, not the spelling.
- **The Imams.** A kunya at the end of a chain names an Imam, not a man with an entry in the
  books: «أبي عبد الله», «أبي جعفر», «أبي الحسن», «أبي محمد». Which Imam is a question of
  layer — «أبي جعفر» is al-Bāqir or al-Jawād, «أبي الحسن» is al-Kāẓim, al-Riḍā or al-Hādī —
  and the narrator receiving from him is what places him. Say which and why. If nothing in the
  chain places him, `ambiguous` is the right answer and `wrong` is the right answer when we
  have named the wrong one.

## Rules

- **Judge the man, not our bookkeeping.** We are not asking whether the name string matches.
  We are asking whether a reader clicking that name would land on the right person.
- Do not hedge. A wrong `cannot_tell` hides a defect instead of reporting one, and a wrong
  `ambiguous` hides a link we could have made. Equally, do not call a link wrong merely
  because the man is obscure or the entry thin.
- `note` is one short clause and the only thing a reviewer can check you against, so make it
  specific: say what in the chain or in the books decides it. Name the man you mean.
- Answer every link in every task. Do not skip and do not invent links.
- Some answers in your batch have been tampered with on purpose, and some tasks appear twice.
  Nothing marks them. Judge each one as it comes.

## Output

Write a JSON file to the exact path you are given. No prose outside the JSON.

```json
{
  "batch": "<the batch filename>",
  "judgements": [
    {"link_id": "chain:0153.1", "verdict": "right",
     "note": "«عبد الله بن جعفر الحميري» is al-Saduq's own shaykh, the Qummi, in al-Amali"},
    {"link_id": "chain:0153.0", "verdict": "settled", "who": "محمد بن الحسن الصفار",
     "note": "al-Saduq's «محمد بن الحسن (رضي الله عنه)» at the head of a chain is Ibn al-Walid"},
    {"link_id": "chain:0153.3", "verdict": "wrong", "who": "محمد بن أبي عمير",
     "note": "«because» has the direction backwards: Aban is his teacher, not his student"}
  ]
}
```

- One object per `link_id`, with exactly one verdict.
- `who` is required on `wrong` when you know the man, and on every `settled`.
- Judge each link by reading it. Do not write a script that decides by string matching; you
  may use a script to print the tasks and to assemble your answers once you have made them.
- Other auditors work beside you. Name any helper file after your batch.
