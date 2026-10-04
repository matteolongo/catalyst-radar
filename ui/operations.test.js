const assert = require('node:assert/strict');
const test = require('node:test');
const { startDashboard, modelSummaryFixture, modelCallFixture, modelCallsPageFixture, operationRunFixture,
  operationRunsPageFixture, operationRunDetailFixture, operationIssuesPageFixture, operationRunIdFixture,
  operationWindowFixture, ingestionRunFixture, ingestionRunsPageFixture, ingestionRunIdFixture, pipelineResultFixture } = require('./test-support');
const DOC_A = '11111111-1111-4111-8111-111111111111';
const DOC_B = '22222222-2222-4222-8222-222222222222';
const DOC_C = '66666666-6666-4666-8666-666666666666';
const DOC_XSS = '88888888-8888-4888-8888-888888888888';
const DOC_UNTRACKED = '99999999-9999-4999-8999-999999999999';
const DOC_SKIPPED = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const DOC_NEW = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const DOC_OLD = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';
const MODEL_CALL = '55555555-5555-4555-8555-555555555555';

test('missing call provenance and legacy null origins never imply a provider was not invoked', async () => {
  const support = require('./test-support');
  const attempts = { ...support.documentAttemptsPageFixture, items: [{
    ...support.documentAttemptsPageFixture.items[0], modelCallIds: [], modelCallsTotal: 0,
  }] };
  const dashboard = startOperations({ storedAdmin: 'admin-secret', documentAttempts: attempts,
    documentDetail: support.documentDetailFixture(DOC_A, { document: support.documentListItemFixture(DOC_A, {firstIngestionRunId:null}) }),
    documentModelRuns: { ...support.documentModelPageFixture, items: [] } });
  await dashboard.openDocument(DOC_A, 'attempts');
  assert.doesNotMatch(dashboard.html('documentOverview'), /ingestionRunId=null|data-ingestion-run-id=/);
  assert.match(dashboard.html('documentAttempts'), /missing provenance.*provider was invoked/i);
  await dashboard.selectDocumentTab('models');
  assert.match(dashboard.html('documentModels'), /missing provenance.*provider was invoked/i);
});

test('clearing keys during a detail refresh never restores protected data on a late success or error', async () => {
  for (const answer of [undefined, 'error']) {
    const dashboard = startOperations({ storedAdmin: 'admin-secret', deferDocuments: true });
    await dashboard.openDocument(DOC_A, 'source');
    dashboard.resolveDocument(DOC_A);
    await dashboard.flush();
    assert.match(dashboard.html('documentSource'), /Quoted source/);
    await dashboard.refresh();
    dashboard.click('clearKey');
    dashboard.resolveDocument(DOC_A, answer);
    await dashboard.flush();
    assert.equal(dashboard.html('documentSource'), '');
    assert.equal(dashboard.html('documentOverview'), '');
    assert.equal(dashboard.element('documentDetail').hidden, true);
    assert.equal(dashboard.requests.filter((r) => new URL(r.url).pathname.endsWith('/body')).length, 1);
  }
});

test('document tab authorization failure clears protected data and links to Settings', async () => {
  const support = require('./test-support');
  const dashboard = startOperations({ storedAdmin: 'admin-secret' });
  await dashboard.openDocument(DOC_A, 'source');
  assert.match(dashboard.html('documentSource'), /Quoted source/);
  assert.match(dashboard.html('documentRows'), /Quarterly filing/);
  await dashboard.selectDocumentTab('attempts');

  const fetch = dashboard.context.fetch;
  const denyAttempts = true;
  let releaseDocumentFeed;
  dashboard.context.fetch = (url, options) => {
    const path = new URL(url, 'https://ops.example').pathname;
    if (path === '/internal/operations/documents') {
      return new Promise((resolve) => {
        releaseDocumentFeed = () => resolve({ ok: true, status: 200, json: async () => support.documentsPageFixture });
      });
    }
    if (denyAttempts && path.endsWith('/attempts')) {
      return Promise.resolve({ ok: false, status: 403, json: async () => ({ code: 'FORBIDDEN' }) });
    }
    return fetch(url, options);
  };
  const refreshing = dashboard.refresh();
  await dashboard.flush();

  assert.match(dashboard.html('documentStatus'), /data-settings-access/);
  assert.equal(dashboard.element('documentRows').hidden, true);
  assert.equal(dashboard.element('documentDetail').hidden, true);
  assert.equal(dashboard.html('documentRows'), '');
  assert.equal(dashboard.html('documentSource'), '');
  assert.equal(dashboard.html('documentAttempts'), '');
  releaseDocumentFeed();
  await dashboard.flush();
  await refreshing;
  assert.equal(dashboard.html('documentRows'), '');
  assert.match(dashboard.html('documentStatus'), /data-settings-access/);
});

test('model authorization failure clears protected data and links to Settings', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret', deferModelDetails: true });
  await dashboard.openModels('?view=models&modelRunId=' + MODEL_CALL);
  assert.equal(dashboard.text('modelCallsTotal'), '1');
  assert.match(dashboard.html('modelRows'), /model-v1/);
  assert.equal(dashboard.requests.filter((request) => new URL(request.url).pathname.endsWith('/model-runs/' + MODEL_CALL)).length, 1);
  dashboard.resolveModelDetail(MODEL_CALL, modelCallFixture(MODEL_CALL));
  await dashboard.flush();
  assert.match(dashboard.html('modelDetailContent'), /model-v1/);

  const fetch = dashboard.context.fetch;
  dashboard.context.fetch = (url, options) => {
    if (new URL(url, 'https://ops.example').pathname.endsWith('/model-summary')) {
      return Promise.resolve({ ok: false, status: 403, json: async () => ({ code: 'FORBIDDEN' }) });
    }
    return fetch(url, options);
  };
  const refreshing = dashboard.refresh();
  await dashboard.flush();

  assert.match(dashboard.html('modelStatus'), /data-settings-access/);
  assert.equal(dashboard.element('modelRows').hidden, true);
  assert.equal(dashboard.element('modelDetail').hidden, true);
  assert.equal(dashboard.text('modelCallsTotal'), 'Unknown');
  assert.doesNotMatch(dashboard.html('modelRows'), /model-v1/);
  dashboard.resolveModelDetail(MODEL_CALL, modelCallFixture(MODEL_CALL));
  await dashboard.flush();
  await refreshing;
  assert.equal(dashboard.html('modelDetailContent'), '');
  assert.match(dashboard.html('modelStatus'), /data-settings-access/);
});

test('changing a source during an event page fetch discards both late data and errors', async () => {
  const support = require('./test-support');
  for (const answer of ['error', { ...support.documentEventsFixture, events: [{
    ...support.documentEventsFixture.events[0], type: 'OBSOLETE_EVENT',
  }] }]) {
    const route = '/internal/operations/documents/' + DOC_A + '/events';
    const dashboard = startOperations({ storedAdmin: 'admin-secret', deferPublicPaths: [route] });
    await dashboard.openDocument(DOC_A, 'events');
    await dashboard.openDocument(DOC_B, 'events');
    const current = dashboard.html('documentEvents');
    dashboard.resolvePublic(route, 0, answer);
    await dashboard.flush();
    assert.equal(dashboard.html('documentEvents'), current);
    assert.doesNotMatch(dashboard.html('documentEvents'), /OBSOLETE_EVENT|Unable to load/);
  }
});

test('a source model call returns to the exact originating document filters and tab', async () => {
  const origin = '?view=documents&status=RETRYABLE_ERROR&runId=' + DOC_B + '&documentId=' + DOC_A + '&documentTab=models';
  const dashboard = startOperations({ storedAdmin: 'admin-secret', search: origin });
  await dashboard.flush();
  dashboard.click('documentDetail', { target: { closest: (selector) => selector === '[data-model-run-id]'
    ? { dataset: { modelRunId: MODEL_CALL } } : null } });
  await dashboard.flush();
  assert.equal(dashboard.window.location.search, '?view=models&modelRunId=' + MODEL_CALL);
  dashboard.click('closeModel');
  await dashboard.flush();
  assert.equal(dashboard.window.location.search, origin);
  assert.equal(dashboard.element('documentModels').hidden, false);
});

