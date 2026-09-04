# Rewayaat

A Shia hadith research platform. 32,519 narrations from 18 books, searchable in Arabic
and English, cross-linked to similar narrations and to the Quranic verses that
illuminate them.

Live at **[hadith.academyofislam.com](https://hadith.academyofislam.com)**.

Spring Boot 3.3.2 on Java 17, with Elasticsearch as the only datastore. Everything
expensive — semantic embeddings, similar-narration judgments, hadith-to-Quran
connections — is computed offline by Python scripts and Claude sub-agents, then stored
in the index. No LLM is called while serving a request.

## Quick Start

You need **Java 17**, **Maven**, and **Elasticsearch 9.x on `localhost:9200`** with the
corpus already indexed. Without a populated index the app starts but every search
returns nothing; see [docs/data-pipeline.md](docs/data-pipeline.md) to build one.

```bash
mvn -B package -DskipTests          # build
./scripts/ops/restart.sh            # run with the dev profile, detached
```

| | |
|---|---|
| App | http://localhost:8002 |
| Actuator / metrics | http://localhost:8003 |
| Swagger UI | http://localhost:8002/swagger-ui.html |
| Elasticsearch | http://localhost:9200 |
| Logs | `/tmp/rewayaat-8002.log` |

`restart.sh` kills whatever holds port 8002 and relaunches detached, so the JVM survives
your shell. To run in the foreground instead:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Spring DevTools does not reliably pick up CSS changes — restart to verify styling.

### Tests

```bash
# what CI runs
mvn test -Dtest='!*IntegrationTest, !HodaAlQuranQualityCheck' -DfailIfNoSpecifiedTests=false

mvn test                            # everything, needs a live Elasticsearch
node --test src/test/js/*.test.js   # frontend unit tests
```

`*IntegrationTest` needs a running Elasticsearch; `HodaAlQuranQualityCheck` scrapes
hodaalquran.com to verify extraction quality. Both are excluded in CI.

## Repository Layout

```
src/main/java/com/rewayaat/
  controllers/       Server-rendered pages — the crawlable surface
    rest/            JSON API — what the Vue app talks to
  service/           Business logic
  core/              Query building, result shaping, text processing
    data/            Persisted models (HadithObject, QuranVerse, ...)
  tafsir/            Tafsir parsing; extractors/ holds 13 source-specific parsers
  loader/            One-time corpus loaders, per book
  tools/             Runnable offline backfill and audit tools
  config/            Spring configuration
src/main/resources/
  templates/         Thymeleaf pages
  static/            CSS, JS, taxonomy.json, quran.json, book_blurbs.json
src/test/            JUnit tests, plus node:test frontend tests in js/

scripts/             Offline pipelines, grouped by purpose — see scripts/README.md
batches/             The source corpus as JSONL, imported into Elasticsearch
docs/                Architecture, search, pipelines — see docs/README.md
k8s/                 Kubernetes manifests
.github/workflows/   CI/CD
tmp/                 Symlink to /mnt/share/rewayaat-backup/tmp — large artifacts, never committed
```

## Where To Look

| I want to... | Read |
|--------------|------|
| Understand how it fits together | [docs/architecture.md](docs/architecture.md) |
| Change how search behaves | [docs/search.md](docs/search.md) |
| Add or rebuild data | [docs/data-pipeline.md](docs/data-pipeline.md) |
| Resume an agent pipeline | [docs/pipelines/](docs/pipelines/) |
| Find the right script | [scripts/README.md](scripts/README.md) |
| Ship it | [docs/deployment.md](docs/deployment.md) |

## Configuration

Defaults live in `src/main/resources/application.yaml`, overridden per profile by
`application-dev.properties` and `application-prod.properties`. Everything is
environment-driven:

| Variable | Default | Purpose |
|----------|---------|---------|
| `ELASTIC_HOST` / `ELASTIC_PORT` | `localhost` / `9200` | Elasticsearch |
| `REWAYAAT_INDEX` | `rewayaat_updated` | Primary hadith index |
| `TAFSIR_INDEX` | `rewayaat_tafsir` | Tafsir index |
| `QURANIC_INSIGHTS_ENABLED` | `true` | Toggle the Quranic insights panel |
| `APP_BASE_URL` | `http://localhost:8002` | Used in outgoing links and mail |
| `RESEND_API_KEY` / `MAIL_FROM` | — | Transactional mail |
| `SENTRY_DSN` | — | Error tracking (prod only) |

Both the `dev` and `prod` profiles point Quranic insights at
`rewayaat_quranic_light_filtered`. The bare `application.yaml` default is the unfiltered
`rewayaat_quranic_light`, so always run with a profile.

Hadith edit access is a static email allowlist in
`src/main/resources/admins.txt`.

## Deployment

Pushes to `master` that touch `src/**`, `pom.xml`, the Dockerfile or the workflow build a
Docker image, push it to Docker Hub, and update the image tag in `k8s/kustomization.yaml`.
Argo CD picks that commit up and rolls it out to DigitalOcean Kubernetes. Pull requests
run the build and tests but never deploy.

## A Few Things That Will Bite You

- **The canonical host is `hadith.academyofislam.com`.** The app also answers on
  `rewayaat.info`, which is a mirror. `BASE_URL` in `HomeController` is deliberate — do
  not "fix" it.
- **`manuscript.css` has duplicate blocks by design** — base styles, then an ornament
  pass that overrides them. Do not consolidate them.
- **`tmp/` is a symlink to another disk.** The main disk runs near full; large artifacts
  must go through `tmp/`.
- **Never `git checkout HEAD` a file with uncommitted changes.** Several pipelines write
  into the working tree.

## License

See [LICENSE](LICENSE).
