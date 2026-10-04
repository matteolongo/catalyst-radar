const assert = require('node:assert/strict');
const test = require('node:test');
const { startDashboard } = require('./test-support');
const DOC_A = '11111111-1111-4111-8111-111111111111';
const DOC_B = '22222222-2222-4222-8222-222222222222';
const DOC_C = '66666666-6666-4666-8666-666666666666';
const DOC_XSS = '88888888-8888-4888-8888-888888888888';
const DOC_UNTRACKED = '99999999-9999-4999-8999-999999999999';
const DOC_SKIPPED = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const DOC_NEW = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const DOC_OLD = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';

function startOperations(options = {}) {
  const stored = { ...(options.stored || {}) };
  if (options.storedAdmin) stored['catalyst-admin-key'] = options.storedAdmin;
  if (options.storedApiKey) stored['catalyst-api-key'] = options.storedApiKey;
  const dashboard = startDashboard({ ...options, stored });
  dashboard.openDocuments = async (search = '?view=documents') => {
    dashboard.popstate(search);
    await dashboard.flush();
    return dashboard;
  };
  dashboard.openDocument = async (id, tab = 'overview') => {
    const route = new URLSearchParams({ view: 'documents', documentId: id, documentTab: tab });
    dashboard.popstate('?' + route.toString());
    await dashboard.flush();
    return dashboard;
  };
  dashboard.selectDocumentTab = async (tab) => {
    const name = tab[0].toUpperCase() + tab.slice(1);
    dashboard.click('documentTab' + name);
    await dashboard.flush();
    return dashboard;
  };
  return dashboard;
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

test('a slow document response cannot replace a newer selected document', async () => {
  const support = require('./test-support');
  const dashboard = startOperations({ storedAdmin: 'admin-secret', deferDocuments: true });
  await dashboard.openDocument(DOC_A);
  await dashboard.openDocument(DOC_B);
  dashboard.resolveDocument(DOC_B, support.documentDetailFixture(DOC_B));
  await dashboard.flush();
  assert.equal(dashboard.text('documentDetailTitle'), 'Source ' + DOC_B);
  dashboard.resolveDocument(DOC_A, support.documentDetailFixture(DOC_A));
  await dashboard.flush();
  assert.equal(dashboard.text('documentDetailTitle'), 'Source ' + DOC_B);
});

test('Documents filters keep repeated states, normalize tickers, and use an exclusive UTC through boundary', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret', search: '?view=overview&range=7d' });
  await dashboard.openDocuments('?view=documents&q=%20guidance%20&provider=polygon&ticker=acme&status=PENDING&status=RETRYABLE_ERROR&from=2026-10-01T00%3A00%3A00Z&to=2026-10-04T00%3A00%3A00Z&dueOnly=true');
  const request = dashboard.requests.find((entry) => new URL(entry.url).pathname === '/internal/operations/documents');
  assert.ok(request);
  const query = new URL(request.url).searchParams;
  assert.deepEqual(query.getAll('status'), ['PENDING', 'RETRYABLE_ERROR']);
  assert.equal(query.get('q'), 'guidance');
  assert.equal(query.get('ticker'), 'ACME');
  assert.equal(query.get('provider'), 'polygon');
  assert.equal(query.get('from'), '2026-10-01T00:00:00Z');
  assert.equal(query.get('to'), '2026-10-04T00:00:00Z');
  assert.equal(query.get('dueOnly'), 'true');
  assert.equal(query.has('range'), false);
});

test('Documents routes clear filters omitted from the URL', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret' });
  await dashboard.openDocuments('?view=documents&q=guidance&status=PENDING&from=2026-10-01T00%3A00%3A00Z');
  await dashboard.openDocuments('?view=documents');
  const requests = dashboard.requests.filter((entry) => new URL(entry.url).pathname === '/internal/operations/documents');
  const query = new URL(requests.at(-1).url).searchParams;
  assert.equal(query.has('q'), false);
  assert.equal(query.has('status'), false);
  assert.equal(query.has('from'), false);
});

test('Documents preserve in-memory filters when leaving and returning through navigation', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret' });
  await dashboard.openDocuments('?view=documents&q=guidance');
  dashboard.click('navOverview');
  await dashboard.flush();
  dashboard.click('navDocuments');
  await dashboard.flush();
  const requests = dashboard.requests.filter((entry) => new URL(entry.url).pathname === '/internal/operations/documents');
  assert.equal(new URL(requests.at(-1).url).searchParams.get('q'), 'guidance');
});