test('run-associated Documents reads keep older discovery records without imposing an activity cutoff', async () => {
  const support = require('./test-support');
  const dashboard = startOperations({ storedAdmin: 'admin-secret', documents: {
    ...support.documentsPageFixture, items: [support.documentListItemFixture(DOC_A, { discoveredAt: '2020-01-01T00:00:00Z' })],
  } });
  await dashboard.openDocuments('?view=documents&runId=' + DOC_B);
  const request = dashboard.requests.find((r) => new URL(r.url).pathname === '/internal/operations/documents');
  assert.equal(new URL(request.url).searchParams.get('runId'), DOC_B);
  assert.equal(new URL(request.url).searchParams.has('from'), false);
  assert.equal(new URL(request.url).searchParams.has('to'), false);
  assert.match(dashboard.html('documentRows'), /2020/);
});

test('polling a selected source preserves its tab and filters while showing the new processing outcome', async () => {
  const support = require('./test-support');
  let state = 'RETRYABLE_ERROR';
  const origin = '?view=documents&status=RETRYABLE_ERROR&documentId=' + DOC_A + '&documentTab=source';
  const dashboard = startOperations({ storedAdmin: 'admin-secret', search: origin,
    documentDetail: (_url, id) => support.documentDetailFixture(id, { document: support.documentListItemFixture(id, { state }) }) });
  await dashboard.flush();
  const source = dashboard.html('documentSource');
  state = 'COMPLETED';
  dashboard.tick();
  await dashboard.flush();
  assert.equal(dashboard.window.location.search, origin);
  assert.equal(dashboard.element('documentTabSource').getAttribute('aria-selected'), 'true');
  assert.match(dashboard.html('documentOverview'), /Completed/);
  assert.equal(dashboard.html('documentSource'), source);
  assert.equal(dashboard.requests.filter((r) => new URL(r.url).pathname.endsWith('/body')).length, 1);
});

test('a model failure after a response retains known paid usage and cost in the source journey', async () => {
  const support = require('./test-support');
  const failed = modelCallFixture(MODEL_CALL, { success: false, errorCode: 'MALFORMED_OUTPUT',
    errorMessage: 'Returned JSON could not be validated.', inputTokens: 123, outputTokens: 45, estimatedCost: 0.002 });
  const dashboard = startOperations({ storedAdmin: 'admin-secret', modelDetail: failed,
    documentModelRuns: { ...support.documentModelPageFixture, items: [failed] } });
  await dashboard.openDocument(DOC_A, 'models');
  assert.match(dashboard.html('documentModels'), /Recorded call failed/);
  assert.match(dashboard.html('documentModels'), /123 input.*45 output/);
  assert.match(dashboard.html('documentModels'), /\$0\.002000/);
  await dashboard.openModels('?view=models&modelRunId=' + MODEL_CALL);
  assert.match(dashboard.html('modelDetailContent'), /MALFORMED_OUTPUT/);
  assert.match(dashboard.html('modelDetailContent'), /123/);
  assert.match(dashboard.html('modelDetailContent'), /\$0\.002000/);
});

test('an empty captured dataset stays empty across screens and explains disabled daily jobs', async () => {
  const support = require('./test-support');
  const dashboard = startOperations({ storedAdmin: 'admin-secret',
    documents: { ...support.documentsPageFixture, items: [] }, modelSummary: modelSummaryFixture(0, 0, 0),
    modelCalls: modelCallsPageFixture(0), operationRuns: operationRunsPageFixture(0), ingestionRuns: ingestionRunsPageFixture(0),
    config: { ...support.configFixture, ingestion: { ...support.configFixture.ingestion, enabled: false },
      snapshots: { ...support.configFixture.snapshots, enabled: false } },
  });
  await dashboard.openDocuments();
  assert.match(dashboard.html('documentRows'), /No captured documents/);
  assert.doesNotMatch(dashboard.html('documentRows'), /data-document-id=/);
  await dashboard.openModels();
  assert.equal(dashboard.text('modelCallsTotal'), '0');
  assert.equal(dashboard.loadedModelRows(), 0);
  assert.match(dashboard.html('modelRows'), /No recorded model calls/);
  await dashboard.openPipeline('?view=pipeline&kind=DAILY_SNAPSHOTS');
  assert.match(dashboard.html('runRows'), /Daily snapshots are disabled\. No cycles are recorded/);
  assert.match(dashboard.html('ingestionRows'), /No provider ingestion runs recorded/);
  assert.doesNotMatch(dashboard.html('runRows'), /data-run-id=/);
  assert.equal(dashboard.requests.some(r => r.method === 'POST'), false);
});

test('Documents opens company Intelligence and the return control restores the document context', async () => {
  const origin = '?view=documents&status=COMPLETED&documentId=' + DOC_A + '&documentTab=events';
  const dashboard = startDashboard({ search: origin, stored: { 'catalyst-admin-key': 'admin-secret' } });
  await dashboard.flush();
  dashboard.click('documentDetail', { target: { closest: (selector) => selector === '[data-document-company]'
    ? { dataset: { documentCompany: 'DELL' } } : null } });
  await dashboard.flush();
  assert.equal(dashboard.window.location.search, '?view=company&ticker=DELL');
  assert.equal(dashboard.historyState.returnSearch, origin);
  assert.deepEqual(Object.keys(dashboard.historyState), ['returnSearch']);
  assert.equal(dashboard.element('navIntelligence').getAttribute('aria-current'), 'page');
  assert.equal(dashboard.element('navDocuments').getAttribute('aria-current'), null);
  dashboard.click('backToResults');
  await dashboard.flush();
  assert.equal(dashboard.window.location.search, origin);
  assert.equal(dashboard.element('documentDetail').hidden, false);
  assert.equal(dashboard.element('documentEvents').hidden, false);
});

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
  dashboard.openModels = async (search = '?view=models') => {
    dashboard.popstate(search);
    await dashboard.flush();
    return dashboard;
  };
  dashboard.openOverview = async (search = '?view=overview') => {
    dashboard.popstate(search);
    await dashboard.flush();
    return dashboard;
  };
  dashboard.openPipeline = async (search = '?view=pipeline') => {
    dashboard.popstate(search);
    await dashboard.flush();
    return dashboard;
  };
  dashboard.refresh = async () => {
    dashboard.click('refresh');
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

test('model totals are independent of the currently loaded page', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret', modelSummary: modelSummaryFixture(61, 60, 0.60), modelPageSize: 25 });
  await dashboard.openModels();
  await dashboard.flush();
  assert.equal(dashboard.text('modelCallsTotal'), '61');
  assert.equal(dashboard.text('modelCostTotal'), '$0.600000 · partial (60/61 calls)');
  assert.equal(dashboard.loadedModelRows(), 25);
});

test('summary and paginated calls share filters and the summary resolved window', async () => {
  const summary = modelSummaryFixture(4, 3, 0.04);
  const dashboard = startOperations({
    storedAdmin: 'admin-secret', modelSummary: summary,
    modelCalls: (url) => modelCallsPageFixture(1, { nextCursor: url.searchParams.has('cursor') ? null : 'next-page' }),
  });
  await dashboard.openModels('?view=models&range=7d&provider=openai&operation=extract&model=gpt-4&success=false' +
    '&documentId=' + DOC_A + '&attemptId=' + DOC_C + '&runId=' + DOC_B);
  dashboard.click('loadModels');
  await dashboard.flush();

  const summaryRequest = dashboard.requests.find((request) => new URL(request.url).pathname.endsWith('/model-summary'));
  const pages = dashboard.requests.filter((request) => new URL(request.url).pathname.endsWith('/model-runs'));
  assert.equal(pages.length, 2);
  const summaryQuery = new URL(summaryRequest.url).searchParams;
  assert.equal(summaryQuery.get('range'), '7d');
  assert.equal(summaryQuery.get('provider'), 'openai');
  assert.equal(summaryQuery.get('operation'), 'extract');
  assert.equal(summaryQuery.get('model'), 'gpt-4');
  assert.equal(summaryQuery.get('success'), 'false');
  assert.equal(summaryQuery.get('documentId'), DOC_A);
  assert.equal(summaryQuery.get('attemptId'), DOC_C);
  assert.equal(summaryQuery.get('runId'), DOC_B);
  assert.equal(summaryQuery.has('limit'), false);

  for (const [index, request] of pages.entries()) {
    const query = new URL(request.url).searchParams;
    assert.equal(query.get('from'), summary.window.from);
    assert.equal(query.get('to'), summary.window.to);
    assert.equal(query.has('range'), false);
    for (const key of ['provider', 'operation', 'model', 'success', 'documentId', 'attemptId', 'runId']) {
      assert.equal(query.get(key), summaryQuery.get(key));
    }
    assert.equal(query.get('limit'), '25');
    assert.equal(query.get('cursor'), index === 0 ? null : 'next-page');
    assert.equal(request.headers['X-Admin-Key'], 'admin-secret');
    assert.equal(request.headers.Authorization, undefined);
  }
  assert.equal(summaryRequest.headers['X-Admin-Key'], 'admin-secret');
});

