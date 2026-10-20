# Strict second look at Qur'anic verse connections to hadith

A first judge has already read every candidate verse for these hadith and kept only the ones it rated **strong**. You are the second, stricter judge. Readers see every verse you keep beside the hadith, with tafsir, as "Qur'anic light" on it, so a weak link misleads them. Keep a verse only if a scholar teaching this hadith would naturally cite this verse for it. When you are unsure, remove it.

Input: `/mnt/share/rewayaat-backup/tmp/v1-restore/qlight-strict-inputs/batch_NNNN.jsonl` (NNNN = the batch number given to you, four digits). One JSON object per line, up to 50 lines:
`{"hadith_id", "hadith_english" (first 600 chars), "hadith_arabic" (first 300 chars), "candidates": [{"verse_key", "verse_text", "surah_name_english", "snippet_context" (first 400 chars of a tafsir passage)}]}`

Output: `/mnt/share/rewayaat-backup/tmp/v1-restore/qlight-strict-outputs/batch_NNNN.jsonl` — exactly one JSON line per input line, in the same order:
`{"hadith_id": "...", "kept": ["verse_key", ...], "removed": ["verse_key", ...], "reasons": {"<removed verse_key>": "<one sentence>"}}`

Every candidate verse_key goes in exactly one of `kept` or `removed`. Never add a verse that is not a candidate. Give a `reasons` entry for every removed verse; `"reasons": {}` when nothing is removed.

Keep — the connection is specific and direct:
- the hadith quotes, paraphrases, or explains this verse, or names it; or
- the hadith and the verse address the same specific ruling, act, virtue, sin, event or person, so that reading one plainly deepens the other.

Remove — the connection is general or indirect:
- they share only a broad theme (patience, the Hereafter, knowledge, God's mercy) without the same specific teaching;
- the link rests on a shared word or name used in a different sense or context;
- the link needs a chain of reasoning, or holds only through the tafsir passage and not through the verse itself;
- the verse is about a different people, event or ruling that merely resembles the hadith's subject.

Judge from the hadith and the verse text. The tafsir context can confirm a link; it cannot create one. A hadith whose candidates all fail gets `"kept": []`.

Process — do the judging yourself, by reading. Do NOT write or run any code, script or keyword matcher to make decisions; do not use the web. Read the input with the Read tool in chunks of 10 lines (offset 1, 11, 21, 31, 41; limit 10); if a read is refused as too large, read that chunk 5 lines at a time. After judging each chunk, append its output lines to the output file (create it with the first chunk). You must rule on every line. Do not modify the input. Any helper file goes only under `/mnt/share/rewayaat-backup/tmp/v1-restore/scratch/`.

Final reply: one line of JSON, `{"batch": NNNN, "hadith": N, "kept": N, "removed": N}`.
