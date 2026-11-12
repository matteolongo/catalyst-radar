# Review Fixes — Stacked PR Plan

> Source: static code review of 2026-11-12 (inconsistencies, broken observability, misleading UI).
> Nothing in the review was executed. Items marked **VERIFY FIRST** rest on behaviour that was
> read from code or recalled, not observed; confirm them with a failing test before fixing.

**Goal:** Fix 21 review findings in nine small, independently reviewable PRs.

**Read before starting:** `AGENTS.md`, `docs/architecture.md`, `docs/implementation-v0.1.md`.

## Ground rules for every PR

- Follow AGENTS.md §36: reproduce in a test, watch it fail, fix, watch it pass.
- Surgical changes only. No refactors, renames or drive-by cleanups outside the listed files.
- No new dependencies. No new infrastructure. No features outside v0.1 scope (§43).
- Never edit an applied Flyway migration. The next free version is `V8`.
- Scoring behaviour must not change. No PR here needs a new `score-v*` version; if a change
  would alter a persisted score, stop and report instead.
- Public `/v1` changes are additive only (new optional fields). Do not rename or remove fields.
- No live provider calls in tests: WireMock and fixtures only (§34).
- Update `README.md`, `ui/README.md` and `docs/operations-backoffice.md` when operator-visible
  behaviour changes. Each PR lists what to update.
- Checks before pushing each PR:
  - `node --test ui/app.test.js ui/operations.test.js ui/operations-model.test.js ui/leaderboard.test.js ui/notebooks.test.js`
  - `./gradlew clean test --console=plain` (needs Docker for Testcontainers)

## Stack layout

Each branch is cut from the one above it. Each PR targets the branch above it.

| # | Branch | Base | Findings | Area |
|---|--------|------|----------|------|
| 1 | `fix/review-01-error-logging` | `main` | 3, 13 | API errors, request ID |
| 2 | `fix/review-02-llm-cost-tracing` | 01 | 1, 2, 12 | OpenAI cost, tracing, dependency status |
| 3 | `fix/review-03-http-clients` | 02 | 4, 5, 9 | HTTP metrics, timeouts, error mapping |
| 4 | `fix/review-04-ingestion-pagination` | 03 | 8 | Ingestion completeness |
| 5 | `fix/review-05-ingestion-ledger` | 04 | 6 (ingestion), 7, 15 | Ingestion run records |
| 6 | `fix/review-06-pipeline-outcomes` | 05 | 6 (pipeline), 14 | Pipeline result and metrics |
| 7 | `fix/review-07-read-consistency` | 06 | 10, 11, 16, 18 (API) | Public read API |
| 8 | `fix/review-08-radar-ui` | 07 | 17, 18 (UI), 20 | Radar and company views |
| 9 | `fix/review-09-operations-ui` | 08 | 19, 21 | Operations backoffice |

**CI caveat.** `.github/workflows/ci.yml` runs `pull_request` checks only for bases `main` and
`codex/usability-*`. PRs 2–9 target `fix/review-*` branches and will get no CI. In PR 1, add
`'fix/review-*'` to `on.pull_request.branches`. This is the only workflow change in the stack.

**Merging.** Merge bottom-up (1 first). After each merge, retarget the next PR to `main` and
rebase it. Do not squash a PR while PRs above it are still open unless you rebase them after.

**Real dependencies** (the rest of the order is file-overlap convenience):
PR 3 edits the same OpenAI adapters as PR 2. PR 5 edits `IngestionService` after PR 4.
PR 8 consumes the API fields PR 7 adds.

---

## PR 1 — Log unexpected errors and make the request ID real

**Findings:** 3 (500s leave no log; `requestId` correlates with nothing), 13 (internal invariant
failures returned as 400 with internal messages).

**Files**
- `src/main/kotlin/com/catalystradar/api/error/ApiError.kt`
- `src/main/kotlin/com/catalystradar/security/ApiKeyAuthFilter.kt`
- New: `src/main/kotlin/com/catalystradar/api/RequestIdFilter.kt`
- New: `src/main/kotlin/com/catalystradar/api/error/InvalidRequestException.kt`
- Controllers under `api/` and the query models in `application/operations/OperationsQueries.kt`
- `.github/workflows/ci.yml` (branch trigger only, see CI caveat)