test('model coverage uses server totals and calls out truncated groups and unknown latency', async () => {
  const summary = modelSummaryFixture(4, 0, null, { groupsTruncated: true });
  summary.totals = {
    calls: 4, successfulCalls: 3, failedCalls: 1,
    inputTokens: 120, inputTokensKnownCalls: 2,
    outputTokens: 20, outputTokensExpectedCalls: 3, outputTokensKnownCalls: 2,
    estimatedCostUsd: null, costKnownCalls: 0,
    latencyKnownCalls: 0, p50LatencyMs: null, p95LatencyMs: null,
  };
  summary.groups = [
    { provider: 'openai', operation: 'extract', model: 'gpt-4', usage: summary.totals },
    { provider: 'openai', operation: 'embed', model: 'embed-v1', usage: {
      ...summary.totals, calls: 1, outputTokens: 999, outputTokensExpectedCalls: 0, outputTokensKnownCalls: 0,
    } },
  ];
  const dashboard = startOperations({ storedAdmin: 'admin-secret', modelSummary: summary, modelCalls: modelCallsPageFixture(0) });
  await dashboard.openModels();

  assert.equal(dashboard.text('modelCallsTotal'), '4');
  assert.equal(dashboard.text('modelFailedTotal'), '1');
  assert.equal(dashboard.text('modelCostTotal'), 'Unknown · no recorded cost (0/4 calls)');
  assert.match(dashboard.text('modelTokenSummary'), /Input tokens: 120.*2\/4 calls/);
  assert.match(dashboard.text('modelTokenSummary'), /Output tokens: 20.*2\/3 expected extract calls/);
  assert.match(dashboard.text('modelLatencySummary'), /p50: Unknown.*p95: Unknown.*0\/4 calls/);
  assert.match(dashboard.html('modelBreakdown'), /More groups are not shown/);
  assert.match(dashboard.html('modelBreakdown'), /embed-v1/);
  assert.equal(dashboard.loadedModelRows(), 0);
});

test('selected model detail is loaded by ID and links only its explicit associations', async () => {
  const detail = modelCallFixture(MODEL_CALL, {
    sourceDocumentId: DOC_A, attemptId: DOC_C, runId: DOC_B,
    success: false, errorCode: 'MODEL_REJECTED', errorMessage: '<script>alert(1)</script>',
  });
  const dashboard = startOperations({ storedAdmin: 'admin-secret', modelDetail: detail, modelCalls: modelCallsPageFixture(1) });
  await dashboard.openModels('?view=models&range=7d&modelRunId=' + MODEL_CALL);

  const detailRequest = dashboard.requests.find((request) => new URL(request.url).pathname.endsWith('/model-runs/' + MODEL_CALL));
  assert.ok(detailRequest);
  assert.equal(detailRequest.headers['X-Admin-Key'], 'admin-secret');
  assert.equal(new URL(detailRequest.url).search, '');
  assert.match(dashboard.html('modelDetailContent'), /MODEL_REJECTED/);
  assert.match(dashboard.html('modelDetailContent'), /&lt;script&gt;alert\(1\)&lt;\/script&gt;/);
  assert.doesNotMatch(dashboard.html('modelDetailContent'), /<script>/);
  assert.match(dashboard.html('modelDetailContent'), new RegExp('data-model-source-id="' + DOC_A + '"'));
  assert.match(dashboard.html('modelDetailContent'), new RegExp('data-model-attempt-id="' + DOC_C + '"'));
  assert.match(dashboard.html('modelDetailContent'), new RegExp('data-operation-run-id="' + DOC_B + '"'));
  assert.match(dashboard.html('modelRows'), /data-model-source-id=/);
  assert.match(dashboard.html('modelRows'), /data-model-attempt-id=/);
  assert.match(dashboard.html('modelRows'), /data-operation-run-id=/);

  dashboard.click('modelDetail', { target: { closest(selector) {
    return selector === '[data-model-attempt-id]' ? { dataset: { modelAttemptId: DOC_C } } : null;
  } } });
  assert.equal(dashboard.window.location.search, '?view=documents&documentId=' + DOC_A + '&documentTab=attempts&attemptId=' + DOC_C);
  assert.equal(dashboard.historyState.returnSearch, '?view=models&range=7d&modelRunId=' + MODEL_CALL);
});

test('an older selected call is fetched by ID and stale detail responses cannot replace it', async () => {
  const older = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd';
  const newer = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee';
  const dashboard = startOperations({ storedAdmin: 'admin-secret', deferModelDetails: true, modelCalls: modelCallsPageFixture(0) });
  await dashboard.openModels('?view=models&modelRunId=' + older);
  await dashboard.openModels('?view=models&modelRunId=' + newer);
  dashboard.resolveModelDetail(older, modelCallFixture(older, { model: 'stale-model' }));
  dashboard.resolveModelDetail(newer, modelCallFixture(newer, { model: 'current-model' }));
  await dashboard.flush();

  assert.match(dashboard.html('modelDetailContent'), /current-model/);
  assert.doesNotMatch(dashboard.html('modelDetailContent'), /stale-model/);
  assert.equal(dashboard.requests.filter((request) => new URL(request.url).pathname.includes('/model-runs/')).length, 2);
});

test('changing selected model calls clears the previous detail while the new detail loads or fails', async () => {
  const older = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd';
  const newer = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee';
  const dashboard = startOperations({ storedAdmin: 'admin-secret', deferModelDetails: true, modelCalls: modelCallsPageFixture(0) });
  await dashboard.openModels('?view=models&modelRunId=' + older);
  dashboard.resolveModelDetail(older, modelCallFixture(older, { model: 'previous-model' }));
  await dashboard.flush();
  assert.match(dashboard.html('modelDetailContent'), /previous-model/);

  await dashboard.openModels('?view=models&modelRunId=' + newer);
  assert.equal(dashboard.html('modelDetailContent'), '');
  assert.doesNotMatch(dashboard.html('modelDetailContent'), /previous-model/);
  dashboard.resolveModelDetail(newer, 'error');
  await dashboard.flush();
  assert.equal(dashboard.text('modelDetailStatus'), 'This recorded model call was not found.');
  assert.equal(dashboard.html('modelDetailContent'), '');
});

test('unlinked embedding calls keep missing usage unknown and known zero visible', async () => {
  const embedding = modelCallFixture(MODEL_CALL, {
    operation: 'embed', model: 'embed-v1', sourceDocumentId: null, attemptId: null, runId: null,
    inputTokens: null, outputTokens: 0, estimatedCost: null, latencyMs: null,
  });
  const dashboard = startOperations({ storedAdmin: 'admin-secret', modelDetail: embedding, modelCalls: modelCallsPageFixture(0) });
  await dashboard.openModels('?view=models&modelRunId=' + MODEL_CALL);
  const content = dashboard.html('modelDetailContent');
  assert.match(content, /No source association recorded/);
  assert.match(content, /<dt>Input tokens<\/dt><dd>Unknown<\/dd>/);
  assert.match(content, /<dt>Output tokens<\/dt><dd>0<\/dd>/);
  assert.match(content, /<dt>Estimated cost<\/dt><dd>Unknown<\/dd>/);
  assert.match(content, /<dt>Latency<\/dt><dd>Unknown<\/dd>/);
  assert.doesNotMatch(content, /data-model-source-id|data-model-attempt-id|data-operation-run-id/);
});

