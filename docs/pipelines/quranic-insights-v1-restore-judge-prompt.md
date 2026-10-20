# Judge Qur'anic verse connections to hadith

You judge whether a Qur'anic verse genuinely illuminates a hadith. A machine proposed the candidate verses from word overlap, shared topic tags, Qur'anic quotation and tafsir matches; many are only word overlap. Readers will see the verses you keep beside the hadith, with tafsir, as "Qur'anic light" on it. Keep only connections a scholar would accept.

Input: `/mnt/share/rewayaat-backup/tmp/v1-restore/qlight-judge-inputs/batch_NNNN.jsonl` (NNNN = the batch number given to you, four digits). One JSON object per line, 50 lines:
`{"hadith_id", "hadith_english", "hadith_arabic" (first 200 chars), "candidates": [{"verse_key", "surah_name", "verse_text", "snippets": [{"tafsir_name", "commentary_text"}]}]}`

Output: `/mnt/share/rewayaat-backup/tmp/v1-restore/qlight-judge-outputs/batch_NNNN.jsonl` — exactly one JSON line per input line, in the same order:
`{"hadith_id": "...", "kept": [{"verse_key": "...", "strength": "strong|moderate"}], "removed": [{"verse_key": "...", "reason": "<one sentence>"}]}`

Every candidate verse_key goes in exactly one of `kept` or `removed`. Never add a verse that is not a candidate.

Strength:
- **strong** — the verse directly illuminates this hadith: the hadith quotes, paraphrases or explains the verse; or the verse and the hadith teach the same specific thing (the same act, virtue, sin, ruling, event or person), so reading one deepens the other. The tafsir often confirms it.
- **moderate** — a real but general link: the same broad theme (e.g. both about patience, or the Hereafter) without the specific teaching in common.
- **removed** — weak or no link: a shared word or name used in a different sense, a generic theme, or a tafsir match on wording only. Say in `reason` what the hadith is about and why the verse does not fit.

Judge from the hadith's content, not from the scores or the tafsir alone. A hadith with no strong or moderate candidate gets `"kept": []`.

Process — do the judging yourself, by reading. Do NOT write or run any code, script or keyword matcher to make decisions; do not use the web. Read the input with the Read tool in chunks of 10 lines (offset 1, 11, 21, 31, 41; limit 10); if a read is refused as too large, read that chunk 5 lines at a time. After judging each chunk, append its 10 output lines to the output file (create it with the first chunk). You must rule on all 50 lines. Do not modify the input. Any helper file goes only under `/mnt/share/rewayaat-backup/tmp/v1-restore/scratch/`.

Final reply: one line of JSON per batch, `{"batch": NNNN, "hadith": 50, "strong": N, "moderate": N, "removed": N}`.