**Changes**
1. `RequestIdFilter` (`OncePerRequestFilter`, highest precedence): take `X-Request-Id` if it is
   non-blank, at most 64 chars and matches `[A-Za-z0-9._-]+`; otherwise generate a UUID. Store it
   as a request attribute, put it in SLF4J MDC under `requestId`, echo it in the `X-Request-Id`
   response header, and clear MDC in `finally`.
2. `ApiExceptionHandler.problem` and `ApiKeyAuthFilter.deny` read the request attribute instead of
   generating their own ID. Both must return the same ID the filter chose.
3. `unexpected()` logs at ERROR with the exception and `method`, `path`. Do not log request bodies,
   query strings or headers (§25, §27).
4. Add `logging.pattern.level: "%5p [%X{requestId:-}]"` to `application.yml` so the ID appears in
   log lines without adding a dependency.
5. Stop mapping every `IllegalArgumentException` to 400:
   - Introduce `InvalidRequestException(message)`. Map it to 400 `INVALID_REQUEST` with its message.
   - Boundary validation that is meant for clients must throw it. Audit every `require(...)` reached
     directly from request parameters: the controllers, `PageRequest`, `DocumentQuery`,
     `ModelQuery`, `IngestionQuery`, `ActivityWindow`, `ActivityWindowResolver`, `OperationsCursor.decode`,
     `DiscoveryStore.discover` limit/offset checks, `normalizeTicker`. Simplest safe approach: keep the
     `require` calls where they are and have each controller method wrap query construction in a small
     `invalidRequest { ... }` helper that converts `IllegalArgumentException` to `InvalidRequestException`.
   - Any other `IllegalArgumentException` now falls through to `unexpected()` → 500, logged.
6. **VERIFY FIRST:** check how framework exceptions are answered today. Write MockMvc tests for an
   unsupported method on an existing route, a malformed JSON body on `POST /internal/replays`, and a
   missing required parameter. If any returns 500, make `ApiExceptionHandler` map
   `HttpRequestMethodNotSupportedException` → 405, `HttpMessageNotReadableException` and
   `MissingServletRequestParameterException` → 400, all in the existing `ProblemResponse` shape.
   If they already return the right status, leave the handler alone and keep the tests.

**Tests**
- Extend the controller tests: a 500 response carries the same `requestId` as the `X-Request-Id`
  response header; a supplied valid `X-Request-Id` is echoed; an invalid one is replaced.
- A controller path that hits a non-boundary `IllegalArgumentException` returns 500 `INTERNAL_ERROR`
  without the internal message in `detail`.
- Every existing 400 assertion in `src/test/.../api/` still passes unchanged.
- `ApiKeyAuthFilterTest`: denial body `requestId` equals the response header.

**Acceptance**
- An unexpected exception produces exactly one ERROR log line with a stack trace and the request ID.
- No response body contains an internal `require` message.

**Docs:** README "Observability": document `X-Request-Id` and that 500s are logged with it.

---

## PR 2 — LLM cost, trace sampling and dependency status

**Findings:** 1 (extraction cost unknown with dated model IDs), 2 (LangSmith traces sampled at
10%), 12 (cancelled model calls mark OpenAI degraded).

**Files**
- `src/main/kotlin/com/catalystradar/adapters/openai/OpenAiPricing.kt`
- `src/main/kotlin/com/catalystradar/adapters/openai/OpenAiEventExtractionProvider.kt`
- `src/main/resources/application.yml`
- `src/main/kotlin/com/catalystradar/persistence/operations/OperationsSummaryStore.kt`
- `src/main/kotlin/com/catalystradar/observability/CatalystMetrics.kt`

**Changes**
1. Cost (finding 1). **VERIFY FIRST:** confirm against current OpenAI API documentation that chat
   completion responses return a dated snapshot in `model` (e.g. `gpt-4o-mini-2024-07-18`).
   - In `OpenAiPricing.estimateUsd`, resolve the price by exact match first, then by stripping a
     trailing `-YYYY-MM-DD` suffix. Keep "unknown model → null".
   - In `OpenAiEventExtractionProvider.record`, persist and tag with the **configured** model
     (`properties.extractionModel`) so successes and failures group together and the Models filter
     matches Settings. Keep sending the response model to the trace (`gen_ai.response.model`), which
     already happens in `trace.succeed`.
   - Do not backfill historical `model_runs` rows. Note the discontinuity in the PR description.
