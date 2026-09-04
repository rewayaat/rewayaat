# Documentation

## Start here

| Document | What it covers |
|----------|----------------|
| [architecture.md](architecture.md) | How the system is put together: packages, controllers, services, indices, design decisions |
| [search.md](search.md) | How search, similar narrations and Quranic insights actually work at query time |
| [data-pipeline.md](data-pipeline.md) | How every piece of data gets into Elasticsearch |
| [deployment.md](deployment.md) | Docker, CI/CD, Kubernetes, monitoring, secrets |

## Agent pipelines

Long-running offline passes where Claude sub-agents judge content in batches. Each is a
runbook: current state, how to resume, and the agent prompt to use.

| Pipeline | State |
|----------|-------|
| [pipelines/llm-similar-hadith.md](pipelines/llm-similar-hadith.md) | Complete — 374,461 pairs judged, loaded on 25,273 docs |
| [pipelines/quranic-insights.md](pipelines/quranic-insights.md) | Judging and highlighting complete; excerpts at 51% |
| [pipelines/hadith-annotation.md](pipelines/hadith-annotation.md) | 13% judged, nothing loaded to ES yet |

## Proposals

Designs that are not built. Kept because the thinking is worth more than the code was.

| Proposal | State |
|----------|-------|
| [proposals/narrator-system.md](proposals/narrator-system.md) | Phases 1-2 ran and the data survives in `tmp/`; the code was deleted and phases 3-5 never started |

## Conventions

- Every document states what is **actually true right now**, with counts taken from the
  live index. If you finish a pipeline stage, update its status line in the same commit.
- Scripts are referenced by their full path from the repo root (`scripts/<group>/<name>.py`).
- Anything under `tmp/` is a large local artifact, never committed. `tmp` symlinks to
  `/mnt/share/rewayaat-backup/tmp/`.
