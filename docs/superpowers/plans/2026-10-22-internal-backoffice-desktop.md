# Internal Backoffice Desktop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task, or superpowers:subagent-driven-development if parallel agent execution is explicitly authorized. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refine the existing internal desktop backoffice with Overview first, readable company leaderboards, and consistent evidence/operations investigation while preserving the current backend and historical limits.

**Architecture:** Keep the existing same-origin HTML/CSS/JavaScript backoffice packaged by Spring Boot. Add one stateless leaderboard module shared by Overview and Companies; retain existing routing, authorization, request guards, operational state, and SVG history machinery. Change presentation incrementally without introducing a frontend framework, new server endpoints, or a separate deployment.

**Tech Stack:** Existing Kotlin/JVM 21/Spring Boot/Gradle application; semantic HTML; CSS; browser JavaScript; inline SVG; Node built-in test runner and current fixture harness.

**Spec:** `docs/superpowers/specs/2026-10-22-internal-backoffice-desktop-design.md`. Read the specification in full before changing implementation. It defines every screen, shared interaction, scope boundary, and copy rule.

## Global Constraints

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

---

## 1. Start here: implementation contract

This is a redesign of an existing backoffice, not a greenfield application. “No API for now” means **no API expansion**; keep the application's existing read endpoints and protected pipeline action. A local static mock replacing working integration is not an acceptable delivery.

The implementation is authorized as a plan only in the originating chat. An executing agent should act on the user's implementation request in its own session. This document does not authorize running a paid pipeline, changing production configuration, or publishing/deploying the application.

Read, in order:

1. Applicable `AGENTS.md` and `C:/Users/Usuario/.codex/RTK.md`.
2. The companion design specification.
3. `docs/architecture.md` and `docs/implementation-v0.1.md`.
4. `docs/operations-backoffice.md` and `ui/README.md`.
5. Existing files listed below and relevant tests before touching each feature.

Reuse a suitable checkout. Inspect status first and preserve unrelated work. At planning time, unrelated untracked `docs/reports/` and October 4 backoffice plan/spec files existed; they are not part of this change. Do not delete, stage, or overwrite them. If isolation is needed, follow repository/worktree instructions. Do not create a worktree just because the design is a multi-step task.

All shell command examples use the required `rtk` prefix. Examples assume the repository root and PowerShell. Do not run the full local test suite, even though some older README commands do so.

## 2. File map and ownership

| File | Change | Responsibility |
|---|---|---|
| `ui/index.html` | Modify | Navigation, labels, overview leaderboard container, forms, semantic screen structure |
| `ui/styles.css` | Modify | Shared visual tokens, tables, fixed desktop split, readable detail layout |
| `ui/app.js` | Modify | Public-data loading, route/filter restoration, leaderboard integration, company/events presentation, access form and guide labels |
| `ui/leaderboard.js` | Create | Pure bounded query serialization and safe table markup shared by two screens |
| `ui/operations.js` | Modify narrowly | Operational presentation and copy; preserve lifecycle/security/trace behavior |
| `ui/operations-model.js` | Normally unchanged | Existing operational helpers; only touch for a demonstrated shared need |
| `ui/leaderboard.test.js` | Create | Query and rendering behavior/security tests |
| `ui/app.test.js` | Modify | Companies/Overview/navigation/history/access regression coverage |
| `ui/operations.test.js` | Modify only where needed | Operational presentation, context, denial and trace regression coverage |
| `ui/operations-model.test.js` | Usually unchanged | Run only affected helper tests if helper behavior changes |
| `ui/test-support.js` | Modify | Load the new script in production order; minimal fixture/harness support |
| `build.gradle.kts` | Modify one allowlist | Include `leaderboard.js` in `static/ops` |
| `.github/workflows/ci.yml` | Modify frontend test step | CI runs every frontend test file including the new one |
| `ui/README.md` | Modify | Updated navigation and walkthrough, exact limits and refresh behavior |
| `docs/operations-backoffice.md` | Modify only if clarification needed | Document presentation changes without changing the server contract |

Backend Kotlin files and migrations are read-only references for this plan. Production assets remain explicitly allowlisted; never package `*.test.js`, `test-support.js`, fixtures, keys, or development tools.

Existing functions to preserve/reuse:

- `app.js`: `api`, `invalidatePublicCredentials`, `readDiscoveryFilters`, `refreshDiscovery`, `routeView`, `safeReturnSearch`, `navigate`, `showView`, `renderCatalyst`, `renderHistory`, `renderEvents`.
- `operations.js`: `renderOverview`, `renderDocumentRows`, `renderDocumentSteps`, `renderValuationRows`, `renderModelSummary`, `renderModelRows`, `renderSelectedModelCall`, `renderPipelineRows`, `clearProtectedData`, current trace refresh/verification machinery.
- `window.CatalystOperations.create(...)` retains its existing public lifecycle contract: `init`, `show`, `refresh`, `credentialsChanged`, `dispose`, `ownsView`.

