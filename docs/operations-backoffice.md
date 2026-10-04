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
substring (1–120 characters, no controls). `runId` selects sources attempted in
a cycle regardless of discovery date; `ingestionRunId` selects first origin.

Run kinds: PIPELINE/DAILY_SNAPSHOTS; statuses: RUNNING/SUCCESS/PARTIAL/FAILED/
CANCELLED/INTERRUPTED. Model filters combine with AND: `provider=openai`,
`operation=extract|embed`, exact `model` (1–128 characters), boolean `success`,
UUID `documentId`, `attemptId`, `runId`. Run links join through attempts, never
time proximity.

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
history has at most 200 snapshots and 200 transitions per range. Original
per-snapshot scoring-driver membership is not captured; contextual events are
not original causal evidence. The [UI guide](../ui/README.md) records version
constants, state bands and the numbered investigation walkthrough.
