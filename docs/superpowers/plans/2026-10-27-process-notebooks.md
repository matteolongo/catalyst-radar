# Process Notebooks Implementation Plan

> Execute inline with superpowers:executing-plans; retain atomic commits in PR #50.

**Goal:** Explain the complete CatalystRadar process in six navigable in-app pages.

**Architecture:** Use the existing static frontend and router. Notebooks contain explanatory
content, one consistently labeled fictional example, and links into existing record views.
They do not run individual pipeline stages, fetch protected records, duplicate scoring logic,
or promise a backfill capability. Keep the pipeline's first-run disclosure and access controls.

**Tech stack:** Existing HTML, CSS, JavaScript, Node test harness, Spring Boot asset packaging.

**Design agreed in chat:** Companies → Collect news → Extract events → Group reports →
Calculate scores → Track changes. Every module explains purpose, inputs, processing, outputs,
missing/failure cases and next steps. An optional known ticker carries across notebook pages
and into relevant document/company views. The fictional example is never presented as live data.

## Constraints

- No new dependencies, backend endpoints, paid calls or persisted synthetic records.
- Keep Overview as the default route. Add Process notebooks prominently in navigation.
- Replace the large optional acknowledgement guide with a compact contextual notebook link.
- Preserve existing first-run pipeline confirmation, including credential/race safeguards.
- Existing records are inspected in their established screens; browser Back retains the notebook.
- Use normal links, current-page semantics, visible keyboard focus and bounded page content.
- Only affected tests locally; full suites run in CI. Run syntax checks and bootJar.

## Task 1 — Content and pure renderer

Files: create `ui/notebooks.js`, `ui/notebooks.test.js`; update Gradle asset allowlist and CI test command.

- [ ] Add tests loading the renderer in a VM; assert all six modules have input/output and
  illustrative-example labels, previous/next URLs preserve ticker, and unsafe strings are escaped.
- [ ] Run the new test file and confirm failure before implementation.
- [ ] Implement `CatalystNotebooks.render(module, ticker)` returning `{title, html}`. Unknown
  modules recover to Companies with a visible notice. URLs use encoded values and local routes.
- [ ] Include company/source/event/cluster distinctions, deterministic scoring, snapshot/replay
  limitations, partial capture, and the current first-page ingestion limitation in plain language.
- [ ] Verify renderer tests and syntax, then commit.

## Task 2 — App integration and simpler guidance

Files: `ui/app.js`, `ui/index.html`, `ui/styles.css`, `ui/test-support.js`, `ui/app.test.js`.

- [ ] Add focused routing tests: open notebooks without keys, all six routes, Back/Forward,
  retained ticker, invalid ticker, modifier clicks, no domain reads/polls/POSTs on notebooks.
- [ ] Add `notebooksView`, a static optional ticker form, notebook content container and sidebar link.
  Register `notebooks` in view and return-route allowlists. Hide operations controls on notebooks.
- [ ] Delegate notebook links through existing navigation for ordinary clicks, preserving company
  return context; retain native modified-click behavior. Focus page heading after navigation.
- [ ] Replace the old guide's normal render with a compact contextual notebook link. Keep the
  configured-model disclosure and its cancellation/credential behavior. Settings links to notebooks.
- [ ] Replace obsolete progress-guide tests with notebook tests; keep execution/security regressions.
- [ ] Style a readable notebook column with numbered input/process/output blocks and a six-step
  contents rail. At constrained desktop/zoom widths, stack the rail without clipping content.
- [ ] Run affected routing, disclosure, access, and renderer tests, then commit.

## Task 3 — Verify, document and update PR

Files: `ui/README.md`, root `README.md`, this plan.

- [ ] Document how to open notebooks and distinguish examples, stored reads and paid pipeline execution.
- [ ] Browser-check all modules, contextual entry links, selected ticker, keyboard navigation,
  ordinary/modified links and document/company return flow using a local read-only fixture server.
- [ ] Run runtime syntax checks, `git diff --check`, `gradlew.bat bootJar --console=plain`; inspect
  the jar for notebooks.js and absence of tests. Do not run full local suites.
- [ ] Review the change, fix concrete defects, push atomic commits and update the existing PR.
- [ ] Report verification limitations honestly; no real ingestion or historical backfill is executed.
