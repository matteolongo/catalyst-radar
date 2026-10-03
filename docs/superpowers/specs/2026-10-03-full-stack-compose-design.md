# Full-Stack Docker Compose Design

## Goal

Make one `docker compose up --build` command run CatalystRadar's PostgreSQL
database, Kotlin/Spring Boot application, and its packaged operator dashboard.

## Scope

- Add a production-style multi-stage Dockerfile for the Kotlin application.
- Extend `compose.yaml` with an application service.
- Keep PostgreSQL 18 with pgvector and its named data volume.
- Document the single-command local runtime.

The dashboard remains static content packaged in the Spring Boot JAR and is
served at `/ops/index.html`; no standalone frontend service is added.

## Containers

### postgres

The existing `postgres` service remains the persistent PostgreSQL 18 +
pgvector datastore. It exposes port `5432` for optional host access and uses
its existing `pg_isready` healthcheck and named volume.

### app

The new `app` service builds from the repository root. Its image has two
stages:

1. A JDK 21 Gradle build stage creates the Spring Boot JAR.
2. A JRE 21 runtime stage copies only the executable JAR, runs it as a
   non-root user, and exposes port `8080`.

Compose supplies the application database URL with `postgres` as the network
hostname. The service waits for the database healthcheck before starting and
uses `/actuator/health` as its own healthcheck. The dashboard and API share
the same origin at `http://localhost:8080`, preserving the dashboard's current
same-origin API behavior.

## Configuration

The Compose file keeps safe local defaults for database credentials. Optional
provider credentials and other runtime overrides are read from the ignored
`.env` file or the caller's environment; they are not copied into the image or
committed. The local Spring profile is active and ingestion/snapshot schedulers
remain disabled by default, so starting the stack does not call external
providers.

## Failure Behavior

PostgreSQL must pass `pg_isready` before Compose starts the app. If the
database is unavailable or Flyway migration fails, the application healthcheck
will fail and Compose logs remain the diagnostic source. Stopping the stack
does not remove the named PostgreSQL volume unless explicitly requested with
`docker compose down -v`.

## Verification

Verification will include:

- the existing Gradle test suite;
- Docker image build and Compose configuration validation;
- `docker compose up --build` using the local stack;
- successful requests to `/actuator/health` and `/ops/index.html`;
- Compose teardown without deleting the named database volume.

## Documentation

The root README will describe the one-command startup flow, available API,
dashboard, and PostgreSQL endpoints, configuration source, and teardown
command.
