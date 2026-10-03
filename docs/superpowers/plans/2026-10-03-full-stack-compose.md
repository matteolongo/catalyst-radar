# Full-Stack Docker Compose Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Start PostgreSQL, the Kotlin/Spring Boot API, and the API-served operator dashboard with one `docker compose up --build` command.

**Architecture:** Add a multi-stage Dockerfile that builds the Spring Boot executable JAR on JDK 21 and runs it in a minimal JRE 21 image as a non-root user. Extend the existing Compose topology with an application service that waits for healthy PostgreSQL, connects through the Compose hostname, and exposes the API and packaged dashboard at port 8080.

**Tech Stack:** Docker, Docker Compose, Gradle 9.7.1 wrapper, Kotlin, Spring Boot 4.1.1, PostgreSQL 18 with pgvector.

**Spec:** `docs/superpowers/specs/2026-10-03-full-stack-compose-design.md`

## Global Constraints

- Preserve the modular-monolith architecture; do not introduce a separate frontend service.
- Keep PostgreSQL 18 + pgvector as the only datastore and retain its named volume.
- Use JVM 21 for both build and runtime images.
- Never bake provider API keys or other secrets into images or committed files.
- Preserve the static dashboard's same-origin runtime at `/ops/index.html`.
- Keep ingestion and snapshot schedulers disabled by default.
- Do not add Redis, Kafka, Kubernetes, or additional application dependencies.

---

### Task 1: Containerize the Spring Boot Application and Compose Service

**Files:**
- Create: `Dockerfile`
- Create: `.dockerignore`
- Modify: `compose.yaml:1-25`

**Interfaces:**
- Consumes: Gradle wrapper (`gradlew`, `gradle/wrapper/`) and application source/resources, including static `ui/` assets copied by `build.gradle.kts`.
- Produces: an `app` Compose service on `http://localhost:8080`, using `postgres` as the JDBC hostname and reporting readiness from `GET /actuator/health`.

- [ ] **Step 1: Verify the existing executable JAR name and packaged dashboard location**

Run:

```powershell
.\gradlew.bat bootJar --no-daemon
jar tf build\libs\catalyst-radar-0.0.1-SNAPSHOT.jar | Select-String "BOOT-INF/classes/static/ops/index.html"
```

Expected: the boot JAR contains `BOOT-INF/classes/static/ops/index.html`, proving that the app image needs no separate frontend build.

- [ ] **Step 2: Add a Docker build context allowlist**

Create `.dockerignore` with these entries so the image build excludes generated output, local secrets, and development files while retaining Gradle wrapper and application sources:

```text
.git
.gradle
.kotlin
build
.env
.env.local
*.log
.idea
.vscode
docs
```

- [ ] **Step 3: Add the multi-stage Dockerfile**

Create `Dockerfile` with a JDK build stage and a JRE runtime stage. Use the Gradle wrapper to build the boot JAR, copy only the executable JAR to runtime, and run as an unprivileged user:

```dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew
RUN ./gradlew dependencies --no-daemon

COPY src src
COPY ui ui
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --uid 10001 catalyst
COPY --from=build /workspace/build/libs/catalyst-radar-0.0.1-SNAPSHOT.jar app.jar
USER catalyst
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

- [ ] **Step 4: Extend Compose with the application service**

Keep the existing `postgres` service and named `catalyst_pgdata` volume. Add this service under `services`:

```yaml
  app:
    build:
      context: .
    environment:
      SPRING_PROFILES_ACTIVE: local
      CATALYST_DB_URL: jdbc:postgresql://postgres:5432/catalyst_radar
      CATALYST_DB_USER: catalyst
      CATALYST_DB_PASSWORD: catalyst
      POLYGON_API_KEY: ${POLYGON_API_KEY:-}
      FINNHUB_API_KEY: ${FINNHUB_API_KEY:-}
      OPENAI_API_KEY: ${OPENAI_API_KEY:-}
      CATALYST_INTERNAL_ADMIN_KEY: ${CATALYST_INTERNAL_ADMIN_KEY:-local-dev-secret}
    depends_on:
      postgres:
        condition: service_healthy
    ports:
      - "8080:8080"
    healthcheck:
      test: ["CMD-SHELL", "curl --fail --silent http://localhost:8080/actuator/health | grep -q '\"status\":\"UP\"'"]
      interval: 10s
      timeout: 3s
      retries: 12
      start_period: 30s