test('Documents reject malformed resource IDs without issuing UUID-path requests', async () => {
  const invalidDocument = startOperations({ storedAdmin: 'admin-secret' });
  await invalidDocument.openDocuments('?view=documents&documentId=not-a-uuid');
  assert.match(invalidDocument.text('documentStatus'), /document ID.*UUID/i);
  assert.equal(invalidDocument.requests.some((entry) => new URL(entry.url).pathname.startsWith('/internal/operations/documents/')), false);

  const invalidAttempt = startOperations({ storedAdmin: 'admin-secret' });
  await invalidAttempt.openDocuments('?view=documents&documentId=11111111-1111-4111-8111-111111111111&attemptId=not-a-uuid');
  assert.match(invalidAttempt.text('documentStatus'), /attempt ID.*UUID/i);
  assert.equal(invalidAttempt.requests.some((entry) => new URL(entry.url).pathname.startsWith('/internal/operations/documents/')), false);
});

test('Documents UTC boundaries reject offset timestamps that date inputs cannot preserve', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret' });
  await dashboard.openDocuments('?view=documents&from=2026-10-03T21%3A00%3A00-05%3A00');
  assert.match(dashboard.text('documentStatus'), /ISO UTC/i);
  assert.equal(dashboard.requests.some((entry) => new URL(entry.url).pathname === '/internal/operations/documents'), false);
});

test('Documents UTC boundaries reject impossible calendar dates', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret' });
  await dashboard.openDocuments('?view=documents&from=2026-02-30T00%3A00%3A00Z');
  assert.match(dashboard.text('documentStatus'), /ISO UTC/i);
  assert.equal(dashboard.requests.some((entry) => new URL(entry.url).pathname === '/internal/operations/documents'), false);
});

test('Documents Apply clears detail selection and exclusive-date validation blocks malformed searches', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret' });
  await dashboard.openDocuments('?view=documents&documentId=' + DOC_A + '&documentTab=attempts&attemptId=33333333-3333-4333-8333-333333333333');
  const initialCount = dashboard.requests.filter((entry) => new URL(entry.url).pathname === '/internal/operations/documents').length;
  dashboard.element('documentFrom').value = '2026-10-04';
  dashboard.element('documentTo').value = '2026-10-03';
  dashboard.element('documentFilters').trigger('submit');
  await dashboard.flush();
  assert.equal(dashboard.window.location.search.includes('documentId='), false);
  assert.equal(dashboard.window.location.search.includes('documentTab='), false);
  assert.equal(dashboard.window.location.search.includes('attemptId='), false);
  assert.equal(dashboard.requests.filter((entry) => new URL(entry.url).pathname === '/internal/operations/documents').length, initialCount);
  assert.match(dashboard.text('documentStatus'), /before|earlier|valid/i);
});

test('Documents Apply converts the through date into the next UTC midnight and repeats selected states', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret' });
  await dashboard.openDocuments();
  dashboard.element('documentFrom').value = '2026-10-01';
  dashboard.element('documentTo').value = '2026-10-03';
  dashboard.element('documentStates').selectedOptions = [{ value: 'PENDING' }, { value: 'RETRYABLE_ERROR' }];
  dashboard.element('documentFilters').trigger('submit');
  await dashboard.flush();
  const request = dashboard.requests.filter((entry) => new URL(entry.url).pathname === '/internal/operations/documents').at(-1);
  const query = new URL(request.url).searchParams;
  assert.equal(query.get('from'), '2026-10-01T00:00:00.000Z');
  assert.equal(query.get('to'), '2026-10-04T00:00:00.000Z');
  assert.deepEqual(query.getAll('status'), ['PENDING', 'RETRYABLE_ERROR']);
  assert.equal(new URL(dashboard.window.location.href || 'https://ops.example' + dashboard.window.location.search).searchParams.has('range'), false);
});

