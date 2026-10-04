# Pipeline valuation trace implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development to execute this plan, with scoped implementation and review.

**Goal:** Make the operations UI coherent and let an administrator follow a cycle through every recorded document stage and the exact persisted company score calculation.

**Architecture:** Extend the existing modular monolith with additive PostgreSQL audit tables and protected read APIs. Persist actual executions and calculator outputs; retain the current deterministic rules and public API. Use the existing plain JavaScript UI and lifecycle guards.

**Tech Stack:** Kotlin, Spring Boot, JDBC, Flyway, PostgreSQL, HTML/CSS, plain JavaScript.

**Spec:** `docs/superpowers/specs/2026-10-04-pipeline-valuation-trace-design.md`

## Global Constraints

- Trace version is `pipeline-trace-v1`; existing rows keep a null version and are shown as partial history.
- Keep raw inputs, provider boundaries, score/taxonomy versions, retry semantics and point-in-time timestamps distinct.
- Do not change score rules. Save the actual post-cutoff `CalculatedScore.contributions`, not a reconstructed calculation.
- Snapshot, valuation record and contributions commit in the same transaction. One successful valuation per operation run and company.
- Reads have no side effects. Internal endpoints use existing admin authentication and `Cache-Control: no-store`.
- Page sizes are 1 through 100. Cursor context includes parent IDs and filters; ordering uses time and UUID. Parent feeds have no implicit activity-window cutoff.
- Link documents, events, clusters and valuations only through stored identifiers and evidence. Never infer lineage from matching times or tickers alone.
- Use safe bounded failure messages. Cancellation/restart marks open stages interrupted without inventing finish times.
- No new dependencies or providers. No live provider calls for implementation checks. Preserve unrelated files in the original checkout.
- Per the developer instruction, do not add or run tests in this turn. Compile production code, check JavaScript syntax, and inspect the changed behavior statically; state this limit in the completion report.

## Task 1: Persist and expose real document stages and company valuations

**Own:** New migration `src/main/resources/db/migration/V5__pipeline_valuation_trace.sql`; backend files under `src/main/kotlin/com/catalystradar`; backend-facing documentation in `docs/operations-backoffice.md`.

1. Add nullable `trace_version` to `operation_runs` and `source_documents`. Set it for newly captured runs/documents without backfilling old rows.
2. Create `document_processing_steps` with UUID, explicit run/document/attempt foreign keys, stage, sequence, status, started/finished/updated times, bounded result counts and safe error fields. Enforce attempt/document/run consistency. Stages: `SOURCE_NORMALIZATION`, `SOURCE_REGISTRATION`, `COMPANY_RESOLUTION`, `EVENT_EXTRACTION`, `EVENT_VALIDATION`, `EVENT_NORMALIZATION`, `EVENT_CLUSTERING`, `EVENT_PERSISTENCE`. Statuses: `RUNNING`, `SUCCEEDED`, `SKIPPED`, `FAILED`, `INTERRUPTED`.
3. Create `company_valuation_records` with run/company/snapshot IDs, asOf, creation time, score/taxonomy versions, prior eligible snapshot ID/score/state (nullable), after score/state, stored velocities, transition information, contribution sum/family count/convergence/raw score/normalization scale/cutoff. Unique successful run/company pair.
4. Create `company_valuation_event_contributions` with valuation/event/cluster IDs and the actual calculator factors/value/sign. Add indexes for the parent feeds and deterministic pagination.
5. Add focused models and JDBC stores in the existing operations boundary. Reuse the existing `OperationsCursor`, safe errors and `OperationsPage` envelope. Extend existing DTOs with traceVersion; add an operations resource error for missing valuation if needed.
6. Instrument `SourceDocumentRegistrationService` and its `IngestionService` caller: measure normalization, registration and company resolution for newly saved documents; preserve duplicate candidate counters. Record unresolved company resolution truthfully. Use the operation run context passed explicitly.
7. Instrument `PipelineService`: record extraction, validation, normalization, clustering and persistence around the real calls. Split validation from preparation in `EventNormalizationService` without changing existing `prepareDocument` behavior. Irrelevant inputs show an explicit skip; later stages never executed must not appear succeeded. Persist successful final stage with `DocumentOutcomePersistenceService`'s existing transaction. Preserve retries and cancellation.
8. In `CatalystService.recalculate`, accept optional `operationRunId: UUID? = null`; choose the previously stored snapshot eligible at or before asOf, then persist the actual calculation alongside the new snapshot. Pass the run ID from pipeline and daily snapshot flows. Keep unrelated direct recalculation behavior compatible.
9. Extend existing orphan recovery to interrupt active document stages. Preserve partial capture if the process stops.
10. Expose the following GET endpoints under `/internal/operations`:
    - `/documents/{id}/steps?runId=&attemptId=&limit=&cursor=`
    - `/runs/{id}/valuations?limit=&cursor=`
    - `/valuations/{id}`
    - `/valuations/{id}/contributions?limit=&cursor=`
    - `/companies/{ticker}/valuations?from=&to=&limit=&cursor=`