Do not add a generic state manager, generic CRUD renderer, table configuration language, or a replacement networking helper. Reuse existing escaping/date utilities where accessible. Tiny private escaping within the independent pure leaderboard module is acceptable; reorganizing every file to share five lines is not required.

## 3. Task sequence and review checkpoints

Each task is independently reviewable. Complete its affected checks before the next task. Keep the app usable between tasks. Inspect the diff at each checkpoint. If the execution workflow uses commits, stage only that task's intended files and use the proposed commit title; do not commit unrelated work.

### Task 1: Desktop shell, navigation, and shared visual rules

**Files:** `ui/index.html`, `ui/styles.css`, `ui/app.js`, `ui/app.test.js`.

**Consumes:** Existing local `?view=` routes, DOM IDs, navigation handler, focus logic, guide route names.

**Produces:** Always-visible grouped navigation; Companies as the visible name of `discover`; unchanged URL compatibility; reusable table/focus/layout CSS.

- [ ] Update the existing navigation behavior test to assert Overview is initially visible, Companies and Events links are visible, Company highlights Companies, and the `operations` alias still opens Pipeline without a POST. Update the old `navIntelligence` expectation rather than retaining a hidden dead link.
- [ ] Run just the selected navigation/alias/guide tests; confirm that the new expectation fails before markup changes.

```powershell
rtk node --test --test-name-pattern="initial load defaults|legacy Operations route|company navigation, return|first visit offers|analyst guide" ui/app.test.js
```

- [ ] Replace the nested Intelligence navigation with visible group captions and `navDiscover` labeled Companies. Remove all `navIntelligence`/`.intelligence-tabs` bindings in the same change. Keep the underlying `discover` route, section IDs, and test harness selectors where possible.

Target navigation markup shape:

```html
<a class="skip-link" href="#mainContent">Skip to main content</a>
<nav class="app-nav" aria-label="Main navigation">
  <a id="navOverview" href="?view=overview">Overview</a>
  <span class="nav-group-label">Research</span>
  <a id="navDiscover" href="?view=discover">Companies</a>
  <a id="navEvents" href="?view=events">Events</a>
  <span class="nav-group-label">Operations</span>
  <a id="navPipeline" href="?view=pipeline">Pipeline</a>
  <a id="navDocuments" href="?view=documents">Documents</a>
  <a id="navModels" href="?view=models">Models &amp; costs</a>
  <a id="navSettings" href="?view=settings">Settings</a>
</nav>
```

- [ ] Add `id="mainContent"` to the existing main landmark. Apply the design token/typography/layout values from specification section 6. Consolidate conflicting old declarations instead of appending another overriding stylesheet.
- [ ] Update page titles, active-navigation mapping, guide labels, and link bindings to match. Intercept navigation only for ordinary primary clicks, preserving modifier clicks. Keep `safeReturnSearch` validation.
- [ ] Review at 1440×900 and 1280×800: visible navigation, wrapping header, no overlap, keyboard focus and skip link. Do not add a theme toggle or mobile menu.
- [ ] Rerun the focused command above, then `rtk node --check ui/app.js` and `rtk git diff --check`.

Checkpoint: `feat(ui): clarify internal backoffice navigation`.

### Task 2: Shared leaderboard query and presentation

**Files:** create `ui/leaderboard.js`, `ui/leaderboard.test.js`; modify `ui/index.html`, `ui/test-support.js`, `build.gradle.kts`, `.github/workflows/ci.yml`.

**Consumes:** Existing discovery response shape, with `results`, `total`, `limit`, `offset`, query `asOf`, and row fields documented in the specification.

**Produces:** Exactly this small public interface, using the existing script pattern:

```javascript
window.CatalystLeaderboard = Object.freeze({ query, render });
// query(filters) -> URLSearchParams
// filters: { states?: string[], minScore?: string|number,
//   minVelocity7d?: string|number, sector?: string,
//   sort?: 'SCORE'|'VELOCITY', limit?: number, offset?: number }
// render(page, { compact?: boolean, sort?: 'SCORE'|'VELOCITY' }) -> HTML string
// No fetch, storage, timers, event listeners, or mutation of page/results.
```

- [ ] Add pure tests using the current VM style. Begin with these runnable test bodies; they fail until the module exists:

```javascript
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const window = {};
const file = path.join(__dirname, 'leaderboard.js');
vm.runInNewContext(fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '',
  { window, URLSearchParams }, { filename: file });

test('leaderboard query preserves signed and zero filters with bounded paging', () => {
  const query = window.CatalystLeaderboard.query({
    states: ['WATCH', 'HIGH'], minScore: 0, minVelocity7d: -2,
    sector: ' Technology ', sort: 'VELOCITY', limit: 20, offset: 40,
  });
  assert.deepEqual(query.getAll('state'), ['WATCH', 'HIGH']);
  assert.equal(query.get('minScore'), '0');
  assert.equal(query.get('minVelocity7d'), '-2');
  assert.equal(query.get('sector'), 'Technology');
  assert.equal(query.get('sort'), 'VELOCITY');
  assert.equal(query.get('limit'), '20');
  assert.equal(query.get('offset'), '40');
});

test('leaderboard query rejects unsupported sorts and invalid bounds', () => {
  for (const filters of [{ sort: 'DECLINE' }, { limit: 101 },
    { offset: -1 }, { minScore: 101 }, { minVelocity7d: 'NaN' }]) {
    assert.throws(() => window.CatalystLeaderboard.query(filters));
  }
});

test('leaderboard rendering preserves server order and escapes evidence text', () => {
  const page = { asOf: '2026-10-22T10:00:00Z', total: 2, limit: 20,
    offset: 0, results: [
      { ticker: 'ZZZ', name: '<img src=x onerror=alert(1)>', sector: 'Technology',
        state: 'WATCH', score: 30, velocity7d: -2, events7d: 0,
        scoreVersion: 'score-v1', taxonomyVersion: 'taxonomy-v1',
        asOf: '2026-10-22T09:00:00Z' },
      { ticker: 'AAA', name: 'Second', state: 'HIGH', score: 90,
        velocity7d: 0, events7d: 1, asOf: '2026-10-22T08:00:00Z' },
    ] };
  const before = JSON.stringify(page);
  const markup = window.CatalystLeaderboard.render(page, { sort: 'SCORE' });
  assert.ok(markup.indexOf('ZZZ') < markup.indexOf('AAA'));
  assert.match(markup, /&lt;img/);
  assert.doesNotMatch(markup, /<img/);
  assert.match(markup, /<table/);
  assert.match(markup, /aria-sort="descending"/);
  assert.match(markup, /UTC/);
  assert.equal(JSON.stringify(page), before);
});
```

- [ ] Run `rtk node --test ui/leaderboard.test.js` and confirm meaningful failures. This is a focused new-module test file, not the frontend suite.
- [ ] Implement the module. `query` omits empty optional values, keeps zero, validates enum/state/numeric bounds, trims sector, emits `sort`, `limit`, `offset`, and rejects unsupported values. Defaults: SCORE/20/0. Do not silently convert an unknown sort to another request inside this helper.
- [ ] Implement `render` as pure semantic table markup in exact server order. Full columns and compact columns follow spec sections 8–9. Escape every external string; ticker href uses `encodeURIComponent`; style classes come from allowlisted states. Null/non-finite values are Unavailable; known zero is visible. The current server's numeric velocity does not establish history coverage.
- [ ] Add compact rendering, unknown/null, positive/negative/zero, missing sector/version, and safe ticker attribute assertions. Do not test exact whitespace or the entire HTML snapshot.
- [ ] Load the new script before `app.js`, after any existing prerequisites. Update the harness's explicit script list to match. Keep scripts classic/IIFE; do not change VM/module infrastructure.
- [ ] Add only `leaderboard.js` to `tasks.processResources`' existing `include(...)`. Update CI's frontend command to include all four test files:

```yaml
- name: Test dashboard
  run: node --test ui/app.test.js ui/operations.test.js ui/operations-model.test.js ui/leaderboard.test.js
```

- [ ] Rerun the module tests and `rtk node --check ui/leaderboard.js`. Packaging is checked at Task 10; no full-suite local command is needed here.

Checkpoint: `feat(ui): share bounded leaderboard rendering`.

### Task 3: Companies table, filtering, and return context

**Files:** `ui/app.js`, `ui/index.html`, `ui/styles.css`, `ui/app.test.js`; harness only if necessary.

**Consumes:** `CatalystLeaderboard.query/render`, existing `api`, discovery counters, current local route helpers.

**Produces:** Companies table using global supported sorts, validated filter form, URL-backed applied context, unchanged company route.

- [ ] Add `desktop companies` regression tests before implementation. Verify SCORE/VELOCITY request parameters, table order, query-vs-snapshot times, reset/page-one behavior, invalid score/velocity handling, and exact sector semantics. Extend existing paging/obsolete-response tests instead of duplicating them.
- [ ] Use a dynamic fixture to assert actual server request sorting rather than merely checking a select label:

