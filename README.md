# 📚 BookStore API

A full-stack book store application — a Spring Boot REST API backed by PostgreSQL, a simple static frontend served by nginx, and a complete Kubernetes + Argo CD (GitOps) deployment setup supporting multiple environments (dev, staging, qa, prod).

## Architecture

```
 Browser ──► nginx (frontend, static HTML/JS)
                 │  /api/*   (Ingress rewrite)
                 ▼
             Spring Boot API (:8080)   ──►  PostgreSQL 16 (StatefulSet)
                 GET /books            ──►  books table (JPA/Hibernate)
                 POST /books
                 GET /books/{id}
                 DELETE /books/{id}
```

* **Backend** — Spring Boot REST API exposing CRUD operations for books, with JPA persistence to PostgreSQL.
* **Frontend** — A single-page static UI (`frontend/index.html`) with vanilla HTML/CSS/JS that lets users view, add, and delete books.
* **Database** — PostgreSQL 16, deployed as a StatefulSet with persistent storage.
* **Deployment** — Docker images + a Helm chart, orchestrated with Argo CD GitOps.

## Tech Stack

| Layer        | Technology                                   |
|--------------|----------------------------------------------|
| Language     | Java 21                                      |
| Framework    | Spring Boot 3.5.15 (Web, Data JPA)           |
| Build tool   | Gradle (wrapper included)                    |
| Database     | PostgreSQL 16 (with HikariCP pool)           |
| Frontend     | Static HTML/CSS/JS served via nginx          |
| Containers   | Docker (`eclipse-temurin:21-jre-alpine` for API, `nginx:alpine` for frontend) |
| Orchestration| Kubernetes (manifests in `k8s/`)             |
| GitOps       | Helm chart + Argo CD ApplicationSet          |

## Project Structure

```
.
├── src/main/java/com/bookstore/bookstore_api/
│   ├── BookstoreApiApplication.java   # Spring Boot entry point
│   ├── Book.java                      # JPA entity (id, title, author, price)
│   ├── BookRepository.java            # Spring Data JPA repository
│   └── BookController.java            # REST endpoints (GET/POST/DELETE)
├── src/main/resources/
│   └── application.properties         # Config (env-var driven)
├── src/test/                          # Spring context smoke test
├── frontend/
│   ├── index.html                     # Single-page UI
│   └── Dockerfile                     # nginx image serving the page
├── Dockerfile                         # API container image
├── k8s/                               # Raw Kubernetes manifests
│   ├── deployment.yaml, service.yaml
│   ├── configmap.yaml, secret.yaml
│   ├── ingress.yaml, hpa.yaml
│   └── postgres-*                     # StatefulSet, services, PVC, secret
├── bookstore-chart/                   # Helm chart (chart version 1.0.0)
│   ├── Chart.yaml, values.yaml
│   ├── values-{dev,staging,qa,prod}.yaml
│   ├── templates/                     # Deployment, services, ingress, secrets...
│   ├── appset.yaml                    # Argo CD ApplicationSet (multi-env)
│   ├── root-app.yaml                  # "App of Apps" root application
│   └── argocd/                        # Per-env Argo CD Applications
├── build.gradle, settings.gradle
└── gradlew / gradle/                  # Gradle wrapper
```

## Running Locally

### Prerequisites
* JDK 21
* PostgreSQL running (see note below)

### Build & test

```bash
./gradlew build
```

### Run the API

```bash
./gradlew bootRun
```

The app starts on `http://localhost:8080` by default. Config is env-var driven:

| Variable        | Default                    | Description                       |
|-----------------|----------------------------|-----------------------------------|
| `SERVER_PORT`   | `8080`                     | HTTP port                         |
| `APP_NAME`      | `BookStore API`            | Spring application name           |
| `DB_USERNAME`   | `bookstoreuser`            | Database user                     |
| `DB_PASSWORD`   | `bookstorepass123`         | Database password                 |

