# CatalystRadar internal backoffice desktop design

Date: 2026-10-22. Status: implementation handoff proposal grounded in the agreed product direction and current repository. This document specifies a refinement of the existing optional backoffice, not a new customer product.

## 1. Decisions and scope

The user chose an internal backoffice, Overview as the landing screen, company leaderboards sorted by catalyst indicators, simple visualizations, and no budget-management feature. The user delegated detailed presentation decisions. This specification resolves those decisions so an implementing agent does not need the chat.

Interpret “without API for now” as **no new API surface or backend changes in this phase**. The existing backoffice necessarily reads the existing same-origin application endpoints. Keep that integration. Do not replace working data with mocks, remove authentication, introduce direct database access from the browser, or create a separate backend. Local test fixtures are for verification only.

Global constraints:

- Internal operator desktop experience only; no mobile product or customer onboarding flow.
- Overview is the default route.
- Reuse existing endpoints and keep backend behavior unchanged.
- Use existing HTML, CSS, and JavaScript served by Spring Boot.
- Add no production frontend dependencies or separate frontend build.
- Keep the existing dark appearance and explicit UTC timestamps.
- Use server sorting; never present a locally sorted page as a global leaderboard.
- Highest score and highest 7-day score change are the only leaderboard sorts in this phase.
- Preserve access isolation, request cancellation, bounded pagination, and historical coverage notices.
- The existing manual pipeline run is the only execution action.
- Do not add budgets, spending limits, alerts, trading features, or portfolio features.
- Run only affected tests locally; full backend and frontend suites belong in CI.
- Run relevant static checks and the production packaging build.

“Proposed” throughout means the target design described here. “Existing” means supported by repository code or documentation inspected on this date. Recheck contracts when implementing if the repository has changed.

## 2. Product intent and success criteria

The workspace supports three jobs: assess operational freshness, identify companies with notable catalyst profiles, and investigate evidence and recorded processing/calculations. The same internal user may perform all three jobs. Do not invent a role administration system.

An operator should be able to:

1. Open Overview and distinguish healthy-looking recent activity, overdue work, and missing observations.
2. Open Companies and rank the supported company set by score or 7-day score change.
3. Open a company and distinguish its saved state from a current reconstruction and recorded historical calculation.
4. Move from an issue to its document, attempt, model call, run, and valuation using actual stored relationships.
5. Return to the previous list without losing applied filters or investigation context.
6. Recognize unavailable, truncated, unfinished, denied, and failed data without confusing them with zero activity.

A score/state describes configured catalyst rules. It is not a recommendation, price target, confidence probability, or forecast return. “Valuation” in the existing backend means a saved catalyst calculation; the UI should say **Recorded calculation** where that avoids confusing it with equity fair value. Existing routes and internal identifiers retain `valuation` naming.

## 3. Technology decision

### Recommended stack

| Layer | Decision | Reason |
|---|---|---|
| Runtime and delivery | Existing Kotlin/Spring Boot application | Already packages the backoffice at `/ops/index.html`; one deployable |
| Markup | Semantic HTML | Native forms, links, tables, details, and buttons cover this interface |
| Styling | Existing CSS with shared variables and layout classes | No CSS framework, runtime styling, or theme subsystem |
| Behavior | Plain JavaScript using the existing script/IIFE pattern | Reuses current routing, security, polling, and tests |
| Data reads | Existing same-origin `api()` helper | Keeps credentials and request restrictions in one place |
| History chart | Existing inline SVG renderer | Small bounded dataset; no chart library needed |
| Tests | Existing `node:test`/VM harness and targeted existing backend tests only if relevant | No new test runner needed |
| Build | Existing Gradle resource copy and `bootJar` | No npm build, bundler, transpiler, or additional deployment |