test('empty model history reports known zero cost and token sums', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret', modelSummary: modelSummaryFixture(0, 0, 0), modelCalls: modelCallsPageFixture(0) });
  await dashboard.openModels();
  assert.equal(dashboard.text('modelCallsTotal'), '0');
  assert.equal(dashboard.text('modelCostTotal'), '$0.000000 · 0/0 calls');
  assert.match(dashboard.text('modelTokenSummary'), /Input tokens: 0/);
  assert.match(dashboard.text('modelTokenSummary'), /Output tokens: 0/);
});

test('invalid model filters show an inline error and send no malformed model request', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret' });
  await dashboard.openModels('?view=models&provider=other');
  assert.match(dashboard.text('modelStatus'), /Choose OpenAI/);
  assert.equal(dashboard.requests.some((request) => /\/model-(summary|runs)(\/|$)/.test(new URL(request.url).pathname)), false);
});

test('applying model filters resets the cursor and selected-call route', async () => {
  const dashboard = startOperations({
    storedAdmin: 'admin-secret',
    modelCalls: (url) => modelCallsPageFixture(1, { nextCursor: url.searchParams.has('cursor') ? null : 'next-page' }),
  });
  await dashboard.openModels('?view=models&range=7d&modelRunId=' + MODEL_CALL);
  dashboard.click('loadModels');
  await dashboard.flush();
  dashboard.element('modelProvider').value = 'openai';
  dashboard.element('modelOperation').value = 'embed';
  dashboard.element('modelName').value = 'embed-v1';
  dashboard.element('modelSuccess').value = 'false';
  dashboard.element('modelFilters').trigger('submit');
  await dashboard.flush();

  const route = new URLSearchParams(dashboard.window.location.search.slice(1));
  assert.equal(route.get('range'), '7d');
  assert.equal(route.get('provider'), 'openai');
  assert.equal(route.get('operation'), 'embed');
  assert.equal(route.get('model'), 'embed-v1');
  assert.equal(route.get('success'), 'false');
  assert.equal(route.has('modelRunId'), false);
  const pages = dashboard.requests.filter((request) => new URL(request.url).pathname.endsWith('/model-runs'));
  assert.equal(pages.length, 3);
  assert.equal(new URL(pages[1].url).searchParams.get('cursor'), 'next-page');
  const resetPage = new URL(pages[2].url).searchParams;
  assert.equal(resetPage.has('cursor'), false);
  assert.equal(resetPage.get('operation'), 'embed');
  assert.equal(resetPage.get('from'), modelSummaryFixture().window.from);
  const summaries = dashboard.requests.filter((request) => new URL(request.url).pathname.endsWith('/model-summary'));
  assert.equal(summaries.length, 2);
  assert.equal(new URL(summaries[1].url).searchParams.get('range'), '7d');
  assert.equal(new URL(summaries[1].url).searchParams.get('operation'), 'embed');
});

test('stale model pages cannot replace the latest filter result', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret', deferModelCalls: true });
  await dashboard.openModels('?view=models&operation=extract');
  await dashboard.openModels('?view=models&operation=embed');
  dashboard.resolveModelCalls(0, modelCallsPageFixture(1, { items: [modelCallFixture(MODEL_CALL, { model: 'stale-page' })] }));
  dashboard.resolveModelCalls(1, modelCallsPageFixture(1, { items: [modelCallFixture(MODEL_CALL, { model: 'current-page' })] }));
  await dashboard.flush();
  assert.match(dashboard.html('modelRows'), /current-page/);
  assert.doesNotMatch(dashboard.html('modelRows'), /stale-page/);
});

test('model list associations navigate by their own explicit IDs', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret', modelCalls: modelCallsPageFixture(1) });
  await dashboard.openModels('?view=models&range=7d');
  dashboard.click('modelRows', { target: { closest(selector) {
    return selector === '[data-model-source-id]' ? { dataset: { modelSourceId: DOC_A } } : null;
  } } });
  assert.equal(dashboard.window.location.search, '?view=documents&documentId=' + DOC_A + '&documentTab=source');
  assert.equal(dashboard.historyState.returnSearch, '?view=models&range=7d');

  const operationDashboard = startOperations({ storedAdmin: 'admin-secret', modelCalls: modelCallsPageFixture(1) });
  await operationDashboard.openModels('?view=models&range=7d');
  operationDashboard.click('modelRows', { target: { closest(selector) {
    return selector === '[data-operation-run-id]' ? { dataset: { operationRunId: DOC_B } } : null;
  } } });
  assert.equal(operationDashboard.window.location.search, '?view=pipeline&runId=' + DOC_B);
  assert.equal(operationDashboard.historyState.returnSearch, '?view=models&range=7d');
});

test('a failed first model page retries without inventing a cursor', async () => {
  let reads = 0;
  const dashboard = startOperations({ storedAdmin: 'admin-secret', modelCalls: () => ++reads === 1 ? 'error' : modelCallsPageFixture(1) });
  await dashboard.openModels();
  assert.match(dashboard.text('modelStatus'), /Unable to load model calls/);
  dashboard.click('loadModels');
  await dashboard.flush();
  assert.equal(reads, 2);
  assert.equal(dashboard.loadedModelRows(), 1);
});

test('Load model calls retries a failed summary before requesting its first page', async () => {
  let summaries = 0;
  const dashboard = startOperations({
    storedAdmin: 'admin-secret',
    modelSummary: () => ++summaries === 1 ? 'error' : modelSummaryFixture(),
    modelCalls: modelCallsPageFixture(1),
  });
  await dashboard.openModels();
  assert.equal(summaries, 1);
  assert.match(dashboard.text('modelStatus'), /Unable to load model summary/);
  assert.equal(dashboard.text('loadModels'), 'Retry model summary');

  dashboard.click('loadModels');
  await dashboard.flush();
  assert.equal(summaries, 2);
  assert.equal(dashboard.requests.filter((request) => new URL(request.url).pathname.endsWith('/model-runs')).length, 1);
  assert.equal(dashboard.loadedModelRows(), 1);
});

test('a model feed cannot use its previous window while a refreshed summary is pending', async () => {
  const first = modelSummaryFixture();
  first.window = { from: '2026-10-01T00:00:00Z', to: '2026-10-02T00:00:00Z' };
  const refreshed = modelSummaryFixture();
  refreshed.window = { from: '2026-10-03T00:00:00Z', to: '2026-10-04T00:00:00Z' };
  const dashboard = startOperations({
    storedAdmin: 'admin-secret', deferModelSummary: true, deferModelCalls: true,
  });
  await dashboard.openModels();
  dashboard.resolveModelSummary(0, first);
  await dashboard.flush();
  dashboard.resolveModelCalls(0, modelCallsPageFixture(1, { nextCursor: 'older-page' }));
  await dashboard.flush();
  assert.equal(dashboard.element('loadModels').disabled, false);

  dashboard.click('refresh');
  await dashboard.flush();
  assert.equal(dashboard.element('loadModels').disabled, true);
  dashboard.click('loadModels');
  await dashboard.flush();
  assert.equal(dashboard.requests.filter((request) => new URL(request.url).pathname.endsWith('/model-runs')).length, 1);

  dashboard.resolveModelSummary(1, refreshed);
  await dashboard.flush();
  const pages = dashboard.requests.filter((request) => new URL(request.url).pathname.endsWith('/model-runs'));
  assert.equal(pages.length, 2);
  assert.equal(new URL(pages[1].url).searchParams.get('from'), refreshed.window.from);
  dashboard.resolveModelCalls(1, modelCallsPageFixture(1));
  await dashboard.flush();
});

test('model detail uses the backend not-found error code', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret', modelDetail: 'error', modelCalls: modelCallsPageFixture(0) });
  await dashboard.openModels('?view=models&modelRunId=' + MODEL_CALL);
  assert.equal(dashboard.text('modelDetailStatus'), 'This recorded model call was not found.');
});

