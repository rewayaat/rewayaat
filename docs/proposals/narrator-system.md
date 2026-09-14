# Narrator System

> **Where things stand (2026-09-13).** 42,076 narrator entries have been extracted from eight
> Rijal books and resolved into 24,239 people, each with a permanent identifier derived from a
> record of identity decisions. None of it is published yet: known identity defects remain,
> and Part III's stages 2–5 must clear them first. Nothing here serves traffic. Tracked in
> [#88](https://github.com/rewayaat/rewayaat/issues/88); the code is on the
> `feature/narrators` branch.

This document has three parts. [Part I](#part-i--what-and-why) says what we are building and
the principles it answers to. [Part II](#part-ii--how-we-got-here) tells the story so far —
what was tried, what went wrong, and what it taught us. [Part III](#part-iii--where-we-are-going)
sets out the strategy that follows and the staged plan. The appendices hold the current
state, the design reference, the measured findings and the extraction record.

---

## Part I — What and why

### What we are building

Every hadith in Rewayaat opens with its isnad — the chain naming who heard it from whom,
back to an Imam or the Prophet. Classical scholarship judges a narration largely by that
chain, and judges the chain by what the Rijal books say about each person in it. The
narrator system brings that material to where readers and researchers need it:

1. **A person for every narrator.** Each narrator resolved to one identity, carrying every
   form of name he goes by, drawn from the eight major Rijal works.
2. **Every scholar's verdict, attributed.** What Najashi, Tusi, Kashshi, Ibn al-Ghadaʾiri,
   Khoei and the others say about him — quoted verbatim, attributed by name, disagreements
   left visible rather than settled by us.
3. **Every chain linked.** Every name in every chain linked to the person it refers to, so a
   reader can click through to a narrator and a researcher can follow a chain.
4. **A transmission network open to analysis.** The linked chains exported as a graph, so a
   researcher can ask with ordinary tools how many time-ordered paths connect two people.

It serves three audiences: readers of the website (narrator pages, clickable names); the MCP
connector, whose evaluation in #66 named `lookup_narrator` the strongest case no webpage can
answer; and researchers, through the graph.

### Principles

Every stage is held to these. Part II shows what happened when they were not in place.

1. **Precision over coverage.** A profile that fuses two men publishes one man's verdict under
   the other's name, attributed to a named scholar — a fabricated attribution. A missed merge
   only leaves two thin profiles. The costs are not symmetric, so every stage fails toward
   *separate* and *absent*, never toward *merged* and *asserted*.
2. **Only what the sources say.** Verdicts are quoted, not paraphrased or graded by us, and
   each stays attached to the scholar who gave it.
3. **Everything traceable.** Every name form, verdict and quotation carries the book and page
   it came from; every identity decision carries its evidence and what made it.
4. **Identity is correctable.** Identity decisions are durable records, not a side effect of a
   pipeline run. Correcting one must never mean starting over, and must reach every hadith
   that depends on it.
5. **Built for analysis.** The end product is a graph researchers can query with standard
   tools, not only pages a website can render.
6. **Measured, not declared.** A stage is done when its output has been measured against a
   stated standard, not when its run completes.
7. **No external LLM APIs.** Language-model work runs through Claude sub-agents.

### Terms

| Term | Meaning |
|---|---|
| isnad | the chain of narrators before a hadith's text |
| matn | the text itself |
| Rijal | the discipline of evaluating narrators; its books are biographical dictionaries |
| kunyah | a name of the form *Abū X* (أبو سعيد) — shared by many men |
| nisbah | an attribution to a place, tribe or trade (الكوفي، الأزدي، الصفار) |
| laqab | an epithet |
| *thiqa* / *ḍaʿīf* | reliable / weak — the core verdicts |
| *mukhtalaf fīh* | disputed — the scholars disagree about him |
| ṭabaqa | generation — who could have heard from whom |

---

## Part II — How we got here

### Timeline

| When | What happened |
|---|---|
| 2026-03 → 06 | The first attempts discovered narrators by parsing chains directly (`chain-audit.sh`, `sample_chain_extraction.rb`, `extract_chains_for_narrators.py`). Removed on 2026-06-03 in favour of a **Rijal-first** approach: build people from the biographical dictionaries, then match chains against them. |
| 2026-06-02 | Narrator code lands with the v2.0 application: an Elasticsearch index manager, a service and API, name matching, an Infallible registry. |
| 2026-06 | **Phase 1, extraction.** Claude reads downloaded pages of eight Rijal works and writes structured profiles — 42,076 in all. |
| 2026-06-09 | The narrator Java is deleted as unused — before the data it was built to serve existed. |
| 2026-06-10 | **Phase 2, rule layers.** 42,076 profiles merge to 30,807; 11,149 cases deferred as ambiguous. |
| 2026-06-14 | **Phase 2, LLM layer**, run through the Anthropic API. 3,976 of the 11,149 deferrals judged; 29,305 profiles. Recorded as "Phase 2 done". It was not. |
| 2026-09-04 | Repository tidy. The design moves to `docs/proposals/`, marked not implemented; the pipeline scripts are no longer in the tree. The data survives in `tmp/`. |
| 2026-09-07 | The MCP connector ships (#86) without `lookup_narrator` — deliberately, because the narrator data was not fit to serve. |
| 2026-09-07 | **Audit.** The June merge is found to over-cluster badly, its LLM layer a third finished, its data unvalidated, two books truncated. The merged file is declared unfit to publish and #88 opened. |
| 2026-09-07 | **Rebuild.** Output contract enforced, merge rewritten with guards, the LLM layer redesigned and moved to sub-agents with versioned, immutable runs. The agents surface further defects; each is checked against the data and fixed. |
| 2026-09-13 | **Layer 3 complete.** 73 batches of agent decisions, zero validation errors: 24,239 people. |
| 2026-09-13 | Checking the most-cited narrators shows one man still spread across several profiles, fusions the pipeline cannot undo, and identities that cannot be corrected without redoing agent work. This shapes Part III. |
| 2026-09-13 | **Stage 1: permanent identity.** Every decision so far — 16,494 of them, including all 73 batches of agent answers — recorded against permanent source keys. People rebuilt from the record reproduce the 24,239 exactly, and each has a permanent identifier. |
| 2026-09-14 | **Stage 2: name forms and agent precedence.** Kunyahs compared case-folded; a man's relatives kept out of his aliases, which separates the father and son 1405 had fused; an agent's separation now binds the rules; names that are only a kunyah go to the agents. Every file written durably after a run was killed for memory mid-merge. |
| 2026-09-14 | **Stage 3 begins.** The top-up Layer 3 run answers the 34 batches stage 2 created: 24,660 people. Asking why the famous narrators stayed split finds each man's own entries in the main Rijal works sitting in different people that no agent had ever compared, which shapes the cross-form pass. |
| 2026-09-14 | **Cross-form pass.** 709 tasks, one per shared name form, answered and validated. For every one of the twelve most-cited narrators, all his main-book entries now sit in one person: 24,079 people. What remains are well-described Khoei and Mamaqani profiles of those men that hold no main entry of their own, which the attach question takes. |
| 2026-09-14 | **Attach pass; stage 3 done.** 1,077 pair tasks put Khoei and Mamaqani profiles beside the main-entry people they may be. 654 were joined, leaving 23,425 people. The profiles still bearing one of the twelve narrators' names are different men, fused profiles, or places where two agents disagree — work for the split pass and the reviewers. |
| 2026-09-14 | **Stage 4: the split pass.** 371 people who might have been assembled from several men went to agents as their entries, and 229 were split. The pass showed Layer 0 joining the pages of consecutive homonyms in Khoei and Mamaqani. 127 such entries were split page by page, the people they belonged to were asked again, and the leftover pages attached where they belong. 23,879 people; verdict clashes 159 → 139. |

### What went wrong in June

The June pipeline completed and its output looked plausible. Measured, it was not.

- **Identity was treated as string matching.** The merge indexed nisbahs and kunyahs as if
  they were names, and merged any profile that had a single candidate, unchecked. Unrelated
  men chained together: one profile, محمد بن سنان, absorbed 195 source entries under 129
  aliases, ten of them the names of *other* narrators in Ibn al-Ghadaʾiri's book. At the same
  time the merge failed to connect the same man across books: only 11.7% of profiles drew on
  more than one.
- **Model output was trusted as data.** Extraction output was written without validation:
  289 invented field names; 88 free-text spellings of a verdict, including `assessed` — a
  quarter of all verdicts, which says nothing; and matching keys produced by the model rather
  than computed.
- **Completion was mistaken for success.** Two books were silently truncated — Rijal al-Tusi
  yielded 123 of roughly 8,000 entries — because page counts were checked, not entry counts.
  The LLM layer judged 36% of its queue, the rest shipped unresolved, and the stage was
  recorded as done.
- **The serving code was deleted before the data existed**, so nothing exposed the data's
  problems in use.

Each is the inverse of a principle in Part I.

### What the September work taught us

The rebuild fixed the June failures: the largest profile went from 195 unrelated entries to
56 entries for one man under one name, cross-book linkage rose from 11.7% to 21.2%, and every
agent decision passed validation. It also taught six things that change the strategy.

1. **Rules can propose, but only the sources can decide.** Large same-name groups — 96
   profiles named أحمد بن محمد, most stating no kunyah, city or death year — cannot be
   separated by rule. Sub-agents reading the Arabic quotations could, and cited the sources
   when they did. Judgment has to rest on the texts.
2. **Nearly every defect was a confusion between kinds of name.** Nisbahs indexed as names;
   kunyahs leaking back in through alias lists; English kunyahs passing a word-count test;
   kunyahs in accusative case (أبا) not recognised as the same kunyah; a son's alias list
   carrying his father's name; the nisbah of an accuser named in a man's entry attaching to
   the man. Name forms need to be a subsystem in their own right, not a list of fields.
3. **A pipeline that can only merge cannot recover.** Layer 3 reunites; it cannot pull apart.
   Profiles fused early — Najashi's reliable ʿAmr b. Ḥurayth joined to a Companion of the same
   name, a father joined to his son — stay fused.
4. **A man's name forms must be reconciled as a whole.** Grouping by identical name left Sahl
   b. Ziyād in at least three profiles, with Najashi's decisive verdict on a different one from
   the bulk of him. Ten of the twelve most-cited narrators checked are split the same way.
5. **Unstable identifiers make every correction expensive.** Profile numbers are reassigned on
   every merge run, so each fix to the merge discarded agent answers already in hand. The
   next fix would discard all 73 batches.
6. **Checks inside a profile cannot see problems across profiles.** Every quality check run
   looked inside profiles; none could see one man spread across several. Person-level checks
   and a random-sample accuracy audit are needed alongside them.

---

## Part III — Where we are going

### The shift in strategy

June's strategy was to *produce a merged file*. The strategy from here is to *keep a record of
identity decisions over sources that never change*, and to derive every product — the
website's index, the hadith links, the research graph — from that record. A correction is an
addition to the record, and everything downstream follows from it.

### Target architecture

Four layers. Only the first two are ever written, by extraction, rules, agents or reviewers;
the last two are always recomputed.

1. **Sources — immutable.** Each Rijal entry is keyed by book and position (`najashi:512`),
   each chain by its hadith. Sources are never edited; re-extracting a book adds a new
   version rather than rewriting the old one.
2. **Decisions — append-only.** Every identity judgment, whether by rule, agent or reviewer, is
   a record: which source entries, same person or not, the method, the evidence quoted, the
   confidence, the date. Resolving a name in a chain is a record of the same kind: hadith,
   position in the chain, the text as written, the person it resolves to, method, confidence.
   Nothing is overwritten. A correction is a new decision that supersedes an earlier one, and
   the earlier one stays on file.
3. **People — derived, with permanent identifiers.** Computed from sources and decisions. A
   person identifier, once published, never disappears: when two people turn out to be one,
   the absorbed identifier redirects; when one turns out to be two, a new identifier is minted
   and the record says where each part went.
4. **Products — derived, regenerable.** The Elasticsearch narrator index (website and MCP), the
   narrator links on each hadith, and the research graph. None is edited by hand.

This is what makes a correction cheap: record the decision, recompute the people it touches,
and re-index the hadith whose chains mention them — a lookup, not a rerun.

Stage 1 built layers 1–3: source keys, the decision record, and people with permanent
identifiers derived from it — see [the decision record](#the-decision-record-and-permanent-identifiers)
in Appendix B. Every decision made so far now lives in it, including all 73 batches of agent
answers, and rebuilding from the record reproduces today's people exactly.

### Names as a subsystem

A person carries every form of his name, each typed and provenanced:

| Form | Example | Role in identity |
|---|---|---|
| Full lineage | سهل بن زياد الآدمي الرازي | identifies |
| Short name | سهل بن زياد | identifies weakly — shared with others |
| *Ibn X* form | ابن أبي عمير | identifies, for some narrators |
| Kunyah | أبو سعيد | narrows, never identifies |
| Nisbah or laqab | الآدمي، الرازي | narrows, never identifies |
| Chain forms | سهل، عنه، عن أبيه | resolved only in context |

The rules. Identity is decided over a person's whole set of forms, never one string at a time.
Kunyahs, nisbahs, editorial placeholders and patronymics never become identifiers, whatever
field they arrive in. Kunyahs are compared case-folded — أبا and أبي are أبو, except in أبي بن
كعب, which is the name Ubayy. A nisbah attaches to a man only where the text applies it to him,
not to someone else named in his entry.

One man's forms have to meet. Al-Najāshī heads al-Ḥusayn b. Saʿīd as `الحسين بن سعيد`,
al-Fihrist as `الحسين بن سعيد بن حماد بن سعيد بن مهران الاهوازي`, Jāmiʿ al-Ruwāt as
`الحسين بن سعيد بن حماد`, and a pass that compares identical names never puts the three side by
side. The cross-form pass does. Its unit is a person holding an entry in one of the main Rijal
works, which give one entry per man. Each task is one name form plus everyone carrying it, either
whole or as the opening of a longer lineage or a name with a nisbah added, and agents partition
it against the quotations. Tasks are built per form, not per connected component. Joined
transitively, one contaminated alias chains unrelated men into tangles of forty. A single form
stays a question a reader can answer.

### Resolving names in chains

This is the harder half. Chains abbreviate: a short name, a kunyah alone, a pronoun (عنه، عن
أبيه). Books have conventions of their own — al-Kulaynī's «عدة من أصحابنا» stands for a fixed
group he names himself; al-Ṣadūq abbreviates chains in al-Faqīh and expands them in his
Mashyakha. And the same man is written differently from one book to the next.

So a mention is resolved in context: its text, the narrators on either side of it, and the
book's conventions, checked against the teachers and students each person is known to have.
A mention that cannot be resolved with confidence stays unresolved rather than being guessed.
Every resolution is recorded, and the chain's text is never altered.

### The research graph

- **Nodes** are people, with death year or generation where the sources give one.
- **Edges** are "A narrated from B" — one for each adjacent pair in a resolved chain, carrying
  the hadith, the book and the confidence — plus the teacher–student links the Rijal books
  state, with their source.
- **Export** as plain node and edge files alongside Elasticsearch, loadable into NetworkX,
  igraph, Neo4j or Gephi. Counting the time-ordered paths between two people, or finding who
  sits at the centre of transmission, becomes a standard query that can be restricted to
  high-confidence edges.
- **The graph checks identity too.** An edge where a student predates his teacher, or a chain
  that jumps a century, points to a wrong resolution.

### Roadmap

The order follows from the architecture: permanent identity first, because every later stage
produces decisions that must survive the stages after it; publication only once accuracy is
measured.

| Stage | What | Done when |
|---|---|---|
| ✓ | Output contract, merge rebuild, Layer 3 through sub-agents | 24,239 people; every agent decision validated (2026-09-13) |
| ✓ 1 | **Permanent identity and the decision record.** Key every source entry; record every existing decision against those keys — the rule merges, the 73 batches of agent answers, the automatic separations; derive people from the record; issue permanent person identifiers with redirects. | Done 2026-09-13. Rebuilding from the record reproduces all 24,239 people exactly; recording twice adds nothing; rebuilding twice changes no identifier. Agent answers now outlive the merge — stage 2's re-run is the first to rely on it. |
| ✓ 2 | **Fix the name-form defects** in one re-run: case-folded kunyahs, patronymic aliases, nisbahs belonging to other people. | Done 2026-09-14. Verdict clashes 155 → 151, kunyah clashes 317 → 312, invariant violations 69 → 66, no regression on the famous-narrator check; 1405's father and son are two people. Nisbah bleed was measured and left to the agent passes. |
| ✓ 3 | **Top up Layer 3, then reconcile name forms across people.** First the agent work stage 2 created: 486 name-group tasks covering 1,330 profiles, 484 of them names that are only a kunyah, and 369 pair deferrals no agent has judged. Then the [cross-form pass](#names-as-a-subsystem): every person holding a main-book entry, one task per name form they share, agents partitioning against the quotations. Last, the attach question: Khoei and Mamaqani profiles that hold no main entry, each paired with the main-entry people it may be. | Each of the twelve most-cited narrators is one person. Done 2026-09-14: 25,099 → 23,425 people, and every main-book entry of each of the twelve sits in one person. Ten profiles still bear one of their names: two are different men, five are fused profiles for stage 4, one is a confused heading, and two are places where agents disagree, which go to review. The corpus-wide split estimate moves to the stage 5 audit. Counting name extensions chains through short forms, whereas a sample of people measures the split directly. |
| ✓ 4 | **Split pass.** Agents review profiles that may fuse several men: the 17 strongest candidates, the people the stage 3 agents flagged as carrying another man's entry, and those with conflicting verdicts or impossible dates. Then come the entries Layer 0 joined across consecutive homonyms, split page by page. | All 17 and every flagged person resolved; the rest reviewed or queued. Done 2026-09-14: 371 people put to agents and 229 split; 127 Layer 0 entries repaired page by page, and the 68 people they belonged to asked again. Result: 23,879 people, verdict clashes 159 → 139, kunyah clashes 308 → 270. Queued: 14 low-confidence splits for review, and 87 single entries that each describe two men, which is an extraction defect for stage 6. |
| 5 | **Accuracy audit.** A random sample of applied merges, each checked against the sources, gives a measured accuracy with its margin — per rule layer, so the rules' acceptance thresholds are set from data. A random sample of people, each checked for other people who are the same man, measures how much splitting remains. Stage 2 showed an alias match three names deep accepted at a context score of 0. | The figure meets a publication threshold agreed beforehand — proposed at 95%. |
| 6 | **Complete the sources.** Re-extract Rijal al-Ṭūsī and Jāmiʿ al-Ruwāt, which were truncated. Re-extract, one man at a time, the entries the split pass found describing two men in one: 87 single entries, and the entries and pages marked mixed. An identity decision cannot divide a single extracted profile. | Each book's yield matches its known entry count; no entry is left marked mixed. |
| 7 | **Publish.** Restore the narrator index, service and API deleted in `9b6adb6`; write the narrator page; add `lookup_narrator` to the MCP connector. A person's display name is his anchor entry's own heading; the merge's longest-form rule gave Ibn Abī ʿUmayr the garbled «أبو أحمد بن محمد بن زياد الأزدي». Before any verdict is shown under a scholar's name, it is checked against the entry it came from. The split pass found 200 entries carrying another man's data, and one Khoei page credits al-Najāshī with praise of Sahl b. Ziyād that al-Najāshī's own entry contradicts. | Narrator pages live, on permanent identifiers; every published verdict traced to its entry. |
| 8 | **Resolve every chain.** Per-mention records linking each name in each chain to a person, following each book's conventions. | Coverage and confidence measured per book. |
| 9 | **Publish the research graph.** Node and edge files, documented, with a worked path query. | A researcher can count time-ordered paths between two people from the export alone. |

If the accuracy audit falls short, the plan returns through stages 2–4 before anything is
published.

---

## Appendix A — Current state

### Numbers

| Stage | Output |
|---|---|
| Extracted, Phase 1 | 42,076 entries from eight books |
| Contract-normalized | 42,045 (31 Infallibles removed) |
| Merged, rule layers 0–2 | 29,514 profiles (merge `29514:d382409476873fe5`) |
| People, after stage 4 | **23,879**, each with a permanent identifier — 23,425 after stage 3, 25,099 after stage 2 |
| Identity decisions on record | 62,834 on file across three merge runs, of which 19,392 in force |
| Review signals | 58, among them 27 agent answers that override another agent's |
| Drawing on more than one book | 19.9% — it falls when two people who each draw on several books become one |
| Held for human review | 421 low-confidence decisions |
| Most-cited narrators still split | 0 of 12 — each one's main-book entries sit in one person, and the split pass left them so |
| Fusions left | 87 single entries that each describe two men, for re-extraction (stage 6), and 14 low-confidence splits for review |

### Data

Under `tmp/`, which is symlinked to `/mnt/share/rewayaat-backup/tmp/`:

| Path | Contents |
|---|---|
| `narrators_book_{slug}.json` | Phase 1 extraction as produced — 42,076 entries |
| `narrators_normalized/{slug}.json` | contract-normalized — 42,046 |
| `narrators_merge/merged.json` | rule-layer merge — 29,514 profiles, fingerprint `29514:d382409476873fe5` |
| `narrators_merge/{name_group_tasks,deferred,quarantine,violations}.json` | Layer 3 inputs and invariant checks |
| `narrators_l3/runs/28687-a993d061519aaa64/batches/` | 73 Layer 3 batches — 32 group, 41 pair |
| `narrators_l3/runs/28687-a993d061519aaa64/outputs/` | the agents' decisions, one file per batch |
| `narrators_l3/runs/28687-a993d061519aaa64/merged_final.json` | Layer 3 output as applied — the reference `build_people.py` reproduces |
| `narrators_l3/runs/28687-a993d061519aaa64/review_queue.json` | 173 low-confidence decisions |
| `narrators_l3/runs/28687-a993d061519aaa64/auto_separate.json` | 432 deferrals kept separate without an agent |
| `narrators_l3/runs/29514-d382409476873fe5/` | the stage 3 top-up — 34 batches (7 group, 27 pair), the answers, and `record.log`, `build.log`, `measure.log` |
| `narrators_l3/runs/xform-24660-b23786d9461fb808/` | the cross-form pass — 24 batches over people, the answers, `candidates.json`, `split_candidates.json` (people the agents flagged as fused) and the run's logs |
| `narrators_l3/runs/attach-24079-68eb0515f81bbec5/` | the attach pass — 30 pair batches, the answers, `agent_flags.json` (fused subjects, right man not offered), `uncapped_tasks.json` (tasks made before the owners cap) and the run's logs |
| `narrators_l3/runs/split-23425-0f610e2eebc74002/` | the split pass — 23 batches of people as their entries, the answers, `candidates.json` (signals per person), `single_source_flags.json` (one-entry people carrying another man's data), `attribution_flags.json` and the run's logs |
| `narrators_l3/runs/entry-23824-5c84594d55ac0c60/` | entry repair — 3 batches of Layer 0 entries shown page by page, the answers and the run's logs |
| `narrators_l3/runs/resplit-23954-e7136f9c42d3628f/` | the re-split — 5 batches of people whose entries entry repair divided, asked again; each task names the decisions it supersedes |
| `narrators_l3/runs/orphan-23881-db55a0c4f4391b45/` | pages entry repair left standing alone, paired with the main-entry people they may be |
| `narrators_l3/archive/` | agent answers to earlier, superseded merges |
| `narrators_l3/runs/<fingerprint>/id_map.json` | each run's merged ids translated to source keys |
| `narrators_identity/decisions.jsonl` | **the decision record** — 62,834 decisions on source keys |
| `narrators_identity/person_ids.jsonl` | the permanent-identifier registry — 23,879 active |
| `narrators_identity/people.json` | **current** — 23,879 people, derived from the record |
| `narrators_identity/review_signals.json` | agent judgments now inside one person — 58 |
| `narrators_identity/distinct_conflicts.json` | unions refused, and agent answers that overrode another agent's |
| `narrators_archive/2026-09-14-pre-stage2/` | the merge, normalized files and people before stage 2 — its measurement baseline |
| `narrators_archive/2026-09-14-post-stage2/` | people, membership, decision record and registry before the top-up — its baseline |
| `narrators_archive/2026-09-14-post-topup/` | the same, before the cross-form pass — its baseline |
| `narrators_archive/2026-09-14-post-xform/` | the same, before the attach pass — its baseline |
| `narrators_archive/2026-09-14-post-attach/` | the same, before the split pass — its baseline |
| `narrators_archive/2026-09-14-post-split/` | the same, before entry repair — its baseline |
| `narrators_archive/2026-09-14-post-entry/` | the same, before the re-split — its baseline |
| `narrators_archive/2026-09-14-post-resplit/` | the same, before the orphan pages were attached — its baseline |
| `narrators_merged.json` | **superseded** — the June merge; do not use |

### Code

On `feature/narrators`:

| Script | Does |
|---|---|
| `scripts/narrators/narrator_schema.py` | normalizers, the reliability vocabulary, the Infallible registry |
| `scripts/narrators/normalize_extraction.py` | the output contract, applied retroactively |
| `scripts/narrators/merge_narrator_profiles.py` | Layers 0–2, invariants, Layer 3 task generation |
| `scripts/narrators/l3_prepare.py` | Layer 3 batches, carrying the source quotations as evidence |
| `scripts/narrators/l3_agent_prompt.md` | the sub-agent brief |
| `scripts/narrators/l3_dispatch.py` | progress, and prompts for unanswered batches |
| `scripts/narrators/l3_apply.py` | validates and applies decisions |
| `scripts/narrators/identity.py` | source keys, the decision record, people, permanent identifiers |
| `scripts/narrators/record_decisions.py` | records a merge's and a Layer 3 run's decisions on source keys |
| `scripts/narrators/build_people.py` | derives people and identifiers from the record |
| `scripts/narrators/crossform_prepare.py` | cross-form batches: people holding main-book entries, one task per shared name form; `--attach`, people without one paired with those they may be |
| `scripts/narrators/split_prepare.py` | split batches: signalled people as their entries; `--pages`, Layer 0 entries as their pages |
| `scripts/narrators/audit_narrator_quality.py` | per-book completeness audit |

```bash
python3 scripts/narrators/normalize_extraction.py --strict
python3 scripts/narrators/merge_narrator_profiles.py       # about 4 minutes
python3 scripts/narrators/l3_prepare.py                    # also writes the run's id_map.json
python3 scripts/narrators/l3_dispatch.py --next 8          # prompts to hand to sub-agents
python3 scripts/narrators/l3_apply.py --dry-run            # validates every answer
python3 scripts/narrators/record_decisions.py              # decisions into the record, on source keys
python3 scripts/narrators/build_people.py                  # people and permanent identifiers
python3 scripts/narrators/crossform_prepare.py             # cross-form batches, over those people
python3 scripts/narrators/record_decisions.py --no-rules --l3-run tmp/narrators_l3/runs/xform-<fp>
python3 scripts/narrators/build_people.py
python3 scripts/narrators/crossform_prepare.py --attach    # then record and build the same way
python3 scripts/narrators/split_prepare.py                 # the split pass, recorded the same way
python3 scripts/narrators/split_prepare.py --pages         # then Layer 0 entries, page by page
```

Sub-agents run at most 20 at a time. An agent stopped before it writes leaves no file, and
one stopped after leaves a complete file, so an interrupted run resumes cleanly. The pipeline's own files are written the same
way: every JSON output goes to a temporary file and is renamed over the old one, and the
append-only decision record and identifier registry trim a torn final line before appending.
This machine's memory is shared with other work, and the kernel has killed runs mid-step.

Recoverable from git:

| Component | Commit | Status |
|---|---|---|
| Narrator Java — `NarratorIndexManager`, `NarratorService`, `NarratorController`, `NarratorDocument`, `SourceAssessment`, `NarratorNameMatcher`, `ImamProphetRegistry` | `9b6adb6^` | to restore in stage 7 |
| June pipeline — `parse_duafa_narrators.py`, `parse_external_rijal.py`, `merge_narrator_profiles.py`, `merge_narrator_layer3.py` | `681d7f3` | extraction needed again for stage 6; the merge is superseded |
| `extract_chains_for_narrators.py` | `0f5a853` | superseded by `semantic_matn_source` |

`narrator.html` was never written. There is no `rewayaat_narrators` index, and nothing is wired
into the running application.

Chain resolution has a head start: `semantic_matn_source` holds chain-stripped Arabic on 32,516
of 32,519 hadith, and `HadithDisplaySegmenter` already separates chain from text.

---

## Appendix B — Design reference

### Rijal sources

**In the corpus:** Kitāb al-Ḍuʿafāʾ of Ibn al-Ghaḍāʾirī — 226 entries, already structured as
narrator biographies with verdicts, with both Arabic and English text.

**Downloaded from the source texts.** The other seven books exist as digitized Arabic text
online. The pages are downloaded and Claude parses the real text; nothing relies on the
model's memory for content.

| Book | Source | Pages | URL |
|------|--------|-------|-----|
| Muʿjam Rijāl al-Ḥadīth (Khoei) | usul.ai | 10,924 | `usul.ai/ar/t/mucjam-rijal` |
| Tanqīḥ al-Maqāl (Mamaqani) | eshia.ir | 34 vols | `ar.lib.eshia.ir/10510` |
| Rijāl al-Kashshī | usul.ai | 94+ | `usul.ai/ar/t/rijal-al-kashshi-maa-taliqat-al-mirdamad` |
| Rijāl al-Najāshī | usul.ai | 461 | `usul.ai/ar/t/rijal-2` |
| Rijāl al-Ṭūsī | usul.ai | 417 | `usul.ai/ar/t/rijal-3` |
| Fihrist al-Ṭūsī | usul.ai | 253 | `usul.ai/ar/t/fihrist-2` |
| Jāmiʿ al-Ruwāt (Ardabili) | usul.ai | 1,210 | `usul.ai/ar/t/jami-al-ruwat-li-muhammad-ali-al-urdubili` |

Pages are sent in batches, and each profile's `assessment_ar` is a verbatim quotation from the
page, not a paraphrase.

### Name storage

In hadith text the same narrator appears in many forms — `محمد بن علي بن الحسين بن موسى ابن
بابويه القمي` in Arabic, `` Abu Ja`far Muhammad b. `Ali b. al-Husayn b. Musa b. Babuwayh
al-Qummi `` in the corpus English — and the Rijal books vary the order, the depth of lineage,
the titles and the transliteration. A narrator record captures every variant:

```
primary_arabic_name    → full name as the most authoritative source gives it
primary_english_name   → one transliteration convention, applied consistently
arabic_aliases[]       → every variant: short forms, spellings, depths of lineage
english_aliases[]      → every English variant and transliteration scheme
kunyah_arabic/english  → e.g. أبو جعفر / Abu Ja`far
titles[]               → nisbahs and laqabs, e.g. القمي، الرازي
normalized_arabic      → matching key: diacritics stripped, alef/ya/ta marbuta folded
normalized_english     → matching key: diacritics and ʿayn/hamza stripped, lowercased
```

The normalized keys are **computed, never extracted** — by `narrator_schema.py`, ported from
the deleted `NarratorNameMatcher` — so the same name always yields the same key.

Aliases are **provenanced**: each carries the book and page it came from, so a bad alias can be
traced to its source and a merge undone.

**Diacritics.** Display forms keep diacritics as each source gives them. The Arabic key strips
tashkeel (U+064B–U+065F, U+0670, U+06D6–U+06ED) and folds أ إ آ → ا, ى → ي, ة → ه. The English
display form follows the corpus convention (backtick for ʿayn) and keeps IJMES, EI2 and DMG
variants as aliases; its key is NFKD-decomposed, stripped of combining marks and ʿ ʾ ʻ ',
and lowercased.

**Arabic–English bridging.** A reader clicking `` `Ali b. Ahmad al-Daqqaq `` in the English view
must reach a profile built from Arabic sources. Every profile therefore carries English forms:
Kitāb al-Ḍuʿafāʾ pairs its own Arabic and English; sub-agents transliterate the Arabic-only
books; chain resolution adds the English forms the corpus actually uses. At click time the
text is normalized and looked up across English names and aliases, with a Jaro–Winkler
fallback at 0.85.

### Name classes are not interchangeable

| Class | Examples | Identifies a person? |
|---|---|---|
| Names — `primary_arabic_name`, `arabic_aliases[]` | `محمد بن سنان`, `محمد بن أورمة` | yes, weakly for short forms |
| Kunyahs — `kunyah_arabic` | `أبو جعفر`, `أبو عبد الله` | no — hundreds share each |
| Titles and nisbahs — `titles[]` | `القمي`, `الكوفي`, `البجلي`, `الصفار` | no — places, tribes, trades |

Kunyahs and nisbahs are **disambiguators, not identifiers**. They inform the context score and
never enter the name index that proposes merges.

**The rule is about string shape, not the field a string arrived in.** Extractors put kunyahs
and bare nisbahs inside `arabic_aliases` as well, so excluding the fields alone lets them back
in through the alias list. An alias counts as an identifier only with **two or more identifying
tokens** after the connectors are discarded (بن، ابن، أبو، أم، عبد، مولى). A phrase built on a
generic kunyah — one of the Imams' kunyahs or the everyday ones — needs two identifying tokens
*after* the kunyah: `أبو الحسن القزويني` is not an identifier, while `أبو ذر الغفاري` and
`أبو هاشم الجعفري`, whose kunyahs each name one man, are. English has its own connector list:
`abu muhammad` is two words and no more identifying than `أبو محمد`.

Such forms are still stored and displayed; they simply cannot be the reason two profiles merge.

**Editorial shorthand is not a name.** Mamaqani refers to the person under discussion as المترجم,
المعنون, صاحب الترجمة, الرجل. These are dropped at the contract stage.

**Kunyahs are compared case-folded.** Sources inflect kunyahs by case — «يكنى أبا جعفر», «عن
أبي جعفر» — so أبا and أبي fold to أبو before any comparison, and the generic-kunyah rule sees
the folded form. أبي followed by بن is the name Ubayy (أبي بن كعب) and is left alone. Truncated
kunyahs — a lone ا, a bare أبو — are dropped at the contract stage.

**Relatives are not aliases.** An entry opens with its subject's lineage and names his sons
and transmitters, so an extractor's alias list mixes the man's own names with his relatives'.
Indexed as aliases, a relative's name merges the relative into him — merged_id 1405 fused a
father and son because the father's Najashi entry, which names the son who transmitted his
book, listed the son among the father's aliases. Two relations are recognised and moved to
`relative_names`, kept for lineage and display, never indexed:

- *ancestor* — the alias is the start of what follows a بن in the subject's name:
  «أحمد بن عامر» on «عبد الله بن أحمد بن عامر»;
- *descendant* — «X بن» followed by the start of the subject's own name, at least two
  identifying names deep: «عبد الله بن أحمد بن عامر» on «أحمد بن عامر بن سليمان».

Neither applies when the alias's own first name is among the names that open the subject's,
ignoring the article — a man who shares his grandfather's name («علي بن محمد بن علي الخزاز»
→ «علي الخزاز»), or a heading that begins with titles, would otherwise have his own name read
as a relative's.

Siblings are measured and not detected. A different first name over the same lineage flagged
494 aliases, and they were mostly variant readings of the man's own name — الحسن and الحسين
بن عقيل, سليمان and سلمان, جيفر and جفير — recorded from the sources' notes on other copies.
Treating them as brothers would discard his own names. Brother-aliases remain a known risk
for the agent passes.

**Nisbah bleed is not handled by rule.** The nisbah of a man named in someone else's entry can
attach to its subject: Sahl b. Ziyād carries الأشعري, his accuser's. Two rules were measured and
neither is precise enough to ship. Requiring a title to appear in the subject's own name forms
or entry heading would demote 20.8% of all titles, nearly all correctly attributed — the
verdict quotation omits most of an entry, so absence from it proves nothing. Requiring every
occurrence to follow another man's name flagged 133 titles, most of them the subject's own long
lineage. Bleed is rare, under 0.5% of titles, and a title alone adds +2 to a context score, less
than a merge needs, so its harm is mostly on display. Each nisbah is shown with the book it came
from, and the agent passes of stages 3–4, which read the text, correct the rest.

### Extraction output contract

The extractor is a language model, so its output is validated, not trusted:

- **Closed key set.** Unknown keys fail the batch. Near-miss keys (`is_doubtual`, `kunyah_ar`,
  `city_or_ribe`) are the signature of an unvalidated pipeline.
- **Reliability is a controlled vocabulary on its own axis.**

  | Grade | Arabic | Meaning |
  |---|---|---|
  | `thiqa` | ثقة | reliable |
  | `saduq` | صدوق | truthful |
  | `hasan` | حسن | good |
  | `qawi` | قوي | strong |
  | `mukhtalaf_fih` | مختلف فيه | the source records disagreement |
  | `majhul` | مجهول | the source says he is unknown |
  | `muhmal` | مهمل | named without comment |
  | `daif` | ضعيف | weak |
  | `very_weak` | ضعيف جدا | weak, with an intensifier |
  | `kadhdhab` | كذاب | liar, fabricator |
  | `not_assessed` | — | mentioned, no verdict given |
  | `non_existent` | — | the source denies he existed |

  Doctrinal charges sit on a separate axis, because sources state one without the other:
  `ghali`, `waqifi`, `fathi`, `zaydi`, `nasibi`, `batri`, `mulhid`, `fasid_al_madhhab`.
  `not_assessed` and `majhul` are different facts. A grade meaning only "an assessment exists"
  is not permitted.
- **Latin in an Arabic field, or Arabic in a key name, fails the batch.**
- **Disambiguation pages are not narrators.** Khoei and Mamaqani each head a page listing
  everyone called حفص; parsed naively it becomes one profile whose 89 "aliases" are 89 people.
  A one-token name with eight or more aliases is quarantined.
- **Verbatim quotation check.** `assessment_ar` must appear in the downloaded page text. Required,
  not yet enforced: it needs the page text kept alongside each profile.

### Identity resolution

Different books, and chains, refer to one narrator in different ways — by depth of lineage
(`الحسن بن علي بن أبي حمزة` against `الحسن بن علي`), by nisbah instead of name (`البرقي`
against `أحمد بن محمد بن خالد`), by kunyah alone, by transliteration — while genuinely different
men share names. Resolution is layered, and each layer fails toward *separate*.

**Layer 0 — intra-book fragments.** A long entry spans several page batches, and extraction
emits one profile per batch. Fragments are adjacent in the file and contiguous in pages;
only those are collapsed. Khoei and Mamaqani were extracted per *mention* — a prolific
narrator named inside another man's entry got a profile of his own — so a repeated name
within a book is not assumed to be a fragment.

**Layer 1 — exact names.** Candidates come from the name index only (see the rule above). A
single candidate is not enough to merge. An exact match on the primary name merges only when
few profiles corpus-wide share that name — six or fewer — and nothing conflicts; larger
same-name groups go to Layer 3 whole. So does an exact match on a primary name that is only a
kunyah — `أبي بصير` names several men as surely as a large group does; the name-class rule
applies to primary names as much as to aliases. A match through an alias needs a name at least three
identifying tokens deep, or supporting context. Every absorbed alias is indexed, so a merge
without these guards would chain: A absorbs B's aliases, C matches one of them, C joins A,
and the cluster grows without any two members having been compared.

**Layer 2 — context.** Where a name is ambiguous, kunyah, nisbah, city, generation, death year
and shared teachers or students are scored. Death years more than a generation apart
disqualify outright. A clear winner must lead the runner-up by a margin; otherwise the case is
deferred.

**Layer 3 — sub-agents reading the sources.** Two kinds of task, each carrying the source
quotations as evidence:

- *Group* — one name held by many profiles: partition them into people. Kunyah is absent on
  82–92% of same-name profiles, so pairwise questions would carry no evidence; the group as a
  whole usually does. 515 group tasks replaced over 11,000 pairwise ones.
- *Pair* — a profile matched a candidate through an alias or partial name and context was
  inconclusive: the same person, or nobody?
- *Cross-form* — people who each hold an entry in a main Rijal work and share a name form,
  whole or as the opening of a longer one: partition them. Built over people rather than
  merged profiles (`crossform_prepare.py`), because one man headed differently by different
  books lands in different name groups and no group task ever compares him with himself. A form
  held by more than twelve such people is too common to be a question; people agents have
  already judged together are not asked again.
- *Attach* — a person drawn only from Khoei or Mamaqani, holding no main entry, who carries
  a main-entry person's name form and agrees with him on a kunyah or nisbah: the same as one
  of up to three such people, or none? It is a pair task because the answer carries a
  confidence. The same twelve-person cap on name forms applies. It is not asked of a
  single-source profile with no verdict, kunyah or nisbah, which gives an agent nothing to
  decide on.

Deferrals where no candidate carries any positive evidence are kept separate without an agent;
asking for a judgment on absent evidence invites a confident wrong merge. Low-confidence
answers go to review rather than being applied. Every answer is validated before it is
applied — a partition must cover its task exactly once, and a merge target must be one the
task offered.

Batches are versioned by a fingerprint of the merge that produced them, and each merge gets its
own immutable run directory; answers are never mixed across merges. Answers name merged ids,
so each run also stores `id_map.json`, translating them to source keys, and
`record_decisions.py` writes them into the decision record, where a later merge cannot discard
them.

The pair format has a known limit: when a task's candidates include two duplicates of the same
man, the agent can name only one.

**Layer 4 — human review.** Low-confidence Layer 3 answers, and cases the agents flag.

**Splitting — agents taking people apart.** Every layer above can join, and this one takes
apart. A person of two or more entries goes to an agent when any of these signals points at him:

- an agent in an earlier run said one of his profiles mixes men
- an agent's separation now lies inside him
- he carries a positive and a negative verdict together with two kunyahs
- the Imams he is said to be a companion of lie more than five apart

The agent sees him as whole entries and partitions them, moving an entry out only on positive
evidence of a different man. An entry that itself mixes two men is set apart on its own.

The answer is recorded as two decisions: a partition, which keeps each cluster together, and a
`distinct` between clusters. `distinct` is the only separation that holds against an agent's
union, which matters because some fusions were agents' own. Both decisions bind every source key
of each entry, so no entry is cut in half. Only high- and medium-confidence answers apply.

A second pass repairs Layer 0 itself. Khoei and Mamaqani head consecutive entries for men of one
name, and Layer 0, which joins adjacent pages as one entry, joined some of those entries. Such
an entry goes to an agent page by page (`split_prepare.py --pages`), and the agent's split
outranks the rule that joined the pages.

**Invariants**, checked after every merge:

- Within a book extracted one profile per headed entry (Ḍuʿafāʾ, Kashshī, Ṭūsī, Fihrist,
  Najāshī, Ardabīlī), two headed entries are two people: a merged profile's aliases must not
  include the primary name of a different profile from the same book. Khoei and Mamaqani were
  extracted per mention, so the check does not apply to them, and a prolific narrator
  legitimately draws dozens of entries from them.
- Growth is capped on distinct primary-name forms, not on sources: a profile that would take
  more than eight distinct forms diverts the newcomer to review.
- Every merge records its layer, matched key and context score.

**Why aliases carry weight.** The narrator service builds its hadith search from every name
variant on a profile, so each alias is a search term fired at the corpus. A foreign name in the
alias list returns another man's narrations under the wrong biography.

### The decision record and permanent identifiers

Built in stage 1 (`scripts/narrators/identity.py`). Every identity decision — by rule, agent or
reviewer — is a line in `tmp/narrators_identity/decisions.jsonl`, keyed on source keys
(`book:index`, an entry's position in its Phase 1 extraction file, which is never rewritten)
and identified by a hash of its content, so recording it twice adds nothing.

| Kind | Meaning | Effect on people |
|---|---|---|
| `same` | these sources are one person | joins them |
| `partition` | a Layer 3 group task: each group is one person | joins within groups; across them, binds rules only |
| `not_same` | judged not shown to be the same | binds rules only; reported if an agent or reviewer joins them |
| `distinct` | positively different people | refuses a `same` from an actor of equal or lower rank |
| `exclude` | not a narrator | removes the sources |

An agent's separation — `not_same`, or different groups of one partition — binds rules and
nothing else. It is the agent's reading of the sources, and a rule ranks below an agent. The
stage 2 re-merge showed why this matters: of 71 rule merges that joined people the previous
build kept apart, 11 went against an agent's considered separation, most of them names that
are only a kunyah (أبي بصير, أبي عبيدة) whose earlier protection had been an accidental kunyah
conflict the case-folding removed. Against another agent or a reviewer the separation stays
weak, because "not the same", or a profile left on its own, often means only "not shown to be
the same" — stage 3's cross-form agents must remain free to join them. `distinct` is hard: it
binds every actor of equal or lower rank, and is reserved for reviewers and the split pass.
Actors rank reviewer over agent over rule.

Rule decisions belong to one merge run and are replaced wholesale when the merge is re-run;
agent and reviewer decisions are judgments about sources and stand across runs. People are the
connected components of the decisions in force. `build_people.py` computes them, assembles each
person's profile from his source entries, and lists any `not_same` judgment that now falls
inside one person as a review signal.

Every person has a permanent identifier (`n000001` onward) in `person_ids.jsonl`, an
append-only registry. Each identifier is anchored on one source — an entry heading from a book
extracted one profile per entry, Najāshī first — because if a person is split, the part holding
the anchor keeps the identifier, and a heading is the source least likely to be split away.
When people merge, the younger identifiers redirect to the oldest; when a person splits, the
rest is minted anew with a note of where it came from. No identifier is reused or dropped.

Answers to the two merges superseded on 2026-09-07 predate `id_map.json`; their batches were
overwritten before the run layout existed, so they could not be translated.

### Pipeline phases

**Phase 1 — extraction, per book.** Each book is extracted on its own, with no cross-book
matching. Kitāb al-Ḍuʿafāʾ is parsed from the corpus; the other books from downloaded pages,
validated against the contract. Batch size is set per book: dense pages of bare names overflow
the model's output and it truncates silently, so a yield far below a book's known entry count
means the book must be re-run smaller.

**Phase 2 — merge.** Normalize; Layer 0; Layers 1–2; Layer 3 through sub-agents; apply; check
the invariants. The rule merge writes `tmp/narrators_merge/merged.json`; Layer 3 writes
`merged_final.json` in its run directory.

**Phase 3 — Elasticsearch.** `NarratorIndexManager` creates `rewayaat_narrators` (index name
overridable through `NARRATOR_INDEX`) and bulk-indexes the people.

**Phase 4 — narrator page.** `narrator.html`, served by `NarratorController` at `/narrator/{id}`,
loading `/v1/narrators/{id}` and listing narrations from `/v1/narrators/{id}/narrations`, with
per-source verdicts shown as quotation and summary.

**Phase 5 — chains.** First, name-variant search: `NarratorService.searchHadithsByNarrator`
queries the corpus with a person's name forms, needing no backfill and exactly as precise as the
alias list. Then per-mention resolution as described in Part III, writing narrator links onto
hadith and making names clickable.

---

## Appendix C — Findings, as measured

### The June merge, audited (2026-09-07)

The per-book extraction was broadly sound; the merge was not.

**The LLM layer never finished.** 11,149 cases were deferred and 3,976 judged (36%). The rest
were added as new profiles and left, so the merged file carried thousands of unresolved
duplicates. That layer also ran through the Anthropic API, against the no-external-API
principle.

**It over-clustered through title and kunyah keys.** Titles and nisbahs went into the name
index, candidates were probed by kunyah, and a single candidate merged unchecked:

| Cluster size | Merged profiles | Source entries absorbed |
|---|---|---|
| ≥2 sources | 5,505 | 18,274 (43.4%) |
| ≥5 sources | 683 | 6,675 (**15.9%**) |
| ≥10 sources | 180 | 3,579 (8.5%) |
| ≥20 sources | 43 | 1,847 (4.4%) |
| largest | 1 | 195 |

The largest, `محمد بن سنان`, carried 129 aliases including `محمد بن أورمة`, `أحمد بن هلال
العبرتائي`, `مؤمن الطاق` and `محمد بن الحسن بن شمون` — ten of them the primary names of *other*
Ḍuʿafāʾ entries. Another fused `أحمد بن محمد بن عيسى الأشعري`, `محمد بن يحيى العطار` and
`الصفار`. The small bilingual books suffered most, because they were processed first and seeded
the index: 57.7% of Ḍuʿafāʾ and 43.5% of Kashshī entries landed in a cluster of five or more.

**It was also too conservative across books.** Only 11.7% of profiles drew on more than one
book, although Khoei alone should cover nearly every narrator in Najashi, Tusi and Kashshi.

**Khoei and Mamaqani were extracted per mention.** 21,938 Khoei profiles across 14,795 distinct
names; سهل بن زياد appears at page spans 381, 671, 3961 and 7011 of a book that gives him one
entry. Only 780 Khoei and 293 Mamaqani profiles are true batch fragments.

**Two books are effectively missing.** Rijal al-Tusi yielded 123 profiles from 417 pages against
roughly 8,000 entries; Jāmiʿ al-Ruwāt 1,796 from 1,210 pages. Both are output truncation.

**The contract was not enforced.** 289 invented keys across 42,076 profiles (0.7%) —
`is_doubtual`, `is_doubtous`, `kunyah_ar`, `kunyah_English`, `city_or_ribe`, and `death_year_hijري`,
an identifier with Arabic letters in it. Each was invisible to the merge. Verdicts took 88
free-text spellings, with `assessed` — 10,190 of them — asserting only that an assessment exists.

**The strongest disambiguator is rare.** `death_year_hijri` is filled on 2.8% of Khoei and 9.6%
of Mamaqani profiles.

### The rebuild (2026-09-07)

From the same extraction, nothing re-downloaded:

| | June merge | Rebuilt |
|---|---|---|
| Profiles | 29,305 | 28,687 |
| Largest cluster | 195 source entries | 15 |
| Clusters of 20+ | 43 | 0 |
| Absorbed into clusters of 5+ | 15.9% | 10.5% |
| Drawing on more than one book | 11.7% | 16.6% |
| Unresolved, for Layer 3 | 11,149 pairwise, 36% judged | 515 group tasks + 1,441 pair tasks |

Less over-merging and more genuine cross-book merging at the same time.

The agents surfaced further defects, each checked against the data before it was fixed. Kunyahs
and nisbahs still reached the index through alias lists — `الكوفي` had linked nine unrelated
narrators, `أبو العباس` pulled Ibn ʿUqda into another man's profile; English kunyahs passed a
word count; a generic kunyah plus one nisbah passed the token rule; and an Imam, al-Ḥasan
al-ʿAskarī, reached the data because the honorific pattern lacked `صلوات الله علي`. One agent
diagnosed a merge fault as an extraction fault; the per-book files were clean. The agents find
real problems, and where they locate them still needs checking.

### Layer 3 (2026-09-13)

73 batches answered by sub-agents, run `28687-a993d061519aaa64`; zero validation errors, zero
tasks unanswered.

| | Before Layer 3 | After |
|---|---|---|
| Profiles | 28,687 | 24,239 |
| Drawing on more than one book | 16.6% | 21.2% |
| Largest cluster | 15 | 56, one name form (محمد بن سنان) |
| Group merges applied | — | 3,533 across 2,922 clusters |
| Pair merges applied | — | 915, with 353 kept separate |
| Low confidence, for review | — | 173 |
| Kept separate without an agent | — | 432 |

The largest clusters reunite prolific narrators whose Khoei and Mamaqani mentions were
extracted one profile each — Ibrāhīm b. Hāshim, al-Ḥusayn b. Saʿīd, Ibn Abī ʿUmayr — under one
name form, with verdicts matching the scholarship.

**Verdict clashes rose from 98 to 143, mostly correctly.** Of the 68 involving a Layer 3 merge,
60 take their conflicting verdicts from different books: narrators the scholars genuinely
dispute, now reunited — al-Nahdī and Ḥamdān al-Qalānisī, whom Kashshī identifies outright;
Jaʿfar b. Muhammad b. Mālik al-Fazārī; Ibrāhīm b. Isḥāq al-Aḥmarī. Per-source attribution is
what makes that safe to publish. A few are contested identity and belong in review.

**Layer 3 cannot split.** On the rule merge, of 6,705 multi-source profiles, 98 carry both a
positive and a negative verdict and 17 of those also carry conflicting kunyahs; 328 of the 379
merge steps behind them were Layer 1 exact or full-name matches on thin profiles. The worst:
merged_id 1008 joins Najashi's reliable ʿAmr b. Ḥurayth al-Ṣayrafī to the Companion of the
same name; 1405 joins a father and son through the son's alias list. The verdict-clash count is
a lower bound — 1008 is a conflict of generation, not verdict. Layer 3 added nothing to either;
nothing undoes them.

**Kunyahs are not case-folded.** 323 of 10,009 kunyah values are accusative or genitive (أبا،
أبي) and 119 are truncated junk. That inflates kunyah clashes by about a quarter (390 → 288
folded), produced 46 false conflict penalties among 203 in Layer 2 — erring toward keeping
profiles apart — and let 48 accusative aliases past the generic-kunyah rule, through which two
merges ran.

**One man across several profiles.** Layer 3 compares profiles with an identical normalized
name, so a man's different name forms never meet. Sahl b. Ziyād al-Ādamī al-Rāzī: 41 sources
under `سهل بن زياد`; Najashi's own entry, with its decisive «ضعيفا في الحديث غير معتمد عليه»,
under `سهل بن زياد الآدمي`; Tusi's Fihrist under `سهل بن زياد الادمي الرازي`. His profile also
carries الأشعري, the nisbah of his accuser named in his entry, and الآملي, which belongs to a
different man. Anchored on each narrator's own nisbah or kunyah:

| Narrator | Profiles that are him | Sources per profile |
|---|---|---|
| Aḥmad b. Muhammad b. ʿĪsā al-Ashʿarī | 7 | 46, 5, 4, 3, 2, 2 |
| Sahl b. Ziyād | 4 | 41, 7, 3, 2 |
| al-Ḥusayn b. Saʿīd | 4 | 50, 4, 3, 2 |
| Ibn Abī ʿUmayr | at least 2 | 45 as `ابن أبي عمير`, 37 as `محمد بن أبي عمير` |
| Yūnus b. ʿAbd al-Raḥmān | 3 | 24, 14, 1 |
| Muhammad b. Muslim | 3 | 36, 3, 2 |
| Ibrāhīm b. Hāshim | 3 | 56, 4, 2 |
| al-Faḍl b. Shādhān | 2 | 37, 3 |
| Zurāra b. Aʿyan | 2 | 45, 2 |
| Ṣafwān b. Yaḥyā; Jamīl b. Darrāj | 1 | unified |

A corpus-wide count is not reliable yet: a name-extension test chains through ambiguous short
forms — `الحسن بن علي` links Ibn Faḍḍāl to al-Washshāʾ — and gives only an upper bound of about
1,400. Stage 3 compares whole profiles instead; profile 62 already lists `سهل بن زياد الآدمي`
among its own aliases, the link that was never tried.

### Stage 2 (2026-09-14)

One merge re-run with the name-form fixes, measured against the build before it — both sides
from `people.json`, so Layer 0 fragments count the same way.

| | Before stage 2 | After |
|---|---|---|
| People | 24,239 | 25,099 |
| Verdict clashes | 155 | 151 |
| Kunyah clashes, case-folded | 317 | 312 |
| Invariant violations | 69 | 66 |
| Agent separations inside one person | — | 0 of 1,215 |
| Drawing on more than one book | 21.2% | 19.5% |

**The targeted defects are fixed.** Exact-name deferrals blocked by a kunyah conflict fell from
44 to 25 — most had been two sources inflecting one kunyah differently. 195 Arabic and 186
English aliases were a relative's, and moved out of the alias lists; merged_id 1405's father
and son are now two people. Nothing regressed on the famous-narrator check, and agent answers
survived three merge re-runs, still joining 4,415 profiles.

**The first re-run exposed two things the stated tests would not have caught.** Removing the
accidental blockers moved cases from "deferred to the agents" to "decided by rule": 71 rule
merges joined people the previous build kept apart, and 11 of those went against an agent's
considered separation — mostly names that are only a kunyah, أبي بصير, whose protection had been
a kunyah conflict the case-folding removed. Hence an agent's separation now binds the rules,
and kunyah-only names go to the agents. The ancestor rule also needed widening twice: it first
missed the start of an ancestor's name, and then missed the reverse — the father's entry
listing the son, which was 1405's actual mechanism.

**What remains.** Holding kunyah-only names back raised people by 860 and lowered cross-book
linkage to 19.5% until the top-up Layer 3 run partitions them (stage 3). The rule layer is
still lenient — an alias match three names deep is accepted at context score 0 — and English
transliteration defeats the relatives rule where Arabic does not: `abdullah ibn ajlan` merged
قيس بن عبد الله بن عجلان into his father because the English forms spelled the lineage
differently. Thresholds are for the stage 5 audit to set from data. merged_id 1008 is still
fused; that is the split pass's.

**A run was killed for memory mid-merge.** Nothing was lost only because the kill landed before
the write; every file is now written durably.

### Stage 3 top-up (2026-09-14)

Sub-agents answered the 34 batches stage 2 created (run `29514-d382409476873fe5`) with zero
validation errors. The results below are measured against the post-stage-2 build.

| | After stage 2 | After the top-up |
|---|---|---|
| People | 25,099 | 24,660 |
| Drawing on more than one book | 19.5% | 20.4% |
| Verdict clashes | 151 | 154 |
| Kunyah clashes, case-folded | 312 | 318 |
| Group tasks with a merge | — | 280 of 488 |
| Pair merges applied | — | 546, with 96 kept separate |
| Low confidence, for review | — | 116 |
| Kept separate without an agent | — | 952 |

The kunyah-only groups mostly stayed apart, as the brief asks. Agents joined their members
only on a shared teacher, a quotation or a full name the source gives. Cross-book linkage
recovered less than the 1.7 points stage 2 gave up, because the profiles it held back were names
that are only a kunyah, and most of those are rightly separate.

**The new verdict clashes are mostly disputed men.** Sahl b. Ziyād, Saʿd b. Ṭarīf, Abū
al-Jārūd and al-Ḥārith al-Aʿwar lead the 14. The scholars genuinely dispute these narrators,
so a thiqa beside a ḍaʿīf is the record, not a fusion.

**17 agent answers override another agent's.** An agent's separation binds only rule unions, so
where two agents disagree the merge stands and the build reports it. Most of the overriding
answers rest on a source naming the man outright, such as Khoei's «هو إبراهيم بن عيسى أبو أيوب»
and «هو أحمد بن محمد بن أبي نصر المتقدم». A few are genuine disagreements (Ismāʿīl al-Juʿfī,
Zakkār). All of them go to review.

**It did not touch the famous narrators, and could not.** All twelve checked stand exactly as
before, and Sahl shows why. Agents had seen 17 of his 19 people, but his own entries —
al-Najāshī 519, al-Fihrist 388, al-Ḍuʿafāʾ 62 — sat in three people that no agent had put side
by side. Each book heads him in a different form, and Layer 3 groups by exact name. Others
follow the same pattern:

- al-Ḥusayn b. Saʿīd: al-Najāshī, al-Fihrist and Jāmiʿ al-Ruwāt, in three people.
- Ibn Abī ʿUmayr: three people.
- Zurāra: Jāmiʿ al-Ruwāt's entry, headed with his full lineage, stood apart from the other
  three books.

A first cross-form candidate list missed all of these, for two reasons:

1. It skipped pairs whose primary names overlapped, on the assumption that Layer 3 had
   compared them.
2. It dropped name forms held by more than four people, which covers every form of a prolific
   narrator.

The cross-form pass is built on that diagnosis, and every famous case falls whole inside one of
its tasks.

Sahl's 15 single-mention Khoei profiles are a separate and lesser problem. Most carry a name
and at most one teacher, so there is nothing an agent can decide on.

### Cross-form pass (2026-09-14)

Run `xform-24660-b23786d9461fb808`: 709 tasks in 24 batches. All answers were validated, and
476 tasks merged something. The results below are measured against the post-top-up build.

| | After the top-up | After the cross-form pass |
|---|---|---|
| People | 24,660 | 24,079 |
| Drawing on more than one book | 20.4% | 19.7% |
| Verdict clashes | 154 | 157 |
| Kunyah clashes, case-folded | 318 | 316 |
| Largest person | 57 sources | 88 — Ibn Abī ʿUmayr, 62 thiqa verdicts and no negative one |
| Agent answers overriding another agent's | 17 | 24 |

**The famous narrators' main entries have met.** Before the pass, several of the twelve had
their own entries in al-Najāshī, al-Fihrist, al-Kashshī and Jāmiʿ al-Ruwāt spread across
people. For every one of them, those entries now sit in one person:

| Narrator | People holding his main entries, before → after |
|---|---|
| Sahl b. Ziyād | 3 → 1 |
| al-Ḥusayn b. Saʿīd | 3 → 1 |
| Ibn Abī ʿUmayr | 3 → 1 |
| Zurāra b. Aʿyan | 2 → 1 |
| Yūnus b. ʿAbd al-Raḥmān | 2 → 1 |
| al-Faḍl b. Shādhān | 2 → 1 |
| Aḥmad b. Muhammad b. ʿĪsā | 2 → 1 |
| Muhammad b. Muslim, Ibrāhīm b. Hāshim, Ṣafwān, Jamīl | already 1 |

Cross-book linkage fell because it counts people, and when two people who each drew on
several books become one, there is one fewer of them.

**The verdict clashes it created are disputes, not fusions.** Counting by source set, 54
clashes are new, but most are people who already clashed and gained sources. Only five were
formed by joining people none of whom clashed before. In all five, Ibn al-Ghaḍāʾirī's ḍaʿīf in
al-Ḍuʿafāʾ stands against al-Najāshī's or Khoei's thiqa, the best-known disagreement in the
field.

**Seven cross-form answers overrode an earlier agent.** Five had the stronger evidence, the
main entries themselves: Yūnus, ʿAlī b. Asbāṭ, Ḥamdawayh, Masʿada b. Ṣadaqa, Ḥujr b. Zāʾida.
Two joined people through material the earlier agent had called contamination:

- al-Ḥusayn b. Abī al-ʿAlāʾ with al-Ḥusayn b. Khālid, who carries al-Ṣayrafī.
- Ṣāliḥ b. Khālid al-Maḥāmilī with al-Qammāṭ, whom al-Najāshī heads separately.

Both are review signals and the first cases for the split pass. The agents flagged 87 more
people as carrying another man's entry, collected in `split_candidates.json`. Person 323
(Thaʿlaba, Ismāʿīl and ʿAnbasa fused) came up in three batches, and al-ʿAllāma al-Ḥillī's
material has leaked into several.

**What the pass could not reach.** Its unit is a person holding a main entry, so a
well-described Khoei or Mamaqani profile of a famous man, holding none, stayed outside. The
clearest cases:

- «إبراهيم بن هاشم أبو إسحاق القمي», whose student is his son ʿAlī.
- «أحمد بن محمد بن عيسى الأشعري», Abū Jaʿfar al-Qummī.

The same check correctly left others apart: Suhayl b. Ziyād al-Wāsiṭī, Muhammad b. Muslim
al-Ṭāʾifī, and a Muhammad b. Muslim profile fused with al-Zuhrī. Those Khoei and Mamaqani
profiles are the attach question.

### Attach pass (2026-09-14)

Run `attach-24079-68eb0515f81bbec5` had 1,077 pair tasks in 30 batches, and every answer was
validated. Of the answers, 654 merges were applied (496 high, 158 medium) and 116 low-confidence
merges went to review. The pass is measured against the post-cross-form build.

| | After the cross-form pass | After the attach pass |
|---|---|---|
| People | 24,079 | 23,425 |
| Drawing on more than one book | 19.7% | 20.2% |
| Verdict clashes | 157 | 159 |
| Kunyah clashes, case-folded | 316 | 308 |
| Agent answers overriding another agent's | 24 | 33 |

Most joins rest on Khoei or Mamaqani quoting the main entry word for word, or pointing to it:
«هو أحمد بن محمد بن سعيد بن عقدة الآتي», «تقدمت ترجمته بعنوان …». Ibrāhīm b. Hāshim's «أبو إسحاق
القمي» profile, whose student is his son ʿAlī, joined him (56 → 60 sources), and Ṣafwān b.
Yaḥyā absorbed 27 Khoei and Mamaqani profiles. Only three verdict clashes were formed by joining
people who did not clash before. All three set al-Najāshī's thiqa against Khoei's or Ibn
al-Ghaḍāʾirī's ḍaʿīf.

**A generator fault, caught mid-run.** At first the attach mode matched on any identifying form,
including `احمد بن محمد`, which over a hundred main-entry people carry. With a shared kunyah that
produced chance candidates: 44 tasks existed only through such forms, and 42 more had their
candidates changed. It now uses the per-form pass's twelve-person cap. All 19 merges applied in
those 86 tasks were read before recording. Each rests on explicit evidence, such as the source
naming the entry, al-Najāshī's text reproduced word for word, or a rare nisbah under a dotting
variant (al-Dūrīstī / al-Dūrīsī), and not on the shared name.

**What the answers say about the generator.** In 15 of 1,077 answers the agent reports that the
right man was not offered. Mostly this is the rule that people agents have already judged
together are not asked again. An earlier agent had kept n013548 apart from Aḥmad b. Muhammad b.
ʿĪsā, and the attach agent now reads it as him. Such disagreements go to review, not to a second
vote. 160 answers name a fused profile, collected in `agent_flags.json` for the split pass.

**The twelve narrators, after stage 3.** Every main-book entry of each sits in one person. Ten
profiles still bear one of their names and a matching kunyah or nisbah:

- Two are different men: Suhayl b. Ziyād al-Wāsiṭī and Ibrāhīm b. Sahl b. Hāshim.
- Five are fused profiles that the agents flagged or that mix two men, for stage 4.
- One is a heading confused with Abū ʿAlī al-Ashʿarī.
- Two are disagreements between agents (n013548, and the single-source n014946), for review.

### Stage 4, the split pass (2026-09-14)

Four runs, each validated, recorded and measured against the build before it:

| Run | Asked | Result |
|---|---|---|
| Split, `split-23425-0f610e2eebc74002` | 371 people as their entries, 3,516 entries in 23 batches | 229 split (97 high, 131 medium); 200 entries marked mixed |
| Entry repair, `entry-23824-5c84594d55ac0c60` | 127 Layer 0 entries as their pages | 89 split |
| Re-split, `resplit-23954-e7136f9c42d3628f` | 68 people whose entries entry repair divided | 67 applied, superseding 134 earlier decisions |
| Orphan pages, `orphan-23881-db55a0c4f4391b45` | 8 pages with a main-entry candidate | 3 joined |

| | After stage 3 | After stage 4 |
|---|---|---|
| People | 23,425 | 23,879 |
| Verdict clashes | 159 | 139 |
| Kunyah clashes, case-folded | 308 | 270 |
| Drawing on more than one book | 20.2% | 19.9% |
| Agent answers overriding another agent's | 33 | 27 |
| Held for review | 405 | 421 |

The 371 people were signalled by:

| Signal | People |
|---|---|
| An agent in an earlier run said a profile mixes men | 348 |
| A generation clash | 79 |
| A review signal | 48 |
| A verdict clash with two kunyahs | 38 |

**What the splits undid.** Some of the cases:

- ʿAmr b. Ḥurayth: al-Najāshī's thiqa al-Ṣayrafī, and the Companion «عدو الله، ملعون».
- Sulaymān b. Jaʿfar al-Jaʿfarī, and the man Khoei heads «وليس بالجعفري».
- Abū Baṣīr, and al-Ḥajjāl's entry.
- Ṣāliḥ b. Khālid al-Maḥāmilī, and al-Qammāṭ.
- Thābit b. Hurmuz, and his son ʿAmr.
- Jābir al-Juʿfī, and al-Fārisī, a companion of al-ʿAskarī.

Both stage 3 joins that the cross-form review had doubted were among them.

**The famous narrators held.** Every main-book entry of each of the twelve is still in one
person. What left them were Khoei mentions carrying another man's data, such as a student
listed as his own teacher, or a death in the Minor Occultation.

**Layer 0 joins consecutive homonyms.** Layer 0 took adjacent pages of one book as fragments of
one entry. Khoei and Mamaqani head consecutive entries for men of the same name, so some
"entries" were several men. Of 919 multi-page entries, 79 had pages that disagree on era, kunyah
or verdict: 54 Khoei, 24 Mamaqani, 1 al-Najāshī. With the entries the split answers marked
mixed, 127 went to agents page by page, and 89 were split. Khoei's four «عمرو بن حريث» pages are
four men: the Companion, al-Ṣayrafī, Abū Khallād al-Kūfī and Abū Muhammad al-Ashjaʿī.

**A split at one grain has to be asked again at the next.** The person split set Khoei's
four-page ʿAmr b. Ḥurayth entry apart as a whole, with a `distinct`. After entry repair, that
`distinct` still held al-Ṣayrafī's own page, Khoei 12466, away from him. The re-split asked those
68 people again with the repaired entries as page groups, and each applied answer superseded the
split before it. Khoei 12466 rejoined al-Ṣayrafī. Retracting old splits could have released some
unrelated union, so every one of the 78 rejoins was checked and lies inside a re-split cluster.

**No split made a join.** After each run, no person combined sources from two people of the
build before, other than the re-split's intended rejoins.

**What remains.** 87 single entries each describe two men. So do many of the entries and pages
marked mixed, which are now set apart. An identity decision cannot divide one extracted profile,
so these go to re-extraction (stage 6). The 139 remaining verdict clashes are, as before, mostly
narrators the scholars genuinely dispute. The agents also found verdicts attributed to the wrong
man. One Khoei page credits al-Najāshī with praise of Sahl b. Ziyād that al-Najāshī's own entry
contradicts. This is why stage 7 checks every verdict against its entry before it is shown.

---

## Appendix D — Phase 1 extraction record

| Book | Slug | Scope | Profiles | Pages | Assessment |
|------|------|-------|----------|-------|------------|
| Kitāb al-Ḍuʿafāʾ | `duafa` | 224 entries | 222 | 224/224 | clean; 2 entries errored |
| Rijāl al-Kashshī | `kashshi` | 94 pages | 209 | 94/94 | clean; 1 error |
| Fihrist al-Ṭūsī | `fihrist` | 253 pages | 731 | 253/253 | clean; 5 errors |
| Rijāl al-Najāshī | `najashi` | 461 pages | 1,310 | 461/461 | clean |
| Rijāl al-Ṭūsī | `tusi` | 417 pages | 123 | 412/417 | **truncated** — 92 errors; re-run at a smaller batch size |
| Jāmiʿ al-Ruwāt | `ardabili` | 1,210 pages | 1,796 | 1,210/1,210 | **truncated** — yield an order of magnitude short |
| Muʿjam Rijāl al-Ḥadīth | `khoei` | 10,924 pages | 21,938 | 10,924/10,924 | per mention, not per entry; about 15,700 real entries |
| Tanqīḥ al-Maqāl | `mamaqani` | 34 vols | 15,747 | 15,873 batches | per mention; volume coverage unverified |

Total before merging: 42,076.

Operational lessons:

- Process one book at a time; parallel runs corrupted shared checkpoints.
- Checkpoint every batch — downloads fail intermittently and a run must survive it.
- Dense pages of bare names truncate on output; shrink the batch, don't drop the page.
- Page coverage is not extraction coverage. Compare yield with the book's known entry count
  before calling a book done.