This is a context-specific maintenance decision: reuse the working stack, rather than claiming vanilla JavaScript is always the smallest choice for every application. Do not migrate this change to React, Next.js, Vue, Svelte, HTMX, Tailwind, a grid library, or a chart library. HTMX/server templates would require a different rendering contract; a framework would add a migration without removing the existing operational obligations.

Modern browsers support native JavaScript modules, but converting the existing script system is unnecessary for this redesign. If a later independently scoped refactor needs modules, browsers can load relative module URLs without a bundler; see [MDN JavaScript modules](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Guide/Modules).

Small code means a small set of explicit responsibilities, not compressing everything into one file. Add one bounded, stateless leaderboard presentation/query module shared by Overview and Companies. Keep route/request ownership in `app.js`; preserve operational state machinery in `operations.js`. Do not create a generic component framework, table schema DSL, application store, or repository abstraction in the frontend.

## 4. Existing capability boundary

Read `docs/architecture.md`, `docs/implementation-v0.1.md`, `docs/operations-backoffice.md`, and `ui/README.md` before implementation. The operations contract is more specific than baseline statements excluding a general UI.

Verified discovery contract:

| Item | Existing behavior | UI decision |
|---|---|---|
| Endpoint | `GET /v1/discovery/catalyzed` | Reuse |
| Filters | repeated `state`, `minScore`, `minVelocity7d`, exact `sector` | Expose these only |
| Sorting | `SCORE` descending or `VELOCITY` descending; ticker tie-break | Label Highest score / Highest 7-day change |
| Pagination | `limit` 1–100; `offset`; total returned | Companies defaults to 20; options 20/50/100 |
| Row values | ticker, name, sector, score, state, velocity7d, events7d, versions, asOf | No per-row follow-up requests |
| Query time | response `asOf`, generated by server for each request | Distinguish query time from each company's snapshot time |
| 1/3-day changes | Available on company catalyst detail, not discovery rows | Show on company detail only |
| Decline ranking | No ascending sort | Defer; do not reverse one loaded page |
| History availability for velocity | Numeric response has no per-lookback provenance flag | Do not infer complete lookback coverage from a zero |
| Sector vocabulary | No verified complete sector enumeration endpoint | Use exact-match text input, not a fabricated dropdown |

`events7d` counts distinct cluster-or-event identifiers discovered within the server's recent window. Label “Events discovered · 7d”; explain that it is not article count or proof of current scoring membership. Do not display a leaderboard sparkline: row history is absent and fetching every company's history would create unnecessary requests.

Offset pagination is not a frozen snapshot. Rankings may move between reads as new snapshots arrive. Keep the query-time label and say “Rankings can change between page reads” beside the pager. Do not invent a snapshot token.

## 5. Navigation and route structure

### Sidebar

Fixed 208 px sidebar on desktop, with this order:

1. Overview
2. Research group: Companies, Events
3. Operations group: Pipeline, Documents, Models & costs
4. Settings at the bottom

Group captions are plain muted text, not accordion controls or routes. All navigation items remain visible. Access problems are explained on the destination; hiding navigation is not authorization. Keep the wordmark linked to Overview.

| Visible destination | Route | Notes |
|---|---|---|
| Overview | `?view=overview` | Also default for an empty or unrecognized view |
| Companies | `?view=discover` | Change visible label; preserve established route |
| Company analysis | `?view=company&ticker=DELL` | Contextual detail; Companies remains highlighted |
| Events | `?view=events` | Existing event route |
| Pipeline | `?view=pipeline` | Preserve `?view=operations` alias |
| Documents | `?view=documents` | Preserve existing selection/filter query fields |
| Models & costs | `?view=models` | Preserve existing call/filter query fields |
| Settings | `?view=settings` | Preserve safe return-to-investigation behavior |

Do not create a standalone top-level Valuations page. Existing exact valuation panels are reached from a company, run, or document.

### Header

Page title on the left; last-read information beneath it. On the right: activity range only for Overview/Pipeline/Models, UTC label, application availability, Refresh, and the existing operational auto-refresh checkbox where relevant.

