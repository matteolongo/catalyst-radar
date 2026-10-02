# CatalystRadar Analysis UI Implementation Plan

**Status:** Implemented on `codex/catalyst-analysis-ui`, based on `main`.

> For agentic workers: Use superpowers:executing-plans to implement this handoff task by task. Keep each task reviewable and preserve the current operator workflows.

**Goal:** Turn the existing operations dashboard into a small research workspace where an analyst can discover a company, understand its current CatalystState, inspect the events and evidence behind it, and see its persisted score/state history.

**Architecture:** Keep the page served by Spring Boot and keep the current dependency-free HTML, CSS, and vanilla JavaScript setup. Use the existing versioned API for discovery, company details, catalyst history, and events. Add only backward-compatible read-model fields needed to explain the current label and connect drivers to safe source metadata. Kotlin remains the only authority for scoring and state decisions.

**Tech Stack:** Existing HTML, CSS, vanilla JavaScript, Spring Boot/Kotlin REST API, JUnit, and Node built-in test runner. Use inline SVG for the history chart; add no UI or chart dependency.

**Spec:** This handoff, AGENTS.md, docs/architecture.md, and docs/implementation-v0.1.md. This handoff records the explicit product request to add an analysis UI even though the v0.1 architecture originally excluded a UI.

## Product intent and current-state audit

Treat “label” as the existing CatalystState: NORMAL, WATCH, BUILDING, CATALYZED, or HIGH.

The current page at /ops/index.html is an operator POC. It stacks pipeline controls, ingestion runs, model runs, and five discovery leaders in one page. Discovery is hard-coded to limit=5, rows do not open a company view, and the UI does not call the company catalyst, timeline, or company-event endpoints. Its tables require a 42rem minimum width and scroll horizontally on narrow screens. Keep the operations features, but make them a separate destination from analysis.

Much of the requested information already exists in the API:

- Discovery supports state, minScore, minVelocity7d, sector, sort, limit, and offset.
- The company catalyst response has score/state, 1/3/7-day velocity, positive/negative/direct/inferred summaries, event counts, three top drivers, versions, and asOf.
- The company timeline returns persisted snapshots and state transitions.
- Company and global event feeds return event attributes and quoteOrFact evidence, with cursor pagination.
- Internal endpoints expose ingestion and model-run history.

The analysis UI is therefore mostly a presentation and navigation gap. A small additive API read-model change is needed because a top driver currently contains only eventId/type/direction/contribution, the state threshold rule is not returned, event responses expose a source document ID but not its safe display metadata, and cross-company event results do not return the ticker needed for a company link.

## User experience to build

1. Open on **Discover**, not the operations tables.
2. Filter and sort the bounded discovery results; selecting a ticker opens its analysis view.
3. On the company view, show the current score and label, the exact score range for that label, data freshness, velocity, the strongest score drivers, and the evidence for each driver.
4. Show stored score snapshots as a historical chart and state transitions as markers. Add a company event timeline below the chart.
5. Put cross-company event search and existing run/model diagnostics on separate **Events** and **Operations** destinations.
6. Keep the experience readable on desktop and mobile. Keep the UI copy in English for consistency with the existing POC; do not add localization infrastructure.

### Historical explanation limitation

The timeline stores past scores, states, and state transitions; it does not store a per-snapshot driver breakdown. The internal replay endpoint can re-extract documents using current model/extractor code and returns no driver breakdown; it can perform model and embedding work. In this first UI delivery:

- Show persisted historical score/state points and transition markers.
- Show events discovered in the selected period as contextual events.
- Do not state that a contextual event caused a particular historical score or label.
- Do not call replay automatically or include replay in the first delivery. Historical “why at this date?” analysis requires a separate, explicit replay design with its version/cost limitations visible.

## Existing contracts and required data boundaries

Use these routes as the starting contract:

| UI use | Route | Existing behavior |
|---|---|---|
| Discovery | GET /v1/discovery/catalyzed | Filters: repeated state, minScore, minVelocity7d, exact sector, sort=SCORE or VELOCITY, limit 1..100, offset |
| Company metadata | GET /v1/companies/{ticker} | Existing company read |
| Current catalyst | GET /v1/companies/{ticker}/catalyst | Current score, state, velocity, aggregates, top three drivers, versions, asOf |
| Score/state history | GET /v1/companies/{ticker}/timeline?from=&to=&limit= | Persisted snapshots and transitions; limit 1..200 |
| Company events | GET /v1/companies/{ticker}/events?limit=&cursor= | Bounded, cursor-paginated event/evidence feed |
| Cross-company events | GET /v1/events?ticker=&family=&type=&direction=&from=&to=&limit=&cursor= | Bounded, cursor-paginated event search; current from/to filters apply to discoveredAt |
| Operations | GET /internal/ingestion/runs and GET /internal/model-runs | Admin-key-protected run histories |
| Trigger pipeline | POST /internal/ingestion/runs | Admin-key-protected and potentially slow; keep only in Operations |

Use the existing request helper and credential rules: send the admin key only to /internal/* and the public API key only to /v1/* when configured. Keep calls same-origin. The UI may format and sort display data, but it must never calculate score, choose a state, select the canonical event for scoring, or infer a missing timestamp.

## Global constraints

- Read AGENTS.md, docs/architecture.md, and docs/implementation-v0.1.md before changing API/domain behavior.
- Keep the current modular monolith and static UI. Do not add React, a chart package, a separate service, or a database migration for this work.
- Use server responses for score, state, state bands, driver contributions, and canonical score drivers. Never copy scoring thresholds or scoring formulas into JavaScript.
- Keep eventTimestamp, publishedAt, discoveredAt, and snapshot asOf distinct in names and displays.
- Never expose source document body or raw provider payload. A short stored quote/fact and safe metadata (title, provider, publishedAt, canonicalUrl) are allowed.
- Do not present CatalystState as a buy/sell recommendation or as a probability/confidence rating.
- Keep public API additions backward-compatible. Preserve all current operations controls, run history, model run cost totals, and credential isolation.
- Discovery sector filtering is an exact match in the current API; use a text input unless an existing sector-options source is found.
- Use accessible labels, keyboard-operable navigation and controls, visible focus, loading/error/empty states, and layouts that work at 375px viewport width without horizontal scrolling in analysis views.

---

### Task 1: Add the analysis shell and a real discovery workspace

**Files:**

- Modify: ui/index.html
- Modify: ui/app.js
- Modify: ui/styles.css
- Test: ui/app.test.js

**Interfaces:**

- Consumes: the existing same-origin api(path, options) helper and GET /v1/discovery/catalyzed.
- Produces: destinations named Discover, Events, and Operations; a company-analysis route encoded as ?view=company&ticker=DELL; filterable discovery results that open that company route.

- [x] Add failing UI tests proving that initial load opens Discover, selecting a discovery ticker navigates to its company route, filter values are serialized into the discovery request, and pagination changes offset while preserving filters.
- [x] Run the focused UI tests with node --test ui/app.test.js and confirm the new behavior tests fail before implementation.
- [x] Replace the single stacked page hierarchy with a compact app header and accessible navigation for Discover, Events, and Operations. Keep company analysis as a drill-down with a visible return-to-results action.
- [x] Use the URL query route ?view=company&ticker=DELL for a selected company. Update browser history on navigation and restore the previous view on popstate/back navigation.
- [x] Build the discovery filters for state (multi-select), minimum score, minimum 7-day velocity, exact sector text, and sort by SCORE or VELOCITY. Include bounded page size and previous/next controls.
- [x] Build the discovery query with URLSearchParams. For example, selecting CATALYZED and HIGH, score 65, velocity 0, Technology, velocity sort, and page 1 must request:

  ~~~text
  /v1/discovery/catalyzed?state=CATALYZED&state=HIGH&minScore=65&minVelocity7d=0&sector=Technology&sort=VELOCITY&limit=20&offset=0
  ~~~
- [x] Render ticker, company name, sector, score, state, 7-day velocity, events7d, scoreVersion, and per-company asOf. Show the response asOf separately as the discovery query timestamp.
- [x] Make each result keyboard-operable and open its company analysis route. Do not leave the leader list hard-coded to five entries.
- [x] Make discovery controls and result cards/table usable at 375px. Keep wide operations tables scrollable inside their own panels rather than forcing the whole page to scroll horizontally.
- [x] Run node --test ui/app.test.js and verify the new navigation, query, pagination, credential-routing, and empty/error-state tests pass.

### Task 2: Add a truthful, additive explanation read model

**Files:**

- Modify: src/main/kotlin/com/catalystradar/application/catalyst/CatalystService.kt
- Modify: src/main/kotlin/com/catalystradar/application/catalyst/CatalystViewService.kt
- Modify: src/main/kotlin/com/catalystradar/application/scoring/ScoreCalculator.kt
- Modify: src/main/kotlin/com/catalystradar/persistence/event/EventStore.kt and its repository/query only as needed to join safe source metadata
- Modify: src/main/kotlin/com/catalystradar/api/dto/CatalystResponses.kt
- Modify: src/main/kotlin/com/catalystradar/api/dto/EventResponses.kt
- Test: src/test/kotlin/com/catalystradar/application/scoring/ScoreCalculatorTest.kt
- Test: src/test/kotlin/com/catalystradar/application/catalyst/CatalystViewServiceTest.kt
- Test: src/test/kotlin/com/catalystradar/api/publicapi/CatalystControllerTest.kt
- Test: src/test/kotlin/com/catalystradar/api/publicapi/CompanyEventsControllerTest.kt
- Test: src/test/kotlin/com/catalystradar/api/publicapi/EventsControllerTest.kt

**Interfaces:**

- Consumes: existing CatalystSnapshot, CatalystEvent, ScoreConfig, EventEvidence, SourceDocument fields, and canonical-event selection.
- Produces: additive score-band and aggregate-calculation data, top-driver factor details and evidence, safe source metadata, and company ticker/name on cross-company event results. Existing response fields and scoring behavior remain unchanged.

- [x] Add failing unit tests for every existing state boundary: 25, 45, 65, and 80; prove the returned state-band explanation matches the stateForScore result at each boundary and immediately below it.
- [x] Add a state-band value in the Kotlin scoring/application layer and make both stateForScore and the catalyst response derive from the same single table. Preserve existing ranges exactly: NORMAL [0,25), WATCH [25,45), BUILDING [45,65), CATALYZED [65,80), HIGH [80,100]. Do not change state behavior or move thresholds to JavaScript.
- [x] Add a failing ScoreCalculator test asserting each signed event contribution reports the exact factors already used by score-v1: sign, baseWeight, confidence, materialityFactor, surpriseFactor, sourceQualityFactor, directnessFactor, timeDecayFactor, and raw contribution.
- [x] Preserve the existing final score formula and version. Return driver factors from the same calculation result rather than recomputing them in the controller.
- [x] Extend the calculation result with the pre-convergence contribution sum, familyCount, convergenceMultiplier, existing converged rawScore, normalizationScale, and contributionCutoff. Return these values under one additive scoreCalculation object so the UI can explain the aggregate without implementing the formula itself.
- [x] Extend each returned top driver with the matching event’s family, timestamps, clusterId, factor details, and evidence quoteOrFact. Keep the existing top-three ordering and contribution semantics. Label contributions as raw event contributions; do not call them normalized score points.
- [x] Add safe source metadata for the matching source document: title, provider, publishedAt, canonicalUrl, and sourceDocumentId when present. Add it to company event responses as well so event cards and driver cards share one source DTO.
- [x] Add ticker and company name to cross-company event responses using the existing company relationship. Add ticker to company-scoped event responses if the shared DTO requires it. This lets the Events view link a result to the right company without guessing from source text.
- [x] Do not return source body, raw_payload, credentials, or extraction prompts.
- [x] Add tests proving ticker/name and the same source metadata appear on events/drivers, absent optional metadata serializes safely, evidence remains intact, state bands match existing boundary behavior, and score-v1 result values do not change.
- [x] Keep the schema unchanged. All source metadata fields already exist in source_documents; add a focused read query if the current event query only returns sourceDocumentId.
- [x] Run focused Kotlin tests for ScoreCalculatorTest, CatalystViewServiceTest, CatalystControllerTest, and CompanyEventsControllerTest. Then run the full test suite required by the repository instructions.

**Score explanation semantics required by the UI:**

The event contribution and final company score are different quantities. Current score-v1 calculates a signed raw contribution per canonical event as:

~~~text
sign(direction)
* baseWeight
* confidence
* (materiality or 1.0)
* (surprise or 1.0)
* sourceQualityFactor
* directnessFactor
* timeDecayFactor
~~~

For time decay, age is based on eventTimestamp, falling back to the calculation asOf only inside the scoring engine. The event contribution cutoff determines which contributions participate. The calculation sums included contributions, multiplies by the configured convergence multiplier, and maps positive converged raw score to 100 * raw / (raw + normalizationScale), clamped to 0..100; non-positive raw score maps to zero. Therefore:

- Show company score and raw event contribution as different values.
- Do not draw positiveScore/negativeScore/directScore/inferredScore as four slices of one pie: direction and directness are overlapping dimensions, and these values are independently normalized summaries.
- Keep any factor-by-factor view collapsed under a “Scoring details” disclosure so the main company page stays readable.

### Task 3: Build the company analysis view, history chart, and evidence timeline

**Files:**

- Modify: ui/index.html
- Modify: ui/app.js
- Modify: ui/styles.css
- Test: ui/app.test.js

**Interfaces:**

- Consumes: GET /v1/companies/{ticker}, GET /v1/companies/{ticker}/catalyst, GET /v1/companies/{ticker}/timeline, and GET /v1/companies/{ticker}/events.
- Consumes Task 2 additive stateBand, top-driver details, and source metadata.
- Produces: company overview, accessible history chart, score explanation, and evidence-linked clustered event feed.

- [x] Add failing tests for valid company detail rendering, unknown ticker, independent endpoint failure, no history, one snapshot, transition markers, top-driver quote/source display, and cursor pagination.
- [x] Run node --test ui/app.test.js and confirm the new company-view behavior tests fail.
- [x] Fetch company metadata, current catalyst, initial timeline window, and first event page when a ticker opens. Keep independent panels usable if one request fails; show a local panel error rather than blanking the whole company page.
- [x] Build a company heading with name/ticker, current score, state pill, server-provided score range, 1/3/7-day velocity, totalEvents, events7d, asOf, scoreVersion, and taxonomyVersion.
- [x] Show positive/negative and direct/inferred summaries as four separate labeled values or paired bars. Do not use one pie chart.
- [x] Build a score history chart from actual timeline snapshots, sorted by asOf ascending. Mark state transitions at their persisted timestamps. Provide 30-day, 90-day, and maximum-returned ranges using from/to and limit=200. Label the maximum-returned view as capped at 200 snapshots/transitions; do not claim that it contains all persisted history.
- [x] Do not interpolate missing snapshots or invent a smooth daily series. If there is one or zero snapshots, show the available values and a clear no-history/insufficient-history message instead of a misleading line.
- [x] Provide a screen-reader label and a compact data table equivalent for chart date, score, state, and asOf. Keep tooltips tied to actual snapshots/transitions.
- [x] Build a “Why this state?” panel using the server-provided stateBand and top three drivers. For each driver show direction, type/family, raw contribution, event date, first-discovered date, evidence quote, source title/provider/publishedAt, and source link only when canonicalUrl exists.
- [x] Treat `explanationStatus=RECONSTRUCTED_SCORE_MATCH` as score/version agreement for a reconstructed explanation, not proof of the original snapshot event membership. Label drivers as reconstructed and disclose that the saved snapshot does not retain its original driver list. Show directional/directness summaries and factor detail only in this status; otherwise explain why attribution is unavailable and do not render drivers.
- [x] Put numeric factor detail under a collapsed Scoring details disclosure. Display the returned factors and exact score version; do not recalculate score math in JavaScript.
- [x] Render canonicalUrl as an external link only when its parsed scheme is http or https; use rel=noopener noreferrer. Render plain source text when the URL is missing or has another scheme.
- [x] Build the event timeline from bounded company event pages. Group records with the same clusterId visually as reports about one catalyst, retain each loaded event’s evidence/source, and use event.id as a standalone group for unclustered events. Let the backend remain authoritative about which canonical event affects score.
- [x] Label eventTimestamp as event date, publishedAt as source publication date, and discoveredAt as first captured. Never substitute one for another.
- [x] Add a Load more action using nextCursor. Preserve the loaded cluster grouping when appending later pages.
- [x] Run node --test ui/app.test.js and verify company rendering, date semantics, chart edge cases, evidence metadata, error states, and pagination pass.

### Task 4: Add cross-company event exploration and retain operations tools

**Files:**

- Modify: ui/index.html
- Modify: ui/app.js
- Modify: ui/styles.css
- Modify: ui/app.test.js
- Update: ui/README.md
- Update: README.md operator dashboard section

**Interfaces:**

- Consumes: GET /v1/events and the existing internal run-history and pipeline-trigger routes.
- Produces: filterable market event view and a preserved Operations view.

- [x] Add failing tests for event search filters, cursor continuation, company navigation from an event, operations key gating, and preservation of pipeline/model-run data.
- [x] Run node --test ui/app.test.js and confirm the new Events/Operations tests fail.
- [x] Build the Events view with ticker, family, type, direction, first-captured from/to, page size, and Load more controls. Use only the existing /v1/events parameters and bounded cursor. The current from/to filter is on discoveredAt; label it accordingly. Keep eventTimestamp, publishedAt, and discoveredAt distinct in each result.
- [x] Link each event’s ticker to its company analysis route. Do not add an unbounded event-history call.
- [x] Move pipeline controls, ingestion runs, model runs, and cost totals into Operations without changing their request routes or removing any existing capability.
- [x] Show promptVersion, extractorVersion, and sourceDocumentId from existing model-run responses in a compact row-detail disclosure; do not log or render prompts or document bodies.
- [x] Keep the admin credential attached only to /internal/* and the public API credential attached only to /v1/*. Preserve sessionStorage clear behavior.
- [x] Run node --test ui/app.test.js and verify Events, Operations, and credential-isolation tests pass.

### Task 5: Finish responsive behavior, documentation, and acceptance review

**Files:**

- Modify: ui/styles.css
- Modify: ui/app.test.js
- Update: ui/README.md
- Update: README.md

- [x] Review all analysis screens at 1280px and 375px widths. Confirm there is no page-wide horizontal scrolling, all controls remain reachable, and operations table scrolling is confined to each table.
- [x] Check keyboard navigation, visible focus, labels for every input, chart text equivalent, escaped API strings, loading states, empty states, forbidden responses, and panel-level errors.
- [x] Verify the event cards never expose source body or raw provider payload and that all rendered API strings are escaped.
- [x] Update UI documentation to explain the Discover, Company, Events, and Operations views, credential requirements, exact-sector filtering, historical-data limits, and the distinction between raw event contribution and normalized company score.
- [x] Run node --test ui/app.test.js, the full Kotlin test suite, and git diff --check. Visually smoke-test the served page at /ops/index.html using the local application.
- [x] Review the diff to confirm no dependency, migration, scoring behavior, API decision logic, trading recommendation, or unrelated operations workflow was introduced.

## Deferred follow-up

Do not add this to the first delivery:

- Historical point-in-time driver explanations. Existing snapshots do not retain driver breakdowns. The current replay endpoint re-extracts at a cutoff, may call the LLM and embedding provider, and returns no drivers. A separate request must define explicit user-triggered replay, version/cost disclosure, and a response that contains historical driver evidence before the UI presents a past score’s exact cause.
- Full source-cluster aggregation across all syndicated documents. The company event UI may group the records already loaded by clusterId, but it must not claim that a loaded page contains every supporting source.
- Social sentiment, SEC/IR ingestion, trading recommendations, portfolio workflows, or other features excluded by AGENTS.md.

## Completion criteria

- Discover is the default view and filters the existing bounded discovery endpoint; results open a company analysis route.
- The company view explains the current CatalystState with server-owned range data, up to three deterministically ranked drivers, raw event contribution factors, stored evidence, and safe source metadata.
- The history chart uses only persisted snapshots and state transitions, with no fabricated points or false historical causal claims.
- Company and cross-company event feeds are bounded, cursor-paginated, evidence-linked, and clear about event, publication, and discovery times.
- Operations features remain available with unchanged key isolation.
- UI tests, Kotlin tests, responsive review, and diff checks pass.
