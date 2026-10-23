# Internal desktop backoffice implementation verification

Implementation follows the [desktop design](../specs/2026-10-22-internal-backoffice-desktop-design.md)
and [implementation plan](2026-10-22-internal-backoffice-desktop.md).

## Delivered

- Seven-area navigation with Overview as the entry point and Companies highlighted on company detail.
- Shared bounded leaderboard queries and tables, URL-restorable Companies filters,
  and an independent five-company Overview ranking. Rankings are server-provided
  SCORE or VELOCITY (7-day), descending only.
- Company changes labeled in points; all returned positive/negative drivers remain
  visible. Stored history remains points only. Recorded calculations and current
  reconstructed explanations stay separate.
- Expandable event evidence grouped by company and stored cluster, with loaded-report counts.
- Document investigation table and fixed desktop split; selected detail stacks above
  the list below 1440px. Pipeline retains full-width detail and the existing guarded run action.
- Model outcomes and usage precede incidental IDs; embedding output and unknown cost
  remain distinct from zero. Settings puts tab-scoped access first.
- Same-query refresh failures retain explicitly stale rows; denied reads clear them.
  Research headers hide operations-only polling and refresh timestamps.

The implementation adds no frontend dependencies, new API, backend service, database
migration or production demo mode. Runtime assets remain plain HTML, CSS and JavaScript
inside the existing Spring Boot application.

## Verification

Only affected tests were run locally, as required by AGENTS.md. Focused groups covered
navigation, leaderboard serialization/escaping, discovery filters and pagination,
stale responses, access clearing, company history, event grouping and source links,
document context, pipeline disclosure, model usage, and settings. New behavior and
defect tests were exercised red/green where applicable. Representative final runs:

- `node --test --test-name-pattern="desktop|discovery|Companies" ui/app.test.js`: 25 passed.
- `node --test --test-name-pattern="research hides|desktop overview|desktop navigation" ui/app.test.js`: 7 passed after the header refinement.
- Independent review: 12 selected regressions passed; no remaining actionable findings
  through the transient-refresh/overflow fix.
- Syntax checks passed for `ui/app.js`, `ui/operations.js` and `ui/leaderboard.js`;
  `git diff --check` passed.
- `gradlew.bat bootJar --console=plain`: successful production build.
- Inspected the generated jar: exactly `index.html`, `styles.css`, `app.js`,
  `operations.js`, `operations-model.js`, and `leaderboard.js` under `static/ops`.
  Test harnesses and the temporary visual-check server are not packaged.

A temporary read-only loopback server served the real assets with visibly labeled
synthetic fixtures. Browser inspection covered Overview, Companies, company analysis,
expanded Events, selected Documents, Pipeline detail, Models and Settings. Checked
1440px and 1920px desktop layouts, the document stack at 1280px, and document containment
at a 640px CSS viewport (a zoom-equivalent width, not an actual browser zoom setting).
The document page had no horizontal page overflow at 1280px or 640px. Keyboard Tab
advanced between labeled Companies filters with visible focus. No browser console
errors were recorded in this walkthrough.

## Limits

Full suites are delegated to CI. Browser checks used synthetic records, not a live
database or paid providers. This was a focused layout and interaction check, not a
complete accessibility audit or all-screen/all-size keyboard test. No pipeline was
executed during browser verification. Costs remain recorded estimates; missing
trace/history remains explicitly incomplete.