Keep “Application reachable” separate from provider observations. The health indicator cannot prove that news providers or models are healthy.

Activity range defaults to 24h and offers 7d. Retain existing explicit `from`/`to` deep-link handling; do not introduce another date-picker library. Clearly label which panel uses a period. Queue counts are current across all dates. The Overview leaderboard uses latest stored snapshots and is independent of the operational period.

### Navigation behavior

- Use real links with useful local `href`s. Preserve normal Ctrl/Cmd-click and middle-click to open another tab; intercept only ordinary primary clicks.
- Keep local route validation. Return destinations must never navigate off origin.
- On navigation, focus the page/detail heading. On closing a pane, return focus to the selection link if available; otherwise focus the list heading.
- Browser Back/Forward restores the applied list context. Form drafts are distinct from applied filters.
- Selecting a company uses a full detail page. No extra company preview drawer in this phase.
- Preserve existing IDs and routes where useful; remove obsolete `navIntelligence` wiring together with its markup rather than leaving invisible duplicate navigation.

## 6. Visual system

Use the existing dark palette as the foundation:

```css
:root {
  color-scheme: dark;
  --canvas: #081118;
  --panel: #101a24;
  --panel-raised: #17202a;
  --text: #f3f6f8;
  --muted: #a7b2bd;
  --border: #263747;
  --accent: #7fb894;
  --success: #26c99a;
  --warning: #e7a63c;
  --failure: #ef6168;
  --radius: 8px;
}
```

Use a system font; no remote fonts. Base text 14px with 1.5 line height; main title 24px/600; section title 16px/600; secondary metadata 12px. Scores may use 28px on company detail, but list numbers stay 14px. Right-align numeric columns and use tabular numerals. Keep IDs monospace in expanded metadata, not as primary titles.

Spacing uses 4/8/12/16/24/32px. Main content padding 24px; panel padding 16px; panel gaps 16px; row padding approximately 10px vertically and 12px horizontally. Preserve comfortable interactive targets around 40–44px. Avoid fixed row heights when content wraps.

Use subtle borders, modest corners, and a single accent. Avoid gradients, decorative charts, large metric tiles, heavy shadows, animated backgrounds, and icons that need a package. Text labels are sufficient for most actions.

States must include words. Positive/negative changes also include signs. Operational failures use danger styling; catalyst state labels do not reuse failure colors to imply HIGH is a problem. Use neutral pills with increasing accent emphasis for catalyst states. Show server-returned state; never derive it from a rounded display score.

### Desktop layout

- Primary review sizes: 1440×900 and 1920×1080. Also verify 1280×800.
- Main shell uses available desktop width, capped around 1920px for legibility.
- Documents split at viewport widths of at least 1440px: list approximately 40%, detail 60%, with minimum readable widths around 360px and 560px.
- Below that width, show selected detail above the retained list. This supports smaller desktop windows and zoom; it is not a mobile design.
- Use a fixed CSS split, not a draggable splitter or persisted pane sizing.
- Tables may scroll horizontally within their own labeled wrapper. Never clip action columns or create whole-page horizontal overflow at the primary review sizes.
- Use normal document scrolling. Avoid nested tall scroll regions for every panel.
- At increased browser zoom, controls wrap and panels stack. Essential functionality must remain reachable.

## 7. Reusable UI patterns

### Tables

Use semantic `table`, `caption`, `thead`, `tbody`, and column header scopes. A ticker, run, or document title is an explicit link; do not turn every cell into an unlabeled click target. Selected rows use a subtle highlight and a text/accessible selection indication.

Sorting is driven by one native select in the filter form. Do not add sort arrows to unsupported columns. Mark the currently sorted supported column with `aria-sort="descending"`. No ascending toggle, column dragging, saved layouts, export controls, or client-side full-dataset search.

