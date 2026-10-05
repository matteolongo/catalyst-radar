# Pipeline and Valuation Trace — Design Specification

**Status:** Approved.
**Date:** 2026-10-04
**Project baseline:** CatalystRadar modular monolith, current `main` with the operations backoffice already present.

## 1. Product decision

Extend the operations backoffice into one connected investigation path from a pipeline cycle to its stored source documents, extracted events, and company valuations. A user can inspect each recorded document-processing step and each company recalculation, including the exact deterministic score calculation and its contributing events.

Capture complete trace data from deployment of this change onward. Existing history remains explicitly partial because earlier runs did not persist per-step outcomes or the original event contributions used for company snapshots. Do not reconstruct missing historical causality from nearby timestamps or today's events.

Use the dark, compact, modular Trader dashboard as a visual reference: a consistent sidebar, aligned content grid, clear status colors, and dense but readable panels. Keep CatalystRadar's terminology, behavior, and identity. Do not reproduce trading features or assets.

## 2. Approved boundaries

- Keep Kotlin, Spring Boot, PostgreSQL, Flyway, and the current same-origin HTML/CSS/vanilla-JavaScript UI.
- Keep the existing public `/v1/*` contract and catalyst scoring rules unchanged.
- Add protected administrative reads under `/internal/operations`; require the existing admin key and return `Cache-Control: no-store`.
- GET requests only read stored records. They never trigger ingestion, model calls, replays, or recalculation.
- Keep PostgreSQL as the audit source of truth. Add an additive migration after the current V4 migration; do not edit applied migrations.
- Use only explicit run, attempt, document, event, cluster, company, and valuation IDs to link evidence. Do not infer links from timestamp proximity.
- Preserve `publishedAt`, `discoveredAt`, `createdAt`, scoring `asOf`, and operational step times as separate values. Display times in UTC.
- Preserve current provider and model boundaries. Persist safe operational summaries only, never raw provider payloads, prompts, or model responses.
- Use bounded, deterministically ordered feeds. Add no framework or runtime dependency.
- Do not change source extraction, taxonomy, clustering, score weights, decay, score thresholds, or API filtering behavior.

## 3. Current behavior and gap

The existing backoffice records pipeline-level phase timing and counts, document attempts, model calls, extracted events, and run issues. Document detail presents these in separate tabs. Company detail presents the saved score and a current reconstruction of its explanation. `CatalystService` persists snapshots and transitions; `ScoreCalculator` produces the deterministic total and event contributions before snapshot persistence.

The current records do not preserve each document's individual phase results or the exact event contributions used by each company snapshot. A run's company count or a document's event association therefore cannot establish which document caused a particular historical score. The new trace must make these relationships explicit and preserve that limitation for legacy records.

## 4. Trace data model

### 4.1 Coverage marker

Add a nullable `trace_version` to operation-run and source-document records. Existing rows remain null. New pipeline and daily-snapshot runs set `pipeline-trace-v1`; newly registered source documents set the same version. The UI uses these markers, not a deployment-date guess, to distinguish full run capture from source documents whose intake predates capture.

Keep `score_version` separate: it identifies the deterministic scoring behavior, while `trace_version` identifies the audit-record shape.

### 4.2 Document processing steps

Add `document_processing_steps`, one row per actual document-stage execution. Each row contains:

- UUID, `operation_run_id`, `source_document_id`, and nullable `processing_attempt_id` for stages recorded before an extraction attempt exists;
- a stable stage name and stage sequence;
- status: `RUNNING`, `SUCCEEDED`, `SKIPPED`, `FAILED`, or `INTERRUPTED`;
- actual `started_at` and nullable `finished_at`;
- optional bounded result counts, safe error code, and safe error message;
- creation time and explicit foreign keys to the associated records.

Record the real stored-document flow in this order: `SOURCE_NORMALIZATION`, `SOURCE_REGISTRATION`, `COMPANY_RESOLUTION`, `EVENT_EXTRACTION`, `EVENT_VALIDATION`, `EVENT_NORMALIZATION`, `EVENT_CLUSTERING`, and `EVENT_PERSISTENCE`. The first three steps have no processing attempt; the remaining steps are associated with the attempt that executed them. A stage row describes what the application did; it does not copy raw article or model content. Company valuation remains a distinct per-company record linked through persisted events, rather than a duplicated document-stage row. Existing ingestion-run rows remain the source for provider fetch totals and duplicate counts. Article candidates deduplicated before a `source_documents` row exists are not represented as analyzed source documents.

