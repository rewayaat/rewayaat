# Deployment

## Docker

**Dockerfile**: `src/main/docker/Dockerfile`

```dockerfile
FROM rewayaat/eclipse-temurin:17-jre
VOLUME /tmp
ARG JAR_FILE=*.jar
COPY ${JAR_FILE} app.jar
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app.jar"]
```

Lightweight JRE-only image. Built and pushed as `rewayaat/rewayaat-v2`.

> The `-v2` suffix on the image, the `rewayaat-v2` namespace and the alert names is
> historical — it dates from the migration off the original deployment, which was
> retired in June 2026 and no longer exists in the cluster. It survives because it is
> the live identity of the running system: renaming it would mean a new namespace, a new
> Docker Hub repository, an Argo repoint and an ingress cutover, not a rename.

## CI/CD Pipeline

**Workflow**: `.github/workflows/ci-cd.yml`
**Trigger**: Push to `master` (path-filtered: `src/**`, `pom.xml`, Dockerfile, workflow)

### Pipeline Steps

1. **Build** — `mvn -B -q package -DskipTests`
2. **Docker build + push** — Tags with `$GITHUB_RUN_ID` and `latest`
3. **Kubernetes config** — `doctl` connects to DigitalOcean cluster `k8s-c1`
4. **Secret sync** — Creates/updates `rewayaat-v2-mail` and `rewayaat-v2-sentry` secrets
5. **GitOps update** — Updates `k8s/kustomization.yaml` image tag, commits and pushes

The kustomization commit triggers Kubernetes to pull the new image.

## Kubernetes

**Namespace**: `rewayaat-v2`

| Resource | File | Purpose |
|----------|------|---------|
| Deployment | `k8s/deployment.yaml` | App pods |
| Service | `k8s/service.yaml` | Internal routing |
| Ingress | `k8s/ingress.yaml` | External access |
| HPA | `k8s/hpa.yaml` | Auto-scaling |
| PDB | `k8s/pdb.yaml` | Pod disruption budget |
| NetworkPolicy | `k8s/networkpolicy.yaml` | Network isolation |
| ServiceMonitor | `k8s/servicemonitor.yaml` | Prometheus metrics |
| PrometheusRules | `k8s/prometheus-rules.yaml` | Alerting rules |
| GrafanaDashboard | `k8s/grafana-dashboard.yaml` | Monitoring dashboard |
| Kustomization | `k8s/kustomization.yaml` | Image tag management |
| ArgoApplication | `k8s/argo-application.yaml` | GitOps deployment |

## Monitoring

- **Prometheus** via ServiceMonitor (metrics on management port 8003)
- **Grafana** dashboard for app metrics
- **Sentry** for error tracking
- **Alerting** via Prometheus rules

## Local Development

```bash
# Prerequisites: Elasticsearch on localhost:9200

# Build
mvn -B -q package -DskipTests

# Run
java -jar target/rewayaat-1.0.jar

# Or with dev config
SPRING_PROFILES_ACTIVE=dev java -jar target/rewayaat-1.0.jar
```

- App: `http://localhost:8002`
- Management/Actuator: `http://localhost:8003`
- ES: `http://localhost:9200`

## Secrets

Managed via GitHub Actions CI/CD:

| Secret | Purpose |
|--------|---------|
| `DOCKERHUB_USERNAME/PASSWORD` | Docker image push |
| `DIGITALOCEAN_ACCESS_TOKEN` | Kubernetes cluster access |
| `MAIL_FROM` | Email sender address |
| `RESEND_API_KEY` | Email delivery (Resend) |
| `SENTRY_DSN` | Error tracking |