Keep table loading/error/empty messages outside the row data and inside the same panel. Empty results do not contain fake rows or example market data.

### Filter forms

Always use visible labels. Use native inputs and selects. Explicit Apply filters is the commit point; typing does not issue requests. Enter submits. Reset restores defaults and fetches page one once. Change of filters resets the cursor/offset and dependent selections.

Invalid values remain in the form, get `aria-invalid`, and show an adjacent correction message. Do not issue a request for a known invalid filter. Do not silently clamp invalid score values into a different user request.

### Panel states

| State | UI behavior and copy |
|---|---|
| First load | “Loading companies…” or the relevant resource; reserve useful space |
| Refreshing identical query | Keep authorized data visible with “Refreshing…” and its last-read time |
| Changed query/context | Clear old results or unmistakably mark them obsolete; never pair old rows with the new query label |
| No matching rows | “No companies match these filters.” Provide Reset filters |
| No captured history | “No recorded history is available for this period.” Do not say nothing happened |
| Transient read error | Safe message and Retry; retain already loaded continuation pages |
| Authorization denied | Clear affected protected data immediately and offer Settings |
| Missing resource | Resource unavailable/not found and return link; do not treat as an empty list |
| Partial data | Identify precisely which metric, collection, or history is incomplete |
| Unfinished run/stage | Recorded-so-far values; unknown finish/duration; no percentage |

A failed continuation retains the existing cursor for retry. An initial/query-changing failure clears misleading result ranges. Credential changes override all retention rules and clear affected cached/rendered data.

### Numbers and dates

Scores and score changes: one decimal for scanability; preserve full returned precision in recorded calculation factors. Changes use `+` for positive values and `−` for negative values, with “points” in the label. A missing value is “Unavailable” or “Not recorded,” depending on context. A known zero remains `0.0`.

Do not infer velocity coverage that the response does not provide. Explain once on Companies/Company: “Score changes are returned by the server; this view does not establish complete lookback coverage.”

All operational and historical times display UTC with exact date/time available. Do not silently substitute event, publication, discovery, creation, scoring, or execution timestamps for one another.

## 8. Overview screen

### Layout, top to bottom

```text
Sidebar | Overview                          Activity [24h] Refresh
        | Last successful read ... UTC
        | [Ingestion freshness] [Waiting / due] [Processing] [Terminal failures]
        | Needs attention                  | Latest catalyst leaders
        | signal + context + inspect link  | Sort [Highest score]
        |                                  | compact company table (5 rows)
        |                                  | Open Companies
        | Recorded activity in selected period
        | Document coverage                | Observed dependencies
```

Freshness tiles use server-provided scheduling/freshness state. Under waiting, distinguish due now from future retries. Processing is separate. Each count has a unit and scope. Missing company associations and missing processing state appear in coverage, not terminal failures.

Needs attention lists server-provided signals with human-readable names and precise links. Do not invent severity thresholds. “No recorded attention signals” is preferable to “Everything healthy.”

The compact leaderboard uses a single request for five rows, with score or 7-day-change sort. Columns: Company, State, Score, 7-day change, Saved at. The company name is secondary text under ticker. Caption: “Latest stored company snapshots; independent of the activity period.” No per-company fetches.

The leaderboard is an independent public-data panel: an administrative read failure does not imply its data is forbidden, and a public-key failure does not destroy an authorized operational summary. Show each panel's own access state. It must not wait for slow operational calls.

For simplicity, the new leaderboard loads on entering Overview, changing its sort, or explicit Refresh. It does not add its own polling timer. Label the existing checkbox “Auto-refresh operations · 30 seconds” so the scope is honest. The leaderboard always displays its own query time.

The operational period picker must not change the leaderboard sort or add a period filter to its request. Open Companies carries the selected sort, with page one and default filters. Selecting a ticker records Overview as the return destination.

Recorded activity keeps existing counts and estimated model cost with coverage. Cost is secondary information, not a prominent budget tile. Keep dependency observations and their observation times; unused configured providers may be Unobserved.

