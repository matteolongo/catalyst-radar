# Operations backoffice

This explicitly requested optional extension adds `/ops/index.html` to the
existing Spring Boot deployable and PostgreSQL database. The v0.1 intelligence
domain remains API-first. Run one application instance: pipeline/daily guards
and active operation observations are in memory, not distributed locks/leases.
See [startup](../README.md#run-the-poc) and the [UI walkthrough](../ui/README.md).

## Access and routes

All 15 GETs below use `/internal/operations`, require `X-Admin-Key`, and send
`Cache-Control: no-store`, including 400/403/404 responses. Public `/v1/*` keeps
its existing bearer-key rules. Same-origin UI keys live only in tab
`sessionStorage`, never URLs. Configuration projects safe booleans/limits/versions,
not secrets. Bodies load through a dedicated protected route, never feeds/config/
public routes. Raw provider payloads, prompts and model responses are not exposed.
Errors use safe bounded categories/messages rather than arbitrary provider text.

| GET suffix | Parameters / purpose |
| --- | --- |
| `/config` | Read-only provider roles, schedules, limits (including `pipelineConcurrency`), versions, access requirement |
| `/overview` | Activity window; queue, freshness, activity, usage, signals, dependencies |
| `/documents` | `status`, `provider`, `ticker`, `q`, `dueOnly`, `runId`, `ingestionRunId`, optional capture `from`/`to`, `limit`, `cursor` |
| `/documents/{id}` | Metadata, latest processing state and bounded associations; no body |
| `/documents/{id}/body` | Stored text capped at 20,000 characters, `originalCharacters`, `truncated` |
| `/documents/{id}/events` | `limit`, existing event cursor; stored reports/cluster references |
| `/documents/{id}/attempts` | `limit`, cursor; recorded attempts across all dates |
| `/documents/{id}/model-runs` | `limit`, cursor; explicitly source-linked calls across all dates |
| `/runs` | Activity window, `kind`, `status`, `limit`, cursor |
| `/runs/{id}` | Operation detail, recorded counters, active observation and issues |
| `/runs/{id}/issues` | `limit`, cursor; ingestion/document/company issues |
| `/ingestion-runs` | Activity window, `provider`, `status`, `runId`, `limit`, cursor; or exact `ingestionRunId` |
| `/model-runs` | Activity window, model filters, `limit`, cursor |
| `/model-runs/{id}` | Recorded call and explicit provenance |
| `/model-summary` | Activity window, model filters; full totals and bounded groups |

GETs read stored data only: no news, LLM, ingestion, replay or recalculation call.
Dependency badges summarize configuration and observed outcomes without probes.
An unused configured fallback can be Unobserved. Disabled jobs show Off while
retaining known last-success timestamps; enabled jobs without success show Never.
Overdue uses actual elapsed time against cadence times the freshness multiplier.
There is no fabricated next-run countdown.

Missing detail resources return 404 `DOCUMENT_NOT_FOUND`, `OPERATION_RUN_NOT_FOUND`
or `MODEL_RUN_NOT_FOUND`. Bad filters/cursors return 400 `INVALID_REQUEST`;
missing/wrong admin keys return 403 `FORBIDDEN`. Exact ingestion lookup for a
missing ID returns an empty feed.

Parameter conversion failures return `INVALID_REQUEST` with the parameter name
and a correction prompt, without enum class names or internal conversion errors.
The Events UI offers the existing taxonomy-v1 types as a family-grouped selector;
invalid filters remain in the form with an inline correction message.

## Universe candidates (ADR 0002)

`GET /internal/universe/candidates` lists provider tickers named by stored
documents that no configured company explains, ranked by `documentsStored`
then ticker. Parameters: `since` (default 30 days ago, at most 90), `minDocuments`
(default 1), `limit` 1–100 (default 50) and `offset` 0–10,000. Counts are
documents, not events: syndicated copies each count. `documentsNeverQueued` is
the subset stored as unresolved, which tracking the ticker would queue for
extraction. A ticker drops out once a company with that ticker exists. The
ledger is written at registration and was backfilled from `raw_payload` by V10.
The Operations → **Candidates** screen shows it. It requires `X-Admin-Key` and
sends `Cache-Control: no-store`.

`POST /internal/universe/promotions` with `{"ticker": "SMCI"}` tracks a
candidate. It refuses at `catalyst.universe.max-active-companies`
(`CATALYST_UNIVERSE_MAX_ACTIVE`, default 1000) with 409 `UNIVERSE_CAP_REACHED`,
then asks the Polygon tickers reference for an exact, active, `CS` (common
stock) row with locale `us`; anything else is 422 `NOT_A_US_EQUITY`, and a
provider failure is 502 `PROVIDER_UNAVAILABLE` without the provider's payload.
On success (201) it creates the company with `origin = PROMOTED` and
`tracked_since`, links every stored document naming the ticker, queues only the
documents that were never queued, and clears the candidate. Documents already
processed for another company are linked but not re-extracted. Promoting an
existing company answers 200 and changes nothing. The next pipeline run
extracts the queued documents. Replay of a promoted company refuses cutoffs
before `tracked_since`. The Candidates screen offers **Track** with an explicit
confirmation, because every tracked company adds provider and model cost to
each cycle.

### Automatic promotion

`catalyst.universe.auto-promotion.enabled` (`CATALYST_UNIVERSE_AUTO_PROMOTION`)
is `false` by default. When enabled, each pipeline cycle, after ingestion and
before processing, promotes candidates named by at least `min-documents`
(default 5) stored documents within `window` (default `P7D`), at most
`max-per-cycle` (default 3, up to 20) per cycle, through the same service as
**Track**. Their queued documents are extracted in the same cycle. At most
three reference lookups are made per allowed promotion; a rejected ticker stays
a candidate and is checked again in later cycles. The cap or a provider failure
stops the cycle's promotions. Outcomes are counted in
`catalyst_universe_auto_promotions_total{outcome}` and promoted companies carry
`origin = PROMOTED` and `tracked_since`. They are not recorded as pipeline
issues, because they are not failures.

In the same cycle, each symbol it promotes also gets a deep fetch: one
per-symbol request for its news over `deep-fetch-lookback` (default `P7D`, at
most `P30D`, `PT0S` turns it off), the same fetch as
`POST /internal/ingestion/ticker-runs`. The market scan only stored articles
published inside its own window, so this brings in the symbol's recent history
before its documents are extracted. A failed deep fetch is recorded as a
pipeline issue for that company and makes the run `PARTIAL`; the promotion
stands. Together with the market scan this is the automatic loop: scan the
news, rank the symbols it names, track the frequent ones, fetch their history,
extract and score.

### Market-wide scan

`catalyst.ingestion.scan-mode` (`CATALYST_INGESTION_SCAN_MODE`) is `market` by
default: a primary provider that supports it (Polygon) is asked once per cycle
for all news in the lookback window, without a ticker filter, following pages
up to `max-pages-per-chunk` with `page-size` 1000; a cut-off window is reported
as `WINDOW_TRUNCATED`. Articles naming tracked companies are queued as usual;
articles naming only untracked tickers are stored and appear as candidates.
Extraction cost therefore still follows tracked companies only.

With `tickers`, the primary is asked once per active ticker. Polygon's `ticker`
filter takes one symbol: a comma-separated list returns no results with status
`OK`. At Polygon's free 5 requests per minute, 517 tickers take over 100
minutes, longer than the 30-minute interval, so `tickers` suits paid plans
only. The fallback provider, and a primary without market support (Finnhub),
always fetch per ticker.

### Manual per-symbol fetch

The market scan finds symbols; fetching one symbol's news is a manual step.
`POST /internal/ingestion/ticker-runs` with `{"ticker": "TSN", "lookback": "P7D"}`
asks the primary provider for news naming that one ticker from `now - lookback`
to now, whatever `scan-mode` is, falling back per ticker as a cycle does. The
fetched articles are registered like any other (already stored ones count as
duplicates and are not extracted again), then the run drains the processing
queue and rescores affected companies exactly as `POST /internal/ingestion/runs`
does. It shares that run's guard, so it never overlaps a cycle: while one runs
it answers `alreadyRunning: true`. `Prefer: respond-async` returns 202 with the
run id; the run appears under Pipeline runs.

`lookback` is an ISO-8601 duration. Omitted, it is
`catalyst.ingestion.ticker-fetch-default-lookback` (`P7D`); above
`ticker-fetch-max-lookback` (`P30D`), or not a positive duration, the request is
400 `INVALID_REQUEST`. The ticker must be an active tracked company, otherwise
404 `COMPANY_NOT_FOUND` without a provider call: an untracked symbol's articles
would be stored but never extracted, so track it first. Each queue drain takes
at most `catalyst.pipeline.batch-size` documents; a long window over a heavily
covered symbol can leave the rest for later runs. Event timestamps keep the
articles' publication times and `discoveredAt` is the fetch time, so replay
before the fetch does not see them. The Candidates screen has a **Fetch news**
form (last day, 7 or 30 days).

### Guided run

Operations → **Guided run** runs the pipeline one stage at a time, and the
operator chooses what moves to the next stage. Nothing runs until a button is
pressed; buttons that call the news provider or the model say so.

| Step | Route | Calls | Operator choice |
| --- | --- | --- | --- |
| 1. Symbols in the news | `POST /internal/workbench/scans` `{"lookback":"P1D"}` (optional), then `GET /internal/workbench/symbols?since=…` | news provider (scan only); the read is stored data | symbols to look at |
| 2. Track and fetch history | `POST /internal/universe/promotions` per untracked symbol, then `POST /internal/workbench/fetches` `{"tickers":[…],"lookback":"P7D"}` | reference provider, news provider | articles to extract |
| 3. Extract | `POST /internal/workbench/extractions` `{"documentIds":[…]}` | extraction and embedding models | companies to score |
| 4. Score | `POST /internal/companies/{ticker}/recalculate` | none | — |

- The scan is a market-wide scan over the window, whatever `scan-mode` is, up
  to `catalyst.ingestion.market-scan-max-lookback` (`P3D`); omitted, the
  window is `catalyst.ingestion.lookback`. It stores articles and queues those
  naming tracked companies, as a cycle does, but extracts nothing.
- `GET /internal/workbench/symbols` lists every symbol named by stored articles
  published since `since` (default one day, at most 30), tracked and untracked,
  ranked by article count, with `queuedDocuments`: articles waiting for
  extraction for that company (always 0 for an untracked symbol). `limit`
  1–100, `offset` 0–10,000. Counts are documents, not events.
- The fetch takes at most 10 tracked tickers, one provider request each, over
  the per-symbol fetch window (default `P7D`, at most `P30D`). An untracked
  ticker is 404 `COMPANY_NOT_FOUND` before any provider call. It extracts nothing.
- The extraction takes at most `catalyst.pipeline.batch-size` documents and
  extracts only those still waiting (pending, interrupted or retryable, without
  waiting for the retry delay). Others come back with `refused`: `NOT_QUEUED`
  or `ALREADY_PROCESSED`. It is recorded as a manual pipeline run without a
  scoring phase, so scores change only in step 4.
- Every step shares the pipeline's overlap guard: while a cycle or another step
  runs, it answers 409 `PIPELINE_BUSY` and does nothing.
- Articles not chosen stay queued. With the scheduled pipeline enabled, or
  after **Run pipeline now**, the next cycle extracts them; keep the scheduler
  off to decide every extraction by hand.

### Provider rate limits

`catalyst.rate-limits.providers.<name>` sets one request budget per provider
(`polygon`, `finnhub`, `openai`), shared by every client of that provider:
news, reference lookups, market data, extraction and embeddings.

| Setting | Meaning | polygon | finnhub | openai |
| --- | --- | --- | --- | --- |
| `requests-per-minute` | Request starts in any rolling minute; `0` = unpaced | 5 | 60 | 0 |
| `max-retries` | Retries of a 429 before the adapter reports `RATE_LIMITED` | 2 | 2 | 0 |
| `max-retry-wait` | Longest single wait; a longer `Retry-After` is not retried | 65s | 65s | 65s |

Environment overrides: `CATALYST_<PROVIDER>_REQUESTS_PER_MINUTE` and
`CATALYST_<PROVIDER>_MAX_RETRIES`. Compose passes the requests-per-minute
variables through. A 429 waits for `Retry-After`, or one minute without it.
OpenAI 429s are not retried in the client because the processing queue already
reschedules them. Pacing blocks the provider call, so a Finnhub fallback over
517 tickers at 60 per minute takes about nine minutes. Waits and retries are
counted in `catalyst_provider_requests_paced_total{provider}` and
`catalyst_provider_requests_rate_limit_retries_total{provider}`.

When a provider failure ends an ingestion run, its code (`RATE_LIMITED`,
`TEMPORARY_UNAVAILABLE`, ...) is the run's error code, even if a document failed
earlier in the run; per-document failures stay in the logs as
`skipping failed document`.

## Filters, windows and cursors

Overview/runs/ingestion/models accept `range=24h|7d`, or a complete ISO-8601 UTC
`from`/`to` pair, never both. Default is the server-resolved last 24h. Windows
are half-open `[from,to)`, maximum seven days; reject partial pairs, `from >= to`
and `to` over five seconds ahead of the server. Runs/ingestion filter start time;
models filter call creation. Resend the returned explicit window on later pages
so it does not slide. Unfinished rows contribute recorded-so-far values only.

Documents default to all capture dates. Either boundary may be absent; they
filter `discoveredAt`, with exclusive `to`. The UI's Captured through becomes
next-day 00:00 UTC. Public Events through dates remain inclusive. The activity
picker affects neither Documents nor the current queue. Repeated statuses are
ORed; other filters combine with AND. States: PENDING, PROCESSING, COMPLETED,
SKIPPED, RETRYABLE_ERROR, TERMINAL_ERROR, UNRESOLVED, NOT_TRACKED. Provider is
`polygon|finnhub`, ticker is normalized, and title `q` is a literal trimmed
substring (1–120 characters, no controls). `runId` selects sources registered or attempted in a cycle through stored ingestion, step, or attempt IDs regardless of discovery date; `ingestionRunId` selects first origin.

Run kinds: PIPELINE/DAILY_SNAPSHOTS; statuses: RUNNING/SUCCESS/PARTIAL/FAILED/
CANCELLED/INTERRUPTED. Model filters combine with AND: `provider=openai`,
`operation=extract|embed`, exact `model` (1–128 characters), boolean `success`,
UUID `documentId`, `attemptId`, `runId`. Run links join through attempts, never
time proximity. Cycle issues include the responsible provider when recorded or
when a historical association is unambiguous; otherwise they say Unknown.

Feeds default to 25 rows; `limit` is 1–100. Envelopes contain `generatedAt`,
`window`, `items`, `limit`, `nextCursor`. Window is null for Documents, attempts,
issues, source-specific calls and exact ingestion lookup. Sort is descending
feed timestamp then UUID, preserving ties. Cursors are opaque context-bound
markers: resend identical normalized filters, parent and resolved window.
Malformed/oversized/changed-context cursors are 400. Document Events preserves
the existing event response/cursor format. Embedded associations carry totals/
truncation flags; paginated child feeds supply the complete recorded trail.

Exact `/ingestion-runs?ingestionRunId={uuid}&limit=25` returns zero/one row with no
window, supporting old origins. It cannot be combined with filters or a cursor.
`/ingestion-runs?runId={uuid}&limit=25` without `range`, `from` or `to` returns
the ingestion runs associated with that pipeline cycle across all dates. This
cycle-scoped feed is paginated and may also filter by `provider` or `status`;
resend the same run ID and filters with each returned cursor. Both all-date
modes return a null `window`. Normal feeds use resolved windows.

Each ingestion run reports `fetched`, `added` (documents stored by the run),
`unresolved` (of those, documents naming no tracked company, stored for audit
but never queued) and `duplicates`. A provider run left `RUNNING` by a crash is
closed at the next startup, or when the next cycle of its kind begins, as
`FAILED` with `UNFINISHED_PREVIOUS_RUN` and no finish time. Such runs are not
counted as provider failure observations.

Examples (keys come from the environment):

```bash
curl -H "X-Admin-Key: $CATALYST_INTERNAL_ADMIN_KEY" \
  'http://localhost:8080/internal/operations/documents?status=RETRYABLE_ERROR&dueOnly=true&ticker=DELL&limit=25'
curl -H "X-Admin-Key: $CATALYST_INTERNAL_ADMIN_KEY" \
  'http://localhost:8080/internal/operations/model-summary?range=7d&operation=extract&success=false'
curl -H "X-Admin-Key: $CATALYST_INTERNAL_ADMIN_KEY" \
  'http://localhost:8080/internal/operations/model-runs?from=2026-10-01T00%3A00%3A00Z&to=2026-10-04T00%3A00%3A00Z&operation=extract&limit=25&cursor=RETURNED_CURSOR'
```

Replace/URL-encode `RETURNED_CURSOR` with the cursor returned for those exact
filters/boundaries. Dates are illustrative requests, not application defaults.

## Queue and costs

Waiting means pending plus retrying; processing is separate. A future retry is
waiting but not due. Due includes PENDING, eligible RETRYABLE_ERROR and recoverable
PROCESSING without an active RUNNING attempt. Active work is excluded from due.
Missing processing means UNRESOLVED without a company link, otherwise NOT_TRACKED.
SKIPPED is not terminal failure. Queue counts cover current state across all dates;
documents, reports, clusters and recalculations are different units.

Model totals are full SQL period aggregates independent of loaded pages. Costs
are estimated recorded USD, not invoices. Null usage/unknown pricing is Unknown;
known zero stays zero. Partial coverage shows known subtotal and known/expected
calls. Empty call sets have known zero token/cost totals and null latency.
Output token coverage includes extraction only; embeddings expect no output tokens.
Latency p50/p95 uses known values. Breakdown caps at 50 groups with
`groupsTruncated` without truncating totals. Failed parsing/validation after a
paid response retains known usage/cost. Recording remains best-effort: recorded
call counts cannot prove all external invocations were captured.

## Ledger and historical limits

Additive Flyway V3 follows V1/V2 without modifying applied migrations. Old source
IDs/data remain readable; legacy origins/attempts/run IDs remain nullable instead
of receiving invented provenance. V3 changes misleading missing-usage zero costs
to null. Attempts are not reconstructed from `attempt_count`; deduplicated sources
keep their original first-ingestion ID. New extraction/embedding calls explicitly
carry source/attempt IDs, with operation correlation through the attempt.

History begins when capture starts. Recalculation totals cover recorded complete
pipeline/daily cycles only. Windows crossing capture start or containing incomplete
cycles are not exhaustive totals. Absent history is unavailable, not evidence of
zero activity. Document extraction completion does not prove company recalculation
succeeded; a linked current snapshot does not prove that source caused its score.

Persisted RUNNING without an in-process active ID is Unfinished. After acquiring
its guard, a later same-kind operation marks old runs/attempts INTERRUPTED with
`UNFINISHED_PREVIOUS_RUN`, leaving real finish times null. Cancellation is rethrown
and guards released: the operation is CANCELLED with incomplete capture, interrupted
document work stays recoverable, and provider ingestion is FAILED/CANCELLED with
recorded-so-far counters. Unfinished records have no invented percentage. Operational
wall-clock start/finish, scoring `asOf`, publication, discovery and creation remain
separate timestamps.

## Execution and analysis limits

The only new UI execution control is existing `POST /internal/ingestion/runs`.
It runs the full pipeline and may incur news/LLM costs; the UI warns before use.
A busy response has `alreadyRunning=true`, null `runId` and no fictitious run.
The UI sends `Prefer: respond-async`: the server then answers `202` with `status=RUNNING`
and the `runId` immediately and continues in the background; progress is read from the
run itself. Without that header the call stays synchronous and returns the final counts.
Daily snapshots have scheduled history, perform no provider calls and have no new
manual endpoint. There is no document retry, scheduler editing, cancel control,
automatic replay, raw payload viewer or trading action.

Company shows its saved snapshot and current-reconstruction notice. Stored
history has at most 200 snapshots and 200 transitions per range. Legacy snapshots have no original driver membership. New pipeline and daily-snapshot valuations preserve their actual calculator contributions; the current explanation remains a reconstruction. The [UI guide](../ui/README.md) records version
constants, state bands and the numbered investigation walkthrough.

## Pipeline valuation trace (`pipeline-trace-v1`)

Flyway V5 adds nullable `trace_version` to operation runs and source documents.
New captured runs and registrations carry `pipeline-trace-v1`; legacy rows remain
null and must show **Detailed trace not recorded**. The version identifies audit
coverage, separately from `scoreVersion` and `taxonomyVersion`. A new run may
process a legacy document: its new attempt steps are recorded, while intake
history remains unavailable. Neither marker proves that an unfinished run completed.

These protected GET endpoints perform stored reads only and return
`Cache-Control: no-store`, including error responses:

| Endpoint below `/internal/operations` | Optional filters | Item DTO |
| --- | --- | --- |
| `/documents/{id}/steps` | `runId`, `attemptId`, `limit`, `cursor` | `DocumentStepResponse` |
| `/documents/{id}/valuations` | `runId`, `limit`, `cursor` | `CompanyValuationResponse` |
| `/runs/{id}/valuations` | `limit`, `cursor` | `CompanyValuationResponse` |
| `/valuations/{id}` | none | direct `CompanyValuationResponse` |
| `/valuations/{id}/contributions` | `limit`, `cursor` | `ValuationContributionResponse` |
| `/companies/{ticker}/valuations` | `from`, `to`, `limit`, `cursor` | `CompanyValuationResponse` |

All feeds use `{generatedAt, window, items, limit, nextCursor}`, default to 25,
and accept `limit` from 1 through 100. Parent feeds have no activity-window
cutoff. Company valuations default to all dates; explicit `from` and `to` must
be supplied together and follow the existing maximum-seven-day window rules.
The company window filters scoring `asOf` with inclusive `from` and exclusive
`to`. Valuations sort descending by `asOf,id`; steps by `startedAt,id`;
contributions by `createdAt,id`. Cursors bind the parent IDs and every filter.
Missing valuation IDs return 404 `VALUATION_NOT_FOUND`; unknown companies,
documents, and runs retain their existing resource errors.

### Recorded document stages

Stages are `SOURCE_NORMALIZATION`, `SOURCE_REGISTRATION`, `COMPANY_RESOLUTION`,
`EVENT_EXTRACTION`, `EVENT_VALIDATION`, `EVENT_NORMALIZATION`, `EVENT_CLUSTERING`,
and `EVENT_PERSISTENCE`, with `sequence` 1–8. Status is `RUNNING`, `SUCCEEDED`,
`SKIPPED`, `FAILED`, or `INTERRUPTED`. A row includes `id`, `operationRunId`,
`sourceDocumentId`, nullable `processingAttemptId` and `attemptNumber`, `stage`,
`sequence`, `status`, `startedAt`, `finishedAt`, `updatedAt`, `durationMs`,
`inputCount`, `outputCount`, `eventsInserted`, `eventsReused`, `errorCode`, and
`errorMessage`. Unknown counts and unfinished durations stay null.

Intake normalization and registration timings are measured around the real
calls and written when their new source row is stored. Their local transaction
also records resolution and queue insertion. A registration rollback leaves no
stored document or fabricated successful trace. Duplicate article candidates
remain ingestion duplicate counts; they do not get new document-stage rows.
Resolution reports the number of linked supported companies, including zero.
Its successful execution with zero matches does not imply extraction ran.

`GET /documents?runId={uuid}` keeps `state` as the document's current global
queue state. It also returns nullable `runAttemptStatus` and `runAttemptNumber`
from the latest attempt explicitly linked to that run, plus `runLastStage` and
`runLastStepStatus` from its latest recorded step. These are selected in the
document feed query, so displaying a cycle outcome requires no per-document
HTTP request. An intake-only source has null attempt fields and its last intake
stage; a legacy association without recorded steps has null step fields.
All four fields are null when the document request has no `runId`, including
the unfiltered document detail. A later cycle cannot overwrite this run context.

Extraction, validation, normalization, clustering, and persistence steps link
to their actual attempt. Invalid candidates reduce validation output counts;
normalization counts reflect accepted companies/timestamps. Irrelevant inputs
show skipped normalization/clustering. Missing supported companies skip
extraction. Later stages that never executed are absent, rather than succeeded.
The persistence stage's successful outcome and inserted/reused counts commit
with the document outcome, events, and attempt. Remote extraction and embedding
calls stay outside that transaction. Failure messages use the bounded safe
operational error catalog. Cancellation and orphan recovery interrupt open
stages with null `finishedAt` and `durationMs`; retry attempts have separate rows.

### Recorded company valuation and evidence

`CompanyValuationResponse` contains `id`, `operationRunId`, `companyId`, `ticker`,
`companyName`, `snapshotId`, nullable `previousSnapshotId`, `asOf`, `createdAt`,
`scoreVersion`, `taxonomyVersion`, nullable `beforeScore`/`beforeState`,
`afterScore`/`afterState`, `velocity1d`, `velocity3d`, `velocity7d`, nullable
`transitionId`, `contributionSum`, `familyCount`, `convergenceMultiplier`,
`rawScore`, `normalizationScale`, `contributionCutoff`, and `contributionCount`.
The previous snapshot is the latest existing snapshot at or before `asOf`,
ordered by `asOf,createdAt,id`. Missing prior state is null, never a zero baseline.
A non-null transition ID refers to the saved transition from before to after.

Snapshots, transitions, valuations, contributions, and their source links commit
in one company recalculation transaction. A company row lock serializes writers;
there is at most one successful valuation per operation run and company. Failed
recalculations stay in the run issue ledger and create no valuation. Direct
recalculation without a run remains supported and creates no trace valuation.

Contributions preserve the actual post-cutoff `CalculatedScore.contributions`.
Each `ValuationContributionResponse` includes `id`, `valuationId`, `eventId`,
nullable `clusterId`, `createdAt`, `ticker`, `eventType`, `family`, `direction`,
`eventTimestamp`, `discoveredAt`, `value`, `sign`, `baseWeight`, `confidence`,
`materialityFactor`, `surpriseFactor`, `sourceQualityFactor`, `directnessFactor`,
`timeDecayFactor`, `supportingSources`, `supportingDocumentsTotal`, and
`supportingDocumentsTruncated`. **`value` is a raw contribution, not final score
points.** The stored sum is multiplied by convergence; normalization then maps
the raw score to the saved score. Neither the API nor UI recalculates a valuation.

Supporting sources are frozen from explicit event/source and cluster memberships
at capture. Each source includes `sourceDocumentId`, the supporting report's
`eventId`, `title`, `provider`, nullable `canonicalUrl`/`publishedAt`,
`discoveredAt`, `evidence` (strings), and `evidenceTruncated`. Display metadata is
capped at 100 distinct documents and 20 facts per source, with facts capped at
1,000 characters. The 100 metadata sources are selected in ascending document
UUID order; the contributing event's own source may fall beyond that display
cap. `supportingDocumentsTotal` counts all distinct frozen source IDs and
`supportingDocumentsTruncated` compares that total with the returned metadata
count. All distinct source IDs remain in an indexed relation table,
so a document beyond the display cap can still find its valuations. Additional
sources never multiply the canonical contribution. The document valuation feed
uses only this frozen relation; same ticker, same cycle, or nearby timestamps
alone cannot create a relationship.

Operational step times, source publication/discovery, scoring `asOf`, and record
creation are separate timestamps. Scoring rules and the existing canonical
input selection are unchanged. Consequently, a backdated live recalculation can
still use an event that was stored after that cutoff; this trace records that
actual input instead of claiming historical eligibility. The contributing
event's own source is always retained in the full frozen relation. Additional
supporting reports require event/source discovery and source publication at or
before `asOf`; a scheduled future occurrence is not treated as a future discovery.
They are captured at write time and never retroactively attached during reads.
Historical replay eligibility remains a separate concern from this audit trail.
