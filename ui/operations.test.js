const assert = require('node:assert/strict');
const test = require('node:test');
const { startDashboard } = require('./test-support');

function startOperations(options = {}) {
  const stored = { ...(options.stored || {}) };
  if (options.storedAdmin) stored['catalyst-admin-key'] = options.storedAdmin;
  if (options.storedApiKey) stored['catalyst-api-key'] = options.storedApiKey;
  return startDashboard({ ...options, stored });
}

test('unauthenticated Overview loads health but no protected data or fake metrics', async () => {
  const dashboard = startOperations();
  await dashboard.flush();
  assert.equal(dashboard.text('health'), 'API available');
  assert.equal(dashboard.requests.some((request) => new URL(request.url).pathname.startsWith('/internal/')), false);
  assert.match(dashboard.html('overviewStatus'), /Admin access required/);
  assert.doesNotMatch(dashboard.html('overviewMetrics'), />0</);
  assert.match(dashboard.html('overviewStatus'), /Settings/);
});

test('protected reads use only the admin key and health uses no credential', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret', storedApiKey: 'public-secret' });
  await dashboard.flush();
  const overview = dashboard.requests.find((request) => new URL(request.url).pathname === '/internal/operations/overview');
  const health = dashboard.requests.find((request) => new URL(request.url).pathname === '/actuator/health');
  assert.equal(overview.headers['X-Admin-Key'], 'admin-secret');
  assert.equal(overview.headers.Authorization, undefined);
  assert.equal(health.headers['X-Admin-Key'], undefined);
  assert.equal(health.headers.Authorization, undefined);
});

test('replacing credentials aborts and suppresses a pending Overview response', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret', deferOverview: true });
  await dashboard.flush();
  const first = dashboard.requests.find((request) => new URL(request.url).pathname === '/internal/operations/overview');
  dashboard.elements.get('adminKey').value = 'replacement-key';
  dashboard.click('saveKey');
  await dashboard.flush();
  const overviewRequests = dashboard.requests.filter((request) => new URL(request.url).pathname === '/internal/operations/overview');
  assert.equal(overviewRequests.length, 2);
  assert.equal(overviewRequests[0].signal.aborted, true);
  assert.equal(overviewRequests[1].headers['X-Admin-Key'], 'replacement-key');
  dashboard.resolveOverview(0, { ...require('./test-support').overviewFixture, activeCompanies: 999 });
  dashboard.resolveOverview(1, { ...require('./test-support').overviewFixture, activeCompanies: 2 });
  await dashboard.flush();
  assert.match(dashboard.html('overviewMetrics'), /2/);
  assert.doesNotMatch(dashboard.html('overviewMetrics'), /999/);
});

test('clearing credentials immediately clears protected Overview content and aborts reads', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret', deferOverview: true });
  await dashboard.flush();
  const pending = dashboard.requests.find((request) => new URL(request.url).pathname === '/internal/operations/overview');
  dashboard.click('clearKey');
  assert.equal(pending.signal.aborted, true);
  assert.equal(dashboard.values.get('catalyst-admin-key'), undefined);
  dashboard.resolveOverview(0, require('./test-support').overviewFixture);
  await dashboard.flush();
  assert.match(dashboard.html('overviewStatus'), /Admin access required/);
  assert.doesNotMatch(dashboard.html('overviewMetrics'), /Companies/);
});

test('late errors and finally handlers cannot repaint after navigation', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret', deferOverview: true });
  await dashboard.flush();
  dashboard.popstate('?view=settings');
  await dashboard.flush();
  const status = dashboard.text('settingsStatus');
  dashboard.resolveOverview(0, 'error');
  await dashboard.flush();
  assert.equal(dashboard.text('settingsStatus'), status);
  assert.doesNotMatch(dashboard.text('banner'), /Overview unavailable/);
});

test('refresh and polling never overlap and hidden tabs pause polling', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret', deferOverview: true });
  await dashboard.flush();
  assert.equal(dashboard.intervalCount, 1);
  dashboard.tick();
  dashboard.tick();
  await dashboard.flush();
  assert.equal(dashboard.requests.filter((request) => new URL(request.url).pathname === '/internal/operations/overview').length, 1);
  dashboard.setHidden(true);
  dashboard.tick();
  await dashboard.flush();
  assert.equal(dashboard.requests.filter((request) => new URL(request.url).pathname === '/internal/operations/overview').length, 1);
  dashboard.setHidden(false);
  await dashboard.flush();
  assert.equal(dashboard.requests.filter((request) => new URL(request.url).pathname === '/internal/operations/overview').length, 2);
});

