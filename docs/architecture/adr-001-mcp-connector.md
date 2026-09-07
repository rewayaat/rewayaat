# ADR-001: The MCP connector

**Status:** accepted, 2026-09-07 · **Supersedes:** nothing · **Enforced by:**
`ArchitectureRulesTest`

## Context

The corpus is served to three consumers that are not browsers: Claude, ChatGPT, and a chatbot
on this site. All three want the same narrations the website already serves, in a different
shape and through a different protocol.

An evaluation (issue #66) established what the connector is actually for, and it is not
"access to hadith" — every narration already links to a public page elsewhere. It is
*uniform retrievability* (all 32,519 equally reachable by field-aware query, where web
coverage is wildly non-uniform), *bounded results* (`chapter_size: 7` is a fact; "these appear
to be the main narrations" is not), *Arabic-native matching*, *authoritative negatives*, and
the *derived joins* — judged similarity, verse links — that are not documents anyone has
crawled.

## Decisions

### 1. One query builder, two output shapes

Free-text search over the corpus is built in exactly one place: `QueryStringQueryResult`. A
caller that needs a different output shape adds a seam to it. A caller that needs a different
field set passes a parameter. Nobody assembles a second query.

This is the expensive one, learned by breaking it. The MCP repository originally built its own
query, justified by needing a much tighter field list — a correct premise and the wrong
conclusion, because a field list is a `_source` argument. The fork silently lost field scoping
(`chapter:"…"`, `source:"…"`, `volume:"…"`), precise-match handling and topic-tag
normalisation. Nothing failed. The connector simply meant something different from the same
words typed into the website, and it introduced a bug the shared path would have prevented: an
inline `book:"…"` was OR-ed against the search terms and *widened* the result set, so a search
scoped to Kāmil al-Ziyārāt returned 3,348 matches, none from that book.

The seams that exist for this, and what each is for:

| Seam | For |
|---|---|
| `result()` | The website: highlighting, facets, display segmenting |
| `rawResult(sourceIncludes)` | Callers that shape their own output: raw `_source`, no segmenting |
| `queryFields(fields)` | Callers whose relevance problem differs from the website's |

**Corollary:** a structured tool argument that duplicates a query capability (`book`,
`match_mode`) is expressed *through* the shared path, not beside it. `book` becomes a field
scope on the query string; `match_mode` is resolved by `HadithQueryService.isPreciseMatchMode`,
which both the REST endpoint and the tool read, so "precise" cannot come to mean two things.

### 2. The tools shape the response; the transport does not

A hadith `_source` is built for a browser. Sent whole to a language model it is roughly
42,000 bytes where 5,400 will do, and the excess is not padding — it is `llm_similar` (the
largest field, and `find_similar`'s job to return deliberately), the `semantic_*_source`
retrieval inputs, and `*_ar` metadata a tool result has no use for.

Shaping therefore happens in `NarrationView` and the tools, against an explicit field list,
and *not* by post-processing a full API response. Response size is a correctness property
here, not an optimisation: Claude Code caps a tool result at 25,000 tokens, and Arabic is far
denser in tokens than its character count suggests. This is why per-tool maxima are small
(`search_hadith` 15, `get_chapter` 20) and why they were halved once measured.

### 3. `search` and `fetch` are a façade we do not own

ChatGPT's company-knowledge path calls only two tools, named `search` and `fetch`, each taking
one string, each returning a fixed object as `structuredContent` *and* as the same JSON in a
text block. Those two exist solely to satisfy that contract. They are not the real surface and
should not grow arguments, however tempting — a richer `search` is a new tool.

Everything the connector is actually good at lives in the other tools, which Claude and
ChatGPT's developer mode can both call.

### 4. In-process, not over HTTP

The MCP server runs inside this application, and the site's chatbot calls
`McpToolCatalog.invoke(name, arguments)` directly rather than speaking JSON-RPC to itself.

The tools need the data, not the API. Calling `/v1/narrations` from the same JVM would mean a
Tomcat worker blocking on another Tomcat worker — thread-pool starvation for no isolation —
and would fetch 42 KB across a loopback hop to discard seven eighths of it. If the connector
is ever deployed as its own service, HTTP becomes the right seam and `/v1/narrations` should
gain field selection; that is a different decision, to be recorded then.

### 5. The corpus boundary is stated in the tools, not assumed

We do not control the host prompt. A model that finds nothing must not report that a narration
does not exist — only that it is not in these eighteen books. `CorpusScope` carries that
sentence and every tool description includes it or points at it.

### 6. Deviations from the MCP spec are recorded, not silent

The `Origin` header is deliberately not validated, which the Streamable HTTP spec words as a
MUST. That requirement protects servers whose defence is their network position; this one is
public, unauthenticated and read-only. Enforcing it would mean allowlisting origins Claude and
ChatGPT may change without notice.

The rule this establishes is the general one: a deviation from a published contract is written
down where the next person will find it, with what it buys and what it costs. The other MUST
in that section — 400 on an unsupported `MCP-Protocol-Version` — is enforced by
`McpProtocolVersionFilter`, because there was no cost to being correct.

## Consequences

- A change to search semantics reaches the website and the connector together. That is the
  point, and it also means a change made carelessly breaks both.
- The connector cannot be extracted into its own service without revisiting decision 4.
- Search is BM25. Semantic search was considered and deliberately not built: the caller is a
  language model that already reformulates and retries, which is what an embedding does, only
  worse and once. See `../mcp-connector.md`.

## Known exemptions

`HadithController.pageForHadithId` builds its own `query_string` rather than going through
`QueryStringQueryResult`, and so does not parse field scopes. A `page_for_id` call for a
field-scoped query therefore computes a page number against a different result set than the
one being paged. This predates the ADR; `ArchitectureRulesTest` names it explicitly so the
rule still bites for new code.