Begin and finish each stage in narrow local transactions, outside remote provider calls. On restart or cancellation, leave truthful finish times and mark any recorded active stage `INTERRUPTED`; do not invent durations or completion percentages. Preserve existing attempt retry semantics.

### 4.3 Company valuation records

Add `company_valuation_records`, written for each successful company recalculation that belongs to a pipeline or daily-snapshot run. A record links `operation_run_id`, `company_id`, the saved `catalyst_snapshot`, `as_of`, score/taxonomy versions, and the score calculation result. One company is recalculated at most once per operation run, so enforce one successful valuation record per run/company pair.

Persist the prior score/state and prior snapshot ID when the latest eligible snapshot exists, and the new score/state from the linked snapshot. The prior snapshot must be the latest persisted snapshot at or before the valuation's `as_of` that existed before this recalculation; later snapshots are never used. A missing prior snapshot stays null; it is never displayed as zero. Persist the full score summary returned by `ScoreCalculator`: contribution sum, family count, convergence multiplier, raw score, normalization scale, and contribution cutoff. Keep velocity and transition data linked to the saved snapshot/transition records rather than recalculating them in the UI.

Add `company_valuation_event_contributions`, one row per event contribution actually used in that calculation. Each row stores the event and cluster IDs, raw contribution, sign, base weight, confidence, materiality, surprise, source-quality, directness, and time-decay factors. Store these rows in the same transaction as the successful snapshot and valuation record. The score remains the application result; the LLM never calculates it. In the UI, label these as raw contributions: the convergence and normalization steps mean they are not final score points.

Add `company_valuation_contribution_sources`, a foreign-key relation containing every explicit supporting source document ID for each contribution. Freeze these IDs at valuation capture, including documents omitted from capped source-text metadata in the contribution DTO. Historical document-to-valuation membership must remain queryable beyond that display cap.

Scoring failures remain represented by the existing company-scoped operation issue and run counters. The UI shows the failure for that company and cycle, with no successful valuation row or fabricated output.

### 4.4 Provenance semantics

Document timelines link to valuation records through persisted event and evidence relationships. Canonical event contributions remain one-per-canonical-event/cluster, even when multiple documents support the event. The UI distinguishes supporting evidence from a separate score contribution and never multiplies a contribution by the number of reports.

A company valuation records the exact inputs used for that run. A document appearing elsewhere in the same run is not by itself evidence that it caused a score change. If a recorded event has multiple source documents, show all explicit evidence sources and identify the contribution once.

## 5. Administrative read API

Add bounded, cursor-paginated reads for:

- `GET /internal/operations/documents/{id}/steps`, filterable by operation run and processing attempt;
- `GET /internal/operations/documents/{id}/valuations`, filterable by `runId`, using the complete frozen source relationship and bounded pagination;
- `GET /internal/operations/runs/{id}/valuations`, covering all valuation records for that run;
- `GET /internal/operations/valuations/{id}` for one immutable valuation summary;
- `GET /internal/operations/valuations/{id}/contributions` for that valuation's event inputs;
- `GET /internal/operations/companies/{ticker}/valuations`, all-date paginated by default, with optional `from`/`to` using the existing activity-window rules.

Use stable DTOs in `api/dto`, provider-neutral application read models, validated queries/cursors, deterministic timestamp-plus-ID ordering, and the existing admin-key security boundary. A missing history row is not a zero-valued result. Legacy records without `trace_version` return the available existing data and an explicit partial-history indication. Run-scoped and valuation-scoped feeds use the parent ID and are not constrained by the seven-day activity window.

For the run-filtered document feed, keep the document's current global `state` distinct from nullable `runAttemptStatus`, `runAttemptNumber`, `runLastStage`, and `runLastStepStatus`, which describe only the attempt and latest recorded step linked to that run. These run-specific fields are null without `runId`; a later retry must not be presented as this cycle's outcome.

Keep full score contribution lists paginated. Do not load an unbounded company event history to render a page. Do not expose raw extraction/model data as part of a trace response.

## 6. UI structure and navigation

### Shell

Retain the current top-level areas and sidebar. Apply one shared dark palette, typography scale, content width, grid, spacing, and panel treatment across Overview, Pipeline, Documents, Models & costs, Intelligence, and Settings. Remove duplicated or misaligned page headings where the shared page title already supplies the heading. Keep keyboard focus visible and status meaning readable without color alone.