Acceptance: no POST on entry/refresh; at most one leaderboard read per explicit trigger; independent panel failures; existing queue semantics retained; sort link opens the same ranking on Companies.

## 9. Companies leaderboard

Title: Companies. Subtitle: “Rank latest stored company snapshots by catalyst score or 7-day score change.”

Top utility: Company ticker input and Open company. This resolves an already supported ticker through the existing route; it does not fetch external company reference data or modify discovery filters.

Filters in a compact, wrapping form:

| Control | Default | Validation/behavior |
|---|---|---|
| Sort | Highest score | `SCORE` or `VELOCITY`; descending only |
| States | All | Existing native multiple select; OR within states |
| Minimum score | Empty | Finite number 0–100; zero is valid |
| Minimum 7-day change | Empty | Finite signed number; zero and negative values are valid |
| Sector (exact match) | Empty | Trim; preserve exact-match semantics |
| Rows | 20 | 20, 50, or 100 |
| Apply / Reset | — | Apply commits drafts; Reset restores defaults |

Full table columns, in order: Company (ticker/name), Sector, State, Score, 7-day change, Events discovered · 7d, Saved at, Versions. Versions are compact secondary metadata with both score and taxonomy versions. Do not add rank numbers: changing results and offsets would suggest more durable rankings than the contract provides.

The table preserves server order exactly. Highest 7-day change includes zero/negative values when appropriate; it is not labeled “Rising companies” unless the user also applies a positive minimum.

Footer: Results X–Y of Z; Previous/Next; query-as-of UTC; changing-rankings note. Use the returned total. A page beyond the current result set may become empty; keep Previous available when offset > 0 and offer Return to first page. Do not trap the user on an empty later page.

Persist applied query values in the local route: `state`, `minScore`, `minVelocity7d`, `sector`, `sort`, `limit`, `offset`. Route parsing validates them before issuing a read. Public filters never contain credentials. Editing a form does not change applied pagination until Apply. Returning from Company restores applied filters and offset; a fresh read may show changed ranking and must update its query time.

No column sorts for event count, sector, or freshness. No 1/3-day leaderboard columns or largest-decline preset in this phase. Those need a separately approved server contract change.

## 10. Company analysis

Use one full page with a compact section-link row, not another layer of tabs or a preview drawer. Order:

1. Company identity and metadata.
2. Saved catalyst snapshot: score, state, saved-as-of, versions, 1/3/7-day changes.
3. Recorded calculations (existing valuation history and detail).
4. Stored score history and transitions.
5. Current reconstructed explanation, with positive and negative contributions both visible.
6. Contextual company events and evidence.

The return action says Back to Companies / Back to Overview / Back to Documents as appropriate. Keep the verified local return route and investigation selection.

Company identity includes ticker, company name, exchange, sector/industry when present, and active status where supplied. Each independently loaded section can fail without blanking successful sections.

### Recorded calculations

Rows show scoring time, run, before/after score and state, and versions. A missing previous score is “Not recorded,” not zero. Open an existing exact valuation detail. Display raw contribution sum, convergence multiplier, raw score, normalization scale, cutoff, saved after-score, and transition link where recorded.

Expandable contribution rows show event/type/direction, raw contribution, recorded factors, and frozen supporting sources/evidence. Use existing protected endpoints and parent verification. Additional sources do not multiply the canonical contribution. Metadata truncation is explicit; do not infer missing membership from omitted display metadata.

Use this notice: “This calculation records the inputs used at capture. It does not by itself establish eligibility for historical replay.” Never recompute the valuation in the browser.

### History visualization

Reuse the existing SVG plot, height around 180–220px, with score axis 0–100. Show stored observations as points. Keep the stored snapshot/transition table alongside or below it as the accessible exact-value representation. Do not add a chart library, smoothing, prediction, price overlay, or inferred state-change causes.