test('manual pipeline execution is never automatically repeated', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret', deferPipeline: true });
  await dashboard.openPipeline();
  dashboard.click('runNow');
  dashboard.click('runNow');
  dashboard.tick();
  await dashboard.refresh();
  await dashboard.openOverview();
  assert.equal(dashboard.requests.filter((request) => request.method === 'POST').length, 1);
});

test('Pipeline feeds preserve filters, use one resolved window, and paginate by cursor', async () => {
  const run = operationRunFixture(operationRunIdFixture, {
    active: true, status: 'RUNNING', phase: 'PROCESSING', captureComplete: false, finishedAt: null, durationMs: null,
  });
  const nextRunId = '12121212-1212-4121-8121-121212121212';
  const ingestion = ingestionRunFixture(ingestionRunIdFixture);
  const nextIngestionId = '13131313-1313-4131-8131-131313131313';
  const dashboard = startOperations({
    storedAdmin: 'admin-secret',
    operationRuns: (url) => url.searchParams.has('cursor')
      ? operationRunsPageFixture(2, { items: [run, operationRunFixture(nextRunId)], nextCursor: null })
      : operationRunsPageFixture(1, { items: [run], nextCursor: 'older-cycle' }),
    ingestionRuns: (url) => url.searchParams.has('cursor')
      ? ingestionRunsPageFixture(2, { items: [ingestion, ingestionRunFixture(nextIngestionId)], nextCursor: null })
      : ingestionRunsPageFixture(1, { items: [ingestion], nextCursor: 'older-ingestion' }),
  });
  await dashboard.openPipeline('?view=pipeline&range=7d&status=PARTIAL&ingestionProvider=finnhub&ingestionStatus=FAILED');

  const runPage = dashboard.requests.find((request) => new URL(request.url).pathname.endsWith('/operations/runs'));
  const ingestionPage = dashboard.requests.find((request) => new URL(request.url).pathname.endsWith('/operations/ingestion-runs'));
  const runQuery = new URL(runPage.url).searchParams;
  const ingestionQuery = new URL(ingestionPage.url).searchParams;
  assert.equal(runQuery.get('range'), '7d');
  assert.equal(runQuery.get('kind'), 'PIPELINE');
  assert.equal(runQuery.get('status'), 'PARTIAL');
  assert.equal(runQuery.get('limit'), '25');
  assert.equal(runPage.headers['X-Admin-Key'], 'admin-secret');
  assert.equal(runPage.headers.Authorization, undefined);
  assert.equal(ingestionQuery.get('from'), operationRunsPageFixture(0).window.from);
  assert.equal(ingestionQuery.get('to'), operationRunsPageFixture(0).window.to);
  assert.equal(ingestionQuery.has('range'), false);
  assert.equal(ingestionQuery.get('provider'), 'finnhub');
  assert.equal(ingestionQuery.get('status'), 'FAILED');
  assert.equal(ingestionPage.headers['X-Admin-Key'], 'admin-secret');
  assert.match(dashboard.html('runRows'), /Recorded so far/);
  assert.equal(dashboard.element('runNow').disabled, true);
  assert.equal(dashboard.element('pipelineTabCycles').getAttribute('aria-selected'), 'true');

  dashboard.click('loadRuns');
  dashboard.click('loadIngestion');
  await dashboard.flush();
  const runPages = dashboard.requests.filter((request) => new URL(request.url).pathname.endsWith('/operations/runs'));
  const ingestionPages = dashboard.requests.filter((request) => new URL(request.url).pathname.endsWith('/operations/ingestion-runs'));
  assert.equal(new URL(runPages[1].url).searchParams.get('cursor'), 'older-cycle');
  assert.equal(new URL(runPages[1].url).searchParams.get('from'), operationRunsPageFixture(0).window.from);
  assert.equal(new URL(ingestionPages[1].url).searchParams.get('cursor'), 'older-ingestion');
  assert.equal((dashboard.html('runRows').match(/data-run-id=/g) || []).length, 2);
  assert.equal((dashboard.html('ingestionRows').match(/data-ingestion-select-id=/g) || []).length, 2);
});

test('failed first Pipeline and ingestion pages can be retried in place', async () => {
  let runReads = 0;
  let ingestionReads = 0;
  const dashboard = startOperations({
    storedAdmin: 'admin-secret',
    operationRuns: () => ++runReads === 1 ? 'error' : operationRunsPageFixture(1),
    ingestionRuns: () => ++ingestionReads === 1 ? 'error' : ingestionRunsPageFixture(1),
  });
  await dashboard.openPipeline();
  assert.equal(dashboard.text('loadRuns'), 'Retry pipeline history');
  assert.equal(dashboard.text('loadIngestion'), 'Retry ingestion history');
  dashboard.click('loadRuns');
  dashboard.click('loadIngestion');
  await dashboard.flush();
  assert.equal(runReads, 2);
  assert.equal(ingestionReads, 2);
  assert.equal((dashboard.html('runRows').match(/data-run-id=/g) || []).length, 1);
  assert.equal((dashboard.html('ingestionRows').match(/data-ingestion-select-id=/g) || []).length, 1);
});

test('a deep-linked unfinished pipeline run loads detail and paginated issues by ID', async () => {
  const runId = '14141414-1414-4141-8141-141414141414';
  const documentId = '15151515-1515-4151-8151-151515151515';
  const issue = operationIssuesPageFixture(1, { items: [Object.assign({}, operationIssuesPageFixture().items[0], {
    runId, documentId, errorMessage: '<script>private</script>',
  })] });
  const detail = operationRunDetailFixture(runId, { run: operationRunFixture(runId, {
    active: false, status: 'RUNNING', phase: 'PROCESSING', captureComplete: false, finishedAt: null, durationMs: null,
    errorCode: 'EXTRACTION_FAILED', errorMessage: '<script>private</script>',
  }), phases: [{ phase: 'PROCESSING', startedAt: '2026-10-04T11:00:00Z', finishedAt: null, durationMs: null }] });
  const dashboard = startOperations({
    storedAdmin: 'admin-secret', operationRuns: operationRunsPageFixture(0),
    operationRunDetail: detail, operationIssues: issue,
  });
  await dashboard.openPipeline('?view=pipeline&runId=' + runId);

  assert.ok(dashboard.requests.some((request) => new URL(request.url).pathname.endsWith('/runs/' + runId)));
  assert.ok(dashboard.requests.some((request) => new URL(request.url).pathname.endsWith('/runs/' + runId + '/issues')));
  assert.match(dashboard.html('runDetailContent'), /Unfinished/);
  assert.match(dashboard.html('runDetailContent'), /Not finished/);
  assert.match(dashboard.html('runDetailContent'), /&lt;script&gt;private&lt;\/script&gt;/);
  assert.doesNotMatch(dashboard.html('runDetailContent'), /<script>/);
  assert.match(dashboard.html('runIssues'), /EXTRACTION_FAILED/);
  assert.match(dashboard.html('runIssues'), new RegExp('data-run-document-id="' + documentId + '"'));
  assert.doesNotMatch(dashboard.html('runDetailContent'), /%/);

  dashboard.click('runDetail', { target: { closest(selector) {
    return selector === '[data-run-document-id]' ? { dataset: { runDocumentId: documentId } } : null;
  } } });
  assert.equal(dashboard.window.location.search, '?view=documents&runId=' + runId + '&documentId=' + documentId);
  assert.equal(dashboard.historyState.returnSearch, '?view=pipeline&runId=' + runId);
});

test('cycle document links scope the feed without selecting the cycle as a document', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret' });
  await dashboard.openPipeline('?view=pipeline&runId=' + operationRunIdFixture);
  const attribute = dashboard.html('runDetailContent').match(/data-([a-z-]+)="[^"]+">Inspect documents for this cycle/)[1];
  dashboard.click('runDetail', { target: { closest(selector) {
    if (selector !== '[data-' + attribute + ']') return null;
    return { dataset: { runDocumentId: operationRunIdFixture, cycleDocumentsId: operationRunIdFixture } };
  } } });
  assert.equal(dashboard.window.location.search, '?view=documents&runId=' + operationRunIdFixture);
});