11. Contribution DTOs include explicit supporting document IDs (bounded with truncation metadata), ticker/event classification, source evidence and factors; valuation summaries include operationRunId, companyId/ticker/name, snapshot IDs, times, before/after values, calculation totals and transition. Document steps include attempt/run IDs and real result counts. Clearly document the actual DTO field names for the UI implementer.
12. Update admin API documentation with capture boundaries, timestamp semantics, legacy partial history and raw contribution units.
13. Compile production Kotlin and resources using `./gradlew.bat compileKotlin processResources --no-daemon`. Review all touched constructors/call sites, SQL null handling, cursor context and transaction boundaries. Do not add/run tests.
14. Commit only this task's files and write a report including DTO examples, compile evidence and any integration concerns.

## Task 2: Build the connected dark operations UI

**Own:** `ui/index.html`, `ui/styles.css`, `ui/operations.js`, `ui/operations-model.js`, `ui/app.js`; optional small new UI module only if it makes the existing lifecycle simpler; `build.gradle.kts` resource includes if needed; `ui/README.md`.

1. Read Task 1's actual DTO report first. Use existing request/auth/revision/AbortController guards and pagination helpers. Clear sensitive views on credential changes; reject stale responses and keep nested selections consistent.
2. Use a consistent dark palette inspired by the reference: canvas `#081118`, panels `#101a24`/`#17202a`, border `#263747`, foreground `#f3f6f8`, muted `#a7b2bd`, accent `#7fb894`, success `#26c99a`, error `#ef6168`, warning `#e7a63c`. Keep current product functions.
3. Align sidebar, page toolbar, section/card spacing, typography, table headers and filter controls. Wrap each filter label with its control, remove duplicate page headings, show full-width document list when no document is selected. Use min-width:0 and intentional breakpoints to avoid clipped data.
4. Selected pipeline run shows existing real phases/counters, a paginated Documents collection and paginated Company valuations collection. Navigate to a document's run-scoped steps or a valuation's exact calculation. Show company scoring issues when no successful valuation exists.
5. Default document view shows the chronological stage timeline, grouped by intake/run/attempt as appropriate. Each stage has readable name, status, actual timing, results and safe failure details. Distinguish run/attempt contexts. Load more steps without discarding prior pages. Retain attempts/model/event/source views; source body remains explicit/lazy.
6. Label null trace versions as partial history. Do not replace missing values with zero. Interrupted stages expose the last recorded state, without fake duration/completion percentage.
7. Add a Company valuations section, all-date pagination by default, and valuation detail with before/after score/state, velocities, transition, recorded calculation totals and expandable per-event factor rows. Clearly label raw contribution values and the normalization equation. Link supporting documents and clusters by stored evidence. Show saved snapshot separately from current reconstructed explanation.
8. Preserve URL navigation and browser back/forward: use explicit run/document/attempt/valuation/ticker identifiers. Selecting a valuation from a cycle must retain the cycle context; loading another cycle or company must clear stale details.
9. Provide semantic headings, visible focus, aria-expanded/pressed/selected where appropriate, readable status labels beyond color, touch targets, narrow-screen cards and desktop split panels.
10. Update UI documentation with the cycle/document/company path and partial-history meaning. Ensure every included script is packaged by `processResources` and referenced in the right order.
11. Run production JavaScript syntax checks using `node --check` for changed scripts; compile resources. Inspect rendering in a local browser if available without calling live providers. Do not add/run tests.
12. Commit only this task's files and report what changed, syntax/asset checks and any unresolved preview limitations.

## Task 3: Integrate and finish

**Own:** Cross-task integration corrections and documentation only where review identifies an actual gap.

1. Review the backend and UI task diffs for spec compliance and quality after each implementation task.
2. Check endpoint/DTO/UI consistency, run and attempt scoping, unknown/legacy behavior, security lifecycle and resource packaging.
3. Review the complete branch, address material findings in one focused correction, then inspect the correction.
4. Compile production code/resources; run syntax checks on production UI scripts and `git diff --check`. No tests added or run.
5. Leave the completed implementation on the attached `codex/pipeline-valuation-trace` branch; preserve the original checkout's unrelated documents. Report actual implementation, checks and historical audit limits in Italian.