```javascript
test('desktop companies sends the selected sort to the server', async () => {
  const dashboard = startDashboard({ discovery: url => {
    const query = url.searchParams;
    return discoveryPage(query.get('sort') === 'VELOCITY' ? 'FAST' : 'HIGH');
  } });
  await dashboard.flush();
  dashboard.element('discoverySort').value = 'VELOCITY';
  dashboard.element('discoveryFilters').trigger('submit');
  await dashboard.flush();
  const reads = dashboard.requests.filter(({ url }) =>
    new URL(url).pathname === '/v1/discovery/catalyzed');
  assert.equal(new URL(reads.at(-1).url).searchParams.get('sort'), 'VELOCITY');
  assert.match(dashboard.html('discoveryResults'), /FAST/);
  assert.match(dashboard.html('discoveryResults'), /<table/);
});
```

The harness already passes a `URL` to the `discovery` callback. Reuse it. If a helper's fixture signature changed since planning, adapt the test to its actual contract rather than adding another harness.

- [ ] Run the affected group and confirm the new behavior tests fail:

```powershell
rtk node --test --test-name-pattern="desktop companies|discovery|next page preserves|paging keeps|page controls" ui/app.test.js
```

- [ ] Keep `readDiscoveryFilters` as the form boundary but use the shared query helper for serialization/validation. Replace discovery-card markup with `CatalystLeaderboard.render(page, {sort})`. Retain existing stale-response/credential checks around assignment to the DOM.
- [ ] Set minimum-score input bounds 0–100; add inline validation status and Reset filters. Labels must use “7-day score change” and “Highest 7-day change.” Remove no working filter. Do not add sort options unsupported by `DiscoverySort`.
- [ ] Serialize applied fields to the `discover` local query string when applying/paging. Parse/restore only known fields. Invalid URL fields show a correction state and do not call the backend; Reset returns to safe defaults. Draft edits do not affect Next/Previous. Preserve state/ticker normalizations.
- [ ] Keep offset > 0 Previous enabled after an empty last page, and add Return to first page. Do not invent a total when the server returns zero. Add the changing-rankings note.
- [ ] Ensure ordinary ticker click carries the current serialized return route; modifier clicks retain native behavior. Company Back and browser Back restore applied filter/sort/offset values and focus.
- [ ] Verify an empty result, 100 long-name rows, invalid form, stale response, and page-two return. Rerun focused tests and `rtk node --check ui/app.js`.

Checkpoint: `feat(ui): make Companies a sortable catalyst leaderboard`.

### Task 4: Overview leaderboard and independent panel lifecycle

**Files:** `ui/index.html`, `ui/app.js`, `ui/styles.css`, `ui/app.test.js`, `ui/operations.js` for overview presentation only.

**Consumes:** Shared leaderboard module; existing public `api()`; Overview operational renderer; route and credential revision.

**Produces:** One five-row ranking panel loaded independently of operations, with sort and company navigation.

Static IDs to add:

```html
<section class="panel" aria-labelledby="overviewLeadersTitle">
  <h2 id="overviewLeadersTitle">Latest catalyst leaders</h2>
  <label>Sort
    <select id="overviewLeaderboardSort">
      <option value="SCORE">Highest score</option>
      <option value="VELOCITY">Highest 7-day change</option>
    </select>
  </label>
  <p>Latest stored snapshots; independent of the activity period.</p>
  <p id="overviewLeaderboardStatus" role="status"></p>
  <p id="overviewLeaderboardAsOf" class="muted small"></p>
  <div id="overviewLeaderboardResults"></div>
  <button id="overviewLeaderboardRetry" type="button" class="ghost hidden">Retry</button>
  <a id="overviewLeaderboardAll" href="?view=discover">Open Companies</a>
</section>
```

- [ ] Add these tests before wiring the panel. Import `startDashboard` from `test-support` directly for Overview tests; the local helper in `app.test.js` defaults to Discover.

```javascript
test('desktop overview leaderboard reads one bounded page without starting work', async () => {
  const dashboard = startDashboardWithRoute({ search: '?view=overview' });
  await dashboard.flush();
  const reads = dashboard.requests.filter(({ url }) =>
    new URL(url).pathname === '/v1/discovery/catalyzed');
  assert.equal(reads.length, 1);
  assert.equal(new URL(reads[0].url).searchParams.get('limit'), '5');
  assert.equal(new URL(reads[0].url).searchParams.get('offset'), '0');
  assert.equal(dashboard.requests.filter(r => r.method === 'POST').length, 0);
  assert.match(dashboard.html('overviewLeaderboardResults'), /<table/);
});

test('desktop overview leaderboard does not wait for administrative reads', async () => {
  const dashboard = startDashboardWithRoute({ search: '?view=overview',
    stored: { 'catalyst-admin-key': 'admin-secret' }, deferOverview: true });
  await dashboard.flush();
  assert.match(dashboard.html('overviewLeaderboardResults'), /DELL/);
});
```