test('issue document navigation bubbles once through the run detail', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret' });
  await dashboard.openPipeline('?view=pipeline&runId=' + operationRunIdFixture);
  const event = { target: { closest(selector) {
    return selector === '[data-run-document-id]' ? { dataset: { runDocumentId: DOC_A } } : null;
  } } };
  const before = dashboard.historyEntries.length;
  // Dispatch along the native bubbling path: nested issues, then its detail parent.
  dashboard.click('runIssues', event);
  dashboard.click('runDetail', event);
  assert.equal(dashboard.historyEntries.length - before, 1);
  assert.equal(dashboard.window.location.search, '?view=documents&runId=' + operationRunIdFixture + '&documentId=' + DOC_A);
});

test('selected cycles load bounded ingestion associations with their recorded run ID', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret',
    ingestionRuns: (url) => url.searchParams.get('runId') === operationRunIdFixture
      ? ingestionRunsPageFixture(1, { items: [ingestionRunFixture(ingestionRunIdFixture)] }) : ingestionRunsPageFixture(0),
  });
  await dashboard.openPipeline('?view=pipeline&runId=' + operationRunIdFixture);
  const associated = dashboard.requests.find((request) => new URL(request.url).pathname.endsWith('/ingestion-runs') &&
    new URL(request.url).searchParams.get('runId') === operationRunIdFixture);
  assert.ok(associated, 'Selected run must request its recorded ingestion associations');
  assert.equal(new URL(associated.url).searchParams.get('limit'), '25');
  assert.equal(new URL(associated.url).searchParams.has('range'), false);
  assert.match(dashboard.html('runIngestionRows'), new RegExp('data-ingestion-select-id="' + ingestionRunIdFixture + '"'));
  dashboard.click('runDetail', { target: { closest(selector) {
    return selector === '[data-ingestion-select-id]' ? { dataset: { ingestionSelectId: ingestionRunIdFixture } } : null;
  } } });
  assert.equal(new URLSearchParams(dashboard.window.location.search).get('ingestionRunId'), ingestionRunIdFixture);
});

test('known active pipelines survive display filters until their own inactive record is read', async () => {
  const active = operationRunFixture(operationRunIdFixture, { active: true, status: 'RUNNING', finishedAt: null });
  const dashboard = startOperations({ storedAdmin: 'admin-secret',
    operationRuns: (url) => url.searchParams.get('kind') === 'PIPELINE' && !url.searchParams.has('status')
      ? operationRunsPageFixture(1, { items: [active] }) : operationRunsPageFixture(0),
    operationRunDetail: operationRunDetailFixture(operationRunIdFixture),
  });
  await dashboard.openPipeline();
  assert.equal(dashboard.element('runNow').disabled, true);
  await dashboard.openPipeline('?view=pipeline&status=SUCCESS');
  assert.equal(dashboard.element('runNow').disabled, true);
  await dashboard.openPipeline('?view=pipeline&kind=DAILY_SNAPSHOTS');
  assert.equal(dashboard.element('runNow').disabled, true);
  await dashboard.openPipeline('?view=pipeline&kind=DAILY_SNAPSHOTS&runId=' + operationRunIdFixture);
  assert.equal(dashboard.element('runNow').disabled, false);
});

test('cycle ingestion paging preserves its run association and retries a failed cursor', async () => {
  let continuations = 0;
  const secondId = 'abababab-abab-4bab-8bab-abababababab';
  const dashboard = startOperations({ storedAdmin: 'admin-secret', ingestionRuns: (url) => {
    if (!url.searchParams.has('runId')) return ingestionRunsPageFixture(0);
    if (!url.searchParams.has('cursor')) return ingestionRunsPageFixture(1, {
      items: [ingestionRunFixture(ingestionRunIdFixture)], nextCursor: 'cycle-ingestion-cursor',
    });
    return ++continuations === 1 ? 'error' : ingestionRunsPageFixture(1, { items: [ingestionRunFixture(secondId)] });
  } });
  await dashboard.openPipeline('?view=pipeline&runId=' + operationRunIdFixture);
  dashboard.click('loadRunIngestion');
  await dashboard.flush();
  assert.match(dashboard.html('runIngestionRows'), new RegExp(ingestionRunIdFixture));
  assert.equal(dashboard.text('loadRunIngestion'), 'Retry cycle ingestion runs');
  dashboard.click('loadRunIngestion');
  await dashboard.flush();
  assert.match(dashboard.html('runIngestionRows'), new RegExp(secondId));
  const reads = dashboard.requests.filter((request) => new URL(request.url).pathname.endsWith('/ingestion-runs') &&
    new URL(request.url).searchParams.has('cursor'));
  assert.equal(reads.length, 2);
  for (const read of reads) {
    const query = new URL(read.url).searchParams;
    assert.equal(query.get('runId'), operationRunIdFixture);
    assert.equal(query.get('cursor'), 'cycle-ingestion-cursor');
    assert.equal(query.has('from'), false);
    assert.equal(query.has('provider'), false);
  }
});

test('terminal cycle outcome remains distinct from incomplete captured counts', async () => {
  const cancelled = operationRunFixture(operationRunIdFixture, {
    status: 'CANCELLED', captureComplete: false, finishedAt: '2026-10-04T11:01:00Z', durationMs: null,
  });
  const dashboard = startOperations({ storedAdmin: 'admin-secret',
    operationRuns: operationRunsPageFixture(1, { items: [cancelled] }),
    operationRunDetail: operationRunDetailFixture(operationRunIdFixture, { run: cancelled }),
  });
  await dashboard.openPipeline('?view=pipeline&runId=' + operationRunIdFixture);

  assert.match(dashboard.html('runRows'), /CANCELLED/);
  assert.match(dashboard.html('runRows'), /Recorded so far/);
  assert.match(dashboard.html('runDetailContent'), /Status<\/dt><dd>CANCELLED/);
  assert.match(dashboard.html('runDetailContent'), /Capture<\/dt><dd>Recorded so far/);
});

test('attempt ledger presents running and interrupted outcomes explicitly', async () => {
  const support = require('./test-support');
  const attempts = { ...support.documentAttemptsPageFixture, items: [
    { ...support.documentAttemptsPageFixture.items[0], status: 'RUNNING' },
    { ...support.documentAttemptsPageFixture.items[0], id: 'abababab-abab-4bab-8bab-abababababab', number: 3, status: 'INTERRUPTED' },
  ] };
  const dashboard = startOperations({ storedAdmin: 'admin-secret', documentAttempts: attempts });
  await dashboard.openDocument(DOC_A, 'attempts');

  assert.match(dashboard.html('documentAttempts'), /Attempt 2 · Running/);
  assert.match(dashboard.html('documentAttempts'), /Attempt 3 · Interrupted/);
  assert.doesNotMatch(dashboard.html('documentAttempts'), /Unknown/);
});

test('unfinished ingestion rows disclose that no final outcome was recorded', async () => {
  const running = ingestionRunFixture(ingestionRunIdFixture, {
    status: 'RUNNING', finishedAt: null, durationMs: null, runId: operationRunIdFixture,
  });
  const dashboard = startOperations({ storedAdmin: 'admin-secret',
    ingestionRuns: (url) => ingestionRunsPageFixture(1, { items: [running], window: url.searchParams.has('runId') ? null : operationWindowFixture }),
  });
  await dashboard.openPipeline('?view=pipeline&runId=' + operationRunIdFixture);

  assert.match(dashboard.html('ingestionRows'), /No final outcome recorded/);
  assert.match(dashboard.html('runIngestionRows'), /No final outcome recorded/);
  dashboard.click('ingestionRows', { target: { closest(selector) {
    return selector === '[data-ingestion-select-id]' ? { dataset: { ingestionSelectId: ingestionRunIdFixture } } : null;
  } } });
  await dashboard.flush();
  assert.match(dashboard.html('ingestionDetailContent'), /Status<\/dt><dd>No final outcome recorded/);
});

