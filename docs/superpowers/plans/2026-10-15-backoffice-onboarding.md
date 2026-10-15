# Backoffice Onboarding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a non-blocking, browser-local first-time guide for the existing CatalystRadar backoffice.

**Architecture:** Keep the guide in the existing static operations UI. `app.js` owns versioned browser-local progress, view-specific explanatory copy, and navigation; `operations.js` asks it for one asynchronous first-run approval before sending the existing pipeline POST. The existing safe configuration response supplies model names only after the user deliberately requests a manual run.

**Tech Stack:** HTML, CSS, vanilla JavaScript, Node.js built-in test runner, Spring Boot static-resource packaging.

**Spec:** `docs/superpowers/specs/2026-10-15-backoffice-onboarding-design.md`

## Global Constraints

- Base this work on PR #46 (`codex/attempts-tab-focus-fix`); keep its single behavior fix separate from onboarding commits.
- No backend endpoint, persistence, dependency, analytics, user account, or configuration mutation.
- Persist only versioned guide progress in `localStorage`; never persist keys, model names, provider data, or source data.
- The guide never starts a pipeline. The existing `POST /internal/ingestion/runs` runs only after an explicit user confirmation.
- On the first manual run, read only `/internal/operations/config` and render only configured extraction/embedding model values. Never hard-code model names.
- Keep existing authentication, request revision, and error behavior intact. Use semantic controls and keyboard-reachable focus.
- Run UI tests with `node --test ui/app.test.js ui/operations.test.js ui/operations-model.test.js`; run `node --check` on changed UI scripts; no live providers.

---

### Task 1: Add the guide shell, browser state, and contextual paths

**Files:**
- Modify: `ui/index.html`
- Modify: `ui/styles.css`
- Modify: `ui/app.js`
- Modify: `ui/test-support.js`
- Test: `ui/app.test.js`

**Interfaces:**
- Consumes: the existing `navigate(localSearch, state)` and `showView()` functions in `ui/app.js`.
- Produces: `onboarding.show(view)` and `onboarding.beforePipelineRun()` for the application and operations controller.

- [ ] **Step 1: Write failing UI tests**

```javascript
test('first visit offers an operator path and records only local guide progress', async () => {
  const dashboard = startDashboard({ stored: { 'catalyst-admin-key': 'admin-secret' } });
  await dashboard.flush();
  assert.match(dashboard.html('onboarding'), /Manage the pipeline/);
  dashboard.click('onboarding');
  assert.equal(dashboard.localValues.get('catalyst-onboarding-v1').includes('operator'), true);
  assert.equal(dashboard.requests.some((request) => request.method === 'POST'), false);
});
```

- [ ] **Step 2: Run the new test and verify it fails because the guide is absent**

Run: `node --test --test-name-pattern "first visit offers an operator path" ui/app.test.js`

Expected: FAIL because `onboarding` does not exist in the static DOM.

- [ ] **Step 3: Add the minimal guide UI and state**

Add one hidden, non-modal guide region near the main content; add a Settings restart button. In `app.js`, parse a safe `catalyst-onboarding-v1` local-storage object with a memory fallback, render the entry choices and path-specific checklist, mark a view complete only after it is displayed, and use existing local navigation. Add only the CSS required for the panel, buttons, visible focus, and narrow screens.

- [ ] **Step 4: Run the focused tests and verify they pass**

Run: `node --test ui/app.test.js`

Expected: PASS, including operator/analyst selection, defer/resume/reset, and no POST side effect.

- [ ] **Step 5: Commit**

```bash
git add ui/index.html ui/styles.css ui/app.js ui/test-support.js ui/app.test.js
git commit -m "feat: add browser-local backoffice guide"
```

### Task 2: Guard the first manual pipeline run with configured model disclosure

**Files:**
- Modify: `ui/operations.js`
- Modify: `ui/app.js`
- Test: `ui/operations.test.js`
- Test: `ui/app.test.js`

**Interfaces:**
- Consumes: `beforePipelineRun(): Promise<boolean>` passed into `CatalystOperations.create` and the existing `api` helper.
- Produces: a first-run callout that either asks for explicit confirmation, links to Settings if models are missing, or allows the unchanged existing POST.

- [ ] **Step 1: Write failing UI tests**

```javascript
test('first manual run shows only configured models before posting', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret' });
  await dashboard.openPipeline();
  dashboard.click('runNow');
  await dashboard.flush();
  assert.match(dashboard.html('onboarding'), /model.*embed-model/i);
  assert.equal(dashboard.requests.filter((request) => request.method === 'POST').length, 0);
});
```

- [ ] **Step 2: Run the new test and verify it fails because Run pipeline now posts immediately**

Run: `node --test --test-name-pattern "first manual run shows only configured models" ui/operations.test.js`

Expected: FAIL because the existing click handler sends a POST without disclosure.

- [ ] **Step 3: Implement the smallest approval seam and callout**

Make `operations.js` await an optional `beforePipelineRun` callback while temporarily disabling the existing button. In `app.js`, fetch the safe configuration only on the first deliberate run, filter the extraction/embedding values by configured provider, and show the role of each model. A missing configuration or failed read offers Settings/retry and returns `false`; explicit confirmation stores the acknowledgment and invokes the unchanged button path once.

- [ ] **Step 4: Run the focused tests and verify they pass**

Run: `node --test ui/operations.test.js ui/app.test.js`

Expected: PASS; no first-click POST, one POST after confirmation, no repeat disclosure after acknowledgment, and no POST for missing/failed configuration.

- [ ] **Step 5: Commit**

```bash
git add ui/operations.js ui/app.js ui/operations.test.js ui/app.test.js ui/test-support.js
git commit -m "feat: explain configured models before first run"
```

### Task 3: Document and verify the complete guide

**Files:**
- Modify: `ui/README.md`
- Test: `ui/app.test.js`
- Test: `ui/operations.test.js`

**Interfaces:**
- Consumes: completed guide UI and existing configuration/read execution contract.
- Produces: concise operator documentation for the guide and first-run disclosure.

- [ ] **Step 1: Add a failing regression test for Settings restart after completion**

```javascript
test('Settings restarts a completed guide without changing credentials', async () => {
  const dashboard = startDashboard({ localStored: { 'catalyst-onboarding-v1': JSON.stringify({ version: 1, path: 'analyst', completed: ['discover', 'company', 'events'] }) } });
  await dashboard.flush();
  dashboard.popstate('?view=settings');
  dashboard.click('restartOnboarding');
  assert.match(dashboard.html('onboarding'), /Manage the pipeline/);
});
```

- [ ] **Step 2: Run the regression and verify it fails before the Settings control is wired**

Run: `node --test --test-name-pattern "Settings restarts a completed guide" ui/app.test.js`

Expected: FAIL if the restart control does not clear browser-local progress and render entry choices.

- [ ] **Step 3: Add concise documentation and any minimal regression correction**

Document the two paths, browser-local scope, first-run configured-model notice, and the fact that no automatic run occurs. Do not document internal implementation details or costs.

- [ ] **Step 4: Run the final checks**

Run: `node --test ui/app.test.js ui/operations.test.js ui/operations-model.test.js`

Run: `node --check ui/app.js && node --check ui/operations.js`

Run: `git diff --check`

Expected: all UI tests pass, JavaScript parses, and the diff has no whitespace errors.

- [ ] **Step 5: Commit**

```bash
git add ui/README.md ui/app.test.js ui/operations.test.js
git commit -m "docs: explain backoffice onboarding"
```
