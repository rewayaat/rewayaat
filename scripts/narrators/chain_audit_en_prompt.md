# Is the link on the right name? — auditing the English isnād links

Every name in every isnād on this site is to become a link to that man's page. The Arabic
chains are linked and have been audited three times. The English chains are linked too, but
they were never resolved against the rijāl books: the translation was lined up against the
Arabic chain name for name, and each English name took the man the Arabic name **in the same
place** had already been given.

So there are two ways an English link can be wrong, and you are being asked about one of them.
Whether the right man was chosen is the Arabic resolver's question and it has been measured.
Whether the link landed on the right English words is this question, and nothing has measured
it. **You are not judging who the man is. You are judging whether the English name and the
Arabic name are the same name.**

Your batch holds `tasks`. Each task is one narration:

- `book` — the book it stands in
- `chain_ar` — the isnād in Arabic, as the site prints it
- `chain_en` — the same isnād in the published English translation
- `links` — one entry per link, with `english` (the words the link covers in `chain_en`) and
  `arabic` (the words in `chain_ar` the man was taken from)

## The answers

| verdict | when |
|---|---|
| `same` | the English words and the Arabic words are the same name |
| `different` | they are not — the link sits on the wrong name in the English chain |
| `cannot_tell` | the translation is too loose, or the chain too damaged, to say |

## How to judge

- **Read the two chains side by side.** The English runs in the same order as the Arabic. The
  question is whether this English name occupies the place this Arabic name occupies.
- **Transliteration varies and that is not an error.** «Hassan» and «al-Hasan», «'Isa» and
  «Isa», «Zurarah» and «Zurara», «ibn» and «b.» are the same name. Judge the name, not the
  spelling.
- **A shortened form is not an error either.** «Ibn Abi 'Umayr» for «محمد بن أبي عمير», and
  «Safwan» for «صفوان بن يحيى», are the same man named more briefly.
- **The order inside a name matters, and this is the mistake to watch for.** «Ahmad ibn
  Muhammad» and «محمد بن أحمد» are two different men, not one name spelled loosely. So are
  «Muhammad ibn al-Hasan» and «الحسن بن محمد». If the English reverses the name, that is
  `different`.
- **Watch for the link one name too early or too late.** A translation sometimes drops a name
  the Arabic has — often the Imam who closes the chain — and a link that slipped past the gap
  will sit on the neighbour. If the English words name the man standing *beside* the Arabic
  one in the chain, that is `different`.
- Where the English translation itself is simply wrong about the name, say `different`. We
  would rather leave a name unlinked than point a reader at somebody the words do not name.

## Rules

- Answer every link in every task. Do not skip and do not invent link ids.
- Do not hedge. `cannot_tell` is for a translation too loose to compare, not for a close call.
- `note` is one short clause and the only thing a reviewer can check you against: say what in
  the two chains decides it.
- Some links in your batch have been moved on purpose, and some tasks appear twice. Nothing
  marks them. Judge each one as it comes.
- Judge each link by reading it. Do not write a script that decides by string matching; you
  may use a script to print the tasks and to assemble your answers once you have made them.

## Output

Write a JSON file to the exact path you are given. No prose outside the JSON.

```json
{
  "batch": "<the batch filename>",
  "judgements": [
    {"link_id": "en:0012.0", "verdict": "same",
     "note": "«Muhammad ibn Yahya» is «محمد بن يحيى», first in both chains"},
    {"link_id": "en:0012.1", "verdict": "different",
     "note": "English has «Ahmad ibn Muhammad», the Arabic «محمد بن أحمد» — reversed, two men"}
  ]
}
```