test('document detail reads do not advance freshness when the primary queue refresh fails', async () => {
  const support = require('./test-support');
  let failDocumentFeed = false;
  let detailAt = '2026-10-04T12:10:00Z';
  let attemptsAt = '2026-10-04T12:11:00Z';
  const attemptsPage = () => ({ ...support.documentAttemptsPageFixture, generatedAt: attemptsAt });
  const dashboard = startOperations({ storedAdmin: 'admin-secret',
    documents: () => failDocumentFeed ? 'error' : { ...support.documentsPageFixture, generatedAt: '2026-10-04T12:05:00Z' },
    documentDetail: (_url, id) => support.documentDetailFixture(id, { generatedAt: detailAt }),
    documentAttempts: attemptsPage,
  });
  await dashboard.openDocument(DOC_A, 'attempts');
  const before = dashboard.text('lastRefresh');
  failDocumentFeed = true;
  detailAt = '2026-10-04T13:10:00Z';
  attemptsAt = '2026-10-04T13:11:00Z';
  await dashboard.refresh();

  assert.match(dashboard.text('documentStatus'), /Unable to load documents/);
  assert.equal(dashboard.text('lastRefresh'), before);
});

test('Pipeline navigation preserves in-memory filters when returning from another screen', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret' });
  await dashboard.openPipeline('?view=pipeline&range=7d&kind=DAILY_SNAPSHOTS&status=PARTIAL&ingestionProvider=finnhub&ingestionStatus=FAILED');
  dashboard.click('navOverview');
  await dashboard.flush();
  dashboard.click('navPipeline');
  await dashboard.flush();

  const runRequest = dashboard.requests.filter((request) => new URL(request.url).pathname.endsWith('/operations/runs')).at(-1);
  const ingestionRequest = dashboard.requests.filter((request) => new URL(request.url).pathname.endsWith('/operations/ingestion-runs')).at(-1);
  const runQuery = new URL(runRequest.url).searchParams;
  const ingestionQuery = new URL(ingestionRequest.url).searchParams;
  assert.equal(runQuery.get('range'), '7d');
  assert.equal(dashboard.element('activityRange').value, '7d');
  assert.equal(runQuery.get('kind'), 'DAILY_SNAPSHOTS');
  assert.equal(runQuery.get('status'), 'PARTIAL');
  assert.equal(ingestionQuery.get('provider'), 'finnhub');
  assert.equal(ingestionQuery.get('status'), 'FAILED');
});

test('cycle ingestion resumes an interrupted first read when polling is disabled', async () => {
  let reads = 0;
  let release;
  const dashboard = startOperations({ storedAdmin: 'admin-secret', ingestionRuns: (url) => {
    if (!url.searchParams.has('runId')) return ingestionRunsPageFixture(0);
    if (++reads === 1) return new Promise((resolve) => { release = resolve; });
    return ingestionRunsPageFixture(1, { items: [ingestionRunFixture(ingestionRunIdFixture)] });
  } });
  await dashboard.openPipeline('?view=pipeline&runId=' + operationRunIdFixture);
  dashboard.element('autoRefresh').checked = false;
  dashboard.setHidden(true);
  release(ingestionRunsPageFixture(0));
  await dashboard.flush();
  dashboard.setHidden(false);
  await dashboard.flush();
  assert.equal(reads, 2);
  assert.match(dashboard.html('runIngestionRows'), new RegExp(ingestionRunIdFixture));
});

test('successful primary refresh advances freshness while retaining the pipeline POST outcome', async () => {
  let generatedAt = '2026-10-04T12:05:00Z';
  const dashboard = startOperations({ storedAdmin: 'admin-secret', deferPipeline: false,
    pipelineResult: { status: 'SKIPPED', alreadyRunning: true, runId: null },
    operationRuns: () => operationRunsPageFixture(0, { generatedAt }),
    ingestionRuns: () => ingestionRunsPageFixture(0, { generatedAt }),
  });
  await dashboard.openPipeline();
  dashboard.click('runNow');
  await dashboard.flush();
  const before = dashboard.text('lastRefresh');
  generatedAt = '2026-10-04T13:05:00Z';
  await dashboard.refresh();
  assert.equal(dashboard.text('pipelineStatus'), 'A pipeline cycle is already running');
  assert.notEqual(dashboard.text('lastRefresh'), before);
});

test('failed primary reads retain freshness even after a pipeline POST outcome', async () => {
  let failIngestion = false;
  let generatedAt = '2026-10-04T12:05:00Z';
  const dashboard = startOperations({ storedAdmin: 'admin-secret', deferPipeline: false,
    pipelineResult: { status: 'SKIPPED', alreadyRunning: true, runId: null },
    operationRuns: () => operationRunsPageFixture(0, { generatedAt }),
    ingestionRuns: () => failIngestion ? 'error' : ingestionRunsPageFixture(0, { generatedAt }),
  });
  await dashboard.openPipeline();
  dashboard.click('runNow');
  await dashboard.flush();
  const before = dashboard.text('lastRefresh');
  generatedAt = '2026-10-04T13:05:00Z';
  failIngestion = true;
  await dashboard.refresh();
  assert.equal(dashboard.text('pipelineStatus'), 'A pipeline cycle is already running');
  assert.equal(dashboard.text('lastRefresh'), before);
});

test('immutable source body remains cached across refresh and polling for the selected document', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret' });
  await dashboard.openDocument(DOC_A, 'source');
  const bodyReads = () => dashboard.requests.filter((request) => new URL(request.url).pathname.endsWith('/body')).length;
  const source = dashboard.html('documentSource');
  await dashboard.refresh();
  assert.equal(bodyReads(), 1);
  assert.equal(dashboard.html('documentSource'), source);
  dashboard.tick();
  await dashboard.flush();
  assert.equal(bodyReads(), 1);
  await dashboard.openDocument(DOC_B, 'source');
  assert.equal(bodyReads(), 2);
  dashboard.element('adminKey').value = 'replacement-admin';
  dashboard.click('saveKey');
  await dashboard.flush();
  assert.equal(bodyReads(), 3);
});

test('cycle issues keep loaded rows while a later page fails and retry the same cursor', async () => {
  const runId = '16161616-1616-4161-8161-161616161616';
  const first = operationIssuesPageFixture(1, { items: [Object.assign({}, operationIssuesPageFixture().items[0], { runId })], nextCursor: 'older-issue' });
  const second = operationIssuesPageFixture(1, { items: [Object.assign({}, operationIssuesPageFixture().items[0], {
    id: '17171717-1717-4171-8171-171717171717', runId,
  })] });
  let continuationReads = 0;
  const dashboard = startOperations({
    storedAdmin: 'admin-secret', operationRuns: operationRunsPageFixture(0),
    operationRunDetail: operationRunDetailFixture(runId),
    operationIssues: (url) => {
      if (!url.searchParams.has('cursor')) return first;
      return ++continuationReads === 1 ? 'error' : second;
    },
  });
  await dashboard.openPipeline('?view=pipeline&runId=' + runId);
  assert.match(dashboard.html('runIssues'), /EXTRACTION_FAILED/);

  dashboard.click('loadRunIssues');
  await dashboard.flush();
  assert.equal(dashboard.text('loadRunIssues'), 'Retry loading issues');
  assert.match(dashboard.html('runIssues'), /EXTRACTION_FAILED/);
  dashboard.click('loadRunIssues');
  await dashboard.flush();

  const issueRequests = dashboard.requests.filter((request) => new URL(request.url).pathname.endsWith('/runs/' + runId + '/issues'));
  assert.equal(new URL(issueRequests[1].url).searchParams.get('cursor'), 'older-issue');
  assert.equal(new URL(issueRequests[2].url).searchParams.get('cursor'), 'older-issue');
  assert.equal((dashboard.html('runIssues').match(/<li>/g) || []).length, 2);
});

