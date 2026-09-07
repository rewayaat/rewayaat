# Architecture decision records

One file per decision that constrains future work. An ADR is worth writing when a choice
is not recoverable from the code alone — when the *absence* of an alternative is the point,
and a reasonable person would otherwise re-introduce it.

[../architecture.md](../architecture.md) describes what the system is. These describe why it
is that and not something else.

| ADR | Decision |
|---|---|
| [adr-001](adr-001-mcp-connector.md) | The MCP connector: one query builder, a shaped output, and a fixed façade for ChatGPT |

## Enforcement

Prose does not stop drift. Rules in these records that can be checked mechanically are
checked by `src/test/java/com/rewayaat/architecture/ArchitectureRulesTest.java`, which fails
the build. Where a rule has an existing violation, the test names it as an exemption rather
than being weakened — so the rule still bites for new code, and the exemption list is a
visible to-do rather than a silence.
