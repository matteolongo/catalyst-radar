# CatalystRadar v0.1 — Agent Handoff

**Status: work in progress, tree green (200 tests, 0 failures).**
**Date:** 2026-09-17. **Branch:** `feat/cr-16-auth` (pushed, not yet a PR).

This file gives the next agent everything needed to finish v0.1 without
reading the prior session. Source-of-truth order still applies:
`docs/implementation-v0.1.md` (behavior) > `docs/architecture.md`
(boundaries) > `AGENTS.md` (engineering rules) >
`docs/implementation-plan-v0.1.md` (sequencing).

## 1. Where things stand

Merged to `main` (PRs #1–#16): CR-01 skeleton, CR-02 domain, CR-03
persistence, CR-04 universe (517 seeded companies), CR-05 contracts,
CR-06 Polygon, CR-07 Finnhub, CR-08 ingestion (+1 hotfix), CR-09
extraction, CR-10 normalization, CR-11 clustering, CR-12 score-v1,
CR-13 state/snapshots, CR-14 public API, CR-15 discovery.

In progress on `feat/cr-16-auth` (2 commits pushed):
`feat(security): add API client persistence` and
`feat(security): add API key generation and hashing`.
ApiKeyService + ApiClientStore exist, tested, green.

## 2. What is missing for a usable product

### 2a. Remaining plan items (in order)

- **CR-16 (resume here):** auth filter + properties + integration test.
  Exact resume point is section 5 below.
- **CR-17 replay:** point-in-time document selection, reprocessing,
  snapshot recalculation. Note: `score_versions` table exists but no
  adapter; ScoreConfig lives in code (`application/scoring`).
  Persisting configs belongs here.
- **CR-18 evaluation:** benchmark fixtures, forward returns (Polygon
  daily-bars adapter deferred here from CR-06 — see sequence item 32
  in `docs/implementation-v0.1.md`), precision/recall/lead-time.
- **CR-19 observability:** provider/LLM/pipeline Micrometer metrics +
  structured logging (names in `docs/implementation-v0.1.md` §21).
- **CR-20 stabilization:** E2E test, config review, docs, cleanup.

### 2b. Gaps the plan does not assign (needed for "usable")

1. **Pipeline orchestrator (biggest gap).** CR-08→CR-13 services are
   standalone; nothing chains
   ingest → extract → normalize → cluster → recalculate into a runnable
   flow. Build a scheduled orchestrator (likely in CR-20 or a small
   extra PR) reusing IngestionService, EventExtractionProvider,
   EventNormalizationService, EventClusteringService, CatalystService.
2. **Internal trigger endpoints** (`POST /internal/ingestion/runs`,
   `POST /internal/companies/{ticker}/recalculate`) are promised by
   architecture §16 / spec §17 but assigned to no CR. Needed to run
   the pipeline on demand. They must require `CATALYST_INTERNAL_ADMIN_KEY`
   (CR-16 leaves the enforcement hook for this).
3. **Key minting has no interface.** ApiKeyService.create exists but
   nothing calls it (no admin endpoint/CLI). Add one with the internal
   endpoints, or keys can never be issued.
4. **OpenAPI/Swagger missing.** Architecture promises
   `/v3/api-docs` + swagger-ui; springdoc was never added (deferred
   in CR-01). Add `springdoc-openapi-starter-webmvc-ui` (pin version,
   check Boot 4.1 compat) for a usable API product.
5. **No CI workflow.** There is no `.github/workflows/*`; add one
   running `./gradlew clean test` (needs Docker for Testcontainers).
6. **Daily snapshot job** (spec §16) for velocity lookbacks — no
   scheduler exists beyond ingestion.
7. **Company aliases**: table exists, no adapter/seed; ticker
   matching is exact (`BRK.B` vs `BRK/B` won't resolve).
8. **README** still describes the pre-database skeleton; rewrite the
   local-run flow (compose, migrate, seed, ingest) in CR-20.
9. Live E2E needs real `POLYGON/FINNHUB/OPENAI_API_KEY`; without keys
   only WireMock/fake paths run. Never commit keys.

### 2c. Non-issues (do not build)

UI, Kafka/Redis/K8s, SEC/IR ingestion, webhooks, buy/sell logic,
social sentiment, vector ANN indexes — all explicitly out of v0.1.

## 3. How to work (mandatory)

- **Branch per CR:** `feat/cr-16-auth` (current), then
  `feat/cr-17-replay`, etc. Never commit to `main` directly.
- **TDD:** test first, watch it fail (compile failure counts as RED
  for new types), minimal implementation, watch it pass. Never claim
  green without running it.
- **Atomic commits**, conventional messages, e.g.
  `feat(security): add API key generation and hashing`. 3–8 commits
  per PR. Tests ride with behavior; a trailing `test(scope): ...`
  commit per PR is the convention used so far.
- **Before every push:** `git status` review of the staged set. A
  partial `git add` once merged a non-compiling `main` (hotfix PR #9).
  Never `git add -A` (stray files exist, see §6).
- **Verify:** `./gradlew clean test` (Windows: `.\gradlew.bat`).
  Amend freely on unpushed branches to keep history green.
- **Ship:** push branch → `gh pr create --base main` (use the PR
  template from `docs/implementation-plan-v0.1.md`) → `gh pr merge
  --merge` → `git checkout main && git pull --ff-only`.
- **Mode:** the user authorized autonomous
  implement→merge→continue until v0.1 is done. No per-PR approval
  waits. Keep this file updated if the plan changes.

## 4. Environment (Windows + PowerShell 5.1)

- JDK 21: `C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`.
  New shells inherit a stale `PATH`, so **every** build command must
  start with:
  `$env:JAVA_HOME=[System.Environment]::GetEnvironmentVariable('JAVA_HOME','User'); $env:Path=[System.Environment]::GetEnvironmentVariable('Path','Machine')+';'+[System.Environment]::GetEnvironmentVariable('Path','User')`
  then `.\gradlew.bat clean test`. (`gradle`/`java` bare may not
  resolve; there is no `head`/`tail`/`sed`/`grep` — use
  `Select-Object`, `Select-String`, `rg`.)
- Gradle wrapper 9.7.1, Spring Boot 4.1.1, Kotlin 2.3.21 (managed).
- **Docker Desktop must be running** (Testcontainers uses the npipe;
  if container tests fail with "Could not find a valid Docker
  environment", start
  `C:\Users\Usuario\AppData\Local\Programs\DockerDesktop\Docker Desktop.exe`
  and wait ~90s). Local PG: `docker compose up -d` (pgvector:pg18,
  volume mounted at `/var/lib/postgresql`, NOT `…/data`).
- Tool harness quirk: very long chained commands and background
  processes (`Start-Process`) get killed; prefer short foreground
  commands. Never leave stray `bootrun*.log` files (`.gitignore`
  covers `*.log`).

## 5. Exact resume point: finish CR-16

Committed and green: `persistence/security/*` (row/repo/store),
`security/ApiKeyGenerator.kt` (format `cr_live_<8 alnum>_<secret>`,
strict parse, SHA-256, constant-time compare), `security/ApiKeyService.kt`
(create/authenticate/result sealed type).

Still to write on `feat/cr-16-auth`:

1. `security/SecurityProperties.kt`:
   `@Component @ConfigurationProperties("catalyst")` with nested
   `api.authEnabled=false` and `internal.adminKey=""`.
2. `security/ApiKeyAuthFilter.kt` (`OncePerRequestFilter`, no
   spring-security dependency): `/actuator*` public; `/internal/*`
   requires `X-Admin-Key` equal to configured key (fail closed when
   blank); `/v1/*` requires `Authorization: Bearer cr_live_…` iff
   auth enabled. Write `ProblemResponse` JSON directly (inject
   `tools.jackson.databind.ObjectMapper`): 401 `INVALID_API_KEY`,
   403 `KEY_DISABLED`. Never log keys.
3. `application.yml`: `catalyst.api.auth-enabled: false`,
   `catalyst.internal.admin-key: ${CATALYST_INTERNAL_ADMIN_KEY:}`.
4. Integration test `@SpringBootTest(properties auth-enabled=true)`
   + `@AutoConfigureMockMvc`: seed company via `CompanyStore`,
   mint key via `ApiKeyService`; assert 401 without key, 200 with
   key, actuator `/health` public without key, disabled key → 403.
5. `clean test` → push → PR (#17) → merge → delete nothing else →
   `feat/cr-17-replay`.

## 6. Verified platform facts (do not rediscover)

- Boot 4.1 needs `spring-boot-starter-flyway` (flyway-core alone
  creates no Flyway bean); `flyway-database-postgresql` for PG.
- No `RestClient.Builder` bean on this classpath → own prototype
  bean in `adapters/http/RestClientConfiguration.kt`.
- Testcontainers managed version is **2.x**: artifacts renamed to
  `testcontainers-postgresql`; `PostgreSQLContainer` is generic
  (`PostgreSQLContainer<*>`); `@Container` on Kotlin companion
  fields never fires → manual `start()` + `@DynamicPropertySource`
  in `PostgresIntegrationTest` (one shared container; write-tests
  must be `@Transactional`).
- WireMock: use `wiremock-standalone:3.13.2` only (core lacks a
  server; `wiremock-jetty12` metadata is broken).
- Jackson 3 = `tools.jackson` BUT annotations stay
  `com.fasterxml.jackson.annotation.*`; Jackson `ObjectMapper` =
  `tools.jackson.databind.ObjectMapper`.
- Spring Data JDBC + assigned UUIDs: `save()` on a set id issues
  UPDATE (silent no-op if absent) → inserts go through
  `JdbcAggregateTemplate.insert()`.
- `@Query` nullable params need explicit `CAST(:p AS type)` or PG
  fails type inference; raw `JdbcClient` cannot bind `Instant`
  (use `java.sql.Timestamp`); JSONB needs the explicit `JsonB`
  wrapper (never a global String converter; value classes erase);
  generic `Map` converters don't trigger — serialize maps in
  mapping code (`persistence/jdbc/Json.kt`).
- `PGvector(String)` throws checked `SQLException` (fine in Kotlin);
  read vectors via `toArray()`.
- Tests: `mockito-kotlin` for suspend stubbing; `@RegisterExtension
  @JvmField` companion pattern for WireMock; MockMvc slices need
  `@MockitoBean` for every injected collaborator; constructor
  injection doesn't work in test classes (field `@Autowired`
  `lateinit`); `ApplicationRunner.run` override takes non-null
  `ApplicationArguments` (Boot 4 JSpecify).
- PowerShell 5.1: `$hash.Keys` enumeration is broken — never use
  `.Keys`/`.Values` on hashtables; `Set-Content` writes BOM
  (strip for JSON resources / use utf8NoBOM).
- Learned the hard way: `groupBy { it.clusterId }` collapses all
  null-cluster events into one (see `CanonicalEventSelector`).

## 7. Codebase map (all under `com.catalystradar`)

`domain/{company,event,catalyst}` (stdlib only) · `ports/` (suspend
contracts + neutral DTOs + `ProviderException`) ·
`adapters/{http,polygon,finnhub,openai}` (RestClient +
Dispatchers.IO, DTOs `internal`, errors → `mapHttpClientError`) ·
`persistence/{company,document,event,catalyst,ingestion,extraction,security,discovery,jdbc}`
(Row/Repository/Store + `toDomain`/`toRow`, `JdbcCustomConversions`)
· `application/{company,ingestion,extraction,event,clustering,scoring,catalyst}`
(services own behavior) · `api/{dto,publicapi,error}` (explicit
DTOs, RFC 9457 advice) · `security/` (keys/filter) ·
`common/Versions.kt` · `scheduling` lives in
`application/ingestion/IngestionScheduler.kt` ·
`resources/prompts/` (versioned LLM artifacts),
`resources/universe/us-equities-v1.json` (517 seeded companies),
`resources/db/migration/V1__initial_schema.sql`.

## 8. Done means

CR-16→CR-20 merged, `clean test` green from scratch with Docker
running, Flyway migrates empty DBs, seed → ingest → extract →
normalize → cluster → score → snapshot → discovery works against
real or WireMock providers, README documents the flow, no v0.2
infrastructure, no secrets in git. Delete this file when v0.1 ships.