test('selected ingestion lookup is exact, independent of feed filters, and links first-source documents', async () => {
  const ingestion = ingestionRunFixture(ingestionRunIdFixture, { runId: null });
  const dashboard = startOperations({
    storedAdmin: 'admin-secret', operationRuns: operationRunsPageFixture(0),
    ingestionRuns: (url) => url.searchParams.has('ingestionRunId')
      ? ingestionRunsPageFixture(1, { window: null, items: [ingestion] })
      : ingestionRunsPageFixture(0),
  });
  await dashboard.openPipeline('?view=pipeline&range=7d&ingestionProvider=finnhub&ingestionStatus=FAILED&ingestionRunId=' + ingestionRunIdFixture);

  const exact = dashboard.requests.find((request) => new URL(request.url).searchParams.has('ingestionRunId'));
  const exactQuery = new URL(exact.url).searchParams;
  assert.equal(exactQuery.get('ingestionRunId'), ingestionRunIdFixture);
  assert.equal(exactQuery.get('limit'), '25');
  for (const key of ['range', 'from', 'to', 'provider', 'status', 'runId', 'cursor']) assert.equal(exactQuery.has(key), false);
  assert.match(dashboard.html('ingestionDetailContent'), /First-source documents/);
  assert.doesNotMatch(dashboard.html('ingestionDetailContent'), /Parent operation/);

  dashboard.click('ingestionDetail', { target: { closest(selector) {
    return selector === '[data-ingestion-source-id]' ? { dataset: { ingestionSourceId: ingestionRunIdFixture } } : null;
  } } });
  assert.equal(dashboard.window.location.search, '?view=documents&ingestionRunId=' + ingestionRunIdFixture);
  assert.equal(dashboard.historyState.returnSearch, '?view=pipeline&range=7d&ingestionProvider=finnhub&ingestionStatus=FAILED&ingestionRunId=' + ingestionRunIdFixture);
});

test('an empty exact ingestion lookup reports that local record as not found', async () => {
  const dashboard = startOperations({
    storedAdmin: 'admin-secret', operationRuns: operationRunsPageFixture(0),
    ingestionRuns: (url) => url.searchParams.has('ingestionRunId')
      ? ingestionRunsPageFixture(0, { window: null }) : ingestionRunsPageFixture(0),
  });
  await dashboard.openPipeline('?view=pipeline&ingestionRunId=' + ingestionRunIdFixture);
  assert.equal(dashboard.text('ingestionDetailStatus'), 'Ingestion run not found.');
  assert.match(dashboard.requests.find((request) => new URL(request.url).searchParams.has('ingestionRunId')).url,
    new RegExp('ingestionRunId=' + ingestionRunIdFixture));
});

test('invalid Pipeline filter routes show validation and send no operational reads', async () => {
  for (const search of [
    '?view=pipeline&kind=UNKNOWN',
    '?view=pipeline&status=UNKNOWN',
    '?view=pipeline&ingestionProvider=UNKNOWN',
    '?view=pipeline&ingestionStatus=UNKNOWN',
    '?view=pipeline&runId=not-a-uuid',
  ]) {
    const dashboard = startOperations({ storedAdmin: 'admin-secret' });
    await dashboard.openPipeline(search);
    assert.match(dashboard.text('pipelineStatus'), /Choose|UUID/);
    assert.equal(dashboard.requests.some((request) => /^\/internal\/operations\/(runs|ingestion-runs|config)(\/|$)/
      .test(new URL(request.url).pathname)), false);
  }
});

test('empty daily history distinguishes a disabled scheduler from an enabled one', async () => {
  const outcomes = [];
  for (const enabled of [false, true]) {
    const config = require('./test-support').configFixture;
    const dashboard = startOperations({
      storedAdmin: 'admin-secret', operationRuns: operationRunsPageFixture(0),
      ingestionRuns: ingestionRunsPageFixture(0), config: { ...config, snapshots: { ...config.snapshots, enabled } },
    });
    await dashboard.openPipeline('?view=pipeline&kind=DAILY_SNAPSHOTS');
    outcomes.push(dashboard.html('runRows'));
  }
  assert.match(outcomes[0], /Daily snapshots are disabled\. No cycles are recorded\./);
  assert.match(outcomes[1], /No recorded cycles yet\./);
  assert.doesNotMatch(outcomes[1], /Daily snapshots are disabled/);
});

test('daily history labels unavailable scheduler state after its configuration read fails', async () => {
  const dashboard = startOperations({
    storedAdmin: 'admin-secret', operationRuns: operationRunsPageFixture(0),
    ingestionRuns: ingestionRunsPageFixture(0), config: 'error',
  });
  await dashboard.openPipeline('?view=pipeline&kind=DAILY_SNAPSHOTS');
  assert.match(dashboard.html('runRows'), /Scheduler state could not be verified/);
});

test('busy pipeline response refreshes reads without inventing a history row', async () => {
  const dashboard = startOperations({
    storedAdmin: 'admin-secret', deferPipeline: false,
    pipelineResult: { status: 'SKIPPED', alreadyRunning: true, runId: null },
    operationRuns: operationRunsPageFixture(0), ingestionRuns: ingestionRunsPageFixture(0),
  });
  await dashboard.openPipeline();
  dashboard.click('runNow');
  await dashboard.flush();
  assert.equal(dashboard.requests.filter((request) => request.method === 'POST').length, 1);
  assert.equal(dashboard.text('pipelineStatus'), 'A pipeline cycle is already running');
  assert.match(dashboard.html('runRows'), /No recorded cycles yet/);
});

test('unfinished inactive pipeline rows stay inspectable without blocking a new trigger', async () => {
  const run = operationRunFixture(operationRunIdFixture, {
    active: false, status: 'RUNNING', phase: 'PROCESSING', captureComplete: false, finishedAt: null, durationMs: null,
  });
  const dashboard = startOperations({
    storedAdmin: 'admin-secret', operationRuns: operationRunsPageFixture(1, { items: [run] }),
  });
  await dashboard.openPipeline();
  assert.match(dashboard.html('runRows'), /Unfinished/);
  assert.equal(dashboard.element('runNow').disabled, false);
});

test('successful manual result shows distinct partial counters without inventing a run link', async () => {
  const dashboard = startOperations({
    storedAdmin: 'admin-secret', deferPipeline: false,
    pipelineResult: { ...pipelineResultFixture, status: 'PARTIAL', documentsCompleted: 2,
      documentsSkipped: 1, documentsRetryScheduled: 1, documentsTerminalFailures: 1,
      eventsInserted: 2, eventsReused: 3, error: 'raw provider error', runId: null },
  });
  await dashboard.openPipeline();
  dashboard.click('runNow');
  await dashboard.flush();
  assert.match(dashboard.text('pipelineStatus'), /PARTIAL: 2 documents completed, 1 skipped, 1 retry scheduled, 1 terminal failures/);
  assert.match(dashboard.text('pipelineStatus'), /2 events inserted and 3 reused/);
  assert.doesNotMatch(dashboard.text('pipelineStatus'), /raw provider error/);
  assert.equal(new URLSearchParams(dashboard.window.location.search.slice(1)).has('runId'), false);
  assert.equal(dashboard.requests.filter((request) => request.method === 'POST').length, 1);
});

test('a credential change prevents a pending pipeline result from repainting or triggering another POST', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret', deferPipeline: true });
  await dashboard.openPipeline();
  dashboard.click('runNow');
  const post = dashboard.requests.find((request) => request.method === 'POST');
  assert.equal(post.headers['X-Admin-Key'], 'admin-secret');
  assert.equal(post.body, undefined);

  dashboard.element('adminKey').value = 'replacement-admin';
  dashboard.click('saveKey');
  await dashboard.flush();
  dashboard.completePipeline(pipelineResultFixture);
  await dashboard.flush();

  assert.equal(dashboard.requests.filter((request) => request.method === 'POST').length, 1);
  assert.doesNotMatch(dashboard.text('pipelineStatus'), /Pipeline SUCCESS/);
  assert.equal(dashboard.element('runNow').disabled, false);
});

test('pipeline connection failure is shown without retrying the POST', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret', deferPipeline: false, pipelineResult: 'error' });
  await dashboard.openPipeline();
  dashboard.click('runNow');
  await dashboard.flush();
  await dashboard.refresh();
  assert.equal(dashboard.requests.filter((request) => request.method === 'POST').length, 1);
  assert.match(dashboard.text('pipelineStatus'), /response was unavailable.*was not retried/);
});

test('leaving Models stops model summary and call reads', async () => {
  const dashboard = startOperations({ storedAdmin: 'admin-secret' });
  await dashboard.openModels();
  const modelReads = () => dashboard.requests.filter((request) => /\/model-(summary|runs)(\/|$)/.test(new URL(request.url).pathname)).length;
  const beforeNavigation = modelReads();
  await dashboard.openOverview();
  assert.equal(modelReads(), beforeNavigation);
});

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