To avoid implying continuous observations when cadence/coverage is not known, render the simple plot as points without a continuous connecting line. One snapshot is one point; no snapshots means no plot. Exact tooltip/title text includes UTC and score. Transitions are separately listed with their own timestamps even when outside the snapshot extent.

Retain the existing 30d/90d/maximum-returned controls and the cap of 200 snapshots plus 200 transitions per range. “Maximum returned” does not mean complete lifetime history. Range changes ignore stale responses. No replay or recalculation is triggered.

### Reconstruction

Label “Current reconstructed explanation” and explain its relationship to the saved snapshot. Keep the existing mismatch behavior: do not attribute a reconstruction to a saved snapshot whose score/versions do not match. Do not use current events to fill missing historical driver membership.

## 11. Events

Use a dense semantic table with an inline expandable detail row for evidence. Columns: Company, Family/type, Direction, Event time, Discovered at, Source, Cluster reference. Keep the existing taxonomy-family/type selectors and ticker/direction/date filters. Date controls explicitly state the time basis; preserve the current inclusive UTC through-date contract.

The list contains stored event reports. A cluster can have several reports. Keep loaded reports grouped by cluster using existing logic, but label any count as “Loaded reports” unless the server supplies a total. Appending a page can add evidence to an already loaded cluster. Do not claim one table group enumerates all supporting sources.

Expanded content contains available classification attributes, evidence excerpts, publication time, source URL, and an Inspect source action only for a real source document ID. No AI-generated explanation is created by opening the view.

External links must use verified HTTP(S) URLs, open separately with `rel="noopener noreferrer"`, and never carry credentials. Unsupported/unsafe URLs appear as text. Escape titles, evidence, provider text, and identifiers.

A company link preserves the Events filter/cursor context. Source investigation requires administrative access independently from the public event read. Keep loaded evidence after a failed continuation and offer Retry.

## 12. Pipeline

Keep Pipeline cycles / Daily snapshots tabs, status filter, and run list. Use a compact table: Started at, Kind, Status, Recorded outcome, Duration, Capture. A row's status and current active observation are different facts. Persisted RUNNING without observed active work must read Unfinished rather than appearing to be executing forever.

Select a run to open its wide detail above the retained run list; do not squeeze the detail into a narrow drawer. Lead with outcome, safe issues summary, known counts and capture completeness. Follow with existing phase information and explicit paginated Documents, Recorded calculations, Provider ingestion, and Issues sections. Retain existing IDs and expansion state.

Cycle documents show both current processing state and this cycle's recorded attempt/stage outcome. A later successful retry must not rewrite what happened in the selected cycle. Missing attempt/stage fields stay Not recorded.

Provider ingestion table remains a separate collection with provider/status filters. Fetched, registered, duplicate, report, cluster, and company recalculation counts are separate units, not one interchangeable “processed” number.

### Manual execution

Keep Run pipeline now on Pipeline only. Caption: “Runs configured news ingestion and processing. Provider and model charges may apply.” Retain the existing first-run configuration/model explanation and Confirm and run pipeline interaction; do not create a new repeated approval wizard. No budget limit, estimate calculator, or cost forecast.

Disable while the trigger is pending or a pipeline is observed active. A busy response displays “A pipeline is already running” and links only if an actual run ID exists. Never fabricate a history row. If the POST response is lost, say the outcome is unknown and direct the user to refresh runs; do not automatically repeat a potentially executed POST.

No cancel, document retry, scheduler editing, daily-snapshot trigger, or replay control. Inspection and polling remain read-only.

## 13. Documents

Full-width list before selection, fixed split on wide desktops after selection, detail above list on narrower desktops. Use existing filters: title substring, provider, ticker, multi-state, captured-from, captured-through, due-only. Keep explicit run and first-ingestion scope notices above the list with independent remove-scope links.