- [ ] Run only `desktop overview` tests and observe failure. Add separate deferred-response cases for sort A then B, leaving Overview, and key replacement; only the newest same-credential response may render.
- [ ] Add `refreshOverviewLeaderboard()` in `app.js`, with its own request counter/loading state and existing credential revision. Use the shared `api`, which already aborts public reads. Add its nodes/counter to `invalidatePublicCredentials`. Do not put public discovery data into the administrative cache.
- [ ] Store its sort as local route field `leaderboardSort=SCORE|VELOCITY`. Enter/explicit refresh/sort change issue one request with `limit=5,offset=0`. Operational range changes preserve the sort; do not pass range to discovery. Reuse the existing main Refresh handler, without adding a timer.
- [ ] Open Companies with `sort` matching the panel. Ticker navigation returns to Overview with its period/sort intact. Use the same normal-click guard as Companies.
- [ ] Errors remain local to this panel, with Retry or Settings as appropriate; they must not replace the operational summary. On identical-query refresh failure retain already authorized rows with stale/read-time copy; on authorization failure clear affected rows. Do not clear valid public data merely because an independent admin call is denied.
- [ ] Reorder operational Overview content according to spec section 8. Show waiting/due/processing separately, keep coverage categories separate, and make costs secondary in activity. Label the auto-refresh checkbox with its operations-only scope.
- [ ] Run:

```powershell
rtk node --test --test-name-pattern="desktop overview|clearing keys|each credential|dashboard sends requests|obsolete" ui/app.test.js
rtk node --test --test-name-pattern="overview|Overview|queue|freshness|dependency|Dependencies" ui/operations.test.js
```

Checkpoint: `feat(ui): surface catalyst leaders on Overview`.

### Task 5: Company analysis and honest historical display

**Files:** `ui/index.html`, `ui/app.js`, `ui/styles.css`, `ui/app.test.js`; `ui/operations.js` only for visible valuation labels.

**Consumes:** Existing company endpoints, `renderHistory`, exact valuation presentation, source inspection links.

**Produces:** Full-page company analysis in the specified order, recorded-calculation naming, point-only history SVG with exact-value table, preserved attribution limits.

- [ ] Add/adjust behavior tests for Back to Overview vs Companies, point-only zero/one/many snapshot plots, transitions outside snapshot extent, version mismatch, and independent section failures. Preserve existing historical caps and source access tests.
- [ ] Use this new regression to prevent invented continuity:

```javascript
test('desktop company history shows observations without an interpolated trace', async () => {
  const dashboard = startDashboard({ search: '?view=company&ticker=DELL',
    company: companyFixture });
  await dashboard.flush();
  const history = dashboard.html('companyHistory');
  assert.match(history, /<circle/);
  assert.doesNotMatch(history, /<polyline/);
  assert.match(history, /UTC/);
});
```

Keep axes/transition markers if present; remove only the connecting score trace. The inspected `companyFixture['/timeline']` supplies two snapshots and a transition, so this test exercises actual plotted observations.

- [ ] Run selected Company/history tests to establish the expected failure, then implement section spacing/order and labels. Preserve DOM IDs `companyValuations` and routing names even when headings say Recorded calculations.
- [ ] Change the existing SVG renderer to points-only; keep score axis, UTC titles, transitions and the exact table. Do not add custom hover machinery or a plotting package. The 30/90/max controls and 200-record limits remain.
- [ ] Add the lookback-coverage and current-reconstruction notices. Keep mismatch suppression. Use raw contribution labels and frozen-source truncation notices; never calculate a saved after-score from displayed factors.
- [ ] Keep company metadata, current score, history, explanation, and protected calculation panels independently recoverable. An admin-denied calculation must not remove separately authorized company metadata.
- [ ] Rerun:

```powershell
rtk node --test --test-name-pattern="desktop company|company route|one failed company|history|mismatched explanations|source inspection|scoring details|company return" ui/app.test.js
rtk node --test --test-name-pattern="company.*valuation|valuation.*company|contribution|frozen|reconstruct" ui/operations.test.js
```

Checkpoint: `refactor(ui): clarify company evidence and recorded calculations`.

### Task 6: Events table and evidence expansion

**Files:** `ui/app.js`, `ui/index.html`, `ui/styles.css`, `ui/app.test.js`.

**Consumes:** Existing event filter/cursor/grouping behavior and source-link helpers.

**Produces:** Dense accessible event/report listing with expandable evidence and the same bounded loading behavior.

