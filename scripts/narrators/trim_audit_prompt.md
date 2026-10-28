# Was the dropped nisba his? — auditing links made by trimming a name

Read `chain_audit_prompt.md` in this directory first. Everything in it holds here: the
direction of a chain, its layers, the verdicts `right` / `wrong` / `cannot_tell`, the output
format, and the controls. This page says only what is different about this batch.

**Every link in this batch was made the same way.** The name as the chain writes it is held by
nobody in the rijāl books. So the resolver took words off the end of it, the nisba, the trade,
the town, «الرزاز», «العلوي», «الحنظلي», until what was left was a name somebody does hold, and
linked that man. `name` is the chain's full name; `person`, `also` and `entry_opens` are the man
the shortened name reached; `entry_opens` is the first lines of a book's entry on him.

Sometimes that is exactly right: the chain adds a nisba the books never bothered to write, and
the man is the only one he can be. «مسعود العياشي» is Masʿūd b. Muḥammad al-ʿAyyāshī.
Sometimes it is badly wrong: the dropped word was the only thing saying who this is, and it
names a different man. «محمد بن جعفر الرزاز» is Abū al-ʿAbbās al-Razzāz, al-Kulaynī's shaykh,
and not Ibn Baṭṭa; «عبيد الله بن موسى العلوي» in an early chain is not a Sayyid of the sixth
century.

## What to weigh

- **Does the dropped word fit him?** Is it his nisba, his trade, his tribe, his town, or one a
  book could plausibly add? Or does it contradict what `entry_opens` says of him — a different
  tribe, a different city, ʿAlawī against non-ʿAlawī?
- **Does he fit the layer?** Who receives from him in this chain, and whom does he receive
  from? A man of al-Ṣādiq's companions cannot stand where a shaykh of al-Ṣadūq stands.
- **Is there a better-known man the full name obviously means?** If you know one, the link is
  `wrong`; put his name in `who`.

Call it `right` only when you would send a reader to this page for this name. A plausible man
whom nothing in the chain or the entry supports, where the dropped word is unexplained, is
`cannot_tell` — not `right`. A man whom the dropped word or the layer rules out is `wrong`.

## The note

Say what decided it, in one clause: «الرزاز is not his nisba; Ibn Baṭṭa is al-Mufīd's
generation, this chain is al-Kulaynī's shaykh», or «al-ʿAyyāshī is his own nisba; the entry
opens with it».