> ⚠️ The default JDBC URL is `jdbc:postgresql://postgres-service:5432/bookstoredb` — the Kubernetes service DNS name. When running locally, override the datasource URL, e.g.:
>
> ```bash
> ./gradlew bootRun \
>   --args='--spring.datasource.url=jdbc:postgresql://localhost:5432/bookstoredb'
> ```
>
> `spring.jpa.hibernate.ddl-auto=update` auto-creates/updates the `book` table.

### Run the frontend locally

Serve `frontend/index.html` with any static server (or just open the file), and it will call the API at `/api/books` — note that in Kubernetes the nginx Ingress maps `/api/*` to the backend, so the same page works locally and in the cluster.

## API Reference

Base path: `/books` (CORS is open to all origins).

| Method   | Endpoint        | Description                    |
|----------|-----------------|--------------------------------|
| `GET`    | `/books`        | List all books                 |
| `GET`    | `/books/{id}`   | Get a single book by id        |
| `POST`   | `/books`        | Add a new book (JSON body)     |
| `DELETE` | `/books/{id}`   | Delete a book by id            |

Example request:

```bash
curl -X POST http://localhost:8080/books \
  -H "Content-Type: application/json" \
  -d '{"title":"Dune","author":"Frank Herbert","price":19.99}'
```

Book JSON shape: `{ "id": int, "title": string, "author": string, "price": number }`

## Docker

### Build & run the API image

```bash
./gradlew build
docker build -t bookstore-api:3.0 .
docker run -p 8080:8080 bookstore-api:3.0
```

### Build & run the frontend image

```bash
cd frontend
docker build -t bookstore-frontend:2.0 .
docker run -p 80:80 bookstore-frontend:2.0
```

## Kubernetes Deployment

Two options are provided:

### 1. Raw manifests (`k8s/`)

Apply the manifests in dependency order (namespace → secrets → configmap → postgres → backend → frontend → ingress):

```bash
kubectl apply -f k8s/
```

Includes an HPA (`k8s/hpa.yaml`) that scales `bookstore-deployment` between 2–5 replicas at 50% CPU utilization.

### 2. Helm chart (`bookstore-chart/`)

```bash
helm install bookstore ./bookstore-chart -f ./bookstore-chart/values-dev.yaml
```

The chart deploys (in sync-wave order):
1. **Wave 0** — ConfigMap, Secrets
2. **Wave 1** — PostgreSQL StatefulSet + services
3. **Wave 2** — Backend Deployment (+ liveness/readiness probes on `/books`)
4. **Wave 3** — Frontend Deployment
5. **Wave 4** — Ingress (routes `/api/*` → backend, everything else → frontend)

## GitOps with Argo CD

Deployment is managed via Argo CD on the `argo` git branch.

* **Root app** — `bookstore-chart/root-app.yaml` ("App of Apps") points to `bookstore-chart/argocd/`, which contains one `Application` per environment.
* **ApplicationSet** — `bookstore-chart/appset.yaml` generates Applications for all environments from a single template:

| Environment | Namespace  | NodePort | Host                  | Storage   |
|-------------|------------|----------|-----------------------|-----------|
| dev         | `dev`      | 30004    | dev.bookstore.local   | 1Gi       |
| staging     | `staging`  | 30005    | staging.bookstore.local | 5Gi     |
| prod        | `prod`     | 30006    | bookstore.local       | 100Gi     |
| qa          | `qa`       | 30007    | qa.bookstore.local    | 1Gi       |

Each environment overlays `values.yaml` with its own `values-<env>.yaml` (replicas, resources, probe tuning, config values). Sync is automated with `prune` and `selfHeal`.

## Testing

```bash
./gradlew test
```

The test suite runs a Spring Boot context smoke test (`contextLoads`).

## Notes

* `patch.json`, `patch-add.json`, `patch-remove.json`, `logs.txt`, and `full_logs.txt` are leftover debug/test artifacts and can be safely ignored.
* `install.sh` is an unrelated copy of the OpenCode CLI installer and is not part of the application.