- [ ] Extend current event tests to assert semantic table headers, escaped evidence, loaded-report labels, and retained evidence on continuation failure. Do not assert a global cluster total from loaded records.
- [ ] Run the event-specific group before changes:

```powershell
rtk node --test --test-name-pattern="event|source links|unsafe source|source inspection" ui/app.test.js
```

- [ ] Change `renderEvents` and relevant company contextual-event presentation to the table/detail structure from spec section 11. Reuse grouping and append logic; do not regroup by article title or company alone.
- [ ] Use native `<details><summary>Evidence and sources</summary>...</details>` inside a spanning detail row for each group/report as appropriate. Keep a real ticker link and only real source IDs. Existing details can remain open across a continuation by storing a small set of opened group IDs if the renderer otherwise closes them; do not add a generalized expansion store.
- [ ] Preserve exact event/from/to filter semantics and family-grouped taxonomy selection. Invalid inputs remain visible with correction copy. Keep inclusive through-date handling separate from Documents.
- [ ] Verify source excerpts cannot become HTML, unsafe protocols are not links, and source text is not prefetched. Preserve modifier-click behavior.
- [ ] Rerun the focused group, syntax check, and browser-review a cluster with reports spanning two pages, missing source metadata, and a long evidence excerpt.

Checkpoint: `refactor(ui): make event evidence easier to inspect`.

### Task 7: Pipeline and document investigation layout

**Files:** `ui/index.html`, `ui/styles.css`, `ui/operations.js`, `ui/operations.test.js`; `ui/app.test.js` only for touched execution disclosure behavior.

**Consumes:** Existing run/document/trace state and handlers; actual recorded relations and current history-retention code.

**Produces:** Readable run tables/wide detail, fixed desktop document split, clear current-vs-cycle outcomes, unchanged execution/access/history invariants.

- [ ] Locate actual existing test names with `rtk rg -n "test\(" ui/operations.test.js`. Select affected tests for cycle context, legacy trace, unfinished work, paused history, source truncation, and access denial. Use these as regression contracts before changing renderers.
- [ ] For changed behavior, add focused `desktop pipeline` / `desktop documents` tests with existing fixtures: current document COMPLETED but selected-cycle failed attempt; null trace version with newer attempt; missing finish/duration; empty cycle period with active filter. Assert visible outcomes and exact links, not only CSS classes.
- [ ] Apply semantic tables to the run/provider/document list renderers while retaining their data attributes and existing delegation. Place selected run detail above run history using DOM order and simple grid rules. Follow spec section 12 ordering inside the panel; keep child feeds independent.
- [ ] Apply fixed document split only at 1440px and wider. Below that, order selected detail above the retained list. Do not add drag handlers, pane persistence, new routes, or duplicate detail state.
- [ ] Keep Timeline as recorded cards, and other tabs intact. Rename visible Valuations to Recorded calculations and Models to Model calls. Preserve tab IDs, ARIA state, keyboard interaction, lazy body fetch, and old routes.
- [ ] Preserve paused history notices/read times and Reload latest behavior. Changes must not repaint multi-page trace collections on metadata polls. Preserve membership proof and exact valuation checks.
- [ ] Keep the existing first-run model disclosure and pending/busy guards. Add a focused test for a lost POST response showing unknown outcome without automatic resubmission if current behavior lacks this distinction. Do not add budget UI or a daily-snapshot trigger.
- [ ] Verify operation/request behavior with focused tests:

```powershell
rtk node --test --test-name-pattern="desktop pipeline|desktop documents|cycle|Timeline|timeline|legacy|unfinished|retained|paused|denied|source body|truncat|busy|duplicate clicks" ui/operations.test.js
rtk node --test --test-name-pattern="first manual run|missing configured models|failed config|leaving Pipeline|changing credentials" ui/app.test.js
```

Inspect the reported selected-test count: a pattern matching no tests is not verification. Narrow or correct the pattern to real relevant names; do not switch to the whole suite.

- [ ] Browser-review full title wrapping, keyboard tab switching, detail close/focus restoration, old/new attempt distinctions, and a paused history collection at 1440×900 and 1280×800.

Checkpoint: `refactor(ui): simplify pipeline and document investigation layouts`.

### Task 8: Models, costs, and Settings

**Files:** `ui/index.html`, `ui/styles.css`, `ui/operations.js`, `ui/app.js`, `ui/operations.test.js`, `ui/app.test.js`.

**Consumes:** Existing server aggregates, call detail/provenance, safe configuration, session credential handling.

**Produces:** Consistent model-call table and outcome-first detail; access-first Settings; unchanged coverage/security behavior.

