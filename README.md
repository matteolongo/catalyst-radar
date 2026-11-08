# CatalystRadar

CatalystRadar watches a small list of US stocks for news that may change company
expectations. It extracts evidence-backed events, groups duplicate coverage and
serves versioned scores, history and rankings through a REST API.

## Production quickstart

Run one application instance and one PostgreSQL database on a Docker Compose
host. The release watches **one to five stocks** from the
[bundled US universe](src/main/resources/universe/us-equities-v1.json). It uses
Polygon news and OpenAI extraction and embeddings. The public API requires
bearer keys. Operators can run ingestion, inspect ingestion and model runs, and
issue or revoke keys.

1. Copy [.env.production.example](.env.production.example) to `.env.production`.
   Set your ticker list and provider keys. Generate separate random values
   for `CATALYST_POSTGRES_PASSWORD`, `CATALYST_DB_PASSWORD` and
   `CATALYST_INTERNAL_ADMIN_KEY` (for example, run `openssl rand -hex 32`
   for each). Keep the file private.
2. Start the stack from the repository root.

   ```sh
   docker compose --env-file .env.production -f compose.production.yaml up -d --build --wait
   curl -fsS http://127.0.0.1:8080/actuator/health/readiness
   ```

   The first startup creates the database role, applies Flyway migrations and
   seeds the watchlist. Readiness checks the application and database. The
   production stack uses its own persistent volume. PostgreSQL stays on the
   container network and the app listens on host loopback.

### Issue a key and run ingestion

Set `ADMIN_KEY` in your shell to the value of `CATALYST_INTERNAL_ADMIN_KEY`.
Issue a client key and save its `rawKey` and `clientId` in your secret store.
CatalystRadar stores only a hash of the secret.

```sh
curl -fsS http://127.0.0.1:8080/internal/api-clients \
  -H "X-Admin-Key: $ADMIN_KEY" -H 'Content-Type: application/json' \
  -d '{"name":"research-client"}'
```

Scheduled ingestion starts off. Run one cycle on the deployment host and check
its `status`. The request waits for the cycle and uses your configured provider
accounts. Set `CATALYST_INGESTION_ENABLED=true` in `.env.production` and run
the startup command again to poll every 30 minutes. Daily snapshots refresh
scores between news cycles.

```sh
curl -fsS -X POST http://127.0.0.1:8080/internal/ingestion/runs \
  -H "X-Admin-Key: $ADMIN_KEY"
```

Set `API_KEY` to the issued `rawKey`. Replace `DELL` with a watched ticker.
Discovery can be empty until the provider returns relevant news.

```sh
curl -fsS http://127.0.0.1:8080/v1/companies/DELL/catalyst \
  -H "Authorization: Bearer $API_KEY"
curl -fsS 'http://127.0.0.1:8080/v1/discovery/catalyzed?limit=20' \
  -H "Authorization: Bearer $API_KEY"
```

OpenAPI JSON is at `/v3/api-docs`. The `/v1` routes cover company details,
company events, catalyst state, timelines, the event feed and discovery.
See the [API design](docs/architecture.md#16-public-api-v01).

### Operate the release

- **Network access.** For remote consumers, forward `/v1/` through your HTTPS
  reverse proxy. Keep `/internal/` on the host for operators. Run a single
  application instance per database, including during updates.
- **Coverage.** Each cycle fetches one page of up to 20 articles per ticker
  from the previous two hours and processes up to 20 queued documents.
  A truncated news page reports `NEWS_WINDOW_TRUNCATED` in ingestion history.
  Extraction results over five candidate events per article are recorded as
  invalid, with the source retained. News older than the lookback needs a
  separate historical import.
- **Watchlist changes.** Edit `CATALYST_TICKERS` and recreate the app with the
  startup command. Removed companies leave discovery and daily scoring.
  Stored events and histories stay queryable, and work queued solely for an
  inactive company waits until it is watched again. The new list applies to
  future extraction. Previously completed and audit-only articles remain
  available to development replay.
- **Monitoring.** Use `/actuator/health/readiness` for serving health. Check
  `GET /internal/ingestion/runs` for failed or partial ingestion and
  `GET /internal/model-runs` for recorded model usage and cost. Both require
  the admin key. Score-v1 weights and thresholds are provisional.
- **Key rotation.** Issue a replacement, update the client, then call
  `DELETE /internal/api-clients/{clientId}` with the admin key to revoke
  the old key. Rotate the admin key by updating the environment file and
  recreating the app. Change database passwords in PostgreSQL before
  updating the environment file on an existing volume.

Back up PostgreSQL before updating the app and keep regular copies off the
host. On the deployment host, run the following with a protected backup
destination.

```sh
docker compose --env-file .env.production -f compose.production.yaml \
  exec -T postgres pg_dump -U postgres -Fc catalyst_radar > catalyst-radar.dump
```

To restore, start only `postgres` with the production Compose file on a fresh
volume and wait for it to become healthy. Restore the dump before starting
the app. Use the same watchlist and configuration to recover the original
coverage.

```sh
docker compose --env-file .env.production -f compose.production.yaml \
  exec -T postgres pg_restore -U postgres -d catalyst_radar --clean --if-exists --exit-on-error \
  < catalyst-radar.dump
```

## Local demo

Run `docker compose up --build` for the loopback development stack. It includes
[Swagger](http://localhost:8080/swagger-ui.html) and the
[operations dashboard](http://localhost:8080/ops/index.html). The development
admin key is `local-dev-secret`. See the [dashboard walkthrough](ui/README.md)
for investigating runs, evidence and costs.

GitHub Actions exercises the fixture-based document-to-discovery flow and boots
the production Compose stack to check readiness and API-key handling.

[Architecture and domain rules](docs/architecture.md) ·
[Implementation specification](docs/implementation-v0.1.md) ·
[Development operations reference](docs/operations-backoffice.md)