List columns: Title/provider, linked company tickers, current processing state, captured at. Add selected-cycle attempt/stage information only when scoped to a run. Do not invent a total from a loaded page. All list and child feeds remain bounded.

Detail header: title, provider, current state, publication, captured/discovered time, record creation time where available, and trace-coverage notice. Long titles wrap. IDs sit in expandable metadata. Tabs retain Timeline, Overview, Recorded calculations, Attempts, Model calls, Events, Source. Timeline opens first.

Timeline is a chronological list of recorded stage cards grouped by intake/run/attempt, not a progress bar. Each card shows stage, outcome, actual start, actual finish if present, duration if recorded, input/output counts with units, and safe error. Missing stages are absent, with a coverage notice; never display an unexecuted stage as completed.

Legacy document with new attempts: show new attempt history and explicitly unavailable intake trace. Earlier failed attempts remain visible after current completion. Load older steps fetches one page then orders the loaded records; no automatic history-draining loop.

Source tab requests protected text only on explicit selection; preserve the 20,000-character cap and original-length/truncation notice. Render plain text, never source HTML. Do not request body on list hover, prefetch, or page entry.

Recorded calculations use the frozen source relationship. Same ticker, cycle, or nearby time is insufficient. A directly linked valuation must pass existing parent/membership checks before display. Unknown association gets a coverage/verification message, not a guessed link.

## 14. Models & costs

Keep selected call detail above period summaries so an investigation opened from a document is immediately visible. Detail order: operation/model/outcome, safe error, recorded usage/cost/latency, versions, explicit document/attempt/run links, full identifiers.

Filters: provider, extraction/embedding operation, exact model, success/failure, and existing explicit context IDs. Summary: recorded calls, failed calls, known token usage, estimated recorded cost, and known latency percentiles. No spending goals, forecasts, alarms, or budget controls.

Call table: Created at, Operation, Model, Outcome, Latency, Input tokens, Output tokens, Estimated cost. Use Unknown for unavailable metrics; known zero remains zero. Embedding output tokens are not applicable rather than a missing extraction measurement.

Totals come from full server period aggregates, not the visible call list. Partial coverage says “Known subtotal” and displays known/expected coverage where provided. Groups may be truncated while total aggregates are complete. Recorded calls cannot prove every external invocation was captured. Failed parsing after a paid response can still have known usage/cost.

If the selected call is outside the summary period, retain it and label the mismatch. Do not silently change the period or fabricate activity around it. No prompts, raw responses, or provider payload viewers.

## 15. Settings and access

Place Access for this tab first, Read-only configuration second, and the existing optional guide controls last. Keep the guide non-modal; do not expand it into another onboarding project.

Admin key and public API key remain separate masked inputs. Explain their purposes without printing secrets. Retain save/clear behavior and tab session storage; no remember-me checkbox, URL keys, local-storage credentials, role editor, account system, or key issuance UI.

Configuration shows safe flags, configured providers, schedules, limits, and active versions. Keep secrets and raw settings omitted. API documentation is a secondary technical link, not a main product destination.

On key change/clear, invalidate pending reads and clear affected public/protected caches and rendered data. On any known admin denial, preserve the existing complete protected-cache purge, including hidden Source text and configuration. Public requests remain independently scoped. Returning to a protected screen requires a new authorized read.

## 16. Context, history, and refresh contracts

Use the existing request sequence/credential revision guards. A stale request from a previous screen, company, filter, range, or credential must not repaint the new context.

Operations polling continues every 30 seconds only while visible and enabled. Preserve the current trace-history retention behavior: a one-page collection can refresh boundedly; after Load more or retained growth beyond 25 rows, pause automatic and main-refresh history replacement, retain cursor/rows, and show page-read times. Current document/run metadata may keep refreshing independently.

Reload latest trace/history replaces only that collection with its latest page and resumes normal behavior. Preserve an already verified selected exact valuation when allowed by the existing same-parent/same-credential context. Changed parent or credentials clear both detail and verification. Never automatically load all older pages to prove a relationship.