- [ ] Reuse existing model coverage, independent totals, selection-outside-period, safe error, and credential invalidation tests. Add a `desktop models` test only for changed display behavior, such as explicit not-applicable output tokens on embedding calls.
- [ ] Reformat the call list into the spec section 14 columns and place selected detail before summary. Show known subtotal/coverage using existing helper values. Do not sum table rows, infer missing prices, or replace unknown costs with zero.
- [ ] Render operation/model/error before IDs; preserve actual document/attempt/run links and absent-link states. No raw prompt/response viewer and no budget control.
- [ ] Move Settings access form first, configuration second, guide controls last. Keep input masking, save/clear, storage scope, safe return handling, and separate admin/public headers. Do not add reveal/key-management controls merely for visual completeness.
- [ ] Ensure failed reauthorization clears old protected panels including hidden source/config data, while authorized public data can still function. Explicit key replacement still clears public cached data via existing revision handling.
- [ ] Run:

```powershell
rtk node --test --test-name-pattern="desktop models|model totals|model coverage|summary.*window|selected.*call|outside.*period|safe.*error|denied|credentials" ui/operations.test.js
rtk node --test --test-name-pattern="Settings|clearing keys|each credential|shared API helper|dashboard sends requests|changing credentials" ui/app.test.js
```

- [ ] Browser-review unknown versus zero costs, failed paid extraction with known usage, outside-period selection, denied access recovery, and key-entry focus. Do not use real credentials in screenshots or test fixtures.

Checkpoint: `refactor(ui): clarify recorded model usage and access settings`.

### Task 9: Cross-screen interaction verification and documentation

**Files:** affected `ui/*.test.js`, `ui/test-support.js` only if needed, `ui/README.md`, optionally `docs/operations-backoffice.md`.

**Consumes:** Completed screen changes and existing protected context/route contracts.

**Produces:** Verified user journeys and accurate operator documentation.

- [ ] Execute the acceptance scenarios in section 4 below with synthetic fixtures or a safe local environment. No live provider/model calls are needed. Never click Run pipeline now against live configured services merely to test its appearance.
- [ ] Use browser tools already available; if none can render the app, record visual QA as unperformed rather than treating VM tests as screenshots. A locally rendered synthetic fixture environment must be clearly labeled and kept out of production packaging. Do not add a permanent production demo mode.
- [ ] Review keyboard-only navigation, contrast, zoom, table scrolling, long strings, loading states and focus retention at 1440×900, 1920×1080, and 1280×800. Fix actual defects with focused regression coverage where logic is affected.
- [ ] Update `ui/README.md` to document Companies naming, two supported rankings, Overview leaderboard refresh scope, point-only recorded history, exact UTC/date semantics, calculation-vs-reconstruction labels, and the preserved investigation flow. Do not claim complete source history or invoice-accurate costs.
- [ ] If shared response/copy documentation needs clarification, update `docs/operations-backoffice.md` without changing endpoint semantics. No architecture ADR is needed because the architecture is unchanged.
- [ ] Run only regression groups relevant to defects found in this task. Record selected commands, pass/fail counts, and browser coverage in the implementation handoff or PR; do not keep rerunning unchanged groups.

Checkpoint: `docs(ui): document the internal desktop investigation workflow`.

### Task 10: Production packaging and final review

**Files:** no expected feature changes; fix only verified packaging/static defects.

**Consumes:** Completed frontend assets, Gradle allowlist, CI configuration.

**Produces:** Buildable application with all needed runtime assets and no test-only assets.

- [ ] Run syntax checks for changed runtime scripts:

```powershell
rtk node --check ui/app.js
rtk node --check ui/operations.js
rtk node --check ui/leaderboard.js
rtk git diff --check
```

Run `node --check ui/operations-model.js` only if changed. Keep any existing configured lint/static checks relevant to the change; do not introduce a lint ecosystem as part of the redesign.

- [ ] Build the production package without invoking the full test suite:

```powershell
rtk .\gradlew.bat bootJar --console=plain
```

`bootJar` is the production build task; do not substitute `build`, `check`, or `clean test` locally if they run full suites. If the task graph has changed, inspect it before execution. Record environmental failures accurately rather than claiming the build passed.

- [ ] Confirm packaged entries with the actual generated filename. With the current version, expected command:

```powershell
rtk jar tf build/libs/catalyst-radar-0.0.1-SNAPSHOT.jar
```

Expected assets under `BOOT-INF/classes/static/ops/`: index.html, styles.css, app.js, operations.js, operations-model.js, leaderboard.js. If the base name differs, find the built jar with `rtk rg --files build/libs` and use that path. No test scripts or harness files should be packaged.