2. Sampling (finding 2). Spring Boot 4.1.1 defaults `management.tracing.sampling.probability` to
   `0.1` (confirmed from the module's configuration metadata). Add to `application.yml`:
   `management.tracing.sampling.probability: ${LANGSMITH_SAMPLING_PROBABILITY:1.0}`.
   **VERIFY FIRST:** the existing `management.tracing.export.otlp.enabled` and
   `management.opentelemetry.tracing.export.otlp.*` keys could not be checked against 4.1.1
   metadata. Confirm them in the Spring Boot 4.1 reference; fix the keys if they are wrong.
3. Dependency status (finding 12). In `OperationsSummaryStore.observations`, exclude cancelled model
   runs the same way ingestion runs are excluded: add
   `AND error_code IS DISTINCT FROM 'CANCELLED'` to the `model_runs` branch.
4. Cost metric (AGENTS.md §28). Add `catalyst_llm_estimated_cost_usd_total{operation,model}` to
   `CatalystMetrics.llmCall` as an optional `estimatedCostUsd: Double?` parameter; increment only when
   known. Pass it from both OpenAI providers.

**Tests**
- `OpenAiEventExtractionProviderTest`: change one fixture response to
  `"model": "gpt-4o-mini-2024-07-18"`; assert the recorded run has a non-null cost and
  `model == "gpt-4o-mini"`.
- New `OpenAiPricingTest`: exact match, dated suffix, unknown model.
- `OperationsSummaryStoreTest`: a latest `CANCELLED` model run does not produce `DEGRADED`.
- `CatalystMetricsTest`: cost counter increments only when cost is known.
- New Spring context test asserting the resolved sampling probability is 1.0 with no env override.

**Acceptance**
- With the real response shape, Overview and Models show extraction cost coverage `n/n`.

**Docs:** README env table: add `LANGSMITH_SAMPLING_PROBABILITY`. README metrics list: add the cost counter.

---

## PR 3 — Provider HTTP metrics, timeouts and failure classification

**Findings:** 4 (no provider request metrics), 5 (no timeouts on news clients), 9 (network
failures classified as `INVALID_RESPONSE`, making documents terminal).

**Files**
- `src/main/kotlin/com/catalystradar/adapters/http/RestClientConfiguration.kt`
- `src/main/kotlin/com/catalystradar/adapters/http/HttpProviderErrors.kt`
- All six adapters under `adapters/polygon`, `adapters/finnhub`, `adapters/openai`
- `PolygonProperties`, `FinnhubProperties`, `OpenAiProperties`, `application.yml`

**Changes**
1. Metrics (finding 4). The static `RestClient.builder()` bypasses Boot's observation wiring.
   Keep the prototype scope but build from Boot's customizers: inject `RestClientBuilderConfigurer`
   and return `configurer.configure(RestClient.builder())`. **VERIFY FIRST:** confirm the class name
   and package in Spring Boot 4.1 (it moved to the `spring-boot-restclient` module). The result must
   emit `http.client.requests`.
   - Cardinality and secrets: Polygon and Finnhub put the API key in a query parameter. Every call
     must use a URI **template** with variables (`.uri("/v2/reference/news?ticker={t}&apiKey={k}", ...)`)
     so the `uri` tag is the template, never the expanded URL. Assert this in a test.
   - Polygon `get(url)` for `next_url` takes a full URL: tag it with a fixed template by setting the
     `URI_TEMPLATE` request attribute, or build the request from the cursor value with a template.
2. Timeouts (finding 5). Give every adapter explicit connect and read timeouts via its properties
   class (`connect-timeout`, `read-timeout`; defaults 10s / 30s for Polygon and Finnhub; keep the
   existing 10s / 120s extraction and 10s / 60s embedding values). No bare numeric constants in
   adapters; move the OpenAI constants into `OpenAiProperties` too.
3. Classification (finding 9). Add `mapTransportError(provider, e: RestClientException)` to
   `HttpProviderErrors.kt`: `ResourceAccessException` (I/O, timeout, connection refused) →
   `ProviderException.TemporaryUnavailable`; anything else → `InvalidResponse`. Use it in all six
   adapters in place of the direct `InvalidResponse` construction. Always pass `provider = ...`
   (the Polygon and Finnhub adapters currently omit it on 5xx and invalid responses).
4. Polygon `next_url` requests must include the API key (PR 4 starts using this path).

**Tests** (WireMock)
- Per adapter: a response delayed beyond the read timeout yields `TemporaryUnavailable`, not
  `InvalidResponse`. Use a short timeout injected through properties.
- `PolygonErrorMappingTest`: malformed JSON still yields `InvalidResponse`.
- `PipelineServiceTest`: an extraction timeout leaves the document `RETRYABLE_ERROR` with
  `TEMPORARY_UNAVAILABLE`, and it completes on the next cycle.
- A context test with `SimpleMeterRegistry`: after one stubbed Polygon call, an
  `http.client.requests` timer exists, and no tag value contains the API key.

**Acceptance**
- `/actuator/prometheus` exposes `http_client_requests_seconds` for all three providers.
- A hung provider fails the call within the configured read timeout.

**Docs:** README "Observability": name the provider metrics. README env table: timeout properties.

---

## PR 4 — Follow provider pagination during ingestion

**Finding:** 8 (only the first page per ticker chunk is fetched; the rest is dropped while the run
reports `SUCCESS`).

**Files**
- `src/main/kotlin/com/catalystradar/application/ingestion/IngestionService.kt`
- `src/main/kotlin/com/catalystradar/application/ingestion/IngestionProperties.kt`
- `src/main/kotlin/com/catalystradar/application/operations/OperationalErrors.kt`
- `src/main/kotlin/com/catalystradar/observability/CatalystMetrics.kt`
- `application.yml`

**Changes**
1. In `runProvider`, loop per chunk: fetch, register articles, then re-request with
   `cursor = page.nextCursor` until it is null.
2. Bound it (§24, §29): new `catalyst.ingestion.max-pages-per-chunk` (default 10, validated 1..100).
3. When the bound is hit with a cursor still present: stop that chunk, set the run's error code to a
   new `WINDOW_TRUNCATED` code if no earlier error exists, and finish the run `PARTIAL`.
   Add the message "Provider returned more pages than the configured limit; some articles in the
   window were not fetched." to `OperationalErrors`.
4. **Decision — do not trigger the fallback provider for `WINDOW_TRUNCATED`.** Today any `PARTIAL`
   primary run triggers the fallback (`IngestionService.ingestCycle`). Truncation is not a provider
   failure and Finnhub costs one call per ticker. Exclude this code from the fallback condition.
5. In `OperationsSummaryStore.observations`, exclude `WINDOW_TRUNCATED` from failed observations so
   the provider is not shown as degraded.
6. Metric: `catalyst_ingestion_window_truncated_total{provider}`.
7. Guard against a provider repeating the same cursor: stop and treat as truncated.

**Tests**
- `IngestionServiceTest`: two pages for one chunk → both registered, run `SUCCESS`.
- Page bound hit → `PARTIAL`, `errorCode == "WINDOW_TRUNCATED"`, fallback not invoked.
- Repeated cursor terminates.
- `PolygonNewsProviderTest`: following `next_url` sends the API key (pairs with PR 3).
- `IngestionIdempotencyTest` still passes: re-running the same window adds nothing.

**Acceptance**
- A stubbed three-page window stores all three pages' articles in one cycle.
- Truncation is visible on the provider run row and as a metric.

**Docs:** README ingestion section and `docs/operations-backoffice.md` error-code table:
`WINDOW_TRUNCATED` and `max-pages-per-chunk`.

---

## PR 5 — Ingestion run records: recovery, counts, duration

**Findings:** 7 (orphaned `RUNNING` ingestion runs never repaired), 15 ("new" includes unqueued
documents), 6 (duration metric missing on failure, no status tag).

**Files**
- New migration `src/main/resources/db/migration/V8__add_ingestion_run_unresolved_count.sql`
- `persistence/ingestion/IngestionRunRow.kt`, `IngestionRunStore.kt`
- `persistence/operations/OperationRunStore.kt` (`interruptUnfinished`, `ingestion` query)
- `application/ingestion/IngestionService.kt`
- `application/operations/OperationsModels.kt`, `api/dto/OperationRunResponses.kt`, `api/dto/RunResponses.kt`
- `observability/CatalystMetrics.kt`
- `ui/operations.js` (provider-run outcome text only)

**Changes**
1. Recovery (finding 7). In `OperationRunStore.interruptUnfinished`, also close ingestion runs:
   set `status='FAILED'`, `error_code='UNFINISHED_PREVIOUS_RUN'`, the matching message, and leave
   `finished_at` NULL (consistent with how interrupted operation runs are recorded).
   Scope: at startup (`kind == null`) all `RUNNING` ingestion runs; in `begin(kind)` only those whose
   `operation_run_id` belongs to an interrupted run of that kind. Do not add an `INTERRUPTED` value
   to `IngestionStatus`.
   - `OperationsSummaryStore.observations` already ignores rows with NULL `finished_at`; add a test
     that a recovered run does not count as a provider failure.
2. Counts (finding 15). `V8`: `ALTER TABLE ingestion_runs ADD COLUMN documents_unresolved INTEGER NOT NULL DEFAULT 0`.
   Keep `documents_new` meaning "stored" (queued + unresolved) for compatibility. Persist the
   `unresolved` counter that `runProvider` already tracks. Expose it as an additive `unresolved`
   field on the ingestion-run responses.
   UI: change the provider-run outcome text to
   `N fetched · N stored (M without a tracked company) · N duplicates`.
3. Duration (finding 6). Record `metrics.ingestionRun` exactly once per run on every exit path,
   including the generic `RuntimeException` and cancellation paths. Add a `status` tag
   (`success|partial|failed|cancelled`). Use a `try/finally` with a status variable rather than
   repeating the call in each branch.

**Tests**
- `OperationRunRecorderTest` / `OperationsSchemaTest`: a `RUNNING` ingestion run is closed on recovery.
- `IngestionRunStoreTest`: unresolved count round-trips. `OperationsSchemaTest`: V8 applies after V7.
- `UnresolvedCompanyIngestionTest`: run reports `added == 2`, `unresolved == 1` for one queued and one unresolved.
- `CatalystMetricsTest`: timer recorded with `status=failed` when the provider throws a non-provider exception.
- `ui/operations.test.js`: outcome text.

**Acceptance**
- After a simulated crash, no ingestion run stays `RUNNING` past the next startup.

**Docs:** `docs/operations-backoffice.md` provider-run fields; README metrics list (status tag).

---

## PR 6 — Pipeline outcome consistency

**Findings:** 14 (failed cycles return zeroed counters), 6 (no `cancelled` cycle metric; retry
counter misnamed).

**Files**
- `src/main/kotlin/com/catalystradar/application/pipeline/PipelineService.kt`
- `src/main/kotlin/com/catalystradar/observability/CatalystMetrics.kt`

**Changes**
1. In the outer `catch (e: RuntimeException)` of `execute`, build the `PipelineResult` from the
   `counts` variable already in scope (documents, events, companies) instead of zeros. Status stays
   `FAILED`; `error` stays the safe message.
2. In the `CancellationException` branch, call `metrics.pipelineCycle("cancelled")` before rethrowing.
3. Retry counter: `catalyst_document_processing_retry_attempts_total` is incremented when a retry is
   **scheduled**. Add `catalyst_document_processing_retries_scheduled_total` with the same
   increments, and keep the old counter for one release. Mark the old name deprecated in the README
   metrics list and in a comment that states the reason and the removal condition.
4. Do not change the run-status decision table or the recorder.

**Tests**
- `PipelineServiceTest`: force a failure in the scoring phase after two documents completed; the
  returned result has `documentsCompleted == 2` and matches the persisted run row.
- `PipelineObservabilityTest`: cancellation increments `status=cancelled`.
- `CatalystMetricsTest`: both retry counters increment together.

**Acceptance**
- The synchronous `POST /internal/ingestion/runs` response and the run row agree on counters for a failed cycle.

**Docs:** README metrics list.

---

## PR 7 — Public read consistency

**Findings:** 10 (timeline truncates snapshots newest-first and transitions oldest-first), 11
(`events7d` has two definitions), 16 (Radar includes inactive companies), 18 API part (a 7-day
change of 0 also means "no baseline").

**Files**
- `persistence/catalyst/StateTransitionRepository.kt`, `CatalystSnapshotStore.kt`
- `api/publicapi/CatalystController.kt`, `api/dto/CatalystResponses.kt`
- `persistence/discovery/DiscoveryStore.kt`, `api/dto/DiscoveryResponses.kt`
- `application/catalyst/CatalystViewService.kt`

**Changes**
1. Timeline (finding 10). Make both lists "newest `limit` rows in the window":
   - `StateTransitionRepository.findWindow`: select `ORDER BY transitioned_at DESC, id DESC LIMIT :limit`.
     Keep the response order ascending by reversing in `CatalystSnapshotStore.findTransitions`, so
     consumers see no ordering change.
   - Fetch `limit + 1` for both lists; add additive response fields `snapshotsTruncated` and
     `transitionsTruncated` to `TimelineResponse`.
2. `events7d` (finding 11). One definition for both endpoints: **distinct canonical clusters whose
   `discovered_at` falls in the 7 days ending at the snapshot's `asOf`.**
   - `DiscoveryStore.recentEventCounts`: window per company relative to that row's snapshot `as_of`,
     not the query `asOf`. Keep it one grouped query (join the page's `(company_id, as_of)` pairs via
     `VALUES` or `unnest`), never N+1.
   - `CatalystViewService`: count canonical events with `discoveredAt` in `(asOf - 7d, asOf]`.
     Stop using `eventTimestamp` for this count.
   - This is a count only. It must not touch scoring inputs.
3. Inactive companies (finding 16). Add `c.active` to the `DiscoveryStore.discover` conditions.
   `total` must reflect the filter.
4. Baseline (finding 18). Do not change stored velocities or `scoreChangeSince`. Derive at read time:
   - Discovery: add `EXISTS (SELECT 1 FROM catalyst_snapshots b WHERE b.company_id = s.company_id
     AND b.as_of <= s.as_of - INTERVAL '7 days') AS has_baseline_7d` to the page query.
   - Expose additive `velocity7dBaselineAvailable: Boolean` on `DiscoveryEntryResponse` and on
     `CatalystResponse` (computed the same way via `latestAtOrBefore(companyId, asOf - 7d) != null`).
   - Sorting and the `minVelocity7d` filter stay as they are.

**Tests**
- `CatalystControllerTest`: with more snapshots and transitions than `limit`, both lists cover the
  newest period, both truncation flags are true, and transitions are still ascending.
- `DiscoveryStoreTest`: inactive company excluded and not counted; `events7d` uses the snapshot's
  `asOf`; `has_baseline_7d` true/false cases.
- `CatalystViewServiceTest`: `events7d` equals the discovery value for the same fixture.
- `DiscoveryControllerTest`: new field serialised.

**Acceptance**
- For any company, Radar `events7d` equals company-view `events7d`.
- Existing `/v1` consumers see only added fields.

**Docs:** README API section: the `events7d` definition, the new fields, the active-only ranking.
OpenAPI output is generated; confirm the new fields appear.

---

## PR 8 — Radar and company view honesty

**Findings:** 17 (ranking compares snapshots of very different ages), 18 UI part, 20 (positive /
negative / direct / inferred "summaries" read as a breakdown), 10 UI part.

**Files**
- `ui/leaderboard.js`, `ui/leaderboard.test.js`
- `ui/app.js`, `ui/app.test.js`
- `ui/styles.css` (only if a new pill class is needed)
- `ui/README.md`

**Changes**
1. No baseline (finding 18). In `leaderboard.js render` and the Radar preview in `app.js`: when
   `velocity7dBaselineAvailable === false`, render `No 7-day baseline` instead of `0.0`, with no
   positive/negative colour class. Same for the company view's 7-day change. Treat a missing field
   (older server) as today's behaviour.
2. Stale snapshots (finding 17). In `leaderboard.js`, add a named constant
   `STALE_AFTER_HOURS = 36`. When a row's `asOf` is older than that relative to the response's
   `asOf`, append a `Stale` marker to the "Saved at" cell, with a `title` explaining that score and
   change were computed at that time and have not decayed since. Add one caption line under the
   table when at least one loaded row is stale: "Some scores were saved more than 36 hours ago.
   Enable daily snapshots to keep the ranking comparable." Do not reorder or hide rows.
3. Summaries (finding 20). In `renderCatalyst`, add one sentence above the four values:
   "Each value maps one subset of contributions to the 0–100 scale on its own, without the
   convergence multiplier. They do not add up to the score." No backend change.
4. Timeline truncation (finding 10). When `snapshotsTruncated` or `transitionsTruncated` is true,
   replace the range note with "Showing the most recent 200 … in this range; older entries are not shown."

**Tests**
- `leaderboard.test.js`: baseline-missing row; stale row and caption; fresh rows unchanged;
  field absent → unchanged output.
- `app.test.js`: company view baseline text; summaries note; truncation note.

**Acceptance**
- A company with no snapshot older than 7 days never shows `0.0` as its 7-day change.

**Docs:** `ui/README.md` Radar section.

---

## PR 9 — Operations backoffice corrections

**Findings:** 19 (sticky pipeline status line masks read errors), 21 (placeholder tiles, caption
scope, hardcoded "single instance", missing concurrency, unrendered fetched/duplicate counts).

**Files**
- `ui/operations.js`, `ui/operations.test.js`
- `application/operations/OperationsModels.kt` (`OperationsConfig`), `OperationsService.config()`,
  `api/dto/OperationsResponses.kt`
- `ui/README.md`, `docs/operations-backoffice.md`

**Changes**
1. Status line (finding 19). In `renderPipelineStatus`, read errors take precedence over
   `pipelineOutcomeText`. Clear `pipelineOutcomeText` when the run it refers to is observed as no
   longer active (track the triggered `runId`; clear in `observePipelineRuns` when that run has
   `active === false`), and when the user navigates to a different run or filter. The
   "already running" and "response unavailable" messages clear on the next successful history load.
2. Placeholder tiles. `clearOverview` must list the same four tiles `renderOverview` renders:
   Last successful ingestion, Waiting documents, Processing documents, Terminal documents.
3. Caption scope. Move "Recorded complete cycles only · captured since …" next to the
   Recalculations row; it does not describe the other activity rows.
4. Fetched and duplicate counts. Render `documentsFetched` and `documentsDuplicate` in the activity
   list ("Fetched from providers", "Duplicates skipped"). They are already in the overview response.
5. Config. Add `pipelineConcurrency` to `OperationsConfig` and its response DTO; render it in
   "Pipeline limits". Change the Deployment row text to "Configured for a single application
   instance" so it reads as configuration, not an observation. Keep the `singleInstance` field.

**Tests**
- `operations.test.js`: after a trigger, a failing runs read shows the error, not "Pipeline started";
  the outcome text clears once the run is inactive; placeholder tile titles equal loaded tile titles;
  fetched/duplicate rows render; concurrency renders.
- `InternalOperationsControllerTest`: config response includes `pipelineConcurrency`.

**Acceptance**
- No stale "Pipeline started" text survives the completion of the run it announced.

**Docs:** `ui/README.md` Overview and Access bullets; `docs/operations-backoffice.md` config fields.

---

## Not in this stack

- Replacing the in-process single-flight guard, or multi-instance support.
- Backfilling `model_runs.model` or historical costs.
- Changing score, decay, state bands or velocity storage.
- A manual daily-snapshot trigger, document retry controls, or any new UI execution control.
- Finnhub fallback cost (one call per ticker per cycle). Measure with the PR 3 metrics first.

## Report back per PR

For each PR, state: which **VERIFY FIRST** items were confirmed or refuted and how; the failing
test that reproduced each finding; test commands run and their results; anything skipped and why.