```

- [ ] **Step 5: Validate the Compose model and build the image**

Run:

```powershell
docker compose config
docker compose build app
```

Expected: configuration expands without missing-variable warnings, and `catalyst-radar-app` builds successfully.

- [ ] **Step 6: Commit the container runtime files**

```powershell
git add Dockerfile .dockerignore compose.yaml
git commit -m "feat: containerize application compose service"
```

### Task 2: Document and Verify the Full Local Stack

**Files:**
- Modify: `README.md:141-202`

**Interfaces:**
- Consumes: the `postgres` and `app` services defined in `compose.yaml`.
- Produces: reproducible operator instructions for starting, checking, configuring, and stopping the full local stack.

- [ ] **Step 1: Update the local runtime documentation**

Replace the statement that the Kotlin application runs outside Docker with one-command instructions:

```markdown
### Start the full local stack

Docker Desktop is the only runtime prerequisite. Start PostgreSQL, the Kotlin
API, and the packaged analysis dashboard with:

```bash
docker compose up --build
```

The API applies Flyway migrations during startup. Wait for the `app` service
to report healthy, then use:

```text
CatalystRadar API   http://localhost:8080
Analysis dashboard  http://localhost:8080/ops/index.html
Health              http://localhost:8080/actuator/health
PostgreSQL          localhost:5432
```

Run `docker compose down` to stop the stack while retaining database data.
Run `docker compose down -v` only when a fresh local database is intended.
```

Keep the existing credential table, clarify that Compose reads optional keys from `.env` or exported environment variables, and retain warnings that real ingestion can incur provider/LLM costs.

- [ ] **Step 2: Run regression tests before the runtime smoke test**

Run:

```powershell
.\gradlew.bat clean test --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Start the complete Compose stack**

Run:

```powershell
docker compose up --build -d
docker compose ps
```

Expected: `postgres` is healthy and `app` reaches healthy status after Flyway completes.

- [ ] **Step 4: Verify the API health and the packaged dashboard**

Run:

```powershell
(Invoke-WebRequest http://localhost:8080/actuator/health).Content
(Invoke-WebRequest http://localhost:8080/ops/index.html).StatusCode
```

Expected: health JSON contains `"status":"UP"`; dashboard request returns `200`.

- [ ] **Step 5: Stop services without deleting persisted data**

Run:

```powershell
docker compose down
docker volume inspect catalyst-radar_catalyst_pgdata
```

Expected: services stop and the named PostgreSQL volume still exists.

- [ ] **Step 6: Commit documentation**

```powershell
git add README.md
git commit -m "docs: document full compose startup"
```

## Plan Self-Review

### Spec coverage

- Multi-stage JDK 21 build and JRE 21 runtime: Task 1, Steps 2-3.
- PostgreSQL preservation and health-gated application startup: Task 1, Step 4.
- Same-origin API-served dashboard: Task 1, Step 1 and Task 2, Step 1.
- Environment-supplied secrets and disabled default schedulers: Task 1, Step 4 and Task 2, Step 1.
- Failure visibility through healthchecks: Task 1, Step 4.
- Compose build, health, dashboard, Gradle regression, and data-retention verification: Task 1, Step 5 and Task 2, Steps 2-5.

### Placeholder scan

No placeholders or deferred implementation steps remain.

### Type consistency

The plan only introduces Compose service configuration and Docker artifacts; it adds no Kotlin interfaces or types.
