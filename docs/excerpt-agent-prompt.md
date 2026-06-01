# Excerpt Highlight Agent Prompt

Use this prompt when spawning agents to process excerpt batches.

## Agent Prompt Template

```
You are generating highlighted excerpts for Quranic light tafsir snippets.

Read the input file at tmp/qlight-excerpt-inputs/batch_XXXX.jsonl. For each hadith:

1. Read the hadith text and each of its candidate snippets
2. For each snippet, find the most relevant passage (the part that directly connects to the hadith's theme/topic)
3. Wrap that passage in <em> tags. Cap the <em> section at 200 words max.
4. If the snippet has NO genuinely relevant passage connecting to the hadith, output null for that snippet (it will be dropped)

The <em> tags should highlight the KEY SENTENCES that make the connection. Don't just highlight matching words - highlight the ARGUMENT or DISCUSSION that connects to the hadith.

Write results as JSONL to tmp/qlight-excerpts/batch_XXXX.jsonl. Each line:
{"hadith_id": "...", "candidates": [{"verse_key": "X:Y", "snippets": [{"index": 0, "highlight_excerpt": "...text with <em>key part</em> ..."}, ...]}]}

If a snippet has no relevant passage: {"index": 2, "highlight_excerpt": null}

Rules:
- <em> section must be <= 200 words
- If the entire snippet is relevant and <= 200 words, wrap the whole thing in <em>
- The highlight_excerpt should contain the FULL snippet text (not truncated), but with the key part wrapped in <em> tags
- If the snippet is longer and the relevant part is in the middle, include surrounding context but only <em>-tag the key portion
- Be strict: if you can't find a meaningful connection between the snippet and the hadith, output null for that snippet
```

## Spawn Pattern (in Claude Code)

To spawn 8 agents in parallel for missing batches:

```python
# First check progress
!python3 scripts/check_excerpt_progress.py

# Then spawn agents for the next 8 missing batch numbers
# Use the prompt template above, replacing XXXX with the batch number
```
