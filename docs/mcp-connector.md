# MCP connector

The corpus, exposed to language-model clients over the Model Context Protocol, so someone
can ask about a narration inside ChatGPT or Claude and have the answer come from this index
rather than from the model's memory.

Motivating evaluation and scope: [issue #66](https://github.com/rewayaat/rewayaat/issues/66).

## Why this exists

Every narration here also lives on thaqalayn.net, so the connector cannot be justified as
access to hadith. What it provides that a web search does not:

| | Web search | This connector |
|---|---|---|
| Coverage | Tracks what has been written up in English and indexed | All 32,519 equally reachable |
| Exhaustiveness | "These appear to be the main ones" | `chapter_size: 7` |
| Arabic | Depends on the page being indexed | Native, over the matn |
| Negatives | Cannot distinguish absent from unindexed | Authoritative for these 18 books |
| Similarity, verse links | Not documents, so nothing indexes them | Returned directly |

The evaluation behind #66 measured web search at **1 of 3** on non-famous content, and found
that closed-book recall gets the matn right while getting the citation wrong.

## Endpoints

| Path | Transport | Notes |
|---|---|---|
| `/mcp` | Streamable HTTP | Current transport. What clients should use. |
| `/mcp/sse` + `/mcp/message` | HTTP+SSE | Deprecated in the spec, still what several clients try first. |

Public, `https://hadith.academyofislam.com`, and **unauthenticated** — the corpus is public
and every tool is read-only, so there is no identity to establish and nothing to authorise.
Claude's connector guidance is explicit that such a server may skip OAuth, and skipping it
removes the most common reason an install fails. Abuse control is at the ingress.

## Tools

`search` and `fetch` are not our design. ChatGPT's deep-research and company-knowledge paths
call **only** those two, and require the names, the single string argument and the result
shape; the rest are for Claude and for ChatGPT's developer mode.

| Tool | Arguments | Returns |
|---|---|---|
| `search` | `query` | `{results: [{id, title, url}]}` — ChatGPT's fixed shape |
| `fetch` | `id` | `{id, title, text, url, metadata}` — ChatGPT's fixed shape |
| `search_hadith` | `query`, `book?`, `topic_tags?`, `limit?`, `offset?` | Full narrations **plus `total_matches`** |
| `get_chapter` | `book`, `chapter`, `volume?`, `limit?`, `offset?` | A chapter in order **plus `chapter_size`** |
| `find_similar` | `id`, `match_type?`, `limit?` | Judged links with the written reason |
| `verses_for_hadith` | `id`, `limit?` | Qur'anic verses with tafsīr extracts |

Every tool is annotated `readOnlyHint: true`, which ChatGPT requires before it will treat one
as a knowledge source.

### `total_matches` and `chapter_size`

These are the point, not decoration. A model reading ten results with no denominator cannot
tell whether it has seen a subject or a tenth of it. The evaluation asked the open web for
the Kāmil al-Ziyārāt chapter on creation weeping for al-Ḥusayn and got five of its seven
narrations with no way to know two were missing; `get_chapter` returns `chapter_size: 7` and
says `Complete: all 7 narrations in this chapter are shown.`

### The corpus boundary

The evaluation found a model cannot tell "exists in the Shia tradition" from "exists in this
corpus" — it rated an absent narration high-confidence. We do not control the host's system
prompt, so the tool surface is the only channel: the boundary is in the server instructions,
in every tool description, and in the payload of every empty result and unknown-id error.

A miss means *not in these 18 books*. It is never evidence that a narration does not exist.

## Response shaping

A hadith `_source` is built for the website, which renders the matn, the isnād and the
similarity panel from separate pre-split fields. A model needs none of that.

```
five search results, raw _source   42,018 bytes
five search results, shaped         5,396 bytes   (7.8x)
```

Dropped: `llm_similar` (the largest field, and `find_similar`'s job), `englishContent` /
`arabicContent` (the matn again, pre-segmented), `englishChain` / `arabicChain` (the isnād,
split for rendering), and the `semantic_*_source` retrieval inputs. Applied at the
Elasticsearch `_source` level, so the difference is never transferred.

This is not tidiness. **Claude caps a tool result near 150,000 characters** and Claude Code at
25,000 tokens, and full Arabic is far denser in tokens than its character count suggests —
fifty chapter narrations measured 94,594 characters. Page maxima are set against those
ceilings, and `total_matches` / `chapter_size` mean a short page is not a lossy one.

## Code

| File | Role |
|---|---|
| `mcp/McpServerConfig.java` | Both transports, server instructions, keepalive |
| `mcp/McpToolCatalog.java` | Adapts tools to MCP; also the entry point for the site's own chatbot |
| `mcp/McpTool.java` | What a tool implements |
| `mcp/NarrationRepository.java` | Elasticsearch reads, with the tight field list |
| `mcp/NarrationView.java` | The shaping contract |
| `mcp/CorpusScope.java` | The 18-book boundary sentence |
| `mcp/tools/*.java` | One class per tool |

It runs **in-process** rather than as a separate service. The tools need the data, not the
API: `llm_similar` is a nested field `/v1/narrations` does not expose on its own terms, and
shaping a response after 42 KB has crossed a network hop saves nothing. The website's chatbot
is the third consumer and lives in the same JVM, so it calls `McpToolCatalog.invoke` directly
and never speaks JSON-RPC to itself.

## Dependencies

`io.modelcontextprotocol.sdk:mcp-core` carries the Jakarta servlet transports, so no
Spring-specific artifact is needed; `mcp-json-jackson2` binds it to the Jackson 2 that Boot
3.3 manages, rather than the Jackson 3 the aggregate `mcp` artifact pulls in.

The SDK declares Jackson 2.21 and Reactor 3.7.0; Boot 3.3.2 pins them to 2.17.2 and 3.6.8.
Verified against a running server that the handshake, `tools/list` and `tools/call` are
unaffected — but it is the thing to check first if a version moves.

## Protocol version

The SDK negotiates up to `2025-11-25`. The current spec is `2026-07-28`, which replaces the
initialize handshake with a per-request version declaration and adds a mandatory
`server/discover`; it defines explicit backward compatibility with the handshake-based
revisions, and Claude's documented support tops out at `2025-11-25`. Revisit when the SDK
catches up.

## Deployment

`k8s/ingress-mcp.yaml` is a separate Ingress because nginx annotations apply per object, and
`/mcp` needs three things the website does not:

- **`limit-rpm: 300`** rather than 50. Every user of a hosted connector arrives from that
  client's shared egress addresses, so one bucket is shared by all of them, and a single
  answer costs several calls.
- **`proxy-buffering: off`.** Streamable HTTP answers as a one-event SSE stream; with
  buffering on, nginx holds the response until the upstream closes.
- **`proxy-read-timeout: 300`** to match Claude's own timeout, so a held-open client stream
  is not dropped every minute. The transports also send a keepalive every 30s.

## Testing

`McpProtocolIntegrationTest` speaks the real transport — session id, `tools/list`,
`tools/call`, both the JSON and SSE framings — and pins the parts of the contract that are
not ours to choose. It seeds its own index with an explicit mapping: under a dynamic one
`book` becomes a text field and every term filter would match nothing while still passing.

Probing a running server by hand:

```bash
curl -X POST http://localhost:8002/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
       "protocolVersion":"2025-06-18","capabilities":{},
       "clientInfo":{"name":"probe","version":"1.0"}}}'
```

`tools/list` and `tools/call` need the returned `Mcp-Session-Id` header, and answer as SSE.

## Not built yet

- **`lookup_narrator`.** Blocked: narrator work is complete through Phase 2 (29,305 merged
  profiles in `tmp/narrators_merged.json`), but Phase 3, the Elasticsearch import, has not
  started and there is no `rewayaat_narrators` index. This is the strongest "no webpage can
  answer this" case in the evaluation, so it is the first thing to add once Phase 3 lands.
- **Semantic `search_hadith`.** Matching is BM25. Embedding a query at request time needs the
  `rewayaat-multilingual-e5-large` inference endpoint, which is not deployed — the stored
  vectors support kNN between existing documents, which is what similarity uses, but not from
  arbitrary text. The consequences are documented in the tool description rather than left
  for a model to discover: an English gloss can miss what the Arabic finds exactly, and a
  common name pulls in isnād chains.