- [ ] Verify CI's frontend step includes all four test files. Full frontend and backend execution remains CI's responsibility. Do not run that whole command locally.
- [ ] Review the complete diff for accidental backend changes, extra dependencies, secrets, source-body prefetching, unsupported sort options, broken old URLs, and misleading totals/history copy.
- [ ] Deliver a concise completion report: what changed, selected test results, packaging result, actual browser coverage, and deferred unsupported rankings. Do not claim CI passed until its actual result is available.

Checkpoint: `chore(ui): verify desktop backoffice packaging` if a final packaging change was necessary; otherwise no extra commit is required.

## 4. End-to-end acceptance matrix

Use this as the final review checklist, not a demand to add a new end-to-end framework. Existing fixture tests plus actual browser review cover different parts of the contract.

| ID | Setup and action | Expected result |
|---|---|---|
| A01 | Empty route | Overview; no execution request |
| A02 | Overview with delayed admin response | Public leaderboard can finish independently |
| A03 | Switch compact sort | One five-row server request, correct sort, unchanged operational period |
| A04 | Open Companies from compact ranking | Same sort, page one, default other filters |
| A05 | Apply HIGH/WATCH + min score 0 + min change -2 | Repeated states and signed/zero values sent intact |
| A06 | Edit sector without Apply; click Next | Uses previously applied sector and page size |
| A07 | Older discovery response finishes last | Does not replace newest query results or pager |
| A08 | Open company from page two, then Back | Restores applied filters, sort, size, offset and heading focus |
| A09 | Results shrink and page two becomes empty | No false total; Previous/first-page recovery available |
| A10 | 1d/3d/7d company changes | Display values but do not invent lookback completeness |
| A11 | Zero/one/multiple snapshots | Empty/one point/recorded points; no invented continuous trace |
| A12 | Reconstruction mismatches saved versions/score | No false attribution to saved snapshot |
| A13 | Recorded calculation with null prior snapshot | Prior score/state Not recorded; no zero baseline |
| A14 | Source beyond frozen metadata display cap | Truncation stated; no inference of missing membership |
| A15 | Events continuation adds same cluster | Existing loaded group gains evidence without new catalyst-count claim |
| A16 | Unsafe title/evidence/URL | Text escaped; unsafe URL is not an executable link |
| A17 | Selected cycle failed; document later completed | Both cycle outcome and current state shown accurately |
| A18 | Legacy source with new attempt steps | New history visible; old intake remains unavailable |
| A19 | Interrupted step or unfinished run | No fabricated finish, duration, percentage, or active claim |
| A20 | Load older trace then poll current metadata | Loaded history retained/paused; metadata can update separately |
| A21 | Reload latest history | One collection resets; valid selected detail preserved per existing rules |
| A22 | Open Source tab | Exactly on-demand protected text read; cap/length visible |
| A23 | Model summary with partial usage | Known subtotal + coverage; no unknown-to-zero conversion |
| A24 | Selected model call outside period | Call retained with mismatch notice; summary scope unchanged |
| A25 | Admin denial after source/config loaded | All protected caches/rendered content purged; no stale repaint |
| A26 | Change/clear keys during requests | Old responses cannot repopulate protected/public old-credential data |
| A27 | Run pipeline pending/busy/lost response | No duplicate POST; no fictitious run; lost outcome not auto-retried |
| A28 | Daily snapshots tab | Read-only history; no provider-triggering control |
| A29 | Ctrl/Cmd-click ticker/source | Native new-tab behavior; safe local/external link destination |
| A30 | Keyboard + zoom + 1280px desktop | Controls reachable, focus visible, detail readable, no clipped actions |
| A31 | Production jar inspect | All six runtime assets included; no fixture/test assets |

## 5. Known limits that must survive implementation

- No global largest-decline or 1/3-day leaderboard sorting without future backend support.
- Discovery ordering is live across page reads, not a snapshot-consistent research dataset.
- Current numeric velocities do not carry explicit lookback coverage provenance.
- Historical snapshots and transitions are capped; absence of capture is not zero activity.
- Recorded live calculations demonstrate actual inputs, not guaranteed historical replay eligibility.
- Model usage/cost capture is best-effort and estimated, not a provider invoice.
- Existing operational active observations/guards assume one application instance.
- Protected source bodies and frozen display metadata have explicit caps.
- No new mobile UX, budget system, editing controls, alerts, or trading functionality.

## 6. Review evidence and external references

The plan is grounded in the current static backoffice and discovery controller/store rather than a new framework proposal. Browser-native JavaScript modules are documented by [MDN](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Guide/Modules), but a migration is intentionally unnecessary here. Node documents focused test execution using `--test-name-pattern` in its [test runner documentation](https://nodejs.org/download/release/latest-jod/docs/api/test.html); use that facility to honor the repository's affected-tests-only local preference.

Planning itself does not establish that the redesigned interface passes tests or visual QA. Those results must be collected during implementation.