Context requirements:

| Navigation | Retain |
|---|---|
| Companies → Company → Back | Applied filters, sort, page size, offset |
| Overview leaderboard → Company → Back | Overview period and leaderboard sort |
| Events → Company/source → Back | Filters, loaded evidence, continuation position |
| Run → scoped Documents | Run ID, cycle outcome fields, current-state distinction |
| Document → call → attempt/run | Actual recorded IDs, source selection, tab |
| Company/run/document → calculation | Verified parent relationship, calculation ID, return context |
| Denied access → Settings → return | Safe destination, but no stale protected content |

No record associations may be inferred from proximity in time or matching company alone.

## 17. Accessibility and resilience

- Native controls and semantic tables; no custom dropdown or grid keyboard model.
- Visible focus, logical heading order, skip link to main content, and a meaningful active navigation label.
- Tabs retain keyboard arrow/Home/End behavior and correct selected/controlled-panel relationships.
- Focus must not disappear when polling or replacing a list. Do not auto-open details on hover.
- Loading uses scoped status announcements; reserve alerts for actionable errors, not every poll.
- Never communicate outcome by color alone. Verify text and controls against contrast expectations in the actual browser.
- Respect reduced-motion settings; no animated chart transitions are necessary.
- Long names, IDs, evidence, and messages wrap or truncate with accessible full content.
- The fake DOM tests cannot verify layout, real keyboard behavior, contrast, or focus rendering. Browser review is required.

## 18. Delivery boundaries and verification

Implement incrementally: shell/navigation; shared leaderboard; Companies; Overview leaderboard; company/history; Events; operations layouts; Settings/access; documentation and packaging. Preserve existing functionality throughout. The linked implementation plan maps these to exact files and focused tests.

No backend changes are required by this design. Do not add endpoints to fulfill deferred sorts. Do not alter scoring, taxonomy, clustering, migrations, provider configuration, or extraction prompts.

Final verification must cover primary desktop sizes, keyboard-only investigation, zero/one/many results, mixed positive/negative/zero changes, stale responses, protected access denial, legacy history, interrupted work, source truncation, unknown costs, and return navigation. Use synthetic fixtures or an explicitly safe local environment; never run the paid pipeline merely to verify styling.

## 19. Deferred proposals

Largest-decline ranking, 1/3-day global ranking, more global sort fields, complete sector picklists, saved views, watchlists, alerts, exports, bulk actions, replay, document retry, editable schedules, and role-based access are excluded. No disabled placeholder buttons for them.

If future work needs one of these, first define its server support and product semantics. A frontend redesign must not claim an unsupported capability.

## 20. Source map

- `ui/index.html`: shell, forms, sections, script order.
- `ui/styles.css`: existing palette and layouts.
- `ui/app.js`: public reads, credentials, navigation, discovery/company/events, guide.
- `ui/operations.js`: operational reads, polling, trace history, execution, access cleanup.
- `ui/operations-model.js`: existing query/format helpers.
- `ui/test-support.js` and `ui/*.test.js`: fixture harness and behavior tests.
- `build.gradle.kts`: explicit production asset allowlist.
- `.github/workflows/ci.yml`: existing CI test entry point.
- `src/main/kotlin/com/catalystradar/api/dto/DiscoveryResponses.kt`: discovery fields.
- `src/main/kotlin/com/catalystradar/api/publicapi/DiscoveryController.kt`: allowed request parameters.
- `src/main/kotlin/com/catalystradar/persistence/discovery/DiscoveryStore.kt`: actual sorts, counts, pagination.
- `docs/operations-backoffice.md` and `ui/README.md`: access, lineage, trace, pagination, and historical truth limits.

Companion implementation plan: `docs/superpowers/plans/2026-10-22-internal-backoffice-desktop.md`.
