# Migrating the narrations index

The application reads and writes the index named by `REWAYAAT_INDEX`. That now
names an **alias**, `rewayaat_hadith`, rather than an index.

## Why an alias

An Elasticsearch analyzer cannot be changed on an existing field. Every change
to how text is tokenised - a new synonym, a different stemmer, folding a script
- means building a new index and copying 32,519 documents into it. Without an
alias each of those is also an application deploy, timed so that the config
change and the new index land together. With one it is a data operation, and
the rollback is a single call rather than a second deploy.

The alias carries writes as well as reads, because narrations can be edited.
Elasticsearch allows that only while an alias resolves to exactly one index, so
`migrate_index.py` always *moves* the alias and never adds a second index to it.
An alias pointing at two indices would fail every hadith edit.

## Running a migration

```bash
# 1. build the new index and check it, without touching the alias
python3 scripts/search/migrate_index.py --host http://localhost:9200 --dry-run

# 2. try the application against the new index directly
REWAYAAT_INDEX=rewayaat_hadith_20260909 mvn spring-boot:run -Dspring-boot.run.profiles=dev
python3 scripts/search/evaluate_search.py

# 3. move the alias
python3 scripts/search/migrate_index.py --host http://localhost:9200
```

The script refuses to move the alias if the reindex reported failures or if the
document counts disagree, and it never deletes the index it copied from.

## Against production

Production Elasticsearch is `elasticsearch-v2` in the `elastic-v2` namespace and
is not exposed outside the cluster, so reach it with a port-forward:

```bash
kubectl port-forward -n elastic-v2 svc/elasticsearch-v2 9201:9200
python3 scripts/search/migrate_index.py --host http://localhost:9201 --dry-run
```

The first migration is the only one that needs a deploy, because
`REWAYAAT_INDEX` has to change from `rewayaat_updated` to `rewayaat_hadith`
once. Order matters: build the index and move the alias **before** rolling out,
so that the alias exists when the new pods start. Afterwards the deploy and the
migration are independent.

## Rolling back

While the alias is in place, roll back by moving it to the previous index -
`migrate_index.py` prints the exact call when it finishes. The old index is left
untouched, so this loses nothing.

Before the first rollout, or if the alias itself is the problem, set
`REWAYAAT_INDEX` back to `rewayaat_updated` and roll out.

## Retiring an old index

Keep the previous index until the new one has served real traffic. Once you are
satisfied, `DELETE /rewayaat_updated`. Nothing else refers to it - the
`rewayaat_quran`, `rewayaat_tafsir`, `rewayaat_users` and
`rewayaat_user_collections` indices are separate and unaffected by any of this.

## What the mapping does

`scripts/search/v2_mapping.json` is the mapping the script applies. Two
analyzers matter:

- **`arabic_norm`** on the Arabic fields: strips vowel marks and tatweel and
  folds alef, teh marbuta and alef maqsura. No stemmer - `جنة` stays `جنه`
  rather than collapsing to `جن`, because exact wording has to keep meaning
  something in a corpus of narrations.
- **`english_fold`** on the English fields: folds transliteration so `Muḥammad`
  and `Muhammad` are one term, and stems lightly with `kstem`, which yields
  `narration` rather than porter's `narrat`. Its search-time counterpart
  **`english_fold_search`** adds `translit_names`, the synonym list that joins
  `husayn`/`hussain` and `ghadir`/`ghadeer`. Because that list is applied at
  search time only, names can be added to it without a reindex - though the
  index does have to be recreated for the settings change itself.

The keyword metadata fields carry an analysed `.text` sub-field so that a bare
word both matches and highlights inside them, while the base `keyword` stays
exact for field filters.