test('first-load and stale-refresh errors have distinct states', async () => {
  const outcomes = ['error', require('./test-support').overviewFixture, 'error'];
  const dashboard = startOperations({ storedAdmin: 'admin-secret', overview: () => outcomes.shift() });
  await dashboard.flush();
  assert.match(dashboard.text('overviewStatus'), /Unable to load overview/);
  dashboard.click('refresh');
  await dashboard.flush();
  assert.match(dashboard.html('overviewMetrics'), /Last successful ingestion/);
  dashboard.click('refresh');
  await dashboard.flush();
  assert.match(dashboard.text('overviewStatus'), /Last loaded .*refresh failed/);
  assert.match(dashboard.html('overviewMetrics'), /Last successful ingestion/);
});

test('health and Overview failures are applied independently', async () => {
  const goodPanel = startOperations({ storedAdmin: 'admin-secret', health: 'error' });
  await goodPanel.flush();
  assert.equal(goodPanel.text('health'), 'API unavailable');
  assert.match(goodPanel.html('overviewMetrics'), /Last successful ingestion/);

  const goodHealth = startOperations({ storedAdmin: 'admin-secret', overview: 'error' });
  await goodHealth.flush();
  assert.equal(goodHealth.text('health'), 'API available');
  assert.match(goodHealth.text('overviewStatus'), /Unable to load overview/);
});

test('Overview exposes inspection links, API severity, and dependency observation times', async () => {
  const overview = require('./test-support').overviewFixture;
  const dashboard = startOperations({
    storedAdmin: 'admin-secret',
    overview: {
      ...overview,
      queue: { ...overview.queue, pending: 2, retrying: 1, waiting: 3, unresolved: 4 },
      signals: [
        { code: 'TERMINAL_DOCUMENTS', severity: 'ERROR', count: 2 },
        { code: 'DUE_DOCUMENTS', severity: 'INFO', count: 1 },
      ],
      dependencies: [{ provider: 'finnhub', configured: true, status: 'UNOBSERVED',
        observedSince: '2026-10-01T00:00:00Z', lastObservedAt: '2026-10-03T12:00:00Z',
        lastSuccessAt: '2026-10-02T12:00:00Z', failedObservations: 0, lastErrorCode: null }],
    },
  });
  await dashboard.flush();

  assert.match(dashboard.html('overviewMetrics'), /Inspect waiting documents/);
  assert.match(dashboard.html('overviewMetrics'), /status=PENDING&amp;status=RETRYABLE_ERROR/);
  assert.match(dashboard.html('overviewMetrics'), /Unresolved: 4/);
  assert.match(dashboard.html('overviewMetrics'), /status=UNRESOLVED/);
  assert.match(dashboard.html('overviewSignals'), /pill bad[^>]*>ERROR/);
  assert.match(dashboard.html('overviewSignals'), /pill info[^>]*>INFO/);
  assert.match(dashboard.html('overviewDependencies'), /last observed/);
  assert.match(dashboard.html('overviewDependencies'), /10\/3\/2026/);

  dashboard.click('overviewMetrics', {
    target: { closest: () => ({ dataset: { localRoute: '?view=documents&status=UNRESOLVED' } }) },
  });
  assert.equal(dashboard.window.location.search, '?view=documents&status=UNRESOLVED');
});

test('only an explicit local signal route is navigable', () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret' });
  assert.equal(dashboard.context.window.CatalystOperationsModel.signalRoute({ code: 'UNRECOGNIZED', url: 'https://evil.example' }), null);
  dashboard.popstate('?view=operations');
  assert.equal(dashboard.text('pageTitle'), 'Pipeline');
});

test('Settings loads only the safe configuration projection and never polls', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret', search: '?view=settings' });
  await dashboard.flush();
  assert.equal(dashboard.requests.filter((request) => new URL(request.url).pathname === '/internal/operations/config').length, 1);
  assert.match(dashboard.html('settingsConfig'), /Primary polygon · fallback finnhub/);
  assert.match(dashboard.html('settingsConfig'), /configured/);
  dashboard.tick();
  await dashboard.flush();
  assert.equal(dashboard.requests.filter((request) => new URL(request.url).pathname === '/internal/operations/config').length, 1);
  assert.doesNotMatch(dashboard.html('settingsConfig'), /secret|api.?key|password/i);
});