test('deep-linked Documents open even when absent from the loaded page, and Back closes the pane', async () => {
  const support = require('./test-support');
  const emptyPage = { ...support.documentsPageFixture, items: [] };
  const dashboard = startOperations({ storedAdmin: 'admin-secret', documents: emptyPage });
  await dashboard.openDocuments('?view=documents&documentId=' + DOC_A);
  assert.equal(dashboard.text('documentDetailTitle'), 'Source ' + DOC_A);
  assert.equal(dashboard.element('documentDetail').hidden, false);
  dashboard.popstate('?view=documents');
  await dashboard.flush();
  assert.equal(dashboard.window.location.search, '?view=documents');
  assert.equal(dashboard.element('documentDetail').hidden, true);
});

test('selecting a document stores its ID in the URL and closing restores opener focus', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret' });
  await dashboard.openDocuments();
  let focused = false;
  const opener = { dataset: { documentId: DOC_A }, focus() { focused = true; } };
  dashboard.click('documentRows', {
    target: { closest: (selector) => selector === '[data-document-id]' ? opener : null },
  });
  await dashboard.flush();
  assert.equal(new URLSearchParams(dashboard.window.location.search.slice(1)).get('documentId'), DOC_A);
  dashboard.click('closeDocument');
  await dashboard.flush();
  assert.equal(dashboard.window.location.search.startsWith('?view=documents'), true);
  assert.equal(new URLSearchParams(dashboard.window.location.search.slice(1)).has('documentId'), false);
  assert.equal(focused, true);
});

test('document list escapes titles, shows derived states, and never includes source body before selection', async () => {
  const support = require('./test-support');
  const items = [
    support.documentListItemFixture(DOC_XSS, { title: '<script>alert(1)</script>', state: 'UNRESOLVED', tickers: ['<img src=x>'] }),
    support.documentListItemFixture(DOC_UNTRACKED, { state: 'NOT_TRACKED', lastErrorMessage: null }),
    support.documentListItemFixture(DOC_SKIPPED, { state: 'SKIPPED', lastErrorMessage: null }),
  ];
  const dashboard = startOperations({ storedAdmin: 'admin-secret', documents: { ...support.documentsPageFixture, items } });
  await dashboard.openDocuments();
  assert.match(dashboard.html('documentRows'), /&lt;script&gt;alert\(1\)&lt;\/script&gt;/);
  assert.doesNotMatch(dashboard.html('documentRows'), /<script>alert\(1\)<\/script>/);
  assert.match(dashboard.html('documentRows'), /Unresolved/);
  assert.match(dashboard.html('documentRows'), /Not tracked/);
  assert.match(dashboard.html('documentRows'), /Skipped/);
  assert.doesNotMatch(dashboard.html('documentsView'), /Quoted source:/);
  assert.match(dashboard.text('documentStatus'), /3 loaded/);
});

test('load more retains rows after failure and retries with ID deduplication', async () => {
  const support = require('./test-support');
  const first = { ...support.documentsPageFixture, items: [support.documentListItemFixture(DOC_A), support.documentListItemFixture(DOC_B)], nextCursor: 'next-token' };
  const last = { ...support.documentsPageFixture, items: [support.documentListItemFixture(DOC_B), support.documentListItemFixture(DOC_C)], nextCursor: null };
  const pages = [first, 'error', last];
  const dashboard = startOperations({ storedAdmin: 'admin-secret', documents: () => pages.shift() });
  await dashboard.openDocuments();
  const original = dashboard.html('documentRows');
  dashboard.click('loadDocuments');
  await dashboard.flush();
  assert.equal(dashboard.html('documentRows').includes(DOC_A), true);
  assert.match(dashboard.text('documentStatus'), /failed|unable|retry/i);
  dashboard.click('loadDocuments');
  await dashboard.flush();
  const rows = dashboard.html('documentRows');
  assert.match(rows, new RegExp(DOC_A));
  assert.match(rows, new RegExp(DOC_B));
  assert.match(rows, new RegExp(DOC_C));
  assert.equal((rows.match(new RegExp('data-document-id="' + DOC_B + '"', 'g')) || []).length, 1);
  assert.notEqual(original, rows);
  const requests = dashboard.requests.filter((entry) => new URL(entry.url).pathname === '/internal/operations/documents');
  assert.equal(new URL(requests[1].url).searchParams.get('cursor'), 'next-token');
  assert.equal(new URL(requests[2].url).searchParams.get('cursor'), 'next-token');
});