### Pipeline investigation

Keep run history as the entry point. Selecting a run shows its actual phase sequence and counters, then two clear collections: **Documents** and **Company valuations**. Each document row shows its outcome and available step progress. Each valuation row shows company, score/state before and after, as-of time, and any transition.

### Document detail

Make the default detail view a chronological step timeline with stage status, actual times/durations, attempt number, and links to model calls, stored events/clusters, supporting evidence, and affected company valuations. Keep source text behind the existing explicit source-inspection action. Preserve separate detail access for large or sensitive content, and show missing or unrecorded stages honestly.

### Company detail

Add a **Valuations** section to the existing Company analysis. Group exact valuation records by run and show score/state changes, scoring version, and all contributing canonical events. Expand a contribution to see its factors and source evidence. Keep the current saved snapshot and current reconstructed explanation clearly distinct from recorded per-run valuations.

### Legacy and incomplete history

For runs without `trace_version`, show “Detailed trace not recorded for this run” and any existing attempts, model calls, events, issues, or snapshots as separate recorded context. For an incomplete or failed run, show the last persisted stage and failure category; do not imply later stages ran. Unknown values remain “Unknown” or “Not recorded,” not zero.

### Responsive behavior

On wide screens, use a compact run list/detail workspace and a consistent grid. On narrow screens, stack the same content into readable cards with labeled values and no clipped controls or required horizontal table scrolling. Keep filters, navigation, and detail actions usable by keyboard and touch.

### History freshness

For the six new trace collections — cycle Documents, cycle Company valuations, company recorded valuations, exact contribution history, document Timeline, and document Valuations — retain loaded history pages while updating current operational metadata. Pause automatic and main-refresh updates only for a collection when older pages are loaded or more than one page is being inspected. Show server read times for retained pages, and provide **Reload latest trace/history** to replace that collection with its latest page and resume normal refresh. Current cycle metadata continues refreshing while a history collection is paused. An explicit history reload preserves a previously verified exact valuation in the same parent and credential context, even when its row is older than the reloaded page. This ruling does not change the inherited refresh behavior of run history, cycle issue/ingestion feeds, or non-trace document tabs.

## 7. Failure handling, transactions, and safety

- External fetch and model calls remain outside long database transactions.
- Record stage start before a remote call and its outcome afterward; retain known model usage and existing retry/error behavior.
- Persist a successful company valuation atomically with its snapshot and contribution rows so the audit cannot disagree with the saved score.
- Keep failed company recalculations in the existing issue ledger and preserve incomplete run capture.
- Bound and sanitize operational error text using existing error policies.
- Protect all new operational reads with admin access and no-store responses; changing or clearing keys continues to clear protected UI data.
- Do not use ticker/document/run IDs as Micrometer tags.

## 8. Acceptance conditions

1. A newly captured run and newly registered source document identify their trace version; legacy runs and source documents remain visibly partial without backfilled trace.
2. A stored document's timeline reports the actual outcome and timestamps for every stage that ran, including skipped, failed, retried, or interrupted stages.
3. Every successful company recalculation in a recorded pipeline or daily-snapshot run has one immutable valuation record linked to the saved snapshot and scoring versions.
4. A valuation's persisted calculation summary and event-contribution rows reproduce the recorded score-v1 calculation without invoking a model or recalculating in the UI.
5. A repeated report for one canonical event can be shown as supporting evidence but does not create a second score contribution.
6. A document-to-company path is shown only through stored document/event/evidence relationships; temporal proximity alone creates no link.
7. Failed scoring appears as a company issue with no fabricated score; missing prior scores and unknown details remain null/unknown.
8. Run, document, valuation, and company screens link to one another through explicit IDs and remain usable on desktop and narrow screens.
9. New reads require the admin key, are paginated/no-store, and cause no external or paid work.
10. Existing public API responses, score-v1 behavior, taxonomy, extraction, and retry semantics remain unchanged.

## 9. Implementation scope

This design requires one additive Flyway migration after V4; recording in the existing registration, pipeline, event-persistence, and catalyst-scoring application flows; protected operations read models/DTOs/routes; and focused changes to `ui/index.html`, `ui/app.js`, `ui/operations.js`, `ui/styles.css`, and UI/API documentation. It adds no provider, service, or frontend dependency. The work is limited to traceability and its presentation.