test('Settings retains the last configuration when a refresh fails', async () => {
  const outcomes = [require('./test-support').configFixture, 'error'];
  const dashboard = startOperations({ storedAdmin: 'admin-secret', search: '?view=settings', config: () => outcomes.shift() });
  await dashboard.flush();
  const savedHtml = dashboard.html('settingsConfig');
  const savedText = dashboard.text('settingsConfig');
  dashboard.click('refresh');
  await dashboard.flush();
  assert.equal(dashboard.html('settingsConfig'), savedHtml);
  assert.equal(dashboard.text('settingsConfig'), savedText);
  assert.match(dashboard.text('settingsStatus'), /Last loaded .*refresh failed/);
});

test('Settings resumes its initial configuration read after a hidden-tab abort', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret', search: '?view=settings', deferConfig: true });
  await dashboard.flush();
  dashboard.setHidden(true);
  assert.equal(dashboard.requests.filter((request) => new URL(request.url).pathname === '/internal/operations/config')[0].signal.aborted, true);
  dashboard.setHidden(false);
  await dashboard.flush();
  const reads = dashboard.requests.filter((request) => new URL(request.url).pathname === '/internal/operations/config');
  assert.equal(reads.length, 2);
  dashboard.resolveConfig(1, require('./test-support').configFixture);
  await dashboard.flush();
  assert.match(dashboard.html('settingsConfig'), /Primary polygon · fallback finnhub/);
});

test('Overview resumes an aborted initial read when auto-refresh is off', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret', deferOverview: true });
  await dashboard.flush();
  dashboard.elements.get('autoRefresh').checked = false;
  dashboard.elements.get('autoRefresh').trigger('change');
  dashboard.setHidden(true);
  dashboard.setHidden(false);
  await dashboard.flush();
  const reads = dashboard.requests.filter((request) => new URL(request.url).pathname === '/internal/operations/overview');
  assert.equal(reads.length, 2);
  assert.equal(reads[0].signal.aborted, true);
  dashboard.resolveOverview(1, require('./test-support').overviewFixture);
  await dashboard.flush();
  assert.match(dashboard.html('overviewMetrics'), /Last successful ingestion/);
});

test('access setup returns to the previous local operational route', async () => {
  const origin = '?view=documents&q=quarterly&status=RETRYABLE_ERROR';
  const dashboard = startOperations({ search: origin });
  await dashboard.flush();
  dashboard.click('documentStatus', {
    target: { closest: () => ({ dataset: { settingsAccess: 'true' } }) },
  });
  assert.equal(dashboard.window.location.search, '?view=settings');
  assert.equal(dashboard.window.history.state.returnSearch, origin);

  dashboard.text('adminKey');
  dashboard.elements.get('adminKey').value = 'admin-secret';
  dashboard.click('saveKey');
  await dashboard.flush();
  assert.equal(dashboard.window.location.search, origin);
  assert.equal(dashboard.values.get('catalyst-admin-key'), 'admin-secret');
});

test('other no-admin operational screens show access guidance without protected reads', async () => {
  for (const view of ['pipeline', 'documents', 'models']) {
    const dashboard = startOperations({ search: '?view=' + view });
    await dashboard.flush();
    assert.equal(dashboard.requests.some((request) => new URL(request.url).pathname.startsWith('/internal/')), false);
    const status = { pipeline: 'pipelineStatus', documents: 'documentStatus', models: 'modelStatus' }[view];
    assert.match(dashboard.html(status), /Admin access required/);
  }
});

test('controller exposes fixed lifecycle hooks and dispose stops its own timer', () => {
  const dashboard = startOperations();
  const controller = dashboard.context.window.CatalystOperations.create({
    api: async () => ({}),
    navigate: () => {},
    credentials: () => ({ hasAdmin: false, revision: 0 }),
    format: { escape: String },
  });
  for (const method of ['init', 'show', 'refresh', 'credentialsChanged', 'dispose', 'ownsView']) {
    assert.equal(typeof controller[method], 'function');
  }
  assert.equal(controller.ownsView('overview'), true);
  assert.equal(controller.ownsView('pipeline'), true);
  assert.equal(controller.ownsView('intelligence'), false);
  const existingTimers = dashboard.intervalCount;
  controller.init();
  assert.equal(dashboard.intervalCount, existingTimers + 1);
  controller.dispose();
  assert.equal(dashboard.intervalCount, existingTimers);
});