test('Load more is disabled while its request is pending and ignores duplicate clicks', async () => {
  const support = require('./test-support');
  const first = { ...support.documentsPageFixture, items: [support.documentListItemFixture(DOC_A)], nextCursor: 'next-token' };
  const dashboard = startOperations({ storedAdmin: 'admin-secret', deferDocumentList: true });
  await dashboard.openDocuments();
  dashboard.resolveDocumentList(0, first);
  await dashboard.flush();
  dashboard.click('loadDocuments');
  await dashboard.flush();
  assert.equal(dashboard.element('loadDocuments').disabled, true);
  dashboard.click('loadDocuments');
  await dashboard.flush();
  assert.equal(dashboard.requests.filter((entry) => new URL(entry.url).pathname === '/internal/operations/documents').length, 2);
  dashboard.resolveDocumentList(1, { ...support.documentsPageFixture, items: [support.documentListItemFixture(DOC_B)] });
  await dashboard.flush();
  assert.equal(dashboard.element('loadDocuments').disabled, true);
});

test('a changed document filter aborts the old page and applies only the newest result', async () => {
  const support = require('./test-support');
  const dashboard = startOperations({ storedAdmin: 'admin-secret', deferDocumentList: true });
  await dashboard.openDocuments('?view=documents&q=old');
  dashboard.element('documentTitleFilter').value = 'new';
  dashboard.element('documentFilters').trigger('submit');
  await dashboard.flush();
  const requests = dashboard.requests.filter((entry) => new URL(entry.url).pathname === '/internal/operations/documents');
  assert.equal(requests.length, 2);
  assert.equal(requests[0].signal.aborted, true);
  dashboard.resolveDocumentList(1, { ...support.documentsPageFixture, items: [support.documentListItemFixture(DOC_NEW, { title: 'New result' })] });
  await dashboard.flush();
  dashboard.resolveDocumentList(0, { ...support.documentsPageFixture, items: [support.documentListItemFixture(DOC_OLD, { title: 'Old result' })] });
  await dashboard.flush();
  assert.match(dashboard.html('documentRows'), /New result/);
  assert.doesNotMatch(dashboard.html('documentRows'), /Old result/);
});

test('source body is lazy, escaped, visibly truncated, and unsafe source URLs are refused', async () => {
  const support = require('./test-support');
  const detail = support.documentDetailFixture(DOC_A, { canonicalUrl: 'javascript:alert(1)' });
  const dashboard = startOperations({ storedAdmin: 'admin-secret', documentDetail: detail });
  await dashboard.openDocument(DOC_A);
  assert.equal(dashboard.requests.some((entry) => new URL(entry.url).pathname.endsWith('/body')), false);
  assert.equal(dashboard.requests.some((entry) => new URL(entry.url).pathname.endsWith('/events')), false);
  assert.doesNotMatch(dashboard.html('documentDetail'), /href="javascript:/i);
  await dashboard.selectDocumentTab('source');
  assert.equal(dashboard.requests.filter((entry) => new URL(entry.url).pathname.endsWith('/body')).length, 1);
  assert.match(dashboard.html('documentSource'), /&lt;script&gt;alert\(1\)&lt;\/script&gt;/);
  assert.doesNotMatch(dashboard.html('documentSource'), /<script>alert\(1\)<\/script>/);
  assert.match(dashboard.html('documentSource'), /truncated/i);
  assert.match(dashboard.html('documentSource'), /50,000/);
});

test('approved source links open separately with noopener and noreferrer', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret' });
  await dashboard.openDocument(DOC_A, 'source');
  assert.match(dashboard.html('documentSource'), /href="https:\/\/example\.com\/filing" target="_blank" rel="noopener noreferrer"/);
});

