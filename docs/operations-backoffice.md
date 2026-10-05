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
| `/config` | Read-only provider roles, schedules, limits, versions, access requirement |
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