test('document tabs load attempts, source-linked model calls, and events only when selected', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret' });
  await dashboard.openDocument(DOC_A);
  assert.equal(dashboard.requests.some((entry) => /\/(attempts|model-runs|events)$/.test(new URL(entry.url).pathname)), false);
  await dashboard.selectDocumentTab('attempts');
  assert.match(dashboard.html('documentAttempts'), /No individual attempts recorded|Attempt 2/);
  assert.match(dashboard.html('documentAttempts'), /model-run-id=/);
  assert.match(dashboard.html('documentAttempts'), /data-operation-run-id="44444444-4444-4444-8444-444444444444"/);
  await dashboard.selectDocumentTab('models');
  assert.match(dashboard.html('documentModels'), /model-v1/);
  assert.equal(dashboard.requests.filter((entry) => new URL(entry.url).pathname.endsWith('/attempts')).length, 1);
  assert.equal(dashboard.requests.filter((entry) => new URL(entry.url).pathname.endsWith('/model-runs')).length, 1);
  await dashboard.selectDocumentTab('events');
  assert.match(dashboard.html('documentEvents'), /GUIDANCE_RAISE/);
  assert.match(dashboard.html('documentEvents'), /2026-10-03T10:00:00Z/);
  assert.match(dashboard.html('documentEvents'), /77777777-7777-4777-8777-777777777777/);
  await dashboard.selectDocumentTab('attempts');
  assert.equal(dashboard.requests.filter((entry) => new URL(entry.url).pathname.endsWith('/attempts')).length, 1);
});

test('empty attempt ledger explains unrecorded attempts and does not invent rows', async () => {
  const support = require('./test-support');
  const detail = support.documentDetailFixture(DOC_A, {
    document: support.documentListItemFixture(DOC_A, { attemptCount: 4 }),
    capturedAttemptCount: 0,
    unrecordedAttemptCount: 4,
  });
  const page = { ...support.documentAttemptsPageFixture, items: [] };
  const dashboard = startOperations({ storedAdmin: 'admin-secret', documentDetail: detail, documentAttempts: page });
  await dashboard.openDocument(DOC_A, 'attempts');
  assert.match(dashboard.html('documentAttempts'), /No individual attempts recorded/);
  assert.match(dashboard.html('documentAttempts'), /Latest processing attempt count: 4/);
  assert.match(dashboard.html('documentAttempts'), /4 earlier processing attempt/);
  assert.doesNotMatch(dashboard.html('documentAttempts'), /data-attempt-id=/);
});

test('an attemptId forces the Attempts tab and focuses a loaded row', async () => {
  const attemptId = '33333333-3333-4333-8333-333333333333';
  const dashboard = startOperations({ storedAdmin: 'admin-secret' });
  await dashboard.openDocuments('?view=documents&documentId=' + DOC_A + '&attemptId=' + attemptId);
  assert.equal(dashboard.element('documentTabAttempts').getAttribute('aria-selected'), 'true');
  assert.match(dashboard.html('documentAttempts'), new RegExp('data-attempt-id="' + attemptId + '"[^>]*aria-current="true"'));
  assert.equal(dashboard.document.activeElement.dataset.attemptId, attemptId);
});

test('a failed document Events tab leaves its Overview available', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret', documentEvents: 'error' });
  await dashboard.openDocument(DOC_A);
  const overview = dashboard.html('documentOverview');
  assert.match(overview, /Latest processing attempt count/);
  await dashboard.selectDocumentTab('events');
  assert.match(dashboard.html('documentEvents'), /Unable to load this tab/);
  assert.equal(dashboard.html('documentOverview'), overview);
});

test('document model-call links preserve the local document route and expose no date window', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret' });
  await dashboard.openDocument(DOC_A, 'models');
  const request = dashboard.requests.find((entry) => new URL(entry.url).pathname.endsWith('/model-runs'));
  assert.ok(request);
  assert.equal(new URL(request.url).searchParams.has('range'), false);
  const current = dashboard.window.location.search;
  dashboard.click('documentDetail', {
    target: { closest: (selector) => selector === '[data-model-run-id]'
      ? { dataset: { modelRunId: '55555555-5555-4555-8555-555555555555' } } : null },
  });
  assert.equal(dashboard.window.location.search, '?view=models&modelRunId=55555555-5555-4555-8555-555555555555');
  assert.equal(dashboard.window.history.state.returnSearch, current);
});

test('no-admin Documents show access guidance and issue no protected reads', async () => {
  const dashboard = startOperations({ search: '?view=documents' });
  await dashboard.flush();
  assert.match(dashboard.html('documentStatus'), /Admin access required/);
  assert.equal(dashboard.element('documentFilters').hidden, true);
  assert.equal(dashboard.element('documentRows').hidden, true);
  assert.equal(dashboard.requests.some((entry) => new URL(entry.url).pathname.startsWith('/internal/operations/documents')), false);
});
